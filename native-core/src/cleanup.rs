//! Explicit destructive actions. Recycling never falls back to these operations.
use super::{
    identity, modified_nanos, process_selected, trash_selected_with_progress, FileEntry, Progress,
    ScanReport, TrashResult,
};
use serde::{Deserialize, Serialize};
use std::{
    cell::RefCell,
    collections::{HashMap, HashSet},
    fs::{self, File, OpenOptions},
    io::{Read, Seek, SeekFrom, Write},
    path::Path,
    sync::atomic::{AtomicBool, Ordering},
    time::Instant,
};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum CleanupMode {
    Recycle,
    Delete,
    Wipe,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CleanupResult {
    pub mode: CleanupMode,
    #[serde(flatten)]
    pub outcome: TrashResult,
    pub rescan_required: bool,
}

pub fn confirmation_phrase(mode: CleanupMode, count: usize) -> String {
    let action = match mode {
        CleanupMode::Recycle => return String::new(),
        CleanupMode::Delete => "DELETE",
        CleanupMode::Wipe => "WIPE",
    };
    format!(
        "{action} {count} {}",
        if count == 1 { "FILE" } else { "FILES" }
    )
}

pub fn cleanup_selected_with_progress(
    report: &ScanReport,
    paths: &[String],
    mode: CleanupMode,
    confirmation: &str,
    allow_all_copies: bool,
    progress: impl FnMut(Progress) + Send,
) -> Result<CleanupResult, String> {
    cleanup_selected_with_cancel(
        report,
        paths,
        mode,
        confirmation,
        allow_all_copies,
        &AtomicBool::new(false),
        progress,
    )
}

pub fn cleanup_selected_with_cancel(
    report: &ScanReport,
    paths: &[String],
    mode: CleanupMode,
    confirmation: &str,
    allow_all_copies: bool,
    cancel: &AtomicBool,
    mut progress: impl FnMut(Progress) + Send,
) -> Result<CleanupResult, String> {
    if mode == CleanupMode::Recycle {
        if allow_all_copies {
            return Err("Keep one copy in each duplicate group when recycling.".into());
        }
        return Ok(CleanupResult {
            mode,
            outcome: trash_selected_with_progress(report, paths, progress),
            rescan_required: false,
        });
    }
    let unique: HashSet<&str> = paths.iter().map(String::as_str).collect();
    if unique.is_empty() {
        return Err("Select at least one scanned file.".into());
    }
    if confirmation != confirmation_phrase(mode, unique.len()) {
        return Err("The typed confirmation must match the action and current file count.".into());
    }
    let index: HashMap<&str, &FileEntry> = report
        .files
        .iter()
        .map(|file| (file.path.as_str(), file))
        .collect();
    if !unique.iter().all(|path| index.contains_key(path)) {
        return Err(
            "The selection contains files outside the current scan. Nothing was changed.".into(),
        );
    }
    let progress = RefCell::new(&mut progress);
    let processed = RefCell::new(0usize);
    let outcome = process_selected(
        report,
        paths,
        allow_all_copies,
        cancel,
        |path| {
            let entry = index
                .get(path.to_str().ok_or("Unsupported filename")?)
                .ok_or("File is outside the current scan")?;
            let result = match mode {
                CleanupMode::Delete => permanently_delete(path, entry),
                CleanupMode::Wipe => overwrite_and_delete(path, entry, cancel, |bytes| {
                    (progress.borrow_mut())(Progress {
                        files: *processed.borrow(),
                        bytes,
                        phase: "Overwriting and verifying file (best effort)",
                    });
                }),
                CleanupMode::Recycle => unreachable!(),
            };
            *processed.borrow_mut() += 1;
            result
        },
        |event| (progress.borrow_mut())(event),
    );
    // An interrupted overwrite may change data without removing the file. Never
    // permit an automatic retry from the old inventory after any wipe failure.
    let rescan_required = mode == CleanupMode::Wipe && !outcome.failed.is_empty();
    Ok(CleanupResult {
        mode,
        outcome,
        rescan_required,
    })
}

fn open_verified(path: &Path, entry: &FileEntry, writable: bool) -> Result<File, String> {
    let mut options = OpenOptions::new();
    options.read(true).write(writable);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC);
    }
    #[cfg(windows)]
    {
        use std::os::windows::fs::OpenOptionsExt;
        use windows::Win32::Storage::FileSystem::FILE_FLAG_OPEN_REPARSE_POINT;
        // Request DELETE on the same handle used for validation/overwriting.
        // Other writers and rename/delete operations are excluded while held.
        options
            .access_mode(if writable { 0xC0010000 } else { 0x00010080 })
            .share_mode(1)
            .custom_flags(FILE_FLAG_OPEN_REPARSE_POINT.0);
    }
    let file = options
        .open(path)
        .map_err(|error| format!("Cannot open the selected file: {error}"))?;
    let meta = file.metadata().map_err(|error| error.to_string())?;
    let (actual, links) = handle_identity(&file)?;
    if !meta.is_file()
        || actual != entry.identity
        || meta.len() != entry.size
        || modified_nanos(&meta) != entry.modified_nanos
    {
        return Err("The opened file differs from the scan. Rescan before cleanup.".into());
    }
    if writable && links != 1 {
        return Err("Overwrite is blocked for hard-linked files because other names share the same data. Use explicit permanent deletion to remove only selected names.".into());
    }
    #[cfg(windows)]
    if writable {
        use std::os::windows::fs::MetadataExt;
        // Reparse/cloud, compressed, sparse, and encrypted files cannot be given
        // the same overwrite semantics as an ordinary local file.
        if meta.file_attributes() & (0x400 | 0x800 | 0x200 | 0x4000) != 0 {
            return Err("Overwrite is unavailable for reparse/cloud, compressed, sparse, or encrypted files. No overwrite was started.".into());
        }
    }
    Ok(file)
}

