# UFR-II AndroidPrint

**A self-contained Android app that prints to Canon UFR-II network printers and scans from the Canon imageCLASS / i-SENSYS MF8030Cn directly from a phone. No root, no Termux, no PC, no adb.**

App label: **UFR-II** · Package: `cz.ufrii.print` · Version: **1.3.0** (versionCode 17) · Status: **Working**

Developed and tested on a Canon MF8030Cn with a Samsung Galaxy S24+ (Android 16).

---

## Features

- **Jetpack Compose app** with a two-block onboarding (*Phone*: enable the print service, allow or decline notifications, printer-status watching; *Printer*: add a printer or load it from a QR code). The *Start* button appears only when everything is done. In landscape the home screen has two panes, the other screens are centred (max 640 dp).
- **Printing, three ways**
  - the app's own print screen: colour / mono, copies, page range, paper source (read from the model's PPD `InputSlot`);
  - **system print service**: "UFR-II" appears in the Android print dialog of any app (Chrome, Gallery, PDF viewers…); *More options* in the dialog offers the paper source;
  - **share target**: share a PDF or image straight to *UFR-II*.
- **Two-sided printing**: automatic (`Duplex=DuplexNoTumble`) for models that have a duplex unit; otherwise **manual duplex** in two passes with a notification / in-app prompt between them. A one-sheet **calibration** per printer × tray learns how the sheet has to be re-inserted (the blank back of the sheet is reused for the next test number; instructions are numbered steps with a top-down picture, printer ↑ / you ↓).
- **Cancel**: *Zrušit* on the home screen, the print screen, the printer detail and in the notification. The proot runs with `--kill-on-exit`, so the Canon filter dies together with it.
- **Printer in Remote Scanner mode** holds print jobs back. The app never re-sends the job: it watches the SNMP page counter and printer status and, when the job obviously sits in the printer, asks you to end scanning on the panel, then waits until the page comes out (up to 10 minutes).
- **Network scanning** (glass and document feeder) via an Android-native build of SANE `scanimage`: glass preview with a draggable frame (auto frame from the lid brightness, snapping to A4/A5/A6/B5), PDF / JPEG / PNG output to Downloads, a chosen place, share, or print a copy.
- **Universal Canon UFR-II**: all 414 PPDs of the Canon driver are packed into the app (translations stripped); the model is matched automatically from what the printer reports (MFNP / SNMP) or picked by hand. Trays, colour mode, duplex unit and raster size follow the model.
- **IPv6**: a printer stores IPv4 and IPv6; print, scan and status use IPv4 and fall back to IPv6.
- **QR sharing** of a printer's whole setup (addresses, PPD, capabilities, duplex calibration, scanner options, defaults) as `ufrprint://import?d=…`; import by the Google code scanner, an opened link, or a link pasted from the clipboard.
- **Printer status**: toner levels, paper, cover / jam alerts via SNMP; optional watching with notifications (every 15 min on Wi-Fi). Printer detail also has a test print, scanner test, re-detect, driver picker, calibration and QR sharing.
- **Job history**, light / dark theme, Czech and English strings.
- **No external dependencies at runtime**: the APK bundles a trimmed Debian arm64 root filesystem (proot + Canon UFR-II driver + glibc) and unpacks it on first launch. A fresh install has **no pre-filled printer**.

## How It Works

Printing:

```
PDF / image
  → Android PdfRenderer → bitmap (600 dpi RGB, banded)
  → CUPS Raster v3 (application/vnd.cups-raster)
  → proot (arm64, bundled in jniLibs, --kill-on-exit)
  → rastertoufr2  (Canon UFR-II CUPS filter, arm64) with the model's PPD + options
  → UFR-II byte stream
  → TCP socket :9100
  → printer
```

The app renders each page with `PdfRenderer`, writes a conforming CUPS Raster stream and passes it to the real Canon `rastertoufr2` filter running inside a minimal proot Debian environment. The PPD of the printer's model and the options (`InputSlot`, `CNColorMode`, `Duplex`) are chosen per job. The filter output goes straight to the printer over raw TCP port 9100. No Ghostscript, no cupsd, no server process.

The proot binaries (`libproot.so`, `libproot_loader.so`, `libproot_loader32.so`) are bundled under `app/src/main/jniLibs/arm64-v8a/`. Android extracts them to `nativeLibraryDir` at install time with execute permission (`android:extractNativeLibs="true"` + `useLegacyPackaging = true`), bypassing the W^X exec restriction that applies to `filesDir`.

Scanning:

```
Remote Scanner on the printer panel (SCAN → Remote Scanner → OK)
  → libscanimage.so  (SANE scanimage, Android NDK build, bionic, run directly — no proot)
  → MFNP protocol, UDP/TCP 8610 (unicast only)
  → printer scanner (glass or feeder)
  → PNM pages → PDF / JPEG / PNG
```

