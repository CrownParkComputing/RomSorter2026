#!/bin/sh
# Xcode Cloud post-clone step (pattern proven in ViceMultiplatform).
#
# Xcode Cloud's images have Xcode and CocoaPods but no Flutter or Rust, and it
# does not run `flutter build` -- it invokes xcodebuild on the Runner scheme
# directly. That only works if Flutter has already generated
# ios/Flutter/Generated.xcconfig (the Runner's build phases depend on
# FLUTTER_ROOT from that file), so: install Flutter, resolve packages, and let
# `--config-only` write it. Unlike Vice, the native core here is pure Rust and
# is cross-compiled on the spot rather than committed as a binary.
#
# Apple runs this from the ci_scripts directory, which must sit next to the
# Xcode project -- hence ios/ci_scripts/ rather than the repo root.
set -e

# Pinned so cloud builds don't drift from local development for reasons
# unrelated to the commit being built.
FLUTTER_VERSION="${FLUTTER_VERSION:-3.41.9}"
FLUTTER_HOME="$HOME/flutter"

echo "--- installing Flutter $FLUTTER_VERSION"
git clone --depth 1 -b "$FLUTTER_VERSION" https://github.com/flutter/flutter.git "$FLUTTER_HOME"
export PATH="$FLUTTER_HOME/bin:$PATH"
flutter --version

echo "--- building the nscb Rust core"
sh "$CI_PRIMARY_REPOSITORY_PATH/flutter_app/ios/scripts/build_rust.sh"

APP_DIR="$CI_PRIMARY_REPOSITORY_PATH/flutter_app"
cd "$APP_DIR"

echo "--- resolving packages"
flutter precache --ios
flutter pub get

echo "--- generating the Xcode config Flutter's build phases rely on"
flutter build ios --release --no-codesign --config-only

echo "--- pod install"
cd ios
pod install

echo "--- ready for xcodebuild"
