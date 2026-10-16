use sift_core::{DuplicateResult, ScanReport, TrashResult};
use std::{
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
};
use tauri::{Emitter, Manager, State};
use tauri_plugin_dialog::DialogExt;

#[derive(Default)]
struct Session {
    chosen: Mutex<Option<PathBuf>>,
    report: Mutex<Option<ScanReport>>,
    busy: AtomicBool,
    cancel: AtomicBool,
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
    paths: Vec<String>,
    session: State<'_, Arc<Session>>,
) -> Result<TrashResult, String> {
    let session = Arc::clone(session.inner());
    let guard = lock_work(&session)?;
    tauri::async_runtime::spawn_blocking(move || {
        let _guard = guard;
        let mut locked = session.report.lock().map_err(|error| error.to_string())?;
        let report = locked.as_mut().ok_or("Scan a folder first.")?;
        let result = sift_core::trash_selected(report, &paths);
        report.volume = result.volume.clone();
        let removed: std::collections::HashSet<&String> = result.removed.iter().collect();
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
            export_report
        ])
        .run(tauri::generate_context!())
        .expect("Could not start Sift");
}
