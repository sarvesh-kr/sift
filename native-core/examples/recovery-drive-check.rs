//! CI only: recover known files deleted from a newly created disposable NTFS VHD.
#[cfg(windows)]
fn main() {
    use std::{path::PathBuf, sync::atomic::AtomicBool};
    let args: Vec<_> = std::env::args().collect();
    assert_eq!(
        args.len(),
        3,
        "expected test drive letter and different-volume output directory"
    );
    let cancel = AtomicBool::new(false);
    let mut session = sift_core::recovery::RecoverySession::open_drive(&args[1]).unwrap();
    let report = session.scan(&cancel, |_| {}).unwrap();
    let expected = ["sift-permanently-deleted.txt", "sift-deleted-payload.bin"];
    assert!(!report.files.iter().any(|f| f.name == "sift-live-keep.txt"));
    let ids: Vec<_> = expected
        .iter()
        .map(|name| {
            let file = report
                .files
                .iter()
                .find(|f| f.name == *name)
                .expect("deleted file record must be found");
            assert!(file.recoverable, "{}", file.evidence);
            file.id.clone()
        })
        .collect();
    // Same-volume writes must fail before creating output.
    assert!(session
        .export(
            &PathBuf::from(format!("{}:\\", args[1])),
            &ids,
            &cancel,
            |_| {}
        )
        .is_err());
    let result = session
        .export(&PathBuf::from(&args[2]), &ids, &cancel, |_| {})
        .unwrap();
    assert!(result.failed.is_empty());
    assert_eq!(result.exported.len(), 2);
    for file in &result.exported {
        let bytes = std::fs::read(&file.path).unwrap();
        if file.candidate.name.ends_with(".txt") {
            assert_eq!(
                bytes,
                b"Permanently deleted from an NTFS volume; no recycle bin.\n"
            );
        } else {
            assert_eq!(
                bytes,
                (0..131072)
                    .map(|i| ((i * 17 + 31) % 251) as u8)
                    .collect::<Vec<_>>()
            );
        }
        assert_eq!(file.candidate.sha256.len(), 64);
    }
    println!("Direct NTFS drive scan: deleted resident and nonresident files recovered byte-for-byte; live file excluded; same-volume output refused.");
}
#[cfg(not(windows))]
fn main() {}
