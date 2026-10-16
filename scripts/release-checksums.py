"""Create one checksum manifest from the successfully downloaded build artifacts."""
import hashlib
from pathlib import Path

root = Path(__file__).resolve().parents[1] / "dist-release"
files = sorted(path for path in root.iterdir() if path.is_file() and not path.name.startswith("SHA256SUMS"))
for label in ["windows-x64", "linux-x64", "macos-apple-silicon", "macos-intel"]:
    if not any(label in path.name for path in files):
        raise SystemExit(f"Missing platform: {label}")
(root / "SHA256SUMS.txt").write_text("\n".join(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}" for path in files) + "\n")
print(f"Checksums recorded for {len(files)} release files.")
