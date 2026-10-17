# Builds and releases

The repository is [sarvesh-kr/sift](https://github.com/sarvesh-kr/sift).

## Continuous checks

`Quality checks` runs on relevant pushes to `main` and on all pull requests. On `main`, documentation, screenshots, website-only and Android-only changes do not rerun the desktop suites. Superseded checks on the same ref are cancelled. The workflow checks the TypeScript production build, frontend analysis tests, Chromium interface tests, Rust formatting, and Rust scanner tests. Pull requests retain their checks, and desktop builds always call the same checks before starting native packaging, regardless of path filters.

Website changes still deploy automatically. Download refreshes after build completion are restricted to successful tagged release builds; ordinary development builds do not redeploy the marketing site.

Build emails are GitHub account preferences, not workflow email steps. To stop them, open [notification settings](https://github.com/settings/notifications), go to **System → Actions**, deselect **Email**, and save. Keep **On GitHub** enabled if you want failures in the web inbox. This preference applies to the account's Actions notifications, not just Sift; repository workflow access cannot change it.

## Build packages without publishing

Open **Actions → Desktop builds → Run workflow**, select a branch, and run it. After completion, download the `sift-windows-x64`, `sift-linux-x64`, `sift-macos-apple-silicon`, or `sift-macos-intel` workflow artifact. These artifacts expire after 30 days.

## Publish a version

1. Set the same version in `package.json`, `src-tauri/tauri.conf.json`, `src-tauri/Cargo.toml`, and `native-core/Cargo.toml`. Update npm and both Cargo lockfiles. Review the release notes in `.github/RELEASE_NOTES.md`.
2. Commit and push to `main`.
3. Create and push a matching version tag:

   ```sh
   git tag -a v0.2.0 -m "Sift 0.2.0 preview"
   git push origin v0.2.0
   ```

The tagged workflow validates manifest/tag consistency and builds on native Windows, Linux, Apple Silicon macOS, and Intel macOS runners. After every build succeeds, it publishes a preview release containing the packages, platform build records, and a combined `SHA256SUMS.txt`.

The workflow's release job has `contents: write`; all other jobs use read-only repository access. It uses the automatic GitHub Actions token. No personal token, app API key, updater key, or signing secret is required. All releases are currently marked as previews.

To retry a transient failure, rerun the failed jobs in Actions. Existing release assets are replaced on rerun with the successfully rebuilt artifacts. Do not move an already published tag; fix a release with a new version.

## Package support

| Build | Native runner | Packages |
| --- | --- | --- |
| Windows x64 | Windows latest | NSIS setup EXE and portable ZIP |
| Linux x64 | Ubuntu 22.04 | Debian package and AppImage |
| macOS Apple Silicon | macOS 15 ARM | DMG |
| macOS Intel | macOS 15 Intel | DMG |

The Linux build uses Ubuntu 22.04 to keep the glibc baseline older than Ubuntu 24.04. Linux compatibility still depends on distribution libraries and graphics drivers; some AppImage setups need FUSE 2. Debian packages require the runtime libraries declared by Tauri. The workflow does not produce ARM Linux or Windows ARM packages.

The Windows portable EXE requires WebView2. The NSIS installer checks for it and can install the runtime if missing.

## Signing and qualification

Builds are unsigned and macOS packages are not notarized. macOS can require **System Settings → Privacy & Security → Open Anyway** for a trusted downloaded preview; Windows may display a reputation warning. Signing and notarization need the repository owner's platform certificates and are not configured with fabricated credentials.

Scanner tests run on each build OS. Interface tests run in Chromium on Linux. A successful workflow establishes compilation, packaging, and those tests; it does not establish interactive native app testing or a competitor performance advantage. Each platform's build record identifies the exact source commit and tools used.

To check a downloaded file:

```sh
# Linux
sha256sum -c SHA256SUMS.txt --ignore-missing

# macOS: compare the value with SHA256SUMS.txt
shasum -a 256 Sift-0.2.0-macos-apple-silicon.dmg
```

On Windows use `Get-FileHash .\Sift-0.2.0-windows-x64-setup.exe -Algorithm SHA256` and compare it with the manifest.