`libscanimage.so` is sane-backends 1.4.0 with only the `pixma` backend and a small MF8000 patch. It is built against the system bionic libc, because the earlier glibc-static build was killed by Android's seccomp filter (exit 159, SIGSYS). The device name comes from `scanimage -L` (unicast only, `auto_detection=no`: a broadcast discovery wedges the scanner until the printer is power-cycled); options and glass size come from `scanimage -A`.

Details: [docs/22-v1.3-funkce-a-architektura.md](docs/22-v1.3-funkce-a-architektura.md) (Czech).

### Key source files

| File | Role |
|---|---|
| `print/CanonPrintService.kt` | `android.printservice.PrintService` implementation (system print dialog) |
| `print/CanonPrinterDiscoverySession.kt` | Printer list for the system dialog |
| `print/PrintOptionsActivity.kt` | "More options" in the system dialog (paper source) |
| `print/DiscoveryEngine.kt` | Discovery: mDNS (also IPv6 via `ServiceInfoCallback`) + TCP 9100 subnet scan |
| `print/Printer.kt` | `Printer` data class + `PrinterStore` (SharedPreferences JSON) |
| `print/PrinterAddress.kt` | IPv4 → IPv6 fallback used by print, scan and status |
| `print/PpdCatalog.kt` | PPD index, model matching, per-model trays / duplex / raster size |
| `print/PrintJobs.kt`, `print/PrintOptions.kt` | Print paths, options, `DuplexPlanner`, `DuplexCalibrations`, `DuplexGate` |
| `print/ManualDuplexJob.kt` | Two-pass manual duplex and the calibration pages |
| `print/CupsRasterWriter.kt` | PDF / image → CUPS Raster (bands, copies, rotation) |
| `print/ProotLauncher.kt`, `print/RootfsManager.kt` | Runs the Canon filter in proot; unpacks / versions the rootfs |
| `print/PrintCancel.kt` | `CancelToken` (cancel) and `StallRetry` (Remote Scanner hold-back) |
| `print/ScanJobs.kt` | `scanimage` wrapper, scan options, preview, PDF / JPEG / PNG output |
| `print/SettingsShare.kt` | QR / link export and import of a printer's setup |
| `print/status/Snmp.kt`, `PrinterProbe.kt` | SNMP client, capabilities and status; MFNP identity |
| `print/StatusWorker.kt`, `Notifications.kt`, `JobHistory.kt` | Status watching, notifications, history |
| `print/ui/` | Compose UI: `UfrApp`, `AppViewModel`, `screens/*` (Welcome, Home, Print, Scan, Printers, detail, Settings…) |

### Documentation

| Doc | Contents |
|---|---|
| [docs/14-uzivatelsky-navod.md](docs/14-uzivatelsky-navod.md) | User guide (Czech, for non-technical users): install, add the printer, print, two-sided, scan, problems |
| [docs/22-v1.3-funkce-a-architektura.md](docs/22-v1.3-funkce-a-architektura.md) | v1.2–v1.3 features and architecture: PPD selection, duplex math, Remote Scanner, scanning, IPv6, QR format, limits |
| [docs/12-jak-sestavit-apk.md](docs/12-jak-sestavit-apk.md) | Step-by-step build guide (proot, rootfs bundle, PPD pack, scanimage, Gradle, release signing, troubleshooting) |
| [docs/20-skenovani-navrh.md](docs/20-skenovani-navrh.md) | Network scan verification: MFNP protocol, SANE patch, gotchas |
| [docs/21-stav-a-schopnosti-tiskarny.md](docs/21-stav-a-schopnosti-tiskarny.md) | Printer capabilities and status over SNMP (measured OIDs, alerts) |
| [docs/04-reseni-architektura.md](docs/04-reseni-architektura.md) | Original architecture: why self-contained, bundle contents, risk analysis |
| [docs/15-phase4-printservice.md](docs/15-phase4-printservice.md) | PrintService implementation details and key technical findings |
| [docs/13-phase3-vysledky.md](docs/13-phase3-vysledky.md) | Phase 3 results: 8 bugs fixed, debugging methodology, final pipeline |

---

## ⚠️ Canon Driver NOT Included

The Canon UFR-II Linux driver is **Canon's proprietary software and is intentionally not part of this repository.** Neither the driver archive, nor the generated rootfs, nor the PPD pack (`ppd.zip`, `ppd-index.json`), nor any pre-built APK are committed here; they are `.gitignore`d.

**To build a working APK you must obtain the driver yourself:**

1. Download the **"Linux UFR II / UFRII LT Printer Driver" version 6.30** (archive `linux-UFRII-drv-v630-m17n-07.tar.gz`). **Direct download:** https://pdisp01.c-wss.com/gdl/WWUFORedirectTarget.do?id=MDEwMDAwNzY1ODQ4&cmp=ABX&lang=EN (or find it via [canon-europe.com](https://www.canon-europe.com) → your model → Drivers → Linux).
2. Place that archive in the project root and extract it there (`tar -xzf linux-UFRII-drv-v630-m17n-07.tar.gz`).
3. Run `scripts/build-bundle.sh`. It extracts the arm64 driver, builds the PPD pack, trims the rootfs and assembles `app/src/main/assets/rootfs.tar.gz`.

