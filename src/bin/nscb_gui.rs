//! NSCB Desktop GUI — Dear ImGui front end (imgui 0.12 + winit 0.30 + glow).
//!
//! Mirrors the Flutter app's Switch library workflow: scan a folder, group by
//! base title, show NUTDB cover art, inspect metadata, delete duplicates/older
//! versions, prepare merges, rename, and run the CLI operations.

use std::collections::{HashMap, HashSet};
use std::fs;
use std::io::{BufRead, BufReader, Read};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::num::NonZeroU32;
use std::sync::mpsc::{self, Receiver, TryRecvError};
use std::thread;
use std::time::Instant;

use glow::HasContext;
use glutin::{
    config::ConfigTemplateBuilder,
    context::{ContextAttributesBuilder, NotCurrentGlContext, PossiblyCurrentContext},
    display::{GetGlDisplay, GlDisplay},
    surface::{GlSurface, Surface, SurfaceAttributesBuilder, SwapInterval, WindowSurface},
};
use imgui::{Condition, FontSource, TextureId, Ui};
use imgui_glow_renderer::{AutoRenderer, TextureMap};
use imgui_winit_support::{HiDpiMode, WinitPlatform};
use nscb::keys::KeyStore;
use nscb::nutdb::NutdbStore;
use raw_window_handle::HasWindowHandle;
use rfd::FileDialog;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use winit::dpi::LogicalSize;
use winit::event::{Event, WindowEvent};
use winit::event_loop::EventLoop;
use winit::window::{Window, WindowAttributes};

