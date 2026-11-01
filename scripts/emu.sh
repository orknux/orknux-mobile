#!/usr/bin/env bash
# Small helpers for driving the app on a running emulator:
#   scripts/emu.sh shot <file.png>      take a screenshot
#   scripts/emu.sh tree                 list what is on screen, with tap coordinates
#   scripts/emu.sh tap <x> <y>          tap
#   scripts/emu.sh type <text>          type (spaces allowed)
#   scripts/emu.sh key <keycode>        press a key, e.g. KEYCODE_BACK
#   scripts/emu.sh install              build and install, then launch
#   scripts/emu.sh log                  the app's crashes since the log was cleared
set -u
ADB="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}/platform-tools/adb.exe"
export MSYS_NO_PATHCONV=1
case "$1" in
  shot) "$ADB" exec-out screencap -p > "$2" ;;
  tree)
    "$ADB" shell uiautomator dump /sdcard/ui.xml > /dev/null
    "$ADB" exec-out cat /sdcard/ui.xml | python -c "
import re,sys,html
for m in re.finditer(r'<node [^>]*>', sys.stdin.buffer.read().decode('utf-8')):
    n=m.group(0); f=lambda k:(re.search(k+r'=\"([^\"]*)\"',n) or [0,''])[1]; t=f('text'); d=f('content-desc')
    if not (t or d): continue
    a,b,c,e=map(int,re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]',n).groups())
    print((a+c)//2,(b+e)//2,html.unescape(t or d)[:90].encode('ascii','replace').decode())
"
    ;;
  tap) "$ADB" shell input tap "$2" "$3" ;;
  type) "$ADB" shell input text "$(printf '%s' "$2" | sed 's/ /%s/g')" ;;
  key) "$ADB" shell input keyevent "$2" ;;
  install)
    ./gradlew :app:assembleDebug --console=plain -q 2>&1 | grep -E '^e: |FAILED'
    "$ADB" install -r -g app/build/outputs/apk/debug/app-debug.apk | tail -1
    "$ADB" logcat -c
    "$ADB" shell am force-stop io.mszymanski.orknux.mobile
    "$ADB" shell am start -n io.mszymanski.orknux.mobile/.MainActivity > /dev/null
    ;;
  log) "$ADB" logcat -d -s AndroidRuntime:E | tail -40 ;;
esac
