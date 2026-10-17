#!/usr/bin/env bash
set -euo pipefail
mkdir -p android-device-results
adb install -r test-apks/debug/app-debug.apk
adb install -r -t test-apks/androidTest/debug/app-debug-androidTest.apk
# Qualify the installed root helper's read-only geometry handshake on the disposable
# userdebug emulator, before any instrumentation. The app itself stays unprivileged.
adb root > android-device-results/root-helper-access.txt 2>&1 || true
adb wait-for-device
if [ "$(adb shell id -u | tr -d '\r')" = 0 ]; then
  apk_path=$(adb shell pm path si.sarvesh.sift | sed -n 's/^package://p' | tr -d '\r' | head -1)
  # Both device jobs use x86_64. PackageManager's dumpsys field name differs
  # between API levels; derive the installed native directory from the base APK.
  native_dir="${apk_path%/*}/lib/x86_64"
  adb shell -T "test -x '$native_dir/libsift_root_reader.so'"
  adb shell -T "'$native_dir/libsift_root_reader.so' --storage-id primary" </dev/null > android-device-results/root-helper-header.bin 2> android-device-results/root-helper-error.txt
  python3 - <<'PY'
from pathlib import Path
import struct
header=Path('android-device-results/root-helper-header.bin').read_bytes()
assert len(header)==24 and header[:8]==b'SIFTRD01', 'Root helper handshake failed'
size, device=struct.unpack('>QQ',header[8:])
assert size >= 4096 and device > 0
print('Installed read-only helper opened disposable userdata; valid geometry; no scan/write requests.')
PY
fi
adb unroot > /dev/null 2>&1 || true
adb wait-for-device
# Verify the real first-launch permission screen before granting anything.
adb shell appops set si.sarvesh.sift MANAGE_EXTERNAL_STORAGE deny
adb shell am start -n si.sarvesh.sift/.MainActivity
python3 scripts/check-android-screen.py 'Clean data' --tap --exact
onboarding=0
for attempt in $(seq 1 10); do
  adb shell uiautomator dump /sdcard/sift-window.xml >/dev/null 2>&1 || true
  if adb shell cat /sdcard/sift-window.xml | grep -q 'Allow shared storage access'; then onboarding=1; break; fi
done
if [ "$onboarding" != 1 ]; then adb logcat -d -t 1000; exit 1; fi
adb exec-out screencap -p > "android-device-results/onboarding-api-$1.png"
adb shell am force-stop si.sarvesh.sift
# Permission revocation kills Android's app process. Exercise permission-free image
# recovery in its own invocation while access is already denied, then stop before granting.
adb shell am instrument -w -r -e class si.sarvesh.sift.RecoveryWithoutPermissionTest si.sarvesh.sift.test/androidx.test.runner.AndroidJUnitRunner > android-device-results/no-storage-access.txt
adb shell am force-stop si.sarvesh.sift
adb shell appops set si.sarvesh.sift MANAGE_EXTERNAL_STORAGE allow
adb shell am instrument -w -r -e notClass si.sarvesh.sift.RecoveryWithoutPermissionTest si.sarvesh.sift.test/androidx.test.runner.AndroidJUnitRunner > android-device-results/instrumentation.txt
adb pull /sdcard/Android/data/si.sarvesh.sift/cache/sift-grid.png "android-device-results/grid-api-$1.png" || true
adb pull /sdcard/Android/data/si.sarvesh.sift/cache/sift-recovery.png "android-device-results/recovery-api-$1.png" || true
adb logcat -d -t 2000 > android-device-results/logcat.txt
cat android-device-results/no-storage-access.txt
cat android-device-results/instrumentation.txt
python3 - <<'PY'
from pathlib import Path
import re
for name in ['no-storage-access.txt','instrumentation.txt']:
    s=Path('android-device-results',name).read_text()
    if not re.search(r'OK \(\d+ tests?\)', s) or any(x in s for x in ['FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed']):
        raise SystemExit(f'Android instrumented tests did not all pass: {name}')
PY
adb shell am start -n si.sarvesh.sift/.MainActivity
# Give the first Compose frame time to draw before capturing the actual emulator UI.
for attempt in $(seq 1 10); do
  adb shell uiautomator dump /sdcard/sift-window.xml >/dev/null 2>&1 || true
  if adb shell cat /sdcard/sift-window.xml | grep -q 'Make room'; then break; fi
done
adb exec-out screencap -p > "android-device-results/phone-api-$1.png"
adb logcat -d -t 1000 > android-device-results/logcat.txt
