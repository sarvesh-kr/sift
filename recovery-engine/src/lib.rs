use ntfs::attribute_value::NtfsAttributeValue;
pub mod root_protocol;
// Filesystem undelete, never a trash enumerator. No source write API exists.
use ntfs::{Ntfs, NtfsAttributeFlags, NtfsAttributeType, NtfsFileFlags};
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    fs::File,
    io::{self, Read, Seek, SeekFrom},
    sync::atomic::{AtomicBool, Ordering},
};

const MAX_RECORDS: u64 = 2_000_000;
const MAX_RESULTS: usize = 10_000;
const MAX_RUNS: usize = 65_536;
const MAX_BITMAP: u64 = 128 * 1024 * 1024;
const MAX_FILE: u64 = 64 * 1024 * 1024 * 1024;
type Result<T> = std::result::Result<T, String>;
fn err(e: impl std::fmt::Display) -> String {
    e.to_string()
}
pub fn digest(data: &[u8]) -> String {
    format!("{:x}", Sha256::digest(data))
}
fn stop(cancel: &AtomicBool) -> Result<()> {
    if cancel.load(Ordering::Relaxed) {
        Err("Recovery stopped.".into())
    } else {
        Ok(())
    }
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeletedFile {
    pub id: String,
    pub name: String,
    pub original_path: String,
    pub size: u64,
    pub offset: u64,
    pub recoverable: bool,
    pub evidence: String,
}
#[derive(Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Scan {
    pub files: Vec<DeletedFile>,
    pub records_examined: u64,
    pub total_records: u64,
    pub skipped: u64,
    pub limited: bool,
    pub cancelled: bool,
}
#[derive(Clone)]
struct Run {
    physical: Option<u64>,
    length: u64,
}
#[derive(Clone)]
struct Plan {
    record: u64,
    record_position: u64,
    record_hash: String,
    resident: Option<Vec<u8>>,
    runs: Vec<Run>,
    size: u64,
}
pub struct Undelete {
    pub scan: Scan,
    base: u64,
    length: u64,
    boot_hash: String,
    cluster: u64,
    record_size: usize,
    plans: HashMap<String, Plan>,
}

/// Bounds and sector-aligns every physical read, including Windows raw volumes.
/// Cached bytes are discarded at every public operation to avoid stale live-disk data.
pub struct Volume<'a> {
    source: &'a File,
    base: u64,
    length: u64,
    position: u64,
    cache_start: u64,
    cache: Vec<u8>,
    previous: Option<(u64, Vec<u8>)>,
}
impl<'a> Volume<'a> {
    pub fn new(source: &'a File, base: u64, length: u64) -> Self {
        Self {
            source,
            base,
            length,
            position: 0,
            cache_start: u64::MAX,
            cache: vec![],
            previous: None,
        }
    }
}
impl Read for Volume<'_> {
    fn read(&mut self, output: &mut [u8]) -> io::Result<usize> {
        let wanted = output
            .len()
            .min(self.length.saturating_sub(self.position) as usize);
        let mut copied = 0;
        while copied < wanted {
            let absolute = self
                .base
                .checked_add(self.position)
                .ok_or_else(|| io::Error::other("Offset overflow"))?;
            if absolute < self.cache_start
                || absolute.saturating_sub(self.cache_start) >= self.cache.len() as u64
            {
                if self.previous.as_ref().is_some_and(|(start, bytes)| {
                    absolute >= *start && absolute - *start < bytes.len() as u64
                }) {
                    let (start, bytes) = self.previous.take().unwrap();
                    self.previous =
                        Some((self.cache_start, std::mem::replace(&mut self.cache, bytes)));
                    self.cache_start = start;
                    continue;
                }
                if !self.cache.is_empty() {
                    self.previous = Some((self.cache_start, std::mem::take(&mut self.cache)));
                }
                self.cache_start = absolute / 4096 * 4096;
                let end = self.base + self.length;
                let count = (end - self.cache_start).min(64 * 1024) as usize;
                self.cache.resize(count, 0);
                self.source.seek(SeekFrom::Start(self.cache_start))?;
                self.source.read_exact(&mut self.cache)?;
            }
            let start = (absolute - self.cache_start) as usize;
            let count = (wanted - copied).min(self.cache.len() - start);
            output[copied..copied + count].copy_from_slice(&self.cache[start..start + count]);
            copied += count;
            self.position += count as u64;
        }
        Ok(copied)
    }
}
impl Seek for Volume<'_> {
    fn seek(&mut self, from: SeekFrom) -> io::Result<u64> {
        let position = match from {
            SeekFrom::Start(n) => n as i128,
            SeekFrom::End(n) => self.length as i128 + n as i128,
            SeekFrom::Current(n) => self.position as i128 + n as i128,
        };
        if position < 0 || position > self.length as i128 {
            return Err(io::Error::other("Seek outside source"));
        }
        self.position = position as u64;
        Ok(self.position)
    }
}
fn bytes(fs: &mut (impl Read + Seek), offset: u64, count: usize) -> Result<Vec<u8>> {
    fs.seek(SeekFrom::Start(offset)).map_err(err)?;
    let mut value = vec![0; count];
    fs.read_exact(&mut value).map_err(err)?;
    Ok(value)
}
fn le64(b: &[u8]) -> u64 {
    u64::from_le_bytes(b.try_into().expect("fixed-width field"))
}

