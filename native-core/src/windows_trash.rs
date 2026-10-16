//! Windows Shell recycling, isolated from async-runtime COM apartments.
//!
//! Each file has its own operation and progress sink. A skipped/aborted operation
//! must not be counted as a successful recycle or poison the following file.
use super::{trash_selected_using, Progress, ScanReport, TrashFailure, TrashResult};
use std::{
    fs,
    os::windows::ffi::OsStrExt,
    path::{Component, Path, PathBuf, Prefix},
    sync::{Arc, Mutex},
};
use windows::{
    core::{implement, Error, Ref, Result, HRESULT, PCWSTR},
    Win32::{
        Foundation::{E_ABORT, E_FAIL, E_NOTIMPL},
        System::Com::{
            CoCreateInstance, CoInitializeEx, CoUninitialize, CLSCTX_INPROC_SERVER,
            COINIT_APARTMENTTHREADED, COINIT_DISABLE_OLE1DDE,
        },
        UI::Shell::*,
    },
};

pub(super) fn trash_selected(
    report: &ScanReport,
    paths: &[String],
    progress: impl FnMut(Progress) + Send,
) -> TrashResult {
    // A fresh STA avoids RPC_E_CHANGED_MODE and stale COM state on shared workers.
    // Borrow the inventory rather than cloning hundreds of thousands of entries.
    let outcome = std::thread::scope(|scope| {
        std::thread::Builder::new()
            .name("sift-recycle".into())
            .spawn_scoped(scope, || {
                let apartment = Apartment::new()?;
                let result = trash_selected_using(report, paths, recycle, progress);
                drop(apartment);
                Ok::<_, String>(result)
            })
            .map_err(|error| format!("Could not start the Recycle Bin worker: {error}"))?
            .join()
            .map_err(|_| "The Recycle Bin worker stopped unexpectedly.".to_owned())?
    });
    outcome.unwrap_or_else(|error| TrashResult {
        removed: Vec::new(),
        failed: paths
            .iter()
            .collect::<std::collections::BTreeSet<_>>()
            .into_iter()
            .map(|path| TrashFailure {
                path: path.clone(),
                error: error.clone(),
            })
            .collect(),
        volume: super::volume_space(Path::new(&report.root)),
    })
}

struct Apartment;
impl Apartment {
    fn new() -> std::result::Result<Self, String> {
        // SAFETY: a newly created thread; every successful initialization has one
        // matching CoUninitialize, after all Shell interfaces have been dropped.
        unsafe { CoInitializeEx(None, COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE).ok() }
            .map_err(|error| describe(error.code()))?;
        Ok(Self)
    }
}
impl Drop for Apartment {
    fn drop(&mut self) {
        unsafe { CoUninitialize() };
    }
}

fn shell_path(path: &Path) -> std::result::Result<PathBuf, String> {
    let mut components = path.components();
    let drive = match components.next() {
        Some(Component::Prefix(prefix)) => match prefix.kind() {
            Prefix::VerbatimDisk(drive) | Prefix::Disk(drive) => drive,
            _ => return Err("This location does not provide a supported local Recycle Bin. No files were deleted.".into()),
        },
        _ => return Err("Recycle Bin requires an absolute local-drive path.".into()),
    };
    let mut plain = PathBuf::from(format!("{}:\\", drive as char));
    for component in components {
        match component {
            Component::RootDir => {}
            Component::Normal(name) => plain.push(name),
            _ => {
                return Err("The file path cannot be represented safely for Windows Shell.".into())
            }
        }
    }
    // Canonical paths use \\?\ prefixes, which Windows Shell does not accept.
    // Do not blindly strip them: ordinary paths can resolve trailing dots/spaces
    // or reserved names differently. Reject any change in what the name means.
    if fs::canonicalize(&plain).map_err(|error| error.to_string())? != path {
        return Err(
            "Windows Shell resolves this path differently. The file was left in place.".into(),
        );
    }
    Ok(plain)
}

#[derive(Default)]
struct Outcome {
    status: Option<HRESULT>,
    recycled_item: bool,
}

// windows-rs makes this COM sink agile; callbacks may cross apartment threads.
// Keep its shared state synchronized even though operations start on our STA.
#[implement(IFileOperationProgressSink)]
struct Sink {
    outcome: Arc<Mutex<Outcome>>,
}

