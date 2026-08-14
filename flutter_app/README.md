# ROM Sorter — Flutter app (iOS + Android)

Flutter front end over the same `nscb` Rust core the Kotlin Android app uses.
The core is consumed through a plain C ABI (`src/ffi_bridge.rs`) via
`dart:ffi`; all bridge logic is shared with the JNI bridge in
`src/bridge_core.rs`.

## Layout

- `lib/src/native/nscb_bindings.dart` — raw dart:ffi bindings (15 symbols)
- `lib/src/native/nscb.dart` — async API (ops run on background isolates) + JSON models
- `lib/src/screens/` — Library / Tools / Logs / Settings
- `ios/Flutter/Nscb.xcconfig` — links `ios/Frameworks/libnscb.a` with `-force_load`
- `ios/scripts/build_rust.sh` — builds the iOS static lib (any Mac / Xcode Cloud)
- `ios/ci_scripts/ci_post_clone.sh` — Xcode Cloud setup: Flutter + Rust + pods

## File model on iOS

The app works entirely inside its Documents folder, which is visible in the
Files app (`UIFileSharingEnabled` + `LSSupportsOpeningDocumentsInPlace`).
Drop ROM files and `prod.keys` there; outputs are written back beside them.

## Android build (local test vehicle)

Requires `cargo-ndk` and the NDK. Gradle builds the Rust core automatically:

```bash
flutter build apk --release --target-platform android-arm64
```

## iOS via Xcode Cloud (internal TestFlight)

Everything is driven from the repo except the one-time onboarding, which must
be done in Xcode on a Mac (same procedure as ViceMultiplatform):

1. Open `flutter_app/ios/Runner.xcworkspace` in Xcode (run
   `flutter build ios --config-only` and `pod install` first so the workspace
   exists), sign into the team, set the bundle id (`com.romsorter2026.app` or
   as registered), and enable automatic signing.
2. Product → Xcode Cloud → Create Workflow. Point it at this repo/branch.
   The default Archive action is fine; `ci_post_clone.sh` handles Flutter,
   the Rust cross-compile, and `pod install`.
3. Set the workflow's post-action to TestFlight **internal** testing only —
   this app must not go to external testing or App Review.

After that, every push builds and lands on internal TestFlight with no Mac
involved.

## Host-side FFI verification

```bash
cargo build --lib      # repo root
cd flutter_app
NSCB_LIB_PATH=../target/debug/libnscb.so flutter test test/ffi_smoke_test.dart
```
