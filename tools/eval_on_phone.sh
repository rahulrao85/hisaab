#!/usr/bin/env bash
# Build, install, open Setup, run the on-phone accuracy check, print the per-merchant log.
set -e
export MSYS_NO_PATHCONV=1
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
cd "$(dirname "$0")/.."
./gradlew :app:assembleDebug --console=plain -q 2>&1 | grep -E "^e:" && exit 1 || true
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
tools/phone.sh launch; sleep 2; tools/phone.sh tap Setup; sleep 1
"$ADB" shell input swipe 540 1500 540 700 400; sleep 1
"$ADB" logcat -c
tools/phone.sh tap "Accuracy check"
for i in $(seq 1 75); do sleep 4; "$ADB" logcat -d -s Hisaab:I | grep -q "EVAL RESULT" && break; done
"$ADB" logcat -d -s Hisaab:I | grep -o "EVAL .*"
