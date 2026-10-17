# Sift 0.6.1 — storage-first recovery

Choose a local drive or connected device directly. Disk-image tools are collapsed under Advanced, and completed scans bring their results into view. Source-oriented labels apply to drives and images, and deep-scan progress describes read-only access without claiming a live disk cannot change. Includes the NTFS undelete and independent deep scanning delivered in 0.6.0 below.

The interface, native safety suites and direct Windows deleted-file recovery checks gate all four platform builds. See [capabilities and limits](https://github.com/sarvesh-kr/sift/blob/main/docs/RECOVERY.md). Installers remain unsigned and Mac packages are not notarized.

---

# Sift 0.6.0 — device recovery preview

Recovery now starts with selecting a local drive or connected storage device. NTFS mode reads deleted filesystem records, retains original names and surviving folder paths, follows resident/fragmented/sparse contents and blocks reused clusters. A separate deep scan searches six contiguous file formats on readable devices and labels deletion status as unknown. Windows can open a UAC-authorized recovery-only window; cleanup is disabled in that process. Existing cleaner, previews, selection and reviewed cleanup remain available in the normal app.

Exports create new categorized copies, sanitize names, retain original paths in the manifest, verify hashes and destination read-back, and reject source-device destinations. Disk-image tools remain optional. See [recovery capabilities and limits](https://github.com/sarvesh-kr/sift/blob/main/docs/RECOVERY.md). Raw-device permissions, encryption, TRIM and overwrites can prevent recovery. This is a preview, not a guarantee of professional-tool parity. Installers remain unsigned/not notarized.

---

**0.5.0: Clean data / Recover data workspaces.** A new start screen keeps the
existing storage explorer and cleanup tools under Clean data. Recover data opens
system trash or scans a local, regular raw disk image for contiguous JPEG, PNG,
WebP, PDF, ZIP, and WAV candidates. Browse categories, List / Grid, image previews,
byte offsets, format evidence, and SHA-256. Select one file, several, or all
filtered matches; save categorized copies into a new folder with a JSON manifest.
Exports compare scan hashes and read copies back, with exclusive creation,
non-replacing publication, cancellation, and per-file outcomes. No source writes.

**Recovery preview limits:** no live raw disks, image creation, filesystem
metadata undelete, original names/folders, fragmented-file reconstruction,
decryption, or universal recovery. Candidates may be existing files or false
positives. PDF header/footer evidence and ZIP directory checks do not prove
readability or payload integrity. Six formats, 512 MiB per file, 10,000 candidates,
and bounded probing; limit/stopped scans are labeled partial. SSD TRIM,
overwriting, or encryption may make recovery impossible. Save recovered copies
to another physical drive. See `docs/RECOVERY.md` for exact scope and limits.
No superiority over professional recovery software has been established.

**0.4.0: recognize your files before cleaning up.** Choose List or Grid, with
local image thumbnails and plain-text/source previews. Windows can additionally
use its available thumbnail handlers for video, PDF, Office, and newer image
formats. File details show a larger preview. Views are available in file lists,
folder browsing, duplicate-copy comparison, and cleanup review. Your saved layout,
filters, selections, and all explicit cleanup confirmations are retained.

Previews are read-only, lazy, memory/concurrency bounded, and restricted to the
current scan. They never launch a file or render HTML. Changed, cloud-placeholder,
oversized, corrupt, or unsupported files show a file-type fallback. Windows codec
and document-handler availability varies. Image support on Mac/Linux includes
JPEG, PNG, WebP, GIF (first frame), BMP, and ICO; video/PDF/Office previews are not
bundled there. Text reads are capped at 4 KB. Thumbnail creation reads contents
only when browsing; scanning remains metadata-only.

Sift is a local desktop storage explorer and cleanup assistant. This preview includes an explanation-based Cleanup Center, folder drill-down, exact duplicate comparison, retained-copy protection, and explicit cleanup actions.

**0.3.0 cleanup actions:** choose Recycle Bin / trash (default), permanent deletion, or best-effort overwrite-and-delete for one file, multiple files, or all matches in a filtered view. Permanent actions require an exact typed action and file count. Removing every known duplicate copy requires an additional explicit acknowledgment.

Overwrite uses one zero pass, flushes, reads every byte back, then deletes the validated file. It refuses hard links and Windows reparse/cloud, compressed, sparse, or encrypted files. Permanent actions can be stopped; an incomplete overwrite may leave altered contents and requires a fresh scan before any further cleanup. This is not certified erasure of SSD/flash, copy-on-write storage, snapshots, backups, or synced copies.

The Windows 0.2.1 Recycle Bin fix is included: an isolated STA worker, explicit recycle-only Shell flags, per-file Windows errors, persistent results, and no permanent-delete fallback. Windows tests recycle and restore isolated fixtures, handle locked files, and reject callbacks requesting permanent deletion. New cross-platform fixtures verify permanent deletion, overwrite read-back, cancellation, exact confirmations, changed-file and hard-link rejection, and explicit all-copy acknowledgment.

Choose the download for your computer:

- **Windows x64:** `windows-x64-setup.exe`, or the portable ZIP. Requires WebView2; the installer can install the runtime if missing.
- **Linux x64:** `.deb` for compatible Ubuntu/Debian-family systems, or `.AppImage`. Packages are built on Ubuntu 22.04; some distributions require FUSE 2 for AppImages.
- **Apple Silicon Mac (M1 or newer):** `macos-apple-silicon.dmg`.
- **Intel Mac:** `macos-intel.dmg`.

Choose **Clean data** at launch; its initial dashboard displays labeled sample data. Click **Scan a folder** to index your real files. Review full paths before cleanup. Moving files to trash typically does not increase available capacity until the trash is emptied.

Packages are unsigned; macOS builds are not notarized. They may require an explicit OS security confirmation. The workflow tests the scanner on each build OS and runs interface tests in Chromium; this does not establish interactive desktop qualification. See the README and validation notes for current limits.

`SHA256SUMS.txt` contains download checksums. Per-platform build information records the source commit and build tools. GitHub source archives contain the complete application source and instructions.
