#!/usr/bin/env bash
# Packages the Home Assistant integration for HACS and puts it on a GitHub release.
#
#   tools/hacs-release.sh 2.7.0        # tag v2.7.0, attach halo_tv.zip
#   tools/hacs-release.sh 2.7.0 --dry  # only build dist/halo_tv.zip
#
# HACS installs from the zip named in hacs.json ("halo_tv.zip"), which must hold
# custom_components/halo_tv/... at its root - that is why the launcher's own repo can be the HACS
# repository without moving anything around.
set -euo pipefail

VERSION="${1:-}"
DRY="${2:-}"
if [[ -z "$VERSION" ]]; then
  echo "usage: $0 <version, e.g. 2.7.0> [--dry]" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/homeassistant"
ZIP="$ROOT/dist/halo_tv.zip"

MANIFEST_VERSION="$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['version'])" \
  "$SRC/custom_components/halo_tv/manifest.json")"
if [[ "$MANIFEST_VERSION" != "$VERSION" ]]; then
  echo "manifest.json says $MANIFEST_VERSION, asked for $VERSION" >&2
  exit 1
fi

mkdir -p "$ROOT/dist"
rm -f "$ZIP"
( cd "$SRC" && zip -q -r "$ZIP" custom_components/halo_tv \
    -x '*/__pycache__/*' -x '*.pyc' -x '*/.DS_Store' )

echo "built dist/halo_tv.zip"
unzip -l "$ZIP" | tail -3

if [[ "$DRY" == "--dry" ]]; then
  exit 0
fi

TAG="v$VERSION"
cd "$ROOT"
git tag -f "$TAG" >/dev/null
git push -f origin "$TAG"
if gh release view "$TAG" >/dev/null 2>&1; then
  gh release upload "$TAG" "$ZIP" --clobber
else
  gh release create "$TAG" "$ZIP" --title "Halo TV $VERSION" --generate-notes
fi
echo "released $TAG"
