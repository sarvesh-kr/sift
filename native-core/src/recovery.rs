//! Read-only carving of contiguous files from a user-selected regular disk image.
//! This deliberately makes no filesystem undelete or original-name claim.
#[cfg(windows)]
mod windows_source;
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::{
    collections::HashSet,
    fs::{self, File, OpenOptions},
    io::{BufReader, Read, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
    sync::atomic::{AtomicBool, Ordering},
    time::{Instant, SystemTime, UNIX_EPOCH},
};
#[cfg(windows)]
pub use windows_source::drives;
#[cfg(unix)]
mod unix_source;
#[cfg(unix)]
pub use unix_source::drives;

const BLOCK: usize = 1024 * 1024;
const MAX_FILE: u64 = 512 * 1024 * 1024;
const MAX_RESULTS: usize = 10_000;
const MAX_ATTEMPTS: u64 = 50_000;
const MAX_JSON_INTEGER: u64 = (1u64 << 53) - 1;
const SIGNATURES: &[&[u8]] = &[
    b"\xff\xd8\xff",
    b"\x89PNG\r\n\x1a\n",
    b"%PDF-",
    b"PK\x03\x04",
    b"RIFF",
];

#[derive(Clone, Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Candidate {
    pub id: String,
    pub name: String,
    pub category: String,
    pub format: String,
    pub offset: u64,
    pub size: u64,
    pub evidence: String,
    pub sha256: String,
    pub original_path: Option<String>,
    pub recoverable: bool,
}
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RecoveryReport {
    pub source: String,
    pub source_bytes: u64,
    pub scanned_bytes: u64,
    pub files: Vec<Candidate>,
    pub rejected_headers: u64,
    pub cancelled: bool,
    pub limited: bool,
    pub elapsed_ms: u64,
    pub method: &'static str,
}
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RecoveryProgress {
    pub bytes: u64,
    pub total: u64,
    pub found: usize,
    pub phase: &'static str,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExportedFile {
    pub candidate: Candidate,
    pub path: String,
}
#[derive(Serialize)]
pub struct ExportFailure {
    pub id: String,
    pub error: String,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RecoveryExport {
    pub directory: String,
    pub exported: Vec<ExportedFile>,
    pub failed: Vec<ExportFailure>,
    pub cancelled: bool,
    pub manifest: Option<String>,
    pub warning: Option<String>,
}

pub struct RecoverySession {
    source: File,
    path: PathBuf,
    identity: String,
    size: u64,
    modified: u128,
    undelete: Option<sift_undelete::Undelete>,
    live_drive: Option<char>,
    #[cfg(unix)]
    direct_source: Option<unix_source::Drive>,
    pub report: RecoveryReport,
}
fn error(e: impl std::fmt::Display) -> String {
    e.to_string()
}
fn stopped(cancel: &AtomicBool) -> Result<(), String> {
    if cancel.load(Ordering::Relaxed) {
        Err("Recovery stopped.".into())
    } else {
        Ok(())
    }
}
impl RecoverySession {
    #[cfg(unix)]
    pub fn open_drive(id: &str) -> Result<Self, String> {
        let selected = unix_source::selected(id)?;
        let source = unix_source::open(&selected)?;
        let size = selected.size;
        if size > MAX_JSON_INTEGER || size < 4096 {
            return Err("Unsupported storage size".into());
        }
        Ok(Self {
            source,
            path: PathBuf::from(id),
            identity: format!("direct-{id}"),
            size,
            modified: 0,
            undelete: None,
            live_drive: None,
            direct_source: Some(selected),
            report: RecoveryReport {
                source: format!("{id} (direct drive)"),
                source_bytes: size,
                scanned_bytes: 0,
                files: vec![],
                rejected_headers: 0,
                cancelled: false,
                limited: false,
                elapsed_ms: 0,
                method: "ntfs-deleted-records-v1",
            },
        })
    }
    #[cfg(windows)]
    pub fn open_drive(id: &str) -> Result<Self, String> {
        let drive = windows_source::validate_drive(id)?;
        let path = PathBuf::from(format!(r"\\.\{drive}:"));
        use std::os::windows::fs::OpenOptionsExt;
        let mut source=OpenOptions::new().read(true).share_mode(3).open(&path).map_err(|e|format!("Cannot read {drive}: as a raw volume: {e}. Use ‘Enable drive access’ to open the recovery-only administrator window."))?;
        let mut boot = [0; 4096];
        source.read_exact(&mut boot).map_err(error)?;
        let size = if &boot[3..11] == b"NTFS    " {
            let sector = u16::from_le_bytes(boot[11..13].try_into().unwrap()) as u64;
            u64::from_le_bytes(boot[40..48].try_into().unwrap())
                .checked_mul(sector)
                .ok_or("Invalid NTFS size")?
        } else {
            windows_source::length(&source)?
        };
        if size > MAX_JSON_INTEGER || size < 4096 {
            return Err("Unsupported NTFS volume size".into());
        }
        Ok(Self {
            source,
            path,
            identity: format!("volume-{drive}"),
            size,
            modified: 0,
            undelete: None,
            live_drive: Some(drive),
            report: RecoveryReport {
                source: format!("{drive}: (direct drive)"),
                source_bytes: size,
                scanned_bytes: 0,
                files: vec![],
                rejected_headers: 0,
                cancelled: false,
                limited: false,
                elapsed_ms: 0,
                method: "ntfs-deleted-records-v1",
            },
        })
    }
    pub fn open(path: &Path) -> Result<Self, String> {
        // Never accept block devices, symlinks, reparse points, or cloud placeholders.
        let meta = fs::symlink_metadata(path).map_err(error)?;
        if !meta.is_file() || meta.file_type().is_symlink() {
            return Err(
                "Choose a regular, local disk-image file. Live disks and links are not supported."
                    .into(),
            );
        }
        #[cfg(windows)]
        {
            use std::os::windows::fs::MetadataExt;
            if meta.file_attributes() & (0x400 | 0x1000 | 0x40000 | 0x400000) != 0 {
                return Err("Download the image locally first; cloud placeholders and reparse points are refused.".into());
            }
        }
        let path = fs::canonicalize(path).map_err(error)?;
        let mut options = OpenOptions::new();
        options.read(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC);
        }
        #[cfg(windows)]
        {
            use std::os::windows::fs::OpenOptionsExt;
            options.share_mode(1).custom_flags(0x00200000);
        }
        let source = options.open(&path).map_err(error)?;
        let metadata = source.metadata().map_err(error)?;
        if !metadata.is_file() || metadata.len() > MAX_JSON_INTEGER {
            return Err("Unsupported source type or image size.".into());
        }
        let identity = crate::cleanup::handle_identity(&source)?.0;
        let size = metadata.len();
        let modified = crate::modified_nanos(&metadata);
        let report = RecoveryReport {
            source: path.display().to_string(),
            source_bytes: size,
            scanned_bytes: 0,
            files: vec![],
            rejected_headers: 0,
            cancelled: false,
            limited: false,
            elapsed_ms: 0,
            method: "contiguous-signature-carving-v1",
        };
        let result = Self {
            source,
            path,
            identity,
            size,
            modified,
            undelete: None,
            live_drive: None,
            #[cfg(unix)]
            direct_source: None,
            report,
        };
        result.unchanged()?;
        Ok(result)
    }
    fn unchanged(&self) -> Result<(), String> {
        #[cfg(unix)]
        if self.direct_source.is_some() {
            return Ok(());
        }
        if self.live_drive.is_some() {
            return Ok(());
        }
        let metadata = self.source.metadata().map_err(error)?;
        let path_metadata = fs::symlink_metadata(&self.path).map_err(error)?;
        if !path_metadata.is_file()
            || path_metadata.file_type().is_symlink()
            || metadata.len() != self.size
            || crate::modified_nanos(&metadata) != self.modified
            || crate::modified_nanos(&path_metadata) != self.modified
            || path_metadata.len() != self.size
            || crate::cleanup::handle_identity(&self.source)?.0 != self.identity
            || format!("{:?}", file_id::get_file_id(&self.path).map_err(error)?) != self.identity
        {
            return Err("The source image changed. Scan a stable image again.".into());
        }
        Ok(())
    }
    pub fn scan(
        &mut self,
        cancel: &AtomicBool,
        progress: impl FnMut(RecoveryProgress),
    ) -> Result<RecoveryReport, String> {
        self.scan_deleted(cancel, progress)
    }
    pub fn scan_deleted(
        &mut self,
        cancel: &AtomicBool,
        mut progress: impl FnMut(RecoveryProgress),
    ) -> Result<RecoveryReport, String> {
        self.unchanged()?;
        let start = Instant::now();
        let engine = sift_undelete::Undelete::scan(
            &self.source,
            self.size,
            cancel,
            |done, total, found| {
                progress(RecoveryProgress {
                    bytes: done,
                    total,
                    found,
                    phase: "Reading NTFS deleted file records",
                });
            },
        )?;
        self.report.files = engine
            .scan
            .files
            .iter()
            .map(|f| Candidate {
                id: f.id.clone(),
                name: f.name.clone(),
                category: crate::category(Path::new(&f.name)).into(),
                format: Path::new(&f.name)
                    .extension()
                    .and_then(|s| s.to_str())
                    .unwrap_or("")
                    .to_ascii_lowercase(),
                offset: f.offset,
                size: f.size,
                evidence: f.evidence.clone(),
                sha256: String::new(),
                original_path: Some(f.original_path.clone()),
                recoverable: f.recoverable,
            })
            .collect();
        self.report.rejected_headers = engine.scan.skipped;
        self.report.cancelled = engine.scan.cancelled;
        self.report.limited = engine.scan.limited;
        self.report.method = "ntfs-deleted-records-v1";
        self.report.scanned_bytes = engine.scan.records_examined;
        self.report.elapsed_ms = start.elapsed().as_millis() as u64;
        self.undelete = Some(engine);
        self.unchanged()?;
        Ok(self.report.clone())
    }
    pub fn scan_carved(
        &mut self,
        cancel: &AtomicBool,
        mut progress: impl FnMut(RecoveryProgress),
    ) -> Result<RecoveryReport, String> {
        self.undelete = None;
        self.report.method = "contiguous-signature-carving-v1";
        self.unchanged()?;
        let start = Instant::now();
        let mut reader = Probe {
            file: BufReader::with_capacity(
                64 * 1024,
                sift_undelete::Volume::new(&self.source, 0, self.size),
            ),
            size: self.size,
            budget: self.size.saturating_mul(4).saturating_add(MAX_FILE),
            cancel,
            search_cache: Vec::new(),
            search_start: 0,
        };
        let mut scan = vec![0u8; BLOCK + 16];
        let matcher = aho_corasick::AhoCorasick::new(SIGNATURES).map_err(error)?;
        let mut position = 0u64;
        let mut skip = 0u64;
        let mut attempts = 0u64;
        self.report.files.clear();
        self.report.rejected_headers = 0;
        self.report.limited = false;
        while position < self.size && !cancel.load(Ordering::Relaxed) {
            let count = ((self.size - position).min(scan.len() as u64)) as usize;
            reader.file.seek(SeekFrom::Start(position)).map_err(error)?;
            reader.file.read_exact(&mut scan[..count]).map_err(error)?;
            let covered = count.min(BLOCK);
            let mut index = skip.saturating_sub(position).min(covered as u64) as usize;
            while index < covered {
                if cancel.load(Ordering::Relaxed) {
                    break;
                }
                let Some(found) = matcher.find(&scan[index..count]) else {
                    break;
                };
                index += found.start();
                if index >= covered {
                    break;
                }
                if SIGNATURES
                    .iter()
                    .any(|sig| scan[index..count].starts_with(sig))
                {
                    attempts += 1;
                    if attempts > MAX_ATTEMPTS
                        || self.report.files.len() >= MAX_RESULTS
                        || reader.budget == 0
                    {
                        self.report.limited = true;
                        break;
                    }
                    let offset = position + index as u64;
                    match identify(&mut reader, offset) {
                        Ok(Some((size, extension, category, evidence))) => {
                            let sha256 = match reader.hash(offset, size) {
                                Ok(hash) => hash,
                                Err(_) if cancel.load(Ordering::Relaxed) => break,
                                Err(_) if reader.budget == 0 => {
                                    self.report.limited = true;
                                    break;
                                }
                                Err(e) => return Err(e),
                            };
                            self.report.files.push(Candidate {
                                id: format!("{offset:016x}"),
                                name: format!("recovered-{offset:016x}.{extension}"),
                                format: extension.into(),
                                category: category.into(),
                                offset,
                                size,
                                evidence: evidence.into(),
                                sha256,
                                original_path: None,
                                recoverable: true,
                            });
                            skip = offset + size;
                            index = skip.saturating_sub(position).min(covered as u64) as usize;
                            continue;
                        }
                        Ok(None) => self.report.rejected_headers += 1,
                        Err(_) if cancel.load(Ordering::Relaxed) => break,
                        Err(_) if reader.budget == 0 => {
                            self.report.limited = true;
                            break;
                        }
                        Err(e) => return Err(e),
                    }
                }
                index += 1;
            }
            if self.report.limited || cancel.load(Ordering::Relaxed) {
                break;
            }
            position += covered as u64;
            progress(RecoveryProgress {
                bytes: position,
                total: self.size,
                found: self.report.files.len(),
                phase: "Reading image · source unchanged",
            });
        }
        self.unchanged()?;
        self.report.scanned_bytes = position;
        self.report.cancelled = cancel.load(Ordering::Relaxed);
        self.report.elapsed_ms = start.elapsed().as_millis() as u64;
        Ok(self.report.clone())
    }
    pub fn preview(&mut self, id: &str) -> Result<Option<crate::FilePreview>, String> {
        self.unchanged()?;
        let candidate = self
            .report
            .files
            .iter()
            .find(|file| file.id == id)
            .ok_or("Candidate is not in this recovery scan.")?;
        if candidate.category != "Images" || candidate.size > 16 * 1024 * 1024 {
            return Ok(None);
        }
        let mut bytes = vec![0; candidate.size as usize];
        let cancel = AtomicBool::new(false);
        if let Some(engine) = &self.undelete {
            engine.validate(&self.source, id, &cancel)?;
        }
        self.read_candidate(candidate, 0, &mut bytes)?;
        if !candidate.sha256.is_empty() && hex(&Sha256::digest(&bytes)) != candidate.sha256 {
            return Err("Source contents changed; scan again.".into());
        }
        let mut decoder = image::ImageReader::new(std::io::Cursor::new(bytes))
            .with_guessed_format()
            .map_err(error)?;
        let mut limits = image::Limits::default();
        limits.max_alloc = Some(64 * 1024 * 1024);
        limits.max_image_width = Some(32768);
        limits.max_image_height = Some(32768);
        decoder.limits(limits);
        let preview = decoder
            .decode()
            .ok()
            .map(crate::preview::picture)
            .transpose()?;
        self.unchanged()?;
        if let Some(engine) = &self.undelete {
            engine.validate(&self.source, id, &cancel)?;
        }
        Ok(preview)
    }
    fn read_candidate(
        &self,
        candidate: &Candidate,
        offset: u64,
        bytes: &mut [u8],
    ) -> Result<(), String> {
        if let Some(engine) = &self.undelete {
            if engine.read(&self.source, &candidate.id, offset, bytes)? != bytes.len() {
                return Err("Truncated deleted stream".into());
            }
        } else {
            let mut source = sift_undelete::Volume::new(&self.source, 0, self.size);
            source
                .seek(SeekFrom::Start(candidate.offset + offset))
                .map_err(error)?;
            source.read_exact(bytes).map_err(error)?;
        }
        Ok(())
    }
    pub fn export(
        &mut self,
        destination: &Path,
        ids: &[String],
        cancel: &AtomicBool,
        mut progress: impl FnMut(RecoveryProgress),
    ) -> Result<RecoveryExport, String> {
        self.unchanged()?;
        let selected: HashSet<_> = ids.iter().collect();
        if selected.is_empty()
            || selected.len() > MAX_RESULTS
            || selected
                .iter()
                .any(|id| !self.report.files.iter().any(|f| &f.id == *id))
        {
            return Err("Select candidates from the current recovery scan.".into());
        }
        let destination = fs::canonicalize(destination).map_err(error)?;
        if !destination.is_dir() {
            return Err("Choose an existing destination folder.".into());
        }
        #[cfg(unix)]
        if let Some(source) = &self.direct_source {
            unix_source::destination_allowed(source, &destination)?;
        }
        if let Some(drive) = self.live_drive {
            #[cfg(windows)]
            if windows_source::same_volume(&destination, drive)? {
                return Err("Choose a different drive. Saving to the source volume could overwrite deleted data.".into());
            }
            #[cfg(not(windows))]
            let _ = drive;
        }
        stopped(cancel)?;
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(error)?
            .as_nanos();
        let directory = destination.join(format!("Sift-recovered-{stamp}"));
        #[allow(unused_mut)]
        let mut builder = fs::DirBuilder::new();
        #[cfg(unix)]
        {
            use std::os::unix::fs::DirBuilderExt;
            builder.mode(0o700);
        }
        builder.create(&directory).map_err(error)?;
        let mut result = RecoveryExport {
            directory: directory.display().to_string(),
            exported: vec![],
            failed: vec![],
            cancelled: false,
            manifest: None,
            warning: None,
        };
        let candidates: Vec<_> = self
            .report
            .files
            .iter()
            .filter(|f| selected.contains(&f.id))
            .cloned()
            .collect();
        let total: u64 = candidates.iter().map(|f| f.size).sum();
        let mut copied = 0;
        for mut candidate in candidates {
            if cancel.load(Ordering::Relaxed) {
                result.cancelled = true;
                break;
            }
            let folder = directory.join(&candidate.category);
            // Never interpret a recovered filename or folder as a destination path.
            // The stable record ID prevents duplicate-name collisions and traversal.
            let safe_name: String = candidate
                .name
                .chars()
                .map(|c| {
                    if c.is_control() || "<>:\"/\\|?*".contains(c) {
                        '_'
                    } else {
                        c
                    }
                })
                .scan(0usize, |bytes, c| {
                    if *bytes + c.len_utf8() > 160 {
                        None
                    } else {
                        *bytes += c.len_utf8();
                        Some(c)
                    }
                })
                .collect();
            let output_name = if self.undelete.is_some() {
                format!(
                    "{}-{}",
                    candidate.id,
                    safe_name.trim_end_matches([' ', '.'])
                )
            } else {
                candidate.name.clone()
            };
            let path = folder.join(&output_name);
            let partial = path.with_extension(format!("{}.partial", candidate.format));
            let mut created = false;
            let outcome: Result<(), String> = (|| {
                self.unchanged()?;
                if !candidate.recoverable {
                    return Err(candidate.evidence.clone());
                }
                if let Some(engine) = &self.undelete {
                    engine.validate(&self.source, &candidate.id, cancel)?;
                }
                if !folder.exists() {
                    builder.create(&folder).map_err(error)?;
                }
                let mut file = OpenOptions::new()
                    .write(true)
                    .read(true)
                    .create_new(true)
                    .open(&partial)
                    .map_err(error)?;
                created = true;
                let mut remaining = candidate.size;
                let mut buffer = vec![0u8; BLOCK];
                let mut hash = Sha256::new();
                while remaining > 0 {
                    stopped(cancel)?;
                    let n = remaining.min(BLOCK as u64) as usize;
                    self.read_candidate(&candidate, candidate.size - remaining, &mut buffer[..n])?;
                    file.write_all(&buffer[..n]).map_err(error)?;
                    hash.update(&buffer[..n]);
                    remaining -= n as u64;
                    copied += n as u64;
                    progress(RecoveryProgress {
                        bytes: copied,
                        total,
                        found: result.exported.len(),
                        phase: "Copying and verifying recovered files",
                    });
                }
                file.sync_all().map_err(error)?;
                let copied_hash = hex(&hash.finalize());
                if !candidate.sha256.is_empty() && copied_hash != candidate.sha256 {
                    return Err("Source bytes differ from the recovery scan.".into());
                }
                file.seek(SeekFrom::Start(0)).map_err(error)?;
                let mut hash = Sha256::new();
                loop {
                    stopped(cancel)?;
                    let n = file.read(&mut buffer).map_err(error)?;
                    if n == 0 {
                        break;
                    }
                    hash.update(&buffer[..n]);
                }
                if hex(&hash.finalize()) != copied_hash {
                    return Err("Destination read-back did not match.".into());
                }
                self.unchanged()?;
                if let Some(engine) = &self.undelete {
                    engine.validate(&self.source, &candidate.id, cancel)?;
                    // Read the source again: a live drive can change without file timestamps.
                    let mut source_hash = Sha256::new();
                    let mut at = 0;
                    while at < candidate.size {
                        stopped(cancel)?;
                        let n = (candidate.size - at).min(BLOCK as u64) as usize;
                        self.read_candidate(&candidate, at, &mut buffer[..n])?;
                        source_hash.update(&buffer[..n]);
                        at += n as u64;
                    }
                    if hex(&source_hash.finalize()) != copied_hash {
                        return Err(
                            "Source data changed during recovery. Rescan a stable image.".into(),
                        );
                    }
                    engine.validate(&self.source, &candidate.id, cancel)?;
                    candidate.sha256 = copied_hash;
                }
                drop(file);
                publish_no_replace(&partial, &path)?;
                Ok(())
            })();
            match outcome {
                Ok(()) => result.exported.push(ExportedFile {
                    candidate,
                    path: path.display().to_string(),
                }),
                Err(mut e) => {
                    if created {
                        if let Err(cleanup) = fs::remove_file(&partial) {
                            e.push_str(&format!(
                                " Incomplete output remains at {}: {cleanup}",
                                partial.display()
                            ));
                        }
                    }
                    result.failed.push(ExportFailure {
                        id: candidate.id,
                        error: e,
                    });
                }
            }
        }
        result.cancelled |= cancel.load(Ordering::Relaxed);
        let manifest_path = directory.join("recovery-manifest.json");
        let notice = if self.undelete.is_some() {
            "Deleted NTFS records. Original paths are metadata evidence, never output instructions. Unallocated clusters can contain overwritten or TRIMmed bytes; readability is not guaranteed. Source is read-only. Export hashes compare copied bytes, destination read-back, and a second source read."
        } else {
            "Contiguous file carving. Original names, folders, deleted status, and readability are unknown. Source opened read-only. Exports SHA-256 checked and read back."
        };
        let manifest = serde_json::json!({ "schemaVersion": 2, "method": self.report.method, "source": self.report.source, "sourceBytes": self.size, "requestedIds": ids, "notice": notice, "result": result });
        match (|| {
            let mut file = OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&manifest_path)
                .map_err(error)?;
            file.write_all(&serde_json::to_vec_pretty(&manifest).map_err(error)?)
                .map_err(error)?;
            file.sync_all().map_err(error)
        })() {
            Ok(()) => result.manifest = Some(manifest_path.display().to_string()),
            Err(e) => {
                result.warning = Some(format!(
                    "Files copied, but the manifest could not be saved: {e}"
                ))
            }
        }
        Ok(result)
    }
}
/// Atomic publication without replacing a destination, including on FAT/exFAT.
fn publish_no_replace(from: &Path, to: &Path) -> Result<(), String> {
    #[cfg(unix)]
    {
        use std::os::unix::ffi::OsStrExt;
        let from = std::ffi::CString::new(from.as_os_str().as_bytes()).map_err(error)?;
        let to = std::ffi::CString::new(to.as_os_str().as_bytes()).map_err(error)?;
        #[cfg(target_os = "linux")]
        // SAFETY: both C strings are valid for the duration of this call. RENAME_NOREPLACE
        // fails atomically when the destination exists; no replacing fallback is used.
        let result = unsafe {
            libc::renameat2(
                libc::AT_FDCWD,
                from.as_ptr(),
                libc::AT_FDCWD,
                to.as_ptr(),
                libc::RENAME_NOREPLACE,
            )
        };
        #[cfg(target_os = "macos")]
        // SAFETY: valid owned C strings; RENAME_EXCL prohibits destination replacement.
        let result = unsafe { libc::renamex_np(from.as_ptr(), to.as_ptr(), libc::RENAME_EXCL) };
        if result != 0 {
            return Err(format!(
                "Could not publish without replacement: {}",
                std::io::Error::last_os_error()
            ));
        }
        Ok(())
    }
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        let from: Vec<u16> = from.as_os_str().encode_wide().chain(Some(0)).collect();
        let to: Vec<u16> = to.as_os_str().encode_wide().chain(Some(0)).collect();
        // SAFETY: valid NUL-terminated UTF-16 paths. MoveFileW never replaces an existing file.
        unsafe {
            windows::Win32::Storage::FileSystem::MoveFileW(
                windows::core::PCWSTR(from.as_ptr()),
                windows::core::PCWSTR(to.as_ptr()),
            )
        }
        .map_err(error)
    }
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

