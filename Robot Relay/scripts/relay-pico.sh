#!/usr/bin/env bash
# Terminal equivalent of the Pico row in Robot Relay's connection popover.
# Each subcommand runs the same commands the app runs (the app shows them in the popover).
#
#   relay-pico.sh find                  # Pico serial ports (USB vendor 2e8a), one per line
#   relay-pico.sh mode                  # what bringup_mode.txt arms: idle, test, center, repeat:...
#   relay-pico.sh disarm                # "Disarm": delete bringup_mode.txt
#   relay-pico.sh arm <test|center|repeat:test|repeat:center>   # the firmware README's arm command
#
# Set PICO_PORT to choose a port; otherwise the first Pico found is used. Uses mpremote from
# PATH, else ~/.venvs/pico/bin/mpremote, so the venv needn't be activated.
#
# Exit any `mpremote repl` first: the port takes one client at a time. Every command
# interrupts the program on the board. This script never runs `mpremote reset`: with the
# carrier off, a reset would consume a one-shot test.
set -euo pipefail

MODE_FILE="bringup_mode.txt"
# Keep these in sync with PicoMode in Services/Pico/PicoUSBLink.swift.
READ_SCRIPT="import os; print('mode=' + (open('$MODE_FILE').read().strip() if '$MODE_FILE' in os.listdir() else ''))"
DISARM_SCRIPT="import os; '$MODE_FILE' in os.listdir() and os.remove('$MODE_FILE')"

usage() {
  sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

# Prints a command the way the app's activity footer does, then runs it.
run() {
  echo "\$ $*" >&2
  "$@"
}

if command -v mpremote >/dev/null; then
  MPREMOTE=mpremote
elif [ -x "$HOME/.venvs/pico/bin/mpremote" ]; then
  MPREMOTE="$HOME/.venvs/pico/bin/mpremote"
else
  echo "mpremote not found (pip install mpremote, or create ~/.venvs/pico)" >&2
  exit 1
fi

# `mpremote connect list` lines look like: /dev/cu.usbmodem1101 <serial> 2e8a:0005 MicroPython Board in FS mode
pico_ports() {
  run "$MPREMOTE" connect list | awk '$3 ~ /^2e8a:/ { print $1 }' | sort
}

# $PICO_PORT, or the first Pico, preferring cu.usbmodem* as the app does.
pick_port() {
  if [ -n "${PICO_PORT:-}" ]; then
    echo "$PICO_PORT"
    return
  fi
  local ports
  ports="$(pico_ports)"
  if [ -z "$ports" ]; then
    echo "no Pico on USB (in BOOTSEL mode it has no serial port)" >&2
    exit 1
  fi
  printf '%s\n' "$ports" | grep -m1 '/cu\.usbmodem' || printf '%s\n' "$ports" | head -n1
}

cmd_mode() {
  local port output mode
  port="$(pick_port)"
  output="$(run "$MPREMOTE" connect "$port" exec "$READ_SCRIPT")"
  if ! mode="$(printf '%s\n' "$output" | tr -d '\r' | grep -m1 '^mode=')"; then
    echo "no mode= line in the output: $output" >&2
    exit 1
  fi
  mode="${mode#mode=}"
  echo "${mode:-idle}"
}

command="${1:-}"
[ $# -gt 0 ] && shift
case "$command" in
  find) pico_ports ;;
  mode) cmd_mode ;;
  disarm) run "$MPREMOTE" connect "$(pick_port)" exec "$DISARM_SCRIPT" ;;
  arm)
    case "${1:-}" in
      test|center|repeat:test|repeat:center) ;;
      *) echo "arm takes test, center, repeat:test or repeat:center" >&2; exit 2 ;;
    esac
    run "$MPREMOTE" connect "$(pick_port)" exec "with open('$MODE_FILE', 'w') as f: f.write('$1')" ;;
  *) usage ;;
esac
