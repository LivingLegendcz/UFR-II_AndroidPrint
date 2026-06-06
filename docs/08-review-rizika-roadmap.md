# 08 - Review, rizika a opravená roadmapa

Konsolidace nálezů z revize (3 paralelní agenti, 2026-06-03). **Tohle je autoritativní zdroj
pro „co zbývá / na co pozor".** Starší přípravné fáze jsou archivovány v PROJECT-LOG.md.

---

## 🔴 P0 - kritická technická rizika (load-bearing, neověřená)

### R1 - Android zákaz spouštění binárek z datového adresáře ✅ VYŘEŠENO

**Status: ✅ Ověřeno a funkční v Phase 3 (2026-06-05)**

**Původní obava:** Od Android 10 (targetSdk ≥ 29) appka nesmí `execve()` soubor z `filesDir` (W^X / SELinux deny na `app_data_file`).

**Jak bylo vyřešeno:**
- `proot` zabalen jako **`libproot.so` v `jniLibs/arm64-v8a/`** - spouští se z `nativeLibraryDir` (jediné exec-capable místo). ✅
- proot přes ptrace interceptuje `execve` a spouští guest binárky (`rastertoufr2`) přes vlastní ELF loader - ty mohou být ve filesDir/rootfs. ✅

**Skutečné problémy (ne ty původně předpokládané):**

1. **Termux proot 5.1.107.76 selhal na Android 16** s `execve ENOSYS` + `ptrace(PEEKDATA): I/O error`. Nebyl to problém 16KB stránek (S24+ má 4KB), ani seccomp/SELinux - prostě starý/chybný binár. **Oprava:** přeložit proot přes `green-green-avk/build-proot-android` s NDK r27, staticky linkovaný talloc 2.4.2, flag `-Wl,-z,max-page-size=16384`.

2. **16KB stránky - planý poplach pro S24+.** Zařízení použité pro GATE (Samsung S24+, SM-S926B) má page size 4KB. Flag `-Wl,-z,max-page-size=16384` byl přidán preventivně pro budoucí zařízení s 16KB stránkami (Pixel 8+/9, budoucí S-série).

**Závěr:** R1 je vyřešen architekturicky (jniLibs W^X bypass). Klíčem bylo přeložit proot čerstvě, ne spoléhat na Termux binary.

