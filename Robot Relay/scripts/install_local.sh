#!/bin/bash
# Build Robot Relay in Release configuration and install it to /Applications,
# replacing any previous version. Usage: scripts/install_local.sh [--no-launch]
set -euo pipefail

APP_NAME="Robot Relay"
DEST="/Applications/$APP_NAME.app"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/robot-relay-build.XXXXXX")"
trap 'rm -rf "$BUILD_DIR"' EXIT

echo "==> Building $APP_NAME (Release)"
xcodebuild \
  -project "$ROOT/$APP_NAME.xcodeproj" \
  -scheme "$APP_NAME" \
  -configuration Release \
  -destination 'generic/platform=macOS' \
  -derivedDataPath "$BUILD_DIR" \
  -quiet \
  build

BUILT="$BUILD_DIR/Build/Products/Release/$APP_NAME.app"
[ -d "$BUILT" ] || { echo "error: build product not found at $BUILT" >&2; exit 1; }
VERSION="$(/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" "$BUILT/Contents/Info.plist")"
BUILD_NUMBER="$(/usr/libexec/PlistBuddy -c "Print :CFBundleVersion" "$BUILT/Contents/Info.plist")"
echo "==> Built v$VERSION build $BUILD_NUMBER"

if pgrep -x "$APP_NAME" >/dev/null; then
  echo "==> Quitting running $APP_NAME"
  osascript -e "tell application \"$APP_NAME\" to quit" >/dev/null 2>&1 || true
  for _ in $(seq 1 20); do pgrep -x "$APP_NAME" >/dev/null || break; sleep 0.5; done
  pkill -x "$APP_NAME" 2>/dev/null || true
fi

echo "==> Installing to $DEST"
if [ -w /Applications ]; then
  rm -rf "$DEST"
  ditto "$BUILT" "$DEST"
else
  sudo rm -rf "$DEST"
  sudo ditto "$BUILT" "$DEST"
fi

echo "==> Installed $DEST"
[ "${1:-}" = "--no-launch" ] || open "$DEST"
