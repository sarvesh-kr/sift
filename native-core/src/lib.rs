use serde::Serialize;
use std::{
    collections::{HashMap, HashSet},
    fs::{self, File},
    io::{Read, Seek, SeekFrom},
    path::{Path, PathBuf},
    sync::atomic::{AtomicBool, Ordering},
    time::{Instant, UNIX_EPOCH},
};
use walkdir::WalkDir;

#[cfg(windows)]
mod windows_trash;

pub const MAX_FILES: usize = 500_000;

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FileEntry {
    pub path: String,
    pub name: String,
    pub size: u64,
    pub modified: u64,
    pub category: String,
    pub duplicate_group: Option<String>,
    #[serde(skip)]
    modified_nanos: u128,
    #[serde(skip)]
    identity: String,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VolumeSpace {
    pub total_bytes: u64,
    pub available_bytes: u64,
}

pub fn volume_space(path: &Path) -> Option<VolumeSpace> {
    Some(VolumeSpace {
        total_bytes: fs2::total_space(path).ok()?,
        available_bytes: fs2::available_space(path).ok()?,
    })
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ScanReport {
    pub root: String,
    pub files: Vec<FileEntry>,
    pub skipped: usize,
    pub truncated: bool,
    pub elapsed_ms: u64,
    pub cancelled: bool,
    pub volume: Option<VolumeSpace>,
}

#[derive(Clone, Serialize)]
pub struct Progress {
    pub files: usize,
    pub bytes: u64,
    pub phase: &'static str,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DuplicateStats {
    pub candidate_files: usize,
    pub sampled_files: usize,
    pub hashed_files: usize,
    pub verified_pairs: usize,
    pub bytes_read: u64,
    pub elapsed_ms: u64,
}

#[derive(Serialize)]
pub struct DuplicateResult {
    pub files: Vec<FileEntry>,
    pub cancelled: bool,
    pub skipped: usize,
    pub stats: DuplicateStats,
}

#[derive(Serialize)]
pub struct TrashFailure {
    pub path: String,
    pub error: String,
}

#[derive(Serialize)]
pub struct TrashResult {
    pub removed: Vec<String>,
    pub failed: Vec<TrashFailure>,
    pub volume: Option<VolumeSpace>,
}

pub fn category(path: &Path) -> &'static str {
    match path
        .extension()
        .and_then(|v| v.to_str())
        .unwrap_or("")
        .to_ascii_lowercase()
        .as_str()
    {
        "mp4" | "mov" | "mkv" | "avi" | "webm" | "m4v" | "wmv" | "mpeg" | "mpg" => "Videos",
        "jpg" | "jpeg" | "png" | "gif" | "webp" | "heic" | "heif" | "tiff" | "tif" | "bmp"
        | "svg" | "raw" | "cr2" | "nef" | "avif" => "Images",
        "pdf" | "doc" | "docx" | "xls" | "xlsx" | "ppt" | "pptx" | "txt" | "md" | "rtf" | "odt"
        | "csv" | "epub" | "pages" | "numbers" | "key" => "Documents",
        "mp3" | "wav" | "flac" | "aac" | "m4a" | "ogg" | "aiff" | "wma" | "opus" => "Audio",
        "zip" | "rar" | "7z" | "tar" | "gz" | "bz2" | "xz" | "iso" | "zst" => "Archives",
        "exe" | "msi" | "dmg" | "pkg" | "deb" | "rpm" | "appimage" => "Applications",
        "rs" | "js" | "jsx" | "ts" | "tsx" | "py" | "go" | "java" | "kt" | "swift" | "c" | "h"
        | "cpp" | "cs" | "css" | "scss" | "html" | "json" | "yaml" | "yml" | "toml" | "sh"
        | "sql" | "vue" | "svelte" => "Code",
        _ => "Other",
    }
}

fn modified_nanos(meta: &fs::Metadata) -> u128 {
    meta.modified()
        .ok()
        .and_then(|time| time.duration_since(UNIX_EPOCH).ok())
        .map(|duration| duration.as_nanos())
        .unwrap_or(0)
}

fn identity(path: &Path, meta: &fs::Metadata) -> String {
    file_id::get_file_id(path)
        .map(|id| format!("{id:?}"))
        .unwrap_or_else(|_| format!("{}:{:?}", path.display(), meta.created().ok()))
}

pub fn scan(
    root: &Path,
    cancel: &AtomicBool,
    mut progress: impl FnMut(Progress),
) -> Result<ScanReport, String> {
    let started = Instant::now();
    let root =
        fs::canonicalize(root).map_err(|error| format!("Cannot access this folder: {error}"))?;
    if !root.is_dir() {
        return Err("Choose a folder or drive, rather than a file.".into());
    }
    let root_string = root
        .to_str()
        .ok_or("This folder's name is not supported Unicode.")?
        .to_owned();
    let mut files = Vec::new();
    let mut skipped = 0;
    let mut truncated = false;
    let mut total = 0u64;
    let mut last_update = Instant::now();
    for item in WalkDir::new(&root).follow_links(false).max_open(32) {
        if cancel.load(Ordering::Relaxed) {
            break;
        }
        let entry = match item {
            Ok(entry) => entry,
            Err(_) => {
                skipped += 1;
                continue;
            }
        };
        if entry.file_type().is_symlink() {
            skipped += 1;
            continue;
        }
        if !entry.file_type().is_file() {
            continue;
        }
        let meta = match fs::symlink_metadata(entry.path()) {
            Ok(meta) if meta.is_file() => meta,
            _ => {
                skipped += 1;
                continue;
            }
        };
        let Some(path) = entry.path().to_str() else {
            skipped += 1;
            continue;
        };
        let Some(name) = entry.file_name().to_str() else {
            skipped += 1;
            continue;
        };
        if files.len() == MAX_FILES {
            truncated = true;
            break;
        }
        let nanos = modified_nanos(&meta);
        files.push(FileEntry {
            path: path.to_owned(),
            name: name.to_owned(),
            size: meta.len(),
            modified: (nanos / 1_000_000) as u64,
            category: category(entry.path()).into(),
            duplicate_group: None,
            modified_nanos: nanos,
            identity: identity(entry.path(), &meta),
        });
        total = total.saturating_add(meta.len());
        if last_update.elapsed().as_millis() >= 120 {
            progress(Progress {
                files: files.len(),
                bytes: total,
                phase: "Scanning files",
            });
            last_update = Instant::now();
        }
    }
    progress(Progress {
        files: files.len(),
        bytes: total,
        phase: "Scanning files",
    });
    Ok(ScanReport {
        volume: volume_space(&root),
        root: root_string,
        files,
        skipped,
        truncated,
        elapsed_ms: started.elapsed().as_millis() as u64,
        cancelled: cancel.load(Ordering::Relaxed),
    })
}

/// Only original scan entries with unchanged metadata and no redirected path qualify.
/// This check reduces races but is not a filesystem transaction; no files are auto-selected.
pub fn validate_candidate(root: &Path, entry: &FileEntry) -> Result<PathBuf, String> {
    let path = PathBuf::from(&entry.path);
    if !path.starts_with(root) {
        return Err("File is outside the scanned folder.".into());
    }
    if fs::canonicalize(&path).map_err(|error| error.to_string())? != path {
        return Err("File path changed or passes through a symbolic link. Scan again.".into());
    }
    let meta = fs::symlink_metadata(&path).map_err(|error| error.to_string())?;
    if !meta.is_file() || meta.file_type().is_symlink() {
        return Err("Only regular, scanned files can be moved to trash.".into());
    }
    if meta.len() != entry.size
        || modified_nanos(&meta) != entry.modified_nanos
        || identity(&path, &meta) != entry.identity
    {
        return Err("File changed since the scan. Scan again before removing it.".into());
    }
    Ok(path)
}

/// Samples are only a prefilter. Every reported match passes full hashing and
/// a separate byte comparison, with snapshot validation around each read.
fn sample_signature(
    root: &Path,
    entry: &FileEntry,
    cancel: &AtomicBool,
    stats: &mut DuplicateStats,
) -> Result<String, String> {
    let path = validate_candidate(root, entry)?;
    let mut reader = File::open(&path).map_err(|error| error.to_string())?;
    let block = entry.size.min(16 * 1024) as usize;
    let end = entry.size - block as u64;
    let mut positions = vec![0, end / 2, end];
    positions.sort_unstable();
    positions.dedup();
    let mut hash = blake3::Hasher::new();
    hash.update(&entry.size.to_le_bytes());
    let mut buffer = vec![0u8; block];
    for position in positions {
        if cancel.load(Ordering::Relaxed) {
            return Err("Cancelled".into());
        }
        reader
            .seek(SeekFrom::Start(position))
            .map_err(|error| error.to_string())?;
        reader
            .read_exact(&mut buffer)
            .map_err(|error| error.to_string())?;
        hash.update(&position.to_le_bytes());
        hash.update(&buffer);
        stats.bytes_read = stats.bytes_read.saturating_add(block as u64);
    }
    validate_candidate(root, entry)?;
    Ok(hash.finalize().to_hex().to_string())
}

fn full_hash(
    root: &Path,
    entry: &FileEntry,
    cancel: &AtomicBool,
    stats: &mut DuplicateStats,
    progress: &mut impl FnMut(Progress),
) -> Result<String, String> {
    let path = validate_candidate(root, entry)?;
    let mut reader = File::open(&path).map_err(|error| error.to_string())?;
    let mut buffer = vec![0u8; 256 * 1024];
    let mut hash = blake3::Hasher::new();
    let mut update = Instant::now();
    loop {
        if cancel.load(Ordering::Relaxed) {
            return Err("Cancelled".into());
        }
        let count = reader
            .read(&mut buffer)
            .map_err(|error| error.to_string())?;
        if count == 0 {
            break;
        }
        hash.update(&buffer[..count]);
        stats.bytes_read = stats.bytes_read.saturating_add(count as u64);
        if update.elapsed().as_millis() >= 120 {
            progress(Progress {
                files: stats.hashed_files,
                bytes: stats.bytes_read,
                phase: "Hashing duplicate candidates",
            });
            update = Instant::now();
        }
    }
    validate_candidate(root, entry)?;
    Ok(hash.finalize().to_hex().to_string())
}

fn bytes_equal(
    root: &Path,
    left: &FileEntry,
    right: &FileEntry,
    cancel: &AtomicBool,
    stats: &mut DuplicateStats,
    progress: &mut impl FnMut(Progress),
) -> Result<bool, String> {
    let left_path = validate_candidate(root, left)?;
    let right_path = validate_candidate(root, right)?;
    let mut a = File::open(left_path).map_err(|error| error.to_string())?;
    let mut b = File::open(right_path).map_err(|error| error.to_string())?;
    let mut left_buffer = vec![0u8; 256 * 1024];
    let mut right_buffer = vec![0u8; 256 * 1024];
    let mut remaining = left.size;
    let mut update = Instant::now();
    if left.size != right.size {
        return Ok(false);
    }
    while remaining > 0 {
        if cancel.load(Ordering::Relaxed) {
            return Err("Cancelled".into());
        }
        let count = remaining.min(left_buffer.len() as u64) as usize;
        a.read_exact(&mut left_buffer[..count])
            .map_err(|error| error.to_string())?;
        b.read_exact(&mut right_buffer[..count])
            .map_err(|error| error.to_string())?;
        stats.bytes_read = stats.bytes_read.saturating_add(count as u64 * 2);
        if left_buffer[..count] != right_buffer[..count] {
            return Ok(false);
        }
        remaining -= count as u64;
        if update.elapsed().as_millis() >= 120 {
            progress(Progress {
                files: stats.verified_pairs,
                bytes: stats.bytes_read,
                phase: "Verifying matching bytes",
            });
            update = Instant::now();
        }
    }
    validate_candidate(root, left)?;
    validate_candidate(root, right)?;
    Ok(true)
}

fn empty_stats() -> DuplicateStats {
    DuplicateStats {
        candidate_files: 0,
        sampled_files: 0,
        hashed_files: 0,
        verified_pairs: 0,
        bytes_read: 0,
        elapsed_ms: 0,
    }
}

pub fn find_duplicates(
    report: &ScanReport,
    cancel: &AtomicBool,
    mut progress: impl FnMut(Progress),
) -> DuplicateResult {
    let started = Instant::now();
    let root = Path::new(&report.root);
    let mut files = report.files.clone();
    let mut stats = empty_stats();
    let mut sizes: HashMap<u64, Vec<usize>> = HashMap::new();
    for (index, file) in files.iter_mut().enumerate() {
        file.duplicate_group = None;
        if file.size > 0 {
            sizes.entry(file.size).or_default().push(index);
        }
    }
    let mut sampled: HashMap<(u64, String), Vec<usize>> = HashMap::new();
    let mut skipped = 0;
    let mut seen = HashSet::new();
    let mut update = Instant::now();
    stats.candidate_files = sizes
        .values()
        .filter(|indices| indices.len() > 1)
        .map(Vec::len)
        .sum();
    'sampling: for indices in sizes.values().filter(|indices| indices.len() > 1) {
        for &index in indices {
            if cancel.load(Ordering::Relaxed) {
                break 'sampling;
            }
            if !seen.insert(files[index].identity.clone()) {
                continue;
            }
            match sample_signature(root, &files[index], cancel, &mut stats) {
                Ok(hash) => {
                    sampled
                        .entry((files[index].size, hash))
                        .or_default()
                        .push(index);
                }
                Err(_) => skipped += 1,
            }
            stats.sampled_files += 1;
            if update.elapsed().as_millis() >= 120 {
                progress(Progress {
                    files: stats.sampled_files,
                    bytes: stats.bytes_read,
                    phase: "Sampling duplicate candidates",
                });
                update = Instant::now();
            }
        }
    }
    let mut hashes: HashMap<String, Vec<usize>> = HashMap::new();
    'hashing: for indices in sampled.values().filter(|indices| indices.len() > 1) {
        for &index in indices {
            if cancel.load(Ordering::Relaxed) {
                break 'hashing;
            }
            match full_hash(root, &files[index], cancel, &mut stats, &mut progress) {
                Ok(hash) => {
                    hashes.entry(hash).or_default().push(index);
                }
                Err(_) => skipped += 1,
            }
            stats.hashed_files += 1;
        }
    }
    'verification: for (hash, indices) in
        hashes.into_iter().filter(|(_, indices)| indices.len() > 1)
    {
        let mut verified: Vec<Vec<usize>> = Vec::new();
        for index in indices {
            if cancel.load(Ordering::Relaxed) {
                break 'verification;
            }
            let mut destination = None;
            let mut failed = false;
            for (group, matches) in verified.iter().enumerate() {
                match bytes_equal(
                    root,
                    &files[matches[0]],
                    &files[index],
                    cancel,
                    &mut stats,
                    &mut progress,
                ) {
                    Ok(true) => {
                        stats.verified_pairs += 1;
                        destination = Some(group);
                        break;
                    }
                    Ok(false) => {}
                    Err(_) => {
                        skipped += 1;
                        failed = true;
                        break;
                    }
                }
            }
            if !failed {
                if let Some(group) = destination {
                    verified[group].push(index);
                } else {
                    verified.push(vec![index]);
                }
            }
        }
        for (partition, indices) in verified
            .into_iter()
            .enumerate()
            .filter(|(_, group)| group.len() > 1)
        {
            for index in indices {
                files[index].duplicate_group = Some(format!("{hash}:{partition}"));
            }
        }
    }
    stats.elapsed_ms = started.elapsed().as_millis() as u64;
    DuplicateResult {
        files,
        cancelled: cancel.load(Ordering::Relaxed),
        skipped,
        stats,
    }
}