**This project is an independent integration and is not affiliated with or endorsed by Canon Inc. No Canon software is redistributed here. An APK built from this project contains Canon binaries: use it for personal / family sideload only and do not distribute it.**

---

## Build

Full guide: [docs/12-jak-sestavit-apk.md](docs/12-jak-sestavit-apk.md).

### Prerequisites

- Linux or WSL2 Ubuntu 24.04 (amd64)
- JDK 17 (`openjdk-17-jdk`)
- Android SDK with platform 35 and build-tools 35 (`sdkmanager "platforms;android-35" "build-tools;35.0.0"`)
- Android NDK r27 (`sdkmanager "ndk;27.2.12479018"`)
- `patchelf`, `dpkg-deb`, `wget`, `curl`, `python3` (`sudo apt install patchelf dpkg wget curl python3`)

### Step 1: Obtain the Canon driver

As described above: `linux-UFRII-drv-v630-m17n-07.tar.gz` in the project root, extracted.

### Step 2: Build the rootfs bundle and the PPD pack

```bash
ANDROID_NDK=~/android-sdk/ndk/27.2.12479018 \
./scripts/build-bundle.sh
```

The script extracts the arm64 driver `.deb`, downloads Debian bookworm arm64 runtime libraries (checked against SHA256 before unpacking), builds a `libcupsstub.so` shim, then calls