// ---------------------------------------------------------------------------
// Operation model (unchanged from the egui GUI)
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum GuiOperation {
    Merge,
    Split,
    ContentList,
    FileList,
    Dspl,
    Create,
    Convert,
    Compress,
    Decompress,
    Rename,
    Verify,
    Scanner,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum CompressInputMode {
    Auto,
    Nsp,
    Xci,
}

impl CompressInputMode {
    fn label(self) -> &'static str {
        match self {
            CompressInputMode::Auto => "Auto",
            CompressInputMode::Nsp => "NSP -> NSZ",
            CompressInputMode::Xci => "XCI -> XCZ",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum DecompressInputMode {
    Auto,
    Nsz,
    Xcz,
    Ncz,
}

impl DecompressInputMode {
    fn label(self) -> &'static str {
        match self {
            DecompressInputMode::Auto => "Auto",
            DecompressInputMode::Nsz => "NSZ -> NSP",
            DecompressInputMode::Xcz => "XCZ -> XCI",
            DecompressInputMode::Ncz => "NCZ -> NCA",
        }
    }
}

impl GuiOperation {
    fn all() -> [GuiOperation; 12] {
        [
            GuiOperation::Merge,
            GuiOperation::Split,
            GuiOperation::ContentList,
            GuiOperation::FileList,
            GuiOperation::Dspl,
            GuiOperation::Create,
            GuiOperation::Convert,
            GuiOperation::Compress,
            GuiOperation::Decompress,
            GuiOperation::Rename,
            GuiOperation::Verify,
            GuiOperation::Scanner,
        ]
    }

    fn label(self) -> &'static str {
        match self {
            GuiOperation::Merge => "Merge",
            GuiOperation::Split => "Split",
            GuiOperation::ContentList => "Content List",
            GuiOperation::FileList => "File List",
            GuiOperation::Dspl => "Split to Files",
            GuiOperation::Create => "Create/Repack",
            GuiOperation::Convert => "Convert NSP/XCI",
            GuiOperation::Compress => "Compress",
            GuiOperation::Decompress => "Decompress",
            GuiOperation::Rename => "Rename from Metadata",
            GuiOperation::Verify => "Verify",
            GuiOperation::Scanner => "Library Scanner",
        }
    }
}

// ---------------------------------------------------------------------------
// Scan model
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
struct ScanFile {
    path: String,
    filename: String,
    title_id: String,
    version: u32,
    size: u64,
    sha256: String,
    kind: nscb::cli::MergeKind,
}

#[derive(Debug, Clone)]
struct ScanGroup {
    base_id: String,
    title_name: String,
    latest_version_db: Option<u64>,
    items: Vec<ScanFile>,
}

// ---------------------------------------------------------------------------
// Worker events + prefs
// ---------------------------------------------------------------------------

#[derive(Debug)]
enum WorkerEvent {
    Started { command_line: String },
    StdoutLine(String),
    StderrLine(String),
    Finished { status_ok: bool, exit_code: Option<i32> },
    SpawnError(String),
    ScanProgress(String),
    ScanFinished(Result<Vec<ScanGroup>, String>),
    DeleteFinished(Result<String, String>),
    RenameFinished(Result<String, String>),
    ImportFolderFinished(Result<String, String>),
    OrganizeFinished(Result<String, String>),
    RefreshTitleDbFinished(Result<String, String>),
}

#[derive(Debug, Default, Serialize, Deserialize)]
#[serde(default)]
struct GuiPrefs {
    keys_path: String,
    output_folder: String,
    import_folder: String,
    scan_path: String,
    delete_sources_after_import: bool,
    analyze_package_before_import: bool,
    /// Per-platform libraries: each platform id -> its own ROMs folder.
    platform_libraries: Vec<PlatformLibrary>,
    /// The platform currently shown in the Library tab.
    active_platform_id: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
struct PlatformLibrary {
    platform_id: String,
    folder: String,
}

// ---------------------------------------------------------------------------
// AppState — all engine logic (ported unchanged from the egui GUI)
// ---------------------------------------------------------------------------

#[derive(Debug)]
struct AppState {
    operation: GuiOperation,
    input_path: String,
    input_list: String,
    output_folder: String,
    keys_path: String,
    output_type: String,
    compress_mode: CompressInputMode,
    decompress_mode: DecompressInputMode,
    compression_level: i32,
    nodelta: bool,
    print_version: bool,
    rsvcap: String,
    keypatch: String,
    create_output_path: String,
    ifolder: String,
    renmode: String,
    addlangue: String,
    noversion: String,
    dlcrname: String,
    vertype: String,
    text_file: String,
    import_folder: String,
    delete_sources_after_import: bool,
    analyze_package_before_import: bool,
    scan_path: String,
    platform_libraries: Vec<PlatformLibrary>,
    active_platform_id: String,
    scan_results: Vec<ScanGroup>,
    selected_scan_group: usize,
    show_details: bool,
    details_popup_open: bool,
    scan_generation: u64,
    is_running: bool,
    run_status: String,
    status_detail: String,
    progress_lines: usize,
    log: String,
    worker_rx: Option<Receiver<WorkerEvent>>,
}

impl Default for AppState {
    fn default() -> Self {
        let mut state = Self {
            operation: GuiOperation::Merge,
            input_path: String::new(),
            input_list: String::new(),
            output_folder: String::new(),
            keys_path: String::new(),
            output_type: "nsp".to_string(),
            compress_mode: CompressInputMode::Auto,
            decompress_mode: DecompressInputMode::Auto,
            compression_level: 3,
            nodelta: false,
            print_version: false,
            rsvcap: String::new(),
            keypatch: String::new(),
            create_output_path: String::new(),
            ifolder: String::new(),
            renmode: "skip_corr_tid".to_string(),
            addlangue: "false".to_string(),
            noversion: "false".to_string(),
            dlcrname: "false".to_string(),
            vertype: "dec".to_string(),
            text_file: String::new(),
            import_folder: String::new(),
            delete_sources_after_import: false,
            analyze_package_before_import: true,
            scan_path: String::new(),
            platform_libraries: Vec::new(),
            active_platform_id: "switch".to_string(),
            scan_results: Vec::new(),
            selected_scan_group: 0,
            show_details: false,
            details_popup_open: false,
            scan_generation: 0,
            is_running: false,
            run_status: "Idle".to_string(),
            status_detail: String::new(),
            progress_lines: 0,
            log: String::new(),
            worker_rx: None,
        };
        state.load_preferences();
        state
    }
}

impl Drop for AppState {
    fn drop(&mut self) {
        self.save_preferences();
    }
}

impl AppState {
    fn push_log_line(&mut self, line: &str) {
        if !self.log.is_empty() {
            self.log.push('\n');
        }
        self.log.push_str(line);
    }

    fn parse_multi_input(&self) -> Vec<String> {
        self.input_list
            .split(|c: char| c == '\n' || c == ';' || c == ',')
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(ToString::to_string)
            .collect()
    }

    fn add_optional_arg(args: &mut Vec<String>, flag: &str, value: &str) {
        if !value.trim().is_empty() {
            args.push(flag.to_string());
            args.push(value.trim().to_string());
        }
    }

    fn extension_of(path: &str) -> String {
        Path::new(path)
            .extension()
            .and_then(|e| e.to_str())
            .unwrap_or("")
            .to_ascii_lowercase()
    }

    fn platform_library_folder(&self, platform_id: &str) -> String {
        self.platform_libraries
            .iter()
            .find(|p| p.platform_id == platform_id)
            .map(|p| p.folder.clone())
            .unwrap_or_default()
    }

    fn set_platform_library_folder(&mut self, platform_id: &str, folder: &str) {
        let folder = folder.trim().to_string();
        if let Some(p) = self
            .platform_libraries
            .iter_mut()
            .find(|p| p.platform_id == platform_id)
        {
            p.folder = folder;
        } else {
            self.platform_libraries.push(PlatformLibrary {
                platform_id: platform_id.to_string(),
                folder,
            });
        }
        self.sync_scan_path_from_active_platform();
    }

    fn active_platform_name(&self) -> &'static str {
        nscb::platform::Platform::all()
            .iter()
            .find(|p| p.id() == self.active_platform_id)
            .map(|p| p.name())
            .unwrap_or("Switch")
    }

    /// Point scan_path at the active platform's configured library folder, if any.
    fn sync_scan_path_from_active_platform(&mut self) {
        let folder = self.platform_library_folder(&self.active_platform_id);
        if !folder.is_empty() {
            self.scan_path = folder.clone();
            if self.output_folder.trim().is_empty() {
                self.output_folder = folder;
            }
        }
    }

    fn prefs_file_path() -> Option<PathBuf> {
        let base = if let Ok(path) = std::env::var("XDG_CONFIG_HOME") {
            PathBuf::from(path)
        } else if let Ok(home) = std::env::var("HOME") {
            PathBuf::from(home).join(".config")
        } else {
            return None;
        };
        Some(base.join("nscb_gui").join("prefs.json"))
    }

    fn load_preferences(&mut self) {
        let Some(path) = Self::prefs_file_path() else {
            return;
        };
        let text = match fs::read_to_string(&path) {
            Ok(text) => text,
            Err(_) => return,
        };
        let prefs = match serde_json::from_str::<GuiPrefs>(&text) {
            Ok(prefs) => prefs,
            Err(_) => return,
        };
        if !prefs.keys_path.trim().is_empty() {
            self.keys_path = prefs.keys_path;
        }
        if !prefs.output_folder.trim().is_empty() {
            self.output_folder = prefs.output_folder;
        }
        if !prefs.import_folder.trim().is_empty() {
            self.import_folder = prefs.import_folder;
        }
        if !prefs.scan_path.trim().is_empty() {
            self.scan_path = prefs.scan_path;
        }
        self.platform_libraries = prefs.platform_libraries;
        if !prefs.active_platform_id.trim().is_empty() {
            let id = prefs.active_platform_id.trim().to_string();
            if nscb::platform::Platform::all()
                .iter()
                .any(|p| p.id() == id)
            {
                self.active_platform_id = id;
            }
        }
        self.delete_sources_after_import = prefs.delete_sources_after_import;
        self.analyze_package_before_import = prefs.analyze_package_before_import;
        self.sync_scan_path_from_active_platform();
    }

    fn save_preferences(&self) {
        let Some(path) = Self::prefs_file_path() else {
            return;
        };
        if let Some(parent) = path.parent() {
            let _ = fs::create_dir_all(parent);
        }
        let prefs = GuiPrefs {
            keys_path: self.keys_path.trim().to_string(),
            output_folder: self.output_folder.trim().to_string(),
            import_folder: self.import_folder.trim().to_string(),
            scan_path: self.scan_path.trim().to_string(),
            delete_sources_after_import: self.delete_sources_after_import,
            analyze_package_before_import: self.analyze_package_before_import,
            platform_libraries: self.platform_libraries.clone(),
            active_platform_id: self.active_platform_id.clone(),
        };
        if let Ok(text) = serde_json::to_string_pretty(&prefs) {
            let _ = fs::write(path, text);
        }
    }

    fn is_supported_merge_file(path: &Path) -> bool {
        match path.extension().and_then(|ext| ext.to_str()) {
            Some(ext) => matches!(
                ext.to_ascii_lowercase().as_str(),
                "nsp" | "nsx" | "nsz" | "xci" | "xcz"
            ),
            None => false,
        }
    }

    fn kind_label(kind: nscb::cli::MergeKind) -> &'static str {
        match kind {
            nscb::cli::MergeKind::Base => "Base",
            nscb::cli::MergeKind::Update => "Update",
            nscb::cli::MergeKind::Dlc => "DLC",
        }
    }

    fn format_size(size: u64) -> String {
        const UNITS: [&str; 5] = ["B", "KB", "MB", "GB", "TB"];
        let mut value = size as f64;
        let mut unit = 0;
        while value >= 1024.0 && unit + 1 < UNITS.len() {
            value /= 1024.0;
            unit += 1;
        }
        if unit == 0 {
            format!("{size} {}", UNITS[unit])
        } else {
            format!("{value:.2} {}", UNITS[unit])
        }
    }

    fn file_sha256(
        path: &Path,
        size: u64,
        tx: &mpsc::Sender<WorkerEvent>,
        index: usize,
        total: usize,
    ) -> Result<String, String> {
        let mut file = fs::File::open(path)
            .map_err(|e| format!("Failed to open {} for hashing: {e}", path.display()))?;
        let mut hasher = Sha256::new();
        let mut buffer = [0_u8; 1024 * 1024];
        let mut hashed = 0_u64;
        let mut next_report = 256_u64 * 1024 * 1024;
        loop {
            let read = file
                .read(&mut buffer)
                .map_err(|e| format!("Failed to read {} for hashing: {e}", path.display()))?;
            if read == 0 {
                break;
            }
            hasher.update(&buffer[..read]);
            hashed += read as u64;
            if hashed >= next_report && size >= 512_u64 * 1024 * 1024 {
                let _ = tx.send(WorkerEvent::ScanProgress(format!(
                    "Hashing file {}/{}: {} of {} ({})",
                    index,
                    total,
                    Self::format_size(hashed),
                    Self::format_size(size),
                    path.file_name()
                        .and_then(|name| name.to_str())
                        .unwrap_or("unknown")
                )));
                next_report = hashed + 256_u64 * 1024 * 1024;
            }
        }
        Ok(hex::encode(hasher.finalize()))
    }

    fn short_hash(hash: &str) -> &str {
        hash.get(..12).unwrap_or(hash)
    }

    fn collect_merge_files_recursive(root: &Path, out: &mut Vec<PathBuf>) {
        let entries = match fs::read_dir(root) {
            Ok(entries) => entries,
            Err(_) => return,
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                Self::collect_merge_files_recursive(&path, out);
            } else if path.is_file() && Self::is_supported_merge_file(&path) {
                out.push(path);
            }
        }
    }

    fn collect_merge_files_recursive_with_progress(
        root: &Path,
        out: &mut Vec<PathBuf>,
        tx: &mpsc::Sender<WorkerEvent>,
        scanned_dirs: &mut usize,
    ) {
        *scanned_dirs += 1;
        if *scanned_dirs == 1 || *scanned_dirs % 25 == 0 {
            let _ = tx.send(WorkerEvent::ScanProgress(format!(
                "Walking folders: {} folder(s), {} content file(s) found. Current: {}",
                scanned_dirs,
                out.len(),
                root.display()
            )));
        }
        let entries = match fs::read_dir(root) {
            Ok(entries) => entries,
            Err(err) => {
                let _ = tx.send(WorkerEvent::ScanProgress(format!(
                    "Skipping unreadable folder {}: {err}",
                    root.display()
                )));
                return;
            }
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                Self::collect_merge_files_recursive_with_progress(&path, out, tx, scanned_dirs);
            } else if path.is_file() && Self::is_supported_merge_file(&path) {
                out.push(path);
                if out.len() % 25 == 0 {
                    let _ = tx.send(WorkerEvent::ScanProgress(format!(
                        "Found {} content file(s) so far...",
                        out.len()
                    )));
                }
            }
        }
    }

    fn populate_merge_list_from_folder(&mut self, folder: &Path) {
        let mut files = Vec::new();
        Self::collect_merge_files_recursive(folder, &mut files);
        files.sort_by(|a, b| a.to_string_lossy().cmp(&b.to_string_lossy()));
        self.input_list = files
            .iter()
            .map(|p| p.display().to_string())
            .collect::<Vec<_>>()
            .join("\n");
        if files.is_empty() {
            self.push_log_line(&format!(
                "No merge files found under {} (supported: .nsp, .nsx, .nsz, .xci, .xcz)",
                folder.display()
            ));
        } else {
            self.push_log_line(&format!(
                "Found {} merge file(s) under {}",
                files.len(),
                folder.display()
            ));
        }
    }

    fn build_args(&self) -> Result<Vec<String>, String> {
        let mut args = Vec::new();

        match self.operation {
            GuiOperation::Merge => {
                let files = self.parse_multi_input();
                if files.is_empty() {
                    return Err(
                        "Merge needs one or more input files in the multi-input box.".to_string(),
                    );
                }
                args.push("--direct_multi".to_string());
                args.extend(files);
                if self.nodelta {
                    args.push("--nodelta".to_string());
                }
                if self.print_version {
                    args.push("--pv".to_string());
                }
                Self::add_optional_arg(&mut args, "--RSVcap", &self.rsvcap);
                Self::add_optional_arg(&mut args, "--keypatch", &self.keypatch);
            }
            GuiOperation::Split => {
                if self.input_path.trim().is_empty() {
                    return Err("Split needs an input NSP/XCI path.".to_string());
                }
                args.push("--splitter".to_string());
                args.push(self.input_path.trim().to_string());
            }
            GuiOperation::ContentList => {
                if self.input_path.trim().is_empty() {
                    return Err("Content List needs an input file path.".to_string());
                }
                args.push("--ADVcontentlist".to_string());
                args.push(self.input_path.trim().to_string());
            }
            GuiOperation::FileList => {
                if self.input_path.trim().is_empty() {
                    return Err("File List needs an input file path.".to_string());
                }
                args.push("--ADVfilelist".to_string());
                args.push(self.input_path.trim().to_string());
            }
            GuiOperation::Dspl => {
                if self.input_path.trim().is_empty() {
                    return Err("Split to Files needs an input file path.".to_string());
                }
                args.push("--dspl".to_string());
                args.push(self.input_path.trim().to_string());
            }
            GuiOperation::Create => {
                if self.create_output_path.trim().is_empty() {
                    return Err("Create/Repack needs an output file path for --create.".to_string());
                }
                if self.ifolder.trim().is_empty() {
                    return Err("Create/Repack needs an input folder for --ifolder.".to_string());
                }
                args.push("--create".to_string());
                args.push(self.create_output_path.trim().to_string());
                args.push("--ifolder".to_string());
                args.push(self.ifolder.trim().to_string());
            }
            GuiOperation::Convert => {
                if self.input_path.trim().is_empty() {
                    return Err("Convert needs an input file path.".to_string());
                }
                args.push("--direct_creation".to_string());
                args.push(self.input_path.trim().to_string());
            }
            GuiOperation::Compress => {
                if self.input_path.trim().is_empty() {
                    return Err("Compress needs an input file path.".to_string());
                }
                args.push("--compress".to_string());
                args.push(self.input_path.trim().to_string());
                args.push("--level".to_string());
                args.push(self.compression_level.to_string());
            }
            GuiOperation::Decompress => {
                if self.input_path.trim().is_empty() {
                    return Err("Decompress needs an input file path.".to_string());
                }
                args.push("--decompress".to_string());
                args.push(self.input_path.trim().to_string());
            }
            GuiOperation::Rename => {
                if self.input_path.trim().is_empty() {
                    return Err("Rename needs a file or folder path.".to_string());
                }
                args.push("--renamef".to_string());
                args.push(self.input_path.trim().to_string());
                Self::add_optional_arg(&mut args, "--renmode", &self.renmode);
                Self::add_optional_arg(&mut args, "--addlangue", &self.addlangue);
                Self::add_optional_arg(&mut args, "--noversion", &self.noversion);
                Self::add_optional_arg(&mut args, "--dlcrname", &self.dlcrname);
            }
            GuiOperation::Verify => {
                if self.input_path.trim().is_empty() {
                    return Err("Verify needs an input file path.".to_string());
                }
                args.push("--verify".to_string());
                args.push(self.input_path.trim().to_string());
                Self::add_optional_arg(&mut args, "--vertype", &self.vertype);
                Self::add_optional_arg(&mut args, "--text_file", &self.text_file);
            }
            GuiOperation::Scanner => {
                return Err("Use the scanner buttons for Library Scanner.".to_string());
            }
        }

        if !self.keys_path.trim().is_empty() {
            args.push("--keys".to_string());
            args.push(self.keys_path.trim().to_string());
        }
        if !self.output_folder.trim().is_empty() {
            args.push("--ofolder".to_string());
            args.push(self.output_folder.trim().to_string());
        }
        if matches!(
            self.operation,
            GuiOperation::Merge | GuiOperation::Dspl | GuiOperation::Convert
        ) {
            args.push("--type".to_string());
            args.push(self.output_type.trim().to_string());
        }
        Ok(args)
    }

    fn needs_output_folder(&self) -> bool {
        matches!(
            self.operation,
            GuiOperation::Merge
                | GuiOperation::Split
                | GuiOperation::Dspl
                | GuiOperation::Convert
                | GuiOperation::Compress
                | GuiOperation::Decompress
        )
    }

    fn validate_before_run(&self) -> Result<(), String> {
        if self.operation == GuiOperation::Merge {
            if self.keys_path.trim().is_empty() {
                return Err(
                    "Merge requires keys. Set Keys path to your prod.keys file.".to_string()
                );
            }
            let key_path = PathBuf::from(self.keys_path.trim());
            if !key_path.exists() {
                return Err(format!("Keys path does not exist: {}", key_path.display()));
            }
        }
        if self.needs_output_folder() && self.output_folder.trim().is_empty() {
            return Err(
                "This operation needs an output folder. Choose one before running.".to_string(),
            );
        }
        if self.operation == GuiOperation::Compress {
            let ext = Self::extension_of(self.input_path.trim());
            match self.compress_mode {
                CompressInputMode::Auto => {
                    if ext != "nsp" && ext != "xci" {
                        return Err(
                            "Compress input must be .nsp or .xci (or choose the correct mode)."
                                .to_string(),
                        );
                    }
                }
                CompressInputMode::Nsp => {
                    if ext != "nsp" {
                        return Err(
                            "Compress mode is NSP -> NSZ, but input file is not .nsp".to_string()
                        );
                    }
                }
                CompressInputMode::Xci => {
                    if ext != "xci" {
                        return Err(
                            "Compress mode is XCI -> XCZ, but input file is not .xci".to_string()
                        );
                    }
                }
            }
        }
        if self.operation == GuiOperation::Decompress {
            let ext = Self::extension_of(self.input_path.trim());
            match self.decompress_mode {
                DecompressInputMode::Auto => {
                    if ext != "nsz" && ext != "xcz" && ext != "ncz" {
                        return Err(
                            "Decompress input must be .nsz, .xcz, or .ncz (or choose the correct mode)."
                                .to_string(),
                        );
                    }
                }
                DecompressInputMode::Nsz => {
                    if ext != "nsz" {
                        return Err(
                            "Decompress mode is NSZ -> NSP, but input file is not .nsz".to_string()
                        );
                    }
                }
                DecompressInputMode::Xcz => {
                    if ext != "xcz" {
                        return Err(
                            "Decompress mode is XCZ -> XCI, but input file is not .xcz".to_string()
                        );
                    }
                }
                DecompressInputMode::Ncz => {
                    if ext != "ncz" {
                        return Err(
                            "Decompress mode is NCZ -> NCA, but input file is not .ncz".to_string()
                        );
                    }
                }
            }
        }
        Ok(())
    }

    fn clear_fields_for_next_job(&mut self) {
        self.input_path.clear();
        self.input_list.clear();
        self.output_folder.clear();
        self.create_output_path.clear();
        self.ifolder.clear();
        self.text_file.clear();
        self.rsvcap.clear();
        self.keypatch.clear();
        self.status_detail = "Cleared input and output fields for next action.".to_string();
        self.run_status = "Idle".to_string();
    }

    fn resolve_cli_binary() -> PathBuf {
        let exe = std::env::current_exe().unwrap_or_else(|_| PathBuf::from("nscb_gui"));
        let gui_name = if cfg!(windows) {
            "nscb_gui.exe"
        } else {
            "nscb_gui"
        };
        let cli_name = if cfg!(windows) { "nscb.exe" } else { "nscb" };

        if exe
            .file_name()
            .and_then(|name| name.to_str())
            .map(|name| name == gui_name)
            .unwrap_or(false)
        {
            return exe.with_file_name(cli_name);
        }
        PathBuf::from(cli_name)
    }

    fn spawn_line_reader<T: std::io::Read + Send + 'static>(
        reader: T,
        is_stderr: bool,
        tx: mpsc::Sender<WorkerEvent>,
    ) {
        thread::spawn(move || {
            let mut br = BufReader::new(reader);
            let mut line = String::new();
            loop {
                line.clear();
                let read = br.read_line(&mut line).unwrap_or(0);
                if read == 0 {
                    break;
                }
                let text = line.trim_end_matches(&['\r', '\n'][..]).to_string();
                if text.is_empty() {
                    continue;
                }
                let _ = if is_stderr {
                    tx.send(WorkerEvent::StderrLine(text))
                } else {
                    tx.send(WorkerEvent::StdoutLine(text))
                };
            }
        });
    }

    fn run_worker(args: Vec<String>, tx: mpsc::Sender<WorkerEvent>) {
        let cli_bin = Self::resolve_cli_binary();
        let (mut command, command_line) = if cli_bin.exists() {
            let mut cmd = Command::new(&cli_bin);
            cmd.args(&args);
            (cmd, format!("{} {}", cli_bin.display(), args.join(" ")))
        } else {
            let mut cmd = Command::new("cargo");
            cmd.args(["run", "--bin", "nscb", "--"]).args(&args);
            (cmd, format!("cargo run --bin nscb -- {}", args.join(" ")))
        };

        let _ = tx.send(WorkerEvent::Started { command_line });

        command.stdout(Stdio::piped()).stderr(Stdio::piped());
        let mut child = match command.spawn() {
            Ok(child) => child,
            Err(err) => {
                let _ = tx.send(WorkerEvent::SpawnError(format!(
                    "Failed to start command: {err}"
                )));
                return;
            }
        };

        if let Some(stdout) = child.stdout.take() {
            Self::spawn_line_reader(stdout, false, tx.clone());
        }
        if let Some(stderr) = child.stderr.take() {
            Self::spawn_line_reader(stderr, true, tx.clone());
        }

        match child.wait() {
            Ok(status) => {
                let _ = tx.send(WorkerEvent::Finished {
                    status_ok: status.success(),
                    exit_code: status.code(),
                });
            }
            Err(err) => {
                let _ = tx.send(WorkerEvent::SpawnError(format!(
                    "Failed while waiting for command: {err}"
                )));
            }
        }
    }

    fn scan_library(
        scan_path: String,
        keys_path: String,
        tx: &mpsc::Sender<WorkerEvent>,
    ) -> Result<Vec<ScanGroup>, String> {
        let _ = tx.send(WorkerEvent::ScanProgress(
            "Loading prod.keys...".to_string(),
        ));
        let ks = KeyStore::from_default_locations(Some(&keys_path))
            .map_err(|e| format!("Key error: {e}"))?;
        let root = PathBuf::from(&scan_path);
        if !root.is_dir() {
            return Err(format!("Scan path is not a directory: {}", root.display()));
        }
        let versions_index = NutdbStore::new(None, None)
            .try_load_cached_versions_index()
            .ok()
            .flatten();

        let _ = tx.send(WorkerEvent::ScanProgress(format!(
            "Walking NAS/library folder: {}",
            root.display()
        )));
        let mut file_paths = Vec::new();
        let mut scanned_dirs = 0;
        Self::collect_merge_files_recursive_with_progress(
            &root,
            &mut file_paths,
            tx,
            &mut scanned_dirs,
        );
        file_paths.sort_by(|a, b| a.to_string_lossy().cmp(&b.to_string_lossy()));
        let _ = tx.send(WorkerEvent::ScanProgress(format!(
            "Found {} content file(s) in {} folder(s). Reading metadata and hashes...",
            file_paths.len(),
            scanned_dirs
        )));

        let mut groups: HashMap<u64, ScanGroup> = HashMap::new();
        let total = file_paths.len();
        for (index, path) in file_paths.into_iter().enumerate() {
            let file_number = index + 1;
            let path_str = path.to_string_lossy().to_string();
            let metadata = fs::metadata(&path)
                .map_err(|e| format!("Failed to read metadata for {}: {e}", path.display()))?;
            let size = metadata.len();
            let filename = path
                .file_name()
                .and_then(|name| name.to_str())
                .unwrap_or("unknown");
            let _ = tx.send(WorkerEvent::ScanProgress(format!(
                "Reading file {}/{}: {} ({})",
                file_number,
                total,
                filename,
                Self::format_size(size)
            )));
            let sha256 = Self::file_sha256(&path, size, tx, file_number, total)?;
            let _ = tx.send(WorkerEvent::ScanProgress(format!(
                "Parsing metadata {}/{}: {}",
                file_number, total, filename
            )));
            let (records, _, title_name) = nscb::cli::collect_title_records(&[&path_str], &ks);
            for (tid, rec) in records {
                let base_id = tid & !0xFFF;
                let base_id_text = format!("{:016X}", base_id);
                let latest_version_db = versions_index
                    .as_ref()
                    .and_then(|idx| idx.latest_version_for(&base_id_text));
                let group = groups.entry(base_id).or_insert_with(|| ScanGroup {
                    base_id: base_id_text,
                    title_name: title_name.clone().unwrap_or_else(|| "Unknown".to_string()),
                    latest_version_db,
                    items: Vec::new(),
                });
                if group.latest_version_db.is_none() {
                    group.latest_version_db = latest_version_db;
                }
                if group.title_name == "Unknown" {
                    if let Some(name) = &title_name {
                        group.title_name = name.clone();
                    }
                }
                group.items.push(ScanFile {
                    path: path_str.clone(),
                    filename: path
                        .file_name()
                        .unwrap_or_default()
                        .to_string_lossy()
                        .to_string(),
                    title_id: format!("{:016X}", tid),
                    version: rec.version,
                    size,
                    sha256: sha256.clone(),
                    kind: rec.kind,
                });
            }
        }

        let _ = tx.send(WorkerEvent::ScanProgress(
            "Sorting scanned title groups...".to_string(),
        ));
        let mut result = groups.into_values().collect::<Vec<_>>();
        for group in &mut result {
            group.items.sort_by(|a, b| {
                a.title_id
                    .cmp(&b.title_id)
                    .then_with(|| b.version.cmp(&a.version))
                    .then_with(|| a.filename.cmp(&b.filename))
            });
        }
        result.sort_by(|a, b| {
            a.title_name
                .to_lowercase()
                .cmp(&b.title_name.to_lowercase())
        });
        Ok(result)
    }

    fn start_scan(&mut self) {
        if self.scan_path.trim().is_empty() {
            self.run_status = "Validation error".to_string();
            self.status_detail = "Choose a library folder to scan.".to_string();
            return;
        }
        if self.keys_path.trim().is_empty() {
            self.run_status = "Validation error".to_string();
            self.status_detail = "Scanner requires a prod.keys path.".to_string();
            return;
        }

        self.is_running = true;
        self.run_status = "Scanning".to_string();
        self.status_detail = format!("Scanning {} recursively...", self.scan_path.trim());
        self.push_log_line("Scanning library...");
        let scan_path = self.scan_path.trim().to_string();
        let keys_path = self.keys_path.trim().to_string();
        let (tx, rx) = mpsc::channel::<WorkerEvent>();
        self.worker_rx = Some(rx);
        thread::spawn(move || {
            let result = Self::scan_library(scan_path, keys_path, &tx);
            let _ = tx.send(WorkerEvent::ScanFinished(result));
        });
    }

    fn unique_output_path(output_dir: &Path, file_name: &str) -> PathBuf {
        let sanitized = nscb::util::filename::sanitize_output_filename(file_name);
        let first = output_dir.join(&sanitized);
        if !first.exists() {
            return first;
        }
        let path = Path::new(&sanitized);
        let stem = path.file_stem().and_then(|s| s.to_str()).unwrap_or("file");
        let ext = path
            .extension()
            .and_then(|e| e.to_str())
            .map(|e| format!(".{e}"))
            .unwrap_or_default();
        for index in 2.. {
            let candidate = output_dir.join(format!("{stem} ({index}){ext}"));
            if !candidate.exists() {
                return candidate;
            }
        }
        unreachable!("unbounded unique filename loop")
    }

    fn suggested_import_name(
        path: &Path,
        ks: &KeyStore,
        nutdb: &NutdbStore,
        analyze: bool,
    ) -> String {
        let original_name = path
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("imported_package.nsp")
            .to_string();
        if !analyze {
            return original_name;
        }
        let ext = path
            .extension()
            .and_then(|e| e.to_str())
            .unwrap_or("nsp")
            .to_ascii_lowercase();
        let path_text = path.to_string_lossy();
        nscb::cli::build_merge_filename_metadata(&[path_text.as_ref()], &ext, ks, nutdb)
            .or_else(|| {
                let refs = [path_text.as_ref()];
                Some(nscb::cli::build_merge_filename(&refs, &ext))
            })
            .and_then(|name| {
                Path::new(&name)
                    .file_name()
                    .and_then(|file| file.to_str())
                    .map(ToString::to_string)
            })
            .unwrap_or(original_name)
    }

    fn copy_with_temp_file(src: &Path, dst: &Path) -> Result<u64, String> {
        if let Some(parent) = dst.parent() {
            fs::create_dir_all(parent)
                .map_err(|e| format!("Could not create output folder {}: {e}", parent.display()))?;
        }
        let tmp = dst.with_extension(format!(
            "{}.tmp",
            dst.extension().and_then(|e| e.to_str()).unwrap_or("part")
        ));
        if tmp.exists() {
            fs::remove_file(&tmp)
                .map_err(|e| format!("Could not remove stale temp file {}: {e}", tmp.display()))?;
        }
        let copied = fs::copy(src, &tmp).map_err(|e| {
            format!(
                "Copy failed from {} to temporary file {}: {e}",
                src.display(),
                tmp.display()
            )
        })?;
        fs::rename(&tmp, dst).map_err(|e| {
            let _ = fs::remove_file(&tmp);
            format!("Could not move completed output to {}: {e}", dst.display())
        })?;
        Ok(copied)
    }

    fn import_folder_to_library(
        import_folder: String,
        output_folder: String,
        keys_path: String,
        delete_sources: bool,
        analyze: bool,
        tx: &mpsc::Sender<WorkerEvent>,
    ) -> Result<String, String> {
        let input_root = PathBuf::from(&import_folder);
        let output_root = PathBuf::from(&output_folder);
        if !input_root.is_dir() {
            return Err(format!(
                "Import folder is not a directory: {}",
                input_root.display()
            ));
        }
        if output_folder.trim().is_empty() {
            return Err("Choose a game library/output folder before importing.".to_string());
        }
        fs::create_dir_all(&output_root).map_err(|e| {
            format!(
                "Could not create game library folder {}: {e}",
                output_root.display()
            )
        })?;

        let ks = if analyze {
            Some(
                KeyStore::from_default_locations(Some(&keys_path))
                    .map_err(|e| format!("Key error: {e}"))?,
            )
        } else {
            None
        };
        let nutdb = NutdbStore::new(None, None);
        let mut files = Vec::new();
        let mut scanned_dirs = 0usize;
        Self::collect_merge_files_recursive_with_progress(
            &input_root,
            &mut files,
            tx,
            &mut scanned_dirs,
        );
        files.sort_by(|a, b| a.to_string_lossy().cmp(&b.to_string_lossy()));
        if files.is_empty() {
            return Ok("No supported package files found in import folder.".to_string());
        }

        let mut imported = 0usize;
        let mut skipped = 0usize;
        let mut deleted = 0usize;
        let total = files.len();
        for (index, src) in files.iter().enumerate() {
            let display = src
                .file_name()
                .and_then(|name| name.to_str())
                .unwrap_or("package");
            let _ = tx.send(WorkerEvent::ScanProgress(format!(
                "Importing {}/{}: {}",
                index + 1,
                total,
                display
            )));
            let suggested = match &ks {
                Some(ks) => Self::suggested_import_name(src, ks, &nutdb, analyze),
                None => src
                    .file_name()
                    .and_then(|name| name.to_str())
                    .unwrap_or("imported_package.nsp")
                    .to_string(),
            };
            let dst = Self::unique_output_path(&output_root, &suggested);
            if dst.exists() {
                skipped += 1;
                continue;
            }
            let copied = Self::copy_with_temp_file(src, &dst)?;
            imported += 1;
            let _ = tx.send(WorkerEvent::ScanProgress(format!(
                "Saved {} to {}",
                Self::format_size(copied),
                dst.display()
            )));
            if delete_sources {
                fs::remove_file(src)
                    .map_err(|e| format!("Imported but failed to delete {}: {e}", src.display()))?;
                deleted += 1;
            }
        }

        Ok(format!(
            "Imported {imported} file(s), skipped {skipped}, deleted {deleted} source file(s)."
        ))
    }

    fn start_import_folder(&mut self) {
        if self.import_folder.trim().is_empty() {
            self.run_status = "Validation error".to_string();
            self.status_detail = "Choose an import folder.".to_string();
            return;
        }
        if self.output_folder.trim().is_empty() {
            self.run_status = "Validation error".to_string();
            self.status_detail = "Choose a game library/output folder.".to_string();
            return;
        }
        if self.analyze_package_before_import && self.keys_path.trim().is_empty() {
            self.run_status = "Validation error".to_string();
            self.status_detail = "Analyzed import needs prod.keys path.".to_string();
            return;
        }

        self.is_running = true;
        self.run_status = "Importing".to_string();
        self.status_detail = "Bulk import started.".to_string();
        self.push_log_line("Importing folder into game library...");
        let import_folder = self.import_folder.trim().to_string();
        let output_folder = self.output_folder.trim().to_string();
        let keys_path = self.keys_path.trim().to_string();
        let delete_sources = self.delete_sources_after_import;
        let analyze = self.analyze_package_before_import;
        let (tx, rx) = mpsc::channel::<WorkerEvent>();
        self.worker_rx = Some(rx);
        thread::spawn(move || {
            let result = Self::import_folder_to_library(
                import_folder,
                output_folder,
                keys_path,
                delete_sources,
                analyze,
                &tx,
            );
            let _ = tx.send(WorkerEvent::ImportFolderFinished(result));
        });
    }

    fn start_organize_folder(&mut self) {
        if self.import_folder.trim().is_empty() {
            self.run_status = "Validation error".to_string();
            self.status_detail = "Choose an import folder to organize.".to_string();
            return;
        }
        if self.output_folder.trim().is_empty() {
            self.run_status = "Validation error".to_string();
            self.status_detail = "Choose a library root folder to organize into.".to_string();
            return;
        }

        self.is_running = true;
        self.run_status = "Organizing".to_string();
        self.status_detail = "Organizing import folder into per-platform library folders...".to_string();
        self.push_log_line("Organizing folder into per-platform library...");
        let import_folder = self.import_folder.trim().to_string();
        let output_folder = self.output_folder.trim().to_string();
        let delete_sources = self.delete_sources_after_import;
        let (tx, rx) = mpsc::channel::<WorkerEvent>();
        self.worker_rx = Some(rx);
        thread::spawn(move || {
            let result = nscb::platform::organize::organize_library(
                Path::new(&import_folder),
                Path::new(&output_folder),
                delete_sources,
            )
            .map(|summary| summary.report())
            .map_err(|e| e.to_string());
            let _ = tx.send(WorkerEvent::OrganizeFinished(result));
        });
    }

    fn start_refresh_title_db(&mut self) {
        self.is_running = true;
        self.run_status = "Refreshing TitlesDB".to_string();
        self.status_detail = "Refreshing local TitlesDB cache...".to_string();
        let (tx, rx) = mpsc::channel::<WorkerEvent>();
        self.worker_rx = Some(rx);
        thread::spawn(move || {
            let result = NutdbStore::new(None, None)
                .refresh()
                .map(|outcome| {
                    format!(
                        "TitlesDB {} ({} indexed titles).",
                        outcome.status.as_str(),
                        outcome.indexed_titles
                    )
                })
                .map_err(|e| format!("TitlesDB refresh failed: {e}"));
            let _ = tx.send(WorkerEvent::RefreshTitleDbFinished(result));
        });
    }

    fn start_bulk_rename_scan_path(&mut self) {
        if self.scan_path.trim().is_empty() || self.keys_path.trim().is_empty() {
            self.run_status = "Validation error".to_string();
            self.status_detail = "Bulk rename needs scanner folder and prod.keys path.".to_string();
            return;
        }

        self.is_running = true;
        self.run_status = "Renaming".to_string();
        self.status_detail = "Bulk rename started. Scanner will refresh after rename.".to_string();
        let scan_path = self.scan_path.trim().to_string();
        let keys_path = self.keys_path.trim().to_string();
        let (tx, rx) = mpsc::channel::<WorkerEvent>();
        self.worker_rx = Some(rx);
        thread::spawn(move || {
            let result = (|| -> Result<String, String> {
                let ks = KeyStore::from_default_locations(Some(&keys_path))
                    .map_err(|e| format!("Key error: {e}"))?;
                let nutdb = NutdbStore::new(None, None);
                let index = nutdb
                    .ensure_index()
                    .map_err(|e| format!("Nutdb error: {e}"))?;
                let options = nscb::cli::RenameOptions::from_args(
                    Some("skip_corr_tid"),
                    Some("true"),
                    Some("false"),
                    Some("tag"),
                );
                let count = nscb::cli::rename_target(&scan_path, &ks, &index, options)
                    .map_err(|e| format!("Rename failed: {e}"))?;
                Ok(format!("Renamed {count} file(s)."))
            })();
            let _ = tx.send(WorkerEvent::RenameFinished(result));
        });
    }

    fn start_delete_scanned_file(&mut self, path: String) {
        self.is_running = true;
        self.run_status = "Deleting".to_string();
        self.status_detail = format!("Deleting {path}");
        let (tx, rx) = mpsc::channel::<WorkerEvent>();
        self.worker_rx = Some(rx);
        thread::spawn(move || {
            let result = fs::remove_file(&path)
                .map(|_| format!("Deleted {path}"))
                .map_err(|e| format!("Delete failed: {e}"));
            let _ = tx.send(WorkerEvent::DeleteFinished(result));
        });
    }

    fn start_delete_scanned_files(&mut self, paths: Vec<String>, label: &str) {
        if paths.is_empty() {
            self.status_detail = format!("No {label} files to delete for this game.");
            self.push_log_line(&self.status_detail.clone());
            return;
        }

        self.is_running = true;
        self.run_status = "Deleting".to_string();
        self.status_detail = format!("Deleting {} {label} file(s)...", paths.len());
        let label = label.to_string();
        let (tx, rx) = mpsc::channel::<WorkerEvent>();
        self.worker_rx = Some(rx);
        thread::spawn(move || {
            let mut deleted = 0_usize;
            let mut errors = Vec::new();
            for path in paths {
                match fs::remove_file(&path) {
                    Ok(_) => deleted += 1,
                    Err(err) => errors.push(format!("{path}: {err}")),
                }
            }
            let result = if errors.is_empty() {
                Ok(format!("Deleted {deleted} {label} file(s)."))
            } else {
                Err(format!(
                    "Deleted {deleted} {label} file(s), failed on {}: {}",
                    errors.len(),
                    errors.join("; ")
                ))
            };
            let _ = tx.send(WorkerEvent::DeleteFinished(result));
        });
    }

    fn start_rename_scan_group(&mut self, group_index: usize) {
        let Some(group) = self.scan_results.get(group_index) else {
            return;
        };
        if self.keys_path.trim().is_empty() {
            self.run_status = "Validation error".to_string();
            self.status_detail = "Rename needs prod.keys path.".to_string();
            return;
        }
        let paths = group
            .items
            .iter()
            .map(|item| item.path.clone())
            .collect::<Vec<_>>();
        if paths.is_empty() {
            self.status_detail = "No files to rename for this game.".to_string();
            return;
        }

        self.is_running = true;
        self.run_status = "Renaming".to_string();
        self.status_detail = format!(
            "Renaming {} file(s) for {}...",
            paths.len(),
            group.title_name
        );
        let keys_path = self.keys_path.trim().to_string();
        let (tx, rx) = mpsc::channel::<WorkerEvent>();
        self.worker_rx = Some(rx);
        thread::spawn(move || {
            let result = (|| -> Result<String, String> {
                let ks = KeyStore::from_default_locations(Some(&keys_path))
                    .map_err(|e| format!("Key error: {e}"))?;
                let nutdb = NutdbStore::new(None, None);
                let index = nutdb
                    .ensure_index()
                    .map_err(|e| format!("Nutdb error: {e}"))?;
                let options = nscb::cli::RenameOptions::from_args(
                    Some("skip_corr_tid"),
                    Some("true"),
                    Some("false"),
                    Some("tag"),
                );
                let mut renamed = 0_usize;
                for path in paths {
                    renamed += nscb::cli::rename_target(&path, &ks, &index, options)
                        .map_err(|e| format!("Rename failed for {path}: {e}"))?;
                }
                Ok(format!("Renamed {renamed} file(s) for selected game."))
            })();
            let _ = tx.send(WorkerEvent::RenameFinished(result));
        });
    }

    fn exact_duplicate_paths_for_group(group: &ScanGroup) -> Vec<String> {
        let mut by_exact: HashMap<(String, u32, u64, String), Vec<&ScanFile>> = HashMap::new();
        for item in &group.items {
            by_exact
                .entry((
                    item.title_id.clone(),
                    item.version,
                    item.size,
                    item.sha256.clone(),
                ))
                .or_default()
                .push(item);
        }
        let mut delete_paths = Vec::new();
        for items in by_exact.values_mut() {
            if items.len() < 2 {
                continue;
            }
            items.sort_by(|a, b| a.path.cmp(&b.path));
            delete_paths.extend(items.iter().skip(1).map(|item| item.path.clone()));
        }
        delete_paths
    }

    fn older_version_paths_for_group(group: &ScanGroup) -> Vec<String> {
        let mut latest_version_by_title: HashMap<&str, u32> = HashMap::new();
        for item in &group.items {
            latest_version_by_title
                .entry(item.title_id.as_str())
                .and_modify(|version| {
                    if item.version > *version {
                        *version = item.version;
                    }
                })
                .or_insert(item.version);
        }
        group
            .items
            .iter()
            .filter(|item| {
                latest_version_by_title
                    .get(item.title_id.as_str())
                    .copied()
                    .unwrap_or(item.version)
                    > item.version
            })
            .map(|item| item.path.clone())
            .collect()
    }

    fn prepare_scan_group_for_merge(&mut self, group_index: usize) {
        let Some(group) = self.scan_results.get(group_index) else {
            return;
        };

        let mut latest_by_title: HashMap<&str, &ScanFile> = HashMap::new();
        for item in &group.items {
            latest_by_title
                .entry(item.title_id.as_str())
                .and_modify(|existing| {
                    if item.version > existing.version {
                        *existing = item;
                    }
                })
                .or_insert(item);
        }

        let mut selected = latest_by_title.into_values().collect::<Vec<_>>();
        selected.sort_by(|a, b| a.title_id.cmp(&b.title_id));
        let paths = selected
            .iter()
            .map(|item| item.path.clone())
            .collect::<Vec<_>>();
        self.input_list = paths.join("\n");
        self.operation = GuiOperation::Merge;

        let refs = paths.iter().map(String::as_str).collect::<Vec<_>>();
        let suggested = if self.keys_path.trim().is_empty() {
            nscb::cli::build_merge_filename(&refs, self.output_type.trim())
        } else {
            match KeyStore::from_default_locations(Some(self.keys_path.trim())) {
                Ok(ks) => {
                    let nutdb = NutdbStore::new(None, None);
                    nscb::cli::build_merge_filename_metadata(
                        &refs,
                        self.output_type.trim(),
                        &ks,
                        &nutdb,
                    )
                    .unwrap_or_else(|| {
                        nscb::cli::build_merge_filename(&refs, self.output_type.trim())
                    })
                }
                Err(_) => nscb::cli::build_merge_filename(&refs, self.output_type.trim()),
            }
        };
        self.push_log_line(&format!(
            "Prepared {} file(s) from {} for merge. Suggested output: {}",
            paths.len(),
            group.title_name,
            suggested
        ));
        self.status_detail = "Scanner group copied to merge list.".to_string();
    }

    fn start_run(&mut self) {
        let args = match self.build_args() {
            Ok(args) => args,
            Err(err) => {
                self.run_status = "Validation error".to_string();
                self.status_detail = err.clone();
                self.push_log_line(&format!("Validation error: {err}"));
                return;
            }
        };

        if self.needs_output_folder() && self.output_folder.trim().is_empty() {
            if let Some(path) = FileDialog::new()
                .set_title("Choose output folder")
                .pick_folder()
            {
                self.output_folder = path.display().to_string();
            }
        }

        if let Err(err) = self.validate_before_run() {
            self.run_status = "Validation error".to_string();
            self.status_detail = err.clone();
            self.push_log_line(&format!("Validation error: {err}"));
            return;
        }

        self.is_running = true;
        self.run_status = "Running".to_string();
        self.status_detail =
            "Operation started. Large merges may take time before output appears.".to_string();
        self.progress_lines = 0;
        self.push_log_line("Running operation...");

        let (tx, rx) = mpsc::channel::<WorkerEvent>();
        self.worker_rx = Some(rx);

        thread::spawn(move || {
            Self::run_worker(args, tx);
        });
    }

    fn check_worker(&mut self) {
        let mut events = Vec::new();
        let mut disconnected = false;
        let mut saw_terminal_event = false;

        {
            let Some(rx) = &self.worker_rx else {
                return;
            };
            loop {
                match rx.try_recv() {
                    Ok(event) => events.push(event),
                    Err(TryRecvError::Disconnected) => {
                        disconnected = true;
                        break;
                    }
                    Err(TryRecvError::Empty) => break,
                }
            }
        }

        for event in events {
            match event {
                WorkerEvent::Started { command_line } => {
                    self.push_log_line(&format!("Command: {command_line}"));
                }
                WorkerEvent::StdoutLine(line) => {
                    self.progress_lines += 1;
                    self.push_log_line(&line);
                    self.status_detail = format!("Running... {} output lines", self.progress_lines);
                }
                WorkerEvent::StderrLine(line) => {
                    self.progress_lines += 1;
                    self.push_log_line(&format!("stderr: {line}"));
                    self.status_detail = format!("Running... {} output lines", self.progress_lines);
                }
                WorkerEvent::Finished {
                    status_ok,
                    exit_code,
                } => {
                    saw_terminal_event = true;
                    self.is_running = false;
                    self.run_status = if status_ok {
                        "Success".to_string()
                    } else {
                        "Failed".to_string()
                    };
                    self.status_detail = if status_ok {
                        format!("Operation completed. Exit code {:?}.", exit_code)
                    } else {
                        format!(
                            "Operation failed with exit code {:?}. Check log output.",
                            exit_code
                        )
                    };
                    self.push_log_line(&format!("Completed with exit code {:?}", exit_code));
                    self.push_log_line("--------------");
                    self.worker_rx = None;
                }
                WorkerEvent::SpawnError(err) => {
                    saw_terminal_event = true;
                    self.is_running = false;
                    self.run_status = "Failed".to_string();
                    self.status_detail = err.clone();
                    self.push_log_line(&format!("Error: {err}"));
                    self.push_log_line("--------------");
                    self.worker_rx = None;
                }
                WorkerEvent::ScanProgress(message) => {
                    self.progress_lines += 1;
                    self.run_status = "Scanning".to_string();
                    self.status_detail = message.clone();
                    self.push_log_line(&message);
                }
                WorkerEvent::ScanFinished(result) => {
                    saw_terminal_event = true;
                    self.is_running = false;
                    match result {
                        Ok(groups) => {
                            let file_count = groups.iter().map(|g| g.items.len()).sum::<usize>();
                            let exact_duplicate_count = groups
                                .iter()
                                .map(|group| {
                                    let mut counts: HashMap<(String, u32, u64, String), usize> =
                                        HashMap::new();
                                    for item in &group.items {
                                        *counts
                                            .entry((
                                                item.title_id.clone(),
                                                item.version,
                                                item.size,
                                                item.sha256.clone(),
                                            ))
                                            .or_default() += 1;
                                    }
                                    counts
                                        .values()
                                        .filter(|count| **count > 1)
                                        .map(|count| count - 1)
                                        .sum::<usize>()
                                })
                                .sum::<usize>();
                            self.scan_results = groups;
                            self.selected_scan_group = 0;
                            self.show_details = false;
                            self.details_popup_open = false;
                            self.scan_generation += 1;
                            self.run_status = "Success".to_string();
                            self.status_detail = format!(
                                "Found {} title group(s), {} file(s), {} exact duplicate file(s).",
                                self.scan_results.len(),
                                file_count,
                                exact_duplicate_count
                            );
                            self.push_log_line(&self.status_detail.clone());
                        }
                        Err(err) => {
                            self.run_status = "Failed".to_string();
                            self.status_detail = err.clone();
                            self.push_log_line(&format!("Scan error: {err}"));
                        }
                    }
                    self.worker_rx = None;
                }
                WorkerEvent::DeleteFinished(result) => {
                    saw_terminal_event = true;
                    self.is_running = false;
                    match result {
                        Ok(msg) => {
                            self.push_log_line(&msg);
                            self.status_detail = msg;
                            self.start_scan();
                        }
                        Err(err) => {
                            self.run_status = "Failed".to_string();
                            self.status_detail = err.clone();
                            self.push_log_line(&err);
                        }
                    }
                    if !self.is_running {
                        self.worker_rx = None;
                    }
                }
                WorkerEvent::RenameFinished(result) => {
                    saw_terminal_event = true;
                    self.is_running = false;
                    match result {
                        Ok(msg) => {
                            self.push_log_line(&msg);
                            self.status_detail = msg;
                            self.start_scan();
                        }
                        Err(err) => {
                            self.run_status = "Failed".to_string();
                            self.status_detail = err.clone();
                            self.push_log_line(&err);
                        }
                    }
                    if !self.is_running {
                        self.worker_rx = None;
                    }
                }
                WorkerEvent::ImportFolderFinished(result) => {
                    saw_terminal_event = true;
                    self.is_running = false;
                    match result {
                        Ok(msg) => {
                            self.run_status = "Success".to_string();
                            self.status_detail = msg.clone();
                            self.push_log_line(&msg);
                            if !self.output_folder.trim().is_empty() {
                                self.scan_path = self.output_folder.clone();
                                self.start_scan();
                            }
                        }
                        Err(err) => {
                            self.run_status = "Failed".to_string();
                            self.status_detail = err.clone();
                            self.push_log_line(&format!("Import error: {err}"));
                        }
                    }
                    if !self.is_running {
                        self.worker_rx = None;
                    }
                }
                WorkerEvent::OrganizeFinished(result) => {
                    saw_terminal_event = true;
                    self.is_running = false;
                    match result {
                        Ok(msg) => {
                            self.run_status = "Success".to_string();
                            self.status_detail = msg.clone();
                            self.push_log_line(&msg);
                            if !self.output_folder.trim().is_empty() {
                                self.scan_path = self.output_folder.clone();
                                self.start_scan();
                            }
                        }
                        Err(err) => {
                            self.run_status = "Failed".to_string();
                            self.status_detail = err.clone();
                            self.push_log_line(&format!("Organize error: {err}"));
                        }
                    }
                    if !self.is_running {
                        self.worker_rx = None;
                    }
                }
                WorkerEvent::RefreshTitleDbFinished(result) => {
                    saw_terminal_event = true;
                    self.is_running = false;
                    match result {
                        Ok(msg) => {
                            self.run_status = "Success".to_string();
                            self.status_detail = msg.clone();
                            self.push_log_line(&msg);
                        }
                        Err(err) => {
                            self.run_status = "Failed".to_string();
                            self.status_detail = err.clone();
                            self.push_log_line(&err);
                        }
                    }
                    self.worker_rx = None;
                }
            }
        }

        if disconnected && !saw_terminal_event && self.worker_rx.is_some() {
            self.is_running = false;
            self.run_status = "Worker disconnected".to_string();
            self.status_detail = "Background worker disconnected before producing output.".to_string();
            self.push_log_line("Worker disconnected before producing output.");
            self.worker_rx = None;
        }
    }
}

