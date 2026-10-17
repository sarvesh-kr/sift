#!/usr/bin/env python3
"""Render the static marketing site from a complete, published GitHub release.

No API is called from visitors' browsers. CI supplies GitHub's release response;
local builds use the checked-in snapshot. Partial releases never become downloads.
"""
import argparse
from datetime import datetime, timezone
import html
import json
from pathlib import Path
import re
import shutil
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parent.parent
REPO = "https://github.com/sarvesh-kr/sift"
SUFFIXES = {
    "WINDOWS_SETUP": "windows-x64-setup.exe",
    "WINDOWS_PORTABLE": "windows-x64-portable.zip",
    "MAC_ARM": "macos-apple-silicon.dmg",
    "MAC_INTEL": "macos-intel.dmg",
    "LINUX_APPIMAGE": "linux-x64.AppImage",
    "LINUX_DEB": "linux-x64.deb",
}


def android_data(releases):
    data = {"ANDROID_HIDDEN": "hidden", "ANDROID_VERSION": "", "ANDROID_SIZE": "",
            "ANDROID_URL": REPO + "/releases", "ANDROID_RELEASE_URL": REPO + "/releases",
            "ANDROID_CHECKSUMS_URL": REPO + "/releases"}
    for release in sorted(releases, key=lambda r: r.get("published_at") or "", reverse=True):
        tag = release.get("tag_name", "")
        if release.get("draft") or not re.fullmatch(r"android-v\d+\.\d+\.\d+", tag):
            continue
        version = tag.removeprefix("android-v")
        assets = {a["name"]: a for a in release.get("assets", []) if a.get("state") == "uploaded" and a.get("size", 0) > 0}
        apk = f"Sift-{version}-android.apk"
        names = [apk, f"Sift-{version}-android-build-info.json", "SHA256SUMS-android.txt"]
        if not all(name in assets for name in names):
            continue
        expected = REPO + "/releases/download/" + tag + "/"
        for name in names:
            if assets[name]["browser_download_url"] != expected + name:
                raise ValueError("Unexpected Android release URL")
        data.update(ANDROID_HIDDEN="", ANDROID_VERSION="v"+version,
                    ANDROID_URL=assets[apk]["browser_download_url"],
                    ANDROID_SIZE=f"{assets[apk]['size'] / 1_000_000:.1f} MB",
                    ANDROID_RELEASE_URL=REPO+"/releases/tag/"+tag,
                    ANDROID_CHECKSUMS_URL=assets["SHA256SUMS-android.txt"]["browser_download_url"])
        break
    return data


def release_data(releases):
    """Use newest complete release, including deliberately labeled previews."""
    for release in sorted(releases, key=lambda r: r.get("published_at") or "", reverse=True):
        tag = release.get("tag_name", "")
        if release.get("draft") or not re.fullmatch(r"v\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]+)?", tag):
            continue
        assets = {a["name"]: a for a in release.get("assets", []) if a.get("state") == "uploaded" and a.get("size", 0) > 0}
        version = tag[1:]
        names = {key: f"Sift-{version}-{suffix}" for key, suffix in SUFFIXES.items()}
        if not all(name in assets for name in [*names.values(), "SHA256SUMS.txt"]):
            continue
        expected = REPO + "/releases/download/" + tag + "/"
        for name in [*names.values(), "SHA256SUMS.txt"]:
            url = assets[name]["browser_download_url"]
            if not url.startswith(expected) or urlparse(url).scheme != "https":
                raise ValueError(f"Unexpected download URL for {name}")
        data = {
            "VERSION": tag,
            "RELEASE_LABEL": "Public preview" if release.get("prerelease") else "Stable release",
            "RELEASE_URL": REPO + "/releases/tag/" + tag,
            "CHECKSUMS_URL": assets["SHA256SUMS.txt"]["browser_download_url"],
            "YEAR": str(datetime.now(timezone.utc).year),
        }
        for key, name in names.items():
            asset = assets[name]
            data[key + "_URL"] = asset["browser_download_url"]
            data[key + "_SIZE"] = f"{asset['size'] / 1_000_000:.1f} MB"
        data.update(android_data(releases))
        return data
    raise ValueError("No complete published release: refusing to publish broken download links")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--releases", type=Path, default=ROOT / "website/release-snapshot.json")
    args = parser.parse_args()
    data = release_data(json.loads(args.releases.read_text()))
    source = ROOT / "website"
    output = ROOT / "site-dist"
    if output.exists():
        shutil.rmtree(output)
    shutil.copytree(source, output, ignore=shutil.ignore_patterns("release-snapshot.json"))
    template = (source / "index.html").read_text()
    rendered = re.sub(r"\{\{([A-Z_]+)\}\}", lambda match: html.escape(data[match.group(1)], quote=True), template)
    if "{{" in rendered:
        raise ValueError("Unresolved template values")
    (output / "index.html").write_text(rendered)
    (output / "release.json").write_text(json.dumps(data, indent=2) + "\n")
    print(f"Built site-dist for {data['VERSION']}; all six platform downloads are present.")


if __name__ == "__main__":
    main()
