# nscb_rust

Rust implementation of core Nintendo Switch content workflows with native CLI, desktop GUI, and Android front ends.

Implemented operations:
- Merge (`--direct_multi`, `-d`)
- Verify container integrity (`--verify`)
- Rename files/folders using package metadata + NUTDB (`--renamef`)
- Split (`--splitter`)
- Split to repacked files (`--dspl`)
- Create/Repack NSP from folder (`--create` + `--ifolder`)
- Convert NSP/XCI (`--direct_creation`, `-c`)
- Compress NSP/XCI (`--compress`, `-z`)
- Decompress NSZ/XCZ/NCZ (`--decompress`)
- Content viewer (`--ADVcontentlist`)
- Metadata/file list (`--ADVfilelist`)
- Firmware controls on merge (`--RSVcap`, `--keypatch`, `--pv`)

## Requirements

- Rust toolchain (stable)
- `prod.keys` (or pass `--keys <path>`)

## Build

```bash
cargo build --release
```

Binary path:

```bash
target/release/nscb
```

## Desktop GUI

A native desktop GUI is available as a second binary. It uses the same Rust engine as the CLI and includes the Android app's library-management workflow: scan a game library, import folders, analyze package metadata, refresh TitlesDB, rename files, prepare merges, and remove duplicates or older versions.

Build and run in debug:

```bash
cargo run --bin nscb_gui
```

Build release GUI:

```bash
cargo build --release --bin nscb_gui
```

Run release GUI:

```bash
target/release/nscb_gui
```

Notes:
- The GUI invokes the sibling `nscb` CLI binary for long-running content operations.
- If you build release GUI, also build release CLI (`cargo build --release`) so `target/release/nscb` exists.
- The Library Scanner tab can bulk import files from another folder into the configured output/library folder using same-folder temporary files, then refresh the scanner after import.

## Android (Scaffold)

A starter Android app is included under `android/` with JNI bindings to Rust for:
- Merge
- File info summary
- Content summary

Android bridge file:
- `src/android_bridge.rs`

Important:
- `prod.keys` path is required for every Android operation.

Build Rust shared library for Android (arm64 example):

```bash
rustup target add aarch64-linux-android
cargo install cargo-ndk
cargo ndk -t arm64-v8a build --release
mkdir -p android/app/src/main/jniLibs/arm64-v8a
cp target/aarch64-linux-android/release/libnscb.so android/app/src/main/jniLibs/arm64-v8a/
```

Build Android debug APK:

```bash
cd android
./gradlew assembleDebug
```

See `android/README.md` for details.

## Windows EXE

### Download from GitHub Releases

- On tag push, GitHub Actions builds and publishes:
  - `nscb_rust.exe`
  - `nscb_rust-linux-amd64`
  - `nscb_rust-macos-arm64`
  - Trigger pattern: `v*` (example: `v0.1.0`)
- Workflow file:
  - `.github/workflows/release.yml`

### Local cross-build from Linux (optional)

```bash
rustup target add x86_64-pc-windows-gnu
sudo apt-get update && sudo apt-get install -y mingw-w64
cargo build --release --target x86_64-pc-windows-gnu
```

Output:

```bash
target/x86_64-pc-windows-gnu/release/nscb.exe
```

## Quick Help

```bash
target/release/nscb --help
```

## Common Options

- `--keys <path>`: path to `prod.keys`
- `-o, --ofolder <dir>`: output folder
- `-t, --type <nsp|xci>`: target type for convert/merge output mode
- `--level <1-22>`: compression level (default: `3`)
- `-n, --nodelta`: exclude delta NCAs during merge

## NUTDB Options

- `--nutdb-refresh`: refresh the local NUTDB cache
- `--nutdb-lookup <titleid>`: inspect a cached NUTDB entry
- `--nutdb-cache-dir <dir>`: override the NUTDB cache directory
- `--nutdb-url <url>`: override the NUTDB source URL

## Usage

### 1) Merge base/update/DLC

```bash
target/release/nscb \
  -d "base.nsp" "update.nsz" "dlc.nsp" \
  --keys /path/to/prod.keys \
  -o /path/to/output
```

### 2) Rename a file or folder using package metadata and NUTDB

```bash
target/release/nscb \
  --renamef "/path/to/library_or_file" \
  --renmode skip_corr_tid \
  --addlangue true \
  --noversion false \
  --dlcrname false \
  --keys /path/to/prod.keys
```

Behavior:
- renames supported files recursively: `.nsp`, `.nsx`, `.nsz`, `.xci`, `.xcz`
- uses package metadata first, then cached NUTDB names as fallback
- auto-refreshes the NUTDB cache on demand using conditional HTTP when supported
- keeps the established CLI names and accepted values for the main rename path:
  `--renamef <path>`
  `--renmode <force|skip_corr_tid|skip_if_tid>`
  `--addlangue <true|false>`
  `--noversion <false|true|xci_no_v0>`
  `--dlcrname <false|true|tag>`
