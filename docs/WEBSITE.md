# Sift website

The public marketing and download site is **https://sift.sarvesh.si**, hosted on
GitHub Pages from this repository. It is separate from the desktop React app.
Cloudflare provides DNS and the edge proxy, with a proxied CNAME from
`sift.sarvesh.si` to `sarvesh-kr.github.io`. GitHub Pages serves the site; Cloudflare
redirects HTTP visitors to HTTPS and verifies the origin using Full (strict) TLS.

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

These checks cover all six release asset links, Mac architecture selection,
keyboard-accessible previews, image loading, narrow layouts, and operation
without JavaScript.

## Publishing and download refresh

`.github/workflows/pages.yml` builds, checks, and deploys the site on relevant
pushes to `main`, on published releases, or through manual dispatch. It also runs
after successful **Desktop builds**, because GitHub releases created with the
workflow's own token do not trigger another release workflow.

The builder uses the newest published release that has **all six** expected
installer/archive assets plus `SHA256SUMS.txt`. It includes preview releases and
labels them. Incomplete releases are skipped, preserving the previous complete
version; if none is complete, deployment fails instead of publishing bad links.
Links are rendered into HTML at build time, so visitors do not depend on GitHub
API rate limits or JavaScript. All downloads go directly to GitHub release assets.

GitHub Pages settings must retain **GitHub Actions** as the source and
`sift.sarvesh.si` as the domain. `website/CNAME` also records the domain in the
source. The `github-pages` environment is the deployment target. The workflow
checks the live HTTPS page after deployment.

Two Cloudflare rules apply only to `sift.sarvesh.si`:

- **Sift HTTPS redirect**, phase `http_request_dynamic_redirect`: match
  `(http.host eq "sift.sarvesh.si" and not ssl)`, then 301 redirect to
  `concat("https://sift.sarvesh.si", http.request.uri.path)` while preserving the
  query string.
- **Sift origin TLS**, phase `http_config_settings`: match
  `(http.host eq "sift.sarvesh.si")` and set `ssl` to `strict`.

GitHub accepts deployment tokens but rejects updating its **Enforce HTTPS**
repository setting through the available integrations. HTTPS redirects are
therefore handled by Cloudflare; the site does not depend on changing that
GitHub setting. Keep the Sift DNS record proxied for these rules to apply.

## Screenshots and fonts

`website/assets/overview.png`, `cleanup.png`, and `duplicates.png` are actual Sift
sample-workspace screenshots; they are labeled on the page. The duplicate-file
illustration is labeled as an example. No performance or comparative superiority
claims are made. Font license notices are included with the self-hosted fonts.
