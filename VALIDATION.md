# Sift validation

The release pipeline requires passing interface tests and native safety tests
before publishing installers. Build metadata in each release identifies its exact
source commit. See GitHub Actions for the recorded outcome on each runner.

- **Cross-platform Rust regressions:** scanning, cancellation, sampled/full
  duplicate comparisons, hard links, changed paths, retained-copy protection,
  unique cleanup outcomes, progress, partial failure, and no permanent-delete
  fallback. Disposable fixtures additionally exercise exact irreversible confirmations, explicit all-copy deletion, full overwrite/read-back including partial blocks, stop between blocks, and hard-link rejection. Unix also checks symlink redirection.
- **Windows runtime regressions:** recycle three disposable files including a
  Unicode name, enumerate those exact fixtures in the Recycle Bin, restore their
  contents, handle a locked file followed by a recyclable file, and refuse a
  callback requesting deletion without recycling. Fixture filtering uses the
  unique test directory; no unrelated Recycle Bin items are modified.
- **26 frontend checks:** storage analysis and review flows, keyboard focus,
  responsive layout, and mocked native partial/zero-success cleanup. These verify
  that per-file errors persist, successful files are excluded from a retry, typed confirmations reset with selection changes, single-file and filtered all-match selection work, and incomplete overwrites block cleanup until rescanning.
- **Six website checks:** release links, Mac architecture selection, previews,
  narrow layouts, Android APK recommendations and install instructions, and download operation without JavaScript.
- TypeScript, Vite, Rust formatting, and Windows-target compilation checks.
- Native installer builds for Windows x64, Linux x64, and both Mac architectures.

These checks do not establish zero bugs or performance superiority. Interactive
GUI testing, unusual Windows policies, huge files, cloud placeholders,
long paths, and very large inventories still need qualification on real devices.
Windows recycling deliberately refuses unsupported/non-recyclable paths and
reports errors without switching to permanent deletion. Permanent actions run only
after an explicit mode choice and matching typed confirmation. Recycle Bin capacity, open-file
locks, and OS access rules can prevent a legitimate recycle operation.

See README.md for reproduction steps and PRODUCT-GOALS.md for benchmark gates.

## Android 0.1.0 release gates

- Release APK compilation and resource/code shrinking, Android lint, and four
  model regressions for extension categorization, exact typed confirmations,
  duplicate singleton cleanup, and file-identity alias exclusion.
- Eleven instrumented checks on Android 11/API 30 and Android 16/API 36 emulators:
  protected-folder and symlink exclusion, changed-file rejection, exact
  confirmation and scan allowlists, duplicate-copy protection,
  cancellation, Unicode trash/restore, collision refusal, persistence, interrupted
  journal recovery, corrupt-record preservation, actual shared-storage moves into
  app-owned trash, and native Compose navigation.
- First-launch onboarding is checked without storage access before granting the
  permission only to the isolated emulator test app.
