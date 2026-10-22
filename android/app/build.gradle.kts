import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The release key lives beside the project and is never committed (`keystore.properties` is
// ignored), so builds on this machine are signed while the repo stays free of it.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// The emulator is a Rust cdylib, one per CPU family:
//   cd src/OpenGMK
//   cargo ndk -t arm64-v8a -t armeabi-v7a --platform 29 build -p gm8emulator --release
// arm64 is required. The 32-bit one is packed too when it was built, for the phones that still run
// 32-bit Android; a phone that can run arm64 always prefers it.
// Each is copied into jniLibs before packaging, refusing a missing or zeroed-out (unclean shutdown) .so.
val emulatorLibs = listOf(
    Triple("arm64-v8a", "aarch64-linux-android", true),
    Triple("armeabi-v7a", "armv7-linux-androideabi", false),
)
fun emulatorSoOf(triple: String) = rootProject.file("../src/OpenGMK/target/$triple/release/libgm8emulator.so")
val copyEmulatorSo by tasks.registering {
    for ((_, triple, _) in emulatorLibs) inputs.file(emulatorSoOf(triple)).withPropertyName("so-$triple").optional()
    outputs.dir(layout.projectDirectory.dir("src/main/jniLibs"))
    doLast {
        for ((abi, triple, required) in emulatorLibs) {
            val emulatorSo = emulatorSoOf(triple)
            val out = layout.projectDirectory.dir("src/main/jniLibs/$abi").asFile
            if (!emulatorSo.isFile) {
                if (required) error("falta ${emulatorSo}; compila con: cargo ndk -t $abi --platform 29 build -p gm8emulator --release")
                // Not built: make sure an old copy does not ride along in the package.
                out.resolve("libgm8emulator.so").delete()
                continue
            }
            val magic = emulatorSo.inputStream().use { it.readNBytes(4) }
            if (magic.size < 4 || magic[0] != 0x7F.toByte() || magic[1] != 'E'.code.toByte() || magic[2] != 'L'.code.toByte() || magic[3] != 'F'.code.toByte()) {
                error("${emulatorSo} no es un ELF valido (¿quedó a cero tras un corte?)")
            }
            out.mkdirs()
            emulatorSo.copyTo(out.resolve("libgm8emulator.so"), overwrite = true)
        }
    }
}

android {
    namespace = "com.ahjin.pr"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ahjin.pr"
        minSdk = 29
        targetSdk = 36
        versionCode = 25
        versionName = "0.7.4"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        create("release") {
            if (!keystoreProperties.isEmpty) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the project's own key when it is present, so the APK shipped as a release
            // is a real release build and not a debug one.
            signingConfig = if (!keystoreProperties.isEmpty) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging { jniLibs { useLegacyPackaging = false } }
}

tasks.named("preBuild") { dependsOn(copyEmulatorSo) }

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.compose.ui:ui:1.13.0-alpha01")
    implementation("androidx.compose.ui:ui-graphics:1.13.0-alpha01")
    implementation("androidx.compose.foundation:foundation:1.13.0-alpha01")
    implementation("androidx.compose.runtime:runtime:1.13.0-alpha01")
    implementation("androidx.compose.material3:material3:1.5.0-alpha28")
    implementation("androidx.compose.material:material-icons-extended:1.7.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    // The Android stubs of org.json do nothing in a plain JVM test, so the real library is used there.
    testImplementation("org.json:json:20240303")
}
