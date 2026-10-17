# Recovery preview: desktop 0.5.0 / Android 0.3.0

Sift opens with **Clean data** and **Recover data** on every supported platform.
The existing storage, duplicate, visual browsing, and cleanup tools live under
Clean data. Recover data is a separate workspace. Desktop cleaning state remains
in memory when switching modes; Android retains its model and saved navigation.
Closing the app discards image-scan results; exported files and manifests persist.

## Two recovery paths

1. **Trash:** desktop opens the operating system's Recycle Bin / trash so its
   file manager can restore retained files with original names and paths. Sift
   does not enumerate or silently restore unrelated system-trash entries.
   Android opens Sift's existing durable trash/restore interface. Gallery / Photos
   trash must be restored in that app. Uninstalling Sift deletes Sift trash.
2. **Disk-image carving:** choose a stable, local, regular raw image file
   (`.img`, `.dd`, `.raw`, or an equivalent uncompressed byte stream). Sift reads
   the image and identifies supported contiguous file ranges. No live raw-disk
   access, elevated privileges, rooting, or disk-image creation is implemented.

Stop writing to the affected drive before attempting recovery. Make a disk image
with an appropriate imaging tool, then work from that image. Save exports to a
different physical drive. Do not install Sift onto the disk you hope to recover.
Failing hardware needs a separate acquisition strategy; this app does not repair
physical damage or handle unreadable sectors like a forensic acquisition tool.

## Actual format coverage and evidence

| Format | Evidence checked | What is not established |
| --- | --- | --- |
| JPEG | Frame/scan marker structure, segment bounds, stuffed/restart markers, EOI | Entropy decoding and visual integrity |
| PNG | IHDR-first structure, IDAT, bounded chunks, every chunk CRC, IEND | Successful image decoding / full PNG semantic conformance |
| WebP | RIFF declared length, chunk bounds and image/frame payload presence | Codec integrity or full animation reconstruction |
| WAV | RIFF declared length, chunk bounds, format and data chunks | Audio decoding or semantic validity |
| PDF | PDF version header and first `%%EOF` footer | Document structure, readability, encryption, incremental revisions after that footer |
| ZIP | Single-volume classic EOCD, central-directory bounds, referenced local headers and data bounds | Payload CRCs, decompression, encryption, ZIP64, split archives |

Archives remain archives; contents are never extracted or executed. OOXML, APK,
and other ZIP containers may appear as `.zip`, with no original subtype inferred.
There is no support in this release for MP4/MOV, HEIC/AVIF, MP3, RAW camera formats,
fragment reassembly, filesystem metadata undelete, NTFS/FAT/exFAT/APFS/ext parsing,
original filenames, deleted-directory trees, encrypted-volume unlocking, backups,
or recoverable-file probability scores. A signature result can be a current file
or a false positive: **deletion status and readability are unknown**. Nested
signatures inside a successfully identified outer file are not listed separately.

A normal Android app cannot read deleted sectors of encrypted internal storage.
All files access does not change this. Image recovery instead uses Android's
Storage Access Framework document picker with a read-only descriptor; no broad
storage permission is required for this path. The source must be local and
seekable. Source metadata/descriptor identity is retained across configuration
changes. An app/process restart requires choosing and scanning the image again.
SSD TRIM, overwriting, encryption, and damage can make recovery impossible.
No superiority over professional recovery tools or universal recovery is claimed.

## Results, previews, and verified copies

- Generated filenames contain source byte offsets. Categories group Images,
  Documents, Archives, and Audio. Search name, format, or hexadecimal offset.
- List / Grid views, individual selection, filtered select-all, and one-file
  recovery are available. Details show exact offset, size, format evidence, and
  SHA-256. In-memory image previews are lazy and bounded; non-image candidates
  show their type. Previews never render HTML, decompress archives, or execute data.
