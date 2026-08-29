//! Organize a source folder into a per-platform library.
//!
//! Walks a source directory, detects each file's console from its extension (see
//! `crate::platform::Platform::detect`), and moves it into `<library>/<Platform>/`.
//! Files with unknown extensions are moved into an `_Unsorted` folder. All destinations
//! are uniquely de-duplicated so nothing is silently overwritten.

use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};

use crate::platform::Platform;

/// Summary of one organize pass.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct OrganizeSummary {
    pub total_files: usize,
    pub moved: usize,
    pub skipped: usize,
    pub errors: usize,
    pub unsorted: usize,
    /// platform display name -> file count. Includes "_Unsorted".
    pub per_platform: HashMap<String, usize>,
}

impl OrganizeSummary {
    pub fn report(&self) -> String {
        let mut platforms = self.per_platform.iter().collect::<Vec<_>>();
        platforms.sort_by(|a, b| b.1.cmp(a.1).then_with(|| a.0.cmp(b.0)));
        let mut lines = Vec::new();
        lines.push(format!(
            "Organized {} file(s): {} moved, {} skipped, {} unsorted, {} error(s).",
            self.total_files, self.moved, self.skipped, self.unsorted, self.errors
        ));
        for (name, count) in platforms {
            lines.push(format!("  {name}: {count}"));
        }
        lines.join("\n")
    }
}

/// Append files found recursively under `root` to `out`, skipping any subdirectory that
/// starts with (or contains) `prune` — so we never re-process the destination library
/// folder when it is nested inside the source.
fn collect_files(root: &Path, prune: &Option<PathBuf>, out: &mut Vec<PathBuf>) {
    let entries = match fs::read_dir(root) {
        Ok(entries) => entries,
        Err(_) => return,
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            if let Some(prune) = prune {
                if prune.starts_with(&path) || path.starts_with(prune) {
                    continue;
                }
            }
            collect_files(&path, prune, out);
        } else if path.is_file() {
            out.push(path);
        }
    }
}

fn platform_folder_name(platform: Option<Platform>) -> String {
    match platform {
        Some(p) => p.name().to_string(),
        None => "_Unsorted".to_string(),
    }
}

/// Compute a unique destination path under `dir` for `file_name`, appending ` (N)`
/// before the extension until no collision exists.
fn unique_dest(dir: &Path, file_name: &str) -> PathBuf {
    let first = dir.join(file_name);
    if !first.exists() {
        return first;
    }
    let path = Path::new(file_name);
    let stem = path.file_stem().and_then(|s| s.to_str()).unwrap_or("file");
    let ext = path
        .extension()
        .and_then(|e| e.to_str())
        .map(|e| format!(".{e}"))
        .unwrap_or_default();
    for index in 2.. {
        let candidate = dir.join(format!("{stem} ({index}){ext}"));
        if !candidate.exists() {
            return candidate;
        }
    }
    unreachable!("unbounded unique filename loop")
}

