#!/usr/bin/env bash
# Tiny ADB helpers for screenshots and taps by visible text. Usage:
#   tools/phone.sh shot out.png | tap "Setup" | dump | launch
export MSYS_NO_PATHCONV=1
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
case "$1" in
  launch) "$ADB" shell am start -n com.govu.hisaab/.MainActivity >/dev/null ;;
  shot)   "$ADB" exec-out screencap -p > "$2" ;;
  dump)   "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null && "$ADB" exec-out cat /sdcard/ui.xml ;;
  tap)
    xml=$("$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null && "$ADB" exec-out cat /sdcard/ui.xml)
    b=$(echo "$xml" | python -c "
import sys,re
x=sys.stdin.read(); t=sys.argv[1]
for m in re.finditer(r'<node [^>]*>', x):
    n=m.group(0)
    if re.search(r'(text|content-desc)=\"'+re.escape(t)+'\"', n):
        a=list(map(int,re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',n).groups()))
        print((a[0]+a[2])//2,(a[1]+a[3])//2); break
" "$2")
    [ -z "$b" ] && { echo "not found: $2" >&2; exit 1; }
    "$ADB" shell input tap $b ;;
  swipeup) "$ADB" shell input swipe 540 1800 540 700 300 ;;
esac
