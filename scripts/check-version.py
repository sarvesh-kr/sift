"""Reject mismatched manifests or version tags before producing a package."""
import json
import os
import tomllib
from pathlib import Path

root = Path(__file__).resolve().parents[1]
versions = {
    "package.json": json.loads((root / "package.json").read_text())["version"],
    "src-tauri/tauri.conf.json": json.loads((root / "src-tauri/tauri.conf.json").read_text())["version"],
}
for name in ["src-tauri/Cargo.toml", "native-core/Cargo.toml"]:
    versions[name] = tomllib.loads((root / name).read_text())["package"]["version"]
if len(set(versions.values())) != 1:
    raise SystemExit(f"Manifest versions do not match: {versions}")
version = next(iter(versions.values()))
if os.environ.get("GITHUB_REF_TYPE") == "tag" and os.environ.get("GITHUB_REF_NAME") != f"v{version}":
    raise SystemExit(f"The release tag must be v{version}.")
print(f"Release manifests agree: {version}")
