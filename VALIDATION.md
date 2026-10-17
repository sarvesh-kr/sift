# Sift 0.4.0 validation

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
- **21 frontend checks:** storage analysis and review flows, keyboard focus,
  responsive layout, and mocked native partial/zero-success cleanup. These verify
  that per-file errors persist, successful files are excluded from a retry, typed confirmations reset with selection changes, single-file and filtered all-match selection work, and incomplete overwrites block cleanup until rescanning.
- **Five website checks:** release links, Mac architecture selection, previews,
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
