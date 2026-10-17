use sift_core::{CleanupMode, CleanupResult, DuplicateResult, ScanReport, TrashResult};
use std::{
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex, RwLock,
    },
};
use tauri::{Emitter, Manager, State};
use tauri_plugin_dialog::DialogExt;

#[derive(Default)]
struct Session {
    chosen: Mutex<Option<PathBuf>>,
    report: Mutex<Option<ScanReport>>,
    recovery: Mutex<Option<sift_core::recovery::RecoverySession>>,
    busy: AtomicBool,
    cancel: AtomicBool,
    needs_rescan: AtomicBool,
    preview_jobs: RwLock<()>,
}

struct BusyGuard(Arc<Session>);
impl Drop for BusyGuard {
    fn drop(&mut self) {
        self.0.busy.store(false, Ordering::Release);
    }
}

fn lock_work(session: &Arc<Session>) -> Result<BusyGuard, String> {
    session
        .busy
        .compare_exchange(false, true, Ordering::AcqRel, Ordering::Relaxed)
        .map_err(|_| "Another scan or cleanup is already running.")?;
    session.cancel.store(false, Ordering::Relaxed);
    Ok(BusyGuard(Arc::clone(session)))
}

#[tauri::command]
async fn choose_folder(
    app: tauri::AppHandle,
    session: State<'_, Arc<Session>>,
) -> Result<Option<String>, String> {
    if session.busy.load(Ordering::Acquire) {
        return Err("Wait for the current operation to finish.".into());
    }
    let picked = tauri::async_runtime::spawn_blocking(move || {
        app.dialog()
            .file()
            .set_title("Choose a folder or drive to explore")
            .blocking_pick_folder()
    })
    .await
    .map_err(|error| error.to_string())?;
    let Some(picked) = picked else {
        return Ok(None);
    };
    let path = picked.into_path().map_err(|error| error.to_string())?;
    let path = std::fs::canonicalize(path).map_err(|error| error.to_string())?;
    let display = path
        .to_str()
        .ok_or("This folder's name is not supported Unicode.")?
        .to_owned();
    *session.chosen.lock().map_err(|error| error.to_string())? = Some(path);
    Ok(Some(display))
}

#[tauri::command]
async fn scan_directory(
    app: tauri::AppHandle,
    session: State<'_, Arc<Session>>,
) -> Result<ScanReport, String> {
    let session = Arc::clone(session.inner());
    let guard = lock_work(&session)?;
    let root = session
        .chosen
        .lock()
        .map_err(|error| error.to_string())?
        .clone()
        .ok_or("Choose a folder first.")?;
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = guard;
        let report = sift_core::scan(&root, &session.cancel, |progress| {
            let _ = app.emit("scan-progress", progress);
        })?;
        *session.report.lock().map_err(|error| error.to_string())? = Some(report.clone());
        session.needs_rescan.store(false, Ordering::Release);
        Ok(report)
    })
    .await
    .map_err(|error| error.to_string())?
}

#[tauri::command]
fn cancel_scan(session: State<'_, Arc<Session>>) {
    session.cancel.store(true, Ordering::Relaxed);
}

#[tauri::command]
async fn find_duplicates(
    app: tauri::AppHandle,
    session: State<'_, Arc<Session>>,
) -> Result<DuplicateResult, String> {
    let session = Arc::clone(session.inner());
    let guard = lock_work(&session)?;
    if session.needs_rescan.load(Ordering::Acquire) {
        return Err(
            "Rescan after an incomplete overwrite before comparing or cleaning up files.".into(),
        );
    }
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = guard;
        // Move the existing report out while busy to avoid duplicating a large scan.
        let mut report = session
            .report
            .lock()
            .map_err(|error| error.to_string())?
            .take()
            .ok_or("Scan a folder first.")?;
        let result = sift_core::find_duplicates(&report, &session.cancel, |progress| {
            let _ = app.emit("scan-progress", progress);
        });
        report.files = result.files.clone();
        *session.report.lock().map_err(|error| error.to_string())? = Some(report);
        Ok(result)
    })
    .await
    .map_err(|error| error.to_string())?
}

#[tauri::command]
async fn trash_files(
    app: tauri::AppHandle,
    paths: Vec<String>,
    session: State<'_, Arc<Session>>,
) -> Result<TrashResult, String> {
    cleanup_files(
        app,
        paths,
        CleanupMode::Recycle,
        String::new(),
        false,
        session,
    )
    .await
    .map(|result| result.outcome)
}