// ---------------------------------------------------------------------------
// Cover art: NUTDB banner/icon download -> decode -> GL texture
// ---------------------------------------------------------------------------

enum CoverMsg {
    Ready {
        base_id: String,
        width: u32,
        height: u32,
        rgba: Vec<u8>,
    },
    Failed {
        base_id: String,
        reason: String,
    },
}

struct CoverCache {
    cache_dir: PathBuf,
    loaded: HashMap<String, (TextureId, u32, u32)>,
    requested: HashSet<String>,
    client: reqwest::blocking::Client,
    tx: mpsc::Sender<CoverMsg>,
    rx: Option<Receiver<CoverMsg>>,
    generation: u64,
}

impl CoverCache {
    fn new() -> Self {
        let (tx, rx) = mpsc::channel::<CoverMsg>();
        let cache_dir = if let Ok(dir) = std::env::var("XDG_CACHE_HOME") {
            PathBuf::from(dir).join("nscb_gui").join("covers")
        } else if let Ok(home) = std::env::var("HOME") {
            PathBuf::from(home).join(".cache").join("nscb_gui").join("covers")
        } else {
            PathBuf::from(".nscb_gui_covers")
        };
        let _ = fs::create_dir_all(&cache_dir);
        let client = reqwest::blocking::Client::builder()
            .user_agent("nscb-gui")
            .timeout(std::time::Duration::from_secs(30))
            .build()
            .unwrap_or_default();
        Self {
            cache_dir,
            loaded: HashMap::new(),
            requested: HashSet::new(),
            client,
            tx,
            rx: Some(rx),
            generation: 0,
        }
    }

