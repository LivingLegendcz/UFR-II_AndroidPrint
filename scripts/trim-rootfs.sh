#!/bin/bash
# trim-rootfs.sh — odstraní z rozbaleného rootfs vše, co tiskový filtr nepotřebuje
# Použití: ./scripts/trim-rootfs.sh <rootfs-dir>
# Volá ho build-bundle.sh před zabalením; jde pustit i na rozbalený existující rootfs.tar.gz.
#
# Jen bezpečné položky (data, která filtr rastertoufr2 nečte):
#   - PPD ostatních modelů (filtr dostane jen etc/cups/ppd/CanonMF8030.ppd přes env PPD=)
#   - dokumentace, překlady hlášek, lintian/gdb/gcc pomocná data
#   - cngplp2 (GTK nastavovací GUI — bez GTK v rootfs stejně nejde spustit)
# Barevné tabulky caepcm, gconv a ICU zůstávají: o nich rozhodne až trace skutečně
# otevíraných souborů (etapa B).

set -euo pipefail

ROOT="${1:?Použití: $0 <rootfs-dir>}"
[ -x "$ROOT/usr/lib/cups/filter/rastertoufr2" ] || { echo "CHYBA: $ROOT nevypadá jako rootfs"; exit 1; }

before=$(du -sk "$ROOT" | cut -f1)

find "$ROOT/usr/share/cups/model" -type f ! -name 'CNRCUPSMF8000CZK.ppd' -delete
rm -rf "$ROOT/usr/share/doc" "$ROOT/usr/share/locale" "$ROOT/usr/share/lintian" \
       "$ROOT/usr/share/gdb" "$ROOT/usr/share/gcc" "$ROOT/usr/share/applications" \
       "$ROOT/usr/share/icons" "$ROOT/usr/share/cngplp2" "$ROOT/usr/bin/cngplp2"

after=$(du -sk "$ROOT" | cut -f1)
echo "  ✓ rootfs ořezán: $((before / 1024)) MB → $((after / 1024)) MB"