- The optimized release APK is exercised through its native screens: scan,
  search, file details, cleanup review, trash, full emulator reboot, and restore.
  The restored fixture is checked against its exact original contents.
  [Android build and device tests](https://github.com/sarvesh-kr/sift/actions/runs/39011631651)
  and [release reboot/restore check](https://github.com/sarvesh-kr/sift/actions/runs/39012745889)
  passed for APK source `2155ec5a0dca3b64d69e6ecb5178bdaacdc0c0de`.
- Signed release verification includes APK certificate, manifest permissions,
  non-debuggable status, universal architectures, 16 KB native-library alignment,
  source provenance, and SHA-256 checksums. No signing key is published.

Emulators do not establish physical-device or OEM-wide qualification. Android
cannot clean other apps' protected private data. Trash remains app-owned storage
and is removed when Sift is uninstalled. Permanent deletion cannot guarantee
physical erasure from flash or synchronized copies. See docs/ANDROID.md.

## Local previews and layouts (desktop 0.4.0 / Android 0.2.0)

- Desktop native fixtures cover decoded image dimensions, scaled/EXIF-oriented
  camera JPEGs, corrupt-image fallback, bounded inert text, binary exclusion,
  and changed-file refusal. Windows additionally exercises a real Shell bitmap.
- Interface checks cover lazy loading, grid/list selection retention, search of
  unloaded files, saved layout, larger details, visual cleanup review,
  folder/duplicate views, inert HTML, and type fallback.
- Android device checks exercise actual PNG, MP4 frame, and PDF rendering;
  text/binary/corrupt fallbacks; private trash previews; and Compose grid/list
  selection retention plus cleanup review. No user data is used.
- Decode limits and private memory caches keep previews separate from the scan.
  A preview is a cached snapshot, not proof that a file is safe to delete.

Release evidence: [desktop build and safety gates](https://github.com/sarvesh-kr/sift/actions/runs/39027533561)
passed for desktop source `478117f042bd391ffc0bbb4a327ba7936b6a36aa`.
All fifteen Android instrumented checks passed on both API 30 and API 36 in
[the Android build](https://github.com/sarvesh-kr/sift/actions/runs/39028021603),
including actual PNG/video/PDF previews and native layout/selection behavior.
The optimized APK additionally passed [cleanup and restore after reboot](https://github.com/sarvesh-kr/sift/actions/runs/39028282583).
Android APK source is `e9ba6d6f60a2bd98fff82b59182e62d29b413e35`; the signed
public APK uses the original Sift certificate and version code 2.

## Recovery preview (desktop 0.5.0 / Android 0.3.0)

The two clients consume the same six-format synthetic golden image and expected
byte offsets, sizes, and SHA-256 values. Native recovery checks cover source
preservation, corruption, overlap, changed/replaced images, allowlists, export
verification/manifests, cancellation, bounded previews, repeated JPEG markers,
and atomic publication without replacement. Kotlin JVM checks cover the shared
golden contract, copies, corruption, cancellation, source changes, and JPEG
search-window reuse. Browser checks cover mode routing/state retention, category
filters, selection/layout, evidence details, exact one-file export IDs, and focus.

Android device gates use an isolated debug-only DocumentsProvider for real SAF
scan/export, corrupted destination rejection, source URI alias rejection without
truncation/deletion, grid/list retention, and Sift-trash navigation. The release
packager rejects accidentally included golden fixtures. Optimized release smoke
checks now enter Clean data through the real launcher before scan/trash/reboot/
restore. CI run outcomes for this release are recorded after completion below.

These checks do not establish forensic acquisition, original-path restoration,
fragmented-file recovery, physical-device recovery rates, or superiority over
commercial tools. [Exact recovery scope and limits](docs/RECOVERY.md).

Android export review additionally covers a provider alias that refuses write
opens: failure cleanup cannot delete an unverified destination/source alias.
Permission-free recovery is tested in its own process started with access denied;
permission changes occur only between instrumentation invocations. Lazy-grid UI
checks scroll the container to uncomposed results before interacting with them.

## Published recovery preview release records

- **Desktop 0.5.0**, source `bdfd063cdfeee81b80cca3a0119ebb77b90a6762`:
  [all four platform builds and release](https://github.com/sarvesh-kr/sift/actions/runs/39033097564)
  passed. The native suite passed 35 tests on Windows and 33 on Linux; the build
  job also ran native tests on both Mac architectures. All 26 interface checks
  passed. The ten package/build-record hashes match GitHub's uploaded asset
  digests, and all four build records identify this source commit.
- **Android 0.3.0**, source `e1c75c13276c255675855299b0e4bacf0ceb83ee`:
  [release build, lint, 11 JVM tests, and device gates](https://github.com/sarvesh-kr/sift/actions/runs/39034081538)
  passed. API 30 and API 36 each passed 20 main instrumented checks plus one
  separate check with All files access already denied. SAF copies, correct
  document MIME types, destination corruption, writable/read-only source aliases,
  visual selection, and existing cleanup/trash regressions were exercised.
- The [optimized APK smoke](https://github.com/sarvesh-kr/sift/actions/runs/39034259498)
  passed both workspace screens while access was denied, then scan, details,
  cleanup review, trash, full OS reboot, restore, and exact restored bytes.
  Screenshots in `artifacts/sift-android.png` and
  `artifacts/sift-android-recovery.png` come from this optimized build's emulator.
- [Signed attachment verification](https://github.com/sarvesh-kr/sift/actions/runs/39034643135)
  confirmed that every signed APK ZIP entry matches the tested CI build. The
  published APK uses the existing Sift certificate, version code 3, no internet
  permission, all four universal ABIs, and 16 KiB native/ZIP alignment. Test
  provider/fixture exclusion, APK certificate, checksums, and exact source were
  separately verified. The published three asset digests match the signed local
  APK, build record, and checksum file. Private signing material was not uploaded.
- Website gates run four Python release-builder guards and six browser checks,
  covering complete downloads, platform recommendations, responsive and
  JavaScript-free use, workspace information, and honest recovery limitations.

These are automated fixture/emulator checks, not physical-device recovery rates,
full desktop interaction qualification, or a commercial recovery benchmark.
