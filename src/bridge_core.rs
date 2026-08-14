// Platform-neutral bridge logic shared by the Android JNI bridge and the
// C FFI bridge (iOS / Flutter). All functions are string-in / string-out;
// structured results are JSON.

use crate::cli::{MergeKind, RenameOptions};
use crate::keys::KeyStore;
use crate::nutdb::{base_title_id, NutdbStore};
use crate::util::progress;
use serde::Serialize;
use std::collections::HashMap;
use std::fs::File;
use std::io::BufReader;
use std::path::{Path, PathBuf};

pub fn require_keys(keys_path: &str) -> Result<KeyStore, String> {
    if keys_path.trim().is_empty() {
        return Err("prod.keys path is required".to_string());
    }
    KeyStore::from_default_locations(Some(keys_path)).map_err(|e| format!("Key error: {e}"))
}

fn format_release_date(value: u64) -> String {
    let year = value / 10000;
    let month = (value / 100) % 100;
    let day = value % 100;
    if year >= 1900 && (1..=12).contains(&month) && (1..=31).contains(&day) {
        format!("{year:04}-{month:02}-{day:02}")
    } else {
        value.to_string()
    }
}

#[derive(Serialize)]
struct ScanFile {
    path: String,
    filename: String,
    title_id: String,
    version: u32,
    kind: MergeKind,
}

#[derive(Serialize)]
struct ScanGroup {
    base_id: String,
    title_name: String,
    latest_version_db: u64,
    items: Vec<ScanFile>,
}

#[derive(Serialize)]
struct LibraryTitleStatus {
    title_id: String,
    title_name: String,
    local_version: u32,
    latest_version: Option<u64>,
    release_date: Option<String>,
    publisher: Option<String>,
    languages: Vec<String>,
    description: Option<String>,
    image_url: Option<String>,
    screenshot_urls: Vec<String>,
    status: String,
}

#[derive(Serialize)]
struct LibraryFileStatus {
    titles: Vec<LibraryTitleStatus>,
}

#[derive(Serialize)]
struct FaultyScanFile {
    path: String,
    filename: String,
    reason: String,
}

fn collect_files_recursive(dir: &Path, files: &mut Vec<PathBuf>) {
    if let Ok(entries) = std::fs::read_dir(dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                collect_files_recursive(&path, files);
            } else {
                let ext = path
                    .extension()
                    .and_then(|e| e.to_str())
                    .unwrap_or("")
                    .to_lowercase();
                if matches!(ext.as_str(), "nsp" | "nsz" | "xci" | "xcz") {
                    files.push(path);
                }
            }
        }
    }
}

fn validate_container_bounds(path: &Path, ks: &KeyStore) -> Result<(), String> {
    let file_size = std::fs::metadata(path)
        .map_err(|e| format!("Unreadable or invalid file: {e}"))?
        .len();
    let path_str = path.to_string_lossy();
    let ext = path
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_lowercase();
    let mut file =
        BufReader::new(File::open(path).map_err(|e| format!("Unreadable or invalid file: {e}"))?);

    match ext.as_str() {
        "nsp" | "nsz" => {
            let nsp = crate::formats::nsp::Nsp::parse(&mut file)
                .map_err(|e| format!("Invalid NSP/NSZ file format: {e}"))?;
            for entry in nsp.all_entries() {
                let end = nsp
                    .file_abs_offset(entry)
                    .checked_add(entry.size)
                    .ok_or_else(|| format!("Entry {} has invalid size", entry.name))?;
                if end > file_size {
                    return Err(format!(
                        "Entry {} ends at {} but file is only {} bytes",
                        entry.name, end, file_size
                    ));
                }
            }
        }
        "xci" | "xcz" => {
            let xci = crate::formats::xci::Xci::parse(&mut file)
                .map_err(|e| format!("Invalid XCI/XCZ file format: {e}"))?;
            let root_end = xci
                .header
                .hfs0_offset
                .checked_add(xci.header.hfs0_size)
                .ok_or_else(|| "Root HFS0 size overflows".to_string())?;
            if root_end > file_size {
                return Err(format!(
                    "Root HFS0 ends at {} but file is only {} bytes",
                    root_end, file_size
                ));
            }
            for entry in &xci.root_hfs0.entries {
                let end = xci
                    .root_hfs0
                    .file_abs_offset(entry)
                    .checked_add(entry.size)
                    .ok_or_else(|| format!("Partition {} has invalid size", entry.name))?;
                if end > file_size {
                    return Err(format!(
                        "Partition {} ends at {} but file is only {} bytes",
                        entry.name, end, file_size
                    ));
                }
            }
            for entry in xci
                .secure_nca_entries(&mut file)
                .map_err(|e| format!("Secure partition parse failed: {e}"))?
            {
                let end = entry
                    .abs_offset
                    .checked_add(entry.size)
                    .ok_or_else(|| format!("Entry {} has invalid size", entry.name))?;
                if end > file_size {
                    return Err(format!(
                        "Entry {} ends at {} but file is only {} bytes",
                        entry.name, end, file_size
                    ));
                }
            }
        }
        _ => return Err(format!("Unsupported file type for {}", path_str)),
    }

    let (records, _, _) = crate::cli::collect_title_records(&[path_str.as_ref()], ks);
    if records.is_empty() {
        return Err("No title metadata found".to_string());
    }
    Ok(())
}

