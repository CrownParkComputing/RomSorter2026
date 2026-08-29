# RomSorter2026 → Generic ROM Manager — Architecture Design

> **Status:** Draft for review — authored against the local `ios-flutter` branch (ahead of `origin/main`).
> **Goal:** Turn the Switch-specific `nscb` engine into a generic, multi-platform ROM manager:
> all archive formats (zip / 7z / rar / chd / unchd / ISO / BIN/CUE), all ROM platforms
> (Switch today; cartridge + disc systems next), Flutter GUI on iOS, Android, Linux, Windows (and macOS).

---

## 1. Executive summary

`nscb` is already a strong Switch content engine with a clean streaming I/O core. The Flutter
front end (iOS + Android) over a shared **C FFI bridge** is already in place on `ios-flutter`.
What is missing to become a *generic* ROM manager:

1. **A `Platform` abstraction** — today everything is Switch-shaped (extensions, prod.keys,
   NUTDB, NSP/XCI-specific ops). Generalize it so each console/ecosystem is a plug-in.
2. **An `ArchiveFormat` abstraction in Rust** — zip extraction currently lives in Dart
   (`flutter_app/lib/src/import.dart` using the `archive` package). Move all archive work into
   the Rust core so the CLI, desktop, Android and iOS all share one streaming implementation,
   and so 7z / rar / chd can be added in one place.
3. **Format backends** — verified crates exist today for every requested format (see §4).
   RAR and 7z need care; CHD has pure-Rust options.
4. **Desktop Flutter** — add Linux/Windows/macOS targets to `flutter_app/`; the egui
   `nscb_gui` binary can then be retired (kept meanwhile as a dev tool).

The migration is incremental: Switch behavior is preserved behind the new traits; nothing is
thrown away.

---

## 2. Current state (verified against `ios-flutter`)

### 2.1 Rust engine (`src/`)

| Area | Files | Notes |
|---|---|---|
| Formats | `formats/{pfs0,hfs0,nca,ncz,nsp,xci,cnmt,nacp,ticket,types}.rs` | Switch containers + crypto layout |
| Ops | `ops/{merge,split,dspl,create,convert,compress,decompress,verify,info}.rs` | Streaming, progress-aware |
| Metadata | `nutdb.rs` | blawar titledb cache (names/versions/release data) |
| Keys | `keys/{keystore,derivation}.rs` | prod.keys → AES/RSA title keys |
| I/O | `util/io.rs` | 1 MiB chunk streaming (`copy_with_progress`, `copy_section`), `u64` sizes — **already 12 GB+ safe** |
| Bridges | `ffi_bridge.rs`, `bridge_core.rs`, `android_bridge.rs` | C ABI (iOS staticlib + Android .so); JNI kept for legacy Android path |

`Cargo.toml` (branch `ios-flutter`): `crate-type = ["rlib", "cdylib", "staticlib"]`;
`jni` only under `target_os = "android"`; `eframe`/`rfd` only when not android/ios.

### 2.2 C FFI bridge

`src/ffi_bridge.rs` exports `nscb_*` functions; every call returns a heap C string
(`"OK: ..."` / `"ERROR: ..."`), freed with `nscb_string_free`. Wrapped in Dart by
`flutter_app/lib/src/native/nscb.dart` (with `Isolate.run` to keep the UI thread free).

### 2.3 Flutter app (`flutter_app/`)

- Screens: setup wizard, home, library, database (TitlesDB), tools, settings, logs.
- Deps: `ffi`, `archive`, `path_provider`, `shared_preferences`, `file_picker`, `permission_handler`.
- iOS: `ios/scripts/build_rust.sh` + `ci_scripts/ci_post_clone.sh`; **Android + iOS only** —
  no `linux/`, `windows/`, `macos/` yet.

### 2.4 Import pipeline today (`lib/src/import.dart`)

- ROM extensions hard-coded: `.nsp .nsz .xci .xcz`.
- Zip extraction: streamed via Dart `archive` (`ZipDecoder().decodeStream`), works for
  multi-GB zips.
- RAR: **counted and reported as unsupported** (`skipped N RAR file(s) (not supported yet)`).
- No 7z / CHD / disc-image handling.

### 2.5 Gaps this design closes

