#!/usr/bin/env bash
# Takes the README screenshots on a running emulator/phone (used by .github/workflows/screenshots.yml).
#   needs: TapeDeck.apk built, demo-music/ made by tools/make-demo-music.sh, adb connected
set -x
PKG=com.rxvy.tapedeck
OUT=${OUT:-screenshots}
mkdir -p "$OUT"

adb wait-for-device
adb install -r -g TapeDeck.apk || adb install -g TapeDeck.apk
adb shell pm grant $PKG android.permission.READ_MEDIA_AUDIO || true
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true

# demo songs -> MediaStore
adb shell mkdir -p /sdcard/Music/TapeDeckDemo
for f in demo-music/*.mp3; do adb push "$f" "/sdcard/Music/TapeDeckDemo/$(basename "$f")" >/dev/null; done
adb shell content call --uri content://media --method scan_volume --arg external_primary || true
for f in demo-music/*.mp3; do adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Music/TapeDeckDemo/$(basename "$f" | sed 's/ /%20/g')" >/dev/null || true; done
# wait until MediaStore has indexed all the demo songs
want=$(ls demo-music/*.mp3 | wc -l)
for i in $(seq 1 30); do
  have=$(adb shell content query --uri content://media/external/audio/media --projection title | grep -c "Row:" || true)
  echo "indexed $have / $want"; [ "$have" -ge "$want" ] && break; sleep 3
done
sleep 2

# the emulator's launcher is slow on CI and pops "isn't responding" dialogs: hide them
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put secure anr_show_background 0 || true
adb shell am force-stop com.google.android.apps.nexuslauncher || true
adb shell input keyevent KEYCODE_BACK || true

# tidy status bar (12:00, full battery, no notification icons)
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1200
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e mobile show -e datatype none -e level 4
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false

shot() {   # shot NAME WAIT_SECONDS am-start-extras...
  local name=$1 wait=$2; shift 2
  adb shell am force-stop $PKG
  adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
  adb shell am start -W -n $PKG/.MainActivity "$@"
  sleep "$wait"
  adb exec-out screencap -p > "$OUT/$name.png"
}

# themes by number (names with spaces get split by the device shell):
# 0 iPod Classic, 1 Windows XP, 2 Windows 95, 3 Game Boy, 4 Winamp, 5 Cassette, 6 iTunes 2011, 7 secret
shot itunes     5 --ei td_skin 6 --ei td_play 0 --ef td_seek 0.35
shot ipod       5 --ei td_skin 0 --ei td_play 1 --ef td_seek 0.30
shot xp         5 --ei td_skin 1 --ei td_play 2 --ef td_seek 0.42
shot win95      5 --ei td_skin 2 --ei td_play 3 --ef td_seek 0.25
shot gameboy    5 --ei td_skin 3 --ei td_play 4 --ef td_seek 0.55
shot winamp     5 --ei td_skin 4 --ei td_play 5 --ef td_seek 0.38
shot cassette   5 --ei td_skin 5 --ei td_play 6 --ef td_seek 0.61
shot undertale  6 --ei td_skin 7 --ei td_play 7 --ef td_seek 0.20
shot fight     14 --ei td_skin 7 --ei td_play 9 --ef td_seek 0.15 --ez td_fight true

# the media notification
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible true
adb shell cmd statusbar expand-notifications
sleep 3
adb exec-out screencap -p > "$OUT/notification.png"
adb shell cmd statusbar collapse

adb logcat -d -t 400 > logcat.txt || true
ls -la "$OUT"