→ Viz [docs/13 N1](13-phase3-vysledky.md#n1--špatný-proot-binár-execve-enosys--ptrace-io-error) pro detail.

### R2 - Generování CUPS rasteru (nové #1 riziko, nahradilo OPVP)
Filter chain = `application/vnd.cups-raster 0 rastertoufr2`. CUPS raster si musíme vyrobit sami
z PdfRenderer bitmap. `rastertoufr2` čte `cups_page_header2_t` a pole **musí sedět na PPD**:
`HWResolution 600×600`, `cupsWidth/Height`, `cupsBitsPerColor`, `cupsBytesPerLine`,
`cupsColorOrder`, a hlavně **`cupsColorSpace` (NEhádat RGB vs CMYK!)**, `PageSize`/marginy.
Špatně → prázdné/rozsypané stránky nebo pád filtru.

**Řešení:**
- Ve scaffoldu **zachytit referenční raster**, který CUPS reálně předává `rastertoufr2`
  (`cupsfilter -e -p PPD`, nebo dump streamu do filtru) → **bajtově replikovat** hlavičku.
- Použít raster **v3 nekomprimovaný** (`RaS3`/`3SaR` LE), ať se vyhneme RLE.
- **Alternativa:** balíkový **`pdftocpca`/`cnpdfdrv`** (PDF cesta) - pokud funguje, hand-rolled
  raster odpadá úplně. Vyhodnotit ve Fázi 1.5.

### R3 - `rastertoufr2` standalone (bez cupsd) potřebuje env + data na cestách
Volá se `rastertoufr2 job user title copies "options" [file]` (argv[0]=název tiskárny),
raster na stdin, UFR-II na stdout. Cupsd normálně dodá env - musíme nafejkovat:
`PPD=`, `CONTENT_TYPE=application/vnd.cups-raster`, `FINAL_CONTENT_TYPE`, `PRINTER`,
`CUPS_DATADIR`, `CUPS_SERVERROOT`, `RIP_CACHE`, `PATH/LANG/TZ`. Filtr dělá `ppdOpenFile(getenv("PPD"))`
→ PPD musí být přítomný a parsovatelný; Canon `libcanon*` + `/usr/share/caepcm/ufr2` color data
musí ležet na **napevno daných cestách** (chybějící `.ICC/.DAT` může filtr shodit).
Cupsd **netřeba**.

### R4 - Ověřit „self-contained" `strace`-em, ne výpisem .deb
Závěr „nepotřebuje ghostscript" plyne z jedné `cupsFilter` řádky. Skutečný manifest závislostí =
**`strace -f` reálné úlohy** ve scaffoldu (jaké binárky filtr spouští, jaké soubory otevírá).
Z toho odvodit: (a) přesnou podmnožinu caepcm dat k zabalení, (b) glibc closure (`ldd`).

---

## 🟢 Barva i mono - výběr při tisku (POŽADAVEK)

**Appka musí umět vybrat barva/mono při každém tisku** (per-job toggle). Dobrá zpráva:
**stačí jeden barevný PPD `CNRCUPSMF8000CZK.ppd`** - má volbu:
```
*OpenUI *CNColorMode/Color Mode: PickOne
*CNColorMode color/Color
*CNColorMode mono/Black and White
*DefaultCNColorMode: color
```
→ UI přepínač mapuje na option **`CNColorMode=color|mono`** předanou filtru (přes options argv
/ `lpoptions`). Není potřeba bundlovat zvlášť mono PPD.

**`*DefaultColorSpace: RGB`** → filtr chce na vstupu **RGB raster** a CMYK separaci si dělá sám
interně (přes caepcm ICC). To **snižuje R2** - PdfRenderer dává RGB nativně, nemusíme dělat
vlastní CMYK konverzi. (Pro mono buď RGB raster + `CNColorMode=mono`, nebo grayscale; potvrdí
golden reference ve Fázi 1.5.)

**Dev pořadí (≠ feature set):** validovat napřed **mono** (jednodušší, menší riziko halftone/ICC),
hned poté přidat **barvu** - ale **finální appka má vždy oba režimy přepínatelné při tisku.**

---

## Opravená fázová roadmapa (s validačními bránami)

| Fáze | Cíl | GATE (pass/fail) |
|---|---|---|
| **1** | Dev scaffold (Termux/proot Debian) - `rastertoufr2` reálně vytiskne | ✅ Fyzický výtisk z MF8030Cn (2026-06-03) |
| **1.5** | Zachytit přesné volání filtru: argv, env, **referenční CUPS raster**, `strace -f` open-list, golden UFR-II stream | ✅ Mám golden referenci + manifest souborů (2026-06-03) |
| **2 (spike)** | Z **holé APK bez Termuxu** spustit `libproot.so` + `rastertoufr2`, reprodukovat tisk | ✅ „Termux nenainstalován → přesto vytiskne" (2026-06-03) |
| **3** | Self-contained appka: PdfRenderer→raster v Kotlin/NDK, volby, status, share target | ✅ **GATE PASSED** - fyzický barevný + mono výtisk, S24+/Android 16 (2026-06-05) |

> Termux RUN_COMMAND / Termux:Widget appka (původní PLAN Fáze 2a/2b) = **scaffold/fallback**, ne cíl.

---

## Fallback žebřík (kdyby self-contained selhalo)

1. **Self-contained APK** (proot v jniLibs) - cíl.
2. **APK + Termux `RUN_COMMAND`** - appka, ale runtime přes Termux.
3. **Termux:Widget skript** - funguje end-to-end, jen méně „appka".
4. **Raspberry Pi / starý x86 PC** s desktop Canon ovladačem jako CUPS/IPP-Mopria bridge -
   „nudné, ale nesmrtelné" (řeší i uživatelovu obavu, že proot/Android politika projekt zabije).

---

## Doplněné mezery (dříve nezachycené)

### Error handling & status (G1)
Definovat chování při: tiskárna offline / `:9100` connection refused / timeout, paper-out,
toner, odmítnutá úloha. Socket timeout + retry policy + viditelná chyba v UI (ne jen spinner).
Rozhodnout, zda číst zpětný status (`cnpkbidir` / SNMP) nebo ignorovat (jen one-way 9100).

### Tiskové volby (G2) - přesné PPD options
Z PPD `CNRCUPSMF8000CZK.ppd` (volby předávané filtru jako `-o KEY=VAL`):

| UI volba | PPD option | Hodnoty | Default |
|---|---|---|---|
| **Barva / Mono** ⭐ | `CNColorMode` | `color` / `mono` | color |
| Formát | `PageSize` | A4 / Letter / … | A4 |
| Rozlišení | `Resolution` | 600 | 600 |
| Úspora toneru | `CNDraftMode` | True / False | False |
| Typ média | `MediaType` | Auto / … | Auto |
| Kompletace | `Collate` | - | Group |
| Halftone (barva) | `CNColorHalftone` | Resolution / Gradation | Gradation |
| Halftone (ČB) | `CNHalftone` | - / Gradation | Gradation |

- **MF8030Cn nemá auto-duplex** → duplex nevystavovat (max. manuální).
- Vstup filtru = **RGB raster** (`DefaultColorSpace: RGB`); CMYK separaci dělá ovladač sám.
- MVP UI minimum: **barva/mono** + formát (A4) + kopie. Zbytek defaulty.

### PDF page size → media (G4)
Politika škálování (fit-to-printable-area vs 1:1), default media, neimplementovatelný okraj
(imageable area MF8030Cn), orientace. Multi-page → víc raster stránek. Mixed sizes.

### Paměť (P1)
A4@600dpi ≈ 4960×7016 px ≈ ~100 MB/stránku ARGB → **banding** (renderovat po pásech, hned
recyklovat bitmapu). Návrh banding pipeline, ne jen zmínka.

### Permissions self-contained appky (G5)
`INTERNET`; local-network dle targetSdk (Android 17/SDK37 = `ACCESS_LOCAL_NETWORK`, do SDK36
stačí INTERNET). **Zahodit** `com.termux.permission.RUN_COMMAND` z finální appky (jen ve scaffoldu).

### Build bundle (G6) → `scripts/build-bundle.sh` (TODO)
Reprodukovatelný postup: extrahovat .deb → `ldd` closure → seznam caepcm souborů pro MF8000C
(z R4 strace) → zdroj/verze statického `proot` → layout v `assets/` → **sha256 freeze** každé
zabalené binárky.

### Networking edge cases (P2)
DHCP změna IP (předtáhnout SNMP discovery z roadmapy), port 9100 single-connection/busy,
tiskárna uspaná (timeout), telefon na VPN/mobilních datech / jiné subsíti.

### Verifikace / debug
- **Golden reference stream** zachytit ve Fázi 1.5 (zdarma, neocenitelné pro diff).
- Kam jde `rastertoufr2` stderr, když APK nic nevytiskne → log capture strategie.
- `error_log` z CUPS scaffoldu při ladění.

---

## Síťová strana tiskárny (potvrzeno + ověřit)

- RAW **9100 one-way** je dokumentovaná rychlá cesta; UFR-II je self-delimiting → pravděpodobně OK.
- **Ověřit**: CUPS *backend* (ne filtr) může přidávat framing/PJL nebo dělat bidi přes `cnpkbidir`.
  Test: dump stdout filtru → `nc` na `:9100` mimo CUPS.
- **LPD 515 držet jako otestovaný fallback** (`lpd://192.168.0.62`) - starý Canon občas chce LPR.

---

## Drobné opravy konzistence (provedeno / TODO)

- ✅ PPD ověřeno výpisem balíku: `CNRCUPSMF8000CZK.ppd` (barva) / `CNRCUPSMF8000ZK.ppd` (mono).
  (Uzavírá starý TODO „ZK vs ZS".)
- ⚠️ Název ovladače = **`linux-UFRII-drv-v630-m17n-07.tar.gz`** (deb `6.30-1.07`) - sjednotit všude (ne `-10`).
- ⚠️ `scripts/setup-debian.sh` přepsán (jeden .deb, bez gs, správný PPD a cesta).
- ⚠️ `docs/02` popisuje starší gs/OPVP generaci ovladače - viz [07](07-ovladac-rozbor.md) pro shipped v6.30.
