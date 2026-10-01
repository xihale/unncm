mod core;
mod probe;

pub use core::{NcmError, NcmInfo, decrypt};
pub use probe::probe_metadata;

use std::fs::File;
use std::io::BufReader;
use std::os::fd::FromRawFd;
use std::panic::{AssertUnwindSafe, catch_unwind};

use jni::JNIEnv;
use jni::objects::{JClass, JObject, JValue};
use jni::sys::{jint, jobject};

#[unsafe(no_mangle)]
pub extern "system" fn Java_top_xihale_unncm_NativeNcmCore_nativeDecrypt<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    input_fd: jint,
    output_fd: jint,
) -> jobject {
    let result = catch_unwind(AssertUnwindSafe(|| {
        decrypt_from_descriptors(&mut env, input_fd, output_fd)
    }));

    match result {
        Ok(Ok(object)) => object.into_raw(),
        Ok(Err(message)) => {
            let _ = env.throw_new("java/io/IOException", message);
            JObject::null().into_raw()
        }
        Err(_) => {
            let _ = env.throw_new("java/lang/IllegalStateException", "Rust NCM core panicked");
            JObject::null().into_raw()
        }
    }
}

/// 扫描探针：返回位掩码（1=有标题，2=有歌手，4=有封面），0 表示无法识别。
#[unsafe(no_mangle)]
pub extern "system" fn Java_top_xihale_unncm_NativeNcmCore_nativeProbeMetadata(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
) -> jint {
    let file = match duplicate_file(fd, "probe") {
        Ok(file) => file,
        Err(_) => return 0,
    };
    let mut reader = BufReader::new(file);
    probe_metadata(&mut reader) as jint
}

fn decrypt_from_descriptors<'local>(
    env: &mut JNIEnv<'local>,
    input_fd: jint,
    output_fd: jint,
) -> Result<JObject<'local>, String> {
    let input = duplicate_file(input_fd, "input")?;
    let output = duplicate_file(output_fd, "output")?;
    let info = decrypt(input, output).map_err(|error| error.to_string())?;
    make_java_info(env, info).map_err(|error| format!("could not return NCM metadata: {error}"))
}

fn duplicate_file(fd: jint, label: &str) -> Result<File, String> {
    if fd < 0 {
        return Err(format!("invalid {label} file descriptor"));
    }
    // SAF owns the descriptors passed from Kotlin. Duplicate them so Rust can
    // close its copies without changing the lifetime of ParcelFileDescriptor.
    let duplicated = unsafe { libc::dup(fd) };
    if duplicated < 0 {
        return Err(format!(
            "could not duplicate {label} file descriptor: {}",
            std::io::Error::last_os_error()
        ));
    }
    Ok(unsafe { File::from_raw_fd(duplicated) })
}

fn make_java_info<'local>(
    env: &mut JNIEnv<'local>,
    info: NcmInfo,
) -> jni::errors::Result<JObject<'local>> {
    let format = env.new_string(info.format)?;
    let title = optional_string(env, info.title)?;
    let album = optional_string(env, info.album)?;
    let string_class = env.find_class("java/lang/String")?;
    let artists =
        env.new_object_array(info.artists.len() as jint, string_class, JObject::null())?;
    for (index, artist) in info.artists.into_iter().enumerate() {
        let artist = env.new_string(artist)?;
        env.set_object_array_element(&artists, index as jint, artist)?;
    }
    let cover = match info.cover {
        Some(bytes) => JObject::from(env.byte_array_from_slice(&bytes)?),
        None => JObject::null(),
    };
    let artists = JObject::from(artists);
    let format = JObject::from(format);

    env.new_object(
        "top/xihale/unncm/NcmInfo",
        "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;[B)V",
        &[
            JValue::Object(&format),
            JValue::Object(&title),
            JValue::Object(&artists),
            JValue::Object(&album),
            JValue::Object(&cover),
        ],
    )
}

fn optional_string<'local>(
    env: &mut JNIEnv<'local>,
    value: Option<String>,
) -> jni::errors::Result<JObject<'local>> {
    value
        .map(|value| env.new_string(value).map(JObject::from))
        .transpose()
        .map(Option::unwrap_or_default)
}
