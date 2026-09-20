#!/bin/zsh
set -euo pipefail

SCRIPT_DIR="${0:A:h}"
APP_SOURCE="$SCRIPT_DIR/xcode-device/Debug-iphoneos/LostXR.app"
STAGE_DIR="$SCRIPT_DIR/livecontainer-stage"
OUTPUT_IPA="$SCRIPT_DIR/LostXR-iOS-LiveContainer.ipa"

if [[ ! -x "$APP_SOURCE/LostXR" ]]; then
  echo "LostXR.app не найден. Сначала соберите device-версию." >&2
  exit 1
fi

/bin/rm -rf "$STAGE_DIR"
/bin/mkdir -p "$STAGE_DIR/Payload"
/usr/bin/ditto "$APP_SOURCE" "$STAGE_DIR/Payload/LostXR.app"
/usr/bin/xattr -cr "$STAGE_DIR/Payload/LostXR.app"
/usr/bin/codesign --force --sign - --generate-entitlement-der "$STAGE_DIR/Payload/LostXR.app"
/bin/rm -f "$OUTPUT_IPA.tmp"
(
  cd "$STAGE_DIR"
  /usr/bin/zip -qry -X "$OUTPUT_IPA.tmp" Payload
)
/bin/mv "$OUTPUT_IPA.tmp" "$OUTPUT_IPA"
/usr/bin/codesign --verify --deep --strict "$STAGE_DIR/Payload/LostXR.app"

echo "$OUTPUT_IPA"