fn geometry(source: &File, size: u64) -> Result<(u64, u64)> {
    let mut fs = Volume::new(source, 0, size);
    let boot = bytes(&mut fs, 0, 512)?;
    let ntfs_at = |fs: &mut Volume<'_>, base: u64, length: u64| -> Result<bool> {
        if base.checked_add(length).is_none_or(|end| end > size) || length < 4096 {
            return Ok(false);
        }
        Ok(bytes(fs, base, 512)?.get(3..11) == Some(b"NTFS    "))
    };
    if boot.get(3..11) == Some(b"NTFS    ") {
        return Ok((0, size));
    }
    if boot[510..512] != [0x55, 0xaa] {
        return Err(
            "This is not an NTFS volume image. Choose an NTFS drive or a raw NTFS image.".into(),
        );
    }
    // MBR partitions. Whole-disk images use 512-byte logical sectors here.
    for entry in boot[446..510].chunks_exact(16) {
        let base = u32::from_le_bytes(entry[8..12].try_into().unwrap()) as u64 * 512;
        let length = u32::from_le_bytes(entry[12..16].try_into().unwrap()) as u64 * 512;
        if entry[4] == 7 && ntfs_at(&mut fs, base, length)? {
            return Ok((base, length));
        }
    }
    // GPT: bounds-check table before examining Windows basic-data partitions.
    let header = bytes(&mut fs, 512, 512)?;
    if &header[..8] == b"EFI PART" {
        let lba = le64(&header[72..80]);
        let count = u32::from_le_bytes(header[80..84].try_into().unwrap()) as u64;
        let width = u32::from_le_bytes(header[84..88].try_into().unwrap()) as u64;
        if width >= 128 && width <= 4096 && count <= 4096 {
            let table = lba.checked_mul(512).ok_or("Invalid GPT table")?;
            for index in 0..count {
                let offset = table
                    .checked_add(index * width)
                    .ok_or("Invalid GPT table")?;
                let entry = bytes(&mut fs, offset, 128)?;
                if entry[..16].iter().all(|b| *b == 0) {
                    continue;
                }
                let start = le64(&entry[32..40]);
                let end = le64(&entry[40..48]);
                if let (Some(base), Some(length)) = (
                    start.checked_mul(512),
                    end.checked_sub(start)
                        .and_then(|n| n.checked_add(1))
                        .and_then(|n| n.checked_mul(512)),
                ) {
                    if ntfs_at(&mut fs, base, length)? {
                        return Ok((base, length));
                    }
                }
            }
        }
    }
    Err("No supported NTFS partition found. FAT/exFAT, APFS, ext, locked BitLocker and encrypted images are not supported by this undelete scan.".into())
}

