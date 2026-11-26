#!/usr/bin/env bash
# Tape Deck installer for Auxo Linux / Void Linux.
# Installs build deps, sets up the official Android SDK, builds the APK,
# and installs + launches it on a phone connected over USB.
set -euo pipefail
cd "$(dirname "$0")"

say()  { printf '\033[1;31m>>\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
export ANDROID_HOME=$SDK
SUDO=""; [ "$(id -u)" -ne 0 ] && SUDO="sudo"

# ---------- 1. system packages (xbps) ----------
if command -v xbps-install >/dev/null 2>&1; then
    say "Installing packages with xbps (asks for your sudo password)"
    $SUDO xbps-install -Sy xbps >/dev/null 2>&1 || true          # xbps must be current first
    $SUDO xbps-install -Sy curl unzip zip || die "xbps couldn't install curl/unzip/zip"
    if ! ls /usr/lib/jvm/*/bin/javac >/dev/null 2>&1 && ! command -v javac >/dev/null 2>&1; then
        $SUDO xbps-install -Sy openjdk21 || $SUDO xbps-install -Sy openjdk17 || die "couldn't install a JDK (openjdk21/openjdk17)"
    fi
    $SUDO xbps-install -Sy android-udev-rules >/dev/null 2>&1 || true   # optional: USB permissions
else
    say "xbps not found - assuming curl, unzip, zip and a JDK 17+ are already installed"
fi

# ---------- 2. Java ----------
if ! command -v javac >/dev/null 2>&1; then
    JDK=$(ls -d /usr/lib/jvm/openjdk21 /usr/lib/jvm/openjdk17 /usr/lib/jvm/*/ 2>/dev/null | while read -r d; do [ -x "${d%/}/bin/javac" ] && echo "${d%/}" && break; done || true)
    [ -n "$JDK" ] || die "no JDK found. Install one: sudo xbps-install -S openjdk21"
    export JAVA_HOME=$JDK PATH="$JDK/bin:$PATH"
fi
JV=$(javac -version 2>&1 | grep -oE '[0-9]+' | head -n1)
[ "${JV:-0}" -ge 17 ] || die "JDK 17 or newer needed (found javac $JV)"
say "Java $JV OK"

# ---------- 3. Android SDK (official, from Google) ----------
SM="$SDK/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SM" ]; then
    say "Downloading Android command-line tools"
    mkdir -p "$SDK/cmdline-tools"; TMP=$(mktemp -d)
    ok=""
    for v in 11076708 10406996 9477386; do
        if curl -fL --retry 3 -o "$TMP/clt.zip" "https://dl.google.com/android/repository/commandlinetools-linux-${v}_latest.zip"; then ok=1; break; fi
    done
    [ -n "$ok" ] || die "couldn't download Android command-line tools (check internet)"
    unzip -q "$TMP/clt.zip" -d "$TMP"
    rm -rf "$SDK/cmdline-tools/latest"; mv "$TMP/cmdline-tools" "$SDK/cmdline-tools/latest"; rm -rf "$TMP"
fi
if ! ls -d "$SDK"/build-tools/*/ >/dev/null 2>&1 || ! ls -d "$SDK"/platforms/android-*/ >/dev/null 2>&1 || [ ! -x "$SDK/platform-tools/adb" ]; then
    say "Installing SDK build-tools, platform and adb (accepting licenses)"
    (yes 2>/dev/null || true) | "$SM" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
    "$SM" --sdk_root="$SDK" "platform-tools" "build-tools;34.0.0" "platforms;android-34" \
        || die "sdkmanager failed to install SDK packages"
fi
say "Android SDK OK ($SDK)"

# ---------- 4. build ----------
./build.sh

# ---------- 5. install on phone ----------
ADB="$SDK/platform-tools/adb"; [ -x "$ADB" ] || ADB=$(command -v adb) || die "adb not found"
say "Plug in your phone with USB debugging ON (Settings > About > tap Build number 7x, then Developer options > USB debugging)"
"$ADB" start-server >/dev/null 2>&1 || true
state=""
for i in $(seq 1 60); do
    line=$("$ADB" devices | sed -n '2,$p' | grep -v '^$' | head -n1 || true)
    state=$(echo "$line" | awk '{print $2}')
    case "$state" in
        device) break ;;
        unauthorized) [ $((i % 5)) -eq 1 ] && say "Tap 'Allow' on the USB debugging prompt on your phone" ;;
        no) # "no permissions" -> restart adb server as root once
            if [ -z "${ROOTADB:-}" ]; then say "USB permission issue - restarting adb as root"; "$ADB" kill-server; $SUDO "$ADB" start-server >/dev/null; ROOTADB=1; fi ;;
        *) [ $((i % 5)) -eq 1 ] && say "Waiting for phone..." ;;
    esac
    sleep 2
done
[ "$state" = "device" ] || die "phone not detected. Check the cable (data, not charge-only), USB debugging, and run ./install.sh again"

say "Installing Tape Deck on $("$ADB" shell getprop ro.product.model | tr -d '\r')"
"$ADB" install -r TapeDeck.apk || { say "Retrying after removing old copy"; "$ADB" uninstall com.rxvy.tapedeck >/dev/null 2>&1 || true; "$ADB" install TapeDeck.apk; }
"$ADB" shell am start -n com.rxvy.tapedeck/.MainActivity >/dev/null
say "Done! Tape Deck is open on your phone - tap Allow so it can read your music."
say "APK also saved at: $(pwd)/TapeDeck.apk"
