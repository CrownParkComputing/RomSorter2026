use std::collections::HashMap;
use std::fmt;
use std::fs::{self, File};
use std::io::{BufReader, BufWriter, Read, Write};
use std::path::{Path, PathBuf};
use std::time::Duration;

use reqwest::blocking::Client;
use reqwest::header::{ETAG, IF_MODIFIED_SINCE, IF_NONE_MATCH, LAST_MODIFIED, USER_AGENT};
use serde::de::{MapAccess, SeqAccess, Visitor};
use serde::{Deserialize, Deserializer, Serialize};
use sha2::{Digest, Sha256};

use crate::error::{NscbError, Result};
use crate::util::progress;

const DEFAULT_SOURCE_URL: &str =
    "https://raw.githubusercontent.com/blawar/titledb/master/US.en.json";
const RAW_CACHE_FILE: &str = "nutdb.raw.json";
const INDEX_CACHE_FILE: &str = "nutdb.index.json";
const META_CACHE_FILE: &str = "nutdb.meta.json";
const VERSIONS_INDEX_CACHE_FILE: &str = "nutdb.versions.index.json";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RefreshStatus {
    Downloaded,
    NotModified,
    UsedCached,
}

impl RefreshStatus {
    pub fn as_str(self) -> &'static str {
        match self {
            RefreshStatus::Downloaded => "downloaded",
            RefreshStatus::NotModified => "not-modified",
            RefreshStatus::UsedCached => "used-cached",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RefreshOutcome {
    pub status: RefreshStatus,
    pub indexed_titles: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct NutdbTitle {
    pub name: Option<String>,
    pub publisher: Option<String>,
    #[serde(default)]
    pub languages: Vec<String>,
    pub version: Option<u64>,
    #[serde(
        default,
        rename = "releaseDate",
        deserialize_with = "deserialize_opt_u64"
    )]
    pub release_date: Option<u64>,
    #[serde(default, deserialize_with = "deserialize_opt_string")]
    pub description: Option<String>,
    #[serde(default, rename = "bannerUrl")]
    pub banner_url: Option<String>,
    #[serde(default, rename = "iconUrl")]
    pub icon_url: Option<String>,
    #[serde(default)]
    pub screenshots: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct NutdbIndex {
    pub source_url: String,
    #[serde(default)]
    pub titles: HashMap<String, NutdbTitle>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct NutdbVersionIndex {
    pub source_url: String,
    #[serde(default, deserialize_with = "deserialize_version_history_map")]
    pub titles: HashMap<String, Vec<u64>>,
}

impl NutdbIndex {
    pub fn len(&self) -> usize {
        self.titles.len()
    }

    pub fn is_empty(&self) -> bool {
        self.titles.is_empty()
    }

    pub fn lookup(&self, title_id: &str) -> Option<&NutdbTitle> {
        let title_id = normalize_title_id(title_id)?;
        self.titles.get(&title_id)
    }

    pub fn languages_for(&self, title_id: &str) -> Vec<String> {
        let base_id = base_title_id(title_id);
        self.lookup(&base_id)
            .map(|title| title.languages.clone())
            .unwrap_or_default()
    }

    pub fn display_name_for(&self, title_id: &str) -> Option<String> {
        let title_id = normalize_title_id(title_id)?;
        if title_id.ends_with("000") {
            return self.lookup(&title_id).and_then(|t| t.name.clone());
        }

        if title_id.ends_with("800") {
            return self
                .lookup(&title_id)
                .and_then(|t| t.name.clone())
                .or_else(|| {
                    let base_id = base_title_id(&title_id);
                    self.lookup(&base_id).and_then(|t| t.name.clone())
                });
        }

        let base_id = base_title_id(&title_id);
        let base_name = self.lookup(&base_id).and_then(|t| t.name.clone());
        let dlc_name = self.lookup(&title_id).and_then(|t| t.name.clone());
        match (base_name, dlc_name) {
            (Some(base), Some(dlc)) if base != dlc => Some(format!("{base} [{dlc}]")),
            (_, Some(dlc)) => Some(dlc),
            (Some(base), None) => Some(format!("{base} [DLC {}]", dlc_number(&title_id))),
            _ => None,
        }
    }

    pub fn python_dlc_name_for(&self, title_id: &str) -> Option<String> {
        let title_id = normalize_title_id(title_id)?;
        let dlc_name = self
            .lookup(&title_id)
            .and_then(|title| title.name.clone())?;
        let base_name = self
            .lookup(&base_title_id(&title_id))
            .and_then(|title| title.name.clone());
        match base_name {
            Some(base) if base != dlc_name => Some(format!("{base} [{dlc_name}]")),
            _ => Some(dlc_name),
        }
    }
}

impl NutdbVersionIndex {
    pub fn latest_version_for(&self, title_id: &str) -> Option<u64> {
        let title_id = normalize_title_id(title_id)?;
        self.titles
            .get(&title_id)
            .and_then(|versions| versions.last().copied())
    }

    pub fn versions_for(&self, title_id: &str) -> Option<&[u64]> {
        let title_id = normalize_title_id(title_id)?;
        self.titles
            .get(&title_id)
            .map(|versions| versions.as_slice())
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
struct CacheMetadata {
    source_url: String,
    etag: Option<String>,
    last_modified: Option<String>,
    raw_sha256: String,
}

#[derive(Debug, Clone)]
pub struct NutdbStore {
    cache_dir: PathBuf,
    source_url: String,
}

impl NutdbStore {
    pub fn new(cache_dir: Option<&str>, source_url: Option<&str>) -> Self {
        let cache_dir = cache_dir
            .map(PathBuf::from)
            .unwrap_or_else(default_cache_dir);
        let source_url = source_url.unwrap_or(DEFAULT_SOURCE_URL).to_string();
        Self {
            cache_dir,
            source_url,
        }
    }

    pub fn cache_dir(&self) -> &Path {
        &self.cache_dir
    }

    pub fn source_url(&self) -> &str {
        &self.source_url
    }

    pub fn ensure_index(&self) -> Result<NutdbIndex> {
        if let Ok(index) = self.load_cached_index() {
            if self
                .read_metadata()
                .map(|meta| meta.source_url == self.source_url)
                .unwrap_or(true)
            {
                return Ok(index);
            }
        }
        self.refresh()?;
        self.load_cached_index()
    }

    pub fn try_load_cached_index(&self) -> Result<Option<NutdbIndex>> {
        match self.load_cached_index() {
            Ok(index) => Ok(Some(index)),
            Err(NscbError::Io(err)) if err.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(err) => Err(err),
        }
    }

    pub fn try_load_cached_versions_index(&self) -> Result<Option<NutdbVersionIndex>> {
        match self.load_cached_versions_index() {
            Ok(index) => Ok(Some(index)),
            Err(NscbError::Io(err)) if err.kind() == std::io::ErrorKind::NotFound => Ok(None),
            Err(err) => Err(err),
        }
    }

    pub fn refresh(&self) -> Result<RefreshOutcome> {
        fs::create_dir_all(&self.cache_dir)?;

        let cached_index = self.try_load_cached_index()?;
        let cached_meta = self.read_metadata().ok();
        let client = build_http_client()?;

        if is_titledb_repo_source(&self.source_url) {
            return self.refresh_from_titledb_repo(&client, cached_index);
        }

        let mut request = client
            .get(&self.source_url)
            .header(USER_AGENT, "nscb-rust/0.1");
        if let Some(meta) = cached_meta
            .as_ref()
            .filter(|m| m.source_url == self.source_url)
        {
            if let Some(etag) = &meta.etag {
                request = request.header(IF_NONE_MATCH, etag);
            }
            if let Some(last_modified) = &meta.last_modified {
                request = request.header(IF_MODIFIED_SINCE, last_modified);
            }
        }

        let response = match request.send() {
            Ok(response) => response,
            Err(err) => {
                if let Some(index) = cached_index {
                    return Ok(RefreshOutcome {
                        status: RefreshStatus::UsedCached,
                        indexed_titles: index.len(),
                    });
                }
                return Err(NscbError::Http(err.to_string()));
            }
        };

        if response.status().as_u16() == 304 {
            let index = self.load_cached_index()?;
            let _ = self.ensure_versions_index(&client);
            return Ok(RefreshOutcome {
                status: RefreshStatus::NotModified,
                indexed_titles: index.len(),
            });
        }

        if !response.status().is_success() {
            if let Some(index) = cached_index {
                let _ = self.ensure_versions_index(&client);
                return Ok(RefreshOutcome {
                    status: RefreshStatus::UsedCached,
                    indexed_titles: index.len(),
                });
            }
            return Err(NscbError::Http(format!(
                "NUTDB request failed with status {}",
                response.status()
            )));
        }

        let etag = header_value(response.headers(), ETAG);
        let last_modified = header_value(response.headers(), LAST_MODIFIED);

        let raw_tmp_path = self.cache_dir.join(format!("{RAW_CACHE_FILE}.tmp"));
        let index_tmp_path = self.cache_dir.join(format!("{INDEX_CACHE_FILE}.tmp"));
        let meta_tmp_path = self.cache_dir.join(format!("{META_CACHE_FILE}.tmp"));
        let raw_path = self.cache_dir.join(RAW_CACHE_FILE);
        let index_path = self.cache_dir.join(INDEX_CACHE_FILE);
        let meta_path = self.cache_dir.join(META_CACHE_FILE);

        let (hash_hex, _) = download_raw_json(response, &raw_tmp_path)?;
        let index = build_index_from_path(&raw_tmp_path, self.source_url.clone())?;
        write_json_file(&index_tmp_path, &index)?;
        write_json_file(
            &meta_tmp_path,
            &CacheMetadata {
                source_url: self.source_url.clone(),
                etag,
                last_modified,
                raw_sha256: hash_hex,
            },
        )?;

        let _ = self.ensure_versions_index(&client);

        fs::rename(&raw_tmp_path, &raw_path)?;
        fs::rename(&index_tmp_path, &index_path)?;
        fs::rename(&meta_tmp_path, &meta_path)?;

        Ok(RefreshOutcome {
            status: RefreshStatus::Downloaded,
            indexed_titles: index.len(),
        })
    }

    fn ensure_versions_index(&self, client: &Client) -> Option<NutdbVersionIndex> {
        let versions_index_path = self.cache_dir.join(VERSIONS_INDEX_CACHE_FILE);
        let cached_versions_index = self.try_load_cached_versions_index().ok().flatten();

        if let Ok(versions_url) = titledb_versions_url(&self.source_url) {
            progress::log(&format!(
                "Checking TitlesDB version history from {}...",
                versions_url
            ));
            match client
                .get(&versions_url)
                .header(USER_AGENT, "nscb-rust/0.1")
                .send()
            {
                Ok(versions_response) if versions_response.status().is_success() => {
                    progress::log("Downloading version history data...");
                    match build_versions_index_from_reader(versions_response, versions_url.clone())
                    {
                        Ok(idx) => {
                            progress::log(&format!(
                                "Successfully indexed {} titles with version history.",
                                idx.titles.len()
                            ));
                            let _ = write_json_file(&versions_index_path, &idx);
                            Some(idx)
                        }
                        Err(e) => {
                            progress::log(&format!("Failed to parse version history JSON: {}", e));
                            cached_versions_index
                        }
                    }
                }
                Ok(resp) => {
                    progress::log(&format!(
                        "TitlesDB versions request failed with status: {}",
                        resp.status()
                    ));
                    cached_versions_index
                }
                Err(e) => {
                    progress::log(&format!("TitlesDB versions request error: {}", e));
                    cached_versions_index
                }
            }
        } else {
            progress::log("Could not determine versions.json URL from source URL.");
            cached_versions_index
        }
    }

    fn refresh_from_titledb_repo(
        &self,
        client: &Client,
        cached_index: Option<NutdbIndex>,
    ) -> Result<RefreshOutcome> {
        let manifest_url = titledb_contents_url(&self.source_url)?;
        let manifest_response = client
            .get(&manifest_url)
            .header(USER_AGENT, "nscb-rust/0.1")
            .send()
            .map_err(|err| NscbError::Http(err.to_string()))?;
        if !manifest_response.status().is_success() {
            if let Some(index) = cached_index {
                return Ok(RefreshOutcome {
                    status: RefreshStatus::UsedCached,
                    indexed_titles: index.len(),
                });
            }
            return Err(NscbError::Http(format!(
                "NUTDB manifest request failed with status {}",
                manifest_response.status()
            )));
        }

        let manifest_value: serde_json::Value = serde_json::from_reader(manifest_response)
            .map_err(|err| NscbError::Json(err.to_string()))?;

        let manifest: Vec<TitledbContentEntry> = if manifest_value.is_array() {
            serde_json::from_value(manifest_value)
                .map_err(|err| NscbError::Json(err.to_string()))?
        } else if manifest_value.is_object() {
            vec![serde_json::from_value(manifest_value)
                .map_err(|err| NscbError::Json(err.to_string()))?]
        } else {
            return Err(NscbError::InvalidData(
                "Invalid TitlesDB manifest format".to_string(),
            ));
        };
        let mut title_entries: Vec<TitledbContentEntry> = manifest
            .into_iter()
            .filter(|entry| entry.kind.as_deref() == Some("file"))
            .filter(|entry| is_titledb_title_file(&entry.name))
            .collect();
        title_entries.sort_by(|a, b| {
            titledb_title_file_priority(&a.name)
                .cmp(&titledb_title_file_priority(&b.name))
                .then_with(|| a.name.cmp(&b.name))
        });

        let raw_tmp_path = self.cache_dir.join(format!("{RAW_CACHE_FILE}.tmp"));
        let index_tmp_path = self.cache_dir.join(format!("{INDEX_CACHE_FILE}.tmp"));
        let meta_tmp_path = self.cache_dir.join(format!("{META_CACHE_FILE}.tmp"));
        let index_path = self.cache_dir.join(INDEX_CACHE_FILE);
        let meta_path = self.cache_dir.join(META_CACHE_FILE);
        let versions_index_path = self.cache_dir.join(VERSIONS_INDEX_CACHE_FILE);

        let total_title_files = title_entries.len();
        progress::log(&format!(
            "Found {} TitlesDB source files to import.",
            total_title_files
        ));

        let mut combined_titles: HashMap<String, NutdbTitle> = HashMap::new();
        let mut raw_hash = Sha256::new();
        let mut imported_files = 0usize;

        for (idx, entry) in title_entries.into_iter().enumerate() {
            let Some(download_url) = entry.download_url.as_deref() else {
                continue;
            };
            progress::log(&format!(
                "Importing TitlesDB file {}/{}: {}",
                idx + 1,
                total_title_files,
                entry.name
            ));
            let response = client
                .get(download_url)
                .header(USER_AGENT, "nscb-rust/0.1")
                .send()
                .map_err(|err| NscbError::Http(err.to_string()))?;
            if !response.status().is_success() {
                continue;
            }
            let bytes = response
                .bytes()
                .map_err(|err| NscbError::Http(err.to_string()))?;
            raw_hash.update(&bytes);
            imported_files += 1;
            let index = build_index_from_reader(bytes.as_ref(), self.source_url.clone())?;
            merge_title_indices(&mut combined_titles, index.titles);
        }

        if combined_titles.is_empty() {
            if let Some(index) = cached_index {
                return Ok(RefreshOutcome {
                    status: RefreshStatus::UsedCached,
                    indexed_titles: index.len(),
                });
            }
            return Err(NscbError::InvalidData(
                "No TitlesDB title files could be imported".to_string(),
            ));
        }

        let versions_url = titledb_versions_url(&self.source_url)?;
        progress::log(&format!(
            "Importing TitlesDB version history from {}...",
            versions_url
        ));
        let versions_index = match client
            .get(&versions_url)
            .header(USER_AGENT, "nscb-rust/0.1")
            .send()
        {
            Ok(versions_response) if versions_response.status().is_success() => {
                match build_versions_index_from_reader(versions_response, versions_url.clone()) {
                    Ok(index) => {
                        progress::log(&format!(
                            "Successfully indexed {} titles with version history.",
                            index.titles.len()
                        ));
                        let _ = write_json_file(&versions_index_path, &index);
                        Some(index)
                    }
                    Err(e) => {
                        progress::log(&format!("Failed to parse version history JSON: {}", e));
                        self.try_load_cached_versions_index().ok().flatten()
                    }
                }
            }
            Ok(resp) => {
                progress::log(&format!(
                    "TitlesDB versions request failed with status: {}",
                    resp.status()
                ));
                self.try_load_cached_versions_index().ok().flatten()
            }
            Err(e) => {
                progress::log(&format!("TitlesDB versions request error: {}", e));
                self.try_load_cached_versions_index().ok().flatten()
            }
        };

        progress::log(&format!(
            "Imported {} source files and indexed {} unique titles.",
            imported_files,
            combined_titles.len()
        ));
        let unique_titles = combined_titles.len();

        let index = NutdbIndex {
            source_url: self.source_url.clone(),
            titles: combined_titles,
        };
        write_json_file(&index_tmp_path, &index)?;
        write_json_file(
            &meta_tmp_path,
            &CacheMetadata {
                source_url: self.source_url.clone(),
                etag: None,
                last_modified: None,
                raw_sha256: hex::encode(raw_hash.finalize()),
            },
        )?;

        if raw_tmp_path.exists() {
            let _ = fs::remove_file(&raw_tmp_path);
        }
        fs::rename(&index_tmp_path, &index_path)?;
        fs::rename(&meta_tmp_path, &meta_path)?;
        if let Some(index) = versions_index {
            write_json_file(&versions_index_path, &index)?;
        }

        Ok(RefreshOutcome {
            status: RefreshStatus::Downloaded,
            indexed_titles: unique_titles,
        })
    }

    fn load_cached_index(&self) -> Result<NutdbIndex> {
        let path = self.cache_dir.join(INDEX_CACHE_FILE);
        let reader = BufReader::new(File::open(path)?);
        serde_json::from_reader(reader).map_err(|err| NscbError::Json(err.to_string()))
    }

    fn read_metadata(&self) -> Result<CacheMetadata> {
        let path = self.cache_dir.join(META_CACHE_FILE);
        let reader = BufReader::new(File::open(path)?);
        serde_json::from_reader(reader).map_err(|err| NscbError::Json(err.to_string()))
    }

    fn load_cached_versions_index(&self) -> Result<NutdbVersionIndex> {
        let path = self.cache_dir.join(VERSIONS_INDEX_CACHE_FILE);
        let reader = BufReader::new(File::open(path)?);
        serde_json::from_reader(reader).map_err(|err| NscbError::Json(err.to_string()))
    }
}

#[derive(Debug, Deserialize)]
struct RawTitleEntry {
    #[serde(default, deserialize_with = "deserialize_opt_string")]
    id: Option<String>,
    #[serde(default, deserialize_with = "deserialize_opt_string")]
    name: Option<String>,
    #[serde(default, deserialize_with = "deserialize_opt_string")]
    publisher: Option<String>,
    #[serde(default, deserialize_with = "deserialize_vec_string")]
    languages: Vec<String>,
    #[serde(default, deserialize_with = "deserialize_opt_u64")]
    version: Option<u64>,
    #[serde(
        default,
        rename = "releaseDate",
        deserialize_with = "deserialize_opt_u64"
    )]
    release_date: Option<u64>,
    #[serde(default, deserialize_with = "deserialize_opt_string")]
    description: Option<String>,
    #[serde(
        default,
        rename = "bannerUrl",
        deserialize_with = "deserialize_opt_string"
    )]
    banner_url: Option<String>,
    #[serde(
        default,
        rename = "iconUrl",
        deserialize_with = "deserialize_opt_string"
    )]
    icon_url: Option<String>,
    #[serde(default, deserialize_with = "deserialize_vec_string")]
    screenshots: Vec<String>,
}

