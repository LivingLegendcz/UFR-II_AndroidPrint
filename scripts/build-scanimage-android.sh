#!/bin/bash
# build-scanimage-android.sh — build an Android-native (bionic, aarch64) `scanimage` from
# sane-backends 1.4.0 with ONLY the pixma backend preloaded (+ MF8000 patch), for the Android app:
# exec'd directly from nativeLibraryDir as libscanimage.so.
#
# Why this exists: the glibc-static build (build-scanimage-arm64.sh) is killed by Android's app
# seccomp filter at startup (exit 159 = SIGSYS on Android 17 / SDK 37; suspects: rseq registration
# at glibc 2.39 startup, clone3 in pthread_create). A binary linked against the system bionic
# uses only the syscalls the platform libc itself issues, so it passes the filter.
#
# Prereqs (WSL Ubuntu / Debian):
#   sudo apt-get install -y build-essential curl python3 file
#   Android NDK r27 (default: ~/android-sdk/ndk/27.2.12479018, override with NDK=...)
# Usage:
#   bash scripts/build-scanimage-android.sh
# Output:
#   app/src/main/jniLibs/arm64-v8a/libscanimage.so   (stripped aarch64 PIE ELF, dynamic against
#       the system bionic: NEEDED = libc.so libm.so libdl.so only, interpreter /system/bin/linker64;
#       sane + pixma + libjpeg are linked in statically)
# Runtime on the phone (no compiled-in paths needed):
#   SANE_CONFIG_DIR=<app dir>   containing  dll.conf (`pixma`)  and  pixma.conf
#       (`auto_detection=no` + `mfnp://<IP>` or `mfnp://[<IPv6>%<ifindex>]`); the device name
#       comes from `scanimage -L` (IPv4: pixma:<model>_<IP>, IPv6: pixma:<model>_<MAC>)
#   Also set HOME / TMPDIR to an app-writable dir (bionic has no /tmp, HOME is unset in app procs).
# pixma REQUIRES libjpeg at configure time (JPEG-source decoding), so IJG libjpeg 9f is built
# statically first. No png/tiff/usb/avahi/snmp -> scanimage output format is pnm only (the app converts).
set -euo pipefail

HERE="$(dirname "$(readlink -f "$0")")"
PROJECT="$(dirname "$HERE")"
# Separate work dir: the glibc build cache in ~/sane-arm64 stays untouched
WORK="${WORK:-$HOME/sane-android}"
SRC="$WORK/src"
# Compiled-in only (configure --prefix; nothing is installed): the app passes SANE_CONFIG_DIR.
# A neutral path keeps the build machine's home directory out of the binary.
PREFIX="/opt/sane"
DEPS="$WORK/deps"
NDK="${NDK:-$HOME/android-sdk/ndk/27.2.12479018}"
API=29                                   # = app minSdk
HOST=aarch64-linux-android
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
TARBALL_URL="https://gitlab.com/-/project/429008/uploads/843c156420e211859e974f78f64c3ea3/sane-backends-1.4.0.tar.gz"
TARBALL_SHA256="f99205c903dfe2fb8990f0c531232c9a00ec9c2c66ac7cb0ce50b4af9f407a72"
JPEG_URL="https://www.ijg.org/files/jpegsrc.v9f.tar.gz"
JPEG_SHA256="04705c110cb2469caa79fb71fba3d7bf834914706e9641a4589485c1f832565b"
OUT="$PROJECT/app/src/main/jniLibs/arm64-v8a/libscanimage.so"

[ -x "$TOOLCHAIN/bin/${HOST}${API}-clang" ] || { echo "missing NDK clang: $TOOLCHAIN/bin/${HOST}${API}-clang (set NDK=...)"; exit 1; }
export PATH="$TOOLCHAIN/bin:$PATH"
export CC="${HOST}${API}-clang"
export AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip NM=llvm-nm
# PIE is mandatory on Android; -z max-page-size=16384 = 16 KB page-size compatible segments
# (Android 15+ 16 KB devices). -fPIC for everything incl. the static libs that end up in the PIE.
# Bionic/clang workaround 2: dll.c's PRELOAD table stores every backend entry point in a
# `void *(*)(void)` slot; GCC accepts that, clang >= 16 makes -Wincompatible-function-pointer-types
# a hard error. The cast-through-slot is the intended (and ABI-safe) sane design -> demote it.
CFLAGS_ANDROID="-O2 -fPIE -fPIC -Wno-incompatible-function-pointer-types"
LDFLAGS_ANDROID="-pie -Wl,-z,max-page-size=16384 -Wl,-z,relro -Wl,-z,now"

