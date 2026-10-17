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

Download `Sift-<version>-android.apk` on your phone and allow installation from your browser or file manager when Android asks. On first launch, enable Sift’s **All files access** in system settings, return to Sift, and scan shared storage. This permission is for user-directed file management. Protected Android app data remains inaccessible. This is a sideloaded preview, not a Google Play listing.

**Sift trash occupies storage.** Permanently empty selected items to reclaim it. Uninstalling Sift removes its trash; restore important files first. Android Gallery trash is separate. Permanent deletion is not secure erasure of phone flash, synced copies, or backups. Scans are capped at 100,000 files, and content comparisons are optional. Avoid concurrent file changes during cleanup.

The release APK is signed with the Sift Android key and is not debuggable. `SHA256SUMS-android.txt` verifies the files; the build record identifies the exact source commit, CI run, and APK certificate fingerprint. Device tests cover Android 11 and Android 16 emulators. Physical-device and OEM-specific qualification remains necessary.
