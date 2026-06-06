# UFR-II AndroidPrint

**A self-contained Android app that prints to a Canon imageCLASS MF8030Cn (UFR-II, network) directly from a phone. No root, no Termux, no PC, no adb.**

App label: **UFR-II** · Package: `cz.ufrii.print` · Version: 1.0 · Status: **Working** (Phases 1 to 4 complete)

---

## Features

- **System PrintService**: registers "Canon MF8030Cn" as a printer in the Android OS print dialog. Any app that can print (Chrome, Gallery, PDF viewers, etc.) can send jobs to the printer without extra steps. Color and monochrome, A4.
- **Share target**: share a PDF or image directly to *UFR-II* without going through the print dialog.
- **Built-in test print**: a "TEST TISK" button in the main activity for quick verification.
- **Network discovery**: mDNS (`NsdManager`), TCP port 9100 subnet scan, and Canon BJNP UDP broadcast. Manual IP/port entry is also available.
- **No external dependencies at runtime**: the app bundles a complete Debian arm64 root filesystem (proot + Canon UFR-II driver + glibc stack) inside the APK and unpacks it on first launch.

## How It Works

```
PDF / image
  → Android PdfRenderer → bitmap (600 dpi, banded)
  → CUPS Raster v3 (application/vnd.cups-raster)
  → proot (arm64, bundled in jniLibs)
  → rastertoufr2  (Canon UFR-II CUPS filter, arm64)
  → UFR-II byte stream
  → TCP socket :9100
  → Canon i-SENSYS MF8030Cn
```

The app renders each page using `PdfRenderer`, writes a conforming CUPS Raster stream, and passes it to the real Canon `rastertoufr2` filter running inside a minimal proot Debian environment. The filter output is streamed directly to the printer over raw TCP port 9100. No Ghostscript, no cupsd, no server process.

The proot binaries (`libproot.so`, `libproot_loader.so`, `libproot_loader32.so`) are bundled under `app/src/main/jniLibs/arm64-v8a/`. Android extracts them to `nativeLibraryDir` at install time with execute permission (`android:extractNativeLibs="true"` + `useLegacyPackaging = true`), bypassing the W^X exec restriction that applies to `filesDir`.

### Key source files (Phase 4 PrintService layer)

| File | Role |
|---|---|
| `print/CanonPrintService.kt` | `android.printservice.PrintService` implementation |
| `print/CanonPrinterDiscoverySession.kt` | Manages the printer list; launches `DiscoveryEngine` |
| `print/DiscoveryEngine.kt` | Three parallel discovery branches (mDNS / TCP scan / BJNP) |
| `print/AddPrinterActivity.kt` | Manual printer management UI |
| `print/Printer.kt` | `Printer` data class + `PrinterStore` (SharedPreferences JSON) |

### Documentation

| Doc | Contents |
|---|---|
| [docs/04-reseni-architektura.md](docs/04-reseni-architektura.md) | Full architecture: why self-contained, bundle contents, risk analysis |
| [docs/12-jak-sestavit-apk.md](docs/12-jak-sestavit-apk.md) | Step-by-step build guide (proot, rootfs bundle, Gradle, troubleshooting) |
| [docs/15-phase4-printservice.md](docs/15-phase4-printservice.md) | PrintService implementation details and key technical findings |
| [docs/13-phase3-vysledky.md](docs/13-phase3-vysledky.md) | Phase 3 results: 8 bugs fixed, debugging methodology, final pipeline |
| [docs/14-uzivatelsky-navod.md](docs/14-uzivatelsky-navod.md) | User guide: installation, network setup, troubleshooting |

---

## ⚠️ Canon Driver NOT Included

The Canon UFR-II Linux driver is **Canon's proprietary software and is intentionally not part of this repository.** Neither the driver archive, nor the generated rootfs, nor any pre-built APK are committed here; they are `.gitignore`d.

**To build a working APK you must obtain the driver yourself:**

1. Download the **"Linux UFR II / UFRII LT Printer Driver" version 6.30** for the Canon MF8030Cn (archive `linux-UFRII-drv-v630-m17n-07.tar.gz`). **Direct download:** https://pdisp01.c-wss.com/gdl/WWUFORedirectTarget.do?id=MDEwMDAwNzY1ODQ4&cmp=ABX&lang=EN (or find it via [canon-europe.com](https://www.canon-europe.com) → your model → Drivers → Linux).
2. Place that archive in the project root.
3. Run `scripts/build-bundle.sh`. It extracts the arm64 driver and assembles `app/src/main/assets/rootfs.tar.gz`.