| Gap | Where | Fix |
|---|---|---|
| Extensions hard-coded | `import.dart`, `bridge_core.rs::scan_directory`, `android_bridge.rs` | Platform registry |
| Archive logic in Dart | `import.dart` | Move into Rust `ArchiveFormat` layer |
| No 7z / rar / chd / ISO | n/a | New Rust backends (§4) |
| prod.keys required for every op | `bridge_core.rs::require_keys` | Keys optional per platform |
| No desktop Flutter | n/a | `flutter create --platforms` + FFI loading per OS |
| egui GUI duplicates Flutter | `src/bin/nscb_gui.rs` | Retire once Flutter desktop ships |

---

## 3. Target architecture

```
┌────────────────────────── Flutter UI (flutter_app/) ──────────────────────────┐
│  screens: Library · Database · Tools · Settings · Logs · Setup               │
└───────────────▲───────────────────────────────┬──────────────────────────────┘
                │ dart:ffi (Isolate.run)        │
┌───────────────┴───────────────────────────────▼──────────────────────────────┐
│  FFI layer           src/ffi_bridge.rs  (nscb_* C ABI, stays as-is)          │
│  Core services       src/bridge_core.rs  (+ new: import, archive, platform)  │
├──────────────────────────────────────────────────────────────────────────────┤
│  GENERIC CORE (new modules)                                                   │
│    platform/    Platform trait · registry · GameMeta                         │
│    archive/     ArchiveFormat trait · zip · 7z · rar · chd · disc            │
│    meta/        MetadataProvider trait · NUTDB · No-Intro DAT · Redump DAT · local JSON │
│    pipeline/    import · scan · group · dedupe · rename · verify              │
├──────────────────────────────────────────────────────────────────────────────┤
│  Switch engine (existing, untouched semantics)                                │
│    formats/ · ops/ · keys/ · nutdb.rs · crypto/                               │
└──────────────────────────────────────────────────────────────────────────────┘
        platforms: Android (cdylib) · iOS (staticlib) · Linux (.so) · Windows (.dll) · macOS (.dylib)
```

### 3.1 `Platform` trait — one impl per console/ecosystem

```rust
// src/platform/mod.rs (new)
pub struct GameMeta {
    pub title: String,
    pub title_id: Option<String>, // Switch TID, DAT serial, disc barcode, …
    pub region: Option<String>,
    pub version: Option<String>,
    pub publisher: Option<String>,
}

#[derive(Clone, Copy, PartialEq)]
pub enum Op { Import, Rename, Verify, Archive, Compress, Merge }

pub trait Platform: Send + Sync {
    fn id(&self) -> &'static str;                  // "switch" | "gba" | "psx" | "snes" | ...
    fn name(&self) -> &'static str;
    fn rom_extensions(&self) -> &'static [&'static str];     // ".nsp",".nsz",".xci",".xcz"
    fn archive_extensions(&self) -> &'static [&'static str]; // ".zip",".7z",".rar" (shared default)
    fn requires_keys(&self) -> bool { false }      // Switch → true
    fn supports(&self, op: Op) -> bool { true }

    /// Read title/region/version from the file itself.
    fn read_metadata(&self, path: &Path, ctx: &MetadataCtx) -> Result<Option<GameMeta>>;
    /// Produce a library filename from metadata (Switch rules move here).
    fn suggest_filename(&self, meta: &GameMeta, opts: &RenameOpts) -> String;
}
```

**Switch platform** (`platform/switch.rs`): wraps today's `cli::collect_title_records`,
NUTDB lookup, `util::filename::sanitize_output_filename` and rename modes (`force`,
`skip_corr_tid`, `skip_if_tid`, `addlangue`, `noversion`, `dlcrname`) — **zero behavior
change**, just relocated.

> **Note — ambiguous extensions:** `.iso`, `.bin`, `.chd` (and `.zip`/`.7z`) are shared across
> many platforms, so extension-based dispatch cannot always pick the platform. Add a
> per-folder default platform + a settings-screen override ("treat this folder as PSX"),
> used before extension fallback. Archive files dispatch by *magic probe*, not extension
> (§3.2), so archives themselves are never ambiguous — only the extracted entry's platform.

### 3.2 `ArchiveFormat` trait — one impl per container

