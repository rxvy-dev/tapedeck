#!/data/data/com.termux/files/usr/bin/bash
# Build Tape Deck ON YOUR PHONE, inside Termux (get Termux from F-Droid, not the Play Store).
#   unzip TapeDeck.zip && cd tapedeck && bash termux-build.sh
set -e
cd "$(dirname "$0")"
echo ">> Installing build tools (a few hundred MB, one time)..."
pkg update -y >/dev/null 2>&1 || true
for p in openjdk-17 aapt aapt2 apksigner dx ecj zip unzip curl; do
  pkg install -y "$p" >/dev/null 2>&1 || echo "   (couldn't install $p - skipping)"
done
bash ./build.sh
mkdir -p "$HOME/storage" 2>/dev/null || true
[ -d "$HOME/storage/downloads" ] || termux-setup-storage || true
sleep 1
if [ -d "$HOME/storage/downloads" ]; then cp -f TapeDeck.apk "$HOME/storage/downloads/TapeDeck.apk" && echo ">> Copied to your Downloads folder"; fi
echo ">> Opening the installer - allow 'install unknown apps' for Termux if Android asks"
termux-open TapeDeck.apk 2>/dev/null || echo "Open Downloads/TapeDeck.apk to install it."
