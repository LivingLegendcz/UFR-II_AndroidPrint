# Phase 1 + 1.5 - Výsledky a golden reference

## Status: ✅ DOKONČENO (2026-06-03)

> **Poznámka (Phase 3):** Scaffold popsaný v tomto dokumentu (CUPS fronta, gs/texttopdf pipeline,
> `lpadmin`) sloužil **výhradně jako vývojový nástroj** pro zachycení golden reference a ověření
> tisku na fyzické tiskárně. **Finální APK CUPS nenainstaluje** - generuje CUPS raster přímo
> v Kotlinu třídou `CupsRasterWriter` (PdfRenderer → banding → RGB raster v3). Viz [docs/13](13-phase3-vysledky.md).

---

## Phase 1: Dev scaffold - fyzický tisk ověřen

### Co jsme zjistili
Místo plánovaného Termux+proot na telefonu jsme použili **WSL2 Ubuntu 24.04 (amd64)**
jako dev sandbox - tarball obsahuje i amd64 balíček a tiskárna je dostupná ze sítě.

### Instalační postup (WSL2 Ubuntu 24.04)

```bash
# Závislosti (Ubuntu 24.04: libcupsimage2 → libcupsimage2t64)
sudo apt install -y cups cups-bsd libcupsimage2t64 libjbig0 libgcrypt20 jbigkit-bin strace

# Driver (amd64)
cd CanonMF8030-AndroidPrint
tar -xzf linux-UFRII-drv-v630-m17n-07.tar.gz
sudo dpkg -i linux-UFRII-drv-v630-m17n/x64/Debian/cnrdrvcups-ufr2-uk_6.30-1.07_amd64.deb
sudo apt-get -f install -y

# CUPS fronta
sudo service cups start
sudo lpadmin -p CanonMF8030 \
  -v "socket://192.168.0.62:9100" \
  -P /usr/share/cups/model/CNRCUPSMF8000CZK.ppd \
  -E
sudo lpoptions -d CanonMF8030
```

**Poznámka:** CUPS fronta se po restartu ztratí (není uložena persistentně v WSL2).
Po každém `service cups start` spustit `lpadmin` znovu nebo uložit jako systemd service.

### Ověření tisku

```bash
# Textový tisk
echo "Test" | lp -d CanonMF8030

# CUPS testovací stránka (barevná)
lp -d CanonMF8030 /usr/share/cups/data/testprint

# Sledování jobu
sudo journalctl -u cups -f
```

**Výsledek:** Text i barevný tisk fyzicky potvrzeny na Canon MF8030Cn. ✅

---

## Phase 1.5: Golden reference captures

### Metoda zachycení

Použit wrapper skript přes originální `rastertoufr2`:

```bash
#!/bin/bash
# /usr/lib/cups/filter/rastertoufr2 (wrapper)
CAPTURE_DIR="/tmp/canon_capture"
mkdir -p "$CAPTURE_DIR"
env > "$CAPTURE_DIR/env.txt"
printf '%s\n' "$@" > "$CAPTURE_DIR/args.txt"
# CUPS raster přichází přes stdin (piped z universal filtru)
tee "$CAPTURE_DIR/raster_input.bin" | exec /usr/lib/cups/filter/rastertoufr2.real "$@"
```

**DŮLEŽITÉ:** `/tmp/canon_capture/` musí mít `chmod 1777` - CUPS spouští filtry jako user `lp`.

UFR-II stream zachycen: `sudo tcpdump -i any -w ufrii_stream.pcap "port 9100"`

### Zachycené soubory (v `captures/`)

| Soubor | Velikost | Obsah |
|--------|----------|-------|
| `golden_raster.bin` | 92 MB | CUPS Raster v3 LE, vstup do rastertoufr2 |
| `ufrii_stream.pcap` | 335 KB | UFR-II TCP stream na port 9100 |
| `cups_env.txt` | 809 B | Prostředí předané CUPS filtru |
| `cups_args.txt` | 308 B | Argumenty volání filtru |

