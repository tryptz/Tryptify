#!/usr/bin/env bash
set -euo pipefail
mkdir -p promo-output/screenshots promo-output/diagnostics
trap 'adb logcat -d > promo-output/diagnostics/logcat.txt 2>&1 || true' EXIT
# A software-rendered emulator is slow enough for the launcher to trip an
# "isn't responding" dialog, which then covers the app. Hide those dialogs;
# capture.py still dismisses any that get through.
adb shell settings put global hide_error_dialogs 1
adb shell settings put secure anr_show_background 0
adb shell wm size 1080x2400
adb shell wm density 420
adb shell settings put system screen_off_timeout 2147483647
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell input keyevent KEYCODE_WAKEUP
adb shell input keyevent KEYCODE_MENU
adb install -r -g promo-output/tryptify.apk
adb shell mkdir -p /sdcard/Music/TryptifyDemo
for file in promo-output/demo-media/*.flac; do
  adb push "$file" /sdcard/Music/TryptifyDemo/
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
    -d "file:///sdcard/Music/TryptifyDemo/$(basename "$file")"
done
python tools/promo/capture.py
