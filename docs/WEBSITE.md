# Sift website

The public marketing and download site is **https://sift.sarvesh.si**, hosted on
GitHub Pages from this repository. It is separate from the desktop React app.
Cloudflare provides DNS, with a **DNS-only** (unproxied) CNAME from
`sift.sarvesh.si` to `sarvesh-kr.github.io`. GitHub Pages terminates HTTPS directly.
DNS-only exposes the required CNAME for GitHub domain verification and certificate
provisioning. Do not enable Cloudflare proxying while validating this domain.

## Edit and preview

Source lives in `website/`. It is buildless HTML, CSS, and a small JavaScript file,
with self-hosted fonts and actual app screenshots. No tracking code, browser API
fetch, framework runtime, or backend is needed for the marketing site. Essential
downloads and FAQs work with JavaScript disabled.

```sh
python3 scripts/build-site.py
python3 -m http.server 4173 --directory site-dist
```

Open `http://localhost:4173`. Local builds use `website/release-snapshot.json`.
To use a fresh GitHub release response:

```sh
gh api repos/sarvesh-kr/sift/releases > /tmp/sift-releases.json
python3 scripts/build-site.py --releases /tmp/sift-releases.json
```

Use `npm ci` and `npx playwright install chromium` if dependencies are missing,
then run:

```sh
npx playwright test --config playwright.website.config.ts
```

These checks cover desktop and Android release asset links, Mac architecture selection,
keyboard-accessible previews, image loading, narrow layouts, and operation
without JavaScript.

## Publishing and download refresh

`.github/workflows/pages.yml` builds, checks, and deploys the site on relevant
pushes to `main`, on published releases, or through manual dispatch. It also runs
after successful **Desktop builds** or **Android builds**, because GitHub releases created with the
workflow's own token do not trigger another release workflow.

The desktop builder uses the newest published release that has **all six** expected
installer/archive assets plus `SHA256SUMS.txt`. It includes preview releases and
labels them. Incomplete releases are skipped, preserving the previous complete
version; if none is complete, deployment fails instead of publishing bad links.
Links are rendered into HTML at build time, so visitors do not depend on GitHub
API rate limits or JavaScript. All downloads go directly to GitHub release assets.

Android is resolved independently from tags `android-vX.Y.Z`. An Android download
appears only when a published release includes the signed filename
`Sift-X.Y.Z-android.apk`, its build record, and `SHA256SUMS-android.txt`. Unsigned
and incomplete Android builds are ignored; previous complete APKs remain linked.
The Android card uses its own version and checksum. Android phones are recommended
the APK rather than a Linux download. Release completeness and URL provenance
have Python regression checks in `scripts/test-site-builder.py`.

GitHub Pages settings must retain **GitHub Actions** as the source and
`sift.sarvesh.si` as the domain. `website/CNAME` also records the domain in the
source. The `github-pages` environment is the deployment target. The workflow
checks the live HTTPS page after deployment.

GitHub has approved the TLS certificate for `sift.sarvesh.si`, and its Pages API
confirms **Enforce HTTPS is enabled** (`https_enforced: true`). Plain HTTP returns
a 301 redirect to HTTPS, preserving the path and query string. The HTTPS site
returns successfully with certificate verification enabled. Keep the native
**Settings → Pages → Enforce HTTPS** setting enabled.

Previous host-specific Cloudflare redirect and strict-origin rules are inactive
because this record is DNS-only. They are not a substitute for GitHub's native
HTTPS setting and do not affect other subdomains.

## Screenshots and fonts

`website/assets/overview.png`, `cleanup.png`, and `duplicates.png` are actual Sift
sample-workspace screenshots; they are labeled on the page. The duplicate-file
illustration is labeled as an example. No performance or comparative superiority
claims are made. Font license notices are included with the self-hosted fonts.
