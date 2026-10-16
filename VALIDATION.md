# Sift 0.2.0 build and validation record

Validated on 16 October 2026 in a Debian 13 x86-64 environment, using Rust 1.99.1 and Node.js 24.19.0.

- TypeScript checks and the Vite production build passed, with no CSS syntax warnings.
- All 10 Rust core tests passed, including sampled-content filtering, full-hash rejection of files with matching samples, separate byte verification, cancellation, hard links, changed-file and symlink checks, retained-copy protection, and trash failures.
- All 12 frontend checks passed: 3 analysis tests and 9 Chromium interface tests covering file search, cleanup explanations, folder scoping, keeper selection, blocked last-copy removal, preferences, responsive layouts, selection scope, and keyboard focus.
- The Rust scanner indexed 10,000 generated files in 40 folders with correct totals and no skipped entries. Each file was 1 KiB; the recorded scan took 34 ms on this cached filesystem. This is a smoke test, not a real-drive or competitor performance benchmark.
- The full Windows x64 desktop application was cross-compiled for x86_64-pc-windows-msvc and bundled into an NSIS installer successfully. The executable is approximately 3.55 MiB and the installer 1.61 MiB. WebView2 is a separate system runtime.
- Screenshots show the labeled sample workspace. Sample data is not a scan of this build environment.

The Windows app and installer have not been launched on Windows here. WebView2 rendering, native dialogs, volume-space readings, and Recycle Bin behavior need Windows runtime qualification. macOS and Linux desktop packages have not been built or launched here. The included CI workflow provides per-platform package builds. Builds are unsigned.

See README.md for reproduction steps and PRODUCT-GOALS.md for the remaining engineering work and comparative benchmark gates. No best-in-class or superiority claim has been established.
