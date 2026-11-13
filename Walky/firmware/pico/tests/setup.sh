#!/bin/sh
# Build a private, pinned Unix interpreter. Requires curl, shasum, make, C tools and python3.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
version=1.29.0
sha=8072f6ad42c0e3bbfca88ed09bf8f1106ce207a39129af920f70081b8f2fe286
cache="$root/.test-runtime"
mkdir -p "$cache"
archive="$cache/micropython-$version.tar.gz"
if [ ! -f "$archive" ]; then
    curl -fL "https://codeload.github.com/micropython/micropython/tar.gz/refs/tags/v$version" -o "$archive.part"
    mv "$archive.part" "$archive"
fi
printf '%s  %s\n' "$sha" "$archive" | shasum -a 256 -c -
if [ ! -d "$cache/micropython-$version" ]; then
    tar -xzf "$archive" -C "$cache"
fi
# These optional native libraries are unused by firmware tests; no submodules needed.
make -C "$cache/micropython-$version/ports/unix" -j4 \
    BUILD=build-walky MICROPY_PY_SSL=0 MICROPY_PY_BTREE=0 MICROPY_PY_FFI=0 \
    FROZEN_MANIFEST= CFLAGS_EXTRA=-DMICROPY_PY_OS_SYNC=1
