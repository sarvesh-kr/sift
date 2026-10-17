"""Sign a verified CI APK with a private local key; never upload that key."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--directory',type=Path,required=True)
parser.add_argument('--keystore',type=Path,required=True)
parser.add_argument('--password-file',type=Path,required=True)
parser.add_argument('--build-tools',type=Path,required=True)
args=parser.parse_args()
root=args.directory
infos=list(root.glob('Sift-*-android-build-info.json'))
if len(infos)!=1: raise SystemExit('Expected exactly one Android source record')
metadata=json.loads(infos[0].read_text()); version=metadata['version']
source=root/f'Sift-{version}-android-unsigned.apk'; output=root/f'Sift-{version}-android.apk'
checks={name:digest for digest,name in (line.split('  ',1) for line in (root/'SHA256SUMS-android.txt').read_text().splitlines())}
for path in [source,infos[0]]:
 if hashlib.sha256(path.read_bytes()).hexdigest()!=checks[path.name]: raise SystemExit('Input checksum mismatch: '+path.name)
assert metadata['signed'] is False and metadata['versionCode']>0
aligned=root/'aligned.apk'
subprocess.run([str(args.build_tools/'zipalign'),'-P','16','-f','4',str(source),str(aligned)],check=True)
env=os.environ.copy();env['SIFT_LOCAL_SIGN_PASSWORD']=args.password_file.read_text().strip()
subprocess.run([str(args.build_tools/'apksigner'),'sign','--ks',str(args.keystore),'--ks-key-alias','sift',
                '--ks-pass','env:SIFT_LOCAL_SIGN_PASSWORD','--key-pass','env:SIFT_LOCAL_SIGN_PASSWORD',
                '--out',str(output),str(aligned)],env=env,check=True)
verified=subprocess.check_output([str(args.build_tools/'apksigner'),'verify','--verbose','--print-certs',str(output)],text=True)
cert=re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)',verified)
if not cert: raise SystemExit('Missing verified APK signing certificate')
metadata.update(signed=True,signingCertificateSha256=cert[1].lower(),signedAt=datetime.datetime.now(datetime.timezone.utc).isoformat())
infos[0].write_text(json.dumps(metadata,indent=2)+'\n')
(root/'SHA256SUMS-android.txt').write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in [output,infos[0]]))
aligned.unlink()
print(verified.strip())
print('Signed installable APK: '+output.name+'; private key remains outside release files.')
