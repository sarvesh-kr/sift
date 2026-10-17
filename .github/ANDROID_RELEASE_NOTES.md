**0.3.0: Clean data / Recover data workspaces.** Start with storage cleanup or
recovery. Recover data opens Sift trash or uses Android's document picker to scan
a local, seekable raw disk image without All files access. Contiguous JPEG, PNG,
WebP, PDF, ZIP, and WAV candidates include categories, List / Grid, image previews,
offsets, evidence, and SHA-256. Save one, selected, or all filtered candidates to
a new category-organized folder, with read-back verification and a JSON manifest.
Output descriptors are checked before writing; source aliases and existing
nonempty files are refused without truncation. Sources are read-only.

**Recovery preview limits:** normal Android permissions cannot undelete encrypted
internal-storage sectors. No raw phone scanning, image creation, filesystem
metadata recovery, original names/folders, fragmentation rebuilding, decryption,
ZIP64, split archives, or universal recovery. Supported contiguous candidates
cap at 512 MiB each / 10,000 results, with bounded probing. PDF footer evidence
and ZIP directory checks do not guarantee readable documents or valid payloads.
Existing files may appear too. TRIM/overwriting/encryption can make recovery
impossible. Use a stable image and save to another physical drive.

**Updating:** install this APK over the existing app, using the same Sift key and
version code 3. **Do not uninstall to update**: uninstalling deletes Sift trash.
The debug test DocumentsProvider and synthetic images are excluded from release.

**0.2.0: List / Grid views with local content previews.** Recognize images,
video frames, PDF first pages, and plain-text/source files in Files, categories,
large files, duplicate matches, Sift trash, and cleanup/restore review. Open file
details for a larger preview. Layout preference persists and switching views
retains selection. Unsupported codecs/types, protected PDFs, oversized images,
and corrupt files show a file-type fallback. Previews are lazy, capped at 384 px,
with three IO decoders and a 24 MB private memory cache. Text/HTML stays inert.

**Updating from 0.1.0:** install this APK over the existing app. It uses the same
Sift signing key and version code 2. Do not uninstall to update: uninstalling
removes Sift trash.

Sift for Android — native Kotlin and Jetpack Compose preview, Android 11 or newer.

- Scan accessible shared phone storage, browse categories, search, find large files, and select individual files or all filtered matches.
- Compare exact duplicates using SHA-256 followed by full byte comparison; hard links are excluded.
- Move files to **Sift trash**, restore them to their original paths without overwriting existing files, or permanently delete with exact typed confirmation.
- Retained-copy protection, scanned-file allowlists, changed-file and symlink checks, cancellation, durable trash records, and per-file failures.
- No internet permission, accounts, ads, accessibility service, RAM booster, automatic background cleanup, or other apps’ private-cache access.

Download `Sift-<version>-android.apk` on your phone and allow installation from your browser or file manager when Android asks. For cleanup, choose **Clean data**, then enable Sift’s **All files access** in system settings, return to Sift, and scan shared storage. This permission is for user-directed file management. Protected Android app data remains inaccessible. This is a sideloaded preview, not a Google Play listing.

**Sift trash occupies storage.** Permanently empty selected items to reclaim it. Uninstalling Sift removes its trash; restore important files first. Android Gallery trash is separate. Permanent deletion is not secure erasure of phone flash, synced copies, or backups. Scans are capped at 100,000 files, and content comparisons are optional. Avoid concurrent file changes during cleanup.

The release APK is signed with the Sift Android key and is not debuggable. `SHA256SUMS-android.txt` verifies the files; the build record identifies the exact source commit, CI run, and APK certificate fingerprint. Device tests cover Android 11 and Android 16 emulators. Physical-device and OEM-specific qualification remains necessary.
