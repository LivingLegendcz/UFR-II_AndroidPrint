# Funkční review v1.0 — nálezy a roadmapa vylepšení

**Datum:** 2026-06-10
**Rozsah:** všech 9 Kotlin souborů včetně `CupsRasterWriter.kt` (jádro renderingu), manifest, capabilities
**Navazuje na:** [docs/16-security-audit.md](16-security-audit.md) (security), tento dokument řeší funkcionalitu

---

## Souhrn

Pipeline je solidní. Nalezeny 4 reálné funkční chyby (F1–F4, balík v1.2), 5 UX vylepšení (v1.3) a větší rozšíření pro v2.x. Nejzávažnější je **F1 — share target ignoruje uložené tiskárny**.

| # | Nález | Typ | Verze | Stav |
|---|---|---|---|---|
| F1 | Share target / TEST TISK ignoruje PrinterStore (vždy hardcoded IP) | 🔴 bug | v1.2 | ✅ opraveno (kód) |
| F2 | Počet kopií z print dialogu se ignoruje | 🔴 bug | v1.2 | ✅ opraveno (kód, ověřit filtr) |
| F3 | EXIF rotace fotek se nečte → fotky tisknou otočeně | 🔴 bug | v1.2 | ✅ opraveno (kód) |
| F4 | Landscape stránky PDF se letterboxují (~70 % velikosti) | 🟠 kvalita | v1.2 | ✅ opraveno (kód) |
| F5 | `printImage()` fyzicky neověřeno | ověření | v1.2 | ⏳ ověřit na zařízení (s F3) |
| U1–U5 | Fail-fast pre-check, progress, SEND_MULTIPLE, rotace logu, abort | UX | v1.3 | open |
| V1–V5 | Formáty papíru, 300 dpi draft, multi-model, manuální duplex, BJNP | feature | v2.x | open |

> **Stav 2026-06-26:** F1–F4 opraveny v kódu (vydání v1.1), čeká ověření na zařízení;
> F5 = vlastní ověření tisku obrázků. Detaily implementace viz [PROJECT-LOG.md](../PROJECT-LOG.md).

---

## v1.2 — funkční opravy

### F1 — Share target ignoruje PrinterStore 🔴

`MainActivity.doPrint()` volá `launcher.printPdf(pfd, colorMode)` bez IP → použijí se default parametry `192.168.0.62:9100` natvrdo v `ProotLauncher.printPdf/printImage`. Tiskárny přidané/upravené přes `AddPrinterActivity` respektuje **jen** systémový print dialog; share target a TEST TISK jedou vždy na hardcoded adresu.

**Oprava:** `PrinterStore.loadManual()` v MainActivity; při jedné tiskárně použít rovnou, při více jednoduchý výběr (dialog/spinner). Zároveň odstranit default hodnoty `printerIp`/`port` z `ProotLauncher` (vynutit explicitní předání → kompilátor odhalí všechna místa).

### F2 — Počet kopií se ignoruje 🔴