fn bitmap(ntfs: &Ntfs, fs: &mut Volume<'_>) -> Result<Vec<u8>> {
    let file = ntfs.file(fs, 6).map_err(err)?;
    let item = file
        .data(fs, "")
        .ok_or("NTFS allocation bitmap missing")?
        .map_err(err)?;
    let attribute = item.to_attribute().map_err(err)?;
    let value = attribute.value(fs).map_err(err)?;
    if value.len() > MAX_BITMAP
        || value.len() < (ntfs.size() / ntfs.cluster_size() as u64).div_ceil(8)
    {
        return Err("Unsupported or truncated NTFS allocation bitmap (maximum 128 MiB).".into());
    }
    let mut data = Vec::with_capacity(value.len() as usize);
    value.attach(fs).read_to_end(&mut data).map_err(err)?;
    Ok(data)
}
fn free(runs: &[Run], cluster: u64, bitmap: &[u8], cancel: &AtomicBool) -> Result<()> {
    for run in runs {
        let Some(position) = run.physical else {
            continue;
        };
        if position % cluster != 0 || run.length % cluster != 0 {
            return Err("Unaligned NTFS data run".into());
        }
        for n in position / cluster..(position / cluster + run.length / cluster) {
            if n % 8192 == 0 {
                stop(cancel)?;
            }
            if bitmap
                .get((n / 8) as usize)
                .is_none_or(|b| b & (1 << (n % 8)) != 0)
            {
                return Err("Data clusters have been reused; recovery is blocked to avoid exporting another file's contents.".into());
            }
        }
    }
    Ok(())
}
fn path(ntfs: &Ntfs, fs: &mut Volume<'_>, file: &ntfs::NtfsFile<'_>) -> Result<(String, String)> {
    let name = file
        .name(fs, None, None)
        .ok_or("Original name missing")?
        .map_err(err)?;
    let filename = name.name().to_string_lossy();
    let mut parts = vec![filename.clone()];
    let mut parent = name.parent_directory_reference();
    let mut visited = std::collections::HashSet::new();
    for _ in 0..64 {
        if parent.file_record_number() == 5 {
            parts.reverse();
            return Ok((filename, format!("/{}", parts.join("/"))));
        }
        if !visited.insert(parent.file_record_number()) {
            break;
        }
        let Ok(directory) = ntfs.file(fs, parent.file_record_number()) else {
            break;
        };
        if directory.sequence_number() != parent.sequence_number() || !directory.is_directory() {
            break;
        }
        let Some(Ok(name)) = directory.name(fs, None, None) else {
            break;
        };
        parts.push(name.name().to_string_lossy());
        parent = name.parent_directory_reference();
    }
    parts.reverse();
    Ok((filename, format!("[unknown parent]/{}", parts.join("/"))))
}