#[allow(non_snake_case)]
impl IFileOperationProgressSink_Impl for Sink_Impl {
    fn StartOperations(&self) -> Result<()> {
        Ok(())
    }
    fn FinishOperations(&self, _: HRESULT) -> Result<()> {
        Ok(())
    }
    fn PreDeleteItem(&self, flags: u32, _: Ref<IShellItem>) -> Result<()> {
        if flags & TSF_DELETE_RECYCLE_IF_POSSIBLE.0 as u32 == 0 {
            self.outcome
                .lock()
                .map_err(|_| Error::from_hresult(E_FAIL))?
                .status = Some(COPYENGINE_E_RECYCLE_FORCE_NUKE);
            return Err(Error::from_hresult(COPYENGINE_E_RECYCLE_FORCE_NUKE));
        }
        Ok(())
    }
    fn PostDeleteItem(
        &self,
        _: u32,
        _: Ref<IShellItem>,
        status: HRESULT,
        recycled: Ref<IShellItem>,
    ) -> Result<()> {
        let mut outcome = self
            .outcome
            .lock()
            .map_err(|_| Error::from_hresult(E_FAIL))?;
        outcome.status = Some(status);
        outcome.recycled_item = recycled.is_some();
        Ok(())
    }
    fn UpdateProgress(&self, _: u32, _: u32) -> Result<()> {
        Ok(())
    }
    fn ResetTimer(&self) -> Result<()> {
        Ok(())
    }
    fn PauseTimer(&self) -> Result<()> {
        Ok(())
    }
    fn ResumeTimer(&self) -> Result<()> {
        Ok(())
    }
    // This adapter only permits deletion-to-Recycle-Bin; never rename/copy/move.
    fn PreRenameItem(&self, _: u32, _: Ref<IShellItem>, _: &PCWSTR) -> Result<()> {
        Err(E_NOTIMPL.into())
    }
    fn PostRenameItem(
        &self,
        _: u32,
        _: Ref<IShellItem>,
        _: &PCWSTR,
        _: HRESULT,
        _: Ref<IShellItem>,
    ) -> Result<()> {
        Ok(())
    }
    fn PreMoveItem(
        &self,
        _: u32,
        _: Ref<IShellItem>,
        _: Ref<IShellItem>,
        _: &PCWSTR,
    ) -> Result<()> {
        Err(E_NOTIMPL.into())
    }
    fn PostMoveItem(
        &self,
        _: u32,
        _: Ref<IShellItem>,
        _: Ref<IShellItem>,
        _: &PCWSTR,
        _: HRESULT,
        _: Ref<IShellItem>,
    ) -> Result<()> {
        Ok(())
    }
    fn PreCopyItem(
        &self,
        _: u32,
        _: Ref<IShellItem>,
        _: Ref<IShellItem>,
        _: &PCWSTR,
    ) -> Result<()> {
        Err(E_NOTIMPL.into())
    }
    fn PostCopyItem(
        &self,
        _: u32,
        _: Ref<IShellItem>,
        _: Ref<IShellItem>,
        _: &PCWSTR,
        _: HRESULT,
        _: Ref<IShellItem>,
    ) -> Result<()> {
        Ok(())
    }
    fn PreNewItem(&self, _: u32, _: Ref<IShellItem>, _: &PCWSTR) -> Result<()> {
        Err(E_NOTIMPL.into())
    }
    fn PostNewItem(
        &self,
        _: u32,
        _: Ref<IShellItem>,
        _: &PCWSTR,
        _: &PCWSTR,
        _: u32,
        _: HRESULT,
        _: Ref<IShellItem>,
    ) -> Result<()> {
        Ok(())
    }
}