- `scripts/build-ppd-pack.py`: packs all 414 PPDs (translations stripped, about 2 MB) into `assets/ppd.zip` and writes `assets/ppd-index.json` (model names, trays, duplex, colour modes, printable area);
- `scripts/trim-rootfs.sh`: removes what the filter does not read (other models' PPDs, docs, locales, the GUI),

and assembles `app/src/main/assets/rootfs.tar.gz` (about 43 MB) plus `rootfs.version`.

### Step 3: Build the scanner binary (Android NDK)

```bash
NDK=~/android-sdk/ndk/27.2.12479018 ./scripts/build-scanimage-android.sh
```

Builds sane-backends 1.4.0 (`pixma` backend + MF8000 patch from `scripts/sane-patch-mf8000.py`, IPv6 enabled) and IJG libjpeg 9f statically against the system bionic libc, and writes `app/src/main/jniLibs/arm64-v8a/libscanimage.so` (about 490 kB). It reuses cached source tarballs if present, otherwise downloads them (SHA256-checked). `scripts/build-scanimage-arm64.sh` is the older glibc-static variant; it does **not** run on Android (seccomp) and is kept only for reference.

### Step 4: Build the APK

> **Gotcha: spaces in the project path.** Gradle fails if the path contains spaces. Symlink the project to a space-free path first:
> ```bash
> ln -sf "/path/to/UFR-II_AndroidPrint" /tmp/ufrbuild
> cd /tmp/ufrbuild
> ```

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
ANDROID_HOME=~/android-sdk \
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk` (larger than release: it also carries the developer probes that live only in `app/src/debug`).

#### Release build (the one to install)

The release build is minified (R8) and **must be signed**. Create a keystore once (keep it **outside** the repo) and put its settings in `local.properties` (gitignored) or environment variables:

```bash
keytool -genkeypair -v -keystore ~/canonprint-release.jks -alias canonprint \
        -keyalg RSA -keysize 4096 -validity 10000
```

`local.properties`:
```properties
RELEASE_STORE_FILE=/home/you/canonprint-release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=canonprint
RELEASE_KEY_PASSWORD=...
```

Then `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (about 50 MB). Without these keys the release build stays unsigned (the debug build is unaffected). Never commit the keystore or its passwords.

### proot binaries

The prebuilt proot `.so` files under `app/src/main/jniLibs/arm64-v8a/` were compiled from [green-green-avk/build-proot-android](https://github.com/green-green-avk/build-proot-android) with NDK r27, targeting `aarch64-linux-android29`, with `-Wl,-z,max-page-size=16384` for 16 KB page alignment. See [docs/12-jak-sestavit-apk.md](docs/12-jak-sestavit-apk.md) if you need to rebuild them.

---

## Install & Use

**Requirements:** arm64 device, Android 10+ (minSdk 29). Tested on Samsung Galaxy S24+ / Android 16.

1. **Sideload** the release APK (Files app, USB, or `adb install`). Allow installs from unknown sources for the app you open it with.
2. **Open *UFR-II*; the onboarding has two blocks.**
   - *Phone*: enable the print service (Settings → Printing → UFR-II → ON; the button opens it), allow or decline notifications (they carry the "turn the paper" prompt for manual duplex and printer alerts), choose whether to watch the printer status.
   - *Printer*: **add a printer** (search the network or type the IP) **or load it from a QR code** shown by another phone (printer detail → *Sdílet nastavení (QR)*; import takes the addresses, model, calibration and scanner options at once).
   - Tap **Start** when both blocks are done.
3. **Print**: from any app → Print → *UFR-II* printer → Print (*More options* = paper source, *Two-sided* = duplex); or share a file to *UFR-II*; or *Vybrat soubor* in the app.
4. **Scan**: on the printer panel press SCAN → Remote Scanner → OK (once), then *Skenovat* in the app.
5. **Cancel** any running job with *Zrušit*. If a card or notification says the print waits in the printer, end scanning on the panel — the job prints right after; do not send it again.

> After reinstalling the APK, toggle the print service OFF and back ON to rebind the service instance.

The app ships with no printer. The examples in the docs use `192.168.0.62` as a generic printer address; use your printer's own.

---

## Status

**v1.3.0 (versionCode 17): working.** Printing (colour / mono, copies, page range, paper source, automatic and manual two-sided with calibration, cancel, Remote Scanner hold-back handling), network scanning (glass, feeder, preview), IPv4 / IPv6, QR setup sharing and printer status are implemented and were developed against a Canon MF8030Cn with a Samsung Galaxy S24+.

Known limits:

- **Other printer models are unverified on real hardware.** The filter accepted the 600 dpi RGB raster for all 414 PPDs in a desktop test, and the PPD data (trays, duplex, colour) is read from the PPDs, but printing on other devices has not been tried.
- **Scanning works only for models in SANE's `pixma` list** (plus the MF8000 patch). The scanner cannot be detected over SNMP; use *Test skeneru* in the printer detail.
- **Remote Scanner mode cannot be detected in advance** (SNMP shows identical values), so a held-back print job is recognised only after sending.
- Low-toner levels and the jam alert were not reproduced safely; the document feeder is detected from the scanner options.
- The release APK bundles Canon binaries: personal / family sideload only.

History and decisions: [PROJECT-LOG.md](PROJECT-LOG.md).

---

## Credits & Licensing

- **proot**: GPL. Binaries built from [green-green-avk/build-proot-android](https://github.com/green-green-avk/build-proot-android).
- **SANE (sane-backends 1.4.0, `pixma` backend)**: GPL-2.0+ with the SANE linking exception. Built from source by `scripts/build-scanimage-android.sh` (the MF8000 patch is `scripts/sane-patch-mf8000.py`); no SANE binaries are committed.
- **IJG libjpeg 9f**: Independent JPEG Group licence, linked statically into `scanimage`.
- **zxing** (QR code generation): Apache-2.0.
- **Google code scanner** (QR reading): Google Play services ML Kit module, downloaded by Play services on first use.
- **Canon UFR-II driver**: © Canon Inc., proprietary. Not included in this repository. Bundling the driver into an APK is for personal / sideload use only (the Canon EULA prohibits redistribution); do not redistribute APKs containing Canon binaries, and do not commit the driver, the PPDs or extracted blobs to a public repository.
- **App wrapper and integration code**: © LivingLegendcz.

---
---

# UFR-II AndroidPrint

**Samostatná Android aplikace pro tisk na síťové tiskárny Canon UFR-II a skenování z Canon imageCLASS / i-SENSYS MF8030Cn přímo z telefonu. Bez rootu, bez Termuxu, bez PC, bez adb.**

Název aplikace: **UFR-II** · Package: `cz.ufrii.print` · Verze: **1.3.0** (versionCode 17) · Stav: **Funkční**

Vyvíjeno a ověřováno na Canon MF8030Cn se Samsung Galaxy S24+ (Android 16).

---

## Funkce

- **Aplikace v Jetpack Compose** s úvodním průvodcem o dvou blocích (*Telefon*: tisková služba, povolit nebo odmítnout upozornění, hlídání stavu tiskárny; *Tiskárna*: přidat tiskárnu nebo ji načíst z QR kódu). Tlačítko *Začít* se objeví, až je vše hotovo. Na šířku má domovská obrazovka dva panely, ostatní obrazovky jsou vystředěné (max. 640 dp).
- **Tisk třemi cestami**
  - vlastní tisková obrazovka aplikace: barva / černobíle, kopie, rozsah stránek, zdroj papíru (z `InputSlot` v PPD modelu);
  - **systémová tisková služba**: „UFR-II“ je v tiskovém dialogu Androidu v každé aplikaci (Chrome, Galerie, prohlížeče PDF…); *Další možnosti* v dialogu nabízí zdroj papíru;
  - **sdílení**: PDF nebo obrázek lze sdílet přímo do *UFR-II*.
- **Oboustranný tisk**: automatický (`Duplex=DuplexNoTumble`) u modelů s duplexní jednotkou; jinak **ruční duplex** ve dvou průchodech s upozorněním / výzvou v aplikaci mezi nimi. **Kalibrace** na jednom listu pro každou kombinaci tiskárna × zásobník zjistí, jak list vložit zpět (čistý rub listu se použije pro další zkušební číslo; návod je v očíslovaných krocích s pohledem shora, tiskárna ↑ / vy ↓).
- **Zrušení**: *Zrušit* na domovské obrazovce, obrazovce Tisk, v detailu tiskárny i v upozornění. Proot běží s `--kill-on-exit`, takže spolu s ním zaniká i Canon filtr.
- **Tiskárna v režimu Remote Scanner** drží tiskové úlohy. Aplikace úlohu nikdy neposílá znovu: sleduje SNMP počítadlo stran a stav tiskárny a když úloha zjevně čeká v tiskárně, vyzve k ukončení skenování na panelu a čeká, až stránka vyjede (nejvýše 10 minut).
- **Síťové skenování** (sklo i podavač) přes Android-nativní build SANE `scanimage`: náhled skla s tažitelným rámečkem (automatický rámeček podle jasu víka, přichycení na A4/A5/A6/B5), výstup PDF / JPEG / PNG do Stažených, do zvoleného místa, sdílení, nebo vytištění kopie.
- **Univerzální Canon UFR-II**: všech 414 PPD z ovladače Canon je zabaleno v aplikaci (bez překladů); model se přiřadí automaticky podle hlášení tiskárny (MFNP / SNMP) nebo se vybere ručně. Zásobníky, barevný režim, duplexní jednotka a velikost rastru se řídí modelem.
- **IPv6**: tiskárna si pamatuje IPv4 i IPv6; tisk, sken i stav používají IPv4 a při neodpovědi přejdou na IPv6.
- **QR sdílení** celého nastavení tiskárny (adresy, PPD, schopnosti, kalibrace duplexu, možnosti skeneru, výchozí volby) jako `ufrprint://import?d=…`; načtení Google code scannerem, otevřeným odkazem nebo odkazem vloženým ze schránky.
- **Stav tiskárny**: tonery, papír, otevřený kryt / zaseknutý papír přes SNMP; volitelné hlídání s upozorněním (každých 15 min na Wi-Fi). Detail tiskárny má také testovací tisk, test skeneru, opětovné zjištění schopností, výběr ovladače, kalibraci a QR sdílení.
- **Historie úloh**, světlý / tmavý vzhled, české a anglické texty.
- **Žádné závislosti za běhu**: APK obsahuje ořezaný Debian arm64 root filesystem (proot + Canon UFR-II ovladač + glibc) a při prvním spuštění ho rozbalí. Čerstvá instalace **nemá žádnou předvyplněnou tiskárnu**.

## Jak to funguje

Tisk:

```
PDF / obrázek
  → Android PdfRenderer → bitmapa (600 dpi RGB, po pásech)
  → CUPS Raster v3 (application/vnd.cups-raster)
  → proot (arm64, zabalený v jniLibs, --kill-on-exit)
  → rastertoufr2  (Canon UFR-II CUPS filtr, arm64) s PPD a volbami modelu
  → UFR-II proud bajtů
  → TCP socket :9100
  → tiskárna
```

Aplikace renderuje každou stránku přes `PdfRenderer`, zapíše CUPS Raster stream a předá ho skutečnému Canon filtru `rastertoufr2` běžícímu uvnitř minimálního proot Debian prostředí. PPD modelu tiskárny a volby (`InputSlot`, `CNColorMode`, `Duplex`) se volí pro každou úlohu zvlášť. Výstup filtru proudí přímo na tiskárnu přes raw TCP port 9100. Žádný Ghostscript, žádný cupsd, žádný serverový proces.

Binárky proot (`libproot.so`, `libproot_loader.so`, `libproot_loader32.so`) jsou zabaleny v `app/src/main/jniLibs/arm64-v8a/`. Android je při instalaci rozbalí do `nativeLibraryDir` s právem spuštění (`android:extractNativeLibs="true"` + `useLegacyPackaging = true`), čímž se obchází W^X omezení platné pro `filesDir`.

Skenování:

```
Remote Scanner na panelu tiskárny (SCAN → Remote Scanner → OK)
  → libscanimage.so  (SANE scanimage, build přes Android NDK, bionic, spouští se přímo — bez proot)
  → protokol MFNP, UDP/TCP 8610 (jen unicast)
  → skener tiskárny (sklo nebo podavač)
  → stránky PNM → PDF / JPEG / PNG
```

`libscanimage.so` je sane-backends 1.4.0 jen s backendem `pixma` a malým patchem MF8000. Je sestavený proti systémovému bionic libc, protože dřívější glibc-static build zabíjel seccomp filtr Androidu (exit 159, SIGSYS). Název zařízení se bere z `scanimage -L` (jen unicast, `auto_detection=no`: broadcast discovery skener zasekne až do restartu tiskárny); možnosti a velikost skla z `scanimage -A`.

Podrobnosti: [docs/22-v1.3-funkce-a-architektura.md](docs/22-v1.3-funkce-a-architektura.md).

### Klíčové zdrojové soubory

| Soubor | Role |
|---|---|
| `print/CanonPrintService.kt` | Implementace `android.printservice.PrintService` (systémový tiskový dialog) |
| `print/CanonPrinterDiscoverySession.kt` | Seznam tiskáren pro systémový dialog |
| `print/PrintOptionsActivity.kt` | „Další možnosti“ v systémovém dialogu (zdroj papíru) |
| `print/DiscoveryEngine.kt` | Hledání: mDNS (i IPv6 přes `ServiceInfoCallback`) + TCP sken portu 9100 v podsíti |
| `print/Printer.kt` | Datová třída `Printer` + `PrinterStore` (SharedPreferences JSON) |
| `print/PrinterAddress.kt` | Přechod IPv4 → IPv6 pro tisk, sken i stav |
| `print/PpdCatalog.kt` | Index PPD, přiřazení modelu, zásobníky / duplex / velikost rastru podle modelu |
| `print/PrintJobs.kt`, `print/PrintOptions.kt` | Tiskové cesty, volby, `DuplexPlanner`, `DuplexCalibrations`, `DuplexGate` |
| `print/ManualDuplexJob.kt` | Ruční duplex ve dvou průchodech a kalibrační stránky |
| `print/CupsRasterWriter.kt` | PDF / obrázek → CUPS Raster (pásy, kopie, rotace) |
| `print/ProotLauncher.kt`, `print/RootfsManager.kt` | Spuštění Canon filtru v proot; rozbalení / verzování rootfs |
| `print/PrintCancel.kt` | `CancelToken` (zrušení) a `StallRetry` (držení úlohy v režimu Remote Scanner) |
| `print/ScanJobs.kt` | Obálka `scanimage`, možnosti skenu, náhled, výstup PDF / JPEG / PNG |
| `print/SettingsShare.kt` | Export a import nastavení tiskárny přes QR / odkaz |
| `print/status/Snmp.kt`, `PrinterProbe.kt` | SNMP klient, schopnosti a stav; identita přes MFNP |
| `print/StatusWorker.kt`, `Notifications.kt`, `JobHistory.kt` | Hlídání stavu, upozornění, historie |
| `print/ui/` | Compose UI: `UfrApp`, `AppViewModel`, `screens/*` (Welcome, Home, Print, Scan, Printers, detail, Settings…) |

### Dokumentace

| Dokument | Obsah |
|---|---|
| [docs/14-uzivatelsky-navod.md](docs/14-uzivatelsky-navod.md) | Uživatelský návod (pro laiky): instalace, přidání tiskárny, tisk, oboustranný tisk, sken, potíže |
| [docs/22-v1.3-funkce-a-architektura.md](docs/22-v1.3-funkce-a-architektura.md) | Funkce a architektura v1.2–v1.3: výběr PPD, matematika duplexu, Remote Scanner, sken, IPv6, formát QR, omezení |
| [docs/12-jak-sestavit-apk.md](docs/12-jak-sestavit-apk.md) | Build guide krok za krokem (proot, rootfs bundle, balík PPD, scanimage, Gradle, podpis release, řešení potíží) |
| [docs/20-skenovani-navrh.md](docs/20-skenovani-navrh.md) | Ověření síťového skenu: protokol MFNP, patch SANE, nástrahy |
| [docs/21-stav-a-schopnosti-tiskarny.md](docs/21-stav-a-schopnosti-tiskarny.md) | Schopnosti a stav tiskárny přes SNMP (naměřené OID, alerty) |
| [docs/04-reseni-architektura.md](docs/04-reseni-architektura.md) | Původní architektura: proč self-contained, obsah bundle, analýza rizik |
| [docs/15-phase4-printservice.md](docs/15-phase4-printservice.md) | Implementace PrintService a klíčové technické nálezy |
| [docs/13-phase3-vysledky.md](docs/13-phase3-vysledky.md) | Výsledky Phase 3: 8 opravených chyb, debugovací metodika, finální pipeline |

---

## ⚠️ Canon ovladač NENÍ součástí repozitáře

Canon UFR-II ovladač pro Linux je **proprietární software Canon Inc. a záměrně není součástí tohoto repozitáře.** Archiv ovladače, vygenerovaný rootfs, balík PPD (`ppd.zip`, `ppd-index.json`) ani žádný sestavený APK zde nejsou uloženy; jsou v `.gitignore`.

**Aby bylo možné sestavit funkční APK, je nutné ovladač získat samostatně:**

1. Stáhněte **„Linux UFR II / UFRII LT Printer Driver“ verze 6.30** (archiv `linux-UFRII-drv-v630-m17n-07.tar.gz`). **Přímé stažení:** https://pdisp01.c-wss.com/gdl/WWUFORedirectTarget.do?id=MDEwMDAwNzY1ODQ4&cmp=ABX&lang=EN (nebo přes [canon-europe.com](https://www.canon-europe.com) → váš model → Ovladače → Linux).
2. Umístěte archiv do kořenového adresáře projektu a rozbalte ho tam (`tar -xzf linux-UFRII-drv-v630-m17n-07.tar.gz`).
3. Spusťte `scripts/build-bundle.sh`. Skript extrahuje arm64 ovladač, sestaví balík PPD, ořeže rootfs a vytvoří `app/src/main/assets/rootfs.tar.gz`.

**Tento projekt je nezávislá integrace a není nijak spojen se společností Canon Inc. ani jí podporován. Žádný software Canon zde není redistribuován. APK sestavené z tohoto projektu obsahuje binárky Canon: používejte ho jen pro osobní / rodinný sideload a nešiřte ho.**

---

## Sestavení APK

Podrobný návod: [docs/12-jak-sestavit-apk.md](docs/12-jak-sestavit-apk.md).

### Prerekvizity

- Linux nebo WSL2 Ubuntu 24.04 (amd64)
- JDK 17 (`openjdk-17-jdk`)
- Android SDK s platformou 35 a build-tools 35 (`sdkmanager "platforms;android-35" "build-tools;35.0.0"`)
- Android NDK r27 (`sdkmanager "ndk;27.2.12479018"`)
- `patchelf`, `dpkg-deb`, `wget`, `curl`, `python3` (`sudo apt install patchelf dpkg wget curl python3`)

### Krok 1: Získání Canon ovladače

Viz výše: `linux-UFRII-drv-v630-m17n-07.tar.gz` v kořeni projektu, rozbalený.

### Krok 2: Sestavení rootfs bundle a balíku PPD

```bash
ANDROID_NDK=~/android-sdk/ndk/27.2.12479018 \
./scripts/build-bundle.sh
```

Skript extrahuje arm64 ovladač `.deb`, stáhne arm64 runtime knihovny z Debian bookworm (před rozbalením se ověřuje SHA256), zkompiluje `libcupsstub.so` shim a potom zavolá

- `scripts/build-ppd-pack.py`: zabalí všech 414 PPD (bez překladů, asi 2 MB) do `assets/ppd.zip` a zapíše `assets/ppd-index.json` (názvy modelů, zásobníky, duplex, barevné režimy, tisknutelná oblast);
- `scripts/trim-rootfs.sh`: odstraní, co filtr nečte (PPD jiných modelů, dokumentaci, lokalizace, GUI),

a sestaví `app/src/main/assets/rootfs.tar.gz` (asi 43 MB) a `rootfs.version`.

### Krok 3: Sestavení skenovací binárky (Android NDK)

```bash
NDK=~/android-sdk/ndk/27.2.12479018 ./scripts/build-scanimage-android.sh
```

Staticky sestaví sane-backends 1.4.0 (backend `pixma` + patch MF8000 ze `scripts/sane-patch-mf8000.py`, IPv6 zapnuto) a IJG libjpeg 9f proti systémovému bionic libc a zapíše `app/src/main/jniLibs/arm64-v8a/libscanimage.so` (asi 490 kB). Použije uložené zdrojové tarbally, pokud existují, jinak je stáhne (s kontrolou SHA256). `scripts/build-scanimage-arm64.sh` je starší glibc-static varianta; na Androidu **nefunguje** (seccomp) a zůstává jen pro referenci.

### Krok 4: Sestavení APK

> **Pozor: mezery v cestě projektu.** Gradle selže, pokud cesta obsahuje mezery. Nejdříve vytvořte symlink na cestu bez mezer:
> ```bash
> ln -sf "/path/to/UFR-II_AndroidPrint" /tmp/ufrbuild
> cd /tmp/ufrbuild
> ```

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
ANDROID_HOME=~/android-sdk \
./gradlew assembleDebug
```

Výstup: `app/build/outputs/apk/debug/app-debug.apk` (větší než release: obsahuje i vývojářské sondy, které jsou jen v `app/src/debug`).

#### Release build (ten se instaluje)

Release build je minifikovaný (R8) a **musí být podepsaný**. Jednorázově vytvořte keystore (držte ho **mimo** repo) a vložte jeho nastavení do `local.properties` (gitignored) nebo do proměnných prostředí:

```bash
keytool -genkeypair -v -keystore ~/canonprint-release.jks -alias canonprint \
        -keyalg RSA -keysize 4096 -validity 10000
```

`local.properties`:
```properties
RELEASE_STORE_FILE=/home/vy/canonprint-release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=canonprint
RELEASE_KEY_PASSWORD=...
```

Pak `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (asi 50 MB). Bez těchto klíčů zůstane release build nepodepsaný (debug build to neovlivní). Keystore ani hesla nikdy necommitujte.

### Binárky proot

Předkompilované proot `.so` soubory v `app/src/main/jniLibs/arm64-v8a/` byly sestaveny z [green-green-avk/build-proot-android](https://github.com/green-green-avk/build-proot-android) s NDK r27, cíl `aarch64-linux-android29`, s příznakem `-Wl,-z,max-page-size=16384` pro 16 KB zarovnání stránek. Pokud je potřeba je znovu sestavit, viz [docs/12-jak-sestavit-apk.md](docs/12-jak-sestavit-apk.md).

---

## Instalace a používání

**Požadavky:** arm64 zařízení, Android 10+ (minSdk 29). Ověřeno na Samsung Galaxy S24+ / Android 16.

1. **Nainstalujte** release APK sideloadem (správce souborů, USB nebo `adb install`). Povolte instalaci z neznámých zdrojů pro aplikaci, kterou APK otevíráte.
2. **Otevřete *UFR-II*; průvodce má dva bloky.**
   - *Telefon*: zapněte tiskovou službu (Nastavení → Tisk → UFR-II → ON; tlačítko ji otevře), povolte nebo odmítněte upozornění (nesou výzvu „otočte papír“ při ručním duplexu a hlášení tiskárny), zvolte hlídání stavu tiskárny.
   - *Tiskárna*: **přidejte tiskárnu** (hledání v síti nebo zadání IP) **nebo ji načtěte z QR kódu** z jiného telefonu (detail tiskárny → *Sdílet nastavení (QR)*; import převezme adresy, model, kalibraci i možnosti skeneru najednou).
   - Až jsou oba bloky hotové, klepněte na **Začít**.
3. **Tisk**: z libovolné aplikace Tisk → tiskárna *UFR-II* → Tisk (*Další možnosti* = zdroj papíru, *Oboustranně* = duplex); nebo sdílejte soubor do *UFR-II*; nebo *Vybrat soubor* v aplikaci.
4. **Sken**: na panelu tiskárny stiskněte SCAN → Remote Scanner → OK (stačí jednou), pak v aplikaci *Skenovat*.
5. Běžící úlohu zrušíte tlačítkem *Zrušit*. Když karta nebo upozornění hlásí, že tisk čeká v tiskárně, ukončete na panelu skenování — úloha se vytiskne hned potom; neposílejte ji znovu.

> Po reinstalaci APK je nutné tiskovou službu přepnout OFF a zpět ON, aby se nová instance služby správně navázala.

Aplikace neobsahuje žádnou tiskárnu. Příklady v dokumentaci používají `192.168.0.62` jako obecnou adresu tiskárny; použijte adresu své tiskárny.

---

## Stav

**v1.3.0 (versionCode 17): funkční.** Tisk (barva / černobíle, kopie, rozsah stránek, zdroj papíru, automatický i ruční oboustranný tisk s kalibrací, zrušení, ošetření držení úlohy v režimu Remote Scanner), síťový sken (sklo, podavač, náhled), IPv4 / IPv6, QR sdílení nastavení a stav tiskárny jsou implementovány a vyvíjeny proti Canon MF8030Cn se Samsung Galaxy S24+.

Známá omezení:

- **Jiné modely tiskáren nejsou ověřeny na skutečném hardwaru.** Filtr přijal RGB raster 600 dpi u všech 414 PPD v testu na počítači a údaje z PPD (zásobníky, duplex, barva) se čtou z PPD, ale tisk na jiných zařízeních nebyl vyzkoušen.
- **Sken funguje jen pro modely v seznamu SANE `pixma`** (plus patch MF8000). Skener nelze zjistit přes SNMP; použijte *Test skeneru* v detailu tiskárny.
- **Režim Remote Scanner nelze zjistit předem** (SNMP ukazuje shodné hodnoty), držená úloha se pozná až po odeslání.
- Nízká hladina toneru a hlášení zaseknutého papíru nebyly bezpečně vyvolány; podavač dokumentů se pozná z možností skeneru.
- Release APK obsahuje binárky Canon: jen osobní / rodinný sideload.

Historie a rozhodnutí: [PROJECT-LOG.md](PROJECT-LOG.md).

---

## Poděkování a licence

- **proot**: GPL. Binárky sestaveny z [green-green-avk/build-proot-android](https://github.com/green-green-avk/build-proot-android).
- **SANE (sane-backends 1.4.0, backend `pixma`)**: GPL-2.0+ s výjimkou SANE pro linkování. Sestavuje se ze zdrojů skriptem `scripts/build-scanimage-android.sh` (patch MF8000 je `scripts/sane-patch-mf8000.py`); žádné binárky SANE nejsou commitnuté.
- **IJG libjpeg 9f**: licence Independent JPEG Group, staticky slinkováno do `scanimage`.
- **zxing** (generování QR kódů): Apache-2.0.
- **Google code scanner** (čtení QR): modul ML Kit ve službách Google Play, stahuje ho Play services při prvním použití.
- **Canon UFR-II ovladač**: © Canon Inc., proprietární. Není součástí tohoto repozitáře. Zabalení ovladače do APK je určeno pouze pro osobní / sideload použití (Canon EULA zakazuje redistribuci); nesdílejte APK s vloženými Canon binárkami veřejně a necommitujte ovladač, PPD ani extrahované soubory do veřejného repozitáře.
- **Obalový kód aplikace a integrace**: © LivingLegendcz.
