#!/bin/bash
# build-scanimage-arm64.sh — cross-build a statically linked aarch64 (glibc) `scanimage` from
# sane-backends 1.4.0 with ONLY the pixma backend preloaded (+ MF8000 patch), for the Android app:
# exec'd directly from nativeLibraryDir as libscanimage.so (no proot, no dynamic loader).
#
# Prereqs (WSL Ubuntu / Debian):
#   sudo apt-get install -y build-essential gcc-aarch64-linux-gnu curl python3
#   (gcc-aarch64-linux-gnu pulls libc6-dev-arm64-cross, which ships the static libc.a)
# Usage:
#   bash scripts/build-scanimage-arm64.sh
# Output:
#   app/src/main/jniLibs/arm64-v8a/libscanimage.so   (stripped, static aarch64 ELF)
# Runtime on the phone (no compiled-in paths needed):
#   SANE_CONFIG_DIR=<app dir>   containing  dll.conf (`pixma`)  and  pixma.conf
#       (`auto_detection=no` + `mfnp://<IP>` or `mfnp://[<IPv6>%<ifindex>]`); the device name
#       comes from `scanimage -L` (IPv4: pixma:<model>_<IP>, IPv6: pixma:<model>_<MAC>)
# pixma REQUIRES libjpeg at configure time (JPEG-source decoding) and no arm64 libjpeg-dev is
# installed, so IJG libjpeg 9f is cross-built statically first. No png/tiff/usb/avahi/snmp ->
# scanimage output format is pnm only (the app converts).
set -euo pipefail

HERE="$(dirname "$(readlink -f "$0")")"
PROJECT="$(dirname "$HERE")"
WORK="${WORK:-$HOME/sane-arm64}"
SRC="$WORK/src"
PREFIX="$WORK/inst"
DEPS="$WORK/deps"
HOST=aarch64-linux-gnu
TARBALL_URL="https://gitlab.com/-/project/429008/uploads/843c156420e211859e974f78f64c3ea3/sane-backends-1.4.0.tar.gz"
TARBALL_SHA256="f99205c903dfe2fb8990f0c531232c9a00ec9c2c66ac7cb0ce50b4af9f407a72"
JPEG_URL="https://www.ijg.org/files/jpegsrc.v9f.tar.gz"
JPEG_SHA256="04705c110cb2469caa79fb71fba3d7bf834914706e9641a4589485c1f832565b"
OUT="$PROJECT/app/src/main/jniLibs/arm64-v8a/libscanimage.so"

command -v ${HOST}-gcc >/dev/null || { echo "missing ${HOST}-gcc (apt install gcc-aarch64-linux-gnu)"; exit 1; }
mkdir -p "$WORK"
cd "$WORK"

# Tarball: reuse the x86_64 test-build cache if present
if [ ! -f sb.tar.gz ]; then
  if [ -f "$HOME/sane-test/sb.tar.gz" ]; then cp "$HOME/sane-test/sb.tar.gz" sb.tar.gz
  else curl -fsSL -o sb.tar.gz "$TARBALL_URL"; fi
fi
echo "$TARBALL_SHA256  sb.tar.gz" | sha256sum -c -

# --- static libjpeg (IJG 9f) for aarch64 ---
[ -f jpegsrc.v9f.tar.gz ] || curl -fsSL -o jpegsrc.v9f.tar.gz "$JPEG_URL"
echo "$JPEG_SHA256  jpegsrc.v9f.tar.gz" | sha256sum -c -
rm -rf "$WORK/jpeg-src" "$DEPS" && mkdir "$WORK/jpeg-src"
tar -xzf jpegsrc.v9f.tar.gz -C "$WORK/jpeg-src" --strip-components=1
( cd "$WORK/jpeg-src" \
  && ./configure --host=$HOST --prefix="$DEPS" --disable-shared --enable-static \
       CC=${HOST}-gcc CFLAGS="-O2" > "$WORK/jpeg-configure.log" 2>&1 \
  && make -j"$(nproc)" > "$WORK/jpeg-make.log" 2>&1 \
  && make install > "$WORK/jpeg-install.log" 2>&1 ) \
  || { echo "libjpeg build failed, see $WORK/jpeg-*.log"; exit 1; }

# --- sane-backends ---
rm -rf "$SRC" "$PREFIX" && mkdir "$SRC"
tar -xzf sb.tar.gz -C "$SRC" --strip-components=1
cd "$SRC"

# MF8000 patches (model alias + ADF, stale-session recovery, jam watchdog)
python3 -I "$HERE/sane-patch-mf8000.py" "$SRC"

# Static + preload: pixma is linked INTO libsane.a (no dlopen), scanimage linked fully static.
# --disable-locking: no /var/lock lockdir. All optional libs disabled except libjpeg (see above).
./configure --host=$HOST --prefix="$PREFIX" \
  --disable-shared --enable-static --enable-preload \
  BACKENDS=pixma --disable-locking \
  --without-usb --without-snmp --without-avahi --without-v4l --without-gphoto2 \
  --enable-ipv6 \
  CC=${HOST}-gcc \
  CFLAGS="-O2" CPPFLAGS="-I$DEPS/include" LDFLAGS="-static -L$DEPS/lib" \
  > "$WORK/configure.log" 2>&1 || { tail -30 "$WORK/configure.log"; exit 1; }

# Only the dirs we need (skips doc/ and tools/ which want host-side helpers)
for d in include lib sanei backend; do
  make -C "$d" -j"$(nproc)" > "$WORK/make-$d.log" 2>&1 || { tail -30 "$WORK/make-$d.log"; exit 1; }
done
# -all-static makes libtool pass -static to the link for the frontend
make -C frontend -j"$(nproc)" scanimage LDFLAGS="-all-static -L$DEPS/lib" > "$WORK/make-frontend.log" 2>&1 \
  || { tail -30 "$WORK/make-frontend.log"; exit 1; }

BIN="$SRC/frontend/scanimage"
[ -f "$BIN" ] || BIN="$SRC/frontend/.libs/scanimage"
mkdir -p "$(dirname "$OUT")"
${HOST}-strip -o "$OUT" "$BIN"
chmod 755 "$OUT"
file "$OUT"
ls -la "$OUT"
