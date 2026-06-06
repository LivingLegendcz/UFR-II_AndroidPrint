#!/bin/bash
# build-bundle.sh — sestaví minimální arm64 rootfs pro assets/rootfs.tar.gz
# Spouštět na x86_64 Linux/WSL2 z kořenového adresáře projektu
# Použití: ./scripts/build-bundle.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJ_DIR="$(dirname "$SCRIPT_DIR")"
DRIVER_ARM64_DEB="$PROJ_DIR/linux-UFRII-drv-v630-m17n/ARM64/Debian/cnrdrvcups-ufr2-uk_6.30-1.07_arm64.deb"
WORK_DIR="/tmp/canon-bundle-$$"
OUT_DIR="$PROJ_DIR/app/src/main/assets"

# NDK / clang pro kompilaci stub knihovny (aarch64-android29)
NDK="${ANDROID_NDK:-$HOME/android-sdk/ndk/27.2.12479018}"
CLANG="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"

# Kontrola prerekvizit
if [ ! -f "$DRIVER_ARM64_DEB" ]; then
  echo "CHYBA: arm64 driver .deb nenalezen: $DRIVER_ARM64_DEB"
  echo "Nejdřív extrahuj tarball: tar -xzf linux-UFRII-drv-v630-m17n-07.tar.gz"
  exit 1
fi

for cmd in dpkg-deb patchelf wget; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "CHYBA: $cmd není nainstalován (apt install $cmd)"
    exit 1
  fi
done

if [ ! -f "$CLANG" ]; then
  echo "CHYBA: aarch64 clang nenalezen: $CLANG"
  echo "Nastav ANDROID_NDK na cestu k NDK (verze 27+)"
  exit 1
fi

mkdir -p "$WORK_DIR/rootfs"
mkdir -p "$OUT_DIR"

echo "=== [1/5] Extrakce Canon arm64 driveru ==="
dpkg-deb -x "$DRIVER_ARM64_DEB" "$WORK_DIR/rootfs"
echo "  ✓ Canon driver extrahován"

echo "=== [2/5] Stažení arm64 runtime knihoven z Debian bookworm ==="
DEB_MIRROR="http://ftp.debian.org/debian/pool/main"
PACKAGES=(
  "g/glibc/libc6_2.36-9+deb12u14_arm64.deb"
  "g/gcc-12/libgcc-s1_12.2.0-14+deb12u1_arm64.deb"
  "g/gcc-12/libstdc++6_12.2.0-14+deb12u1_arm64.deb"
  "c/cups/libcups2_2.4.2-3+deb12u9_arm64.deb"
  "c/cups/libcupsimage2_2.4.2-3+deb12u9_arm64.deb"
  "libj/libjpeg-turbo/libjpeg62-turbo_2.1.5-2_arm64.deb"
  "j/jbigkit/libjbig0_2.1-6.1_arm64.deb"
  "libg/libgcrypt20/libgcrypt20_1.10.1-3+deb12u1_arm64.deb"
  "libg/libgpg-error/libgpg-error0_1.46-1_arm64.deb"
  "z/zlib/zlib1g_1.2.13.dfsg-1_arm64.deb"
  "libx/libxml2/libxml2_2.9.14+dfsg-1.3~deb12u5_arm64.deb"
  "i/icu/libicu72_72.1-3+deb12u1_arm64.deb"
  "x/xz-utils/liblzma5_5.4.1-1_arm64.deb"
)
mkdir -p "$WORK_DIR/debs"
for pkg in "${PACKAGES[@]}"; do
  name=$(basename "$pkg")
  echo "  Stahuji $name..."
  if ! wget -q "$DEB_MIRROR/$pkg" -O "$WORK_DIR/debs/$name"; then
    echo "  CHYBA: nepodařilo se stáhnout $name"
    exit 1
  fi
  dpkg-deb -x "$WORK_DIR/debs/$name" "$WORK_DIR/rootfs"
  echo "  ✓ $name"
done

