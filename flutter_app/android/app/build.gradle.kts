plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.romsorter2026.rom_sorter"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.romsorter2026.rom_sorter"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

flutter {
    source = "../.."
}

// Same pattern as the Kotlin app in ../../android: build the Rust core with
// cargo-ndk and drop it into jniLibs before the Android build packs it.
val repoRoot = rootProject.projectDir.parentFile.parentFile

val cargoNdkBuild by tasks.registering(Exec::class) {
    workingDir = repoRoot
    commandLine("cargo", "ndk", "-t", "arm64-v8a", "build", "--release", "--lib")
}

val copyNscbLib by tasks.registering(Copy::class) {
    dependsOn(cargoNdkBuild)
    from(repoRoot.resolve("target/aarch64-linux-android/release/libnscb.so"))
    into(projectDir.resolve("src/main/jniLibs/arm64-v8a"))
}

tasks.named("preBuild") {
    dependsOn(copyNscbLib)
}
