#!/bin/sh
# Build a private, pinned Unix interpreter. Requires curl, shasum, make, C tools and python3.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
version=1.28.0
sha=1e77eb4450ce06098e6c8dba2dce1b2a4d885c24b74a2ba950dde3a1394d4ff6
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