    fn request(&mut self, base_id: &str, url: Option<&str>) {
        if self.loaded.contains_key(base_id) || self.requested.contains(base_id) {
            return;
        }
        let Some(url) = url else {
            return;
        };
        if url.trim().is_empty() {
            return;
        }
        self.requested.insert(base_id.to_string());
        let base_id = base_id.to_string();
        let cache_file = self.cache_dir.join(format!("{base_id}.img"));

        // Disk cache hit: decode now and hand the pixels to the same channel so
        // poll_uploads does the real GL upload with a valid texture id.
        if let Ok(bytes) = fs::read(&cache_file) {
            if let Some((w, h, rgba)) = decode_rgba(&bytes) {
                let _ = self.tx.send(CoverMsg::Ready {
                    base_id,
                    width: w,
                    height: h,
                    rgba,
                });
                return;
            }
        }

        let url = url.to_string();
        let client = self.client.clone();
        let tx = self.tx.clone();
        thread::spawn(move || {
            let result = (|| -> Result<(u32, u32, Vec<u8>), String> {
                let resp = client
                    .get(&url)
                    .send()
                    .map_err(|e| format!("download failed: {e}"))?;
                if !resp.status().is_success() {
                    return Err(format!("HTTP {}", resp.status()));
                }
                let bytes = resp.bytes().map_err(|e| e.to_string())?;
                let decoded = decode_rgba(&bytes)
                    .ok_or_else(|| "unsupported image".to_string())?;
                let _ = fs::create_dir_all(cache_file.parent().unwrap_or(Path::new(".")));
                let _ = fs::write(&cache_file, &bytes);
                Ok(decoded)
            })();
            match result {
                Ok((w, h, rgba)) => {
                    let _ = tx.send(CoverMsg::Ready {
                        base_id,
                        width: w,
                        height: h,
                        rgba,
                    });
                }
                Err(reason) => {
                    let _ = tx.send(CoverMsg::Failed { base_id, reason });
                }
            }
        });
    }