mkdir -p "$WORK"
cd "$WORK"

# Tarball: reuse the glibc build cache / x86_64 test-build cache if present
if [ ! -f sb.tar.gz ]; then
  if   [ -f "$HOME/sane-arm64/sb.tar.gz" ]; then cp "$HOME/sane-arm64/sb.tar.gz" sb.tar.gz
  elif [ -f "$HOME/sane-test/sb.tar.gz" ];  then cp "$HOME/sane-test/sb.tar.gz" sb.tar.gz
  else curl -fsSL -o sb.tar.gz "$TARBALL_URL"; fi
fi
echo "$TARBALL_SHA256  sb.tar.gz" | sha256sum -c -

# --- static libjpeg (IJG 9f) for bionic aarch64 ---
if [ ! -f jpegsrc.v9f.tar.gz ]; then
  if [ -f "$HOME/sane-arm64/jpegsrc.v9f.tar.gz" ]; then cp "$HOME/sane-arm64/jpegsrc.v9f.tar.gz" .
  else curl -fsSL -o jpegsrc.v9f.tar.gz "$JPEG_URL"; fi
fi
echo "$JPEG_SHA256  jpegsrc.v9f.tar.gz" | sha256sum -c -
rm -rf "$WORK/jpeg-src" "$DEPS" && mkdir "$WORK/jpeg-src"
tar -xzf jpegsrc.v9f.tar.gz -C "$WORK/jpeg-src" --strip-components=1
( cd "$WORK/jpeg-src" \
  && ./configure --host=$HOST --prefix="$DEPS" --disable-shared --enable-static \
       CFLAGS="$CFLAGS_ANDROID" > "$WORK/jpeg-configure.log" 2>&1 \
  && make -j"$(nproc)" > "$WORK/jpeg-make.log" 2>&1 \
  && make install > "$WORK/jpeg-install.log" 2>&1 ) \
  || { echo "libjpeg build failed, see $WORK/jpeg-*.log"; exit 1; }

# --- sane-backends ---
rm -rf "$SRC" && mkdir "$SRC"
tar -xzf sb.tar.gz -C "$SRC" --strip-components=1
cd "$SRC"

# MF8000 patches (model alias + ADF, stale-session recovery, jam watchdog)
python3 -I "$HERE/sane-patch-mf8000.py" "$SRC"

# Bionic workaround 1: NO pthreads. bionic has no pthread_cancel, which sanei_thread.c uses when
# USE_PTHREAD is defined (configure would also fall back to fork by itself when pthread_cancel is
# missing; --disable-pthread makes that explicit and deterministic). With fork(), pixma's reader
# runs in a child process that streams the image through a pipe (sanei_thread_is_forked()).
# fork() is allowed by the app seccomp policy (it is the plain clone syscall).
#
# Static + preload: pixma is linked INTO libsane.a (no dlopen), sane/jpeg static inside the
# dynamically-linked-to-bionic PIE. --disable-locking: no /var/lock lockdir. All optional libs
# disabled except libjpeg (see above).
./configure --host=$HOST --prefix="$PREFIX" \
  --disable-shared --enable-static --enable-preload \
  BACKENDS=pixma --disable-locking --disable-pthread \
  --without-usb --without-snmp --without-avahi --without-v4l --without-gphoto2 \
  --enable-ipv6 \
  CC="$CC" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" \
  CFLAGS="$CFLAGS_ANDROID" CPPFLAGS="-I$DEPS/include" LDFLAGS="$LDFLAGS_ANDROID -L$DEPS/lib" \
  > "$WORK/configure.log" 2>&1 || { tail -30 "$WORK/configure.log"; exit 1; }

# Only the dirs we need (skips doc/ and tools/ which want host-side helpers)
for d in include lib sanei backend; do
  make -C "$d" -j"$(nproc)" > "$WORK/make-$d.log" 2>&1 || { tail -30 "$WORK/make-$d.log"; exit 1; }
done
# No -all-static here: scanimage must stay dynamic against bionic (libc/libm/libdl from /system)
make -C frontend -j"$(nproc)" scanimage > "$WORK/make-frontend.log" 2>&1 \
  || { tail -30 "$WORK/make-frontend.log"; exit 1; }

BIN="$SRC/frontend/scanimage"
[ -f "$BIN" ] || BIN="$SRC/frontend/.libs/scanimage"
mkdir -p "$(dirname "$OUT")"
$STRIP -o "$OUT" "$BIN"
chmod 755 "$OUT"
file "$OUT"
ls -la "$OUT"