```rust
// src/archive/mod.rs (new)
pub struct ArchiveEntry { pub name: String, pub size: u64, pub is_dir: bool }

pub trait ArchiveFormat: Send + Sync {
    fn id(&self) -> &'static str;                          // "zip" | "7z" | "rar" | "chd"
    fn extensions(&self) -> &'static [&'static str];
    fn probe(&self, path: &Path) -> bool;                  // magic bytes
    fn list(&self, path: &Path) -> Result<Vec<ArchiveEntry>>;
    fn extract(&self, path: &Path, entries: &[String], dest: &Path,
               pb: Option<&ProgressBar>) -> Result<ExtractStats>; // streaming (see §5)
    fn create(&self, files: &[PathBuf], dest: &Path, opts: &CreateOpts,
              pb: Option<&ProgressBar>) -> Result<()> { Err(unsupported) } // optional
}

pub struct Registry { platforms: Vec<Box<dyn Platform>>, archives: Vec<Box<dyn ArchiveFormat>> }
impl Registry {
    pub fn platform_for(&self, p: &Path) -> Option<&dyn Platform>;
    pub fn archive_for(&self, p: &Path) -> Option<&dyn ArchiveFormat>; // probe → extension fallback
    pub fn all_rom_extensions(&self) -> HashSet<&'static str>;
}
```

Every `extract` implementation must stream through `util::io::copy_with_progress` with 1 MiB
buffers — the same path the Switch ops already use — so a 12 GB zip and a 12 GB NSZ behave
identically (bounded RAM, progress, cancel-friendly). `ExtractStats` should carry per-entry
verification results so corrupted entries are reported, not silently skipped.

### 3.3 Generic import pipeline (replaces `import.dart` logic in Rust)

```
walk(importDir)
  classify each file:
    rom      → platform.rom_extensions()            (registry-wide, not per-file)
    archive  → registry.archive_for() via probe()
    unknown  → skip (report)
for each archive:
    list() → filter entries whose extension is a known rom ext → extract(streamed) into library
for each loose rom:
    move/copy into library
after import: optional per-platform rename via read_metadata() + suggest_filename()
```

Exposed to Flutter as one new FFI call: `nscb_import_folder(import_dir, library_dir, keys_path,
cache_dir) -> summary-string` (same `"OK:"/"ERROR:"` contract). Dart `import.dart` shrinks to a
thin `Nscb.importFolder(...)` call; the `archive` Dart dependency is dropped.

---

## 4. Format coverage plan (crates verified on crates.io, 2026-08)

| Format | Crate | Pure Rust | Version | Mobile (Android/iOS) | Notes |
|---|---|---|---|---|---|
| ZIP (r/w) | `zip` | ✅ | 8.6.0 (2026-08) | ✅ | Zip64 + streaming; 251 M downloads, very active |
| 7z (extract) | `sevenz-rust` | ✅ | 0.6.1 (2024-07) | ✅ | Solid archives are sequential; see §4.2 for maintenance risk |
| RAR | `rar` | ✅ | 0.4.0 (2025-11) | ✅ | Young (9 k downloads) — **RAR5 coverage is the risk** |
| RAR (fallback) | `unrar` | ❌ C++ | 0.5.8 | ⚠️ cross-compile pain | Feature-gated fallback backend |
| CHD (r/w) | `chd` | ✅ | 0.3.4 (2026-03) | ✅ | `chd-capi` gives libchdr-compatible C API |
| CHD (MAME) | `libchdman-rs` | wrapper | 0.289 | ⚠️ | MAME chdman core, read+write CD/DVD/HD |
| Disc images | `opticaldiscs` | ✅ | 0.15.0 (2026-08) | ✅ | ISO / BIN/CUE / CHD filesystem browsing (read-only) |
| LZMA/XZ | `lzma-rs` / `lzma-sdk-rs` | ✅ | 0.3.0 / 0.2301 | ✅ | 7-zip LZMA SDK port is bit-exact |
| zstd (in use) | `zstd` | binding | 0.13.3 | ✅ | Already the NSZ codec |

### 4.1 ZIP — low risk, do first
`zip::ZipArchive` + `by_index(...).read_stream()` with 1 MiB chunks into `util::io` helpers.
Reads and writes, Zip64 for >4 GB. Verify each entry's CRC32 after extraction (the crate
exposes it) and report mismatches in `ExtractStats`. This replaces the Dart `archive` path 1:1.

