"""Wait for a real Android accessibility node, optionally tapping its bounds."""
import argparse
import re
import subprocess
import xml.etree.ElementTree as ET

p=argparse.ArgumentParser();p.add_argument('text');p.add_argument('--tap',action='store_true');p.add_argument('--input');p.add_argument('--exact',action='store_true');p.add_argument('--last',action='store_true');p.add_argument('--class-name');args=p.parse_args()
for _ in range(15):
    subprocess.run(['adb','shell','uiautomator','dump','/data/local/tmp/sift-release-window.xml'],capture_output=True)
    result=subprocess.run(['adb','shell','cat','/data/local/tmp/sift-release-window.xml'],text=True,capture_output=True)
    try: root=ET.fromstring(result.stdout)
    except ET.ParseError: continue
    matches=[n for n in root.iter('node') if (n.attrib.get('text','')==args.text if args.exact else args.text in n.attrib.get('text','')) and n.attrib.get('enabled')=='true' and (args.class_name is None or n.attrib.get('class')==args.class_name)]
    found=matches[-1 if args.last else 0] if matches else None
    if found is None:continue
    if args.tap or args.input is not None:
        coords=list(map(int,re.findall(r'\d+',found.attrib['bounds'])))
        if len(coords)!=4 or coords[2]<=coords[0] or coords[3]<=coords[1]:continue
        subprocess.run(['adb','shell','input','tap',str((coords[0]+coords[2])//2),str((coords[1]+coords[3])//2)],check=True)
    if args.input is not None:
        subprocess.run(['adb','shell','uiautomator','dump','/data/local/tmp/sift-release-window.xml'],capture_output=True)
        subprocess.run(['adb','shell','input','text',args.input],check=True)
        subprocess.run(['adb','shell','input','keyevent','KEYCODE_BACK'],check=True)
    print('Confirmed Android UI: '+args.text)
    break
else:
    from pathlib import Path
    out=Path('android-release-results');out.mkdir(exist_ok=True)
    (out/'failed-window.xml').write_text(result.stdout)
    with (out/'failed-screen.png').open('wb') as screenshot:
        subprocess.run(['adb','exec-out','screencap','-p'],stdout=screenshot)
    print(result.stdout)
    subprocess.run(['adb','shell','ls','-la','/sdcard/Android/data/si.sarvesh.sift/files/SiftTrash'])
    raise SystemExit('Android UI node did not appear: '+args.text)
