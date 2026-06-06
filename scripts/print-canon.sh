#!/data/data/com.termux/files/usr/bin/bash
# print-canon.sh — Termux:Widget shortcut pro tisk na Canon MF8030Cn
#
# ⚠️ DEV SCAFFOLD / FALLBACK, ne shipped řešení. Cílová appka je self-contained APK
#    bez Termuxu (viz docs/04 + docs/08). Tohle je rychlý spouštěč přes proot Debian.
#
# Umístění: ~/.shortcuts/print-canon.sh  (chmod +x)
#
# Použití:
#   - bez argumentu: vytiskne poslední PDF ze /sdcard/Download
#   - s argumentem:  print-canon.sh /cesta/k/souboru.pdf
#
# Renderuje reálný Canon arm64 UFR-II ovladač uvnitř proot Debianu.

set -euo pipefail

QUEUE="Canon_MF8030Cn"
DISTRO="debian"
DEFAULT_DIR="/sdcard/Download"

export PROOT_NO_SECCOMP=1

# vybrat soubor
FILE="${1:-}"
if [ -z "$FILE" ]; then
  FILE="$(ls -t "$DEFAULT_DIR"/*.pdf 2>/dev/null | head -n1 || true)"
fi
if [ -z "$FILE" ] || [ ! -f "$FILE" ]; then
  echo "❌ Žádný PDF soubor k tisku (zadej cestu nebo dej PDF do $DEFAULT_DIR)"
  exit 1
fi

echo "🖨  Tisknu: $FILE → $QUEUE"

proot-distro login "$DISTRO" -- bash -lc '
  set -e
  # nastartovat CUPS, když neběží
  pgrep cupsd >/dev/null 2>&1 || cupsd -f &
  sleep 1
  lp -d "'"$QUEUE"'" "'"$FILE"'"
  echo "✅ Odesláno do fronty."
  lpstat -o || true
'