pub fn configure_temp_root(root: &str) -> Result<String, String> {
    let root = root.trim();
    if root.is_empty() {
        return Err("Temp root path is empty".to_string());
    }
    std::fs::create_dir_all(root)
        .map_err(|e| format!("Could not create native temp root {root}: {e}"))?;
    std::env::set_var("TMPDIR", root);
    std::env::set_var("TMP", root);
    std::env::set_var("TEMP", root);
    Ok(format!("OK: native temp root set to {root}"))
}

pub fn merge(
    inputs_joined: &str,
    output: &str,
    keys_path: &str,
    output_type: &str,
) -> Result<String, String> {
    let ks = require_keys(keys_path)?;

    let items = inputs_joined
        .split('\n')
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .collect::<Vec<_>>();

    if items.is_empty() {
        return Err("No input files were provided for merge".to_string());
    }

    progress::log(&format!("Starting merge of {} files...", items.len()));

    let is_nsp = output_type.eq_ignore_ascii_case("nsp");
    crate::ops::merge::merge(
        &items,
        output.trim(),
        &ks,
        false,
        output_type.trim(),
        is_nsp,
        None,
        None,
        false,
    )
    .map_err(|e| format!("Merge failed: {e}"))?;

    progress::log("Merge completed successfully.");
    Ok(format!("OK: merged {} input(s)", items.len()))
}

pub fn compress(input: &str, output: &str, keys_path: &str, level: i32) -> Result<String, String> {
    let ks = require_keys(keys_path)?;

    progress::log(&format!("Compressing {}...", input));
    crate::ops::compress::compress(input.trim(), output.trim(), level, &ks)
        .map_err(|e| format!("Compress failed: {e}"))?;

    progress::log("Compress completed successfully.");
    Ok("OK: compressed input".to_string())
}

pub fn decompress(input: &str, output: &str) -> Result<String, String> {
    progress::log(&format!("Decompressing {}...", input));
    crate::ops::decompress::decompress(input.trim(), output.trim())
        .map_err(|e| format!("Decompress failed: {e}"))?;

    progress::log("Decompress completed successfully.");
    Ok("OK: decompressed input".to_string())
}

#[allow(clippy::too_many_arguments)]
pub fn rename_path(
    path: &str,
    keys_path: &str,
    cache_dir: &str,
    renmode: &str,
    addlangue: &str,
    noversion: &str,
    dlcrname: &str,
) -> Result<String, String> {
    progress::log(&format!("Starting bulk rename in {}...", path));

    let ks = require_keys(keys_path)?;
    let nutdb = NutdbStore::new(Some(cache_dir.trim()), None);
    let index = nutdb
        .ensure_index()
        .map_err(|e| format!("Nutdb error: {e}"))?;

    let options = RenameOptions::from_args(
        Some(renmode),
        Some(addlangue),
        Some(noversion),
        Some(dlcrname),
    );

    let count = crate::cli::rename_target(path, &ks, &index, options)
        .map_err(|e| format!("Rename failed: {e}"))?;

    progress::log(&format!("Successfully renamed {} file(s).", count));
    Ok(format!("OK: Renamed {} file(s)", count))
}