impl RawTitleEntry {
    fn into_title(self) -> Option<(String, NutdbTitle)> {
        let title_id = normalize_title_id(self.id.as_deref()?)?;
        Some((
            title_id,
            NutdbTitle {
                name: self.name,
                publisher: self.publisher,
                languages: self.languages,
                version: self.version,
                release_date: self.release_date,
                description: self.description,
                banner_url: self.banner_url,
                icon_url: self.icon_url,
                screenshots: self.screenshots,
            },
        ))
    }
}

struct TitlesVisitor;

impl<'de> Visitor<'de> for TitlesVisitor {
    type Value = HashMap<String, NutdbTitle>;

    fn expecting(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("a NUTDB JSON object")
    }

    fn visit_map<M>(self, mut access: M) -> std::result::Result<Self::Value, M::Error>
    where
        M: MapAccess<'de>,
    {
        let mut titles = HashMap::new();
        while let Some(key) = access.next_key::<String>()? {
            let value_res = access.next_value::<serde_json::Value>();
            if let Ok(value) = value_res {
                if let Ok(mut entry) = serde_json::from_value::<RawTitleEntry>(value) {
                    if entry.id.is_none() {
                        entry.id = Some(key.clone());
                    }
                    if let Some((title_id, title)) = entry.into_title() {
                        titles.insert(title_id, title);
                    }
                }
            }
        }
        Ok(titles)
    }