    fn poll_uploads(&mut self, gl: &glow::Context, renderer: &mut AutoRenderer) {
        let Some(rx) = &self.rx else {
            return;
        };
        loop {
            match rx.try_recv() {
                Ok(CoverMsg::Ready {
                    base_id,
                    width,
                    height,
                    rgba,
                }) => {
                    self.requested.remove(&base_id);
                    let Some(tex) = upload_texture(gl, width, height, &rgba) else {
                        continue;
                    };
                    let Some(tex_id) = renderer.texture_map_mut().register(tex) else {
                        continue;
                    };
                    self.loaded.insert(base_id, (tex_id, width, height));
                }
                Ok(CoverMsg::Failed { base_id, reason }) => {
                    self.requested.remove(&base_id);
                    eprintln!("cover failed for {base_id}: {reason}");
                }
                Err(TryRecvError::Empty) => break,
                Err(TryRecvError::Disconnected) => break,
            }
        }
    }

    /// Drop GL textures from a previous scan once the scan generation changes.
    fn reset_for_new_scan(&mut self, gl: &glow::Context, generation: u64, renderer: &mut AutoRenderer) {
        if generation == self.generation {
            return;
        }
        self.generation = generation;
        for (_, (tex_id, _, _)) in self.loaded.drain() {
            if let Some(tex) = renderer.texture_map().gl_texture(tex_id) {
                unsafe { gl.delete_texture(tex) };
            }
        }
        self.requested.clear();
    }

