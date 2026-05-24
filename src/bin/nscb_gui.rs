use eframe::egui;
use nscb::keys::KeyStore;
use nscb::nutdb::NutdbStore;
use rfd::FileDialog;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::fs;
use std::io::{BufRead, BufReader, Read};
use std::path::Path;
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::sync::mpsc::{self, Receiver, TryRecvError};
use std::thread;

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
    scan_results: Vec<ScanGroup>,
    selected_scan_group: usize,
    is_running: bool,
    run_status: String,
    status_detail: String,
    progress_lines: usize,
    ui_style_applied: bool,
    log: String,
    worker_rx: Option<Receiver<WorkerEvent>>,
}

#[derive(Debug)]
enum WorkerEvent {
    Started {
        command_line: String,
    },
    StdoutLine(String),
    StderrLine(String),
    Finished {
        status_ok: bool,
        exit_code: Option<i32>,
    },
    SpawnError(String),
    ScanProgress(String),
    ScanFinished(Result<Vec<ScanGroup>, String>),
    DeleteFinished(Result<String, String>),
    RenameFinished(Result<String, String>),
    ImportFolderFinished(Result<String, String>),
    RefreshTitleDbFinished(Result<String, String>),
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct GuiPrefs {
    keys_path: String,
    output_folder: String,
    import_folder: String,
    scan_path: String,
    delete_sources_after_import: bool,
    analyze_package_before_import: bool,
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
            scan_results: Vec::new(),
            selected_scan_group: 0,
            is_running: false,
            run_status: "Idle".to_string(),
            status_detail: String::new(),
            progress_lines: 0,
            ui_style_applied: false,
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
    fn apply_readable_style(&mut self, ctx: &egui::Context) {
        if self.ui_style_applied {
            return;
        }

        let mut style = (*ctx.style()).clone();
        style.spacing.item_spacing = egui::vec2(10.0, 10.0);
        style.spacing.button_padding = egui::vec2(12.0, 8.0);
        style.spacing.interact_size.y = 32.0;

        style.text_styles.insert(
            egui::TextStyle::Heading,
            egui::FontId::new(28.0, egui::FontFamily::Proportional),
        );
        style.text_styles.insert(
            egui::TextStyle::Body,
            egui::FontId::new(18.0, egui::FontFamily::Proportional),
        );
        style.text_styles.insert(
            egui::TextStyle::Button,
            egui::FontId::new(17.0, egui::FontFamily::Proportional),
        );
        style.text_styles.insert(
            egui::TextStyle::Monospace,
            egui::FontId::new(16.0, egui::FontFamily::Monospace),
        );
        style.text_styles.insert(
            egui::TextStyle::Small,
            egui::FontId::new(15.0, egui::FontFamily::Proportional),
        );

        ctx.set_style(style);
        ctx.set_pixels_per_point(1.08);
        self.ui_style_applied = true;
    }

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
        self.delete_sources_after_import = prefs.delete_sources_after_import;
        self.analyze_package_before_import = prefs.analyze_package_before_import;
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
                        return Err("Decompress input must be .nsz, .xcz, or .ncz (or choose the correct mode).".to_string());
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
        self.scan_results.clear();
        self.selected_scan_group = 0;
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
            return Err(format!("Import folder is not a directory: {}", input_root.display()));
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
            self.status_detail =
                "Background worker disconnected before producing output.".to_string();
            self.push_log_line("Worker disconnected before producing output.");
            self.worker_rx = None;
        }
    }
}

impl eframe::App for AppState {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        self.apply_readable_style(ctx);
        self.check_worker();
        if self.is_running {
            ctx.request_repaint_after(std::time::Duration::from_millis(250));
        }

        egui::TopBottomPanel::top("top_panel").show(ctx, |ui| {
            ui.heading("NSCB Desktop GUI");
            ui.label("Desktop wrapper around the existing nscb CLI operations.");
            ui.separator();
        });

