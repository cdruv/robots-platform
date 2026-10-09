#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
interpreter="$root/.test-runtime/micropython-1.28.0/ports/unix/build-walky/micropython"
if [ ! -x "$interpreter" ]; then
    "$root/tests/setup.sh"
fi
scratch=$(mktemp -d "${TMPDIR:-/tmp}/walky-tests.XXXXXX")
trap 'rm -rf "$scratch"' EXIT HUP INT TERM
cd "$scratch"
# Exclude user-installed modules, especially CPython compatibility packages.
MICROPYPATH="$root/tests/fakes:$root/src:$root/tests" "$interpreter" "$root/tests/run.py"