    fn visit_seq<A>(self, mut access: A) -> std::result::Result<Self::Value, A::Error>
    where
        A: SeqAccess<'de>,
    {
        let mut titles = HashMap::new();
        while let Some(value) = access.next_element::<serde_json::Value>()? {
            if let Ok(entry) = serde_json::from_value::<RawTitleEntry>(value) {
                if let Some((title_id, title)) = entry.into_title() {
                    titles.insert(title_id, title);
                }
            }
        }
        Ok(titles)
    }
}

fn build_index_from_path(path: &Path, source_url: String) -> Result<NutdbIndex> {
    let reader = BufReader::new(File::open(path)?);
    build_index_from_reader(reader, source_url)
}

fn build_index_from_reader<R: Read>(reader: R, source_url: String) -> Result<NutdbIndex> {
    let mut deserializer = serde_json::Deserializer::from_reader(reader);
    let titles = deserializer
        .deserialize_any(TitlesVisitor)
        .map_err(|err| NscbError::Json(err.to_string()))?;
    Ok(NutdbIndex { source_url, titles })
}

fn build_versions_index_from_reader<R: Read>(
    reader: R,
    source_url: String,
) -> Result<NutdbVersionIndex> {
    let value: serde_json::Value =
        serde_json::from_reader(reader).map_err(|err| NscbError::Json(err.to_string()))?;
    let mut titles = HashMap::new();
    if let Some(obj) = value.as_object() {
        for (title_id, versions_val) in obj {
            let mut history = Vec::new();
            if let Some(map) = versions_val.as_object() {
                for version_key in map.keys() {
                    if let Ok(version) = version_key.parse::<u64>() {
                        history.push(version);
                    }
                }
            } else if let Some(arr) = versions_val.as_array() {
                for v in arr {
                    if let Some(v_u64) = v.as_u64() {
                        history.push(v_u64);
                    } else if let Some(v_str) = v.as_str() {
                        if let Ok(v_u64) = v_str.parse::<u64>() {
                            history.push(v_u64);
                        }
                    }
                }
            }
            if !history.is_empty() {
                history.sort_unstable();
                history.dedup();
                if let Some(tid) = normalize_title_id(title_id) {
                    titles.insert(tid, history);
                }
            }
        }
    }
    Ok(NutdbVersionIndex { source_url, titles })
}