#[cfg(unix)]
fn handle_identity(file: &File) -> Result<(String, u64), String> {
    use std::os::unix::fs::MetadataExt;
    let m = file.metadata().map_err(|error| error.to_string())?;
    Ok((
        format!("{:?}", file_id::FileId::new_inode(m.dev(), m.ino())),
        m.nlink(),
    ))
}

#[cfg(windows)]
fn handle_identity(file: &File) -> Result<(String, u64), String> {
    use std::{mem::size_of, os::windows::io::AsRawHandle};
    use windows::Win32::{Foundation::HANDLE, Storage::FileSystem::*};
    let handle = HANDLE(file.as_raw_handle());
    let mut low = BY_HANDLE_FILE_INFORMATION::default();
    let mut high = FILE_ID_INFO::default();
    // SAFETY: valid borrowed File handle and correctly sized initialized output
    // structs; Windows writes only within those buffers.
    unsafe {
        GetFileInformationByHandle(handle, &mut low).map_err(|error| error.to_string())?;
        let id = if GetFileInformationByHandleEx(
            handle,
            FileIdInfo,
            &mut high as *mut _ as _,
            size_of::<FILE_ID_INFO>() as u32,
        )
        .is_ok()
        {
            file_id::FileId::new_high_res(
                high.VolumeSerialNumber,
                u128::from_le_bytes(high.FileId.Identifier),
            )
        } else {
            file_id::FileId::new_low_res(
                low.dwVolumeSerialNumber,
                ((low.nFileIndexHigh as u64) << 32) | low.nFileIndexLow as u64,
            )
        };
        Ok((format!("{id:?}"), low.nNumberOfLinks as u64))
    }
}

fn verify_path(path: &Path, entry: &FileEntry) -> Result<(), String> {
    let m = fs::symlink_metadata(path).map_err(|error| error.to_string())?;
    if !m.is_file()
        || m.file_type().is_symlink()
        || fs::canonicalize(path).map_err(|error| error.to_string())? != path
        || identity(path, &m) != entry.identity
    {
        return Err("The file path changed during cleanup. No further action was taken.".into());
    }
    Ok(())
}

