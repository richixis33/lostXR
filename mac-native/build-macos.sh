#!/bin/zsh
set -euo pipefail

SCRIPT_DIR="${0:A:h}"
OUTPUT_DIR="$SCRIPT_DIR/build"
export DEVELOPER_DIR="/Applications/Xcode.app/Contents/Developer"
APP_DIR="$OUTPUT_DIR/XR Bridge.app"
STAGE_DIR="$OUTPUT_DIR/dmg-stage"
DMG_PATH="$OUTPUT_DIR/XR Bridge-5.1.dmg"
RUNTIME_APK="$SCRIPT_DIR/../openxr-runtime/monado/src/xrt/targets/openxr_android/build/outputs/apk/outOfProcess/debug/openxr_android-outOfProcess-debug.apk"

if [[ ! -f "$RUNTIME_APK" ]]; then
  echo "OpenXR Runtime APK не найден: $RUNTIME_APK" >&2
  exit 1
fi

/bin/rm -rf "$APP_DIR"
/bin/mkdir -p "$APP_DIR/Contents/MacOS" "$APP_DIR/Contents/Resources"

/usr/bin/xcrun swiftc \
  -parse-as-library \
  -O \
  -module-cache-path "$OUTPUT_DIR/module-cache" \
  -target arm64-apple-macos13.0 \
  -framework SwiftUI \
  -framework AppKit \
  "$SCRIPT_DIR/XRBridgeApp.swift" \
  "$SCRIPT_DIR/AndroidBridge.swift" \
  "$SCRIPT_DIR/IOSBridge.swift" \
  "$SCRIPT_DIR/PXRBridge.swift" \
  "$SCRIPT_DIR/UnityBridge.swift" \
  -o "$APP_DIR/Contents/MacOS/XRBridge"

/bin/cp "$SCRIPT_DIR/Info.plist" "$APP_DIR/Contents/Info.plist"
/bin/cp "$SCRIPT_DIR/../app/build/outputs/apk/debug/app-debug.apk" "$APP_DIR/Contents/Resources/cardboard-hands.apk"
/bin/cp "$RUNTIME_APK" "$APP_DIR/Contents/Resources/monado-openxr-runtime.apk"
/bin/cp "$SCRIPT_DIR/../PhoneXR-iOS/PhoneXR-iOS-LiveContainer.ipa" "$APP_DIR/Contents/Resources/PhoneXR-iOS.ipa"
/usr/bin/codesign --force --deep --sign - "$APP_DIR"
/bin/rm -rf "$STAGE_DIR"
/bin/mkdir -p "$STAGE_DIR"
/usr/bin/ditto "$APP_DIR" "$STAGE_DIR/XR Bridge.app"
/bin/ln -s /Applications "$STAGE_DIR/Applications"
/bin/rm -f "$DMG_PATH"
/usr/bin/hdiutil create -volname "XR Bridge" -srcfolder "$STAGE_DIR" -ov -format UDZO "$DMG_PATH"

echo "$DMG_PATH"
