# Sift for Android

The native Android client lives in `android/`, alongside the Rust/Tauri desktop
app. Kotlin and Jetpack Compose use Android's own storage and lifecycle APIs.
The two clients share the Sift product and repository; Android does not pretend
that desktop filesystem APIs work around Android's storage restrictions.

## Workspaces and recovery

Android 0.3.0 (version code 3) opens with **Clean data** and **Recover data**.
Cleaning and Sift-trash restore retain their previous permission and safety
checks. Recovery can also scan a local, seekable raw image selected through the
system document picker, without All files access. It carves contiguous JPEG,
PNG, WebP, PDF, ZIP, and WAV candidates, shows evidence and local image previews,
and exports selected category-organized copies with SHA-256/read-back checks and
a JSON manifest. This cannot undelete encrypted internal-storage sectors or
rebuild fragmented files, original names, or folder trees. [Full recovery scope
and platform limits](RECOVERY.md).

Image handles live in a separate lifecycle ViewModel. Selection is allowlisted;
output descriptors open without truncation and must be fresh empty regular
files distinct from the source. Native picker destinations must support local
regular-file IO, flush, and read-back. API 30/36 instrumented fixtures include a
debug-only DocumentsProvider with corruption and source-alias fault injection.
This provider and golden sample image are excluded from release APKs; packaging
rejects accidental fixture assets.

## Storage and cleanup

Android 11+ is required. Target SDK: 36 (Android 16). The Clean data workspace explicitly asks
the user to enable **All files access** for Sift before scanning shared primary
storage. No root, accessibility service, package visibility, internet, contacts,
or background scan permission is requested. Android/data, Android/obb, other
apps' private files, and the system partition are excluded. Only indexed regular
files may be cleaned; symlinks and changed identities, sizes, or timestamps are
refused. Scans cap the inventory at 100,000 files. Duplicate checks are optional,
stream SHA-256, exclude hard links, and confirm matches with another byte pass.

Sift trash is app-owned storage on the same primary volume. An atomic intent
record is saved before each rename, so interrupted moves can be recovered.
Failed moves never fall back to deletion. Android media FUSE can change file
identity/timestamps and invalidate old handles after renaming into app data.
Trash therefore uses the native rename acknowledgment, revalidated source,
absent original, private nonce-named destination, and preserved size; it records
the destination’s actual metadata. Device and FUSE inode IDs can change on
remount or reboot, so persistent trash records validate their private UUID path,
size and modification time, then refresh filesystem IDs for the current operation. Duplicate content is verified before moving. Restoring refuses collisions and
missing or redirected original folders. Trash occupies space until explicitly
emptied. **Uninstalling Sift removes its trash.** The app says this before moving
files. It does not use or claim to manage Android Gallery's separate trash.
Invalid trash records are reported and left unchanged; do not uninstall before
recovering needed data. Keep backups and avoid concurrent file changes during
cleanup: ordinary path operations cannot eliminate all races with other apps.

Permanent deletion requires `DELETE N FILE(S)` and an additional acknowledgment
when selecting every known duplicate copy. There is no secure-wipe feature:
flash wear leveling, copy-on-write, cloud synchronization, and backups prevent
reliable file-level physical-erasure guarantees. App caches and RAM are outside
this client's scope. All files access is restricted on Google Play; this APK is
sideloaded, with no claim of Play review or listing.

Official platform documentation:
- https://developer.android.com/training/data-storage/manage-all-files
- https://developer.android.com/training/data-storage/shared/documents-files
- https://developer.android.com/reference/android/provider/MediaStore#createTrashRequest(android.content.ContentResolver,java.util.Collection%3Candroid.net.Uri%3E,boolean)

## Visual browsing

Android 0.2.0 saves the chosen List / Grid layout and retains selection and filters
when switching. The Files page (including categories, large files, and duplicates),
Sift trash, cleanup/restore review, and file details show local previews. Images
use sampled decoding with orientation, videos use Android's thumbnail decoder,
and PDFs show the first page. Text/source snippets are rendered as inert text.
Unavailable formats, corrupt content, password-protected PDFs, and unsupported
codecs fall back to a file-type illustration. Support depends on Android's codecs.