#[tauri::command]
async fn cleanup_files(
    app: tauri::AppHandle,
    paths: Vec<String>,
    mode: CleanupMode,
    confirmation: String,
    allow_all_copies: bool,
    session: State<'_, Arc<Session>>,
) -> Result<CleanupResult, String> {
    let session = Arc::clone(session.inner());
    let guard = lock_work(&session)?;
    if session.needs_rescan.load(Ordering::Acquire) {
        return Err(
            "A previous overwrite was not fully completed. Rescan before any further cleanup."
                .into(),
        );
    }
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = guard;
        // Retire our preview read handles before any rename/delete/overwrite.
        // The busy flag prevents new preview readers while this worker waits.
        let _preview_pause = session.preview_jobs.write().map_err(|e| e.to_string())?;
        let mut locked = session.report.lock().map_err(|error| error.to_string())?;
        let report = locked.as_mut().ok_or("Scan a folder first.")?;
        let result = sift_core::cleanup_selected_with_cancel(
            report,
            &paths,
            mode,
            &confirmation,
            allow_all_copies,
            &session.cancel,
            |progress| {
                let _ = app.emit("scan-progress", progress);
            },
        )?;
        if result.rescan_required {
            session.needs_rescan.store(true, Ordering::Release);
        }
        report.volume = result.outcome.volume.clone();
        let removed: std::collections::HashSet<&String> = result.outcome.removed.iter().collect();
        report.files.retain(|file| !removed.contains(&file.path));
        let mut group_counts = std::collections::HashMap::new();
        for file in &report.files {
            if let Some(group) = &file.duplicate_group {
                *group_counts.entry(group.clone()).or_insert(0usize) += 1;
            }
        }
        for file in &mut report.files {
            if file
                .duplicate_group
                .as_ref()
                .is_some_and(|group| group_counts.get(group).copied().unwrap_or(0) < 2)
            {
                file.duplicate_group = None;
            }
        }
        Ok(result)
    })
    .await
    .map_err(|error| error.to_string())?
}

#[tauri::command]
async fn file_preview(
    path: String,
    session: State<'_, Arc<Session>>,
) -> Result<Option<sift_core::FilePreview>, String> {
    static SLOTS: tokio::sync::Semaphore = tokio::sync::Semaphore::const_new(2);
    let permit = SLOTS.acquire().await.map_err(|e| e.to_string())?;
    let session = Arc::clone(session.inner());
    let result = tauri::async_runtime::spawn_blocking(move || {
        // Filesystem/locking/decoding stays off the IPC executor. New preview
        // requests yield to scans and cleanup; existing readers finish first.
        let _preview = session.preview_jobs.try_read().map_err(|e| e.to_string())?;
        if session.busy.load(Ordering::Acquire) {
            return Err("Previews paused during file operations.".into());
        }
        let (root, entry) = {
            let locked = session
                .report
                .try_lock()
                .map_err(|_| "File inventory is busy.")?;
            let report = locked.as_ref().ok_or("Scan a folder first.")?;
            let entry = report
                .files
                .iter()
                .find(|entry| entry.path == path)
                .cloned()
                .ok_or("File is no longer in this scan.")?;
            (PathBuf::from(&report.root), entry)
        };
        sift_core::file_preview(&root, &entry)
    })
    .await
    .map_err(|e| e.to_string())?;
    drop(permit);
    result
}

#[tauri::command]
async fn export_report(
    app: tauri::AppHandle,
    session: State<'_, Arc<Session>>,
) -> Result<Option<String>, String> {
    let session = Arc::clone(session.inner());
    tauri::async_runtime::spawn_blocking(move || {
        let contents = {
            let locked = session.report.lock().map_err(|error| error.to_string())?;
            let report = locked
                .as_ref()
                .ok_or("Scan a folder before exporting an inventory.")?;
            serde_json::to_vec_pretty(report).map_err(|error| error.to_string())?
        };
        let Some(destination) = app
            .dialog()
            .file()
            .set_title("Export scan inventory")
            .set_file_name("sift-scan-report.json")
            .add_filter("JSON inventory", &["json"])
            .blocking_save_file()
        else {
            return Ok(None);
        };
        let path = destination.into_path().map_err(|error| error.to_string())?;
        std::fs::write(&path, contents).map_err(|error| error.to_string())?;
        Ok(Some(path.display().to_string()))
    })
    .await
    .map_err(|error| error.to_string())?
}