    fn texture(&self, base_id: &str) -> Option<(TextureId, u32, u32)> {
        self.loaded.get(base_id).copied()
    }
}

fn decode_rgba(bytes: &[u8]) -> Option<(u32, u32, Vec<u8>)> {
    let img = image::load_from_memory(bytes).ok()?;
    let rgba = img.to_rgba8();
    let (w, h) = rgba.dimensions();
    let raw = rgba.into_raw();
    // Flip vertically: OpenGL textures have origin at bottom-left, imgui top-left.
    let row = w as usize * 4;
    let mut flipped = vec![0u8; raw.len()];
    for y in 0..h as usize {
        let src = y * row;
        let dst = (h as usize - 1 - y) * row;
        flipped[dst..dst + row].copy_from_slice(&raw[src..src + row]);
    }
    Some((w, h, flipped))
}

fn upload_texture(gl: &glow::Context, w: u32, h: u32, rgba: &[u8]) -> Option<glow::Texture> {
    unsafe {
        let tex = gl.create_texture().ok()?;
        gl.bind_texture(glow::TEXTURE_2D, Some(tex));
        gl.tex_image_2d(
            glow::TEXTURE_2D,
            0,
            glow::RGBA8 as i32,
            w as i32,
            h as i32,
            0,
            glow::RGBA,
            glow::UNSIGNED_BYTE,
            Some(rgba),
        );
        gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_MIN_FILTER, glow::LINEAR as i32);
        gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_MAG_FILTER, glow::LINEAR as i32);
        gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_WRAP_S, glow::CLAMP_TO_EDGE as i32);
        gl.tex_parameter_i32(glow::TEXTURE_2D, glow::TEXTURE_WRAP_T, glow::CLAMP_TO_EDGE as i32);
        Some(tex)
    }
}

// ---------------------------------------------------------------------------
// imgui UI
// ---------------------------------------------------------------------------

fn status_color(status: &str) -> [f32; 4] {
    match status {
        "Success" => [0.2, 0.8, 0.3, 1.0],
        "Failed" | "Validation error" | "Worker disconnected" => [0.9, 0.25, 0.25, 1.0],
        "Running" | "Scanning" | "Importing" | "Deleting" | "Renaming" | "Refreshing TitlesDB" => {
            [0.95, 0.7, 0.15, 1.0]
        }
        _ => [0.8, 0.8, 0.8, 1.0],
    }
}

fn text_input(ui: &Ui, id: &str, value: &mut String) -> bool {
    ui.input_text(id, value).build()
}

fn library_tab(
    ui: &Ui,
    state: &mut AppState,
    covers: &mut CoverCache,
    index: Option<&nscb::nutdb::NutdbIndex>,
) {
    ui_scan_controls(ui, state);

    ui.separator();
    ui.text_colored(status_color(&state.run_status), &format!("Status: {}", state.run_status));
    if !state.status_detail.is_empty() {
        ui.text_wrapped(&state.status_detail);
    }
    if state.is_running {
        ui.text("Working... check the Logs tab for progress.");
    }

    ui.separator();

    if state.scan_results.is_empty() {
        ui.text_wrapped("No scan results yet. Choose a library folder and click Scan Library.");
        return;
    }

    // Request covers for every group (from NUTDB index cache).
    if let Some(index) = index {
        for group in &state.scan_results {
            let url = index
                .lookup(&group.base_id)
                .and_then(|t| t.banner_url.clone().or_else(|| t.icon_url.clone()));
            covers.request(&group.base_id, url.as_deref());
        }
    }

    // Cover grid.
    let avail = ui.content_region_max();
    let cols = ((avail[0] / 240.0).floor() as usize).clamp(1, 6);
    ui.columns(cols as i32, "cover_grid", true);
    let mut clicked: Option<usize> = None;
    for (i, group) in state.scan_results.iter().enumerate() {
        let (thumb_w, thumb_h) = (220.0, 124.0);
        if let Some((tex, _w, _h)) = covers.texture(&group.base_id) {
            if ui.image_button(&format!("##cover{i}"), tex, [thumb_w, thumb_h]) {
                clicked = Some(i);
            }
        } else {
            // Placeholder box while the cover downloads.
            ui.text_colored([0.35, 0.4, 0.5, 1.0], "loading cover...");
            if ui.button("view") {
                clicked = Some(i);
            }
        }
        ui.text_wrapped(&group.title_name);
        ui.text_colored(
            [0.6, 0.65, 0.7, 1.0],
            &format!("{} file(s)", group.items.len()),
        );
        ui.next_column();
    }
    ui.columns(1, "cover_grid_end", true);

    if let Some(i) = clicked {
        state.selected_scan_group = i;
        state.show_details = true;
    }

    if state.show_details {
        if !state.details_popup_open {
            ui.open_popup("##game_details");
            state.details_popup_open = true;
        }
        ui.modal_popup("##game_details", || {
            details_popup(ui, state, covers, index);
        });
    } else {
        state.details_popup_open = false;
    }
}