pub fn scan_directory(path: &str, keys_path: &str, cache_dir: &str) -> Result<String, String> {
    let ks = require_keys(keys_path)?;

    let dir = Path::new(path);
    if !dir.is_dir() {
        return Err("Not a directory".to_string());
    }

    progress::log("Scanning directory recursively...");
    let mut groups: HashMap<String, ScanGroup> = HashMap::new();
    let mut file_paths = Vec::new();
    collect_files_recursive(dir, &mut file_paths);

    progress::log(&format!(
        "Found {} potential content files. Analyzing metadata...",
        file_paths.len()
    ));

    let nutdb = NutdbStore::new(Some(cache_dir.trim()), None);
    let versions_index = nutdb.try_load_cached_versions_index().ok().flatten();

    for (i, path) in file_paths.iter().enumerate() {
        let filename = path
            .file_name()
            .unwrap_or_default()
            .to_string_lossy()
            .to_string();
        progress::log(&format!(
            "[{}/{}] Analyzing {}...",
            i + 1,
            file_paths.len(),
            filename
        ));

        let path_str = path.to_string_lossy().to_string();
        let (records, _, title_name) = crate::cli::collect_title_records(&[&path_str], &ks);
        for (tid, rec) in records {
            let title_id = format!("{:016X}", tid);
            let base_id = base_title_id(&title_id);

            let latest_v = versions_index
                .as_ref()
                .and_then(|idx| idx.latest_version_for(&base_id))
                .unwrap_or(0);

            let group = groups.entry(base_id.clone()).or_insert_with(|| ScanGroup {
                base_id: base_id.clone(),
                title_name: title_name.clone().unwrap_or_else(|| "Unknown".to_string()),
                latest_version_db: latest_v,
                items: Vec::new(),
            });

            if group.title_name == "Unknown" {
                if let Some(name) = &title_name {
                    group.title_name = name.clone();
                }
            }

            group.items.push(ScanFile {
                path: path_str.clone(),
                filename,
                title_id,
                version: rec.version,
                kind: rec.kind,
            });
            break; // One record per file is enough for grouping
        }
    }

    let mut result_list: Vec<ScanGroup> = groups.into_values().collect();
    for group in &mut result_list {
        group.items.sort_by(|a, b| {
            a.title_id
                .cmp(&b.title_id)
                .then_with(|| b.version.cmp(&a.version))
                .then_with(|| a.filename.cmp(&b.filename))
        });
    }
    result_list.sort_by(|a, b| {
        a.title_name
            .to_lowercase()
            .cmp(&b.title_name.to_lowercase())
    });

    progress::log(&format!(
        "Scan complete. Grouped into {} titles.",
        result_list.len()
    ));
    serde_json::to_string(&result_list).map_err(|e| e.to_string())
}

pub fn scan_faulty_files(path: &str, keys_path: &str) -> Result<String, String> {
    let ks = require_keys(keys_path)?;

    let dir = Path::new(path);
    if !dir.is_dir() {
        return Err("Not a directory".to_string());
    }

    progress::log("Checking scanned files for container errors...");
    let mut file_paths = Vec::new();
    collect_files_recursive(dir, &mut file_paths);
    let total = file_paths.len();
    progress::log(&format!("Verifying {} game file(s)...", total));
    let mut faulty = Vec::new();
    for (idx, path) in file_paths.into_iter().enumerate() {
        let filename = path
            .file_name()
            .unwrap_or_default()
            .to_string_lossy()
            .to_string();
        progress::log(&format!(
            "[{}/{}] Verifying {}...",
            idx + 1,
            total,
            filename
        ));
        if let Err(reason) = validate_container_bounds(&path, &ks) {
            faulty.push(FaultyScanFile {
                path: path.to_string_lossy().to_string(),
                filename,
                reason,
            });
        }
    }
    progress::log(&format!(
        "Fault check complete. Found {} faulty file(s).",
        faulty.len()
    ));
    serde_json::to_string(&faulty).map_err(|e| e.to_string())
}

pub fn refresh_titledb(cache_dir: &str) -> Result<String, String> {
    progress::log("Refreshing local TitlesDB...");
    let nutdb = NutdbStore::new(Some(cache_dir.trim()), None);
    let outcome = nutdb
        .refresh()
        .map_err(|e| format!("TitlesDB refresh failed: {e}"))?;
    progress::log(&format!(
        "TitlesDB {} ({} indexed titles).",
        outcome.status.as_str(),
        outcome.indexed_titles
    ));
    Ok(format!(
        "OK: TitlesDB {} ({} indexed titles)",
        outcome.status.as_str(),
        outcome.indexed_titles
    ))
}

