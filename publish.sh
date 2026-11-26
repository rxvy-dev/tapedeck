#!/usr/bin/env bash
# Publish Tape Deck to GitHub, fully open source: pushes the code and makes a Release with the APK attached.
#
#   ./publish.sh           release the version that's in AndroidManifest.xml
#   ./publish.sh 1.3.0     bump to 1.3.0 first (versionCode +1), then release
#
# Needs: git, the GitHub CLI logged in (gh auth login), and the Android build tools (./install.sh sets them up).
# Repo defaults to <your GitHub user>/tapedeck - override with TAPEDECK_REPO=owner/name ./publish.sh
# Signing: uses the bundled debug.keystore unless TAPEDECK_KEYSTORE (+ _KEYSTORE_PASS, _KEY_ALIAS) is set.
set -euo pipefail
cd "$(dirname "$0")"
say() { printf '\033[1;31m>>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

command -v git >/dev/null || die "git is not installed"
command -v gh >/dev/null  || die "GitHub CLI (gh) is not installed - Void/Auxo: sudo xbps-install -S github-cli"
gh auth status >/dev/null 2>&1 || die "log in to GitHub first: gh auth login"

USER_NAME=$(gh api user --jq .login)
REPO=${TAPEDECK_REPO:-$USER_NAME/tapedeck}

# ---------- version ----------
if [ $# -ge 1 ]; then
  NEW=${1#v}
  [[ $NEW =~ ^[0-9]+\.[0-9]+(\.[0-9]+)?$ ]] || die "version must look like 1.3.0"
  CODE=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' AndroidManifest.xml)
  sed -i "s/android:versionCode=\"$CODE\"/android:versionCode=\"$((CODE + 1))\"/; s/android:versionName=\"[^\"]*\"/android:versionName=\"$NEW\"/" AndroidManifest.xml
  say "Version bumped to $NEW (versionCode $((CODE + 1)))"
fi
VER=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)
TAG="v$VER"
[ -n "$VER" ] || die "couldn't read versionName from AndroidManifest.xml"

if ! grep -q "^## $VER" CHANGELOG.md 2>/dev/null; then
  say "No CHANGELOG entry for $VER - adding a short one (edit CHANGELOG.md to say more)"
  { echo "# Changelog"; echo; echo "## $VER - $(date +%Y-%m-%d)"; echo "- Bug fixes and improvements"; echo; sed '1{/^# Changelog/d}' CHANGELOG.md 2>/dev/null | sed '1{/^$/d}'; } > CHANGELOG.tmp
  mv CHANGELOG.tmp CHANGELOG.md
fi

# ---------- build ----------
say "Building TapeDeck.apk ($TAG)"
./build.sh

# ---------- git: commit + push ----------
[ -d .git ] || { git init -q; git checkout -q -b main; }
if ! git remote get-url origin >/dev/null 2>&1; then
  if gh repo view "$REPO" >/dev/null 2>&1; then git remote add origin "https://github.com/$REPO.git"
  else say "Creating public repo $REPO"; gh repo create "$REPO" --public --description "Retro music player for Android - iPod, XP, Winamp, Game Boy, iTunes 2011 and a secret Undertale theme with a beat-synced fight" >/dev/null; git remote add origin "https://github.com/$REPO.git"; fi
fi
git add -A
git diff --cached --quiet || git commit -q -m "Tape Deck $VER"
BR=$(git rev-parse --abbrev-ref HEAD)
say "Pushing $BR to $REPO"
if ! git push -u origin "$BR:main"; then
  say "Remote has new commits (screenshots bot?) - rebasing and retrying"
  git pull --rebase origin main && git push -u origin "$BR:main"
fi

# ---------- tag ----------
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null || git tag -a "$TAG" -m "Tape Deck $VER"
git push origin "$TAG" 2>/dev/null || say "tag $TAG already on GitHub"

# ---------- release with the APK ----------
NOTES=$(mktemp)
awk -v v="$VER" '$0 ~ "^## "v {f=1; next} /^## / && f {exit} f' CHANGELOG.md > "$NOTES"
[ -s "$NOTES" ] || echo "Tape Deck $VER" > "$NOTES"
printf '\n---\n**Install:** download `TapeDeck.apk` below and open it on your phone (allow "install unknown apps").\n' >> "$NOTES"
if gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then
  say "Release $TAG exists - replacing the APK"
  gh release upload "$TAG" TapeDeck.apk --clobber -R "$REPO"
else
  gh release create "$TAG" TapeDeck.apk -R "$REPO" --title "Tape Deck $VER" --notes-file "$NOTES"
fi
rm -f "$NOTES"

# make the repo look nice (harmless if it fails)
gh repo edit "$REPO" --description "Retro music player for Android - iPod, XP, Winamp, Game Boy, iTunes 2011 and a secret Undertale theme with a beat-synced fight" \
  --add-topic android --add-topic music-player --add-topic retro --add-topic undertale --add-topic open-source >/dev/null 2>&1 || true

say "Done! https://github.com/$REPO/releases/tag/$TAG"
say "Direct APK link: https://github.com/$REPO/releases/latest/download/TapeDeck.apk"