echo "=== [3/5] Oprava libcups (odstranění nepoužitých síťových závislostí) ==="
# rastertoufr2 jen čte CUPS raster, nepotřebuje TLS/Kerberos/Avahi discovery
LIBCUPS="$WORK_DIR/rootfs/usr/lib/aarch64-linux-gnu/libcups.so.2"
if [ -f "$LIBCUPS" ]; then
  patchelf --remove-needed libgssapi_krb5.so.2 \
           --remove-needed libavahi-client.so.3 \
           --remove-needed libavahi-common.so.3 \
           --remove-needed libgnutls.so.30 \
           "$LIBCUPS"
  # patchelf odstraní DT_NEEDED ale nechá DT_VERNEED záznamy → glibc assert / "version 0".
  # Oprava: vynulovat DT_VERNEED + DT_VERNEEDNUM přímo v .dynamic sekci (Python, inline).
  python3 - "$LIBCUPS" << 'PYEOF'
import struct, sys
path = sys.argv[1]
with open(path, 'r+b') as f:
    data = bytearray(f.read())
assert data[:4] == b'\x7fELF' and data[4] == 2 and data[5] == 1, "Expected ELF64 LE"
e_shoff      = struct.unpack_from('<Q', data, 0x28)[0]
e_shentsize  = struct.unpack_from('<H', data, 0x3A)[0]
e_shnum      = struct.unpack_from('<H', data, 0x3C)[0]
for i in range(e_shnum):
    o = e_shoff + i * e_shentsize
    if struct.unpack_from('<I', data, o+4)[0] == 6:          # SHT_DYNAMIC
        sh_off  = struct.unpack_from('<Q', data, o+24)[0]
        sh_size = struct.unpack_from('<Q', data, o+32)[0]
        p = sh_off
        while p < sh_off + sh_size:
            tag = struct.unpack_from('<Q', data, p)[0]
            if tag in (0x6ffffffe, 0x6fffffff):               # DT_VERNEED / DT_VERNEEDNUM
                struct.pack_into('<QQ', data, p, 0, 0)        # nahradit DT_NULL
                print(f"  zeroed DT_{'VERNEED' if tag==0x6ffffffe else 'VERNEEDNUM'} @{p:#x}")
            p += 16
with open(path, 'wb') as f: f.write(bytes(data))
print("  ✓ DT_VERNEED vynulován")
PYEOF
  echo "  ✓ libcups: síťové závislosti + DT_VERNEED odstraněny"
else
  echo "  CHYBA: $LIBCUPS nenalezen"
  exit 1
fi

echo "=== [3b/5] Sestavení stub knihovny pro gss/gnutls/avahi symboly ==="
# libcups importuje 81 FUNC + 1 OBJECT ze tří knihoven (libgssapi_krb5, libgnutls, libavahi-*),
# které jsme odstranili z DT_NEEDED. Canon raster filtr tyto síťové funkce nikdy nevolá
# (TLS/Kerberos/mDNS), takže prázdné stuby jsou bezpečné. Generujeme je dynamicky z aktuální
# množiny UND symbolů v právě patchovaném libcups, aby seznam zůstal správný při změnách.
STUB_C="$WORK_DIR/stub.c"
STUB_MULTIARCH="$WORK_DIR/rootfs/usr/lib/aarch64-linux-gnu"
STUB_SO="$STUB_MULTIARCH/libcupsstub.so"

cat > "$STUB_C" << 'STUB_HEADER'
/*
 * libcupsstub.so — prázdné stuby pro gss/GSS/gnutls/avahi symboly
 *
 * libcups.so.2 importuje tyto symboly z libgssapi_krb5, libgnutls a libavahi-*,
 * které jsme odebrali z DT_NEEDED, aby nebyl potřeba celý Kerberos/TLS/mDNS stack.
 * Canon UFR-II filtr rastertoufr2 tyto síťové funkce nikdy nevolá — slouží jen
 * pro tisk rastrových dat, nikoli pro síťovou autentizaci nebo discovery.
 * Stuby jsou generovány automaticky z readelf --dyn-syms na patchovaném libcups.
 */
STUB_HEADER

# Generuj stuby z UND symbolů v patchovaném libcups
readelf --dyn-syms -W "$LIBCUPS" | awk '$7=="UND"{print $4,$8}' | grep -E '(gss_|GSS_|gnutls_|avahi_)' | \
while IFS=' ' read -r TYPE NAME; do
  # Přeskoč prázdné řádky
  [ -z "$NAME" ] && continue
  if [ "$TYPE" = "FUNC" ]; then
    printf 'int %s(void){return 0;}\n' "$NAME"
  elif [ "$TYPE" = "OBJECT" ]; then
    printf 'void *%s = 0;\n' "$NAME"
  fi