fn build_http_client() -> Result<Client> {
    Client::builder()
        .timeout(Duration::from_secs(15))
        .build()
        .map_err(|err| NscbError::Http(err.to_string()))
}

fn download_raw_json(
    mut response: reqwest::blocking::Response,
    destination: &Path,
) -> Result<(String, u64)> {
    let mut writer = BufWriter::new(File::create(destination)?);
    let mut hasher = Sha256::new();
    let mut total = 0u64;
    let mut buffer = [0u8; 64 * 1024];

    loop {
        let read = response
            .read(&mut buffer)
            .map_err(|err| NscbError::Http(err.to_string()))?;
        if read == 0 {
            break;
        }
        writer.write_all(&buffer[..read])?;
        hasher.update(&buffer[..read]);
        total += read as u64;
    }
    writer.flush()?;
    Ok((hex::encode(hasher.finalize()), total))
}

fn titledb_contents_url(source_url: &str) -> Result<String> {
    let (owner, repo, branch) = titledb_repo_parts(source_url).ok_or_else(|| {
        NscbError::InvalidData(format!("Unsupported TitlesDB source URL: {source_url}"))
    })?;
    Ok(format!(
        "https://api.github.com/repos/{owner}/{repo}/contents?ref={branch}"
    ))
}

fn titledb_versions_url(source_url: &str) -> Result<String> {
    let (owner, repo, branch) = titledb_repo_parts(source_url).ok_or_else(|| {
        NscbError::InvalidData(format!("Unsupported TitlesDB source URL: {source_url}"))
    })?;
    Ok(format!(
        "https://raw.githubusercontent.com/{owner}/{repo}/{branch}/versions.json"
    ))
}

