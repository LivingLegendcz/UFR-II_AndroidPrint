#!/bin/bash
# sane-test-build.sh — x86_64 (WSL2) test build of sane-backends 1.4.0 with the MF8000 patch,
# plus a test network scan. Proof-of-concept for docs/20 (phase 0); NOT the arm64 rootfs build.
#
# Prereqs (WSL Ubuntu):
#   sudo apt-get install -y build-essential pkg-config libjpeg-dev curl
# Usage:
#   bash scripts/sane-test-build.sh                 # build only
#   bash scripts/sane-test-build.sh scan [IP] [DPI] # build (if needed) + scan to ~/sane-test/scan.jpg
# Before scanning: page on the glass + printer panel SCAN → Remote Scanner → OK (else EBUSY).
set -euo pipefail

WORK="$HOME/sane-test"
SRC="$WORK/sbt"
PREFIX="$WORK/inst"
CFG="$WORK/cfg"
TARBALL_URL="https://gitlab.com/-/project/429008/uploads/843c156420e211859e974f78f64c3ea3/sane-backends-1.4.0.tar.gz"
TARBALL_SHA256="f99205c903dfe2fb8990f0c531232c9a00ec9c2c66ac7cb0ce50b4af9f407a72"

mkdir -p "$WORK"
cd "$WORK"

if [ ! -x "$PREFIX/bin/scanimage" ]; then
  # Release tarball (has pre-generated configure → no autopoint/autogen needed)
  [ -f sb.tar.gz ] || curl -fsSL -o sb.tar.gz "$TARBALL_URL"
  echo "$TARBALL_SHA256  sb.tar.gz" | sha256sum -c -
  rm -rf "$SRC" && mkdir "$SRC" && tar -xzf sb.tar.gz -C "$SRC" --strip-components=1
  cd "$SRC"

  # The patch: device reports MDL "MF8000 Series" over the network, table only knows "MF8030".
  # Same PID → generation-2 imageCLASS code path. PIXMA_CAP_ADF: the unit has a document feeder.
  sed -i '/"MF8030", MF8030_PID/a\  DEV ("Canon i-SENSYS MF8000 Series", "MF8000", MF8030_PID, 600, 0, 640, 877, PIXMA_CAP_ADF),' \
    backend/pixma/pixma_imageclass.c
  grep -q '"MF8000"' backend/pixma/pixma_imageclass.c

  ./configure --prefix="$PREFIX" BACKENDS=pixma --disable-locking \
    --without-usb --without-snmp --without-avahi > "$WORK/configure.log" 2>&1
  make -j"$(nproc)" > "$WORK/make.log" 2>&1
  make install > "$WORK/install.log" 2>&1
  cd "$WORK"
fi

IP="${2:-192.168.0.62}"
DPI="${3:-150}"

# auto_detection=no is REQUIRED: broadcast discovery wedges the firmware's UDP 8610 until power-cycle.
mkdir -p "$CFG"
echo pixma > "$CFG/dll.conf"
printf 'auto_detection=no\nmfnp://%s\n' "$IP" > "$CFG/pixma.conf"

if [ "${1:-}" = "scan" ]; then
  export SANE_CONFIG_DIR="$CFG" LD_LIBRARY_PATH="$PREFIX/lib"
  "$PREFIX/bin/scanimage" -d "pixma:MF8000_$IP" --resolution "$DPI" --mode Color \
    --format=jpeg -o "$WORK/scan.jpg"
  ls -la "$WORK/scan.jpg"
fi
