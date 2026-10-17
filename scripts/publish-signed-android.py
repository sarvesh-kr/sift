"""Publish an offline-signed APK after checking it against tested CI contents.

Only public signed bytes are staged as Git blobs. Private signing material must
never be supplied to this workflow. Regular signed CI releases use android.yml.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile


def gh_json(*args):
    return json.loads(subprocess.check_output(['gh', *args], text=True))


repo = os.environ['GITHUB_REPOSITORY']
run_id = os.environ['SOURCE_RUN']
commit = os.environ['SOURCE_COMMIT']
assert re.fullmatch(r'\d+', run_id)
assert re.fullmatch(r'[0-9a-f]{40}', commit)
run = gh_json('run', 'view', run_id, '--json', 'headSha,conclusion,jobs')
assert run['headSha'] == commit and run['conclusion'] == 'success'
for name in ['build', 'device (30)', 'device (36)']:
    assert any(j['name'] == name and j['conclusion'] == 'success' for j in run['jobs'])
subprocess.run(['gh', 'run', 'download', run_id, '--name', 'sift-android-package', '--dir', 'candidate'], check=True)
original_info = list(Path('candidate').glob('*-build-info.json'))
assert len(original_info) == 1
original = json.loads(original_info[0].read_text())
assert original['commit'] == commit and original['signed'] is False
version = original['version']
assert re.fullmatch(r'\d+\.\d+\.\d+', version)
output = Path('signed-release')
output.mkdir()
apk = output / f'Sift-{version}-android.apk'
metadata = output / f'Sift-{version}-android-build-info.json'
for field, target in [('APK_BLOB', apk), ('METADATA_BLOB', metadata)]:
    sha = os.environ[field]
    assert re.fullmatch(r'[0-9a-f]{40}', sha)
    blob = gh_json('api', f'repos/{repo}/git/blobs/{sha}')
    assert blob['encoding'] == 'base64' and blob['sha'] == sha
    contents = base64.b64decode(blob['content'])
    assert hashlib.sha1(f'blob {len(contents)}\0'.encode() + contents).hexdigest() == sha
    target.write_bytes(contents)
info = json.loads(metadata.read_text())
for key in ['version', 'versionCode', 'minimumAndroid', 'targetSdk', 'commit', 'universal', 'networkPermission']:
    assert info[key] == original[key], key
assert info['signed'] is True
certificate = 'e9077ccd2d0ced08d77ae918fd2a665eea95a7190710a99ea0e3ae61df52334a'
assert info['signingCertificateSha256'] == certificate
unsigned = Path('candidate') / f'Sift-{version}-android-unsigned.apk'
with zipfile.ZipFile(unsigned) as source, zipfile.ZipFile(apk) as signed:
    assert len(signed.namelist()) == len(set(signed.namelist()))
    assert signed.testzip() is None
    extras = set(signed.namelist()) - set(source.namelist())
    assert extras <= {'META-INF/MANIFEST.MF', 'META-INF/SIFT.SF', 'META-INF/SIFT.RSA'}
    assert not set(source.namelist()) - set(signed.namelist())
    for name in source.namelist():
        assert source.read(name) == signed.read(name), name
build_tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
certs = subprocess.check_output([str(build_tools / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
assert f'Signer #1 certificate SHA-256 digest: {certificate}' in certs
subprocess.run([str(build_tools / 'zipalign'), '-c', '-P', '16', '4', str(apk)], check=True)
checksums = output / 'SHA256SUMS-android.txt'
checksums.write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in sorted([apk, metadata])))
release = gh_json('release', 'view', f'android-v{version}', '--json', 'isDraft,targetCommitish')
assert release['isDraft'] and release['targetCommitish'] == commit
subprocess.run(['gh', 'release', 'upload', f'android-v{version}', str(apk), str(metadata), str(checksums), '--clobber'], check=True)
print('Verified signed APK matches tested CI contents; three assets attached to draft release.')