fn titledb_repo_parts(source_url: &str) -> Option<(&str, &str, &str)> {
    let url = source_url.trim();
    let host = "raw.githubusercontent.com/";
    let pos = url.find(host)? + host.len();
    let rest = &url[pos..];
    let mut parts = rest.split('/');
    let owner = parts.next()?;
    let repo = parts.next()?;
    let branch = parts.next()?;
    Some((owner, repo, branch))
}

fn is_titledb_repo_source(source_url: &str) -> bool {
    titledb_repo_parts(source_url).is_some() && !source_url.to_lowercase().ends_with(".json")
}

fn is_titledb_title_file(name: &str) -> bool {
    if name.eq_ignore_ascii_case("titles.json") {
        return true;
    }
    let mut parts = name.split('.');
    matches!(
        (parts.next(), parts.next(), parts.next(), parts.next()),
        (Some(region), Some(language), Some("json"), None)
            if region.len() == 2
                && language.len() == 2
                && region.chars().all(|c| c.is_ascii_alphabetic())
                && language.chars().all(|c| c.is_ascii_alphabetic())
    )
}

fn titledb_title_file_priority(name: &str) -> usize {
    if name.eq_ignore_ascii_case("US.en.json") {
        0
    } else if name.eq_ignore_ascii_case("titles.json") {
        1
    } else {
        2
    }
}

