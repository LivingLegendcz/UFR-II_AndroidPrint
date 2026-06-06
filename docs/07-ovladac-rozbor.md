# 07 - Rozbor Canon ovladače (v6.30 arm64)

Rozbor balíku `cnrdrvcups-ufr2-uk_6.30-1.07_arm64.deb` z `linux-UFRII-drv-v630-m17n-07.tar.gz`.
**Zásadní zjištění: tenhle ovladač je self-contained - NEpotřebuje Ghostscript/OPVP.**

## Architektura binárek

Všechny ELF binárky a knihovny jsou **64bit AArch64 (arm64)** → poběží na moderním telefonu.
(armhf/32-bit varianta v balíku NENÍ.)

## Filter chain (z PPD `CNRCUPSMF8000CZK.ppd`)

```
*cupsFilter: "application/vnd.cups-raster 0 rastertoufr2"
*ModelName:  "Canon MF8000C Series UFRII LT"
*NickName:   "Canon MF8000C Series UFRII LT"
*DefaultResolution: 600
```

→ Vstup = **CUPS raster** (`application/vnd.cups-raster`), filtr `rastertoufr2` ho převede na
UFR-II. **Žádný Ghostscript, žádný OPVP device, žádný `libcanonc3pl` přes gs.** To je novější
generace ovladače než ta, co popisuje starší literatura - největší riziko projektu (křehká
OPVP závislost) tím **odpadá**.

Pipeline na telefonu pak může být:
```
PDF → (Android PdfRenderer → bitmapa → CUPS raster)  → rastertoufr2 → UFR-II → socket :9100
```
CUPS raster si můžeme vygenerovat sami z PdfRenderer bitmap (nepotřebujeme pdftoraster/gs).
Alternativně balík nabízí i vlastní PDF cestu (`cnpdfdrv`, `pdftocpca`) - upřesní se při ladění.

## Závislosti (z control souboru)

```
Depends: cups | cupsys, libcups2 | libcups2t64, libcupsimage2 | libcupsimage2t64,
         cups-bsd, libgtk-3-0 (jen GUI cngplp2 - headless netřeba),
         libjpeg62, libjbig0, libgcrypt20, lsb-release
```
**Žádný ghostscript!** glibc closure k zabalení: glibc + loader, libcups2, libcupsimage2,
libjpeg62, libjbig0, libgcrypt20 (+ jejich tranzitivní deps). GTK jen pro GUI → vynechat.

`Installed-Size: 114818` KB ≈ **112 MB** (plná instalace všech modelů).

## Obsah balíku (klíčové soubory)

**CUPS filtry / backend** (`/usr/lib/cups/`):
| Soubor | Velikost | Role |
|---|---|---|
| `filter/rastertoufr2` | 18 KB | **CUPS raster → UFR-II** (hlavní filtr) |
| `filter/pdftocpca` | 10 KB | PDF → CPCA (alternativní cesta) |
| `backend/cnusbufr2` | 18 KB | USB backend (pro síť netřeba - jedeme socket) |

**Binárky** (`/usr/bin/`): `cnpdfdrv` (40 KB), `cnpkmoduleufr2r` (202 KB, jádro renderu),
`cnrsdrvufr2` (66 KB), `cnpkbidir` (58 KB, status/bidi), `cnjbigufr2`, `cnjatool2`,
`cnsetuputil2`, `cngplp2` (GUI).

**Canon knihovny** (`/usr/lib/`, ~2 MB celkem):
`libcanonufr2r.so` (374 KB), `libColorGearCufr2.so` (524 KB), `libuictlufr2r.so` (321 KB),
`libcnlbcmr.so` (277 KB), `libcaepcmufr2.so` (488 KB), `libcanon_slimufr2.so`,
`libufr2filterr.so`, `libcaiowrapufr2.so`, `libcaiocnpkbidir.so`.

**Color data** (`/usr/share/caepcm/ufr2/`, **~60 MB**): stovky `.DAT` + `.ICC` profilů.
Pro MF8030Cn potřebujeme jen podmnožinu → lze výrazně ořezat.

**PPD** (`/usr/share/cups/model/`): pro MF8030Cn = **`CNRCUPSMF8000CZK.ppd`**
(barevná MF8000C série; `CNRCUPSMF8000ZK.ppd` = mono varianta).

## Co z toho plyne pro "appku bez Termuxu"

Závislostní closure je malý a čistý, ovladač self-contained → **vše jde zabalit do vlastní APK**
(driver + glibc libs + statický `proot`). Viz [04-reseni-architektura.md](04-reseni-architektura.md).
