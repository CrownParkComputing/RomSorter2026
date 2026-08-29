plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.simplikfiwed.librarymanager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.simplikfiwed.librarymanager"
        minSdk = 28
        targetSdk = 35
        versionCode = 4
        versionName = "0.1.1"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("release-upload-key.jks")
            storePassword = "Brooklyn99$$"
            keyAlias = "upload"
            keyPassword = "Brooklyn99$$"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.compose.ui:ui:1.7.6")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.6")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling:1.7.6")
}

val cargoNdkBuild by tasks.registering(Exec::class) {
    workingDir = rootProject.projectDir.parentFile
    commandLine("cargo", "ndk", "-t", "arm64-v8a", "build", "--release", "--lib")
}

val copyRustJniLib by tasks.registering(Copy::class) {
    dependsOn(cargoNdkBuild)
    from(rootProject.projectDir.parentFile.resolve("target/aarch64-linux-android/release/libnscb.so"))
    into(projectDir.resolve("src/main/jniLibs/arm64-v8a"))
}

tasks.named("preBuild") {
    dependsOn(copyRustJniLib)
}