**This project is an independent integration and is not affiliated with or endorsed by Canon Inc. No Canon software is redistributed here.**

---

## Build

### Prerequisites

- Linux or WSL2 Ubuntu 24.04 (amd64)
- JDK 17 (`openjdk-17-jdk`)
- Android SDK with platform 35 and build-tools 35 (`sdkmanager "platforms;android-35" "build-tools;35.0.0"`)
- Android NDK r27 (`sdkmanager "ndk;27.2.12479018"`)
- `patchelf` (`sudo apt install patchelf`)

### Step 1: Obtain the Canon driver

As described above, place `linux-UFRII-drv-v630-m17n-07.tar.gz` in the project root.

### Step 2: Build the rootfs bundle

```bash
ANDROID_NDK=~/android-sdk/ndk/27.2.12479018 \
./scripts/build-bundle.sh
```

The script extracts the arm64 driver `.deb`, downloads Debian bookworm arm64 runtime libraries (glibc, libcups2, libcupsimage2, libjpeg62-turbo, libjbig0, libgcrypt20, libxml2, libicu72, liblzma5, and others), builds a `libcupsstub.so` shim, and assembles the final `app/src/main/assets/rootfs.tar.gz`.

### Step 3: Build the APK

> **Gotcha: spaces in the project path.** Gradle fails if the path contains spaces (e.g. `My Documents`). Symlink the project to a space-free path first:
> ```bash
> ln -sf "/path/to/UFR-II_AndroidPrint" /tmp/ufrbuild
> cd /tmp/ufrbuild
> ```

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
ANDROID_HOME=~/android-sdk \
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk` (~102 MB).

### proot binaries

The prebuilt proot `.so` files under `app/src/main/jniLibs/arm64-v8a/` were compiled from [green-green-avk/build-proot-android](https://github.com/green-green-avk/build-proot-android) with NDK r27, targeting `aarch64-linux-android29`, with `-Wl,-z,max-page-size=16384` for 16 KB page alignment. See [docs/12-jak-sestavit-apk.md](docs/12-jak-sestavit-apk.md) if you need to rebuild them.

---

## Install & Use

**Requirements:** arm64 device, Android 10+ (minSdk 29). Tested on Samsung Galaxy S24+ / Android 16.

1. **Sideload** the APK (USB install, Files app, or `adb install app-debug.apk`).
2. **Enable the print service:** Settings → Connections → More connection settings → Printing → UFR-II → toggle ON.
   The app's main screen has a button that opens this settings screen directly.
3. **Print from any app:** tap Print → select *Canon MF8030Cn (default)* → Print.
4. **Or share:** Share a PDF or image → UFR-II.

> After reinstalling the APK, toggle the print service OFF then back ON to rebind the service instance.

Default printer address is `192.168.0.62:9100`. Edit or add printers via the printer management screen (accessible from the app or from the print service settings).

---

## Status

**Working. v1.0, Phases 1 to 4 complete.**

Physical color and monochrome printing confirmed via both the system print dialog and the share target on Samsung Galaxy S24+ / Android 16. Round-trip time ~2.2 s per page.

Remaining polish items: configurable printer IP in a settings screen, APK size reduction (strip model-irrelevant caepcm color data), multi-model Canon UFR-II support.

---

## Credits & Licensing

- **proot**: GPL. Binaries built from [green-green-avk/build-proot-android](https://github.com/green-green-avk/build-proot-android).
- **Canon UFR-II driver**: © Canon Inc., proprietary. Not included in this repository. Bundling the driver into an APK is for personal/sideload use only (Canon EULA prohibits redistribution); do not redistribute APKs containing Canon binaries, and do not commit the driver or extracted blobs to a public repository.
- **App wrapper and integration code**: © LivingLegendcz.

---
---

# UFR-II AndroidPrint

**Samostatná Android aplikace pro tisk na Canon imageCLASS MF8030Cn (UFR-II, síťová tiskárna) přímo z telefonu. Bez rootu, bez Termuxu, bez PC, bez adb.**

Název aplikace: **UFR-II** · Package: `cz.ufrii.print` · Verze: 1.0 · Stav: **Funkční** (fáze 1 až 4 dokončeny)

---

## Funkce

- **Systémový PrintService**: registruje tiskárnu "Canon MF8030Cn" v nativním tiskovém dialogu Androidu. Každá aplikace, která umí tisknout (Chrome, Galerie, prohlížeče PDF, …), může odesílat tiskové úlohy bez dalšího nastavení. Barevný i černobílý tisk, A4.
- **Sdílení (share target)**: PDF nebo obrázek je možné sdílet přímo do *UFR-II* bez průchodu tiskovým dialogem.
- **Vestavěný testovací tisk**: tlačítko "TEST TISK" v hlavní aktivitě pro rychlé ověření funkčnosti.
- **Síťové vyhledávání tiskáren**: mDNS (`NsdManager`), TCP sken portu 9100 v podsíti a Canon BJNP UDP broadcast. K dispozici je také ruční zadání IP/portu.
- **Žádné závislosti za běhu**: aplikace v sobě obsahuje kompletní Debian arm64 root filesystem (proot + Canon UFR-II ovladač + glibc) zabalený do APK; při prvním spuštění ho rozbalí sama.

## Jak to funguje

```
PDF / obrázek
  → Android PdfRenderer → bitmapa (600 dpi, po pásech)
  → CUPS Raster v3 (application/vnd.cups-raster)
  → proot (arm64, zabalený v jniLibs)
  → rastertoufr2  (Canon UFR-II CUPS filtr, arm64)
  → UFR-II proud bajtů
  → TCP socket :9100
  → Canon i-SENSYS MF8030Cn
