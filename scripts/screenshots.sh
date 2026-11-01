#!/usr/bin/env bash
# Retakes the pictures in docs/screens, against a made-up server (see
# scripts/screenshot_server.py), so they never show anybody's real chats.
#
#   scripts/screenshots.sh [emulator-serial]
#
# Runs on an emulator only and refuses anything else, because it clears the
# app's data: that emulator's app is signed out when it ends.
set -eu
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1
ADB="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}/platform-tools/adb.exe"
[ -x "$ADB" ] || ADB=adb
export ANDROID_SERIAL="${1:-$("$ADB" devices | awk '/^emulator-.*device$/{print $1; exit}')}"
case "$ANDROID_SERIAL" in
  emulator-*) ;;
  *) echo "Needs a running emulator: this clears the app's data." >&2; exit 1 ;;
esac

PORT=18999
OUT=docs/screens
E="bash scripts/emu.sh"
pause() { python -c "import time;time.sleep($1)"; }
# Taps the first thing on screen matching a pattern; fails loudly if it is not there.
press() {
  local at
  at=$($E tree | grep -E "$1" | head -1 | cut -d' ' -f1,2)
  [ -n "$at" ] || { echo "Nothing on screen matches: $1" >&2; $E tree >&2; exit 1; }
  # shellcheck disable=SC2086
  $E tap $at
  pause "${2:-3}"
}

./gradlew :app:assembleDebug --console=plain -q
"$ADB" install -r -g app/build/outputs/apk/debug/app-debug.apk > /dev/null

python scripts/screenshot_server.py "$PORT" &
SERVER=$!
finish() {
  kill "$SERVER" 2>/dev/null || true
  "$ADB" reverse --remove "tcp:$PORT" > /dev/null 2>&1 || true
  "$ADB" shell am broadcast -a com.android.systemui.demo -e command exit > /dev/null 2>&1 || true
  "$ADB" shell pm clear io.mszymanski.orknux.mobile > /dev/null 2>&1 || true
}
trap finish EXIT
"$ADB" reverse "tcp:$PORT" "tcp:$PORT" > /dev/null

# A tidy status bar, the same in every release's pictures.
"$ADB" shell settings put global sysui_demo_allowed 1
"$ADB" shell am broadcast -a com.android.systemui.demo -e command enter > /dev/null
"$ADB" shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1200 > /dev/null
"$ADB" shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false > /dev/null
"$ADB" shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false > /dev/null

mkdir -p "$OUT"
"$ADB" shell pm clear io.mszymanski.orknux.mobile > /dev/null
# Clearing takes the permissions with it, and voice mode's prompt would be in the picture.
"$ADB" shell pm grant io.mszymanski.orknux.mobile android.permission.RECORD_AUDIO
"$ADB" shell am start -n io.mszymanski.orknux.mobile/.MainActivity > /dev/null
pause 5
$E shot "$OUT/1-connect.png"

press 'orknux.example.com' 1
$E type "localhost:$PORT"
pause 1
$E key KEYCODE_BACK
pause 1
press ' Continue$' 5
$E shot "$OUT/2-chats.png"

press 'Drafting the customer update' 4
$E shot "$OUT/3-chat.png"

press ' Voice$' 4
$E shot "$OUT/4-voice.png"
press ' End$' 2
$E key KEYCODE_BACK
pause 2

press 'Retiring the legacy billing webhook' 4
$E shot "$OUT/5-plan.png"
$E key KEYCODE_BACK
pause 2

press ' alice$' 2
press ' Options$' 2
$E shot "$OUT/6-options.png"

ls "$OUT"