struct Probe<'a> {
    file: BufReader<sift_undelete::Volume<'a>>,
    size: u64,
    budget: u64,
    cancel: &'a AtomicBool,
    search_cache: Vec<u8>,
    search_start: u64,
}
impl Probe<'_> {
    fn read(&mut self, position: u64, bytes: &mut [u8]) -> Result<bool, String> {
        stopped(self.cancel)?;
        if position
            .checked_add(bytes.len() as u64)
            .is_none_or(|end| end > self.size)
        {
            return Ok(false);
        }
        if self.budget < bytes.len() as u64 {
            self.budget = 0;
            return Err("Validation read budget reached.".into());
        }
        self.budget -= bytes.len() as u64;
        self.file.seek(SeekFrom::Start(position)).map_err(error)?;
        self.file.read_exact(bytes).map_err(error)?;
        Ok(true)
    }
    fn hash(&mut self, offset: u64, size: u64) -> Result<String, String> {
        let mut hash = Sha256::new();
        let mut buffer = vec![0u8; BLOCK];
        let mut pos = offset;
        while pos < offset + size {
            let n = (offset + size - pos).min(BLOCK as u64) as usize;
            if !self.read(pos, &mut buffer[..n])? {
                return Err("Truncated source image.".into());
            }
            hash.update(&buffer[..n]);
            pos += n as u64;
        }
        Ok(hex(&hash.finalize()))
    }
    fn find(&mut self, start: u64, end: u64, needle: &[u8]) -> Result<Option<u64>, String> {
        let mut pos = start;
        while pos < end {
            stopped(self.cancel)?;
            let cache_end = self.search_start + self.search_cache.len() as u64;
            if pos >= self.search_start && pos < cache_end {
                let begin = (pos - self.search_start) as usize;
                let stop = (end.min(cache_end) - self.search_start) as usize;
                if let Some(i) = memchr::memmem::find(&self.search_cache[begin..stop], needle) {
                    return Ok(Some(pos + i as u64));
                }
                if cache_end >= end {
                    break;
                }
                // Re-read only the small overlap needed for boundary-crossing markers.
                let next = cache_end.saturating_sub(needle.len() as u64 - 1);
                if next > pos {
                    pos = next;
                }
            }
            let n = (end - pos).min((64 * 1024 + needle.len()) as u64) as usize;
            if n < needle.len() {
                break;
            }
            let mut bytes = vec![0; n];
            if !self.read(pos, &mut bytes)? {
                return Ok(None);
            }
            self.search_start = pos;
            self.search_cache = bytes;
        }
        Ok(None)
    }
}
type Match = Option<(u64, &'static str, &'static str, &'static str)>;
fn identify(p: &mut Probe<'_>, base: u64) -> Result<Match, String> {
    let end = p.size.min(base.saturating_add(MAX_FILE));
    let mut head = [0u8; 12];
    if !p.read(base, &mut head)? {
        return Ok(None);
    }
    if head.starts_with(b"\x89PNG\r\n\x1a\n") {
        let mut pos = base + 8;
        let mut ihdr = false;
        let mut idat = false;
        let mut count = 0;
        let mut buffer = vec![0; 64 * 1024];
        while pos + 12 <= end && count < 100_000 {
            count += 1;
            let mut header = [0; 8];
            if !p.read(pos, &mut header)? {
                break;
            }
            let length = u32::from_be_bytes(header[..4].try_into().unwrap()) as u64;
            if length > 64 * 1024 * 1024 || pos + 12 + length > end {
                break;
            }
            if !ihdr && (&header[4..] != b"IHDR" || length != 13) {
                break;
            }
            if &header[4..] == b"IHDR" {
                if ihdr || length != 13 {
                    break;
                }
                ihdr = true;
            }
            if &header[4..] == b"IDAT" {
                idat = true;
            }
            let mut crc = crc32fast::Hasher::new();
            crc.update(&header[4..]);
            let mut offset = pos + 8;
            while offset < pos + 8 + length {
                let n = (pos + 8 + length - offset).min(buffer.len() as u64) as usize;
                if !p.read(offset, &mut buffer[..n])? {
                    return Ok(None);
                }
                crc.update(&buffer[..n]);
                offset += n as u64;
            }
            let mut expected = [0; 4];
            if !p.read(offset, &mut expected)? || crc.finalize() != u32::from_be_bytes(expected) {
                break;
            }
            pos += 12 + length;
            if &header[4..] == b"IEND" {
                return Ok((length == 0 && idat).then_some((
                    pos - base,
                    "png",
                    "Images",
                    "PNG chunk CRCs and end marker checked",
                )));
            }
        }
    } else if head.starts_with(b"\xff\xd8\xff") {
        let mut pos = base + 2;
        let mut frame = false;
        let mut scan = false;
        let mut entropy = false;
        while pos + 2 <= end {
            let marker = if entropy {
                let Some(found) = p.find(pos, end, b"\xff")? else {
                    break;
                };
                pos = found;
                let mut bytes = [0; 2];
                if !p.read(pos, &mut bytes)? {
                    break;
                }
                bytes[1]
            } else {
                let mut bytes = [0; 2];
                if !p.read(pos, &mut bytes)? || bytes[0] != 0xff {
                    break;
                }
                bytes[1]
            };
            if marker == 0xff {
                pos += 1;
                continue;
            }
            if entropy && (marker == 0 || (0xd0..=0xd7).contains(&marker)) {
                pos += 2;
                continue;
            }
            entropy = false;
            if marker == 0xd9 {
                return Ok((frame && scan).then_some((
                    pos + 2 - base,
                    "jpg",
                    "Images",
                    "JPEG marker structure and end marker checked",
                )));
            }
            if marker == 0 || marker == 0xd8 || (0xd0..=0xd7).contains(&marker) {
                break;
            }
            let mut size = [0; 2];
            if !p.read(pos + 2, &mut size)? {
                break;
            }
            let length = u16::from_be_bytes(size) as u64;
            if length < 2 || pos + 2 + length > end {
                break;
            }
            if matches!(marker, 0xc0..=0xc3 | 0xc5..=0xc7 | 0xc9..=0xcb | 0xcd..=0xcf) {
                if length < 8 {
                    break;
                }
                frame = true;
            }
            if marker == 0xda {
                if !frame || length < 6 {
                    break;
                }
                scan = true;
                entropy = true;
            }
            pos += 2 + length;
        }
    } else if head.starts_with(b"%PDF-")
        && matches!(head[5], b'1' | b'2')
        && head[6] == b'.'
        && head[7].is_ascii_digit()
    {
        if let Some(footer) = p.find(base + 8, end, b"%%EOF")? {
            // A PDF footer is boundary evidence only; incremental updates and encryption
            // require a real PDF reader. Never describe this as an integrity check.
            return Ok(Some((
                footer + 5 - base,
                "pdf",
                "Documents",
                "PDF header/footer found; readability and later revisions unverified",
            )));
        }
    } else if head.starts_with(b"RIFF") {
        let length = u32::from_le_bytes(head[4..8].try_into().unwrap()) as u64 + 8;
        if length < 20 || base + length > end {
            return Ok(None);
        }
        let (extension, category) = match &head[8..12] {
            b"WEBP" => ("webp", "Images"),
            b"WAVE" => ("wav", "Audio"),
            _ => return Ok(None),
        };
        let mut pos = base + 12;
        let mut payload = false;
        let mut fmt = false;
        let mut chunks = 0;
        while pos + 8 <= base + length && chunks < 100_000 {
            chunks += 1;
            let mut chunk = [0u8; 8];
            if !p.read(pos, &mut chunk)? {
                break;
            }
            let n = u32::from_le_bytes(chunk[4..].try_into().unwrap()) as u64;
            if pos + 8 + n + (n % 2) > base + length {
                break;
            }
            if extension == "wav" {
                if &chunk[..4] == b"fmt " && n >= 16 {
                    fmt = true;
                }
                if &chunk[..4] == b"data" && n > 0 {
                    payload = true;
                }
            } else if matches!(&chunk[..4], b"VP8 " | b"VP8L" | b"ANMF") && n > 0 {
                payload = true;
            }
            pos += 8 + n + (n % 2);
        }
        if pos == base + length && payload && (extension != "wav" || fmt) {
            return Ok(Some((
                length,
                extension,
                category,
                "RIFF declared size and chunk boundaries checked",
            )));
        }
    } else if head.starts_with(b"PK\x03\x04") {
        let mut search = base + 30;
        while let Some(eocd) = p.find(search, end, b"PK\x05\x06")? {
            let mut record = [0; 22];
            if !p.read(eocd, &mut record)? {
                break;
            }
            let le16 = |i| u16::from_le_bytes(record[i..i + 2].try_into().unwrap()) as u64;
            let le32 = |i| u32::from_le_bytes(record[i..i + 4].try_into().unwrap()) as u64;
            let entries = le16(10);
            let cd_size = le32(12);
            let cd_offset = le32(16);
            let comment = le16(20);
            if le16(4) == 0
                && le16(6) == 0
                && le16(8) == entries
                && entries > 0
                && entries < 65535
                && base + cd_offset + cd_size == eocd
                && eocd + 22 + comment <= end
            {
                let mut pos = base + cd_offset;
                let mut valid = true;
                for _ in 0..entries {
                    let mut central = [0; 46];
                    if pos + 46 > eocd
                        || !p.read(pos, &mut central)?
                        || &central[..4] != b"PK\x01\x02"
                    {
                        valid = false;
                        break;
                    }
                    let n = u16::from_le_bytes(central[28..30].try_into().unwrap()) as u64;
                    let extra = u16::from_le_bytes(central[30..32].try_into().unwrap()) as u64;
                    let comment = u16::from_le_bytes(central[32..34].try_into().unwrap()) as u64;
                    let local = u32::from_le_bytes(central[42..46].try_into().unwrap()) as u64;
                    let mut local_head = [0; 30];
                    if base + local + 30 > base + cd_offset
                        || !p.read(base + local, &mut local_head)?
                        || &local_head[..4] != b"PK\x03\x04"
                    {
                        valid = false;
                        break;
                    }
                    let compressed = u32::from_le_bytes(central[20..24].try_into().unwrap()) as u64;
                    let local_n = u16::from_le_bytes(local_head[26..28].try_into().unwrap()) as u64;
                    let local_extra =
                        u16::from_le_bytes(local_head[28..30].try_into().unwrap()) as u64;
                    if local_n != n
                        || base + local + 30 + local_n + local_extra + compressed > base + cd_offset
                        || pos + 46 + n + extra + comment > eocd
                    {
                        valid = false;
                        break;
                    }
                    pos += 46 + n + extra + comment;
                }
                if valid && pos == eocd {
                    return Ok(Some((eocd + 22 + comment - base, "zip", "Archives", "ZIP central directory and local entry bounds checked; payload CRCs unverified")));
                }
            }
            search = eocd + 4;
        }
    }
    Ok(None)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn deleted_ntfs_records_export_original_contents_with_hashes_and_path_evidence() {
        let source_folder = tempfile::tempdir().unwrap();
        let source = source_folder.path().join("ntfs.img");
        let mut gzip = flate2::read::GzDecoder::new(
            &include_bytes!("../../recovery-fixtures/ntfs-deleted.img.gz")[..],
        );
        {
            let mut writer = File::create(&source).unwrap();
            std::io::copy(&mut gzip, &mut writer).unwrap();
            writer.sync_all().unwrap();
        }
        let before = hex(&Sha256::digest(std::fs::read(&source).unwrap()));
        let cancel = AtomicBool::new(false);
        let mut session = RecoverySession::open(&source).unwrap();
        let report = session.scan(&cancel, |_| {}).unwrap();
        assert_eq!(report.files.len(), 2);
        assert_eq!(report.method, "ntfs-deleted-records-v1");
        let destination = tempfile::tempdir().unwrap();
        let ids = report
            .files
            .iter()
            .map(|f| f.id.clone())
            .collect::<Vec<_>>();
        let result = session
            .export(destination.path(), &ids, &cancel, |_| {})
            .unwrap();
        assert_eq!(result.exported.len(), 2);
        assert!(result.failed.is_empty());
        for output in &result.exported {
            let data = std::fs::read(&output.path).unwrap();
            assert_eq!(hex(&Sha256::digest(&data)), output.candidate.sha256);
            assert!(output
                .candidate
                .original_path
                .as_ref()
                .unwrap()
                .starts_with("/Projects/"));
            if output.candidate.name == "meeting-notes.txt" {
                assert_eq!(
                    data,
                    b"These notes were permanently deleted, outside any recycle bin.\n"
                );
            }
        }
        let manifest: serde_json::Value =
            serde_json::from_slice(&std::fs::read(result.manifest.unwrap()).unwrap()).unwrap();
        assert_eq!(manifest["method"], "ntfs-deleted-records-v1");
        assert_eq!(
            before,
            hex(&Sha256::digest(std::fs::read(&source).unwrap()))
        );
    }
    const SAMPLE: &[u8] = include_bytes!("../../recovery-fixtures/sample.img");
    fn fixture() -> (tempfile::TempDir, PathBuf) {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("evidence.img");
        fs::write(&path, SAMPLE).unwrap();
        (dir, path)
    }
    fn run(path: &Path) -> RecoverySession {
        let mut session = RecoverySession::open(path).unwrap();
        session
            .scan_carved(&AtomicBool::new(false), |_| {})
            .unwrap();
        session
    }
    #[test]
    fn six_formats_have_exact_boundaries_and_hashes_without_source_changes() {
        let (_dir, path) = fixture();
        let source = fs::read(&path).unwrap();
        let session = run(&path);
        let expected: serde_json::Value =
            serde_json::from_str(include_str!("../../recovery-fixtures/expected.json")).unwrap();
        assert_eq!(session.report.files.len(), 6);
        for (file, expected) in session
            .report
            .files
            .iter()
            .zip(expected.as_array().unwrap())
        {
            assert_eq!(file.offset, expected["offset"].as_u64().unwrap());
            assert_eq!(file.size, expected["size"].as_u64().unwrap());
            assert_eq!(file.sha256, expected["sha256"].as_str().unwrap());
            assert_eq!(file.format, expected["format"].as_str().unwrap());
        }
        assert!(session.report.rejected_headers >= 3);
        assert_eq!(session.report.scanned_bytes, source.len() as u64);
        assert_eq!(fs::read(path).unwrap(), source);
    }
    #[test]
    fn boundary_crossing_signature_is_not_lost_or_duplicated() {
        let (dir, _) = fixture();
        let path = dir.path().join("boundary.img");
        let padding = BLOCK - 3 - 258;
        let mut bytes = vec![0; padding];
        bytes.extend(SAMPLE);
        fs::write(&path, bytes).unwrap();
        let session = run(&path);
        assert_eq!(session.report.files.len(), 6);
        assert_eq!(session.report.files[0].offset, (BLOCK - 3) as u64);
    }
    #[test]
    fn export_preserves_input_existing_files_and_writes_verified_manifest() {
        let (dir, path) = fixture();
        let mut session = run(&path);
        let dest = dir.path().join("output");
        fs::create_dir(&dest).unwrap();
        fs::write(dest.join("keep.txt"), b"keep").unwrap();
        let ids: Vec<_> = session.report.files.iter().map(|f| f.id.clone()).collect();
        let result = session
            .export(&dest, &ids, &AtomicBool::new(false), |_| {})
            .unwrap();
        assert_eq!(result.exported.len(), 6);
        assert!(result.failed.is_empty());
        for file in &result.exported {
            assert_eq!(
                hex(&Sha256::digest(fs::read(&file.path).unwrap())),
                file.candidate.sha256
            );
        }
        assert!(Path::new(&result.manifest.unwrap()).is_file());
        assert_eq!(fs::read(path).unwrap(), SAMPLE);
        assert_eq!(fs::read(dest.join("keep.txt")).unwrap(), b"keep");
    }
    #[test]
    fn cancellation_keeps_only_completed_results_and_source_unchanged() {
        let (dir, path) = fixture();
        let mut session = run(&path);
        let cancel = AtomicBool::new(false);
        let ids = session
            .report
            .files
            .iter()
            .map(|f| f.id.clone())
            .collect::<Vec<_>>();
        let result = session
            .export(dir.path(), &ids, &cancel, |_| {
                cancel.store(true, Ordering::Relaxed)
            })
            .unwrap();
        assert!(result.cancelled);
        assert!(result.exported.is_empty());
        assert_eq!(result.failed.len(), 1);
        assert_eq!(fs::read(&path).unwrap(), SAMPLE);
        let mut session = RecoverySession::open(&path).unwrap();
        let report = session.scan_carved(&cancel, |_| {}).unwrap();
        assert!(report.cancelled);
        assert!(report.files.is_empty());
    }
    #[test]
    fn changed_source_and_unscanned_ids_are_refused_before_output_creation() {
        let (dir, path) = fixture();
        let mut session = run(&path);
        assert!(session
            .export(
                dir.path(),
                &["forged".into()],
                &AtomicBool::new(false),
                |_| {}
            )
            .is_err());
        // Windows intentionally denies external writers while evidence is held open.
        #[cfg(not(windows))]
        {
            fs::write(&path, b"changed").unwrap();
            assert!(session
                .export(
                    dir.path(),
                    &[session.report.files[0].id.clone()],
                    &AtomicBool::new(false),
                    |_| {}
                )
                .is_err());
        }
        assert_eq!(fs::read_dir(dir.path()).unwrap().count(), 1);
    }
    #[test]
    fn png_crc_corruption_and_oversized_riff_are_not_recovered() {
        let (dir, _) = fixture();
        let path = dir.path().join("broken.img");
        let mut bytes = SAMPLE.to_vec();
        bytes[257 + 29] ^= 0x10;
        fs::write(&path, bytes).unwrap();
        let session = run(&path);
        assert_eq!(session.report.files.len(), 5);
        assert!(!session.report.files.iter().any(|f| f.format == "png"));
    }
    #[test]
    fn repeated_stuffed_jpeg_markers_do_not_exhaust_the_read_budget() {
        let (dir, _) = fixture();
        let path = dir.path().join("dense.img");
        let expected: serde_json::Value =
            serde_json::from_str(include_str!("../../recovery-fixtures/expected.json")).unwrap();
        let jpg = &expected[1];
        let offset = jpg["offset"].as_u64().unwrap() as usize;
        let size = jpg["size"].as_u64().unwrap() as usize;
        let mut bytes = SAMPLE[offset..offset + size - 2].to_vec();
        for _ in 0..100_000 {
            bytes.extend_from_slice(&[0xff, 0]);
        }
        bytes.extend_from_slice(&[0xff, 0xd9]);
        fs::write(&path, &bytes).unwrap();
        let session = run(&path);
        assert_eq!(session.report.files.len(), 1);
        assert!(!session.report.limited);
        assert_eq!(session.report.files[0].size, bytes.len() as u64);
    }
    #[test]
    fn publishing_cannot_replace_an_existing_destination() {
        let dir = tempfile::tempdir().unwrap();
        let from = dir.path().join("file.partial");
        let to = dir.path().join("existing.jpg");
        fs::write(&from, b"new").unwrap();
        fs::write(&to, b"keep").unwrap();
        assert!(publish_no_replace(&from, &to).is_err());
        assert_eq!(fs::read(&to).unwrap(), b"keep");
        assert_eq!(fs::read(&from).unwrap(), b"new");
    }
    #[test]
    fn recovery_preview_is_bounded_and_allowlisted() {
        let (_dir, path) = fixture();
        let mut session = run(&path);
        let id = session.report.files[0].id.clone();
        assert!(session
            .preview(&id)
            .unwrap()
            .unwrap()
            .image
            .unwrap()
            .starts_with("data:image/png;base64,"));
        assert!(session.preview("../elsewhere").is_err());
    }
    #[cfg(unix)]
    #[test]
    fn symlinks_and_replaced_image_paths_are_refused() {
        let (dir, path) = fixture();
        let link = dir.path().join("link.img");
        std::os::unix::fs::symlink(&path, &link).unwrap();
        assert!(RecoverySession::open(&link).is_err());
        let mut session = run(&path);
        fs::remove_file(&path).unwrap();
        fs::write(&path, SAMPLE).unwrap();
        assert!(session
            .scan_carved(&AtomicBool::new(false), |_| {})
            .is_err());
    }
}