### 4.2 7z — medium risk, extract-only initially
`sevenz-rust` decodes LZMA/LZMA2 solid streams; extracting one entry may require decompressing
preceding solid blocks (sequential by design). For import we only need *extraction of rom
entries*, so this is acceptable. Add a test matrix with a 7z made by 7-Zip (LZMA2) **and** one
made by macOS/BSD (compressed headers, different signatures).
**Maintenance risk:** last `sevenz-rust` release was 2024-07 (~2 years stale). Gate it behind a
feature flag and keep the swap path to `libarchive_oxide` (pure Rust) or `libarchive2` (C) if
it stalls. Creation: defer (solid-block writer is limited); use `lzma-sdk-rs` later if
compression is wanted.

### 4.3 RAR — highest risk, use a backend strategy
- Default: `rar` crate (pure Rust, no toolchain pain on mobile). Cover RAR4 solid + normal;
  test RAR5 thoroughly — if `rar` cannot read common RAR5 files, gate it.
- **Decision gate before P1 ships:** extract a corpus of real RAR4 *and* RAR5 files (solid,
  multi-volume, encrypted headers) and record pass/fail per category. If RAR5 fails, build the
  `unrar` C++ backend for Android/iOS up front rather than after the fact.
- Fallback: `unrar` (libunrar C++). Compiling the C++ unrar sources for
  `aarch64-linux-android` and `aarch64-apple-ios` is a build-pipeline commitment (NDK
  toolchain + Xcode); model it as a feature flag: `default = ["rar-pure"]`,
  `rar-unrar-cpp` for desktop builds where portability pain is low.
- Multi-part `.part1.rar`/`.r00` sets: `rar`/`unrar` both handle spanning; the importer must
  treat the whole set as one unit and prefer the first volume.

### 4.4 CHD / unCHD — pure Rust available
- **Read/unCHD**: `chd` crate reads v1–v5 CHDs (zlib, zstd, LZMA, FLAC, Huffman hunks).
  unCHD = extract the embedded raw ISO/BIN (CD) or drive image (HD) back out — stream hunk by
  hunk (`chd::open` → iterate hunks → write raw image). For CD images `opticaldiscs` can browse
  filesystems inside the CHD directly (read-only).
- **Write (ROM → CHD)**: `chd` supports creation from a raw image with hunk-size + compression
  choices (zstd is the modern default; FLAC for CDDA). Write support is younger than read —
  **validate parity:** create with `chd`, then verify with `chdman -verify` and an emulator.
  Wire as a Tools-screen op (`nscb_chd_create(input, output, hunk_size, compression)`).
- Keep `libchdman-rs` as an optional heavy backend if MAME parity is ever needed.

### 4.5 Disc images (ISO / BIN/CUE) — `opticaldiscs`
`opticaldiscs` reads ISO9660/Joliet/Rock Ridge, BIN/CUE and CHD, extracts files, and returns
disc metadata. Use for: verifying, extracting specific files (e.g. `.bin`→CHD workflows),
and as the metadata source (volume label often contains the title). Because `.iso`/`.bin` are
shared extensions, platform for disc images comes from the per-folder/override setting (§3.1).

### 4.6 Cartridge ROMs (NES/SNES/GB/GBA/MD/…) — raw + DAT verify
- No container logic needed; a `Platform` impl per console (or one generic `CartridgePlatform`
  keyed by folder). Rename/verify against **No-Intro DAT** XML (`meta/` provider): parse DAT,
  match by (name, size, CRC32/SHA1), rename to canonical set names, flag missing/different.
- ZIP sets work through the generic zip backend (§4.1). Also supports `.7z` sets via §4.2.

---

## 5. Metadata providers (`src/meta/`)

| Provider | Source | Platforms | Notes |
|---|---|---|---|
| `nutdb` (existing) | blawar titledb JSON | Switch | Keep as-is; becomes one provider |
| `no-intro-dat` | DAT XML (offline file or URL) | cartridge | Match by size + CRC32/SHA1 |
| `redump-dat` | DAT XML | disc (PS1/PS2/Saturn/…) | Match by size + SHA1/CRC |
| `local-json` | user-supplied or generated | any | Manual title/region overrides |

Design: `trait MetadataProvider { fn lookup(&self, key: &MetaKey) -> Option<GameMeta> }`,
with a resolver that tries **embedded metadata first** (`Platform::read_metadata` returns what
the file itself contains), then configured providers to enrich what's missing (display name,
region, version). The existing NUTDB caching (ETag/304, versions index) is the template for
DAT caching.

