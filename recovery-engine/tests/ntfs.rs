use sift_undelete::Undelete;
use std::{
    fs::File,
    io::{Read, Seek, SeekFrom, Write},
    sync::atomic::AtomicBool,
};
fn fixture() -> tempfile::NamedTempFile {
    let mut image = tempfile::NamedTempFile::new().unwrap();
    let mut gzip = flate2::read::GzDecoder::new(
        &include_bytes!("../../recovery-fixtures/ntfs-deleted.img.gz")[..],
    );
    std::io::copy(&mut gzip, &mut image).unwrap();
    image.flush().unwrap();
    image
}

#[test]
fn reused_clusters_are_blocked_both_at_scan_and_before_copy() {
    use ntfs::{Ntfs, NtfsReadSeek};
    let image = fixture();
    let cancel = AtomicBool::new(false);
    let source = File::open(image.path()).unwrap();
    let engine = Undelete::scan(
        &source,
        source.metadata().unwrap().len(),
        &cancel,
        |_, _, _| {},
    )
    .unwrap();
    let file = engine
        .scan
        .files
        .iter()
        .find(|f| f.name == "fragmented.bin")
        .unwrap();
    let mut reader = &source;
    let ntfs = Ntfs::new(&mut reader).unwrap();
    let inode = ntfs.file(&mut reader, 66).unwrap();
    let item = inode.data(&mut reader, "").unwrap().unwrap();
    let attr = item.to_attribute().unwrap();
    let data = attr.value(&mut reader).unwrap();
    let cluster = data.data_position().value().unwrap().get() / ntfs.cluster_size() as u64;
    let bitmap = ntfs.file(&mut reader, 6).unwrap();
    let item = bitmap.data(&mut reader, "").unwrap().unwrap();
    let attr = item.to_attribute().unwrap();
    let mut value = attr.value(&mut reader).unwrap();
    value
        .seek(&mut reader, SeekFrom::Start(cluster / 8))
        .unwrap();
    let position = value.data_position().value().unwrap().get();
    let mut byte = [0];
    reader.seek(SeekFrom::Start(position)).unwrap();
    reader.read_exact(&mut byte).unwrap();
    byte[0] |= 1 << (cluster % 8);
    let mut writer = std::fs::OpenOptions::new()
        .write(true)
        .open(image.path())
        .unwrap();
    writer.seek(SeekFrom::Start(position)).unwrap();
    writer.write_all(&byte).unwrap();
    writer.sync_all().unwrap();
    assert!(engine
        .validate(&source, &file.id, &cancel)
        .unwrap_err()
        .contains("reused"));
    let rescanned = Undelete::scan(
        &source,
        source.metadata().unwrap().len(),
        &cancel,
        |_, _, _| {},
    )
    .unwrap();
    assert!(
        !rescanned
            .scan
            .files
            .iter()
            .find(|f| f.name == "fragmented.bin")
            .unwrap()
            .recoverable
    );
    assert!(
        rescanned
            .scan
            .files
            .iter()
            .find(|f| f.name == "meeting-notes.txt")
            .unwrap()
            .recoverable
    );
}
#[test]
fn windows_style_cleared_initialized_length_retains_raw_recovery_with_explicit_evidence() {
    use ntfs::Ntfs;
    let image = fixture();
    let source = File::open(image.path()).unwrap();
    let cancel = AtomicBool::new(false);
    let mut reader = &source;
    let ntfs = Ntfs::new(&mut reader).unwrap();
    let inode = ntfs.file(&mut reader, 66).unwrap();
    let item = inode.data(&mut reader, "").unwrap().unwrap();
    let attr = item.to_attribute().unwrap();
    let position = attr.position().value().unwrap().get();
    let mut writer = std::fs::OpenOptions::new()
        .write(true)
        .open(image.path())
        .unwrap();
    writer.seek(SeekFrom::Start(position + 56)).unwrap();
    writer.write_all(&0u64.to_le_bytes()).unwrap();
    writer.sync_all().unwrap();
    let engine = Undelete::scan(
        &source,
        source.metadata().unwrap().len(),
        &cancel,
        |_, _, _| {},
    )
    .unwrap();
    let file = engine
        .scan
        .files
        .iter()
        .find(|f| f.name == "fragmented.bin")
        .unwrap();
    assert!(file.recoverable);
    assert!(file.evidence.contains("original valid length is unknown"));
    let mut bytes = vec![0; file.size as usize];
    engine.read(&source, &file.id, 0, &mut bytes).unwrap();
    let block: Vec<u8> = (0..4096).map(|i| ((i * 17 + 31) % 251) as u8).collect();
    assert_eq!(bytes, block.repeat(12));
}
#[test]
fn reused_file_records_and_changed_boot_sectors_are_rejected() {
    let image = fixture();
    let cancel = AtomicBool::new(false);
    let source = File::open(image.path()).unwrap();
    let engine = Undelete::scan(
        &source,
        source.metadata().unwrap().len(),
        &cancel,
        |_, _, _| {},
    )
    .unwrap();
    let file = engine
        .scan
        .files
        .iter()
        .find(|f| f.name == "meeting-notes.txt")
        .unwrap();
    let mut writer = std::fs::OpenOptions::new()
        .write(true)
        .open(image.path())
        .unwrap();
    writer.seek(SeekFrom::Start(file.offset + 22)).unwrap();
    writer.write_all(&1u16.to_le_bytes()).unwrap();
    writer.sync_all().unwrap();
    assert!(engine.validate(&source, &file.id, &cancel).is_err());
    let scan = Undelete::scan(
        &source,
        source.metadata().unwrap().len(),
        &cancel,
        |_, _, _| {},
    )
    .unwrap();
    assert!(!scan
        .scan
        .files
        .iter()
        .any(|f| f.name == "meeting-notes.txt"));
}
#[test]
fn whole_disk_mbr_offsets_and_cancellation_are_supported_without_source_writes() {
    let volume = fixture();
    let data = std::fs::read(volume.path()).unwrap();
    let mut disk = tempfile::NamedTempFile::new().unwrap();
    let mut prefix = vec![0; 1024 * 1024];
    prefix[510..512].copy_from_slice(&[0x55, 0xaa]);
    prefix[450] = 7;
    prefix[454..458].copy_from_slice(&2048u32.to_le_bytes());
    prefix[458..462].copy_from_slice(&((data.len() / 512) as u32).to_le_bytes());
    disk.write_all(&prefix).unwrap();
    disk.write_all(&data).unwrap();
    disk.flush().unwrap();
    let source = File::open(disk.path()).unwrap();
    let cancel = AtomicBool::new(false);
    let engine = Undelete::scan(
        &source,
        source.metadata().unwrap().len(),
        &cancel,
        |_, _, _| {},
    )
    .unwrap();
    assert_eq!(engine.scan.files.len(), 2);
    let file = engine
        .scan
        .files
        .iter()
        .find(|f| f.name == "fragmented.bin")
        .unwrap();
    engine.validate(&source, &file.id, &cancel).unwrap();
    let mut crossing = vec![0; 100];
    engine.read(&source, &file.id, 4050, &mut crossing).unwrap();
    let block: Vec<_> = (0..4096).map(|i| ((i * 17 + 31) % 251) as u8).collect();
    assert_eq!(crossing, block.repeat(2)[4050..4150]);
    cancel.store(true, std::sync::atomic::Ordering::Relaxed);
    let stopped = Undelete::scan(
        &source,
        source.metadata().unwrap().len(),
        &cancel,
        |_, _, _| {},
    )
    .unwrap();
    assert!(stopped.scan.cancelled);
    assert!(stopped.scan.files.is_empty());
}
#[test]
fn recovers_really_deleted_resident_and_fragmented_files_with_original_paths() {
    let image = fixture();
    let source = File::open(image.path()).unwrap();
    let before = sift_undelete::digest(&std::fs::read(image.path()).unwrap());
    let cancel = AtomicBool::new(false);
    let engine = Undelete::scan(
        &source,
        source.metadata().unwrap().len(),
        &cancel,
        |_, _, _| {},
    )
    .unwrap();
    for f in &engine.scan.files {
        eprintln!("{f:?}");
    }
    assert_eq!(engine.scan.files.len(), 2);
    assert!(!engine
        .scan
        .files
        .iter()
        .any(|f| f.name.starts_with("keep-")));
    let note = engine
        .scan
        .files
        .iter()
        .find(|f| f.name == "meeting-notes.txt")
        .unwrap();
    assert!(note.recoverable);
    assert_eq!(note.original_path, "/Projects/meeting-notes.txt");
    engine.validate(&source, &note.id, &cancel).unwrap();
    let mut data = vec![0; note.size as usize];
    engine.read(&source, &note.id, 0, &mut data).unwrap();
    assert_eq!(
        data,
        b"These notes were permanently deleted, outside any recycle bin.\n"
    );
    let large = engine
        .scan
        .files
        .iter()
        .find(|f| f.name == "fragmented.bin")
        .unwrap();
    assert!(large.recoverable);
    assert!(large.evidence.contains("2 data runs"), "{}", large.evidence);
    let mut data = vec![0; large.size as usize];
    engine.read(&source, &large.id, 0, &mut data).unwrap();
    let expected: Vec<u8> = (0..4096).map(|i| ((i * 17 + 31) % 251) as u8).collect();
    assert_eq!(data, expected.repeat(12));
    engine.validate(&source, &large.id, &cancel).unwrap();
    assert!(engine
        .read(&source, "ntfs-unscanned", 0, &mut data)
        .is_err());
    assert_eq!(
        sift_undelete::digest(&std::fs::read(image.path()).unwrap()),
        before
    );
}
