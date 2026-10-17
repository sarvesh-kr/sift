"""Wait for a real Android accessibility node, optionally tapping its bounds."""
import argparse
import re
import subprocess
import xml.etree.ElementTree as ET

p=argparse.ArgumentParser();p.add_argument('text');p.add_argument('--tap',action='store_true');args=p.parse_args()
for _ in range(15):
    subprocess.run(['adb','shell','uiautomator','dump','/sdcard/sift-release-window.xml'],capture_output=True)
    result=subprocess.run(['adb','shell','cat','/sdcard/sift-release-window.xml'],text=True,capture_output=True)
    try: root=ET.fromstring(result.stdout)
    except ET.ParseError: continue
    found=next((n for n in root.iter('node') if args.text in n.attrib.get('text','') and n.attrib.get('enabled')=='true'),None)
    if found is None:continue
    if args.tap:
        coords=list(map(int,re.findall(r'\d+',found.attrib['bounds'])))
        if len(coords)!=4 or coords[2]<=coords[0] or coords[3]<=coords[1]:continue
        subprocess.run(['adb','shell','input','tap',str((coords[0]+coords[2])//2),str((coords[1]+coords[3])//2)],check=True)
    print('Confirmed Android UI: '+args.text)
    break
else:
    raise SystemExit('Android UI node did not appear: '+args.text)