`rastertoufr2` dostává copies natvrdo `"1"` (argv #4); `printJob.info.copies` se nikde nečte. Uživatel zadá 3 kopie → vytiskne se 1.

**Oprava:** předat `printJob.info.copies.toString()` jako argv #4 — CUPS filtry copies argument standardně zpracovávají. Ověřit testem; fallback = zapsat stránky N× do raster streamu (SYNC jednou, header+pixely N×).

### F3 — EXIF rotace fotek 🔴

`BitmapFactory.decodeFile` EXIF orientation ignoruje → fotky z mobilu (typicky orientation=6/8) se vytisknou ležmo nebo vzhůru nohama.

**Oprava:** `androidx.exifinterface:exifinterface` → přečíst orientation z temp souboru, aplikovat `Matrix.postRotate()` na dekódovaný bitmap před `printImage()`.

### F4 — Landscape letterbox 🟠

`CupsRasterWriter.writePdf` dělá fit-to-portrait se zachováním poměru → landscape stránka (`page.width > page.height`) se zmenší na ~70 % a vytiskne s velkými okraji.

**Oprava:** detekce `page.width > page.height` → render matice s rotací 90° (`setRotate(90)` + přepočet scale/translate na prohozené rozměry). Stejně tak ve `writeImage` pro panoramatické obrázky.

### F5 — Ověřit `printImage()`

Implementováno, fyzicky netestováno (otevřené v PROJECT-LOG TODO). Otestovat společně s F3.

---

## v1.3 — UX vylepšení

- **U1 — Fail-fast pre-check tiskárny:** před spuštěním pipeline (extrakce + 96 MB raster) zkusit 2s TCP connect na cíl. Dnes při vypnuté tiskárně proběhne celý filtr a job umře až na socketu po desítkách sekund. Při neúspěchu rovnou `printJob.fail("Tiskárna nedostupná")`.
- **U2 — Progress v print dialogu:** `printJob.setProgress()` (API 24+) per stránka/band — row callback v `CupsRasterWriter` už existuje, jen propojit. Vícestránkové PDF dnes v dialogu mlčky visí.
- **U3 — `ACTION_SEND_MULTIPLE`:** sdílení více fotek najednou — přidat intent-filter + smyčku přes `EXTRA_STREAM` seznam (tisknout sekvenčně přes executor).
- **U4 — Rotace logu:** `printservice.log` se při překročení 256 KB celý smaže → místo toho ponechat posledních ~128 KB (zapsat tail do nového souboru).
- **U5 — Kooperativní abort:** zrušení běžícího jobu dnes nechá doběhnout celou stránku. `@Volatile abortRequested` flag kontrolovaný v band loopu feederu + zavření socketu → abort do ~1 bandu.

---

## v2.x — větší rozšíření

### V1 — Další formáty papíru (Letter, A5, B5)

Page header je dnes 1796 B binární kopie golden capture (`cups_page_header_a4.bin`) → blokuje vše kromě A4@600dpi portrait. Řešení: patchovat pole `cupsWidth`, `cupsHeight`, `cupsBytesPerLine`, `PageSize[2]`, `cupsPageSizeName` v template programově (offsety jsou v CUPS Raster spec pevné, little-endian). Odemyká V2 i V3.

### V2 — 300 dpi draft režim

4× menší raster (24 MB místo 96 MB/stránku) → výrazně rychlejší tisk běžných dokumentů. Druhá `Resolution` v capabilities + patch `HWResolution` v headeru (závisí na V1). Ověřit, že PPD/filtr 300 dpi akceptuje (`*Resolution` volby v PPD).

### V3 — Multi-model Canon UFR-II

Už v plánu (414 PPD v balíku). Vyžaduje: PPD per tiskárna místo hardcoded `CanonMF8030.ppd` v env (`Printer.model` → cesta k PPD v rootfs), bundlovat vybranou sadu PPD, výběr modelu v AddPrinterActivity. Navazuje na V1 (header dle PPD).

### V4 — Průvodce manuálním duplexem

MF8030Cn nemá automatický duplex. Workflow v appce:

1. Vytisknout **liché stránky** (1, 3, 5… — v pořadí dle počtu stránek tak, aby po otočení seděly).
2. Zobrazit **dialog s instrukcí, jak vrátit papír do zásobníku** — textově + jednoduchá ilustrace/piktogram:
   - který stranou nahoru (potištěnou nahoru/dolů),
   - kterou hranou napřed (horní hrana k tiskárně / od tiskárny),
   - zda stoh přerovnat (pořadí stránek).
3. Po potvrzení vytisknout **sudé stránky** (v odpovídajícím pořadí, typicky reverzně).

**Důležité — kalibrace orientace:** správná orientace vloženého papíru závisí na dráze papíru konkrétní tiskárny (kazeta vs. multi-purpose tray se u Canonů liší!). Neodhadovat — udělat jednorázový **kalibrační tisk**: testovací stránka s šipkou + textem „strana A", uživatel ji vrátí do zásobníku zvoleným způsobem, vytiskne se „strana B", a podle výsledku (správně/vzhůru nohama/na stejné straně) appka určí a **uloží orientační profil** per tiskárna (do `Printer`/PrinterStore). Instrukční dialog pak zobrazuje ověřený postup pro daný zásobník, ne generickou hádanku.

Pozn.: liché/sudé rozdělení se dá udělat čistě v `writePdf` (filtr stránek), žádný zásah do filtru/proot vrstvy.

### V5 — BJNP discovery: ověřit nebo odstranit

BJNP paket (prong C) je dle komentáře v kódu modelovaný podle cups-bjnp a **neověřený proti reálné MF8030Cn**. Jednorázově ověřit Wiresharkem; pokud nefunguje, prong vyhodit — mDNS + TCP scan pokrývají use-case.

---

## Co záměrně NEdělat

- Komprese raster streamu / výkonová optimalizace pipeline — 2,2 s/stránku je dostatečné.
- IPP fallback pro ne-Canon tiskárny — mimo scope projektu.
- Přepis threading modelu na coroutines — současný model (executor + 3 vlákna pipeline) funguje a je čitelný.
