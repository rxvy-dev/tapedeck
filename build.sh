#!/usr/bin/env bash
# Builds TapeDeck.apk. Works with any of:
#   * the official Android SDK (desktop / GitHub Actions)  - uses d8
#   * distro packages (aapt2 + dx, e.g. Debian/Ubuntu)      - uses dx
#   * Termux on an Android phone (see termux-build.sh)      - uses ecj + dx
set -euo pipefail
cd "$(dirname "$0")"
say() { printf '>> %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# ---------- find the tools ----------
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
BTD=()
for d in $(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V -r) /usr/lib/android-sdk/build-tools/*/; do [ -d "$d" ] && BTD+=("${d%/}"); done
tool() { local d; for d in "${BTD[@]:-}"; do [ -n "$d" ] && [ -x "$d/$1" ] && { echo "$d/$1"; return; }; done; command -v "$1" 2>/dev/null || true; }
AAPT2=$(tool aapt2); D8=$(tool d8); DX=$(tool dx); ZIPALIGN=$(tool zipalign); APKSIGNER=$(tool apksigner)
[ -n "$AAPT2" ]    || die "aapt2 not found (SDK build-tools, 'apt install aapt', or Termux 'pkg install aapt2')"
[ -n "$APKSIGNER" ] || die "apksigner not found (SDK build-tools, 'apt install apksigner', or Termux 'pkg install apksigner')"
[ -n "$D8$DX" ]    || die "need d8 (SDK build-tools) or dx ('apt install dalvik-exchange' / Termux 'pkg install dx')"
command -v zip >/dev/null     || die "zip not found (install the 'zip' package)"
command -v keytool >/dev/null || die "keytool not found (install a JDK)"

# android.jar must be API 30+ (the delete prompt uses MediaStore.createTrashRequest)
has_new() { [ "$(unzip -p "$1" android/provider/MediaStore.class 2>/dev/null | grep -c createTrashRequest || true)" -gt 0 ]; }
JAR=${ANDROID_JAR:-}
if [ -z "$JAR" ]; then
  for c in $(ls -d "$SDK"/platforms/android-*/ 2>/dev/null | sort -V -r) "${PREFIX:-/nonexistent}/share/aapt/" .cache/; do
    [ -f "$c/android.jar" ] && has_new "$c/android.jar" && { JAR="$c/android.jar"; break; }
  done
fi
if [ -z "$JAR" ]; then
  say "No suitable android.jar found - downloading one (26 MB, one time)"
  mkdir -p .cache
  curl -fL --retry 3 -o .cache/android.jar https://raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar || die "couldn't download android.jar (check internet)"
  JAR=.cache/android.jar
fi
say "aapt2: $AAPT2"; say "dex:   ${D8:-$DX}"; say "jar:   $JAR"

# ---------- compile ----------
rm -rf build && mkdir -p build/classes build/dex
say "Compiling Java"
find src -name '*.java' > build/sources.txt
if command -v javac >/dev/null; then
  javac -nowarn -encoding UTF-8 --release 8 -cp "$JAR" -d build/classes @build/sources.txt 2>/dev/null \
    || javac -nowarn -encoding UTF-8 -source 8 -target 8 -cp "$JAR" -d build/classes @build/sources.txt
elif command -v ecj >/dev/null; then
  ecj -nowarn -encoding UTF-8 -source 1.8 -target 1.8 -cp "$JAR" -d build/classes src
else die "need javac (a JDK) or ecj (Termux: pkg install ecj)"; fi

say "Converting to dex"
(cd build/classes && zip -qr ../classes.jar .)
if [ -n "$D8" ]; then "$D8" --release --min-api 21 --lib "$JAR" --output build/dex build/classes.jar
else "$DX" --dex --min-sdk-version=21 --output=build/dex/classes.dex build/classes.jar; fi
[ -f build/dex/classes.dex ] || die "dex step produced no classes.dex"

# ---------- package ----------
say "Packaging (manifest, icon, fonts)"
LINK=(link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml --min-sdk-version 21 --target-sdk-version 34)
if [ -d res ]; then "$AAPT2" compile --dir res -o build/res.zip; LINK+=(build/res.zip); fi
[ -d assets ] && LINK+=(-A assets)
"$AAPT2" "${LINK[@]}"
(cd build/dex && zip -q ../base.apk classes.dex)

say "Aligning + signing"
# Signing key:
#  * default: debug.keystore (ships with the project, so every build - PC, phone, CI, GitHub releases - installs over the last one)
#  * your own private key: set TAPEDECK_KEYSTORE (+ TAPEDECK_KEYSTORE_PASS, TAPEDECK_KEY_ALIAS, TAPEDECK_KEY_PASS)
KS=${TAPEDECK_KEYSTORE:-debug.keystore}; KSP=${TAPEDECK_KEYSTORE_PASS:-android}; KA=${TAPEDECK_KEY_ALIAS:-}; KP=${TAPEDECK_KEY_PASS:-$KSP}
if [ "$KS" = debug.keystore ] && [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
    -alias tapedeck -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Tape Deck" >/dev/null 2>&1
fi
[ -f "$KS" ] || die "keystore not found: $KS"
[ "$KS" = debug.keystore ] && say "Signing with the bundled debug.keystore" || say "Signing with $KS"
if [ -n "$ZIPALIGN" ]; then "$ZIPALIGN" -f -p 4 build/base.apk build/aligned.apk; else cp build/base.apk build/aligned.apk; fi
rm -f TapeDeck.apk
SIGN=(--ks "$KS" --ks-pass "pass:$KSP" --key-pass "pass:$KP"); [ -n "$KA" ] && SIGN+=(--ks-key-alias "$KA")
"$APKSIGNER" sign "${SIGN[@]}" --out TapeDeck.apk build/aligned.apk
"$APKSIGNER" verify TapeDeck.apk
say "Built $(pwd)/TapeDeck.apk ($(du -h TapeDeck.apk | cut -f1))"
