# Sift 0.2.1 validation

The release pipeline requires passing interface tests and native safety tests
before publishing installers. Build metadata in each release identifies its exact
source commit. See GitHub Actions for the recorded outcome on each runner.

- **Cross-platform Rust regressions:** scanning, cancellation, sampled/full
  duplicate comparisons, hard links, changed paths, retained-copy protection,
  unique cleanup outcomes, progress, partial failure, and no permanent-delete
  fallback. Unix also checks symlink redirection.
- **Windows runtime regressions:** recycle three disposable files including a
  Unicode name, enumerate those exact fixtures in the Recycle Bin, restore their
  contents, handle a locked file followed by a recyclable file, and refuse a
  callback requesting deletion without recycling. Fixture filtering uses the
  unique test directory; no unrelated Recycle Bin items are modified.
- **14 frontend checks:** storage analysis and review flows, keyboard focus,
  responsive layout, and mocked native partial/zero-success cleanup. These verify
  that per-file errors persist and successful files are excluded from a retry.
- **Four website checks:** release links, Mac architecture selection, previews,
  narrow layouts, and download operation without JavaScript.
- TypeScript, Vite, Rust formatting, and Windows-target compilation checks.
- Native installer builds for Windows x64, Linux x64, and both Mac architectures.

These checks do not establish zero bugs or performance superiority. Interactive
GUI testing, unusual Windows policies, huge files, cloud placeholders,
long paths, and very large inventories still need qualification on real devices.
Windows cleanup deliberately refuses unsupported/non-recyclable paths and
reports errors rather than deleting permanently. Recycle Bin capacity, open-file
locks, and OS access rules can prevent a legitimate recycle operation.

See README.md for reproduction steps and PRODUCT-GOALS.md for benchmark gates.