- `--dlcrname tag` uses the established rename behavior:
  default `skip_corr_tid` uses an exact DLC NUTDB entry when present, otherwise falls back to
  `DLC <number>`; `--renmode force --dlcrname tag` keeps the resolved name and appends `[DLC <number>]`
- covered by Rust regression tests for rename output in the main modes:
  basic rename, `force`, `skip_corr_tid`, `skip_if_tid`, `addlangue`,
  `noversion=true`, `noversion=xci_no_v0`, `dlcrname=true`, `dlcrname=tag`,
  and `force + dlcrname=tag`
- appends ` (SeemsDuplicate)` when the target filename already exists
- appends ` (needscheck)` when a valid title name/title ID cannot be resolved

### 3) Refresh or inspect the NUTDB cache

```bash
target/release/nscb --nutdb-refresh
target/release/nscb --nutdb-lookup 0100F8F0000A2000
```

### 4) Split by title ID (CNMT-aware naming)

```bash
target/release/nscb \
  --splitter "merged.nsp_or_xci" \
  --keys /path/to/prod.keys \
  -o /path/to/split
```

Expected split output:
- Creates one folder per title group (base/update/DLC), not `.nsp` files.
- Folder names are title-aware, for example:
  - `Hollow Knight [0100633007D48000]`
  - `Hollow Knight [0100633007D48800][v458752][UPD]`
- Each folder contains extracted title content files, primarily `.nca`/`.ncz`.
- Tickets/certs are not guaranteed in split output; `--splitter` is designed for content grouping.

### 5) Create/Repack NSP from a split folder

```bash
target/release/nscb \
  --create "/path/to/repacked.nsp" \
  --ifolder "/path/to/split/Game Name [0100...000]" \
  --keys /path/to/prod.keys
```

Expected create behavior:
- Reads top-level files from `--ifolder`.
- Rebuilds a single `.nsp` with deterministic packing order.
- Typical workflow:
  - Split merged file with `--splitter`
  - Repack one split folder with `--create`

### 6) Split to per-title NSP/XCI files

```bash
target/release/nscb \
  --dspl "merged.xci" \
  --type nsp \
  --keys /path/to/prod.keys \
  -o /path/to/output
```

### 7) View detailed container contents

```bash
target/release/nscb \
  --ADVcontentlist "game.nsp_or_xci" \
  --keys /path/to/prod.keys
```

### 8) View title metadata summary

```bash
target/release/nscb \
  --ADVfilelist "game.nsp_or_xci" \
  --keys /path/to/prod.keys
```

### 9) Convert NSP -> XCI

```bash
target/release/nscb \
  --direct_creation "game.nsp" \
  --type xci \
  --keys /path/to/prod.keys \
  -o /path/to/output
```

### 10) Convert XCI -> NSP

```bash
target/release/nscb \
  --direct_creation "game.xci" \
  --type nsp \
  --keys /path/to/prod.keys \
  -o /path/to/output
```

### 11) Compress NSP -> NSZ (or XCI -> XCZ)

```bash
target/release/nscb \
  --compress "game.nsp" \
  --level 3 \
  --keys /path/to/prod.keys \
  -o /path/to/output
```

### 12) Decompress NSZ -> NSP (or XCZ -> XCI, NCZ -> NCA)

```bash
target/release/nscb \
  --decompress "game.nsz" \
  --keys /path/to/prod.keys \
  -o /path/to/output
```

### 13) Merge with firmware caps

```bash
target/release/nscb \
  -d "base.xci" "update.nsz" "dlc1.nsp" "dlc2.nsp" \
  --type xci \
  --RSVcap 0 \
  --keypatch 4 \
  --pv \
  --keys /path/to/prod.keys \
  -o /path/to/output
```

### 14) Verify NSP/XCI/NSZ/XCZ contents

```bash
target/release/nscb \
  --verify "game.nsp_or_xci_or_nsz" \
  --vertype full \
  --keys /path/to/prod.keys
```

Verify modes:
- `--vertype dec`: decryption test
- `--vertype sig`: signature test
- `--vertype full`: full flow including hash verification prompt

## Notes

- Progress bars are implemented for merge/decompress/convert operations, and also for compress/split.
- Split uses title-aware grouping and writes separate base/update/DLC folders.
- For large files, always use an output folder (`-o`) to avoid overwriting source content.
- If `--keys` is not set, the app also checks common default key locations.

## Testing

Run the native Rust test suite:

```bash
cargo test
```

Check the desktop GUI target:

```bash
cargo check --bin nscb_gui
```
