#!/data/data/com.termux/files/usr/bin/bash
# setup-debian.sh — DEV SCAFFOLD (Fáze 1): odladit Canon ovladač v proot Debianu na telefonu.
#
# ⚠️ TOHLE NENÍ SHIPPED ŘEŠENÍ. Cílová appka je self-contained APK BEZ Termuxu
#    (proot jako libproot.so v jniLibs) — viz docs/04 + docs/08. Tenhle skript slouží jen
#    k ověření, že Canon arm64 ovladač na telefonu vytiskne, a k zachycení referenčního
#    streamu/volání filtru (Fáze 1.5).
#
# Spustit V TERMUXU. Ovladač (tarball) musí být ve /sdcard/Download.
# Pozn.: ovladač v6.30 = JEDEN .deb, NEpotřebuje ghostscript (filter chain rastertoufr2).

set -euo pipefail

DRIVER_TGZ="/sdcard/Download/linux-UFRII-drv-v630-m17n-07.tar.gz"
PPD="CNRCUPSMF8000CZK.ppd"     # barva; mono = CNRCUPSMF8000ZK.ppd (mono-first doporučeno)
PRINTER_IP="192.168.0.62"
export PROOT_NO_SECCOMP=1

echo "== 0) kontrola architektury (čekáme aarch64) =="
uname -m

echo "== 1) proot-distro + Debian 13 =="
pkg install -y proot-distro
proot-distro list | grep -q "debian.*installed" || proot-distro install debian

echo "== 2) zkopírovat ovladač do Debianu =="
if [ ! -f "$DRIVER_TGZ" ]; then
  echo "❌ Nenalezen $DRIVER_TGZ — stáhni Canon UFR II v6.30 a ulož do Download."
  exit 1
fi
cp "$DRIVER_TGZ" "$PREFIX/var/lib/proot-distro/installed-rootfs/debian/root/" 2>/dev/null || true

echo "== 3) instalace uvnitř Debianu (jeden arm64 .deb, BEZ ghostscriptu) =="
proot-distro login debian -- bash -lc '
  set -e
  apt update
  apt install -y cups cups-bsd libjpeg62 libjbig0 libgcrypt20 jbigkit-bin
  cd /root
  tar xzf linux-UFRII-drv-v630-m17n-07.tar.gz
  cd linux-UFRII-drv-v630-m17n
  ls ARM64/Debian/
  dpkg -i ./ARM64/Debian/cnrdrvcups-ufr2-uk_6.30-1.07_arm64.deb || true
  apt-get -f install -y
  echo "--- PPD ověření ---"
  ls /usr/share/cups/model/ | grep -i mf8000
'

echo "== 4) fronta + test (doplň ručně podle docs/05) =="
cat <<EOF
👉 Uvnitř Debianu dokonči:
   cupsd -f &
   lpadmin -p Canon_MF8030Cn -v socket://$PRINTER_IP:9100 \\
           -P /usr/share/cups/model/$PPD -E
   lpstat -p Canon_MF8030Cn
   lp -d Canon_MF8030Cn /sdcard/Download/test.pdf      # GATE (Fáze 1)

👉 Fáze 1.5 — zachytit referenci (pro pozdější self-contained APK):
   # golden UFR-II stream:
   #   nahraď socket backend dumpem, nebo zkopíruj /var/spool/cups/ data
   # strace open-list (jaké soubory filtr čte → manifest pro bundle):
   apt install -y strace
   # spusť tisk pod 'strace -f -e trace=open,openat,execve lp -d Canon_MF8030Cn test.pdf'
EOF
