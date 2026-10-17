# Recovery in Sift 0.6.1 desktop / 0.4 Android

Recovery means finding lost or deleted file contents. The recovery workspace does not open a recycle bin. Cleaning and Sift trash remain in the cleaning workspace.

## Choose the storage first

Desktop cleaning uses the native folder/drive picker. Desktop recovery lists local drives and connected devices supplied by Windows, `lsblk` on Linux, or `diskutil` on macOS. Android cleaning and recovery list mounted internal shared storage and removable volumes exposed by Android's StorageManager. Unmounted, locked or Android-inaccessible devices cannot be scanned through these pickers. Disconnecting a device can invalidate a scan; reconnect and rescan.

| Operation | What it actually reads | Important limits |
| --- | --- | --- |
| Desktop NTFS undelete | Deleted NTFS master-file-table records and their data runs; resident, fragmented and sparse contents; surviving original names and directory paths | Reused clusters are blocked. Compressed/EFS data, NTFS extension records and complex MFT attribute lists are unsupported. Missing parent paths are marked unknown. |
| Desktop deep signature scan | Readable bytes of the selected device, independent of filesystem | JPEG, PNG, WebP, PDF, classic ZIP and WAV; contiguous files only, maximum 512 MiB each. Existing files can appear. Original names, paths and deletion status are unknown. |
| Android non-root phone/card scan | Accessible shared cache, hidden and `.nomedia` folders; supported embedded file contents | Surviving copies, sometimes only thumbnails. No deleted-sector or app-private access. Original deletion status is unknown; results can include copies of files that still exist. Dimensions are recorded for bounded decodable images. |
| Android rooted deep scan (experimental) | The selected mounted internal userdata or supported card block device, using explicit `su` consent and a read-only native helper | Same six contiguous formats as deep scanning. Root does not undo file-based encryption, TRIM or overwrites. Original names, paths and deletion status are unknown. Root-manager/device compatibility needs real-device qualification. |
| Advanced image tools | A user-selected local NTFS image, or separately selected signature carving of an image | Optional specialist tools; no image is needed for direct device scanning. Sift does not create disk images. |

## Permissions and source protection

Windows drive scanning requires administrator access on standard installations. **Enable drive access** opens a recovery-only process through Windows UAC; its backend refuses cleanup requests. Linux and macOS device access depends on OS disk-read permissions. Where needed, use your administrator tools to launch the installed native executable with `--sift-recovery-only`. Sift does not automatically elevate on these platforms. macOS privacy settings may also restrict disk access.

Android non-root scanning requires All files access for the selected shared volume. This permission does not grant raw-sector access. Rooted scanning is an explicit separate action; Sift never roots, unlocks or flashes a device. The installed helper accepts a validated storage identifier, resolves its mounted device itself, opens it read-only and implements bounded reads only. It has no source write, deletion, destination or arbitrary command API. No network permission is added.

Active sources can change through the operating system or other apps. NTFS metadata and allocation are rechecked before and after recovery. Exported bytes are compared with destination read-back and a second source read. Signature and non-root cached candidates are SHA-256 checked against the scanned byte ranges. Stable images remain useful for forensic work but are optional.

## Review and save

Results support categories, search, list/grid views, local image thumbnails, individual selection, multiple selection, all filtered matches, evidence details, progress and cancellation. Entries that cannot safely be recovered remain inspectable and cannot be selected. Original paths are evidence, never output instructions. Output filenames are sanitized and record IDs prevent collisions. Copies go into a new category-organized recovery folder with a JSON manifest recording evidence, original paths where known, hashes and per-file outcomes. Original directory trees are recorded, not recreated automatically.

Direct Windows exports refuse the source volume, including aliases and mount points. Linux exports refuse devices in the source physical-device family. Direct macOS exports require a different external disk. Rooted Android exports require a different, identifiable local storage volume and refuse the source before creating output. Non-root phone scanning can save copies through Android's destination picker; another device is preferable.

Hashes verify the bytes Sift read and wrote; they cannot establish that those bytes equal a lost original whose hash is unknown. Zeroed, partly overwritten or encrypted data can be unrecoverable. No tool can guarantee complete recovery.

## Bounded work

NTFS: 2 million records, 10,000 deleted entries, 64 GiB per file, 128 MiB allocation bitmap, 65,536 data runs per file. Signature scanning: 10,000 candidates, 50,000 header attempts, 512 MiB per file and a bounded validation-read budget. Non-root Android: 100,000 visited entries, 1,024 cache containers, 256 MiB per container, 2 GiB of containers, 10,000 distinct candidates. Limits and cancellation produce partial reports. Bins, links, Android app-private directories and Sift recovery output directories are excluded from the non-root search.

## Verification

`recovery-fixtures/ntfs-deleted.img.gz` is a real, 32 MiB NTFS filesystem created with NTFS-3G. `scripts/make-ntfs-fixture.c` writes and deletes a resident text file and a fragmented binary file through NTFS-3G's filesystem APIs. Live control files remain. It is development-only GPL-licensed tooling, never linked into the MIT application. The compressed image contains synthetic data only and is excluded from release APKs.

The shared Rust engine verifies original paths, exact bytes across fragmented runs, live-file exclusion, reused-cluster refusal, changed-record refusal, partition offsets, cancellation and source preservation. The native exporter additionally verifies recovered files, hashes and manifests. Windows CI creates a disposable VHD, permanently deletes known files through DeleteFile, recovers directly from that drive and checks same-volume output refusal. Android instrumentation exercises the same NTFS engine, non-root on-device cache discovery and verified SAF exports. Root-helper protocol tests exercise bounded cross-sector reads and source preservation; protocol tests are not qualification of all root managers or encrypted devices.
