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
sleep 8
adb shell content query --uri content://media/external/audio/media --projection title:artist | head -20

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
  adb shell am start -W -n $PKG/.MainActivity "$@"
  sleep "$wait"
  adb exec-out screencap -p > "$OUT/$name.png"
}

shot itunes     5 --es td_theme "iTunes 2011"  --ei td_play 0 --ef td_seek 0.35
shot ipod       5 --es td_theme "iPod Classic" --ei td_play 1 --ef td_seek 0.30
shot xp         5 --es td_theme "Windows XP"   --ei td_play 2 --ef td_seek 0.42
shot win95      5 --es td_theme "Windows 95"   --ei td_play 3 --ef td_seek 0.25
shot gameboy    5 --es td_theme "Game Boy"     --ei td_play 4 --ef td_seek 0.55
shot winamp     5 --es td_theme "Winamp"       --ei td_play 5 --ef td_seek 0.38
shot cassette   5 --es td_theme "Cassette"     --ei td_play 6 --ef td_seek 0.61
shot undertale  6 --es td_theme "* ???"        --ei td_play 7 --ef td_seek 0.20
shot fight     14 --es td_theme "* ???"        --ei td_play 9 --ef td_seek 0.15 --ez td_fight true

# the media notification
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible true
adb shell cmd statusbar expand-notifications
sleep 3
adb exec-out screencap -p > "$OUT/notification.png"
adb shell cmd statusbar collapse

adb logcat -d -t 400 > logcat.txt || true
ls -la "$OUT"