fn merge_title_indices(
    combined: &mut HashMap<String, NutdbTitle>,
    incoming: HashMap<String, NutdbTitle>,
) {
    for (title_id, title) in incoming {
        combined
            .entry(title_id)
            .and_modify(|existing| merge_title(existing, &title))
            .or_insert(title);
    }
}

fn merge_title(existing: &mut NutdbTitle, incoming: &NutdbTitle) {
    if existing.name.is_none() {
        existing.name = incoming.name.clone();
    }
    if existing.publisher.is_none() {
        existing.publisher = incoming.publisher.clone();
    }
    if existing.version.is_none() {
        existing.version = incoming.version;
    } else if let (Some(current), Some(next)) = (existing.version, incoming.version) {
        existing.version = Some(current.max(next));
    }
    if existing.release_date.is_none() {
        existing.release_date = incoming.release_date;
    }
    if existing.description.is_none() {
        existing.description = incoming.description.clone();
    }
    if existing.banner_url.is_none() {
        existing.banner_url = incoming.banner_url.clone();
    }
    if existing.icon_url.is_none() {
        existing.icon_url = incoming.icon_url.clone();
    }
    if existing.screenshots.is_empty() && !incoming.screenshots.is_empty() {
        existing.screenshots = incoming.screenshots.clone();
    } else {
        for screenshot in &incoming.screenshots {
            if !existing
                .screenshots
                .iter()
                .any(|current| current == screenshot)
            {
                existing.screenshots.push(screenshot.clone());
            }
        }
    }
    if incoming.languages.is_empty() {
        return;
    }
    for lang in &incoming.languages {
        if !existing
            .languages
            .iter()
            .any(|current| current.eq_ignore_ascii_case(lang))
        {
            existing.languages.push(lang.clone());
        }
    }
}

#[derive(Debug, Deserialize)]
struct TitledbContentEntry {
    name: String,
    #[serde(rename = "type")]
    kind: Option<String>,
    download_url: Option<String>,
}

fn write_json_file<T: Serialize>(path: &Path, value: &T) -> Result<()> {
    let writer = BufWriter::new(File::create(path)?);
    serde_json::to_writer(writer, value).map_err(|err| NscbError::Json(err.to_string()))
}

fn header_value(
    headers: &reqwest::header::HeaderMap,
    name: reqwest::header::HeaderName,
) -> Option<String> {
    headers
        .get(name)
        .and_then(|value| value.to_str().ok())
        .map(|value| value.to_string())
}

fn deserialize_opt_string<'de, D>(deserializer: D) -> std::result::Result<Option<String>, D::Error>
where
    D: Deserializer<'de>,
{
    let value = Option::<serde_json::Value>::deserialize(deserializer)?;
    Ok(match value {
        None | Some(serde_json::Value::Null) => None,
        Some(serde_json::Value::String(text)) => normalize_optional_text(text),
        Some(other) => normalize_optional_text(other.to_string()),
    })
}

fn deserialize_opt_u64<'de, D>(deserializer: D) -> std::result::Result<Option<u64>, D::Error>
where
    D: Deserializer<'de>,
{
    let value = Option::<serde_json::Value>::deserialize(deserializer)?;
    Ok(match value {
        None | Some(serde_json::Value::Null) => None,
        Some(serde_json::Value::Number(number)) => number
            .as_u64()
            .or_else(|| number.as_i64().map(|v| v.max(0) as u64)),
        Some(serde_json::Value::String(text)) => text.trim().parse::<u64>().ok(),
        Some(_) => None,
    })
}

