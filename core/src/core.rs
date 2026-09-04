use std::io::{self, Read, Write};

use aes::Aes128;
use aes::cipher::{BlockDecrypt, KeyInit, generic_array::GenericArray};
use base64::Engine;
use serde::Deserialize;
use thiserror::Error;

const MAGIC_HEADER: &[u8; 8] = b"CTENFDAM";
const KEY_CORE: [u8; 16] = [
    0x68, 0x7a, 0x48, 0x52, 0x41, 0x6d, 0x73, 0x6f, 0x35, 0x6b, 0x49, 0x6e, 0x62, 0x61, 0x78, 0x57,
];
const KEY_META: [u8; 16] = [
    0x23, 0x31, 0x34, 0x6c, 0x6a, 0x6b, 0x5f, 0x21, 0x5c, 0x5d, 0x26, 0x30, 0x55, 0x3c, 0x27, 0x28,
];
const KEY_PREFIX_LEN: usize = 17;
const META_PREFIX_LEN: usize = 22;
const MAX_KEY_BYTES: usize = 1024 * 1024;
const MAX_META_BYTES: usize = 16 * 1024 * 1024;
const MAX_COVER_BYTES: usize = 32 * 1024 * 1024;
const COPY_BUFFER_BYTES: usize = 256 * 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NcmInfo {
    pub format: String,
    pub title: Option<String>,
    pub artists: Vec<String>,
    pub album: Option<String>,
    pub cover: Option<Vec<u8>>,
}

