#!/bin/bash
# Build Robot Relay in Release configuration and install it to /Applications,
# replacing any previous version. Usage: scripts/install_local.sh [--no-launch]
set -euo pipefail

APP_NAME="Robot Relay"
DEST="/Applications/$APP_NAME.app"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/robot-relay-build.XXXXXX")"
trap 'rm -rf "$BUILD_DIR"' EXIT
# Major.minor is MARKETING_VERSION in the Xcode project (bumped by hand);
# the build number is the local build time, shown in the sidebar footer.
BUILD_NUMBER="$(date +%Y%m%d.%H%M)"

echo "==> Building $APP_NAME (Release, build $BUILD_NUMBER)"
xcodebuild \
  -project "$ROOT/$APP_NAME.xcodeproj" \
  -scheme "$APP_NAME" \
  -configuration Release \
  -destination 'generic/platform=macOS' \
  -derivedDataPath "$BUILD_DIR" \
  -quiet \
  build \
  CURRENT_PROJECT_VERSION="$BUILD_NUMBER"

BUILT="$BUILD_DIR/Build/Products/Release/$APP_NAME.app"
[ -d "$BUILT" ] || { echo "error: build product not found at $BUILT" >&2; exit 1; }

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
