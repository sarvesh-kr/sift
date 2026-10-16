Sift is a local desktop storage explorer and cleanup assistant. This preview includes an explanation-based Cleanup Center, folder drill-down, exact duplicate comparison, retained-copy protection, and system-trash cleanup.

**0.2.1 cleanup fix:** Windows now uses an isolated STA worker and explicit Recycle Bin operations instead of the dependency that reduced failures to “Some operations were aborted.” Each file reports its own Windows error code and recovery hint. A skipped operation is never counted as a successful recycle. Permanent-delete requests are blocked. Successful files leave the selection; failed or unconfirmed files retain their full-path results for review. Cleanup progress is shown, and unrelated duplicate groups are no longer revalidated on each cleanup.

Windows CI now recycles and restores disposable fixture files with Unicode names, checks that a locked file does not block the next file, and exercises the permanent-delete callback guard. These tests also run on ordinary pushes and pull requests.

Choose the download for your computer:

- **Windows x64:** `windows-x64-setup.exe`, or the portable ZIP. Requires WebView2; the installer can install the runtime if missing.
- **Linux x64:** `.deb` for compatible Ubuntu/Debian-family systems, or `.AppImage`. Packages are built on Ubuntu 22.04; some distributions require FUSE 2 for AppImages.
- **Apple Silicon Mac (M1 or newer):** `macos-apple-silicon.dmg`.
- **Intel Mac:** `macos-intel.dmg`.

The initial dashboard displays labeled sample data. Click **Scan a folder** to index your real files. Review full paths before cleanup. Moving files to trash typically does not increase available capacity until the trash is emptied.

Packages are unsigned; macOS builds are not notarized. They may require an explicit OS security confirmation. The workflow tests the scanner on each build OS and runs interface tests in Chromium; this does not establish interactive desktop qualification. See the README and validation notes for current limits.

`SHA256SUMS.txt` contains download checksums. Per-platform build information records the source commit and build tools. GitHub source archives contain the complete application source and instructions.
