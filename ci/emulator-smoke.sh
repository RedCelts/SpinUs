#!/usr/bin/env bash
# CI smoke test on an x86_64 emulator: install the debug APK, open the app
# with built-in Tailscale switched on, wait, and collect any crash output.
set -u
APK=app/build/outputs/apk/debug/app-debug.apk
PKG=com.oniel.music
adb install -r "$APK"
adb logcat -c
adb shell am start -n $PKG/.MainActivity --ez ts_enable true
sleep 75
echo "===== process ====="
adb shell pidof $PKG || echo "NOT RUNNING (crashed?)"
echo "===== crash files ====="
adb shell run-as $PKG sh -c 'for f in files/crash/*; do echo "--- $f"; cat "$f"; done' 2>&1 || true
echo "===== logcat: crashes ====="
adb logcat -d -b crash 2>&1 | tail -n 200
echo "===== logcat: app / Go ====="
adb logcat -d 2>&1 | grep -E "OnielTailscale|GoLog|panic|fatal error|AndroidRuntime|FATAL|DEBUG  |libgojni|$PKG" | tail -n 300
echo "===== second launch (crash-loop guard) ====="
adb logcat -c
adb shell am start -n $PKG/.MainActivity
sleep 20
adb shell pidof $PKG || echo "NOT RUNNING after relaunch"
adb logcat -d -b crash 2>&1 | tail -n 60
exit 0