impl Undelete {
    pub fn scan(
        source: &File,
        size: u64,
        cancel: &AtomicBool,
        mut progress: impl FnMut(u64, u64, usize),
    ) -> Result<Self> {
        if size < 4096 || size > (1u64 << 53) - 1 {
            return Err("Unsupported source size".into());
        }
        let (base, length) = geometry(source, size)?;
        let mut fs = Volume::new(source, base, length);
        let boot = bytes(&mut fs, 0, 512)?;
        let ntfs = Ntfs::new(&mut fs).map_err(err)?;
        if ntfs.size() > length {
            return Err("The NTFS volume extends beyond this image.".into());
        }
        let cluster = ntfs.cluster_size() as u64;
        let record_size = ntfs.file_record_size() as usize;
        if !(512..=65536).contains(&record_size) {
            return Err("Unsupported NTFS file-record size".into());
        }
        // Fail closed for complex MFTs the upstream reader does not support.
        let mft = ntfs.file(&mut fs, 0).map_err(err)?;
        for item in mft.attributes_raw() {
            if item.map_err(err)?.ty().map_err(err)? == NtfsAttributeType::AttributeList {
                return Err("This NTFS master file table uses extension records; this reader cannot safely undelete it yet.".into());
            }
        }
        let item = mft
            .data(&mut fs, "")
            .ok_or("NTFS master file table missing")?
            .map_err(err)?;
        let attribute = item.to_attribute().map_err(err)?;
        let total = attribute.value_length() / record_size as u64;
        let allocation = bitmap(&ntfs, &mut fs)?;
        let mut result = Self {
            scan: Scan {
                total_records: total,
                limited: total > MAX_RECORDS,
                ..Scan::default()
            },
            base,
            length,
            boot_hash: digest(&boot),
            cluster,
            record_size,
            plans: HashMap::new(),
        };
        for record in 16..total.min(MAX_RECORDS) {
            if cancel.load(Ordering::Relaxed) {
                result.scan.cancelled = true;
                break;
            }
            result.scan.records_examined = record + 1;
            if record % 128 == 0 {
                progress(record, total, result.scan.files.len());
            }
            let file = match ntfs.file(&mut fs, record) {
                Ok(f) => f,
                Err(_) => {
                    result.scan.skipped += 1;
                    continue;
                }
            };
            if file.flags().contains(NtfsFileFlags::IN_USE) || file.is_directory() {
                continue;
            }
            let Ok((name, original_path)) = path(&ntfs, &mut fs, &file) else {
                result.scan.skipped += 1;
                continue;
            };
            let id = format!("ntfs-{record}-{}", file.sequence_number());
            let Some(position) = file.position().value().map(|p| p.get()) else {
                continue;
            };
            let raw = bytes(&mut fs, position, record_size)?;
            // Extension records are not independent files.
            if le64(&raw[32..40]) != 0 {
                continue;
            }
            let mut candidate = DeletedFile {
                id: id.clone(),
                name,
                original_path,
                size: 0,
                offset: base + position,
                recoverable: false,
                evidence: String::new(),
            };
            let mut shortened_initialized_length = false;
            let outcome: Result<Plan> =
                (|| {
                    let item = file
                        .data(&mut fs, "")
                        .ok_or("Deleted data attribute no longer exists")?
                        .map_err(err)?;
                    let attribute = item.to_attribute().map_err(err)?;
                    candidate.size = attribute.value_length();
                    if candidate.size > MAX_FILE {
                        return Err("File exceeds the 64 GiB recovery limit.".into());
                    }
                    if attribute
                        .flags()
                        .intersects(NtfsAttributeFlags::COMPRESSED | NtfsAttributeFlags::ENCRYPTED)
                    {
                        return Err("Compressed or EFS-encrypted data is not supported.".into());
                    }
                    let mut plan = Plan {
                        record,
                        record_position: position,
                        record_hash: digest(&raw),
                        resident: None,
                        runs: vec![],
                        size: candidate.size,
                    };
                    match attribute.value(&mut fs).map_err(err)? {
                        NtfsAttributeValue::Resident(value) => {
                            plan.resident = Some(value.data().to_vec())
                        }
                        NtfsAttributeValue::NonResident(value) => {
                            // Windows can clear initialized length on deletion while
                            // original-size runs and contents still survive. Reading
                            // those unallocated stale bytes is the purpose of undelete;
                            // never describe them as guaranteed intact original data.
                            let attribute_offset = attribute
                                .position()
                                .value()
                                .ok_or("Missing attribute position")?
                                .get();
                            let header = bytes(&mut fs, attribute_offset, 64)?;
                            shortened_initialized_length = le64(&header[56..64]) < candidate.size;
                            let mut covered = 0u64;
                            for run in value.data_runs() {
                                if plan.runs.len() >= MAX_RUNS {
                                    return Err("Data-run safety limit reached".into());
                                }
                                let run = run.map_err(err)?;
                                let length = run.allocated_size();
                                let physical = run.data_position().value().map(|p| p.get());
                                if physical.is_some_and(|p| {
                                    p.checked_add(length).is_none_or(|end| end > ntfs.size())
                                }) {
                                    return Err("Data run outside source".into());
                                }
                                covered = covered.checked_add(length).ok_or("Data-run overflow")?;
                                plan.runs.push(Run { physical, length });
                            }
                            if covered < candidate.size {
                                return Err("Incomplete deleted data runs".into());
                            }
                            free(&plan.runs, cluster, &allocation, cancel)?;
                        }
                        NtfsAttributeValue::AttributeListNonResident(_) => return Err(
                            "File data spans NTFS extension records; recovery not supported yet."
                                .into(),
                        ),
                    }
                    Ok(plan)
                })();
            match outcome {
                Ok(plan) => {
                    candidate.recoverable = true;
                    candidate.evidence = if plan.resident.is_some() {
                        "Deleted NTFS record · resident contents retained in the file record".into()
                    } else {
                        format!("Deleted NTFS record · {} data runs · all referenced clusters currently unallocated",plan.runs.len())
                    };
                    if shortened_initialized_length {
                        candidate.evidence.push_str(" · initialized length is shorter/cleared; original valid length is unknown");
                    }
                    result.plans.insert(id, plan);
                }
                Err(reason) => candidate.evidence = reason,
            }
            result.scan.files.push(candidate);
            if result.scan.files.len() >= MAX_RESULTS {
                result.scan.limited = true;
                break;
            }
        }
        progress(result.scan.records_examined, total, result.scan.files.len());
        Ok(result)
    }
    pub fn validate(&self, source: &File, id: &str, cancel: &AtomicBool) -> Result<()> {
        stop(cancel)?;
        let plan = self
            .plans
            .get(id)
            .ok_or("This deleted entry cannot currently be recovered.")?;
        let mut fs = Volume::new(source, self.base, self.length);
        if digest(&bytes(&mut fs, 0, 512)?) != self.boot_hash
            || digest(&bytes(&mut fs, plan.record_position, self.record_size)?) != plan.record_hash
        {
            return Err("NTFS metadata changed. Scan the source again.".into());
        }
        let ntfs = Ntfs::new(&mut fs).map_err(err)?;
        let file = ntfs.file(&mut fs, plan.record).map_err(err)?;
        if file.flags().contains(NtfsFileFlags::IN_USE) {
            return Err("The deleted file record was reused. Scan again.".into());
        }
        if !plan.runs.is_empty() {
            free(&plan.runs, self.cluster, &bitmap(&ntfs, &mut fs)?, cancel)?;
        }
        Ok(())
    }
    /// Read only scan-allowlisted file bytes, reconstructing fragmented and sparse runs.
    pub fn read(&self, source: &File, id: &str, offset: u64, output: &mut [u8]) -> Result<usize> {
        let plan = self
            .plans
            .get(id)
            .ok_or("Unknown or unrecoverable deleted file")?;
        let count = output.len().min(plan.size.saturating_sub(offset) as usize);
        if let Some(data) = &plan.resident {
            output[..count].copy_from_slice(
                &data[offset.min(data.len() as u64) as usize
                    ..offset.min(data.len() as u64) as usize + count],
            );
            return Ok(count);
        }
        let mut logical = 0;
        let mut copied = 0;
        let mut fs = Volume::new(source, self.base, self.length);
        for run in &plan.runs {
            let at = offset + copied as u64;
            if at >= logical + run.length {
                logical += run.length;
                continue;
            }
            if copied == count {
                break;
            }
            let within = at.saturating_sub(logical);
            let n = (count - copied).min((run.length - within) as usize);
            if let Some(position) = run.physical {
                fs.seek(SeekFrom::Start(position + within)).map_err(err)?;
                fs.read_exact(&mut output[copied..copied + n])
                    .map_err(err)?;
            } else {
                output[copied..copied + n].fill(0);
            }
            copied += n;
            logical += run.length;
        }
        if copied != count {
            return Err("Deleted file has incomplete data runs".into());
        }
        Ok(count)
    }
}

#[cfg(feature = "android-jni")]
mod android;