done >> "$STUB_C"

# Spočítej vygenerované symboly
FUNC_COUNT=$(grep -c '(void){return 0;}' "$STUB_C" || true)
OBJ_COUNT=$(grep -c 'void \*.*= 0;' "$STUB_C" || true)
echo "  Stuby: $FUNC_COUNT FUNC + $OBJ_COUNT OBJECT"

# Kompiluj aarch64 sdílenou knihovnu
# -nostdlib -nodefaultlibs: žádné bionic libc.so/libdl.so v DT_NEEDED (stub volá nic)
"$CLANG" -shared -fPIC \
  -nostdlib -nodefaultlibs -fno-stack-protector -fno-stack-clash-protection \
  -Wl,-soname,libcupsstub.so \
  -Wl,-z,max-page-size=16384 \
  -o "$STUB_SO" \
  "$STUB_C"
echo "  ✓ $STUB_SO zkompilován ($(du -sh "$STUB_SO" | cut -f1))"

# stub musí být bez závislostí (žádné bionic libc.so/libdl.so)
if readelf -d "$STUB_SO" | grep -q NEEDED; then
  echo "  varování: stub má NEEDED, odstraňuji:"; readelf -d "$STUB_SO" | grep NEEDED
  for dep in $(readelf -d "$STUB_SO" | grep NEEDED | sed -E 's/.*\[(.*)\]/\1/'); do
    patchelf --remove-needed "$dep" "$STUB_SO" || true
  done
fi
echo "  ✓ libcupsstub.so NEEDED po opravě:"; readelf -d "$STUB_SO" | grep NEEDED || echo "    (žádné — správně)"

# Přidej libcupsstub.so jako DT_NEEDED a nastav RPATH=$ORIGIN do libcups.so.2
patchelf --add-needed libcupsstub.so "$LIBCUPS"
patchelf --set-rpath '$ORIGIN' "$LIBCUPS"
echo "  ✓ patchelf: libcupsstub.so přidán do DT_NEEDED + RPATH=\$ORIGIN"

# Ověření
echo "  Ověření readelf -d libcups.so.2 (NEEDED + RPATH):"
readelf -d "$LIBCUPS" | grep -E 'NEEDED|RPATH|RUNPATH' | sed 's/^/    /'

echo "=== [4/5] Vytvoření potřebné adresářové struktury ==="
mkdir -p "$WORK_DIR/rootfs/etc/cups/ppd"
mkdir -p "$WORK_DIR/rootfs/var/spool/cups/tmp"
mkdir -p "$WORK_DIR/rootfs/var/cache/cups"
mkdir -p "$WORK_DIR/rootfs/usr/share/cups/mime"
mkdir -p "$WORK_DIR/rootfs/tmp"
mkdir -p "$WORK_DIR/rootfs/proc"
mkdir -p "$WORK_DIR/rootfs/dev"
mkdir -p "$WORK_DIR/rootfs/sys"
# libcaepcmufr2 fallback temp path /var/temp/ (caWclGetTempPath). canonfix remapuje
# /var/temp -> rootfs/var/temp; adresář musí existovat (app-writable v filesDir).
mkdir -p "$WORK_DIR/rootfs/var/temp"

# Belt-and-suspenders: create /etc/ld.so.conf so the dynamic linker reads
# /etc/ld.so.conf.d/*.conf (which already lists /usr/lib/aarch64-linux-gnu).
# Without this file glibc's ldconfig does not search the multiarch dir on first run.
mkdir -p "$WORK_DIR/rootfs/etc/ld.so.conf.d"
printf 'include /etc/ld.so.conf.d/*.conf\n' > "$WORK_DIR/rootfs/etc/ld.so.conf"
echo "  ✓ etc/ld.so.conf créé"

