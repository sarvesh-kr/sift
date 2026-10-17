#!/usr/bin/env bash
set -euo pipefail
mkdir -p android-release-results
# The CI-only signer exercises the exact R8 APK without exposing the production key.
# This temporary signer and APK are never published or uploaded as release assets.
keytool -genkeypair -keystore "$RUNNER_TEMP/smoke.p12" -storetype PKCS12 -storepass android -keypass android -alias smoke -keyalg RSA -validity 2 -dname 'CN=Disposable Emulator Test'
unsigned_apk=$(find candidate -name '*-unsigned.apk' -print -quit)
if [ -n "$unsigned_apk" ]; then
  "$ANDROID_HOME/build-tools/35.0.0/apksigner" sign --ks "$RUNNER_TEMP/smoke.p12" --ks-pass pass:android --out "$RUNNER_TEMP/smoke.apk" "$unsigned_apk"
else
  find candidate -name '*.apk' -exec cp '{}' "$RUNNER_TEMP/smoke.apk" \;
fi
adb install "$RUNNER_TEMP/smoke.apk"
adb shell appops set si.sarvesh.sift MANAGE_EXTERNAL_STORAGE deny
adb shell am start -n si.sarvesh.sift/.MainActivity
python scripts/check-android-screen.py 'Allow shared storage access'
adb exec-out screencap -p > android-release-results/onboarding.png
adb shell am force-stop si.sarvesh.sift
adb shell appops set si.sarvesh.sift MANAGE_EXTERNAL_STORAGE allow
printf 'Disposable release smoke fixture\n' > "$RUNNER_TEMP/Sift-release-fixture.txt"
adb push "$RUNNER_TEMP/Sift-release-fixture.txt" /sdcard/Download/Sift-release-fixture.txt
adb shell am start -n si.sarvesh.sift/.MainActivity
python scripts/check-android-screen.py 'Scan shared storage' --tap
python scripts/check-android-screen.py 'files indexed'
adb exec-out screencap -p > android-release-results/scanned.png
python scripts/check-android-screen.py 'Files' --tap
python scripts/check-android-screen.py 'Search files' --input 'Sift-release-fixture.txt'
python scripts/check-android-screen.py 'Sift-release-fixture.txt'
adb exec-out screencap -p > android-release-results/files.png
python scripts/check-android-screen.py 'Sift-release-fixture.txt' --tap --exact --last
python scripts/check-android-screen.py 'Review this file only' --tap --exact
python scripts/check-android-screen.py 'Move to Sift trash' --tap --exact --last
python scripts/check-android-screen.py '1 file moved to Sift trash'
# Restart the whole OS while the fixture is in trash, then verify its
# private record remains usable after mount/FUSE identities can change.
adb reboot
adb wait-for-device
booted=0
for attempt in $(seq 1 120); do
  if [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; then booted=1; break; fi
  sleep 1
done
if [ "$booted" != 1 ]; then exit 1; fi
adb shell input keyevent KEYCODE_MENU
adb shell wm dismiss-keyguard
adb shell am start -n si.sarvesh.sift/.MainActivity
python scripts/check-android-screen.py 'Trash' --tap --exact
python scripts/check-android-screen.py '' --class-name android.widget.CheckBox --tap
python scripts/check-android-screen.py 'Restore' --tap --exact
python scripts/check-android-screen.py 'Restore files' --tap --exact
python scripts/check-android-screen.py '1 file restored'
adb exec-out screencap -p > android-release-results/restored.png
python - <<'CHECK'
import subprocess
contents=subprocess.check_output(['adb','shell','cat','/sdcard/Download/Sift-release-fixture.txt'],text=True)
assert contents.strip()=='Disposable release smoke fixture'
print('Optimized APK cleanup UI moved and restored the exact original contents.')
CHECK
app_pid=$(adb shell pidof -s si.sarvesh.sift)
adb logcat -d --pid "$app_pid" -t 1000 > android-release-results/logcat.txt
if grep -q 'FATAL EXCEPTION' android-release-results/logcat.txt; then exit 1; fi
adb shell rm /sdcard/Download/Sift-release-fixture.txt