fn permanently_delete(path: &Path, entry: &FileEntry) -> Result<(), String> {
    let file = open_verified(path, entry, false)?;
    unlink_verified(file, path, entry)
}

fn unlink_verified(file: File, path: &Path, entry: &FileEntry) -> Result<(), String> {
    verify_path(path, entry)?;
    #[cfg(unix)]
    {
        fs::remove_file(path).map_err(|error| error.to_string())?;
        drop(file);
    }
    #[cfg(windows)]
    {
        use std::{mem::size_of, os::windows::io::AsRawHandle};
        use windows::Win32::{Foundation::HANDLE, Storage::FileSystem::*};
        let info = FILE_DISPOSITION_INFO {
            DeleteFile: true.into(),
        };
        // Delete the validated file object, never a re-resolved path.
        unsafe {
            SetFileInformationByHandle(
                HANDLE(file.as_raw_handle()),
                FileDispositionInfo,
                &info as *const _ as _,
                size_of::<FILE_DISPOSITION_INFO>() as u32,
            )
        }
        .map_err(|error| error.to_string())?;
        drop(file);
    }
    match fs::symlink_metadata(path) {
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        _ => Err("Removal is pending or the path changed. Rescan before retrying.".into()),
    }
}

fn overwrite_and_delete(
    path: &Path,
    entry: &FileEntry,
    cancel: &AtomicBool,
    mut progress: impl FnMut(u64),
) -> Result<(), String> {
    let mut file = open_verified(path, entry, true)?;
    let mut written = 0;
    let mut last = Instant::now();
    let outcome = (|| {
        overwrite(&mut file, entry.size, cancel, |count| {
            written = count;
            if last.elapsed().as_millis() >= 120 || count == entry.size {
                progress(count);
                last = Instant::now();
            }
        })?;
        check_cancel(cancel)?;
        if handle_identity(&file)?.1 != 1
            || file.metadata().map_err(|e| e.to_string())?.len() != entry.size
        {
            return Err("File size or hard-link count changed during overwrite".into());
        }
        unlink_verified(file, path, entry)
    })();
    outcome.map_err(|error: String| format!("Overwrite did not complete removal ({written} of {} bytes written). The file may already be partially or fully overwritten. Rescan before further cleanup. {error}", entry.size))
}

/// One logical zero pass, sync, then full read-back verification. This makes no
/// physical-media sanitization claim for SSD/flash, CoW, snapshots, or backups.
fn overwrite(
    file: &mut File,
    length: u64,
    cancel: &AtomicBool,
    mut progress: impl FnMut(u64),
) -> Result<(), String> {
    let zeroes = vec![0u8; 1024 * 1024];
    let mut written = 0u64;
    while written < length {
        check_cancel(cancel)?;
        let amount = (length - written).min(zeroes.len() as u64) as usize;
        match file.write(&zeroes[..amount]) {
            Ok(0) => return Err("Writing made no progress".into()),
            Ok(count) => {
                written += count as u64;
                progress(written);
            }
            Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(error) => return Err(error.to_string()),
        }
    }
    check_cancel(cancel)?;
    file.sync_all().map_err(|error| error.to_string())?;
    file.seek(SeekFrom::Start(0))
        .map_err(|error| error.to_string())?;
    let mut buffer = vec![0u8; zeroes.len()];
    let mut remaining = length;
    while remaining > 0 {
        check_cancel(cancel)?;
        let amount = remaining.min(buffer.len() as u64) as usize;
        file.read_exact(&mut buffer[..amount])
            .map_err(|error| error.to_string())?;
        if buffer[..amount].iter().any(|byte| *byte != 0) {
            return Err("Read-back verification failed".into());
        }
        remaining -= amount as u64;
    }
    Ok(())
}

