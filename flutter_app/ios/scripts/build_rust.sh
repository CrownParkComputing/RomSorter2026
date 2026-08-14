#!/bin/sh
# Builds the nscb Rust core as an iOS static library and drops it where
# ios/Flutter/Nscb.xcconfig expects it. Runs on any Mac with rustup
# (Xcode Cloud calls this from ci_post_clone.sh).
#
# Device-only (aarch64-apple-ios). The app is distributed through TestFlight,
# so no simulator slice is built; producing one would need an xcframework
# because device and Apple-silicon-simulator share the arm64 arch.
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
TARGET=aarch64-apple-ios

if ! command -v cargo >/dev/null 2>&1; then
  echo "--- installing Rust toolchain"
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal
  . "$HOME/.cargo/env"
fi

rustup target add "$TARGET"

echo "--- building libnscb.a for $TARGET"
cd "$REPO_ROOT"
cargo build --release --lib --target "$TARGET"

mkdir -p "$REPO_ROOT/flutter_app/ios/Frameworks"
cp "$REPO_ROOT/target/$TARGET/release/libnscb.a" \
   "$REPO_ROOT/flutter_app/ios/Frameworks/libnscb.a"
echo "--- libnscb.a ready: $(du -h "$REPO_ROOT/flutter_app/ios/Frameworks/libnscb.a" | cut -f1)"
