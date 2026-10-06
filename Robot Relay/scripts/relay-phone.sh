#!/usr/bin/env bash
# Terminal equivalent of the Phone row in Robot Relay's connection popover.
# Each subcommand runs the same commands the app runs (the app shows them in the popover).
#
#   relay-phone.sh devices                         # online adb serials, one per line
#   relay-phone.sh forward [port]                  # "adb forward": phone port -> localhost
#   relay-phone.sh tail [host[:port]] [--pretty]   # "Connect": stream telemetry, reconnecting every 1 s
#   relay-phone.sh ping [host[:port]]              # round trip through the telemetry queue, in ms
#   relay-phone.sh unforward [port]                # "Disconnect" for a forward (or ctrl-c a tail)
#
#   relay-phone.sh forward && relay-phone.sh tail --pretty   # over USB
#   relay-phone.sh tail 192.168.1.42 | tee run.jsonl         # over Wi-Fi, keeping a recording
#
# Defaults: host localhost, port ${YOBOT_TELEMETRY_PORT:-7777}. With several devices attached,
# set ANDROID_SERIAL to choose one; otherwise the single online device is used.
#
# The phone sends {"link":"hello",...} first, then the last ~200 events, then live events.
# A {"ping":n} line sent to it is answered with {"link":"pong","ping":n} behind pending events.
set -euo pipefail

PORT="${YOBOT_TELEMETRY_PORT:-7777}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
  sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

# Prints a command the way the app's activity footer does, then runs it.
run() {
  echo "\$ $*" >&2
  "$@"
}

online_serials() {
  adb devices | awk 'NR > 1 && $2 == "device" { print $1 }'
}

# The device to use: $ANDROID_SERIAL, or the only online one.
pick_serial() {
  if [ -n "${ANDROID_SERIAL:-}" ]; then
    echo "$ANDROID_SERIAL"
    return
  fi
  echo "\$ adb devices" >&2
  local serials count
  serials="$(online_serials)"
  count="$(printf '%s\n' "$serials" | grep -c . || true)"
  if [ "$count" = 1 ]; then
    echo "$serials"
  elif [ "$count" = 0 ]; then
    echo "no online device (is USB debugging authorized?)" >&2
    exit 1
  else
    echo "several devices: $(echo $serials) (set ANDROID_SERIAL)" >&2
    exit 1
  fi
}

# Splits host[:port] into HOST and PORT.
parse_address() {
  local address="${1:-localhost}"
  HOST="${address%%:*}"
  if [ "$address" != "$HOST" ]; then PORT="${address#*:}"; fi
  HOST="${HOST:-localhost}"
}

cmd_tail() {
  local address="" pretty=0
  for arg in "$@"; do
    case "$arg" in
      --pretty|-p) pretty=1 ;;
      *) address="$arg" ;;
    esac
  done
  parse_address "$address"

  if [ "$pretty" = 1 ]; then
    command -v jq >/dev/null || { echo "jq is required for --pretty (brew install jq)" >&2; exit 1; }
    filter() { jq --unbuffered -r -f "$HERE/telemetry-pretty.jq"; }
  else
    filter() { cat; }
  fi

  echo "\$ nc $HOST $PORT   (reconnects every 1 s, ctrl-c to stop)" >&2
  while true; do
    nc "$HOST" "$PORT" | filter || true
    sleep 1
  done
}

# Perl for millisecond timing; the pong is matched by its ping number.
cmd_ping() {
  parse_address "${1:-}"
  echo "\$ echo '{\"ping\":1}' | nc $HOST $PORT   (waits for the pong)" >&2
  perl -MIO::Socket::INET -MTime::HiRes=time -e '
    my ($host, $port) = @ARGV;
    my $s = IO::Socket::INET->new(PeerAddr => $host, PeerPort => $port, Timeout => 3)
      or die "connect $host:$port: $!\n";
    $s->autoflush(1);
    local $SIG{ALRM} = sub { die "no pong within 5 s\n" };
    alarm 5;
    my $sent = time;
    print $s "{\"ping\":1}\n";
    while (my $line = <$s>) {
      if ($line =~ /"link"\s*:\s*"pong"/ && $line =~ /"ping"\s*:\s*1\b/) {
        printf "%.1f ms\n", (time - $sent) * 1000;
        exit 0;
      }
    }
    die "connection closed before the pong\n";
  ' "$HOST" "$PORT"
}

command="${1:-}"
[ $# -gt 0 ] && shift
case "$command" in
  devices) run adb devices >/dev/null; online_serials ;;
  forward)
    PORT="${1:-$PORT}"
    serial="$(pick_serial)"
    run adb -s "$serial" forward "tcp:$PORT" "tcp:$PORT" >/dev/null
    echo "localhost:$PORT" ;;
  unforward)
    PORT="${1:-$PORT}"
    serial="$(pick_serial)"
    run adb -s "$serial" forward --remove "tcp:$PORT" ;;
  tail) cmd_tail "$@" ;;
  ping) cmd_ping "$@" ;;
  *) usage ;;
esac
