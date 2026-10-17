"""Regenerate disposable, synthetic cross-platform carving fixtures (Pillow needed)."""
from pathlib import Path
from io import BytesIO
import hashlib, json, struct, wave, zipfile
from PIL import Image
root = Path(__file__).resolve().parents[1] / 'recovery-fixtures'
root.mkdir(exist_ok=True)
files=[]
for extension, fmt in [('png','PNG'),('jpg','JPEG'),('webp','WEBP')]:
    out=BytesIO(); Image.new('RGB',(24,16),(45,115,80)).save(out,format=fmt); files.append((extension,out.getvalue()))
files.append(('pdf',b'%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\nstartxref\n0\n%%EOF'))
out=BytesIO()
with zipfile.ZipFile(out,'w',compression=zipfile.ZIP_STORED) as z:
    info=zipfile.ZipInfo('note.txt',date_time=(2020,1,1,0,0,0)); z.writestr(info,'Disposable recovery test content.\n')
files.append(('zip',out.getvalue()))
out=BytesIO()
with wave.open(out,'wb') as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(8000); w.writeframes(struct.pack('<32h',*([0,1,-1,2]*8)))
files.append(('wav',out.getvalue()))
image=bytearray(b'SIFT-SYNTHETIC-IMAGE\x00'+bytes(237)); expected=[]
for extension,data in files:
    expected.append(dict(offset=len(image),size=len(data),format=extension,sha256=hashlib.sha256(data).hexdigest()))
    image.extend(data); image.extend(bytes(137))
# False/truncated signatures must never become exportable candidates.
image.extend(b'\x89PNG\r\n\x1a\n'+bytes(50)+b'PK\x03\x04'+bytes(80)+b'RIFF\xff\xff\xff\xffWAVE')
(root/'sample.img').write_bytes(image)
(root/'expected.json').write_text(json.dumps(expected,indent=2)+'\n')
(root/'expected.tsv').write_text(''.join(f"{f['format']}\t{f['offset']}\t{f['size']}\t{f['sha256']}\n" for f in expected))
print(f'{len(image)} bytes; {len(expected)} candidates')