fn recycle(path: &Path) -> std::result::Result<(), String> {
    let plain = shell_path(path)?;
    let wide: Vec<u16> = plain.as_os_str().encode_wide().chain(Some(0)).collect();
    let outcome = Arc::new(Mutex::new(Outcome::default()));
    let sink: IFileOperationProgressSink = Sink {
        outcome: Arc::clone(&outcome),
    }
    .into();
    // SAFETY: Shell calls stay on this initialized STA. All COM objects and the
    // UTF-16 path remain alive until synchronous PerformOperations completes.
    unsafe {
        let operation: IFileOperation =
            CoCreateInstance(&FileOperation, None, CLSCTX_INPROC_SERVER)
                .map_err(|error| describe(error.code()))?;
        operation
            .SetOperationFlags(
                FOF_NO_UI
                    | FOF_ALLOWUNDO
                    | FOFX_RECYCLEONDELETE
                    | FOFX_ADDUNDORECORD
                    | FOF_WANTNUKEWARNING
                    | FOF_NO_CONNECTED_ELEMENTS,
            )
            .map_err(|error| describe(error.code()))?;
        let item: IShellItem = SHCreateItemFromParsingName(PCWSTR(wide.as_ptr()), None)
            .map_err(|error| describe(error.code()))?;
        operation
            .DeleteItem(&item, &sink)
            .map_err(|error| describe(error.code()))?;
        let performed = operation.PerformOperations();
        let state = outcome.lock().map_err(|_| {
            "Could not read the Windows recycle result. Rescan before retrying.".to_owned()
        })?;
        // Windows can report COPYENGINE_S_DONT_PROCESS_CHILDREN after recycling
        // a regular file. It is a completion code, unlike S_USER_IGNORED. Keep
        // requiring the destination item and disappearance of the source below.
        let completed = matches!(
            state.status,
            Some(HRESULT(0) | COPYENGINE_S_DONT_PROCESS_CHILDREN)
        );
        if let Some(status) = state.status {
            if !completed {
                return Err(describe(status));
            }
        }
        performed.map_err(|error| describe(error.code()))?;
        // S_OK alone is insufficient: skipped operations can be "successful".
        // Require the per-file callback and a destination item in the bin.
        if !completed || !state.recycled_item {
            return Err("Windows did not confirm recycling this file. Check whether it is open, protected, or exceeds the Recycle Bin’s size limit. No permanent-delete fallback was used.".into());
        }
        match fs::symlink_metadata(path) {
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            _ => Err("Windows reported recycling, but the original path still exists or cannot be verified. Rescan before retrying.".into()),
        }
    }
}