/// Organize all ROMs under `source` into per-platform folders beneath `library`.
///
/// * `source` — directory scanned for files.
/// * `library` — destination root; `<library>/<Platform>` folders are created.
/// * `move_files` — if true files are moved (deleted from source), else copied.
///
/// Returns a summary. Never overwrites an existing file; collisions get a ` (N)` suffix.
pub fn organize_library(source: &Path, library: &Path, move_files: bool) -> crate::error::Result<OrganizeSummary> {
    if !source.is_dir() {
        return Err(crate::error::NscbError::InvalidData(format!(
            "Source is not a directory: {}",
            source.display()
        )));
    }
    fs::create_dir_all(library).map_err(|e| {
        crate::error::NscbError::Io(std::io::Error::new(
            e.kind(),
            format!("Could not create library folder {}: {e}", library.display()),
        ))
    })?;

    let mut files = Vec::new();
    // Never descend into the library when it's nested inside source.
    let prune = if library == source || library.starts_with(source) {
        Some(library.to_path_buf())
    } else {
        None
    };
    collect_files(source, &prune, &mut files);

    let mut summary = OrganizeSummary {
        total_files: files.len(),
        ..Default::default()
    };
    let mut errors = Vec::new();

    for src in &files {
        let platform = Platform::detect(src);
        let folder_name = platform_folder_name(platform);
        let dest_dir = library.join(&folder_name);

        if let Err(e) = fs::create_dir_all(&dest_dir) {
            summary.errors += 1;
            errors.push(format!("Could not create {dest_dir:?}: {e}"));
            continue;
        }

        let Some(file_name) = src.file_name().and_then(|n| n.to_str()) else {
            summary.skipped += 1;
            continue;
        };
        let dest = unique_dest(&dest_dir, file_name);

        // Skip already-in-place files (already in the right folder under the library).
        if *src == dest {
            summary.skipped += 1;
            continue;
        }

        let result = if move_files {
            fs::rename(src, &dest)
        } else {
            fs::copy(src, &dest).map(|_| ())
        };

        match result {
            Ok(_) => {
                summary.moved += 1;
                if platform.is_none() {
                    summary.unsorted += 1;
                }
                *summary.per_platform.entry(folder_name).or_insert(0) += 1;
            }
            Err(e) => {
                summary.errors += 1;
                errors.push(format!("Failed {} {:?} -> {:?}: {e}", {
                    if move_files { "moving" } else { "copying" }
                }, src, dest));
            }
        }
    }

    if !errors.is_empty() && summary.moved == 0 && summary.total_files > 0 {
        return Err(crate::error::NscbError::InvalidData(errors.join("\n")));
    }

    Ok(summary)
}

#[cfg(test)]
mod tests {
    use super::{organize_library, unique_dest};
    use std::fs;
    use std::path::PathBuf;

    fn write_file(path: &PathBuf, contents: &[u8]) {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).unwrap();
        }
        fs::write(path, contents).unwrap();
    }

    #[test]
    fn organizes_by_platform_and_moves() {
        let tmp = tempfile::tempdir().unwrap();
        let src = tmp.path().join("import");
        let lib = tmp.path().join("library");

        write_file(&src.join("Zelda.nsp"), b"switch");
        write_file(&src.join("Crash.bin"), b"psx-bin");
        write_file(&src.join("ff7.cue"), b"psx-cue");
        write_file(&src.join("readme.txt"), b"unknown");

        let summary = organize_library(&src, &lib, true).unwrap();

        assert!(lib.join("Switch").join("Zelda.nsp").exists());
        assert!(lib.join("_Unsorted").join("readme.txt").exists());
        // .bin is ambiguous without a folder hint -> defaults to PlayStation.
        assert!(lib.join("PlayStation").join("Crash.bin").exists());
        assert!(lib.join("PlayStation").join("ff7.cue").exists());
        assert!(!src.join("Zelda.nsp").exists(), "source should be removed on move");

        assert_eq!(summary.moved, 4);
        assert_eq!(summary.total_files, 4);
    }

    #[test]
    fn copies_instead_of_moves_when_requested() {
        let tmp = tempfile::tempdir().unwrap();
        let src = tmp.path().join("import");
        let lib = tmp.path().join("library");

        write_file(&src.join("Metroid.nds"), b"nds");

        organize_library(&src, &lib, false).unwrap();

        assert!(src.join("Metroid.nds").exists(), "copy keeps the source");
        assert!(lib.join("Nintendo DS").join("Metroid.nds").exists());
    }

    #[test]
    fn dedupes_collisions_with_suffix() {
        let tmp = tempfile::tempdir().unwrap();
        let lib = tmp.path().join("library");
        let dest = lib.join("Switch");
        fs::create_dir_all(&dest).unwrap();
        fs::write(dest.join("Game.nsp"), b"existing").unwrap();

        let got = unique_dest(&dest, "Game.nsp");
        assert_eq!(got.file_name().unwrap().to_str().unwrap(), "Game (2).nsp");
    }

    #[test]
    fn bad_source_errors() {
        let tmp = tempfile::tempdir().unwrap();
        let result = organize_library(&tmp.path().join("nope"), &tmp.path().join("lib"), true);
        assert!(result.is_err());
    }
}