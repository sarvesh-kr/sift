"""Collect native packages, create the Windows portable ZIP, and record hashes."""
import argparse
import datetime
import hashlib
import json
import os
import platform
import shutil
import subprocess
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--target", required=True)
parser.add_argument("--label", required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
version = json.loads((root / "package.json").read_text())["version"]
release = root / "src-tauri" / "target" / args.target / "release"
output = root / "dist-release"
output.mkdir(exist_ok=True)
prefix = f"Sift-{version}-{args.label}"
extensions = [".exe"] if "windows" in args.target else [".dmg"] if "darwin" in args.target else [".deb", ".AppImage"]
for extension in extensions:
    matches = [path for path in (release / "bundle").rglob(f"*{extension}") if version in path.name]
    if len(matches) != 1:
        raise SystemExit(f"Expected exactly one {extension} installer for {args.target}: {matches}")
    suffix = "-setup.exe" if extension == ".exe" else extension
    shutil.copy2(matches[0], output / f"{prefix}{suffix}")

if "windows" in args.target:
    executable = release / "sift.exe"
    if not executable.is_file():
        raise SystemExit("Missing standalone Windows executable")
    with zipfile.ZipFile(output / f"{prefix}-portable.zip", "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        archive.write(executable, "Sift/Sift.exe")
        archive.writestr("Sift/START-HERE.txt", f"Sift {version}\n\nRun Sift.exe. Requires Microsoft Edge WebView2; use the setup installer if it is missing.\nThe initial workspace is labeled sample data. Click Scan a folder for real files.\nReview the selected files, then choose Recycle Bin / trash (default), permanent deletion, or best-effort overwrite-and-delete. Permanent actions require typed confirmation. Overwrite is not certified erasure of SSDs, snapshots, or backups.\n\nThis is an unsigned preview. See VALIDATION.md and the repository README for limits.\n")
        for name in ["LICENSE", "THIRD_PARTY_NOTICES.txt", "VALIDATION.md", "PRODUCT-GOALS.md"]:
            archive.write(root / name, f"Sift/{name}")

def command(*parts):
    return subprocess.check_output(parts, text=True, cwd=root).strip()

info = {
    "version": version,
    "target": args.target,
    "platform": args.label,
    "commit": os.environ.get("GITHUB_SHA") or command("git", "rev-parse", "HEAD"),
    "builtAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "buildHost": platform.platform(),
    "rust": command("rustc", "--version"),
    "node": command("node", "--version"),
    "workflowRun": f"https://github.com/{os.environ.get('GITHUB_REPOSITORY', 'sarvesh-kr/sift')}/actions/runs/{os.environ.get('GITHUB_RUN_ID', '')}",
    "signed": False,
    "interactiveDesktopQualification": "Not performed by this workflow",
}
(output / f"{prefix}-build-info.json").write_text(json.dumps(info, indent=2) + "\n")
checksums = []
for path in sorted(output.glob(f"{prefix}*")):
    if path.suffix == ".zip":
        with zipfile.ZipFile(path) as archive:
            if archive.testzip() is not None:
                raise SystemExit(f"Invalid archive: {path}")
    checksums.append(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}")
    print(path.name)
(output / f"SHA256SUMS-{args.label}.txt").write_text("\n".join(checksums) + "\n")
