# Sift: the standard to beat

The goal is a leading **storage analysis and cleanup** product. The current release is not a validated replacement for every CCleaner Professional feature, nor has it beaten the following products in controlled tests.

## Comparison set

Reviewed official documentation on 16 October 2026:

- [CCleaner Professional](https://www.ccleaner.com/ccleaner/professional): compare storage-cleanup coverage and everyday cleanup workflows. Its broader optimization, driver, registry, browser, and cloud features are separate capabilities; this app does not currently implement them.
- [WizTree](https://diskanalyzer.com/): compare inventory speed, treemap navigation, and hard-link accounting. WizTree documents direct NTFS MFT scanning; Sift currently uses directory traversal and does not claim the same scan speed.
- [TreeSize duplicate search](https://manuals.jam-software.com/treesize/EN/duplicate_file_search.html): compare duplicate investigation, exclusion controls, and management of multiple locations.

These are product-documentation comparisons, not independently measured performance results.

## Delivered in 0.2

| Area | Behavior |
| --- | --- |
| Cleanup Center | Suggestions explain the matching evidence, modification-age threshold, and likely consequence. Personal media and archives are explicitly treated as personal files. |
| Folder investigation | Drill through directories, compare branch totals, and scope a searchable file inventory to any branch. |
| Duplicate pipeline | Same-size candidate filtering → sampled-content filtering → complete BLAKE3 hashing → separate byte verification. Hard-link identities are excluded where available. |
| Retained copy | Choose a keeper and select extras. Both UI review and the native cleanup path prevent removal of every detected copy. The retained copy is revalidated and byte-compared before each duplicate is moved. |
| Volume context | Total and available space for the scanned folder's host volume, refreshed after cleanup. Logical inventory sizes remain separately labeled. |
| Privacy and deletion | No upload or account. Explicit selection and review, default OS trash with no deletion fallback, typed confirmation for permanent actions, changed-file checks, and best-effort overwrite with read-back verification. |

## Next engineering milestones

1. **Windows runtime qualification:** test actual WebView2 rendering, native dialogs, Recycle Bin behavior, locked files, system directories, long paths, junctions, and cloud placeholders. Add signing before a public release.
2. **Complete storage accounting:** report logical size, allocated size, hard links, sparse files, compression, cloud-only content, and measured available volume space separately. Verify platform-native semantics rather than infer physical savings from file length.
3. **Large inventories:** incremental IPC, a disk-backed index, virtualized rows, persistent exclusions, multiple scan roots, and recovery after interrupted scans. The current 500,000-file cap remains until this is validated.
4. **Windows acceleration:** an optional read-only NTFS metadata engine with appropriate privileges and a traversal fallback. Validate freshness, accessibility, reparse-point handling, and volume boundaries against the normal engine before using it for cleanup decisions.
5. **Application-aware cleanup:** versioned adapters for specific application caches, OS-owned cleanup APIs, detection of running applications, preservation of user databases and credentials, and integration fixtures per application version. Matching a folder named `cache` alone is not sufficient evidence for automatic deletion.
6. **Recovery and receipts:** persistent operation receipts and supported restore integration for each OS. Moving to trash is not the same as freeing capacity, and neither is a substitute for backup.
7. **Deeper content analysis:** opt-in similar-image comparison and media/document metadata, with confidence labels. Similarity must remain separate from exact duplicates; semantic AI should not decide that a personal file is disposable.

## Release gates for a superiority claim

Run the same datasets on the same real Windows machine and record the exact app versions, permissions, filesystem, disk type, OS build, exclusions, and cache conditions. Repeat cold and warm runs and publish the median and variation. Keep application cache cleanup and whole-drive inventory results separate.

| Measurement | Required evidence |
| --- | --- |
| Inventory speed | Comparable 100k- and 1m-file drives, first-result latency and completion latency, with equivalent coverage. A partial or inaccessible scan must never be presented as a full-drive speed result. |
| Memory and responsiveness | Whole process tree, including the webview: private working set at idle and peak, UI search latency, and cancellation latency. |
| Duplicate accuracy | Known duplicate corpus with same-size different files, renamed originals, changed files, hard links, permissions errors, and interrupted comparisons. No false positive in the corpus and no last-copy loss in cleanup fixtures. |
| Cleanup coverage | Explicit application-version fixtures and known expected disposable files, alongside protected user data. Count what is correctly identified, missed, and incorrectly suggested. |
| Space recovery | Measure OS-reported available space before and after cleanup and controlled trash emptying. Report logical removed bytes separately; account for unrelated disk activity. |
| Usability and recovery | Timed real-user tasks, keyboard/screen-reader checks, failed-operation handling, and restore tests on each supported platform. |

Only claim an advantage for the metric and scenario actually measured. “Best in class” is a product goal until the necessary public comparisons and reliability tests support it.

## Recovery qualification after on-device recovery

Desktop 0.6.1 / Android 0.4.0 add selected-storage recovery, a shared NTFS
metadata undelete engine, independent deep scanning, Android non-root surviving
cache recovery and an experimental rooted read-only helper. Exact delivered
capabilities and exclusions are in `docs/RECOVERY.md`.

Remaining work includes broader filesystem metadata support, compressed/encrypted
NTFS cases, extension records, safe partial-file recovery, more formats and larger
carved candidates, stronger acquisition integration and wider real-device/root
manager qualification. A signature, cached thumbnail or generated name does not
prove that an original deleted file was restored.

Before claiming competitive recovery quality, benchmark against established tools
on reproducible labeled NTFS/FAT/exFAT/APFS/ext/F2FS corpora with intact, deleted,
fragmented, overwritten, encrypted and TRIM-affected files. Measure false
positives, byte-exact recall, readable-file rate, path/name fidelity, scan speed,
peak memory, cancellation latency, source writes and export integrity. Publish
hardware, access level, ground truth and failures. No superiority or universal
recovery claim follows from passing the current functional fixtures.