# Belt-and-suspenders: /usr/lib/libjbig.so* symlinks → aarch64-linux-gnu/libjbig.so.0
# The dynamic linker always searches /usr/lib (hardcoded default in glibc) even without
# ldconfig cache.  cnjbigufr2 may dlopen("libjbig.so") / dlopen("libjbig.so.0") by bare name.
# Providing these symlinks in /usr/lib ensures resolution regardless of LD_LIBRARY_PATH order.
mkdir -p "$WORK_DIR/rootfs/usr/lib"
LIBJBIG_REAL="$WORK_DIR/rootfs/usr/lib/aarch64-linux-gnu/libjbig.so.0"
if [ -f "$LIBJBIG_REAL" ]; then
  for JBIG_NAME in libjbig.so libjbig.so.0 libjbig.so.2.0 libjbig.so.2.1; do
    JBIG_LINK="$WORK_DIR/rootfs/usr/lib/$JBIG_NAME"
    if [ ! -e "$JBIG_LINK" ] && [ ! -L "$JBIG_LINK" ]; then
      ln -s "aarch64-linux-gnu/libjbig.so.0" "$JBIG_LINK"
      echo "  ✓ usr/lib/$JBIG_NAME -> aarch64-linux-gnu/libjbig.so.0"
    fi
  done
else
  echo "  VAROVÁNÍ: $LIBJBIG_REAL nenalezeno — cnjbigufr2 nemusí nastartovat"
fi

# Zkopírovat PPD pro MF8030 na cestu kterou rastertoufr2 dostane přes env PPD=
PPD_SRC=$(find "$WORK_DIR/rootfs" -name "CNRCUPSMF8000CZK.ppd" 2>/dev/null | head -1)
if [ -n "$PPD_SRC" ]; then
  cp "$PPD_SRC" "$WORK_DIR/rootfs/etc/cups/ppd/CanonMF8030.ppd"
  echo "  ✓ PPD zkopírováno do etc/cups/ppd/CanonMF8030.ppd"
else
  echo "  CHYBA: CNRCUPSMF8000CZK.ppd nenalezeno v Canon driveru"
  exit 1
fi

echo "=== [5/5] Balení rootfs.tar.gz ==="
tar -czf "$OUT_DIR/rootfs.tar.gz" -C "$WORK_DIR/rootfs" .
SIZE=$(du -sh "$OUT_DIR/rootfs.tar.gz" | cut -f1)
FILE_COUNT=$(tar -tzf "$OUT_DIR/rootfs.tar.gz" | wc -l)
echo "  ✓ $OUT_DIR/rootfs.tar.gz ($SIZE, $FILE_COUNT souborů)"

sha256sum "$OUT_DIR/rootfs.tar.gz" | awk '{print $1}' > "$OUT_DIR/rootfs.version"
echo "  ✓ rootfs.version = $(cat "$OUT_DIR/rootfs.version")"

echo "  Ověřuji klíčové soubory:"
for f in \
  "usr/lib/cups/filter/rastertoufr2" \
  "usr/bin/cnjbigufr2" \
  "usr/share/cups/model/CNRCUPSMF8000CZK.ppd" \
  "etc/cups/ppd/CanonMF8030.ppd" \
  "usr/lib/aarch64-linux-gnu/libcups.so.2" \
  "usr/lib/aarch64-linux-gnu/libcupsstub.so" \
  "lib/aarch64-linux-gnu/libc.so.6" \
  "usr/lib/aarch64-linux-gnu/libstdc++.so.6" \
  "lib/aarch64-linux-gnu/libz.so.1" \
  "usr/lib/aarch64-linux-gnu/libxml2.so.2" \
  "usr/lib/aarch64-linux-gnu/libicuuc.so.72" \
  "usr/lib/aarch64-linux-gnu/libicudata.so.72" \
  "lib/aarch64-linux-gnu/liblzma.so.5" \
  "usr/share/caepcm/ufr2" \
  "usr/share/ufr2filterr/ThLB_27A.BIN" \
  "usr/share/cnpkbidir/cnpkbidir_info_001.xml"; do
  if tar -tzf "$OUT_DIR/rootfs.tar.gz" "./$f" >/dev/null 2>&1; then
    echo "    ✓ $f"
  else
    echo "    ✗ CHYBÍ: $f"
  fi
done

# Úklid
rm -rf "$WORK_DIR"
echo ""
echo "=== HOTOVO ==="
echo "Umístění: $OUT_DIR/rootfs.tar.gz"
echo "Další krok: ./gradlew assembleDebug"