pub fn trash_selected(report: &ScanReport, paths: &[String]) -> TrashResult {
    trash_selected_with_progress(report, paths, |_| {})
}

pub fn trash_selected_with_progress(
    report: &ScanReport,
    paths: &[String],
    progress: impl FnMut(Progress) + Send,
) -> TrashResult {
    #[cfg(windows)]
    return windows_trash::trash_selected(report, paths, progress);
    #[cfg(not(windows))]
    trash_selected_using(
        report,
        paths,
        |path| trash::delete(path).map_err(|error| error.to_string()),
        progress,
    )
}

#[cfg(test)]
fn trash_selected_with(
    report: &ScanReport,
    paths: &[String],
    move_to_trash: impl FnMut(&Path) -> Result<(), String>,
) -> TrashResult {
    trash_selected_using(report, paths, move_to_trash, |_| {})
}

fn trash_selected_using(
    report: &ScanReport,
    paths: &[String],
    mut move_to_trash: impl FnMut(&Path) -> Result<(), String>,
    mut progress: impl FnMut(Progress),
) -> TrashResult {
    let index: HashMap<&str, &FileEntry> = report
        .files
        .iter()
        .map(|file| (file.path.as_str(), file))
        .collect();
    let mut result = TrashResult {
        removed: Vec::new(),
        failed: Vec::new(),
        volume: None,
    };
    let selected: HashSet<&str> = paths.iter().map(String::as_str).collect();
    let selected_groups: HashSet<&str> = selected
        .iter()
        .filter_map(|path| {
            index
                .get(path)
                .and_then(|file| file.duplicate_group.as_deref())
        })
        .collect();
    let mut keepers: HashMap<&str, &FileEntry> = HashMap::new();
    for file in &report.files {
        if let Some(group) = file.duplicate_group.as_deref() {
            if selected_groups.contains(group)
                && !keepers.contains_key(group)
                && !selected.contains(file.path.as_str())
                && validate_candidate(Path::new(&report.root), file).is_ok()
            {
                keepers.entry(group).or_insert(file);
            }
        }
    }
    let mut seen = HashSet::new();
    let mut last_update = Instant::now();
    for path in paths {
        if !seen.insert(path) {
            continue;
        }
        let operation = index
            .get(path.as_str())
            .ok_or_else(|| "This file was not in the current scan.".to_owned())
            .and_then(|file| {
                let safe_path = validate_candidate(Path::new(&report.root), file)?;
                if let Some(group) = file.duplicate_group.as_deref() {
                    let keeper = keepers
                        .get(group)
                        .ok_or("Keep at least one unchanged copy of each duplicate group.")?;
                    let mut stats = empty_stats();
                    if !bytes_equal(
                        Path::new(&report.root),
                        file,
                        keeper,
                        &AtomicBool::new(false),
                        &mut stats,
                        &mut |_| {},
                    )? {
                        return Err("Retained copy no longer matches. Compare files again.".into());
                    }
                }
                Ok(safe_path)
            })
            .and_then(|safe_path| move_to_trash(&safe_path));
        match operation {
            Ok(()) => result.removed.push(path.clone()),
            Err(error) => result.failed.push(TrashFailure {
                path: path.clone(),
                error,
            }),
        }
        if last_update.elapsed().as_millis() >= 120 || seen.len() == selected.len() {
            progress(Progress {
                files: seen.len(),
                bytes: 0,
                phase: "Processing cleanup files",
            });
            last_update = Instant::now();
        }
    }
    result.volume = volume_space(Path::new(&report.root));
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn scans_nested_files_and_categorizes_without_reading_contents() {
        let dir = tempdir().unwrap();
        fs::create_dir(dir.path().join("nested")).unwrap();
        fs::write(dir.path().join("nested/PHOTO.JPG"), b"picture").unwrap();
        fs::write(dir.path().join("notes.pdf"), b"document").unwrap();
        let report = scan(dir.path(), &AtomicBool::new(false), |_| {}).unwrap();
        assert_eq!(report.files.len(), 2);
        assert_eq!(report.files.iter().map(|file| file.size).sum::<u64>(), 15);
        assert!(report.files.iter().any(|file| file.category == "Images"));
        assert!(report.files.iter().any(|file| file.category == "Documents"));
    }

    #[test]
    fn identifies_content_duplicates_without_grouping_same_size_different_files() {
        let dir = tempdir().unwrap();
        fs::write(dir.path().join("one.bin"), b"same").unwrap();
        fs::write(dir.path().join("two.bin"), b"same").unwrap();
        fs::write(dir.path().join("three.bin"), b"diff").unwrap();
        fs::write(dir.path().join("empty.bin"), b"").unwrap();
        let report = scan(dir.path(), &AtomicBool::new(false), |_| {}).unwrap();
        let result = find_duplicates(&report, &AtomicBool::new(false), |_| {});
        assert_eq!(
            result
                .files
                .iter()
                .filter(|file| file.duplicate_group.is_some())
                .count(),
            2
        );
        assert!(result
            .files
            .iter()
            .find(|file| file.name == "three.bin")
            .unwrap()
            .duplicate_group
            .is_none());
        assert_eq!(result.stats.hashed_files, 2);
        assert_eq!(result.stats.verified_pairs, 1);
    }

    #[test]
    fn samples_reject_different_files_before_full_hashing() {
        let dir = tempdir().unwrap();
        fs::write(dir.path().join("one"), vec![1u8; 256 * 1024]).unwrap();
        fs::write(dir.path().join("two"), vec![2u8; 256 * 1024]).unwrap();
        let report = scan(dir.path(), &AtomicBool::new(false), |_| {}).unwrap();
        let result = find_duplicates(&report, &AtomicBool::new(false), |_| {});
        assert_eq!(result.stats.sampled_files, 2);
        assert_eq!(result.stats.hashed_files, 0);
        assert!(result.stats.bytes_read < 256 * 1024);
        assert!(result
            .files
            .iter()
            .all(|file| file.duplicate_group.is_none()));
    }

    #[test]
    fn matching_samples_do_not_prove_full_content_equality() {
        let dir = tempdir().unwrap();
        let a = vec![1u8; 256 * 1024];
        let mut b = a.clone();
        b[40 * 1024] = 2;
        fs::write(dir.path().join("one"), a).unwrap();
        fs::write(dir.path().join("two"), b).unwrap();
        let report = scan(dir.path(), &AtomicBool::new(false), |_| {}).unwrap();
        let result = find_duplicates(&report, &AtomicBool::new(false), |_| {});
        assert_eq!(result.stats.hashed_files, 2);
        assert_eq!(result.stats.verified_pairs, 0);
        assert!(result
            .files
            .iter()
            .all(|file| file.duplicate_group.is_none()));
    }

    #[test]
    fn duplicate_cleanup_requires_a_remaining_unchanged_copy() {
        let dir = tempdir().unwrap();
        fs::write(dir.path().join("one"), b"same content").unwrap();
        fs::write(dir.path().join("two"), b"same content").unwrap();
        let mut report = scan(dir.path(), &AtomicBool::new(false), |_| {}).unwrap();
        report.files = find_duplicates(&report, &AtomicBool::new(false), |_| {}).files;
        let all = report
            .files
            .iter()
            .map(|file| file.path.clone())
            .collect::<Vec<_>>();
        let result = trash_selected_with(&report, &all, |_| {
            panic!("Last-copy protection must run before trash")
        });
        assert_eq!(result.failed.len(), 2);
        let result = trash_selected_with(&report, &all[..1], |_| Ok(()));
        assert_eq!(result.removed.len(), 1);
        fs::write(&all[1], b"changed retained content").unwrap();
        let result = trash_selected_with(&report, &all[..1], |_| {
            panic!("Changed keeper must block removal")
        });
        assert_eq!(result.failed.len(), 1);
    }

    #[test]
    fn rejects_changed_and_unscanned_files_and_preserves_partial_failures() {
        let dir = tempdir().unwrap();
        for name in ["keep.txt", "changed.txt", "okay.txt"] {
            fs::write(dir.path().join(name), b"original").unwrap();
        }
        let report = scan(dir.path(), &AtomicBool::new(false), |_| {}).unwrap();
        fs::write(dir.path().join("changed.txt"), b"changed after the scan").unwrap();
        let paths: Vec<String> = ["changed.txt", "okay.txt", "missing.txt"]
            .iter()
            .map(|name| {
                Path::new(&report.root)
                    .join(name)
                    .to_string_lossy()
                    .into_owned()
            })
            .collect();
        let mut moved = Vec::new();
        let result = trash_selected_with(&report, &paths, |path| {
            moved.push(path.to_path_buf());
            Ok(())
        });
        assert_eq!(result.removed.len(), 1);
        assert_eq!(result.failed.len(), 2);
        assert!(moved[0].ends_with("okay.txt"));
        assert!(dir.path().join("keep.txt").exists());
    }

    #[test]
    fn honours_cancellation() {
        let dir = tempdir().unwrap();
        fs::write(dir.path().join("data.bin"), b"data").unwrap();
        let report = scan(dir.path(), &AtomicBool::new(true), |_| {}).unwrap();
        assert!(report.cancelled);
        assert!(report.files.is_empty());
    }

    #[test]
    fn trash_failure_never_falls_back_to_permanent_deletion() {
        let dir = tempdir().unwrap();
        fs::write(dir.path().join("important.txt"), b"important").unwrap();
        let report = scan(dir.path(), &AtomicBool::new(false), |_| {}).unwrap();
        let result = trash_selected_with(&report, &[report.files[0].path.clone()], |_| {
            Err("Trash unavailable".into())
        });
        assert!(result.removed.is_empty());
        assert_eq!(result.failed[0].error, "Trash unavailable");
        assert!(dir.path().join("important.txt").exists());
    }

    #[test]
    fn cleanup_reports_unique_outcomes_and_progress_after_a_failure() {
        let dir = tempdir().unwrap();
        fs::write(dir.path().join("locked.txt"), b"locked").unwrap();
        fs::write(dir.path().join("okay.txt"), b"okay").unwrap();
        let report = scan(dir.path(), &AtomicBool::new(false), |_| {}).unwrap();
        let locked = Path::new(&report.root)
            .join("locked.txt")
            .to_str()
            .unwrap()
            .to_owned();
        let okay = Path::new(&report.root)
            .join("okay.txt")
            .to_str()
            .unwrap()
            .to_owned();
        let mut processed = Vec::new();
        let mut calls = 0;
        let result = trash_selected_using(
            &report,
            &[locked.clone(), locked.clone(), okay.clone()],
            |path| {
                calls += 1;
                if path.ends_with("locked.txt") {
                    Err("File locked".into())
                } else {
                    Ok(())
                }
            },
            |progress| processed.push(progress.files),
        );
        assert_eq!(calls, 2);
        assert_eq!(result.removed, vec![okay]);
        assert_eq!(result.failed.len(), 1);
        assert_eq!(result.failed[0].path, locked);
        assert_eq!(processed.last(), Some(&2));
    }

    #[cfg(unix)]
    #[test]
    fn skips_symlinks_and_rejects_path_redirection() {
        use std::os::unix::fs::symlink;
        let dir = tempdir().unwrap();
        let outside = tempdir().unwrap();
        fs::write(outside.path().join("secret.txt"), b"secret").unwrap();
        symlink(outside.path(), dir.path().join("link")).unwrap();
        fs::create_dir(dir.path().join("nested")).unwrap();
        fs::write(dir.path().join("nested/data.txt"), b"original").unwrap();
        let report = scan(dir.path(), &AtomicBool::new(false), |_| {}).unwrap();
        assert_eq!(report.files.len(), 1);
        assert_eq!(report.skipped, 1);
        fs::rename(dir.path().join("nested"), dir.path().join("moved")).unwrap();
        symlink(dir.path().join("moved"), dir.path().join("nested")).unwrap();
        assert!(validate_candidate(Path::new(&report.root), &report.files[0]).is_err());
    }

    #[test]
    fn does_not_count_hard_links_as_reclaimable_duplicates() {
        let dir = tempdir().unwrap();
        fs::write(dir.path().join("original"), b"content").unwrap();
        fs::hard_link(dir.path().join("original"), dir.path().join("link")).unwrap();
        let report = scan(dir.path(), &AtomicBool::new(false), |_| {}).unwrap();
        let result = find_duplicates(&report, &AtomicBool::new(false), |_| {});
        assert!(result
            .files
            .iter()
            .all(|file| file.duplicate_group.is_none()));
    }
}