        egui::CentralPanel::default().show(ctx, |ui| {
            ui.horizontal(|ui| {
                ui.label("Operation");
                egui::ComboBox::from_id_salt("operation_combo")
                    .selected_text(self.operation.label())
                    .show_ui(ui, |ui| {
                        for op in GuiOperation::all() {
                            ui.selectable_value(&mut self.operation, op, op.label());
                        }
                    });
            });

            ui.separator();

            ui.horizontal(|ui| {
                ui.label("Input path");
                ui.text_edit_singleline(&mut self.input_path);
                if ui.button("Browse file").clicked() {
                    let mut dialog = FileDialog::new();
                    if !self.scan_path.trim().is_empty() {
                        dialog = dialog.set_directory(&self.scan_path);
                    }
                    if let Some(path) = dialog.pick_file() {
                        self.input_path = path.display().to_string();
                    }
                }
                if ui.button("Browse dir").clicked() {
                    let mut dialog = FileDialog::new();
                    if !self.scan_path.trim().is_empty() {
                        dialog = dialog.set_directory(&self.scan_path);
                    }
                    if let Some(path) = dialog.pick_folder() {
                        self.input_path = path.display().to_string();
                        if self.operation == GuiOperation::Merge {
                            let selected = path.clone();
                            self.populate_merge_list_from_folder(&selected);
                        }
                    }
                }
            });

            if self.operation == GuiOperation::Merge {
                egui::ScrollArea::vertical()
                    .max_height(180.0)
                    .show(ui, |ui| {
                        ui.add(
                            egui::TextEdit::multiline(&mut self.input_list)
                                .desired_rows(8)
                                .desired_width(f32::INFINITY)
                                .hint_text("/path/base.nsp\n/path/update.nsz\n/path/dlc.nsp"),
                        );
                    });
                if ui.button("Add file to merge list").clicked() {
                    let mut dialog = FileDialog::new();
                    if !self.scan_path.trim().is_empty() {
                        dialog = dialog.set_directory(&self.scan_path);
                    }
                    if let Some(path) = dialog.pick_file() {
                        if !self.input_list.trim().is_empty() {
                            self.input_list.push('\n');
                        }
                        self.input_list.push_str(&path.display().to_string());
                    }
                }
                if ui.button("Scan folder into merge list").clicked() {
                    if self.input_path.trim().is_empty() {
                        self.push_log_line("Set Input path to a folder first, then click Scan folder into merge list.");
                    } else {
                        let folder = PathBuf::from(self.input_path.trim());
                        if folder.is_dir() {
                            self.populate_merge_list_from_folder(&folder);
                        } else {
                            self.push_log_line("Input path is not a folder; choose a directory for merge scan.");
                        }
                    }
                }
                ui.horizontal(|ui| {
                    ui.checkbox(&mut self.nodelta, "nodelta");
                    ui.checkbox(&mut self.print_version, "pv (print version changes)");
                });
                ui.label("Merge requires prod.keys. Set Keys path, or place prod.keys in a default location the CLI can detect.");
                ui.horizontal(|ui| {
                    ui.label("RSVcap");
                    ui.text_edit_singleline(&mut self.rsvcap);
                    ui.label("keypatch");
                    ui.text_edit_singleline(&mut self.keypatch);
                });
            }

            if self.operation == GuiOperation::Create {
                ui.horizontal(|ui| {
                    ui.label("Create output path");
                    ui.text_edit_singleline(&mut self.create_output_path);
                    if ui.button("Save as").clicked() {
                        if let Some(path) = FileDialog::new().save_file() {
                            self.create_output_path = path.display().to_string();
                        }
                    }
                });
                ui.horizontal(|ui| {
                    ui.label("Input folder (--ifolder)");
                    ui.text_edit_singleline(&mut self.ifolder);
                    if ui.button("Browse").clicked() {
                        if let Some(path) = FileDialog::new().pick_folder() {
                            self.ifolder = path.display().to_string();
                        }
                    }
                });
            }

            if self.operation == GuiOperation::Rename {
                ui.horizontal(|ui| {
                    ui.label("renmode");
                    ui.text_edit_singleline(&mut self.renmode);
                    ui.label("addlangue");
                    ui.text_edit_singleline(&mut self.addlangue);
                });
                ui.horizontal(|ui| {
                    ui.label("noversion");
                    ui.text_edit_singleline(&mut self.noversion);
                    ui.label("dlcrname");
                    ui.text_edit_singleline(&mut self.dlcrname);
                });
            }

            if self.operation == GuiOperation::Verify {
                ui.horizontal(|ui| {
                    ui.label("vertype");
                    ui.text_edit_singleline(&mut self.vertype);
                });
                ui.horizontal(|ui| {
                    ui.label("text_file");
                    ui.text_edit_singleline(&mut self.text_file);
                    if ui.button("Save as").clicked() {
                        if let Some(path) = FileDialog::new().save_file() {
                            self.text_file = path.display().to_string();
                        }
                    }
                });
            }

            if self.operation == GuiOperation::Scanner {
                ui.group(|ui| {
                    ui.heading("APK-style Library Workflow");
                    ui.horizontal(|ui| {
                        ui.label("Import folder");
                        ui.text_edit_singleline(&mut self.import_folder);
                        if ui.button("Browse").clicked() {
                            if let Some(path) = FileDialog::new().pick_folder() {
                                self.import_folder = path.display().to_string();
                            }
                        }
                    });
                    ui.horizontal(|ui| {
                        ui.checkbox(
                            &mut self.analyze_package_before_import,
                            "Analyze package before import",
                        );
                        ui.checkbox(
                            &mut self.delete_sources_after_import,
                            "Delete source files after successful import",
                        );
                    });
                    ui.horizontal(|ui| {
                        if ui
                            .add_enabled(
                                !self.is_running,
                                egui::Button::new("Scan + Bulk Import Folder"),
                            )
                            .clicked()
                        {
                            self.start_import_folder();
                        }
                        if ui
                            .add_enabled(!self.is_running, egui::Button::new("Refresh TitlesDB"))
                            .clicked()
                        {
                            self.start_refresh_title_db();
                        }
                    });
                });
                ui.separator();
                ui.horizontal(|ui| {
                    ui.label("Library folder");
                    ui.text_edit_singleline(&mut self.scan_path);
                    if ui.button("Browse").clicked() {
                        if let Some(path) = FileDialog::new().pick_folder() {
                            self.scan_path = path.display().to_string();
                        }
                    }
                });
                ui.horizontal(|ui| {
                    if ui
                        .add_enabled(!self.is_running, egui::Button::new("Scan Library"))
                        .clicked()
                    {
                        self.start_scan();
                    }
                    if ui
                        .add_enabled(!self.is_running, egui::Button::new("Bulk Rename"))
                        .clicked()
                    {
                        self.start_bulk_rename_scan_path();
                    }
                    ui.label(format!("{} group(s)", self.scan_results.len()));
                });

                ui.separator();
                if !self.scan_results.is_empty() {
                    if self.selected_scan_group >= self.scan_results.len() {
                        self.selected_scan_group = self.scan_results.len() - 1;
                    }
                    let current = self.selected_scan_group;
                    let group = self.scan_results[current].clone();
                    let exact_duplicates = Self::exact_duplicate_paths_for_group(&group);
                    let older_versions = Self::older_version_paths_for_group(&group);

                    ui.group(|ui| {
                        ui.horizontal(|ui| {
                            ui.heading("Current Game");
                            ui.label(format!(
                                "{} of {}",
                                current + 1,
                                self.scan_results.len()
                            ));
                            if ui
                                .add_enabled(
                                    !self.is_running && current > 0,
                                    egui::Button::new("Previous"),
                                )
                                .clicked()
                            {
                                self.selected_scan_group -= 1;
                            }
                            if ui
                                .add_enabled(
                                    !self.is_running && current + 1 < self.scan_results.len(),
                                    egui::Button::new("Next"),
                                )
                                .clicked()
                            {
                                self.selected_scan_group += 1;
                            }
                        });
                        ui.horizontal(|ui| {
                            ui.strong(&group.title_name);
                            ui.monospace(&group.base_id);
                            ui.label(format!("{} file(s)", group.items.len()));
                            if let Some(latest) = group.latest_version_db {
                                let local_latest = group
                                    .items
                                    .iter()
                                    .map(|item| item.version as u64)
                                    .max()
                                    .unwrap_or(0);
                                if latest > local_latest {
                                    ui.colored_label(
                                        egui::Color32::from_rgb(220, 150, 40),
                                        format!("outdated: local v{}, latest v{}", local_latest, latest),
                                    );
                                } else {
                                    ui.colored_label(
                                        egui::Color32::from_rgb(30, 170, 90),
                                        "current",
                                    );
                                }
                            }
                            if !exact_duplicates.is_empty() {
                                ui.colored_label(
                                    egui::Color32::from_rgb(210, 80, 70),
                                    format!("{} exact duplicate(s)", exact_duplicates.len()),
                                );
                            }
                            if !older_versions.is_empty() {
                                ui.colored_label(
                                    egui::Color32::from_rgb(220, 150, 40),
                                    format!("{} older version(s)", older_versions.len()),
                                );
                            }
                        });
                        ui.horizontal(|ui| {
                            if ui
                                .add_enabled(
                                    !self.is_running && group.items.len() > 1,
                                    egui::Button::new("Prepare Merge"),
                                )
                                .clicked()
                            {
                                self.prepare_scan_group_for_merge(current);
                            }
                            if ui
                                .add_enabled(
                                    !self.is_running && !exact_duplicates.is_empty(),
                                    egui::Button::new("Delete Exact Duplicates"),
                                )
                                .clicked()
                            {
                                self.start_delete_scanned_files(
                                    exact_duplicates,
                                    "exact duplicate",
                                );
                            }
                            if ui
                                .add_enabled(
                                    !self.is_running && !older_versions.is_empty(),
                                    egui::Button::new("Delete Older Versions"),
                                )
                                .clicked()
                            {
                                self.start_delete_scanned_files(older_versions, "older version");
                            }
                            if ui
                                .add_enabled(!self.is_running, egui::Button::new("Rename Game Files"))
                                .clicked()
                            {
                                self.start_rename_scan_group(current);
                            }
                            if ui
                                .add_enabled(!self.is_running, egui::Button::new("Skip"))
                                .clicked()
                            {
                                self.selected_scan_group =
                                    (self.selected_scan_group + 1).min(self.scan_results.len() - 1);
                            }
                        });
                    });
                    ui.separator();
                }

                egui::ScrollArea::vertical()
                    .max_height(420.0)
                    .show(ui, |ui| {
                        if self.scan_results.is_empty() {
                            ui.label("No scan results.");
                        }
                        for group_index in 0..self.scan_results.len() {
                            let group = self.scan_results[group_index].clone();
                            ui.group(|ui| {
                                let mut latest_version_by_title: HashMap<String, u32> =
                                    HashMap::new();
                                let mut exact_counts: HashMap<(String, u32, u64, String), usize> =
                                    HashMap::new();
                                let mut version_counts: HashMap<(String, u32), usize> =
                                    HashMap::new();
                                for item in &group.items {
                                    latest_version_by_title
                                        .entry(item.title_id.clone())
                                        .and_modify(|version| {
                                            if item.version > *version {
                                                *version = item.version;
                                            }
                                        })
                                        .or_insert(item.version);
                                    *exact_counts
                                        .entry((
                                            item.title_id.clone(),
                                            item.version,
                                            item.size,
                                            item.sha256.clone(),
                                        ))
                                        .or_default() += 1;
                                    *version_counts
                                        .entry((item.title_id.clone(), item.version))
                                        .or_default() += 1;
                                }
                                let exact_duplicate_groups = exact_counts
                                    .values()
                                    .filter(|count| **count > 1)
                                    .count();
                                let older_files = group
                                    .items
                                    .iter()
                                    .filter(|item| {
                                        latest_version_by_title
                                            .get(&item.title_id)
                                            .copied()
                                            .unwrap_or(item.version)
                                            > item.version
                                    })
                                    .count();

                                ui.horizontal(|ui| {
                                    ui.strong(&group.title_name);
                                    ui.monospace(&group.base_id);
                                    if let Some(latest) = group.latest_version_db {
                                        let local_latest = group
                                            .items
                                            .iter()
                                            .map(|item| item.version as u64)
                                            .max()
                                            .unwrap_or(0);
                                        if latest > local_latest {
                                            ui.colored_label(
                                                egui::Color32::from_rgb(220, 150, 40),
                                                format!("OUTDATED v{} -> v{}", local_latest, latest),
                                            );
                                        }
                                    }
                                    if exact_duplicate_groups > 0 {
                                        ui.colored_label(
                                            egui::Color32::from_rgb(210, 80, 70),
                                            format!("{exact_duplicate_groups} duplicate set(s)"),
                                        );
                                    }
                                    if older_files > 0 {
                                        ui.colored_label(
                                            egui::Color32::from_rgb(220, 150, 40),
                                            format!("{older_files} older file(s)"),
                                        );
                                    }
                                    if group.items.len() > 1
                                        && ui
                                            .add_enabled(
                                                !self.is_running,
                                                egui::Button::new("Prepare merge"),
                                            )
                                            .clicked()
                                    {
                                        self.prepare_scan_group_for_merge(group_index);
                                    }
                                });

                                let mut delete_path = None;
                                for item in &group.items {
                                    let exact_key = (
                                        item.title_id.clone(),
                                        item.version,
                                        item.size,
                                        item.sha256.clone(),
                                    );
                                    let version_key = (item.title_id.clone(), item.version);
                                    let exact_duplicates =
                                        exact_counts.get(&exact_key).copied().unwrap_or(0);
                                    let same_version_count =
                                        version_counts.get(&version_key).copied().unwrap_or(0);
                                    let is_older = latest_version_by_title
                                        .get(&item.title_id)
                                        .copied()
                                        .unwrap_or(item.version)
                                        > item.version;
                                    let has_same_version_other_content =
                                        same_version_count > exact_duplicates;

                                    ui.horizontal(|ui| {
                                        ui.label(Self::kind_label(item.kind));
                                        ui.monospace(&item.title_id);
                                        ui.label(format!("v{}", item.version / 65536));
                                        ui.label(Self::format_size(item.size));
                                        ui.monospace(Self::short_hash(&item.sha256));
                                        if exact_duplicates > 1 {
                                            ui.colored_label(
                                                egui::Color32::from_rgb(210, 80, 70),
                                                "EXACT DUPLICATE",
                                            );
                                        } else if has_same_version_other_content {
                                            ui.colored_label(
                                                egui::Color32::from_rgb(220, 150, 40),
                                                "SAME VERSION, DIFFERENT CONTENT",
                                            );
                                        } else if is_older {
                                            ui.colored_label(
                                                egui::Color32::from_rgb(220, 150, 40),
                                                "OLDER VERSION",
                                            );
                                        }
                                        ui.label(&item.filename);
                                        if ui
                                            .add_enabled(
                                                !self.is_running,
                                                egui::Button::new("Delete"),
                                            )
                                            .clicked()
                                        {
                                            delete_path = Some(item.path.clone());
                                        }
                                    });
                                }
                                if let Some(path) = delete_path {
                                    self.start_delete_scanned_file(path);
                                }
                            });
                        }
                    });
            }

            if matches!(
                self.operation,
                GuiOperation::Merge | GuiOperation::Dspl | GuiOperation::Convert
            ) {
                ui.horizontal(|ui| {
                    ui.label("Output type");
                    ui.text_edit_singleline(&mut self.output_type);
                });
            }

            if self.operation == GuiOperation::Compress {
                ui.horizontal(|ui| {
                    ui.label("Compression level");
                    ui.add(egui::Slider::new(&mut self.compression_level, 1..=22));
                });
                ui.horizontal(|ui| {
                    ui.label("Compress mode");
                    egui::ComboBox::from_id_salt("compress_mode_combo")
                        .selected_text(self.compress_mode.label())
                        .show_ui(ui, |ui| {
                            ui.selectable_value(
                                &mut self.compress_mode,
                                CompressInputMode::Auto,
                                CompressInputMode::Auto.label(),
                            );
                            ui.selectable_value(
                                &mut self.compress_mode,
                                CompressInputMode::Nsp,
                                CompressInputMode::Nsp.label(),
                            );
                            ui.selectable_value(
                                &mut self.compress_mode,
                                CompressInputMode::Xci,
                                CompressInputMode::Xci.label(),
                            );
                        });
                });
            }

            if self.operation == GuiOperation::Decompress {
                ui.horizontal(|ui| {
                    ui.label("Decompress mode");
                    egui::ComboBox::from_id_salt("decompress_mode_combo")
                        .selected_text(self.decompress_mode.label())
                        .show_ui(ui, |ui| {
                            ui.selectable_value(
                                &mut self.decompress_mode,
                                DecompressInputMode::Auto,
                                DecompressInputMode::Auto.label(),
                            );
                            ui.selectable_value(
                                &mut self.decompress_mode,
                                DecompressInputMode::Nsz,
                                DecompressInputMode::Nsz.label(),
                            );
                            ui.selectable_value(
                                &mut self.decompress_mode,
                                DecompressInputMode::Xcz,
                                DecompressInputMode::Xcz.label(),
                            );
                            ui.selectable_value(
                                &mut self.decompress_mode,
                                DecompressInputMode::Ncz,
                                DecompressInputMode::Ncz.label(),
                            );
                        });
                });
            }

            ui.horizontal(|ui| {
                ui.label("Output folder");
                ui.text_edit_singleline(&mut self.output_folder);
                if ui.button("Browse").clicked() {
                    if let Some(path) = FileDialog::new().pick_folder() {
                        self.output_folder = path.display().to_string();
                    }
                }
            });

            ui.horizontal(|ui| {
                ui.label("Keys path");
                ui.text_edit_singleline(&mut self.keys_path);
                if ui.button("Browse").clicked() {
                    if let Some(path) = FileDialog::new().pick_file() {
                        self.keys_path = path.display().to_string();
                    }
                }
            });

            ui.separator();

            let status_color = match self.run_status.as_str() {
                "Success" => egui::Color32::from_rgb(30, 170, 90),
                "Failed" | "Validation error" | "Worker disconnected" => {
                    egui::Color32::from_rgb(200, 60, 60)
                }
                "Running" => egui::Color32::from_rgb(220, 170, 40),
                _ => egui::Color32::from_rgb(180, 180, 180),
            };
            ui.colored_label(status_color, format!("Status: {}", self.run_status));
            if !self.status_detail.trim().is_empty() {
                ui.label(self.status_detail.as_str());
            }
            if self.is_running {
                ui.horizontal(|ui| {
                    ui.spinner();
                    ui.label(format!("Streaming progress... {} log lines", self.progress_lines));
                });
            }

            ui.horizontal(|ui| {
                let run_button = ui.add_enabled(!self.is_running, egui::Button::new("Run operation"));
                if run_button.clicked() {
                    self.start_run();
                }

                if ui.button("Clear log").clicked() {
                    self.log.clear();
                }

                if ui
                    .add_enabled(!self.is_running, egui::Button::new("Clear fields"))
                    .clicked()
                {
                    self.clear_fields_for_next_job();
                }
            });

            ui.separator();
            egui::ScrollArea::vertical()
                .auto_shrink([false, false])
                .show(ui, |ui| {
                ui.add(
                    egui::TextEdit::multiline(&mut self.log)
                        .desired_rows(18)
                        .desired_width(f32::INFINITY),
                );
            });
        });
    }
}

fn main() {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([1380.0, 920.0])
            .with_min_inner_size([980.0, 680.0]),
        ..Default::default()
    };
    let app = AppState::default();
    let run = eframe::run_native(
        "NSCB Desktop GUI",
        options,
        Box::new(|_cc| Ok(Box::new(app))),
    );

    if let Err(err) = run {
        eprintln!("Failed to start GUI: {err}");
        std::process::exit(1);
    }
}