fn ui_scan_controls(ui: &Ui, state: &mut AppState) {
    ui.group(|| {
        ui.text("Per-platform library:");
        // Platform picker (drives which platform's ROMs folder we scan/manage).
        let platforms = nscb::platform::Platform::all();
        let names: Vec<&str> = platforms.iter().map(|p| p.name()).collect();
        let mut idx = platforms
            .iter()
            .position(|p| p.id() == state.active_platform_id)
            .unwrap_or(0) as i32;
        if ui_combo(ui, "##active_platform", &mut idx, &names) {
            state.active_platform_id = platforms[idx as usize].id().to_string();
            state.sync_scan_path_from_active_platform();
        }
        // Show the active platform's configured ROMs folder.
        let configured = state.platform_library_folder(&state.active_platform_id);
        ui.text_colored(
            [0.6, 0.65, 0.7, 1.0],
            &format!("{} library: {}",
                state.active_platform_name(),
                if configured.is_empty() { "(not set)".to_string() } else { configured }),
        );
        if ui.button("Set this platform's ROMs folder") {
            if let Some(path) = FileDialog::new().pick_folder() {
                let id = state.active_platform_id.clone();
                state.set_platform_library_folder(&id, &path.display().to_string());
            }
        }
        if ui.button("Reuse current folder") {
            let id = state.active_platform_id.clone();
            let folder = state.scan_path.clone();
            state.set_platform_library_folder(&id, &folder);
        }
    });
    ui.group(|| {
        ui.text("Library folder:");
        ui.set_next_item_width(320.0);
        text_input(ui, "##scan_path", &mut state.scan_path);
        if ui.button("Browse") {
            if let Some(path) = FileDialog::new().pick_folder() {
                state.scan_path = path.display().to_string();
            }
        }
        if ui.button("Scan Library") && !state.is_running {
            state.start_scan();
        }
        if ui.button("Bulk Rename") && !state.is_running {
            state.start_bulk_rename_scan_path();
        }
    });
    ui.group(|| {
        ui.text("Keys (prod.keys):");
        ui.set_next_item_width(320.0);
        text_input(ui, "##keys_path", &mut state.keys_path);
        if ui.button("Browse key file") {
            if let Some(path) = FileDialog::new().pick_file() {
                state.keys_path = path.display().to_string();
            }
        }
        if ui.button("Refresh TitlesDB") && !state.is_running {
            state.start_refresh_title_db();
        }
    });
    ui.group(|| {
        ui.text("Import folder:");
        ui.set_next_item_width(260.0);
        text_input(ui, "##import_folder", &mut state.import_folder);
        if ui.button("Browse import") {
            if let Some(path) = FileDialog::new().pick_folder() {
                state.import_folder = path.display().to_string();
            }
        }
        ui.text("Output library:");
        ui.set_next_item_width(200.0);
        text_input(ui, "##output_folder", &mut state.output_folder);
        if ui.button("Browse output") {
            if let Some(path) = FileDialog::new().pick_folder() {
                state.output_folder = path.display().to_string();
            }
        }
        if ui.button("Scan + Import") && !state.is_running {
            state.start_import_folder();
        }
        ui.same_line();
        if ui.small_button("Organize import into platform folders") && !state.is_running {
            state.start_organize_folder();
        }
        ui.checkbox("delete source after organize/import", &mut state.delete_sources_after_import);
    });
}fn details_popup(
    ui: &Ui,
    state: &mut AppState,
    covers: &mut CoverCache,
    index: Option<&nscb::nutdb::NutdbIndex>,
) {
    let Some(group) = state.scan_results.get(state.selected_scan_group) else {
        state.show_details = false;
        return;
    };
    // Take owned copies now so the immutable borrow on state ends before the
    // mutation buttons below run.
    let base_id = group.base_id.clone();
    let title_name = group.title_name.clone();
    let latest_version_db = group.latest_version_db;
    let items = group.items.clone();
    let exact = AppState::exact_duplicate_paths_for_group(group);
    let older = AppState::older_version_paths_for_group(group);

    // Cover image (large).
    if let Some((tex, w, h)) = covers.texture(&base_id) {
        let scale = (480.0 / w as f32).min(270.0 / h as f32).min(1.0);
        let dw = (w as f32 * scale).max(1.0);
        let dh = (h as f32 * scale).max(1.0);
        ui_image(ui, tex, [dw, dh]);
        ui.same_line();
    }

    ui.text(&title_name);
    ui.text_colored([0.6, 0.65, 0.7, 1.0], &format!("Title ID {base_id}"));

    if let Some(index) = index {
        if let Some(title) = index.lookup(&base_id) {
            if let Some(pub_) = &title.publisher {
                ui.text(&format!("Publisher: {pub_}"));
            }
            if let Some(rel) = title.release_date {
                let year = rel / 10000;
                let month = (rel / 100) % 100;
                let day = rel % 100;
                if year >= 1900 && (1..=12).contains(&month) && (1..=31).contains(&day) {
                    ui.text(&format!("Released: {year:04}-{month:02}-{day:02}"));
                }
            }
            if let Some(desc) = &title.description {
                ui.text_wrapped(&format!("Description: {desc}"));
            }
        }
    }

    let local_latest = items
        .iter()
        .map(|item| item.version as u64)
        .max()
        .unwrap_or(0);
    if let Some(latest) = latest_version_db {
        if latest > local_latest {
            ui.text_colored(
                [0.95, 0.7, 0.15, 1.0],
                &format!("OUTDATED: local v{local_latest}, latest v{latest}"),
            );
        } else {
            ui.text_colored([0.2, 0.8, 0.3, 1.0], &format!("CURRENT (v{local_latest})"));
        }
    }
    if !exact.is_empty() {
        ui.text_colored([0.9, 0.25, 0.25, 1.0], &format!("{} exact duplicate(s)", exact.len()));
    }
    if !older.is_empty() {
        ui.text_colored([0.95, 0.7, 0.15, 1.0], &format!("{} older version(s)", older.len()));
    }

    ui.separator();
    if ui.button("Prepare Merge") && !state.is_running {
        state.prepare_scan_group_for_merge(state.selected_scan_group);
        state.show_details = false;
    }
    ui.same_line();
    if ui.button("Rename Game Files") && !state.is_running {
        state.start_rename_scan_group(state.selected_scan_group);
        state.show_details = false;
    }
    ui.same_line();
    if ui.button("Delete Exact Duplicates") && !state.is_running {
        let paths = exact;
        state.start_delete_scanned_files(paths, "exact duplicate");
        state.show_details = false;
    }
    ui.same_line();
    if ui.button("Delete Older Versions") && !state.is_running {
        let paths = older;
        state.start_delete_scanned_files(paths, "older version");
        state.show_details = false;
    }
    ui.same_line();
    if ui.button("Close") {
        state.show_details = false;
    }

    ui.separator();
    ui.text("Files:");
    let mut delete_path: Option<String> = None;
    for item in &items {
        ui.text_colored(
            [0.7, 0.75, 0.8, 1.0],
            &format!(
                "[{}] v{}  {}  {}  {}",
                AppState::kind_label(item.kind),
                item.version / 65536,
                AppState::format_size(item.size),
                AppState::short_hash(&item.sha256),
                item.filename
            ),
        );
        if ui.small_button(&format!("delete##{}", item.path)) {
            delete_path = Some(item.path.clone());
        }
        ui.separator();
    }
    if let Some(path) = delete_path {
        state.start_delete_scanned_file(path);
        state.show_details = false;
    }
}

fn ui_image(ui: &Ui, tex: TextureId, size: [f32; 2]) {
    // Rendered via Image::new with uv hint to keep GL orientation correct.
    imgui::Image::new(tex, size).build(ui);
}