#[derive(Debug, Error)]
pub enum NcmError {
    #[error("I/O error: {0}")]
    Io(#[from] io::Error),
    #[error("invalid NCM file: {0}")]
    Invalid(&'static str),
    #[error("invalid NCM metadata: {0}")]
    Metadata(String),
}

#[derive(Debug, Default, Deserialize)]
struct RawMetadata {
    #[serde(default)]
    format: String,
    #[serde(rename = "musicName", default)]
    music_name: String,
    #[serde(default)]
    artist: Vec<Vec<serde_json::Value>>,
    #[serde(default)]
    album: String,
}

pub fn decrypt<R: Read, W: Write>(mut input: R, mut output: W) -> Result<NcmInfo, NcmError> {
    let mut magic = [0_u8; 8];
    input.read_exact(&mut magic)?;
    if &magic != MAGIC_HEADER {
        return Err(NcmError::Invalid("magic header mismatch"));
    }

    skip_exact(&mut input, 2)?;

    let key_len = checked_len(
        read_u32_le(&mut input)?,
        MAX_KEY_BYTES,
        "key data too large",
    )?;
    if key_len == 0 {
        return Err(NcmError::Invalid("empty key data"));
    }
    let mut key_data = read_vec(&mut input, key_len)?;
    key_data.iter_mut().for_each(|byte| *byte ^= 0x64);
    let key_plain = decrypt_aes_ecb_pkcs7(&key_data, &KEY_CORE)?;
    if key_plain.len() <= KEY_PREFIX_LEN {
        return Err(NcmError::Invalid("decrypted key is too short"));
    }
    let key_box = build_key_box(&key_plain[KEY_PREFIX_LEN..])?;

    let meta_len = checked_len(
        read_u32_le(&mut input)?,
        MAX_META_BYTES,
        "metadata too large",
    )?;
    let metadata = if meta_len == 0 {
        None
    } else {
        let meta_data = read_vec(&mut input, meta_len)?;
        // Metadata is not required to decrypt the audio payload. Some older
        // files contain damaged metadata but still have a valid stream key.
        decrypt_metadata(meta_data).ok()
    };

    skip_exact(&mut input, 5)?;
    let cover_space = checked_len(
        read_u32_le(&mut input)?,
        MAX_COVER_BYTES,
        "cover allocation too large",
    )?;
    let cover_len = checked_len(
        read_u32_le(&mut input)?,
        MAX_COVER_BYTES,
        "cover data too large",
    )?;
    if cover_len > cover_space {
        return Err(NcmError::Invalid("cover data exceeds its allocation"));
    }
    let cover = match cover_len {
        0 => None,
        len => Some(read_vec(&mut input, len)?),
    };
    skip_exact(&mut input, cover_space - cover_len)?;

    decrypt_audio(&mut input, &mut output, &key_box)?;
    output.flush()?;

    let metadata = metadata.unwrap_or_default();
    Ok(NcmInfo {
        format: sanitize_format(&metadata.format),
        title: non_blank(metadata.music_name),
        artists: metadata
            .artist
            .into_iter()
            .filter_map(|entry| {
                entry
                    .first()
                    .and_then(serde_json::Value::as_str)
                    .map(str::to_owned)
            })
            .filter(|name| !name.is_empty())
            .collect(),
        album: non_blank(metadata.album),
        cover,
    })
}

fn decrypt_metadata(mut data: Vec<u8>) -> Result<RawMetadata, NcmError> {
    if data.len() < META_PREFIX_LEN {
        return Err(NcmError::Invalid("metadata block is too short"));
    }
    data.drain(..META_PREFIX_LEN);
    data.iter_mut().for_each(|byte| *byte ^= 0x63);
    let encrypted = base64::engine::general_purpose::STANDARD
        .decode(data)
        .map_err(|error| NcmError::Metadata(format!("invalid base64: {error}")))?;
    let plain = decrypt_aes_ecb_pkcs7(&encrypted, &KEY_META)?;
    let json_start = plain
        .iter()
        .position(|byte| *byte == b':')
        .map(|index| index + 1)
        .ok_or_else(|| NcmError::Metadata("missing JSON prefix separator".into()))?;
    serde_json::from_slice(&plain[json_start..])
        .map_err(|error| NcmError::Metadata(format!("invalid JSON: {error}")))
}

fn decrypt_aes_ecb_pkcs7(data: &[u8], key: &[u8; 16]) -> Result<Vec<u8>, NcmError> {
    if data.is_empty() || !data.len().is_multiple_of(16) {
        return Err(NcmError::Invalid("AES data is not block aligned"));
    }
    let cipher = Aes128::new(GenericArray::from_slice(key));
    let mut plain = data.to_vec();
    for block in plain.chunks_exact_mut(16) {
        cipher.decrypt_block(GenericArray::from_mut_slice(block));
    }
    unpad_pkcs7(plain)
}

fn unpad_pkcs7(mut data: Vec<u8>) -> Result<Vec<u8>, NcmError> {
    let padding = data
        .last()
        .copied()
        .ok_or(NcmError::Invalid("missing PKCS#7 padding"))? as usize;
    if padding == 0 || padding > 16 || padding > data.len() {
        return Err(NcmError::Invalid("invalid PKCS#7 padding"));
    }
    if !data[data.len() - padding..]
        .iter()
        .all(|byte| *byte as usize == padding)
    {
        return Err(NcmError::Invalid("invalid PKCS#7 padding bytes"));
    }
    data.truncate(data.len() - padding);
    Ok(data)
}

fn build_key_box(key: &[u8]) -> Result<[u8; 256], NcmError> {
    if key.is_empty() {
        return Err(NcmError::Invalid("empty stream key"));
    }
    let mut box_values = [0_u8; 256];
    for (index, value) in box_values.iter_mut().enumerate() {
        *value = index as u8;
    }

    let mut j = 0_usize;
    for i in 0..256 {
        j = (box_values[i] as usize + j + key[i % key.len()] as usize) & 0xff;
        box_values.swap(i, j);
    }

    let mut key_box = [0_u8; 256];
    for (i, key) in key_box.iter_mut().enumerate() {
        let next = (i + 1) & 0xff;
        let first = box_values[next] as usize;
        let second = box_values[(next + first) & 0xff] as usize;
        *key = box_values[(first + second) & 0xff];
    }
    Ok(key_box)
}

fn decrypt_audio<R: Read, W: Write>(
    input: &mut R,
    output: &mut W,
    key_box: &[u8; 256],
) -> Result<(), NcmError> {
    let mut buffer = vec![0_u8; COPY_BUFFER_BYTES];
    let mut offset = 0_usize;
    loop {
        let count = input.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        for (index, byte) in buffer[..count].iter_mut().enumerate() {
            *byte ^= key_box[(offset + index) & 0xff];
        }
        output.write_all(&buffer[..count])?;
        offset = offset.wrapping_add(count);
    }
    Ok(())
}

fn read_u32_le<R: Read>(input: &mut R) -> Result<u32, NcmError> {
    let mut bytes = [0_u8; 4];
    input.read_exact(&mut bytes)?;
    Ok(u32::from_le_bytes(bytes))
}

fn checked_len(value: u32, max: usize, message: &'static str) -> Result<usize, NcmError> {
    let value = value as usize;
    if value > max {
        Err(NcmError::Invalid(message))
    } else {
        Ok(value)
    }
}

fn read_vec<R: Read>(input: &mut R, len: usize) -> Result<Vec<u8>, NcmError> {
    let mut data = vec![0_u8; len];
    input.read_exact(&mut data)?;
    Ok(data)
}

fn skip_exact<R: Read>(input: &mut R, mut len: usize) -> Result<(), NcmError> {
    let mut buffer = [0_u8; 8192];
    while len > 0 {
        let count = len.min(buffer.len());
        input.read_exact(&mut buffer[..count])?;
        len -= count;
    }
    Ok(())
}

fn sanitize_format(format: &str) -> String {
    let normalized = format.trim().to_ascii_lowercase();
    if !normalized.is_empty()
        && normalized.len() <= 10
        && normalized.bytes().all(|byte| byte.is_ascii_alphanumeric())
    {
        normalized
    } else {
        "mp3".to_owned()
    }
}

fn non_blank(value: String) -> Option<String> {
    if value.trim().is_empty() {
        None
    } else {
        Some(value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use aes::cipher::BlockEncrypt;
    use std::io::Cursor;

    #[test]
    fn decrypts_a_complete_ncm_stream_and_skips_cover_padding() {
        let audio = (0..700_000).map(|value| value as u8).collect::<Vec<_>>();
        let cover = vec![0xff, 0xd8, 0xff, 0xd9];
        let fixture = make_fixture(&audio, &cover, 12);
        let mut output = Vec::new();

        let info = decrypt(Cursor::new(fixture), &mut output).unwrap();

        assert_eq!(output, audio);
        assert_eq!(info.format, "flac");
        assert_eq!(info.title.as_deref(), Some("Test Song"));
        assert_eq!(info.artists, ["First Artist", "Second Artist"]);
        assert_eq!(info.album.as_deref(), Some("Test Album"));
        assert_eq!(info.cover.as_deref(), Some(cover.as_slice()));
    }

    #[test]
    fn rejects_an_invalid_magic_header() {
        let error = decrypt(Cursor::new(b"not-ncm!"), Vec::new()).unwrap_err();
        assert!(matches!(error, NcmError::Invalid("magic header mismatch")));
    }

    #[test]
    fn rejects_cover_length_larger_than_allocation() {
        let mut fixture = make_fixture(&[], &[], 0);
        let allocation_offset = find_cover_allocation_offset(&fixture);
        fixture[allocation_offset..allocation_offset + 4].copy_from_slice(&0_u32.to_le_bytes());
        fixture[allocation_offset + 4..allocation_offset + 8].copy_from_slice(&1_u32.to_le_bytes());
        let error = decrypt(Cursor::new(fixture), Vec::new()).unwrap_err();
        assert!(matches!(
            error,
            NcmError::Invalid("cover data exceeds its allocation")
        ));
    }

    #[test]
    fn decrypts_audio_when_optional_metadata_is_damaged() {
        let audio = b"audio still decrypts";
        let mut fixture = make_fixture(audio, &[], 0);
        let key_len = u32::from_le_bytes(fixture[10..14].try_into().unwrap()) as usize;
        let metadata_start = 14 + key_len + 4;
        fixture[metadata_start + META_PREFIX_LEN] = 0xff;
        let mut output = Vec::new();

        let info = decrypt(Cursor::new(fixture), &mut output).unwrap();

        assert_eq!(output, audio);
        assert_eq!(info.format, "mp3");
        assert_eq!(info.title, None);
    }

    fn make_fixture(audio: &[u8], cover: &[u8], cover_padding: usize) -> Vec<u8> {
        let stream_key = b"unit-test-stream-key";
        let mut key_plain = b"neteasecloudmusic".to_vec();
        key_plain.extend_from_slice(stream_key);
        let mut key_data = aes_encrypt_pkcs7(&key_plain, &KEY_CORE);
        key_data.iter_mut().for_each(|byte| *byte ^= 0x64);

        let json = br#"{"format":"FLAC","musicName":"Test Song","artist":[["First Artist",1],["Second Artist",2]],"album":"Test Album"}"#;
        let mut meta_plain = b"music:".to_vec();
        meta_plain.extend_from_slice(json);
        let encrypted_meta = aes_encrypt_pkcs7(&meta_plain, &KEY_META);
        let encoded_meta = base64::engine::general_purpose::STANDARD.encode(encrypted_meta);
        let mut meta_data = b"163 key(Don't modify):".to_vec();
        meta_data.extend(encoded_meta.bytes().map(|byte| byte ^ 0x63));

        let key_box = build_key_box(stream_key).unwrap();
        let encrypted_audio = audio
            .iter()
            .enumerate()
            .map(|(index, byte)| byte ^ key_box[index & 0xff])
            .collect::<Vec<_>>();

        let mut fixture = MAGIC_HEADER.to_vec();
        fixture.extend_from_slice(&[0_u8; 2]);
        fixture.extend_from_slice(&(key_data.len() as u32).to_le_bytes());
        fixture.extend_from_slice(&key_data);
        fixture.extend_from_slice(&(meta_data.len() as u32).to_le_bytes());
        fixture.extend_from_slice(&meta_data);
        fixture.extend_from_slice(&[0_u8; 5]);
        fixture.extend_from_slice(&((cover.len() + cover_padding) as u32).to_le_bytes());
        fixture.extend_from_slice(&(cover.len() as u32).to_le_bytes());
        fixture.extend_from_slice(cover);
        fixture.extend(std::iter::repeat_n(0_u8, cover_padding));
        fixture.extend_from_slice(&encrypted_audio);
        fixture
    }

    fn aes_encrypt_pkcs7(data: &[u8], key: &[u8; 16]) -> Vec<u8> {
        let padding = 16 - data.len() % 16;
        let mut encrypted = data.to_vec();
        encrypted.extend(std::iter::repeat_n(padding as u8, padding));
        let cipher = Aes128::new(GenericArray::from_slice(key));
        for block in encrypted.chunks_exact_mut(16) {
            cipher.encrypt_block(GenericArray::from_mut_slice(block));
        }
        encrypted
    }

    fn find_cover_allocation_offset(fixture: &[u8]) -> usize {
        let key_len = u32::from_le_bytes(fixture[10..14].try_into().unwrap()) as usize;
        let meta_len_offset = 14 + key_len;
        let meta_len = u32::from_le_bytes(
            fixture[meta_len_offset..meta_len_offset + 4]
                .try_into()
                .unwrap(),
        ) as usize;
        meta_len_offset + 4 + meta_len + 5
    }
}
