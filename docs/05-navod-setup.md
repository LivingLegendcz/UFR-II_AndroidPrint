# 05 - Návod krok za krokem (dev scaffold)

Kompletní setup. **Fáze 1 je validační brána** - dokud z tiskárny nevyleze stránka, nestaví se appka.

> ⚠️ **Pozn.:** Termux/proot níže slouží jako **vývojový scaffold** k odladění filter chain.
> Cílová appka bude **self-contained** (zabalený proot + ovladač) a Termux **nepotřebuje** -
> viz [04-reseni-architektura.md](04-reseni-architektura.md). Tento návod použij k ověření,
> že Canon arm64 ovladač na telefonu skutečně vytiskne, a k zachycení přesného volání filtru.

> Tiskárna `192.168.0.62`, telefon musí být na stejné WiFi.

---

## Fáze 0 - Předpoklady

1. Ověřit, že telefon je **arm64**: v Termuxu `uname -m` → musí být `aarch64`.
2. Stáhnout Canon ovladač **`linux-UFRII-drv-v630-m17n-07.tar.gz`** (~58 MB, deb `6.30-1.07`) z:
   `https://www.canon.cz/support/consumer/products/printers/i-sensys/mf-series/i-sensys-mf8030cn.html?type=drivers&os=Linux%20(64-bit)`
   (nebo Canon Asia: `https://asia.canon/en/support/0100924010`). Uložit do `/sdcard/Download/`.

---

## Fáze 1 - Pipeline na telefonu (GATE)

### 1.1 Termux
- Nainstalovat **Termux z F-Droid** (`https://f-droid.org/packages/com.termux/`) nebo GitHub.
  **NE z Google Play** (zastaralé).
- Volitelně **Termux:Widget** (pro Fázi 2a) - také z F-Droid.

### 1.2 proot Debian 13
```bash
pkg update && pkg upgrade -y
pkg install -y proot-distro
proot-distro install debian          # nainstaluje trixie (Debian 13)
export PROOT_NO_SECCOMP=1             # fix Android 15/16 seccomp
proot-distro login debian
```

### 1.3 Uvnitř Debianu: CUPS (BEZ Ghostscriptu)
```bash
apt update && apt install -y cups cups-bsd libjpeg62 libjbig0 libgcrypt20 jbigkit-bin
```
> ✅ **Ghostscript ani OPVP NETŘEBA** - ovladač v6.30 je self-contained (`cupsFilter: …
> rastertoufr2`), viz [07-ovladac-rozbor.md](07-ovladac-rozbor.md). (Starší literatura mluví
> o gs/OPVP - týká se starší generace ovladače, ne tohoto balíku.)

### 1.4 Canon ovladač (arm64 .deb)
```bash
cd /root
cp /sdcard/Download/linux-UFRII-drv-v630-m17n-07.tar.gz .   # nebo wget přímo
tar xzf linux-UFRII-drv-v630-m17n-07.tar.gz
cd linux-UFRII-drv-v630-m17n

# arm64 balík je v ARM64/Debian (POZOR: v6.30 = jediný .deb, ne tři)
ls ARM64/Debian/
dpkg -i ./ARM64/Debian/cnrdrvcups-ufr2-uk_6.30-1.07_arm64.deb
apt-get -f install -y         # dotáhnout závislosti (cups, libjpeg62, libjbig0, libgcrypt20…)
```
> Pozn.: tenhle ovladač **nepotřebuje ghostscript** (`cupsFilter: … rastertoufr2`).

### 1.5 CUPS fronta (RAW socket na tiskárnu)
```bash
cupsd -f &                                       # spustit CUPS jako user daemon
sleep 1
ls /usr/share/cups/model/ | grep -i mf8000       # PPD = CNRCUPSMF8000CZK.ppd

lpadmin -p Canon_MF8030Cn \
        -v socket://192.168.0.62:9100 \
        -P /usr/share/cups/model/CNRCUPSMF8000CZK.ppd \
        -E
lpstat -p Canon_MF8030Cn        # ověřit, že fronta existuje a je enabled
```

### 1.6 TEST (validační brána)
```bash
echo "Canon MF8030Cn test $(date)" | enscript -o test.ps 2>/dev/null || \
  printf "test\n" > test.txt
lp -d Canon_MF8030Cn /sdcard/Download/test.pdf     # nejlépe reálné PDF
lpstat -W completed -o                              # stav úlohy
```
✅ **Z tiskárny vyleze stránka → pipeline funguje, pokračuj na Fázi 2.**
Vyzkoušet i barevné PDF.

---

## Fáze 2 - "Appka"

### 2a - Termux:Widget shortcut (MVP)
Vytvoř `~/.shortcuts/print-canon.sh` (viz hotový `scripts/print-canon.sh` v repu):
```bash
mkdir -p ~/.shortcuts
# zkopíruj scripts/print-canon.sh do ~/.shortcuts/ a dej mu +x
chmod +x ~/.shortcuts/print-canon.sh
```
Přidej Termux:Widget na plochu → tap → tisk.

### 2b - Tenké Android APK (cílový stav)
APK postaví Claude na PC (složka `app/`). Po instalaci:
- v PDF prohlížeči/galerii **Sdílet → Canon MF8030Cn**,
- APK pošle `com.termux.RUN_COMMAND` intent → Termux spustí tisk,
- APK ukáže status.

Předpoklad: v Termuxu povolit external apps (`~/.termux/termux.properties`:
`allow-external-apps = true`) a udělit APK permission `com.termux.permission.RUN_COMMAND`.

---

## Pozn. - Ghostscript/OPVP (NEAKTUÁLNÍ pro v6.30)

Dřívější verze plánu řešily křehkou závislost na Ghostscript+OPVP. **Rozbor balíku v6.30
(docs/07) ukázal, že shipped ovladač je self-contained a gs/OPVP nepotřebuje.** Tahle sekce
je tu jen jako historická poznámka - pokud bys narazil na starší ovladač (jiný model/verze)
vyžadující OPVP, řešení bylo Debian 13 trixie / build `gs --with-openprinting` / `ghostscript-printer-app`.

---

## Užitečné příkazy (provoz)

```bash
# znovu vstoupit do prostředí
export PROOT_NO_SECCOMP=1 && proot-distro login debian

# nastartovat CUPS, když neběží
pgrep cupsd || cupsd -f &

# tisk
lp -d Canon_MF8030Cn soubor.pdf

# fronta / úlohy
lpstat -p Canon_MF8030Cn
lpstat -o                 # čekající úlohy
cancel -a Canon_MF8030Cn  # zrušit vše
```