fn deserialize_vec_string<'de, D>(deserializer: D) -> std::result::Result<Vec<String>, D::Error>
where
    D: Deserializer<'de>,
{
    let value = Option::<serde_json::Value>::deserialize(deserializer)?;
    let mut items_out = Vec::new();
    match value {
        None | Some(serde_json::Value::Null) => {}
        Some(serde_json::Value::String(text)) => {
            if text.contains(',') {
                for item in text.split(',') {
                    if let Some(item) = normalize_optional_text(item.to_string()) {
                        items_out.push(item);
                    }
                }
            } else if let Some(item) = normalize_optional_text(text) {
                items_out.push(item);
            }
        }
        Some(serde_json::Value::Array(items)) => {
            for item in items {
                match item {
                    serde_json::Value::String(text) => {
                        if let Some(item) = normalize_optional_text(text) {
                            items_out.push(item);
                        }
                    }
                    other => {
                        if let Some(item) =
                            normalize_optional_text(other.to_string().trim_matches('"').to_string())
                        {
                            items_out.push(item);
                        }
                    }
                }
            }
        }
        Some(other) => {
            if let Some(item) =
                normalize_optional_text(other.to_string().trim_matches('"').to_string())
            {
                items_out.push(item);
            }
        }
    }
    Ok(items_out)
}

fn deserialize_version_history_map<'de, D>(
    deserializer: D,
) -> std::result::Result<HashMap<String, Vec<u64>>, D::Error>
where
    D: Deserializer<'de>,
{
    let value = Option::<serde_json::Value>::deserialize(deserializer)?;
    let mut titles = HashMap::new();
    let Some(serde_json::Value::Object(obj)) = value else {
        return Ok(titles);
    };
    for (title_id, value) in obj {
        let mut versions = Vec::new();
        match value {
            serde_json::Value::Number(number) => {
                if let Some(version) = number
                    .as_u64()
                    .or_else(|| number.as_i64().map(|v| v.max(0) as u64))
                {
                    versions.push(version);
                }
            }
            serde_json::Value::Array(items) => {
                for item in items {
                    match item {
                        serde_json::Value::Number(number) => {
                            if let Some(version) = number
                                .as_u64()
                                .or_else(|| number.as_i64().map(|v| v.max(0) as u64))
                            {
                                versions.push(version);
                            }
                        }
                        serde_json::Value::String(text) => {
                            if let Ok(version) = text.trim().parse::<u64>() {
                                versions.push(version);
                            }
                        }
                        _ => {}
                    }
                }
            }
            serde_json::Value::String(text) => {
                if let Ok(version) = text.trim().parse::<u64>() {
                    versions.push(version);
                }
            }
            _ => {}
        }
        if !versions.is_empty() {
            versions.sort_unstable();
            versions.dedup();
            if normalize_title_id(&title_id).is_some() {
                titles.insert(title_id.to_ascii_uppercase(), versions);
            }
        }
    }
    Ok(titles)
}

fn normalize_optional_text(value: String) -> Option<String> {
    let trimmed = value.trim();
    if trimmed.is_empty() || trimmed.eq_ignore_ascii_case("none") {
        None
    } else {
        Some(trimmed.to_string())
    }
}

pub fn normalize_title_id(title_id: &str) -> Option<String> {
    let trimmed = title_id.trim();
    if trimmed.len() != 16 || !trimmed.chars().all(|c| c.is_ascii_hexdigit()) {
        return None;
    }
    Some(trimmed.to_ascii_uppercase())
}

pub fn base_title_id(title_id: &str) -> String {
    let title_id =
        normalize_title_id(title_id).unwrap_or_else(|| title_id.trim().to_ascii_uppercase());
    if title_id.ends_with("000") {
        return title_id;
    }
    if title_id.ends_with("800") && title_id.len() == 16 {
        let mut chars: Vec<char> = title_id.chars().collect();
        chars[13] = '0';
        chars[14] = '0';
        chars[15] = '0';
        return chars.into_iter().collect();
    }
    if title_id.len() == 16 {
        let mut chars: Vec<char> = title_id.chars().collect();
        if let Some(nibble) = chars.get(12).and_then(|c| c.to_digit(16)) {
            let adjusted = nibble.saturating_sub(1);
            chars[12] = char::from_digit(adjusted, 16).unwrap().to_ascii_uppercase();
            chars[13] = '0';
            chars[14] = '0';
            chars[15] = '0';
            return chars.into_iter().collect();
        }
    }
    title_id
}

pub fn dlc_number(title_id: &str) -> u16 {
    let title_id =
        normalize_title_id(title_id).unwrap_or_else(|| title_id.trim().to_ascii_uppercase());
    u16::from_str_radix(&title_id[13..], 16).unwrap_or(0)
}