fn operations_tab(ui: &Ui, state: &mut AppState) {
    let ops = GuiOperation::all();
    let labels: Vec<&str> = ops.iter().map(|op| op.label()).collect();
    let mut op_idx = ops
        .iter()
        .position(|op| *op == state.operation)
        .unwrap_or(0) as i32;
    if ui_combo(ui, "Operation", &mut op_idx, &labels) {
        state.operation = ops[op_idx as usize];
    }

    ui.separator();
    ui.text("Input path:");
    ui.set_next_item_width(420.0);
    text_input(ui, "##input_path", &mut state.input_path);
    if ui.button("Browse file") {
        if let Some(path) = FileDialog::new().pick_file() {
            state.input_path = path.display().to_string();
        }
    }
    ui.same_line();
    if ui.button("Browse dir") {
        if let Some(path) = FileDialog::new().pick_folder() {
            state.input_path = path.display().to_string();
            if state.operation == GuiOperation::Merge {
                let selected = path.clone();
                state.populate_merge_list_from_folder(&selected);
            }
        }
    }

    if state.operation == GuiOperation::Merge {
        ui.text("Merge inputs (one path per line):");
        ui.input_text_multiline("##merge_list", &mut state.input_list, [420.0, 150.0])
            .build();
        if ui.button("Add file to merge list") {
            if let Some(path) = FileDialog::new().pick_file() {
                if !state.input_list.trim().is_empty() {
                    state.input_list.push('\n');
                }
                state.input_list.push_str(&path.display().to_string());
            }
        }
        ui.same_line();
        if ui.button("Scan folder into merge list") {
            let folder = PathBuf::from(state.input_path.trim());
            if folder.is_dir() {
                state.populate_merge_list_from_folder(&folder);
            }
        }
        ui.checkbox("nodelta", &mut state.nodelta);
        ui.same_line();
        ui.checkbox("pv (print version changes)", &mut state.print_version);
        ui.text("RSVcap:");
        ui.set_next_item_width(120.0);
        text_input(ui, "##rsvcap", &mut state.rsvcap);
        ui.same_line();
        ui.text("keypatch:");
        ui.set_next_item_width(120.0);
        text_input(ui, "##keypatch", &mut state.keypatch);
    }

    if state.operation == GuiOperation::Create {
        ui.text("Create output path:");
        ui.set_next_item_width(360.0);
        text_input(ui, "##create_output", &mut state.create_output_path);
        if ui.button("Save as") {
            if let Some(path) = FileDialog::new().save_file() {
                state.create_output_path = path.display().to_string();
            }
        }
        ui.text("Input folder (--ifolder):");
        ui.set_next_item_width(360.0);
        text_input(ui, "##ifolder", &mut state.ifolder);
        if ui.button("Browse") {
            if let Some(path) = FileDialog::new().pick_folder() {
                state.ifolder = path.display().to_string();
            }
        }
    }

    if state.operation == GuiOperation::Rename {
        ui.text("renmode:");
        ui.set_next_item_width(140.0);
        text_input(ui, "##renmode", &mut state.renmode);
        ui.same_line();
        ui.text("addlangue:");
        ui.set_next_item_width(80.0);
        text_input(ui, "##addlangue", &mut state.addlangue);
        ui.text("noversion:");
        ui.set_next_item_width(140.0);
        text_input(ui, "##noversion", &mut state.noversion);
        ui.same_line();
        ui.text("dlcrname:");
        ui.set_next_item_width(80.0);
        text_input(ui, "##dlcrname", &mut state.dlcrname);
    }

    if state.operation == GuiOperation::Verify {
        ui.text("vertype (dec/sig/full):");
        ui.set_next_item_width(100.0);
        text_input(ui, "##vertype", &mut state.vertype);
        ui.text("text_file (optional):");
        ui.set_next_item_width(320.0);
        text_input(ui, "##text_file", &mut state.text_file);
        if ui.button("Save as") {
            if let Some(path) = FileDialog::new().save_file() {
                state.text_file = path.display().to_string();
            }
        }
    }

    if matches!(
        state.operation,
        GuiOperation::Merge | GuiOperation::Dspl | GuiOperation::Convert
    ) {
        ui.text("Output type (nsp/xci):");
        ui.set_next_item_width(100.0);
        text_input(ui, "##output_type", &mut state.output_type);
    }

    if state.operation == GuiOperation::Compress {
        ui.text("Compression level:");
        ui.slider("##level", 1, 22, &mut state.compression_level);
        let modes = [
            CompressInputMode::Auto.label(),
            CompressInputMode::Nsp.label(),
            CompressInputMode::Xci.label(),
        ];
        let mut idx = match state.compress_mode {
            CompressInputMode::Auto => 0,
            CompressInputMode::Nsp => 1,
            CompressInputMode::Xci => 2,
        } as i32;
        if ui_combo(ui, "Compress mode", &mut idx, &modes) {
            state.compress_mode = match idx {
                1 => CompressInputMode::Nsp,
                2 => CompressInputMode::Xci,
                _ => CompressInputMode::Auto,
            };
        }
    }

    if state.operation == GuiOperation::Decompress {
        let modes = [
            DecompressInputMode::Auto.label(),
            DecompressInputMode::Nsz.label(),
            DecompressInputMode::Xcz.label(),
            DecompressInputMode::Ncz.label(),
        ];
        let mut idx = match state.decompress_mode {
            DecompressInputMode::Auto => 0,
            DecompressInputMode::Nsz => 1,
            DecompressInputMode::Xcz => 2,
            DecompressInputMode::Ncz => 3,
        } as i32;
        if ui_combo(ui, "Decompress mode", &mut idx, &modes) {
            state.decompress_mode = match idx {
                1 => DecompressInputMode::Nsz,
                2 => DecompressInputMode::Xcz,
                3 => DecompressInputMode::Ncz,
                _ => DecompressInputMode::Auto,
            };
        }
    }

    ui.separator();
    ui.text("Output folder:");
    ui.set_next_item_width(360.0);
    text_input(ui, "##output_folder", &mut state.output_folder);
    if ui.button("Browse") {
        if let Some(path) = FileDialog::new().pick_folder() {
            state.output_folder = path.display().to_string();
        }
    }

    ui.separator();
    ui.text_colored(status_color(&state.run_status), &format!("Status: {}", state.run_status));
    if !state.status_detail.is_empty() {
        ui.text_wrapped(&state.status_detail);
    }

    ui.separator();
    if ui.button("Run operation") && !state.is_running {
        state.start_run();
    }
    ui.same_line();
    if ui.button("Clear fields") && !state.is_running {
        state.clear_fields_for_next_job();
    }
    ui.same_line();
    if ui.button("Clear log") {
        state.log.clear();
    }
}

fn ui_combo(ui: &Ui, label: &str, idx: &mut i32, items: &[&str]) -> bool {
    let mut current = *idx as usize;
    let changed = ui.combo_simple_string(label, &mut current, items);
    *idx = current as i32;
    changed
}

fn logs_tab(ui: &Ui, state: &mut AppState) {
    let avail = ui.content_region_avail();
    ui.input_text_multiline("##log", &mut state.log, [avail[0], avail[1]])
        .read_only(true)
        .build();
}

// ---------------------------------------------------------------------------
// winit + glutin + glow bootstrap (mirrors the imgui-glow-renderer example)
// ---------------------------------------------------------------------------

#[allow(clippy::type_complexity)]
fn create_window(
    title: &str,
) -> (
    EventLoop<()>,
    Window,
    Surface<WindowSurface>,
    PossiblyCurrentContext,
) {
    let event_loop = EventLoop::new().unwrap();

    let window_attributes = WindowAttributes::default()
        .with_title(title)
        .with_inner_size(LogicalSize::new(1380.0, 920.0));
    let (window, cfg) = glutin_winit::DisplayBuilder::new()
        .with_window_attributes(Some(window_attributes))
        .build(&event_loop, ConfigTemplateBuilder::new(), |mut configs| {
            configs.next().unwrap()
        })
        .expect("Failed to create OpenGL window");

    let window = window.unwrap();

    let context_attribs = ContextAttributesBuilder::new().build(Some(
        window.window_handle().unwrap().as_raw(),
    ));
    let context = unsafe {
        cfg.display()
            .create_context(&cfg, &context_attribs)
            .expect("Failed to create OpenGL context")
    };

    let surface_attribs = SurfaceAttributesBuilder::<WindowSurface>::new()
        .with_srgb(Some(true))
        .build(
            window.window_handle().unwrap().as_raw(),
            NonZeroU32::new(1380).unwrap(),
            NonZeroU32::new(920).unwrap(),
        );
    let surface = unsafe {
        cfg.display()
            .create_window_surface(&cfg, &surface_attribs)
            .expect("Failed to create OpenGL surface")
    };

    let context = context
        .make_current(&surface)
        .expect("Failed to make OpenGL context current");

    surface
        .set_swap_interval(&context, SwapInterval::Wait(NonZeroU32::new(1).unwrap()))
        .expect("Failed to set swap interval");

    (event_loop, window, surface, context)
}

fn glow_context(context: &PossiblyCurrentContext) -> glow::Context {
    unsafe {
        glow::Context::from_loader_function_cstr(|s| context.display().get_proc_address(s).cast())
    }
}

fn imgui_init(window: &Window) -> (WinitPlatform, imgui::Context) {
    let mut imgui_context = imgui::Context::create();
    imgui_context.set_ini_filename(None);

    let mut winit_platform = WinitPlatform::new(&mut imgui_context);
    winit_platform.attach_window(imgui_context.io_mut(), window, HiDpiMode::Rounded);

    // Try to load a nicer proportional font if available.
    for candidate in [
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf",
    ] {
        if let Ok(bytes) = fs::read(candidate) {
            imgui_context.fonts().add_font(&[FontSource::TtfData {
                data: &bytes,
                size_pixels: 18.0,
                config: None,
            }]);
            break;
        }
    }

    imgui_context.io_mut().font_global_scale = (1.0 / winit_platform.hidpi_factor()) as f32;

    (winit_platform, imgui_context)
}

fn main() {
    let (event_loop, window, surface, context) = create_window("NSCB Desktop GUI");
    let (mut winit_platform, mut imgui_context) = imgui_init(&window);

    let gl = glow_context(&context);
    let mut renderer = AutoRenderer::new(gl, &mut imgui_context).expect("failed to create renderer");

    let mut state = AppState::default();
    let mut covers = CoverCache::new();
    let mut nutdb_index: Option<nscb::nutdb::NutdbIndex> = None;
    let mut last_frame = Instant::now();

    #[allow(deprecated)]
    event_loop
        .run(move |event, window_target| {
            match event {
                Event::NewEvents(_) => {
                    let now = Instant::now();
                    imgui_context
                        .io_mut()
                        .update_delta_time(now.duration_since(last_frame));
                    last_frame = now;
                }
                Event::AboutToWait => {
                    if let Err(err) =
                        winit_platform.prepare_frame(imgui_context.io_mut(), &window)
                    {
                        eprintln!("prepare_frame failed: {err}");
                        return;
                    }
                    window.request_redraw();
                }
                Event::WindowEvent {
                    event: WindowEvent::RedrawRequested,
                    ..
                } => {
                    // The renderer assumes you'll be clearing the buffer yourself.
                    unsafe { renderer.gl_context().clear(glow::COLOR_BUFFER_BIT) };

                    // Drain worker events + finished cover downloads.
                    state.check_worker();
                    let gl = renderer.gl_context().clone();
                    covers.reset_for_new_scan(&gl, state.scan_generation, &mut renderer);
                    covers.poll_uploads(&gl, &mut renderer);

                    // Load NUTDB index lazily for cover art + metadata.
                    if nutdb_index.is_none() {
                        nutdb_index = NutdbStore::new(None, None)
                            .try_load_cached_index()
                            .ok()
                            .flatten();
                    }

                    let ui = imgui_context.frame();
                    let display = ui.io().display_size;
                    let index_ref = nutdb_index.as_ref();

                    ui.window("NSCB Desktop GUI")
                        .size([display[0], display[1]], Condition::Always)
                        .position([0.0, 0.0], Condition::Always)
                        .title_bar(false)
                        .movable(false)
                        .resizable(false)
                        .collapsible(false)
                        .build(|| {
                            if let Some(_bar) = ui.tab_bar("main_tabs") {
                                if let Some(_tab) = ui.tab_item("Library") {
                                    library_tab(ui, &mut state, &mut covers, index_ref);
                                }
                                if let Some(_tab) = ui.tab_item("Operations") {
                                    operations_tab(ui, &mut state);
                                }
                                if let Some(_tab) = ui.tab_item("Logs") {
                                    logs_tab(ui, &mut state);
                                }
                            }
                        });

                    winit_platform.prepare_render(ui, &window);
                    let draw_data = imgui_context.render();

                    if let Err(err) = renderer.render(draw_data) {
                        eprintln!("render failed: {err}");
                        return;
                    }
                    if let Err(err) = surface.swap_buffers(&context) {
                        eprintln!("swap_buffers failed: {err}");
                    }
                }
                Event::WindowEvent {
                    event: WindowEvent::CloseRequested,
                    ..
                } => {
                    window_target.exit();
                }
                Event::WindowEvent {
                    event: WindowEvent::Resized(new_size),
                    ..
                } => {
                    if new_size.width > 0 && new_size.height > 0 {
                        surface.resize(
                            &context,
                            NonZeroU32::new(new_size.width).unwrap(),
                            NonZeroU32::new(new_size.height).unwrap(),
                        );
                    }
                    winit_platform.handle_event(imgui_context.io_mut(), &window, &event);
                }
                event => {
                    winit_platform.handle_event(imgui_context.io_mut(), &window, &event);
                }
            }
        })
        .expect("EventLoop error");
}
