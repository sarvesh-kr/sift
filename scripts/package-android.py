"""Collect a universal Android APK without confusing unsigned builds with installs."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
config = (root/'android/app/build.gradle.kts').read_text()
version = re.search(r'versionName = "([^"]+)"', config)[1]
code = int(re.search(r'versionCode = (\d+)', config)[1])
if os.environ.get('GITHUB_REF_TYPE') == 'tag' and os.environ.get('GITHUB_REF_NAME') != f'android-v{version}':
    raise SystemExit('Android tag must match versionName')
source = root/'android/app/build/outputs/apk/release'
signed = (source/'app-release.apk').is_file()
apk = source/('app-release.apk' if signed else 'app-release-unsigned.apk')
if not apk.is_file(): raise SystemExit('Android APK missing')
with zipfile.ZipFile(apk) as archive:
    if any(name in archive.namelist() for name in ['assets/sample.img','assets/expected.json','assets/expected.tsv']):
        raise SystemExit('Debug recovery fixtures must never ship in a release APK')
output = root/'dist-android'; output.mkdir(exist_ok=True)
name = f'Sift-{version}-android'+('' if signed else '-unsigned')+'.apk'
shutil.copy2(apk, output/name)
metadata = {'version':version,'versionCode':code,'minimumAndroid':'11 (API 30)', 'targetSdk':36,
            'commit':os.environ.get('GITHUB_SHA') or subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),
            'signed':signed,'universal':True,'networkPermission':False,
            'workflowRun':f"https://github.com/{os.environ.get('GITHUB_REPOSITORY','sarvesh-kr/sift')}/actions/runs/{os.environ.get('GITHUB_RUN_ID','')}"}
if signed:
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk: raise SystemExit('ANDROID_HOME required to verify the signed APK')
    result = subprocess.check_output([str(Path(sdk)/'build-tools/35.0.0/apksigner'),'verify','--print-certs',str(output/name)],text=True)
    match = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)',result)
    if not match: raise SystemExit('APK signing certificate not verified')
    metadata['signingCertificateSha256'] = match[1].lower()
if os.environ.get('GITHUB_OUTPUT'):
    with open(os.environ['GITHUB_OUTPUT'],'a') as handle: handle.write(f'signed={str(signed).lower()}\n')
(output/f'Sift-{version}-android-build-info.json').write_text(json.dumps(metadata,indent=2)+'\n')
files=sorted(p for p in output.iterdir() if p.name!='SHA256SUMS-android.txt')
(output/'SHA256SUMS-android.txt').write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in files))
print(f'{name}: '+('signed installable APK' if signed else 'unsigned build; sign before installing or publishing'))