- Select a destination explicitly. Sift creates a new unique recovery folder
  with category subfolders. Android declares the corresponding image/document/
  archive/audio MIME type so providers can retain usable file extensions. It never
  reconstructs unknown original paths.
- Each copy is compared with the SHA-256 recorded during the scan, flushed, and
  read back. A copy is reported successful only after verification. This verifies
  copied bytes, not whether the recovered document is meaningful or will open.
- Desktop uses exclusive file creation and OS non-replacing publication, including
  Linux `RENAME_NOREPLACE`, macOS `RENAME_EXCL`, and Windows `MoveFileW`. There is
  no replacing fallback if the filesystem rejects that operation. Android uses
  fresh DocumentsProvider documents, non-truncating opens, empty-file/type checks,
  and source descriptor-identity checks before writing. Failed or unverified
  destination opens never trigger document deletion. Providers must support
  regular local files, read-back, and flush; unsupported destinations report errors.
- Failures are per file. Failed incomplete copies are removed when possible;
  removal failures are reported. Stop preserves completed verified copies;
  unprocessed selections remain selected. Source bytes are never written by the
  recovery engine. `Close image` releases the evidence handle and result set.
- `recovery-manifest.json` records method/schema version, source label/size,
  requested IDs, offsets, sizes, hashes, format evidence, and outcomes. It contains
  private source/destination information; share it deliberately. A manifest-save
  failure is reported independently of successfully copied files.

## Resource limits and implementation

The Rust desktop engine and Kotlin Android engine consume the same synthetic
cross-platform golden image, expected offsets, sizes, and SHA-256 values.
Source scans use 1 MiB blocks with signature overlap. Format probing and hashing
are streaming; entropy/footer searches reuse bounded windows. No image-size
buffer or temporary image copy is created. Native work stays off the UI thread.

Limits: 512 MiB per candidate, 64 MiB per PNG chunk, 10,000 results, 50,000 header
attempts, 100,000 format chunks, and a validation-read budget of four image lengths
plus 512 MiB. A limit or cancellation produces explicitly partial results. This
is a bounded preview, not a claim to examine every recoverable byte or format.
Image preview input caps at 16 MiB and output at 384 px. Desktop bounds decoder
allocation at 64 MiB and native preview concurrency at two; Android uses sampled
ImageDecoder, two preview workers, and a 16 MiB bitmap LRU cache. The desktop UI
shares the lazy bounded preview cache/queue with cleaning, under a separate
allowlisted recovery command. No arbitrary image ranges are accepted from the UI.

Source identity, length, and timestamps are checked around work. Selected ranges
are hashed at scan and export to detect changed content. Windows holds the source
with read sharing only. These measures do not turn a live, concurrently modified
image into a forensic snapshot; use stable offline evidence. Normal path-based
filesystem APIs cannot eliminate all races with unrelated local processes.

## Validation boundaries

Native tests cover exact six-format golden boundaries/hashes, source preservation,
scan-buffer overlap, PNG corruption, oversized RIFF, changed/replaced sources,
selection allowlists, cancellation, verified exports/manifests, bounded previews,
JPEG search-window reuse, and no-replacement publication. Kotlin JVM tests consume
the same image and exercise exact copies, changes, corruption, cancellation, and
JPEG marker density. Android instrumented tests use an isolated **debug-only**
DocumentsProvider to exercise real SAF descriptors, read-back failure, source URI
aliases, Compose selection/layout/details, and trash navigation. Permission-free image recovery runs in a separate
invocation with All files access already denied; revoking this permission during
instrumentation terminates Android’s running app process. No test provider
or sample image is included in release APKs. Interface tests verify launcher,
state retention, native command arguments, filters, one-file export, and focus.

Emulators, fixtures, and browser tests do not establish physical-device recovery
rates, OEM compatibility, full desktop GUI qualification, or competitive recovery
benchmarks. See [validation records](../VALIDATION.md) and [product goals](../PRODUCT-GOALS.md).
