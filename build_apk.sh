#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "==> Building Andclaw debug APK..."
./gradlew :app:assembleDebug

APK="$SCRIPT_DIR/app/build/outputs/apk/debug/Andclaw.apk"
echo ""
echo "==> Done: $APK"
echo "    Size: $(du -sh "$APK" | cut -f1)"

echo ""
echo "==> Installing to device..."
adb install -r "$APK"
echo "==> Install complete."
