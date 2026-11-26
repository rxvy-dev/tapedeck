#!/usr/bin/env bash
# Makes a folder of original, royalty-free demo songs (synthesized with ffmpeg) with tags and cover art.
# Used by CI to take the screenshots, and handy for testing on an emulator:
#   tools/make-demo-music.sh demo-music && adb push demo-music /sdcard/Music/
set -euo pipefail
OUT=${1:-demo-music}; SECS=${SECS:-120}
command -v ffmpeg >/dev/null || { echo "need ffmpeg"; exit 1; }
mkdir -p "$OUT"; TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

# title | artist | album | bpm | bass Hz | cover colours (c0 c1 c2) | cover source
TRACKS=(
"Neon Arcade|Pixel Harbor|Night Drive|118|55|0xff2e88 0x2b0b5c 0x00e5ff|gradients"
"Magnetic Tape|The Cassettes|Side A|124|62|0xffb000 0xc8460f 0x2b2118|gradients"
"Glass Elevator|Lumen Park|Skylines|100|49|0x8fd3ff 0x2a6bd8 0xffffff|gradients"
"Starfield Run|Orbit Club|Satellite Hearts|140|58|black|mandelbrot"
"Paper Planes Over Tokyo|Mira Sol|Postcards|92|52|0xffd1dc 0xff6f61 0xfff4c2|gradients"
"Crystal Caverns|Pixel Harbor|Night Drive|128|44|0x4dffc3 0x0b3d5c 0x9b5cff|gradients"
"Low Battery|The Cassettes|Side B|110|65|0x9bbc0f 0x306230 0x0f380f|gradients"
"Velvet Static|Lumen Park|Skylines|96|47|0x5b2a86 0xe0607e 0x1a1a40|gradients"
"Fireflies at 3AM|Mira Sol|Postcards|84|41|0x0b1d3a 0xffe066 0x16324f|gradients"
"Hyperjump|Orbit Club|Satellite Hearts|150|60|black|life"
"Sunday Rewind|Juniper & Coal|Rewind|105|50|0xf4a259 0x5b8e7d 0xf4e285|gradients"
"Last Train Home|Juniper & Coal|Rewind|122|46|0x264653 0xe76f51 0x2a9d8f|gradients"
)

n=0
for t in "${TRACKS[@]}"; do
  n=$((n + 1)); IFS='|' read -r title artist album bpm bass cols src <<<"$t"
  P=$(awk "BEGIN{printf \"%.6f\", 60/$bpm}")      # seconds per beat
  H=$(awk "BEGIN{printf \"%.6f\", 30/$bpm}")      # half a beat
  # kick on every beat, offbeat hi-hat, a bass line that walks every 2 bars, and a soft pad
  EXPR="0.75*sin(2*PI*(45+90*exp(-mod(t,$P)*28))*mod(t,$P))*exp(-mod(t,$P)*7)"
  EXPR="$EXPR+0.18*sin(2*PI*$bass*(1+0.5*gt(mod(t,8*$P),4*$P))*t)*(0.6+0.4*exp(-mod(t,$P)*3))"
  EXPR="$EXPR+0.07*(random(0)-0.5)*exp(-mod(t+$H,$P)*45)"
  EXPR="$EXPR+0.05*sin(2*PI*$bass*4*t)*(0.5+0.5*sin(2*PI*t/(16*$P)))"
  ffmpeg -nostdin -loglevel error -y -f lavfi -i "aevalsrc=exprs='$EXPR|$EXPR':s=44100:d=$SECS" -af "afade=t=in:d=1,afade=t=out:st=$((SECS - 3)):d=3" "$TMP/a.wav"
  read -r c0 c1 c2 <<<"$cols"
  case "$src" in
    mandelbrot) ffmpeg -nostdin -loglevel error -y -f lavfi -i "mandelbrot=s=500x500:start_scale=1.6:end_pts=1" -frames:v 1 "$TMP/c.png" ;;
    life)       ffmpeg -nostdin -loglevel error -y -f lavfi -i "life=s=125x125:mold=10:r=10:ratio=0.2:death_color=#1a0b3a:life_color=#00ffcc:mold_color=#ff2e88,scale=500:500:flags=neighbor" -ss 2 -frames:v 1 "$TMP/c.png" ;;
    *)          ffmpeg -nostdin -loglevel error -y -f lavfi -i "gradients=s=500x500:c0=$c0:c1=$c1:c2=$c2:nb_colors=3:seed=$((n * 7)):speed=0.01" -frames:v 1 "$TMP/c.png" ;;
  esac
  f="$OUT/$(printf '%02d' "$n") - $title.mp3"
  ffmpeg -nostdin -loglevel error -y -i "$TMP/a.wav" -i "$TMP/c.png" -map 0:a -map 1:v -c:a libmp3lame -b:a 128k -c:v mjpeg -disposition:v attached_pic \
    -id3v2_version 3 -metadata title="$title" -metadata artist="$artist" -metadata album="$album" -metadata track="$n" -metadata genre="Demo" "$f"
  echo "made $f"
done