```

Aplikace renderuje každou stránku přes `PdfRenderer`, zapíše CUPS Raster stream a předá ho skutečnému Canon filtru `rastertoufr2` běžícímu uvnitř minimálního proot Debian prostředí. Výstup filtru proudí přímo na tiskárnu přes raw TCP port 9100. Žádný Ghostscript, žádný cupsd, žádný serverový proces.

Binárky proot (`libproot.so`, `libproot_loader.so`, `libproot_loader32.so`) jsou zabaleny v `app/src/main/jniLibs/arm64-v8a/`. Android je při instalaci rozbalí do `nativeLibraryDir` s právem spuštění (`android:extractNativeLibs="true"` + `useLegacyPackaging = true`), čímž se obchází W^X omezení platné pro `filesDir`.

### Klíčové zdrojové soubory (PrintService vrstva, Phase 4)

| Soubor | Role |
|---|---|
| `print/CanonPrintService.kt` | Implementace `android.printservice.PrintService` |
| `print/CanonPrinterDiscoverySession.kt` | Správa seznamu tiskáren; spouští `DiscoveryEngine` |
| `print/DiscoveryEngine.kt` | Tři paralelní větve discovery (mDNS / TCP sken / BJNP) |
| `print/AddPrinterActivity.kt` | UI pro ruční správu tiskáren |
| `print/Printer.kt` | Datová třída `Printer` + `PrinterStore` (SharedPreferences JSON) |

### Dokumentace

| Dokument | Obsah |
|---|---|
| [docs/04-reseni-architektura.md](docs/04-reseni-architektura.md) | Kompletní architektura: proč self-contained, obsah bundle, analýza rizik |
| [docs/12-jak-sestavit-apk.md](docs/12-jak-sestavit-apk.md) | Krok-za-krokem build guide (proot, rootfs bundle, Gradle, troubleshooting) |
| [docs/15-phase4-printservice.md](docs/15-phase4-printservice.md) | Implementace PrintService a klíčové technické nálezy |
| [docs/13-phase3-vysledky.md](docs/13-phase3-vysledky.md) | Výsledky Phase 3: 8 opravených chyb, debugovací metodika, finální pipeline |
| [docs/14-uzivatelsky-navod.md](docs/14-uzivatelsky-navod.md) | Uživatelský návod: instalace, síť, řešení potíží |

---

## ⚠️ Canon ovladač NENÍ součástí repozitáře

Canon UFR-II ovladač pro Linux je **proprietární software Canon Inc. a záměrně není součástí tohoto repozitáře.** Archiv ovladače, vygenerovaný rootfs ani žádný sestavený APK zde nejsou uloženy; jsou v `.gitignore`.

**Aby bylo možné sestavit funkční APK, je nutné ovladač získat samostatně:**

1. Stáhněte **"Linux UFR II / UFRII LT Printer Driver" verze 6.30** pro Canon MF8030Cn (archiv `linux-UFRII-drv-v630-m17n-07.tar.gz`). **Přímé stažení:** https://pdisp01.c-wss.com/gdl/WWUFORedirectTarget.do?id=MDEwMDAwNzY1ODQ4&cmp=ABX&lang=EN (nebo přes [canon-europe.com](https://www.canon-europe.com) → váš model → Ovladače → Linux).
2. Umístěte tento archiv do kořenového adresáře projektu.
3. Spusťte `scripts/build-bundle.sh`. Skript extrahuje arm64 ovladač a sestaví `app/src/main/assets/rootfs.tar.gz`.

**Tento projekt je nezávislá integrace a není nijak spojen se společností Canon Inc. ani jí podporován. Žádný software Canon zde není redistribuován.**

---

## Sestavení APK

### Prerekvizity

- Linux nebo WSL2 Ubuntu 24.04 (amd64)
- JDK 17 (`openjdk-17-jdk`)
- Android SDK s platformou 35 a build-tools 35 (`sdkmanager "platforms;android-35" "build-tools;35.0.0"`)
- Android NDK r27 (`sdkmanager "ndk;27.2.12479018"`)
- `patchelf` (`sudo apt install patchelf`)

### Krok 1: Získání Canon ovladače

Viz výše. Umístěte `linux-UFRII-drv-v630-m17n-07.tar.gz` do kořenového adresáře projektu.

### Krok 2: Sestavení rootfs bundle

```bash
ANDROID_NDK=~/android-sdk/ndk/27.2.12479018 \
./scripts/build-bundle.sh
```

Skript extrahuje arm64 ovladač `.deb`, stáhne arm64 runtime knihovny z Debian bookworm (glibc, libcups2, libcupsimage2, libjpeg62-turbo, libjbig0, libgcrypt20, libxml2, libicu72, liblzma5 a další), zkompiluje `libcupsstub.so` shim a sestaví výsledný `app/src/main/assets/rootfs.tar.gz`.

### Krok 3: Sestavení APK

> **Pozor: mezery v cestě projektu.** Gradle selže, pokud cesta obsahuje mezery (např. `My Documents`). Nejdříve vytvořte symlink na cestu bez mezer:
> ```bash
> ln -sf "/path/to/UFR-II_AndroidPrint" /tmp/ufrbuild
> cd /tmp/ufrbuild
> ```

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
ANDROID_HOME=~/android-sdk \
./gradlew assembleDebug
```