---

## 6. Large-file strategy (12 GB+)

Already solid in the engine; keep the invariants:

1. **Never load a whole ROM into RAM.** 1 MiB chunked streaming (`util/io.rs`) everywhere,
   including all new archive extract/create paths.
2. **Temp-file discipline.** Decompressed NCAs already go to `tempfile::NamedTempFile`. New
   extract paths write each entry to a temp file in the destination *folder* then rename —
   atomic and cross-device-safe (mirrors `import.dart`'s move-then-copy fallback).
3. **Disk-space preflight.** Before any op, check free space vs expected output (the Android
   UI already computes `expectedMergeFloor`; generalize it). Abort with a clear error.
4. **u64 everywhere, checked arithmetic.** Offsets/sizes are already `u64`; add Zip64 handling
   (zip crate does this) and a `checked_add` policy for all offsets (Android bridge already
   bounds-checks containers; extend to archives).
5. **Progress + cancellation.** All ops take `Option<&ProgressBar>`; add a cancel token the
   FFI layer can check between chunks.
6. **Android SAF/iOS Files.** The Android side already works through SAF URIs and a temp root
   (`nscb_configure_temp_root`). iOS: files live in the app sandbox or a user-selected
   document-picker folder; keep the same "temp root" mechanism.
7. **Post-extract integrity.** Verify CRC32/SHA1 of extracted entries (zip exposes CRC32; DAT
   matching covers cartridge; disc/CHD carry their own hashes). Corrupted entries are reported
   in `ExtractStats` and retried/reported — never silently dropped.

---

## 7. FFI & Flutter strategy

### 7.1 Keep the hand-written C ABI (do not switch to flutter_rust_bridge now)
- The current `nscb_*` C ABI works on every target including iOS static-lib
  (`DynamicLibrary.process()`), is already exercised by `flutter_app/test/ffi_smoke_test.dart`,
  and has no codegen step.
- `flutter_rust_bridge` (2.13, verified) would give type-safe async/streams, but adds a codegen
  pipeline and a Rust async runtime — a bigger change with no current user-facing win. **Revisit
  only if** we need live progress streams or richer typed payloads.
- Increment: keep string-payload calls, add JSON payloads (already the pattern in
  `scan_directory` → `ScanGroup` JSON). Add one new call per generic feature
  (`nscb_import_folder`, `nscb_archive_list`, `nscb_chd_create`, …).

### 7.2 Import moves into Rust (§3.3)
Single source of truth: CLI, Flutter desktop, Android and iOS all call the same
`nscb_import_folder`. Dart `archive` dependency removed; keep `flutter_app/test/import_test.dart`
updated to exercise the Rust-backed import (assert on the same summaries).

### 7.3 Platform builds
| Target | Artifact | Notes |
|---|---|---|
| Android | `libnscb.so` (cdylib, existing) | `cargo ndk`; keep JNI path or drop once Flutter-only |
| iOS | staticlib (existing `build_rust.sh`) | + simulator slice (`x86_64-apple-ios`/`aarch64-apple-ios-sim`) for local runs |
| Linux | `libnscb.so` | `flutter create --platforms=linux` + `DynamicLibrary.open('libnscb.so')` |
| Windows | `nscb.dll` | MSVC or mingw (`x86_64-pc-windows-gnu`); `extern "C"` ABI is already portable |
| macOS | `libnscb.dylib` + code signing | Prefer dylib over a static framework (static linking into Flutter macOS requires a custom Xcode static framework — a workaround, not the default) |

---

## 8. iOS specifics

- App Store: static library linked into the runner is the safest route for the *engine*
  (no dlopen of bundled dylibs). Already configured (`staticlib`, `ios/scripts/build_rust.sh`,
  `ci_scripts/`). Desktop macOS keeps a signed dylib (§7.3).
- prod.keys: for Switch ops on iOS, read via document picker (`file_picker`), store in app
  sandbox; **non-Switch platforms never ask for keys** (§3.1 `requires_keys`).
- NUTDB refresh uses `reqwest` (rustls) — fine on iOS; add ATS only if non-HTTPS sources are
  ever used.

---

## 9. Migration roadmap

| Phase | Work | Exits |
|---|---|---|
| **P1 — Archive core** | Add `archive/` module: `ArchiveFormat` trait + `zip` backend + `7z` backend + `rar` (pure) backend + RAR5 corpus decision gate (§4.3); `nscb_import_folder` FFI call; move import out of Dart; update `import_test.dart` | `cargo test` + `flutter test` green; 12 GB zip import on Android; RAR corpus results documented |
| **P2 — Platform core** | Add `platform/` module: `Platform` trait, registry, `SwitchPlatform` relocating rename/metadata logic; generic scanner replaces hard-coded ext lists; per-folder platform override for ambiguous exts | Switch behavior byte-identical (existing rename/scan tests pass) |
| **P3 — DAT + cartridge** | `meta/` providers: No-Intro DAT parser + CRC32/SHA1 matcher; generic cartridge platform; rename + verify ops | Test with a real No-Intro zip set |
| **P4 — CHD + discs** | `chd` backend (read/unCHD + create) + `opticaldiscs` for ISO/BIN/CUE; Tools-screen ops | CHD round-trip test (ISO→CHD→ISO, hash-equal; `chdman -verify` clean) |
| **P5 — Desktop Flutter** | `flutter create --platforms=linux,windows,macos`; FFI loading per OS; publish Windows DLL/Linux .so in build scripts; retire `nscb_gui` | App runs on all 5 OSes |
| **P6 — Polish** | Cancel tokens, disk-space preflight everywhere, archive *creation* (zip/7z writers), post-extract integrity everywhere, App Store review prep | Release |

---

## 10. Risks & open questions

1. **RAR5 in pure Rust** — if `rar` underdelivers, the `unrar` C++ backend becomes mandatory
   for mobile. Decision gate in P1 (§4.3). **Recommend:** test `rar` on a corpus of real RAR5
   files first.
2. **7z solid extraction** — extracting one entry from a huge solid archive can require
   decompressing many GB sequentially; acceptable for import, document it. Plus the
   `sevenz-rust` maintenance staleness (last release 2024-07) — feature-gate it and keep the
   `libarchive_oxide`/`libarchive2` swap path (§4.2).
3. **CHD writing parity** — `chd` write support is younger than read; test write→`chdman
   -verify`→emulator before advertising create (§4.4).
4. **Ambiguous disc extensions** — `.iso`/`.bin`/`.chd` don't identify the platform; rely on
   per-folder default + settings override (§3.1).
5. **Desktop Flutter FFI loading** — `DynamicLibrary.open` naming differs per OS
   (`libnscb.so` / `nscb.dll` / `libnscb.dylib`); add a small loader helper + smoke test per OS.
6. **Windows toolchain** — decide MSVC vs mingw early; keep the `extern "C"` ABI (already
   C-string based, so no calling-convention issues).
7. **Drop the egui GUI?** — yes once Flutter desktop ships; keeps one GUI codebase.
8. **Keys UX** — Switch ops need prod.keys on every platform; provide a
   "keys per platform" settings screen entry (already have the setup wizard to extend).
9. **Zip-bomb guard** — per-entry size cap vs free space in the disk-space preflight, so a
   hostile/corrupt archive can't fill the device.

---

## 11. Ecosystem snapshot (verified 2026-08-29)

| Crate | Version | Downloads | Note |
|---|---|---|---|
| `zip` | 8.6.0 | 251 M | Pure Rust ZIP r/w, Zip64, active |
| `sevenz-rust` | 0.6.1 | 1.4 M | Pure Rust 7z (stale since 2024-07) |
| `unrar` | 0.5.8 | 504 k | libunrar C++ binding |
| `rar` | 0.4.0 | 9 k | Pure Rust (nom), young |
| `chd` | 0.3.4 | — | Pure Rust CHD r/w (SnowflakePowered) |
| `chd-capi` | 0.3.1 | — | libchdr-compatible C API |
| `libchdman-rs` | 0.289 | — | MAME chdman wrapper |
| `opticaldiscs` | 0.15.0 | — | ISO/BIN/CUE/CHD browsing (read-only) |
| `lzma-sdk-rs` | 0.2301 | — | Bit-exact 7-zip LZMA SDK port |
| `zstd` | 0.13.3 | 369 M | Already in use |
| `flutter_rust_bridge` | 2.13.0 | 6.9 M | Considered, deferred (§7.1) |