pub fn library_status(input: &str, keys_path: &str, cache_dir: &str) -> Result<String, String> {
    let ks = require_keys(keys_path)?;
    let nutdb = NutdbStore::new(Some(cache_dir.trim()), None);
    let index = nutdb
        .try_load_cached_index()
        .map_err(|e| format!("TitlesDB cache error: {e}"))?;
    let versions_index = nutdb
        .try_load_cached_versions_index()
        .map_err(|e| format!("TitlesDB versions cache error: {e}"))?;
    let (records, _, fallback_name) = crate::cli::collect_title_records(&[input.trim()], &ks);
    let mut grouped: HashMap<String, LibraryTitleStatus> = HashMap::new();
    for (tid, rec) in records {
        let title_id = format!("{:016X}", tid);
        let base_id = base_title_id(&title_id);
        let local_version = rec.version;
        let title_meta = index
            .as_ref()
            .and_then(|idx| idx.lookup(&base_id).or_else(|| idx.lookup(&title_id)));
        let latest_version = versions_index
            .as_ref()
            .and_then(|idx| {
                idx.latest_version_for(&base_id)
                    .or_else(|| idx.latest_version_for(&title_id))
            })
            .or_else(|| title_meta.and_then(|title| title.version));
        let image_url = title_meta
            .and_then(|title| title.banner_url.clone().or_else(|| title.icon_url.clone()));
        let screenshot_urls = title_meta
            .map(|title| title.screenshots.clone())
            .unwrap_or_default();
        let release_date = title_meta
            .and_then(|title| title.release_date)
            .map(format_release_date);
        let publisher = title_meta.and_then(|title| title.publisher.clone());
        let languages = title_meta
            .map(|title| title.languages.clone())
            .unwrap_or_default();
        let description = title_meta.and_then(|title| title.description.clone());
        let title_name = index
            .as_ref()
            .and_then(|idx| {
                idx.display_name_for(&base_id)
                    .or_else(|| idx.display_name_for(&title_id))
            })
            .or_else(|| fallback_name.clone())
            .unwrap_or_else(|| "Unknown".to_string());
        let status = match latest_version {
            Some(latest) if latest > local_version as u64 => "outdated",
            Some(_) => "current",
            None => "unknown",
        }
        .to_string();

        grouped
            .entry(base_id.clone())
            .and_modify(|existing| {
                if local_version > existing.local_version {
                    existing.local_version = local_version;
                }
                if existing.latest_version.is_none() {
                    existing.latest_version = latest_version;
                }
                if existing.image_url.is_none() {
                    existing.image_url = image_url.clone();
                }
                if existing.title_name == "Unknown" && title_name != "Unknown" {
                    existing.title_name = title_name.clone();
                }
                if existing.release_date.is_none() {
                    existing.release_date = release_date.clone();
                }
                if existing.publisher.is_none() {
                    existing.publisher = publisher.clone();
                }
                if existing.description.is_none() {
                    existing.description = description.clone();
                }
                if existing.languages.is_empty() && !languages.is_empty() {
                    existing.languages = languages.clone();
                }
                existing.status = match (existing.latest_version, existing.local_version) {
                    (Some(latest), local) if latest > local as u64 => "outdated".to_string(),
                    (Some(_), _) => "current".to_string(),
                    (None, _) => "unknown".to_string(),
                };
            })
            .or_insert(LibraryTitleStatus {
                title_id: base_id,
                title_name,
                local_version,
                latest_version,
                release_date,
                publisher,
                languages,
                description,
                image_url,
                screenshot_urls,
                status,
            });
    }
    let mut titles: Vec<LibraryTitleStatus> = grouped.into_values().collect();
    titles.sort_by(|a, b| a.title_id.cmp(&b.title_id));
    serde_json::to_string(&LibraryFileStatus { titles }).map_err(|e| e.to_string())
}

pub fn get_logs() -> String {
    progress::get_logs()
}

pub fn delete_file(path: &str) -> Result<String, String> {
    let p = Path::new(path);
    if p.exists() {
        std::fs::remove_file(p).map_err(|e| format!("Delete failed: {e}"))?;
        progress::log(&format!("Deleted file: {}", path));
        Ok(format!("Deleted {}", path))
    } else {
        Err("File does not exist".to_string())
    }
}

pub fn content_list(input: &str, keys_path: &str) -> Result<String, String> {
    let ks = require_keys(keys_path)?;
    crate::ops::info::content_list_text(input.trim(), &ks)
        .map_err(|e| format!("Content list failed: {e}"))
}

pub fn file_list(input: &str, keys_path: &str) -> Result<String, String> {
    let ks = require_keys(keys_path)?;
    crate::ops::info::file_list_text(input.trim(), &ks)
        .map_err(|e| format!("File list failed: {e}"))
}

pub fn suggested_file_name(
    inputs_joined: &str,
    keys_path: &str,
    cache_dir: &str,
    output_type: &str,
) -> Result<String, String> {
    let ks = require_keys(keys_path)?;
    let nutdb = NutdbStore::new(Some(cache_dir.trim()), None);

    let items = inputs_joined
        .split('\n')
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .collect::<Vec<_>>();

    if items.is_empty() {
        return Err("No input files provided".to_string());
    }

    let name = crate::cli::build_merge_filename_metadata(&items, output_type, &ks, &nutdb)
        .unwrap_or_else(|| crate::cli::build_merge_filename(&items, output_type));

    Ok(name)
}