### CUPS Raster v3 page header - klíčové parametry

```
Sync:              RaS3 (0x33536152, little-endian = CUPS Raster v3)
PageSize:          595 × 842 pt  (= A4)
HWResolution:      600 DPI
MediaType:         'Auto'
cupsPageSizeName:  'A4'
cupsRenderingIntent: 'auto'
Raster velikost:   ~92 MB / stránka (nekomprimovaný, 600 DPI, color)
```

Výpočet pro kontrolu: A4 @600 DPI = 4961 × 7016 px. Při 8 bpp × 4 kanály (CMYK) =
4961 × 7016 × 4 = ~139 MB nekomprimovaného. Skutečný soubor je 92 MB → zřejmě RGB (3 kanály)
nebo komprimovaný řádkový raster.

### Kritické env vars pro Android APK

```
PPD=/etc/cups/ppd/CanonMF8030.ppd
CUPS_DATADIR=/usr/share/cups
CUPS_SERVERBIN=/usr/lib/cups
CUPS_SERVERROOT=/etc/cups
CUPS_CACHEDIR=/var/cache/cups
CUPS_STATEDIR=/run/cups
PATH=/usr/lib/cups/filter:/usr/bin:/usr/sbin:/bin:/usr/bin
HOME=/var/spool/cups/tmp
TMPDIR=/var/spool/cups/tmp
```

### Argumenty volání rastertoufr2

```
$1 = CanonMF8030          (printer name)
$2 = <job_id>             (číslo jobu)
$3 = <username>           (uživatel)
$4 = <job_title>          (název jobu)
$5 = 1                    (počet kopií)
$6 = "finishings=3 number-up=1 print-color-mode=color CNColorMode=color CNDraftMode=False ..."
# $7 = file path (jen pokud CUPS nepipuje přes stdin; v pipeline obvykle stdin)
```

### CUPS filter pipeline pro text/plain

```
text/plain
  → [universal filter] texttopdf + pdftopdf + ghostscript
  → application/vnd.cups-raster (stdin pipe)
  → [rastertoufr2] /usr/lib/cups/filter/rastertoufr2
  → UFR-II byte stream (stdout)
  → [socket backend] TCP socket 192.168.0.62:9100
  → tiskárna
```

---

## Co z toho plyne pro Android APK (Phase 2+)

### Generování CUPS raster v Android APK

Android PdfRenderer generuje Bitmap (ARGB_8888 nebo RGB_565). Je třeba konvertovat na
CUPS Raster v3 kompatibilní s tím, co `rastertoufr2` očekává:
- Sync word: `RaS3` (0x52615333 BE nebo 0x33536152 LE)
- Page header: cups_page_header2_t (1796 B)
  - MediaType = "Auto"
  - HWResolution = 600 dpi
  - PageSize = [595, 842] (A4 v bodech)
  - cupsWidth = 4961, cupsHeight = 7016 (px při 600 DPI)
  - cupsBitsPerColor = 8
  - cupsBitsPerPixel = 24 (RGB) nebo 32 (CMYK)
  - cupsBytesPerLine = 4961 × 3 (RGB) = 14883 nebo × 4 (CMYK) = 19844
  - cupsColorSpace = 1 (sRGB) nebo 6 (CMYK)
- Page data: řádky pixelů (bez komprese)

### env vars v APK

```kotlin
// V proot chroot prostředí nastavit:
val env = mapOf(
    "PPD" to "${filesDir}/cups/ppd/CanonMF8030.ppd",
    "CUPS_DATADIR" to "${filesDir}/cups/share",
    "CUPS_SERVERBIN" to "${filesDir}/cups/lib",
    "CUPS_SERVERROOT" to "${filesDir}/cups/etc",
    "CUPS_CACHEDIR" to "${cacheDir}/cups",
    "TMPDIR" to "${cacheDir}",
    "HOME" to "${filesDir}",
    "PATH" to "${filesDir}/cups/lib/filter:${filesDir}/usr/bin:${filesDir}/usr/sbin:/bin"
)
```