Výstup: `app/build/outputs/apk/debug/app-debug.apk` (~102 MB).

### Binárky proot

Předkompilované proot `.so` soubory v `app/src/main/jniLibs/arm64-v8a/` byly sestaveny z [green-green-avk/build-proot-android](https://github.com/green-green-avk/build-proot-android) s NDK r27, cíl `aarch64-linux-android29`, s příznakem `-Wl,-z,max-page-size=16384` pro 16 KB zarovnání stránek. Pokud je potřeba je znovu sestavit, viz [docs/12-jak-sestavit-apk.md](docs/12-jak-sestavit-apk.md).

---

## Instalace a používání

**Požadavky:** arm64 zařízení, Android 10+ (minSdk 29). Ověřeno na Samsung Galaxy S24+ / Android 16.

1. **Nainstalujte APK** sideloadem (USB, správce souborů nebo `adb install app-debug.apk`).
2. **Aktivujte tiskovou službu:** Nastavení → Připojení → Další nastavení připojení → Tisk → UFR-II → přepnout ON.
   Hlavní obrazovka aplikace obsahuje tlačítko, které tuto obrazovku nastavení přímo otevře.
3. **Tisk z libovolné aplikace:** Tisk → vybrat *Canon MF8030Cn (default)* → Tisk.
4. **Nebo sdílejte:** Sdílet PDF nebo obrázek → UFR-II.

> Po reinstalaci APK je nutné tiskovou službu přepnout OFF a zpět ON, aby se nová instance služby správně navázala.

Výchozí adresa tiskárny je `192.168.0.62:9100`. Tiskárny lze přidávat a upravovat přes správu tiskáren (přístupnou z aplikace nebo z nastavení tiskové služby).

---

## Stav

**Funkční. v1.0, fáze 1 až 4 dokončeny.**

Fyzický barevný i černobílý tisk ověřen prostřednictvím systémového tiskového dialogu i sdílení na Samsung Galaxy S24+ / Android 16. Doba zpracování ~2,2 s na stránku.

Zbývající vylepšení: konfigurovatelná IP tiskárny v nastavení aplikace, zmenšení APK (ořez caepcm barevných dat na relevantní modely), podpora více modelů Canon UFR-II.

---

## Poděkování a licence

- **proot**: GPL. Binárky sestaveny z [green-green-avk/build-proot-android](https://github.com/green-green-avk/build-proot-android).
- **Canon UFR-II ovladač**: © Canon Inc., proprietární. Není součástí tohoto repozitáře. Zabalení ovladače do APK je určeno pouze pro osobní/sideload použití (Canon EULA zakazuje redistribuci); nesdílejte APK s vloženými Canon binárkami veřejně a necommitujte ovladač ani extrahované soubory do veřejného repozitáře.
- **Obalový kód aplikace a integrace**: © LivingLegendcz.