fn check_cancel(cancel: &AtomicBool) -> Result<(), String> {
    if cancel.load(Ordering::Relaxed) {
        Err("Cleanup stopped. Overwritten bytes cannot be restored by Sift.".into())
    } else {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicBool;
    use tempfile::tempdir;
    fn scan(path: &Path) -> ScanReport {
        super::super::scan(path, &AtomicBool::new(false), |_| {}).unwrap()
    }
    #[test]
    fn destructive_actions_require_exact_confirmation_before_any_change() {
        let dir = tempdir().unwrap();
        fs::write(dir.path().join("keep"), b"important").unwrap();
        let report = scan(dir.path());
        let paths = vec![report.files[0].path.clone()];
        for mode in [CleanupMode::Delete, CleanupMode::Wipe] {
            assert!(cleanup_selected_with_progress(
                &report,
                &paths,
                mode,
                "DELETE 2 FILES",
                false,
                |_| {}
            )
            .is_err());
            assert_eq!(fs::read(&paths[0]).unwrap(), b"important");
        }
    }
    #[test]
    fn permanent_delete_and_overwrite_remove_only_reviewed_files() {
        for mode in [CleanupMode::Delete, CleanupMode::Wipe] {
            let dir = tempdir().unwrap();
            fs::write(
                dir.path().join("selected"),
                vec![0x5au8; 2 * 1024 * 1024 + 13],
            )
            .unwrap();
            fs::write(dir.path().join("keep"), b"keep").unwrap();
            let report = scan(dir.path());
            let paths = vec![report
                .files
                .iter()
                .find(|f| f.name == "selected")
                .unwrap()
                .path
                .clone()];
            let result = cleanup_selected_with_progress(
                &report,
                &paths,
                mode,
                &confirmation_phrase(mode, 1),
                false,
                |_| {},
            )
            .unwrap();
            assert_eq!(result.outcome.removed, paths);
            assert!(
                result.outcome.failed.is_empty(),
                "{:?}",
                result
                    .outcome
                    .failed
                    .iter()
                    .map(|f| &f.error)
                    .collect::<Vec<_>>()
            );
            assert!(!Path::new(&paths[0]).exists());
            assert_eq!(fs::read(dir.path().join("keep")).unwrap(), b"keep");
        }
    }
    #[test]
    fn overwrite_readback_preserves_length_and_covers_partial_final_block() {
        let mut file = tempfile::tempfile().unwrap();
        file.write_all(&vec![0xaau8; 1024 * 1024 + 31]).unwrap();
        file.rewind().unwrap();
        overwrite(&mut file, 1024 * 1024 + 31, &AtomicBool::new(false), |_| {}).unwrap();
        file.rewind().unwrap();
        let mut bytes = Vec::new();
        file.read_to_end(&mut bytes).unwrap();
        assert_eq!(bytes.len(), 1024 * 1024 + 31);
        assert!(bytes.iter().all(|b| *b == 0));
    }
    #[test]
    fn cancelled_overwrite_keeps_file_and_requires_new_inventory() {
        let dir = tempdir().unwrap();
        let original = vec![0x5au8; 2 * 1024 * 1024 + 13];
        fs::write(dir.path().join("selected"), &original).unwrap();
        let report = scan(dir.path());
        let cancel = AtomicBool::new(false);
        let paths = vec![report.files[0].path.clone()];
        let result = cleanup_selected_with_cancel(
            &report,
            &paths,
            CleanupMode::Wipe,
            "WIPE 1 FILE",
            false,
            &cancel,
            |event| {
                if event.bytes > 0 {
                    cancel.store(true, Ordering::Relaxed);
                }
            },
        )
        .unwrap();
        // The final write emits progress even for a fast, sub-120ms file.
        assert!(result.outcome.removed.is_empty());
        assert!(result.rescan_required);
        assert!(result.outcome.failed[0].error.contains("bytes written"));
        assert!(Path::new(&paths[0]).exists());
        assert_eq!(
            fs::metadata(&paths[0]).unwrap().len(),
            original.len() as u64
        );
    }

    #[test]
    fn cancellation_is_checked_between_overwrite_blocks() {
        let mut file = tempfile::tempfile().unwrap();
        file.write_all(&vec![0x5au8; 2 * 1024 * 1024]).unwrap();
        file.rewind().unwrap();
        let cancel = AtomicBool::new(false);
        let mut written = 0;
        let error = overwrite(&mut file, 2 * 1024 * 1024, &cancel, |count| {
            written = count;
            cancel.store(true, Ordering::Relaxed);
        })
        .unwrap_err();
        assert!(error.contains("stopped"));
        assert_eq!(written, 1024 * 1024);
        file.rewind().unwrap();
        let mut bytes = Vec::new();
        file.read_to_end(&mut bytes).unwrap();
        assert!(bytes[..1024 * 1024].iter().all(|b| *b == 0));
        assert!(bytes[1024 * 1024..].iter().all(|b| *b == 0x5a));
    }

    #[test]
    fn unscanned_paths_abort_entire_irreversible_request_and_counts_are_unique() {
        let dir = tempdir().unwrap();
        fs::write(dir.path().join("selected"), b"keep").unwrap();
        let report = scan(dir.path());
        let path = report.files[0].path.clone();
        let paths = vec![
            path.clone(),
            dir.path().join("outside").to_string_lossy().into_owned(),
        ];
        let result = cleanup_selected_with_progress(
            &report,
            &paths,
            CleanupMode::Delete,
            "DELETE 2 FILES",
            false,
            |_| {},
        );
        assert!(result.is_err());
        assert_eq!(fs::read(&path).unwrap(), b"keep");
        let paths = vec![path.clone(), path];
        assert!(cleanup_selected_with_progress(
            &report,
            &paths,
            CleanupMode::Delete,
            "DELETE 2 FILES",
            false,
            |_| {}
        )
        .is_err());
        let result = cleanup_selected_with_progress(
            &report,
            &paths,
            CleanupMode::Delete,
            "DELETE 1 FILE",
            false,
            |_| {},
        )
        .unwrap();
        assert_eq!(result.outcome.removed.len(), 1);
    }

    #[test]
    fn deleting_every_duplicate_requires_explicit_irreversible_override() {
        let dir = tempdir().unwrap();
        for name in ["one", "two"] {
            fs::write(dir.path().join(name), b"identical").unwrap();
        }
        let mut report = scan(dir.path());
        report.files =
            super::super::find_duplicates(&report, &AtomicBool::new(false), |_| {}).files;
        let paths: Vec<String> = report.files.iter().map(|f| f.path.clone()).collect();
        let blocked = cleanup_selected_with_progress(
            &report,
            &paths,
            CleanupMode::Delete,
            "DELETE 2 FILES",
            false,
            |_| {},
        )
        .unwrap();
        assert_eq!(blocked.outcome.failed.len(), 2);
        assert!(cleanup_selected_with_progress(
            &report,
            &paths,
            CleanupMode::Recycle,
            "",
            true,
            |_| {}
        )
        .is_err());
        assert!(paths.iter().all(|p| Path::new(p).exists()));
        let result = cleanup_selected_with_progress(
            &report,
            &paths,
            CleanupMode::Delete,
            "DELETE 2 FILES",
            true,
            |_| {},
        )
        .unwrap();
        assert_eq!(result.outcome.removed.len(), 2);
    }

    #[test]
    fn changed_files_and_hard_linked_overwrites_are_refused_without_data_loss() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("original");
        fs::write(&path, b"keep").unwrap();
        fs::hard_link(&path, dir.path().join("other-name")).unwrap();
        let report = scan(dir.path());
        let paths = vec![report.files[0].path.clone()];
        let result = cleanup_selected_with_progress(
            &report,
            &paths,
            CleanupMode::Wipe,
            "WIPE 1 FILE",
            false,
            |_| {},
        )
        .unwrap();
        assert!(result.outcome.removed.is_empty());
        assert!(result.outcome.failed[0].error.contains("hard-linked"));
        assert_eq!(fs::read(&path).unwrap(), b"keep");
        fs::write(&paths[0], b"changed after scan").unwrap();
        let result = cleanup_selected_with_progress(
            &report,
            &paths,
            CleanupMode::Delete,
            "DELETE 1 FILE",
            false,
            |_| {},
        )
        .unwrap();
        assert!(result.outcome.removed.is_empty());
    }
}
