# NSCB Android Scaffold

This folder contains a starter Android app focused on:
- Merge
- File info (`ADVfilelist` equivalent)
- Content summary (`ADVcontentlist` equivalent)

All operations require a valid `prod.keys` path.

## Rust JNI Bridge

JNI exports are implemented in:
- `src/android_bridge.rs`

They expose:
- `merge(inputsJoined, outputPath, keysPath, outputType)`
- `fileList(inputPath, keysPath)`
- `contentList(inputPath, keysPath)`

Each call enforces non-empty `keysPath` and loads keys using Rust `KeyStore`.

## Build Rust Library for Android

Example (arm64):

```bash
rustup target add aarch64-linux-android
cargo install cargo-ndk
cargo ndk -t arm64-v8a build --release
```

Expected output:
- `target/aarch64-linux-android/release/libnscb.so`

Copy to Android app JNI libs path:

```bash
mkdir -p android/app/src/main/jniLibs/arm64-v8a
cp target/aarch64-linux-android/release/libnscb.so android/app/src/main/jniLibs/arm64-v8a/
```

## Build Android App

```bash
cd android
./gradlew assembleDebug
```

## Notes

- The starter UI now includes Android document pickers for keys and input files.
- Merge output uses Android "Create Document" so users choose the final destination file.
- Selected SAF files are imported into app cache, then passed to Rust as local paths.
- `prod.keys` is mandatory in the UI and required by all bridge calls.
- Operations run in a background coroutine to keep UI responsive.
- Merge shows staged progress text (merging, exporting, completed/failed).