fn describe(code: HRESULT) -> String {
    let hint = match code {
        COPYENGINE_E_SHARING_VIOLATION_SRC | COPYENGINE_E_SHARING_VIOLATION_DEST => "The file is open in another app. Close it and try again.",
        COPYENGINE_E_ACCESS_DENIED_SRC | COPYENGINE_E_ACCESS_DENIED_DEST | COPYENGINE_E_ACCESSDENIED_READONLY => "Windows denied access. Check the file’s permissions or protection settings.",
        COPYENGINE_E_RECYCLE_SIZE_TOO_BIG => "This file exceeds the Recycle Bin’s size limit. Increase the limit in Recycle Bin Properties if you want to recycle it.",
        COPYENGINE_E_RECYCLE_FORCE_NUKE => "Windows could not recycle this file. Sift blocked permanent deletion. Check Recycle Bin Properties and its available capacity.",
        COPYENGINE_E_RECYCLE_BIN_NOT_FOUND => "A Recycle Bin is unavailable for this location. Check Recycle Bin Properties.",
        COPYENGINE_E_RECYCLE_PATH_TOO_LONG | COPYENGINE_E_PATH_TOO_DEEP_SRC => "Windows cannot recycle this path because it is too long.",
        COPYENGINE_E_CANCELLED | E_ABORT => "Windows cancelled this operation. The file was not confirmed in the Recycle Bin.",
        COPYENGINE_E_REQUIRES_ELEVATION => "This file requires elevated permissions. Sift does not elevate cleanup operations.",
        COPYENGINE_S_USER_IGNORED => "Windows skipped this file. Check permissions, open apps, and Recycle Bin capacity.",
        _ if code == HRESULT::from_win32(32) || code == HRESULT::from_win32(33) => "The file is open or locked by another app. Close it and try again.",
        _ if code == HRESULT::from_win32(5) => "Windows denied access. Check the file’s permissions or protection settings.",
        _ => "Windows could not confirm moving this file to the Recycle Bin.",
    };
    format!(
        "{hint} Windows code 0x{:08X}: {}",
        code.0 as u32,
        Error::from_hresult(code).message()
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{fs::OpenOptions, os::windows::fs::OpenOptionsExt, sync::atomic::AtomicBool};

    fn restore_fixtures(report: &ScanReport, expected_count: usize) {
        let _apartment = Apartment::new().unwrap();
        let items: Vec<_> = trash::os_limited::list()
            .unwrap()
            .into_iter()
            .filter(|item| {
                fs::canonicalize(&item.original_parent).ok().as_deref()
                    == Some(Path::new(&report.root))
            })
            .collect();
        assert_eq!(
            items.len(),
            expected_count,
            "Every reported success must be present in the Recycle Bin"
        );
        let wide =
            |path: &std::ffi::OsStr| -> Vec<u16> { path.encode_wide().chain(Some(0)).collect() };
        let parent = wide(shell_path(Path::new(&report.root)).unwrap().as_os_str());
        for item in items {
            // Shell display names can hide registered file extensions. Restore
            // with the original scanned filename, not a display-name-derived
            // path (the dependency's restore helper silently renames these).
            let original = report
                .files
                .iter()
                .find(|file| {
                    Some(file.name.as_str()) == item.name.to_str()
                        || Path::new(&file.name).file_stem() == Some(item.name.as_ref())
                })
                .expect("Only this test's unique fixture filenames may be restored");
            let source = wide(item.id.as_ref());
            let name = wide(original.name.as_ref());
            unsafe {
                let operation: IFileOperation =
                    CoCreateInstance(&FileOperation, None, CLSCTX_INPROC_SERVER).unwrap();
                operation
                    .SetOperationFlags(FOF_NO_UI | FOF_ALLOWUNDO)
                    .unwrap();
                let from: IShellItem =
                    SHCreateItemFromParsingName(PCWSTR(source.as_ptr()), None).unwrap();
                let to: IShellItem =
                    SHCreateItemFromParsingName(PCWSTR(parent.as_ptr()), None).unwrap();
                operation
                    .MoveItem(&from, &to, PCWSTR(name.as_ptr()), None)
                    .unwrap();
                operation.PerformOperations().unwrap();
                assert!(!operation.GetAnyOperationsAborted().unwrap().as_bool());
            }
            assert!(
                Path::new(&original.path).exists(),
                "Original fixture filename must be restored"
            );
        }
    }

    #[test]
    fn recycles_real_files_with_unicode_names_and_restores_only_test_fixtures() {
        let directory = tempfile::tempdir().unwrap();
        for name in ["notes.txt", "résumé 日本語.txt", "another file.dat"] {
            fs::write(
                directory.path().join(name),
                b"Sift recycle regression fixture",
            )
            .unwrap();
        }
        let report = super::super::scan(directory.path(), &AtomicBool::new(false), |_| {}).unwrap();
        let paths: Vec<_> = report.files.iter().map(|file| file.path.clone()).collect();
        let result = trash_selected(&report, &paths, |_| {});
        assert!(
            result.failed.is_empty(),
            "{:?}",
            result.failed.iter().map(|f| &f.error).collect::<Vec<_>>()
        );
        assert_eq!(result.removed.len(), 3);
        restore_fixtures(&report, 3);
        for path in paths {
            assert_eq!(fs::read(path).unwrap(), b"Sift recycle regression fixture");
        }
    }

    #[test]
    fn locked_file_reports_a_reason_and_does_not_block_the_next_file() {
        let directory = tempfile::tempdir().unwrap();
        let locked = directory.path().join("locked.txt");
        let available = directory.path().join("available.txt");
        fs::write(&locked, b"keep locked").unwrap();
        fs::write(&available, b"recycle available").unwrap();
        let report = super::super::scan(directory.path(), &AtomicBool::new(false), |_| {}).unwrap();
        let paths: Vec<_> = ["locked.txt", "available.txt"]
            .iter()
            .map(|name| {
                Path::new(&report.root)
                    .join(name)
                    .to_str()
                    .unwrap()
                    .to_owned()
            })
            .collect();
        let handle = OpenOptions::new()
            .read(true)
            .share_mode(1)
            .open(&locked)
            .unwrap();
        let result = trash_selected(&report, &paths, |_| {});
        assert_eq!(result.removed, paths[1..]);
        assert_eq!(result.failed.len(), 1);
        assert_eq!(result.failed[0].path, paths[0]);
        assert!(
            result.failed[0].error.contains("0x"),
            "{}",
            result.failed[0].error
        );
        assert!(locked.exists());
        drop(handle);
        restore_fixtures(&report, 1);
        assert_eq!(fs::read(available).unwrap(), b"recycle available");
    }

    #[test]
    fn permanent_delete_requests_are_blocked_by_the_callback() {
        let sink = Sink {
            outcome: Arc::new(Mutex::new(Outcome::default())),
        };
        let sink: IFileOperationProgressSink = sink.into();
        assert!(unsafe { sink.PreDeleteItem(0, None) }.is_err());
    }
}