Previews are generated for composed visible/prefetched items on IO workers, with
three concurrent decodes and a 24 MB memory cache. No thumbnails are written to
shared storage or uploaded. Image/PDF input is capped at 64 MB, output at 384 px,
and text reads at 4 KB. Cleaning inventory scanning remains metadata-only; image recovery reads source bytes. Trash previews use the
validated private stored file while displaying its original name and path.

Install updates over the existing app using the same signing key. **Do not
uninstall to update** if you need to preserve Sift trash.

## Build and validate

Install JDK 17, Android SDK platform 36 and build-tools 35.0.0. The Gradle 8.13
wrapper verifies the distribution's pinned SHA-256 checksum.

```sh
cd android
./gradlew :app:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:testDebugUnitTest
```

Without a private signing configuration, the release output is
`app/build/outputs/apk/release/app-release-unsigned.apk`; it cannot be installed
until signed. Never advertise or upload it as the installable download.
`.github/workflows/android.yml` builds, lints, runs model tests, and exercises
actual file operations plus Compose navigation on Android 11/API 30 and Android
16/API 36 emulators. The device job first verifies onboarding without storage
access, then grants access only inside its disposable emulator. Tests operate on
unique temporary fixtures, including a shared-storage move/restore test.

## Signing and releases

Application ID: `si.sarvesh.sift`. Version name and monotonically increasing
version code live in `android/app/build.gradle.kts`. Use tags `android-vX.Y.Z`;
desktop releases retain their independent versions and tags.

Keep the PKCS12 signing key and password **private and backed up**. Never commit
them, upload them as Actions artifacts, or publish them with release assets.
Future installed updates must use the same key and a higher version code.

Repository signing secrets:

- `SIFT_ANDROID_KEYSTORE_BASE64`: base64-encoded PKCS12 keystore.
- `SIFT_ANDROID_STORE_PASSWORD`: store password.
- `SIFT_ANDROID_KEY_PASSWORD`: key password (same as store password for this key).

Configure these with an owner-authorized GitHub session, using stdin to avoid
putting private values in command arguments:

```sh
base64 < /private/sift-android.p12 | gh secret set SIFT_ANDROID_KEYSTORE_BASE64 --repo sarvesh-kr/sift
gh secret set SIFT_ANDROID_STORE_PASSWORD --repo sarvesh-kr/sift < /private/store-password.txt
gh secret set SIFT_ANDROID_KEY_PASSWORD --repo sarvesh-kr/sift < /private/store-password.txt
```

The current integration cannot manage repository secrets (HTTP 403). Until the
owner configures them, CI deliberately produces unsigned release artifacts.
An authorized maintainer can sign a verified artifact locally with
`scripts/sign-android.py --directory ... --keystore ... --password-file ...
--build-tools ...` and publish only the signed APK, updated build record, and
checksum manifest after all device tests pass. The first APK uses this route. When the direct release-upload endpoint is unavailable,
`android-signed-upload.yml` attaches public APK/build-record Git blobs to an
existing draft. It verifies the pinned signing certificate, successful build and
device jobs, source metadata, ZIP alignment, and every original APK entry against
the tested CI artifact. Only signature entries may be added. It receives no
private key and leaves publication as a separate action.
When the secrets are configured, Android version tags automatically publish a
signed preview only after both emulator targets pass. Signed APKs are verified
with `apksigner`; certificate fingerprints are recorded in the build metadata.

The website resolves Android independently from complete desktop releases, so
adding an APK does not remove or replace Windows, Linux, or Mac downloads.

The initial Sift Android signing certificate SHA-256 fingerprint is:

```text
e9077ccd2d0ced08d77ae918fd2a665eea95a7190710a99ea0e3ae61df52334a
```
This is a public certificate fingerprint, not the private signing key.