#[tauri::command]
async fn scan_recovery_image(
    app: tauri::AppHandle,
    session: State<'_, Arc<Session>>,
) -> Result<Option<sift_core::recovery::RecoveryReport>, String> {
    let session = Arc::clone(session.inner());
    let guard = lock_work(&session)?;
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = guard;
        let Some(picked) = app
            .dialog()
            .file()
            .set_title("Choose a local raw disk image (.img / .dd / .raw)")
            .blocking_pick_file()
        else {
            return Ok(None);
        };
        let path = picked.into_path().map_err(|e| e.to_string())?;
        // Retain a read-only evidence handle and allow only IDs from this scan.
        let mut slot = session.recovery.lock().map_err(|e| e.to_string())?;
        *slot = None;
        let mut recovery = sift_core::recovery::RecoverySession::open(&path)?;
        let report = recovery.scan(&session.cancel, |progress| {
            let _ = app.emit("recovery-progress", progress);
        })?;
        *slot = Some(recovery);
        Ok(Some(report))
    })
    .await
    .map_err(|e| e.to_string())?
}
#[tauri::command]
async fn close_recovery_image(session: State<'_, Arc<Session>>) -> Result<(), String> {
    let session = Arc::clone(session.inner());
    let guard = lock_work(&session)?;
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = guard;
        *session.recovery.lock().map_err(|e| e.to_string())? = None;
        Ok(())
    })
    .await
    .map_err(|e| e.to_string())?
}
#[tauri::command]
async fn recovery_preview(
    id: String,
    session: State<'_, Arc<Session>>,
) -> Result<Option<sift_core::FilePreview>, String> {
    static SLOTS: tokio::sync::Semaphore = tokio::sync::Semaphore::const_new(2);
    let permit = SLOTS.acquire().await.map_err(|e| e.to_string())?;
    let session = Arc::clone(session.inner());
    let result = tauri::async_runtime::spawn_blocking(move || {
        if session.busy.load(Ordering::Acquire) {
            return Err("Previews paused during recovery.".into());
        }
        session
            .recovery
            .try_lock()
            .map_err(|_| "Recovery image is busy.")?
            .as_mut()
            .ok_or("Scan an image first.")?
            .preview(&id)
    })
    .await
    .map_err(|e| e.to_string())?;
    drop(permit);
    result
}
#[tauri::command]
async fn export_recovery_files(
    app: tauri::AppHandle,
    ids: Vec<String>,
    session: State<'_, Arc<Session>>,
) -> Result<Option<sift_core::recovery::RecoveryExport>, String> {
    let session = Arc::clone(session.inner());
    let guard = lock_work(&session)?;
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = guard;
        let Some(picked) = app
            .dialog()
            .file()
            .set_title("Save recovered copies — prefer another physical drive")
            .blocking_pick_folder()
        else {
            return Ok(None);
        };
        let destination = picked.into_path().map_err(|e| e.to_string())?;
        let result = session
            .recovery
            .lock()
            .map_err(|e| e.to_string())?
            .as_mut()
            .ok_or("Scan an image first.")?
            .export(&destination, &ids, &session.cancel, |progress| {
                let _ = app.emit("recovery-progress", progress);
            })?;
        Ok(Some(result))
    })
    .await
    .map_err(|e| e.to_string())?
}
#[tauri::command]
async fn open_system_trash() -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(|| {
        #[cfg(windows)]
        let status = std::process::Command::new("explorer.exe")
            .arg("shell:RecycleBinFolder")
            .spawn();
        #[cfg(target_os = "macos")]
        let status = std::process::Command::new("open")
            .arg(
                std::env::var_os("HOME")
                    .map(PathBuf::from)
                    .ok_or("Home directory unavailable.")?
                    .join(".Trash"),
            )
            .spawn();
        #[cfg(target_os = "linux")]
        let status = std::process::Command::new("gio")
            .args(["open", "trash:///"])
            .spawn();
        status.map(|_| ()).map_err(|e| {
            format!("Could not open system trash: {e}. Open it from your system file manager.")
        })
    })
    .await
    .map_err(|e| e.to_string())?
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            app.manage(Arc::new(Session::default()));
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            choose_folder,
            scan_directory,
            cancel_scan,
            find_duplicates,
            trash_files,
            cleanup_files,
            export_report,
            file_preview,
            scan_recovery_image,
            recovery_preview,
            close_recovery_image,
            export_recovery_files,
            open_system_trash
        ])
        .run(tauri::generate_context!())
        .expect("Could not start Sift");
}
