#![cfg(target_os = "android")]

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

use crate::cli::{MergeKind, RenameOptions};
use crate::keys::KeyStore;
use crate::nutdb::{base_title_id, NutdbStore};
use crate::util::progress;
use serde::Serialize;
use std::collections::HashMap;
use std::fs::File;
use std::io::BufReader;
use std::path::{Path, PathBuf};

fn jstr_to_string(env: &mut JNIEnv<'_>, value: JString<'_>) -> Result<String, String> {
    env.get_string(&value)
        .map(|v| v.to_string_lossy().into_owned())
        .map_err(|e| format!("JNI string error: {e}"))
}

fn to_jstring(env: &mut JNIEnv<'_>, text: &str) -> jstring {
    match env.new_string(text) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn require_keys(keys_path: &str) -> Result<KeyStore, String> {
    if keys_path.trim().is_empty() {
        return Err("prod.keys path is required".to_string());
    }
    KeyStore::from_default_locations(Some(keys_path)).map_err(|e| format!("Key error: {e}"))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_configureTempRoot(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    temp_root: JString<'_>,
) -> jstring {
    #[cfg(target_os = "android")]
    crate::util::progress::init_android_logging();

    let result = (|| -> Result<String, String> {
        let root = jstr_to_string(&mut env, temp_root)?;
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
    })();

    match result {
        Ok(msg) => to_jstring(&mut env, &msg),
        Err(err) => to_jstring(&mut env, &format!("ERROR: {err}")),
    }
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

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_merge(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    inputs_joined: JString<'_>,
    output_path: JString<'_>,
    keys_path: JString<'_>,
    output_type: JString<'_>,
) -> jstring {
    let result = (|| -> Result<String, String> {
        let inputs_text = jstr_to_string(&mut env, inputs_joined)?;
        let output = jstr_to_string(&mut env, output_path)?;
        let keys = jstr_to_string(&mut env, keys_path)?;
        let out_type = jstr_to_string(&mut env, output_type)?;

        let ks = require_keys(&keys)?;

        let items = inputs_text
            .split('\n')
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .collect::<Vec<_>>();

        if items.is_empty() {
            return Err("No input files were provided for merge".to_string());
        }

        progress::log(&format!("Starting merge of {} files...", items.len()));

        let is_nsp = out_type.eq_ignore_ascii_case("nsp");
        crate::ops::merge::merge(
            &items,
            output.trim(),
            &ks,
            false,
            out_type.trim(),
            is_nsp,
            None,
            None,
            false,
        )
        .map_err(|e| format!("Merge failed: {e}"))?;

        progress::log("Merge completed successfully.");
        Ok(format!("OK: merged {} input(s)", items.len()))
    })();

    match result {
        Ok(msg) => to_jstring(&mut env, &msg),
        Err(err) => {
            progress::log(&format!("ERROR: {err}"));
            to_jstring(&mut env, &format!("ERROR: {err}"))
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_compress(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    input_path: JString<'_>,
    output_path: JString<'_>,
    keys_path: JString<'_>,
    level: jni::sys::jint,
) -> jstring {
    let result = (|| -> Result<String, String> {
        let input = jstr_to_string(&mut env, input_path)?;
        let output = jstr_to_string(&mut env, output_path)?;
        let keys = jstr_to_string(&mut env, keys_path)?;
        let ks = require_keys(&keys)?;

        progress::log(&format!("Compressing {}...", input));
        crate::ops::compress::compress(input.trim(), output.trim(), level as i32, &ks)
            .map_err(|e| format!("Compress failed: {e}"))?;

        progress::log("Compress completed successfully.");
        Ok("OK: compressed input".to_string())
    })();

    match result {
        Ok(msg) => to_jstring(&mut env, &msg),
        Err(err) => {
            progress::log(&format!("ERROR: {err}"));
            to_jstring(&mut env, &format!("ERROR: {err}"))
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_decompress(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    input_path: JString<'_>,
    output_path: JString<'_>,
) -> jstring {
    let result = (|| -> Result<String, String> {
        let input = jstr_to_string(&mut env, input_path)?;
        let output = jstr_to_string(&mut env, output_path)?;

        progress::log(&format!("Decompressing {}...", input));
        crate::ops::decompress::decompress(input.trim(), output.trim())
            .map_err(|e| format!("Decompress failed: {e}"))?;

        progress::log("Decompress completed successfully.");
        Ok("OK: decompressed input".to_string())
    })();

    match result {
        Ok(msg) => to_jstring(&mut env, &msg),
        Err(err) => {
            progress::log(&format!("ERROR: {err}"));
            to_jstring(&mut env, &format!("ERROR: {err}"))
        }
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
    progress::log(&format!(
        "Validating container bounds: {}",
        path.file_name().unwrap_or_default().to_string_lossy()
    ));
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
            progress::log("  Parsing NSP structure...");
            let nsp = crate::formats::nsp::Nsp::parse(&mut file)
                .map_err(|e| format!("Invalid NSP/NSZ file format: {e}"))?;
            let entries = nsp.all_entries();
            progress::log(&format!("  Found {} entries in NSP header", entries.len()));
            for entry in entries {
                let end = nsp
                    .file_abs_offset(entry)
                    .checked_add(entry.size)
                    .ok_or_else(|| format!("Entry {} has invalid size", entry.name))?;
                progress::log(&format!(
                    "    Entry '{}' starts at {}, ends at {} (file size {})",
                    entry.name,
                    nsp.file_abs_offset(entry),
                    end,
                    file_size
                ));
                if end > file_size {
                    return Err(format!(
                        "Entry {} ends at {} but file is only {} bytes",
                        entry.name, end, file_size
                    ));
                }
            }
        }
        "xci" | "xcz" => {
            progress::log("  Parsing XCI structure...");
            let xci = crate::formats::xci::Xci::parse(&mut file)
                .map_err(|e| format!("Invalid XCI/XCZ file format: {e}"))?;
            let root_end = xci
                .header
                .hfs0_offset
                .checked_add(xci.header.hfs0_size)
                .ok_or_else(|| "Root HFS0 size overflows".to_string())?;
            progress::log(&format!(
                "    Root HFS0 at offset {0} size {1} => end {2} (file size {3})",
                xci.header.hfs0_offset, xci.header.hfs0_size, root_end, file_size
            ));
            if root_end > file_size {
                return Err(format!(
                    "Root HFS0 ends at {} but file is only {} bytes",
                    root_end, file_size
                ));
            }
            progress::log(&format!(
                "    Root partition has {} entries",
                xci.root_hfs0.entries.len()
            ));
            for entry in &xci.root_hfs0.entries {
                let end = xci
                    .root_hfs0
                    .file_abs_offset(entry)
                    .checked_add(entry.size)
                    .ok_or_else(|| format!("Partition {} has invalid size", entry.name))?;
                progress::log(&format!(
                    "      Partition '{}' offset {} size {} => end {}",
                    entry.name,
                    xci.root_hfs0.file_abs_offset(entry),
                    entry.size,
                    end
                ));
                if end > file_size {
                    return Err(format!(
                        "Partition {} ends at {} but file is only {} bytes",
                        entry.name, end, file_size
                    ));
                }
            }
            progress::log("    Scanning secure partition...");
            let secure = xci
                .secure_nca_entries(&mut file)
                .map_err(|e| format!("Secure partition parse failed: {e}"))?;
            progress::log(&format!("    Secure partition has {} NCA entries", secure.len()));
            for entry in secure {
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

    progress::log("  Bounds OK. Extracting title metadata from NCAs...");
    let (records, _, _) = crate::cli::collect_title_records(&[path_str.as_ref()], ks);
    if records.is_empty() {
        return Err("No title metadata found".to_string());
    }
    Ok(())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_renamePath(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    path: JString<'_>,
    keys_path: JString<'_>,
    cache_dir: JString<'_>,
    renmode: JString<'_>,
    addlangue: JString<'_>,
    noversion: JString<'_>,
    dlcrname: JString<'_>,
) -> jstring {
    let result = (|| -> Result<String, String> {
        let p = jstr_to_string(&mut env, path)?;
        let keys = jstr_to_string(&mut env, keys_path)?;
        let cache = jstr_to_string(&mut env, cache_dir)?;
        let mode = jstr_to_string(&mut env, renmode)?;
        let lang = jstr_to_string(&mut env, addlangue)?;
        let nver = jstr_to_string(&mut env, noversion)?;
        let dlc = jstr_to_string(&mut env, dlcrname)?;

        progress::log(&format!("Starting bulk rename in {}...", p));

        let ks = require_keys(&keys)?;
        let nutdb = NutdbStore::new(Some(cache.trim()), None);
        let index = nutdb
            .ensure_index()
            .map_err(|e| format!("Nutdb error: {e}"))?;

        let options = RenameOptions::from_args(Some(&mode), Some(&lang), Some(&nver), Some(&dlc));

        let count = crate::cli::rename_target(&p, &ks, &index, options)
            .map_err(|e| format!("Rename failed: {e}"))?;

        progress::log(&format!("Successfully renamed {} file(s).", count));
        Ok(format!("OK: Renamed {} file(s)", count))
    })();

    match result {
        Ok(msg) => to_jstring(&mut env, &msg),
        Err(err) => {
            progress::log(&format!("ERROR: {err}"));
            to_jstring(&mut env, &format!("ERROR: {err}"))
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_scanDirectory(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    path: JString<'_>,
    keys_path: JString<'_>,
    cache_dir: JString<'_>,
) -> jstring {
    let result = (|| -> Result<String, String> {
        let p = jstr_to_string(&mut env, path)?;
        let keys = jstr_to_string(&mut env, keys_path)?;
        let cache = jstr_to_string(&mut env, cache_dir)?;
        let ks = require_keys(&keys)?;

        let dir = Path::new(&p);
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

        let nutdb = NutdbStore::new(Some(cache.trim()), None);
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
    })();

    match result {
        Ok(msg) => to_jstring(&mut env, &msg),
        Err(err) => {
            progress::log(&format!("ERROR: {err}"));
            to_jstring(&mut env, &format!("ERROR: {err}"))
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_scanFaultyFiles(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    path: JString<'_>,
    keys_path: JString<'_>,
) -> jstring {
    let result = (|| -> Result<String, String> {
        let p = jstr_to_string(&mut env, path)?;
        let keys = jstr_to_string(&mut env, keys_path)?;
        let ks = require_keys(&keys)?;

        let dir = Path::new(&p);
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
    })();

    match result {
        Ok(msg) => to_jstring(&mut env, &msg),
        Err(err) => {
            progress::log(&format!("ERROR: {err}"));
            to_jstring(&mut env, &format!("ERROR: {err}"))
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_refreshTitleDb(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    cache_dir: JString<'_>,
) -> jstring {
    let result = (|| -> Result<String, String> {
        let cache = jstr_to_string(&mut env, cache_dir)?;
        progress::log("Refreshing local TitlesDB...");
        let nutdb = NutdbStore::new(Some(cache.trim()), None);
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
    })();

    match result {
        Ok(msg) => to_jstring(&mut env, &msg),
        Err(err) => {
            progress::log(&format!("ERROR: {err}"));
            to_jstring(&mut env, &format!("ERROR: {err}"))
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_libraryStatus(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    input_path: JString<'_>,
    keys_path: JString<'_>,
    cache_dir: JString<'_>,
) -> jstring {
    let result = (|| -> Result<String, String> {
        let input = jstr_to_string(&mut env, input_path)?;
        let keys = jstr_to_string(&mut env, keys_path)?;
        let cache = jstr_to_string(&mut env, cache_dir)?;
        let ks = require_keys(&keys)?;
        let nutdb = NutdbStore::new(Some(cache.trim()), None);
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
    })();

    match result {
        Ok(msg) => to_jstring(&mut env, &msg),
        Err(err) => {
            progress::log(&format!("ERROR: {err}"));
            to_jstring(&mut env, &format!("ERROR: {err}"))
        }
    }
}

#[derive(Serialize)]
struct FileTitleExtract {
    title_id: Option<String>,
    local_version: u32,
    name_from_filename: Option<String>,
}

#[derive(Serialize)]
struct BatchLookupResult {
    title_id: String,
    title_name: String,
    latest_version: Option<u64>,
    release_date: Option<String>,
    publisher: Option<String>,
    languages: Vec<String>,
    description: Option<String>,
    image_url: Option<String>,
    screenshot_urls: Vec<String>,
}

#[derive(Serialize)]
struct BatchLookupResponse {
    results: Vec<BatchLookupResult>,
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_extractFileTitle(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    file_name: JString<'_>,
) -> jstring {
    let result = (|| -> Result<String, String> {
        let name = jstr_to_string(&mut env, file_name)?;
        let stem = Path::new(&name)
            .file_stem()
            .unwrap_or_default()
            .to_string_lossy()
            .to_string();

        let tid_re = regex::Regex::new(r"[\[-]([0-9A-Fa-f]{16})[\]-]").map_err(|e| e.to_string())?;
        let title_id = tid_re.captures(&stem).map(|c| c[1].to_uppercase());

        let ver_bracket_re = regex::Regex::new(r"\[v(\d+)\]").map_err(|e| e.to_string())?;
        let ver_dash_re = regex::Regex::new(r"--v(\d+)-").map_err(|e| e.to_string())?;
        let local_version = ver_bracket_re
            .captures(&stem)
            .or_else(|| ver_dash_re.captures(&stem))
            .and_then(|c| c.get(1))
            .and_then(|m| m.as_str().parse::<u32>().ok())
            .unwrap_or(0);

        let name_from_filename = if let Some(pos) = stem.find('[') {
            let n = stem[..pos].trim();
            if !n.is_empty() { Some(n.to_string()) } else { None }
        } else {
            None
        };

        let out = FileTitleExtract {
            title_id,
            local_version,
            name_from_filename,
        };
        serde_json::to_string(&out).map_err(|e| e.to_string())
    })();

    match result {
        Ok(msg) => to_jstring(&mut env, &msg),
        Err(err) => to_jstring(&mut env, &format!("ERROR: {err}")),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_titleDbLookupBatch(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    ids_joined: JString<'_>,
    cache_dir: JString<'_>,
) -> jstring {
    let result = (|| -> Result<String, String> {
        let ids_text = jstr_to_string(&mut env, ids_joined)?;
        let cache = jstr_to_string(&mut env, cache_dir)?;

        let nutdb = NutdbStore::new(Some(cache.trim()), None);
        let index = nutdb
            .try_load_cached_index()
            .map_err(|e| format!("TitlesDB cache error: {e}"))?;
        let versions_index = nutdb
            .try_load_cached_versions_index()
            .map_err(|e| format!("TitlesDB versions cache error: {e}"))?;

        let ids: Vec<String> = ids_text
            .lines()
            .map(str::trim)
            .filter(|s| !s.is_empty() && s.len() == 16)
            .map(|s| s.to_ascii_uppercase())
            .collect();

        let mut results = Vec::new();
        for id in ids {
            let base_id = base_title_id(&id);
            let meta = index.as_ref().and_then(|idx| idx.lookup(&base_id).or_else(|| idx.lookup(&id)));
            let latest = versions_index
                .as_ref()
                .and_then(|idx| idx.latest_version_for(&base_id).or_else(|| idx.latest_version_for(&id)))
                .or_else(|| meta.and_then(|m| m.version));
            let name = index
                .as_ref()
                .and_then(|idx| idx.display_name_for(&base_id).or_else(|| idx.display_name_for(&id)))
                .or_else(|| meta.and_then(|m| m.name.clone()))
                .unwrap_or_else(|| "Unknown".to_string());
            results.push(BatchLookupResult {
                title_id: base_id.clone(),
                title_name: name,
                latest_version: latest,
                release_date: meta.and_then(|m| m.release_date).map(format_release_date),
                publisher: meta.and_then(|m| m.publisher.clone()),
                languages: meta.map(|m| m.languages.clone()).unwrap_or_default(),
                description: meta.and_then(|m| m.description.clone()),
                image_url: meta.and_then(|m| m.banner_url.clone().or_else(|| m.icon_url.clone())),
                screenshot_urls: meta.map(|m| m.screenshots.clone()).unwrap_or_default(),
            });
        }

        serde_json::to_string(&BatchLookupResponse { results }).map_err(|e| e.to_string())
    })();

    match result {
        Ok(msg) => to_jstring(&mut env, &msg),
        Err(err) => to_jstring(&mut env, &format!("ERROR: {err}")),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_getLogs(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    to_jstring(&mut env, &progress::get_logs())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_deleteFile(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    path: JString<'_>,
) -> jstring {
    let result = (|| -> Result<String, String> {
        let p = jstr_to_string(&mut env, path)?;
        let path = Path::new(&p);
        if path.exists() {
            std::fs::remove_file(path).map_err(|e| format!("Delete failed: {e}"))?;
            progress::log(&format!("Deleted file: {}", p));
            Ok(format!("Deleted {}", p))
        } else {
            Err("File does not exist".to_string())
        }
    })();

    match result {
        Ok(msg) => to_jstring(&mut env, &msg),
        Err(err) => to_jstring(&mut env, &format!("ERROR: {err}")),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_contentList(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    input_path: JString<'_>,
    keys_path: JString<'_>,
) -> jstring {
    let result = (|| -> Result<String, String> {
        let input = jstr_to_string(&mut env, input_path)?;
        let keys = jstr_to_string(&mut env, keys_path)?;
        let ks = require_keys(&keys)?;
        crate::ops::info::content_list_text(input.trim(), &ks)
            .map_err(|e| format!("Content list failed: {e}"))
    })();

    match result {
        Ok(msg) => to_jstring(&mut env, &msg),
        Err(err) => to_jstring(&mut env, &format!("ERROR: {err}")),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_fileList(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    input_path: JString<'_>,
    keys_path: JString<'_>,
) -> jstring {
    let result = (|| -> Result<String, String> {
        let input = jstr_to_string(&mut env, input_path)?;
        let keys = jstr_to_string(&mut env, keys_path)?;
        let ks = require_keys(&keys)?;
        crate::ops::info::file_list_text(input.trim(), &ks)
            .map_err(|e| format!("File list failed: {e}"))
    })();

    match result {
        Ok(msg) => to_jstring(&mut env, &msg),
        Err(err) => to_jstring(&mut env, &format!("ERROR: {err}")),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_getSuggestedFileName(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    inputs_joined: JString<'_>,
    keys_path: JString<'_>,
    cache_dir: JString<'_>,
    output_type: JString<'_>,
) -> jstring {
    let result = (|| -> Result<String, String> {
        let inputs_text = jstr_to_string(&mut env, inputs_joined)?;
        let keys = jstr_to_string(&mut env, keys_path)?;
        let cache = jstr_to_string(&mut env, cache_dir)?;
        let out_type = jstr_to_string(&mut env, output_type)?;

        let ks = require_keys(&keys)?;
        let nutdb = NutdbStore::new(Some(cache.trim()), None);

        let items = inputs_text
            .split('\n')
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .collect::<Vec<_>>();

        if items.is_empty() {
            return Err("No input files provided".to_string());
        }

        let name = crate::cli::build_merge_filename_metadata(&items, &out_type, &ks, &nutdb)
            .unwrap_or_else(|| crate::cli::build_merge_filename(&items, &out_type));

        Ok(name)
    })();

    match result {
        Ok(msg) => to_jstring(&mut env, &msg),
        Err(err) => to_jstring(&mut env, &format!("ERROR: {err}")),
    }
}