fn default_cache_dir() -> PathBuf {
    if let Ok(path) = std::env::var("NSCB_NUTDB_CACHE_DIR") {
        return PathBuf::from(path);
    }
    if let Ok(path) = std::env::var("XDG_CACHE_HOME") {
        return PathBuf::from(path).join("nscb").join("nutdb");
    }
    if let Ok(path) = std::env::var("LOCALAPPDATA") {
        return PathBuf::from(path).join("nscb").join("nutdb");
    }
    if let Ok(path) = std::env::var("HOME") {
        return PathBuf::from(path)
            .join(".cache")
            .join("nscb")
            .join("nutdb");
    }
    if let Ok(path) = std::env::var("USERPROFILE") {
        return PathBuf::from(path)
            .join(".cache")
            .join("nscb")
            .join("nutdb");
    }
    PathBuf::from(".nscb").join("cache").join("nutdb")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;
    use std::sync::{Arc, Mutex};
    use std::thread;

    use tempfile::tempdir;

    #[test]
    fn base_title_id_matches_python_rules() {
        assert_eq!(base_title_id("0100F8F0000A2000"), "0100F8F0000A2000");
        assert_eq!(base_title_id("0100F8F0000A2800"), "0100F8F0000A2000");
        assert_eq!(base_title_id("0100F8F0000A3401"), "0100F8F0000A2000");
    }

    #[test]
    fn builds_compact_index_from_streamed_json() {
        let json = r#"{
            "0": {"id":"0100F8F0000A2000","name":"Base Game","publisher":"Studio","languages":["en","fr"],"version":"0"},
            "1": {"id":"0100F8F0000A3401","name":"Expansion Pack","publisher":"Studio"},
            "2": {"id":"invalid","name":"Skip Me"}
        }"#;

        let index = build_index_from_reader(
            json.as_bytes(),
            "http://example.test/nutdb.json".to_string(),
        )
        .expect("index builds");

        assert_eq!(index.len(), 2);
        assert_eq!(
            index.display_name_for("0100F8F0000A3401").as_deref(),
            Some("Base Game [Expansion Pack]")
        );
        assert_eq!(
            index.python_dlc_name_for("0100F8F0000A3401").as_deref(),
            Some("Base Game [Expansion Pack]")
        );
        assert_eq!(index.languages_for("0100F8F0000A3401"), vec!["en", "fr"]);
    }

    #[test]
    fn python_dlc_name_for_requires_exact_dlc_entry() {
        let json = r#"{
            "0": {"id":"0100F8F0000A2000","name":"Base Game","publisher":"Studio","languages":["en"],"version":"0"}
        }"#;

        let index = build_index_from_reader(
            json.as_bytes(),
            "http://example.test/nutdb.json".to_string(),
        )
        .expect("index builds");

        assert_eq!(
            index.display_name_for("0100F8F0000A3401").as_deref(),
            Some("Base Game [DLC 1025]")
        );
        assert_eq!(index.python_dlc_name_for("0100F8F0000A3401"), None);
    }

    #[test]
    fn refresh_uses_conditional_http_and_keeps_local_index() {
        let dir = tempdir().expect("tempdir");
        let etag = "\"etag-123\"";
        let body = r#"{
            "0": {"id":"0100F8F0000A2000","name":"Base Game","publisher":"Studio","languages":["en"],"version":"0"}
        }"#;
        let request_count = Arc::new(Mutex::new(0usize));
        let listener = TcpListener::bind("127.0.0.1:0").expect("listener");
        let addr = listener.local_addr().expect("local addr");
        let requests = Arc::clone(&request_count);
        let server = thread::spawn(move || {
            for _ in 0..2 {
                let (mut stream, _) = listener.accept().expect("accept");
                let mut buffer = [0u8; 8192];
                let read = stream.read(&mut buffer).expect("read request");
                let request = String::from_utf8_lossy(&buffer[..read]).to_lowercase();
                let mut count = requests.lock().expect("lock");
                *count += 1;
                drop(count);

                if request.contains("if-none-match: \"etag-123\"") {
                    let response = "HTTP/1.1 304 Not Modified\r\nConnection: close\r\nContent-Length: 0\r\n\r\n";
                    stream.write_all(response.as_bytes()).expect("write 304");
                } else {
                    let response = format!(
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nETag: {etag}\r\nLast-Modified: Tue, 01 Jan 2030 00:00:00 GMT\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                        body.len(),
                        body
                    );
                    stream.write_all(response.as_bytes()).expect("write 200");
                }
            }
        });

        let url = format!("http://{addr}/nutdb.json");
        let store = NutdbStore::new(Some(dir.path().to_string_lossy().as_ref()), Some(&url));

        let first = store.refresh().expect("first refresh");
        assert_eq!(first.status, RefreshStatus::Downloaded);
        assert_eq!(first.indexed_titles, 1);

        let second = store.refresh().expect("second refresh");
        assert_eq!(second.status, RefreshStatus::NotModified);
        assert_eq!(second.indexed_titles, 1);

        let index = store.ensure_index().expect("cached index");
        assert_eq!(
            index.display_name_for("0100F8F0000A2000").as_deref(),
            Some("Base Game")
        );

        server.join().expect("server join");
        assert_eq!(*request_count.lock().expect("lock"), 2);
    }
}
