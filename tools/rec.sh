#!/usr/bin/env bash
# tools/rec.sh <name> <seconds> "<action script>" : records the phone screen while running the actions.
export MSYS_NO_PATHCONV=1
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
cd "$(dirname "$0")/.."
"$ADB" shell rm -f /sdcard/rec.mp4
"$ADB" shell screenrecord --bit-rate 16000000 --time-limit "$2" /sdcard/rec.mp4 &
pid=$!
sleep 1
eval "$3"
wait $pid
sleep 1
"$ADB" pull /sdcard/rec.mp4 "video/assets/rec/$1.mp4" >/dev/null && ls -la "video/assets/rec/$1.mp4"
