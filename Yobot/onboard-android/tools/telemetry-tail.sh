#!/usr/bin/env bash
# Streams Yobot telemetry (one JSON object per line) from the phone to this terminal.
#
#   ./tools/telemetry-tail.sh                 # over USB/adb: forwards the port, connects to localhost
#   ./tools/telemetry-tail.sh 192.168.1.42    # over Wi-Fi: the phone's address (shown in the debug overlay)
#   ./tools/telemetry-tail.sh | tee run.jsonl # keep a recording
#   ./tools/telemetry-tail.sh --pretty        # one human-readable line per event (needs jq)
#   ANDROID_SERIAL=XYZ ./tools/telemetry-tail.sh   # choose the phone when several devices are attached
#
# The app serves the last ~200 events to each new client, then streams live. The script
# reconnects every second, so it can be left running across app restarts and reinstalls.
set -euo pipefail

PORT="${YOBOT_TELEMETRY_PORT:-7777}"
HOST=""
PRETTY=0
for arg in "$@"; do
  case "$arg" in
    --pretty|-p) PRETTY=1 ;;
    *) HOST="$arg" ;;
  esac
done

if [ -z "$HOST" ]; then
  # With several devices attached (e.g. a stale emulator), target the one that is online.
  if [ -z "${ANDROID_SERIAL:-}" ]; then
    online=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    if [ "$(printf '%s\n' "$online" | grep -c .)" = 1 ]; then
      export ANDROID_SERIAL="$online"
    fi
  fi
  adb forward "tcp:$PORT" "tcp:$PORT" >/dev/null
  HOST=localhost
fi

if [ "$PRETTY" = 1 ]; then
  command -v jq >/dev/null || { echo "jq is required for --pretty (brew install jq)" >&2; exit 1; }
  filter() {
    jq --unbuffered -r -f "$(dirname "${BASH_SOURCE[0]}")/telemetry-pretty.jq"
  }
else
  filter() { cat; }
fi

echo "yobot telemetry: $HOST:$PORT (ctrl-c to stop)" >&2
while true; do
  nc "$HOST" "$PORT" | filter || true
  sleep 1
done
