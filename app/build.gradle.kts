plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun stageWeight(stage: String): Int? = when (stage.lowercase()) {
    "alpha" -> 1
    "beta" -> 3
    "rc" -> 5
    "release" -> 9
    else -> null
}

fun parseTagToCode(tag: String): Int? {
    val match = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)(?:-([A-Za-z]+)\\.(\\d+))?$").matchEntire(tag) ?: return null
    val major = match.groupValues[1].toIntOrNull() ?: return null
    val minor = match.groupValues[2].toIntOrNull() ?: return null
    val patch = match.groupValues[3].toIntOrNull() ?: return null
    val stageName = match.groupValues[4].ifBlank { "release" }
    val sequence = match.groupValues[5].ifBlank { "0" }.toIntOrNull() ?: return null
    val stage = stageWeight(stageName) ?: return null
    return major * 10_000_000 + minor * 100_000 + patch * 1_000 + stage * 100 + sequence
}

fun runGit(project: org.gradle.api.Project, vararg args: String): String? = try {
    project.providers.exec {
        workingDir = project.rootProject.projectDir
        commandLine(*args)
    }.standardOutput.asText.get().trim().ifBlank { null }
} catch (_: Exception) {
    null
}

fun getGitVersionCode(project: org.gradle.api.Project): Int {
    val tags = runGit(project, "git", "tag", "--list", "v*")
        ?.lineSequence()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toList()
        .orEmpty()

    val bestTag = tags.mapNotNull { tag ->
        parseTagToCode(tag)?.let { code -> tag to code }
    }.maxByOrNull { (_, code) -> code } ?: return 1

    val distance = runGit(project, "git", "rev-list", "--count", "${bestTag.first}..HEAD")?.toIntOrNull() ?: return 1
    return bestTag.second + minOf(distance, 99)
}

fun getGitVersionName(project: org.gradle.api.Project): String {
    val script = project.rootProject.projectDir.resolve("git_version.sh")
    if (!script.exists()) {
        return "0.0.0-fallback"
    }
    return runGit(project, "bash", script.absolutePath) ?: "0.0.0-fallback"
}

android {
    namespace = "top.xihale.unncm"
    compileSdk = 34
    ndkVersion = "28.2.13676358"

    val releaseStoreFilePath = System.getenv("UNNCM_RELEASE_STORE_FILE") ?: "/home/xihale/.ssh/android/unncm.jks"
    val releaseStorePassword = System.getenv("UNNCM_RELEASE_STORE_PASSWORD") ?: ""
    val releaseKeyPassword = System.getenv("UNNCM_RELEASE_KEY_PASSWORD") ?: releaseStorePassword
    val releaseKeyAlias = System.getenv("UNNCM_RELEASE_KEY_ALIAS") ?: "unncm"

    defaultConfig {
        applicationId = "top.xihale.unncm"
        minSdk = 26
        targetSdk = 34
        versionCode = getGitVersionCode(project)
        versionName = getGitVersionName(project)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(releaseStoreFilePath)
            keyAlias = releaseKeyAlias
            storePassword = releaseStorePassword
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        viewBinding = true
    }
    lint {
        // lintVital stalls indefinitely on CI release builds; run lint explicitly when needed
        checkReleaseBuilds = false
    }

    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/rust/jniLibs"))
}

val rustJniLibsDir = layout.buildDirectory.dir("generated/rust/jniLibs")
val buildRustCore by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Rust NCM core for all packaged Android ABIs"

    val rustSources = rootProject.fileTree("core") {
        include("Cargo.toml", "Cargo.lock", "src/**")
        exclude("target/**")
    }
    inputs.files(rustSources)
    outputs.dir(rustJniLibsDir)

    workingDir(rootProject.file("core"))
    environment("ANDROID_NDK_HOME", android.sdkDirectory.resolve("ndk/${android.ndkVersion}"))
    commandLine(
        "cargo", "ndk",
        "--platform", android.defaultConfig.minSdk ?: 26,
        "--target", "armeabi-v7a",
        "--target", "arm64-v8a",
        "--output-dir", rustJniLibsDir.get().asFile,
        "build", "--release", "--locked"
    )
}

tasks.named("preBuild").configure {
    dependsOn(buildRustCore)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("net.jthink:jaudiotagger:3.0.1")
}
