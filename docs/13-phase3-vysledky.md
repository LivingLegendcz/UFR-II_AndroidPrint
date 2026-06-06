# 13 - Phase 3: Self-contained APK - výsledky (2026-06-05)

## Status: ✅ GATE PASSED

**Datum:** 2026-06-05  
**Zařízení:** Samsung Galaxy S24+ (SM-S926B), Android 16 (SDK 36), page size 4 KB, arm64-v8a  
**APK:** `CanonPrint-0.3-phase3.apk` (~102 MB)  
**Podmínky:** žádný Termux, žádný root, žádný adb

---

## Milník: fyzický tisk z holé APK

Aplikace `CanonPrint-0.3-phase3.apk` fyzicky vytiskla na Canon i-SENSYS MF8030Cn:
- **barevný** výtisk ✅
- **monochromatický** výtisk ✅

Celý řetězec bez jakékoli externí závislosti:

```
PDF
  → PdfRenderer (Android)
  → CupsRasterWriter: CUPS Raster v3, 96 MB/stránku, banding (256 řádků/band)
  → proot (statický, z nativeLibraryDir)
  → rastertoufr2 (Canon arm64 filtr, uvnitř glibc rootfs)
  → UFR-II stream (~833 KB barva / ~269 KB mono)
  → java.net.Socket → 192.168.0.62:9100
  → tiskárna
```

**Výstup filtru:** `exit 0`; zprávy `bidiCommon.c err=0` na stderr jsou nezávadné.

---

## Architektura finálního řešení

### Komponenty APK

| Komponenta | Umístění | Popis |
|---|---|---|
| `libproot.so` | `jniLibs/arm64-v8a/` | proot přeložený NDK r27, staticky linkovaný talloc; spouštěn přímo z `nativeLibraryDir` (W^X bypass) |
| `libproot_loader.so` | `jniLibs/arm64-v8a/` | proot statický ELF loader (arm64) |
| `libproot_loader32.so` | `jniLibs/arm64-v8a/` | proot loader (32bit, rezerva) |
| `libtalloc.so` | `jniLibs/arm64-v8a/` | Termux talloc 2.4.3 (záloha; nová libproot si talloc linkuje staticky) |
| `libtest_hello.so` | `jniLibs/arm64-v8a/` | NDK bionic smoke test |
| `libptraceprobe.so` | `jniLibs/arm64-v8a/` | diagnostická sonda ptrace |
| `rootfs.tar.gz` | `assets/` | ~50 MB: Canon arm64 driver + glibc closure + caepcm data |
| `rootfs.version` | `assets/` | sha256 tarballu → řídí re-extrakci při aktualizaci |
| `cups_page_header_a4.bin` | `assets/` | binární šablona `cups_page_header2_t` (1796 B), golden reference |
| `sample.pdf` | `assets/` | vestavěné testovací PDF |

### Kotlin komponenty

| Třída | Soubor | Role |
|---|---|---|
| `CupsRasterWriter` | `CupsRasterWriter.kt` | PdfRenderer → CUPS Raster v3, banding, RGB |
| `RootfsManager` | `RootfsManager.kt` | extrakce rootfs.tar.gz, version check, path-traversal guard |
| `ProotLauncher` | `ProotLauncher.kt` | spuštění proot+filtru, 3-vláknový pipeline (feeder/socket/stderr) |
| `MainActivity` | `MainActivity.kt` | UI, file picker, share target, barva/mono přepínač |

### Závislosti build.gradle.kts

```kotlin
implementation("androidx.appcompat:appcompat:1.7.0")
implementation("androidx.core:core-ktx:1.13.1")
implementation("org.apache.commons:commons-compress:1.26.2")  // extrakce tar.gz
```

---

## Řetězec chyb → oprav (podrobný záznam)

### N1 - Špatný proot binár: `execve ENOSYS` + ptrace I/O error

**Symptom:**  
Původně bundlovaný Termux proot 5.1.107.76 (bionic build z Phase 2) selhal na Android 16 s:
```
proot: execve(…) = ENOSYS
ptrace(PEEKDATA): I/O error
```

**Co to NENÍ:**
- Není to 16KB page size (Samsung S24+ má 4KB stránky, ne 16KB).
- Není to ptrace/seccomp blokování Androidem 16 samotným.

**Skutečná příčina:**  
Termux proot 5.1.107.76 je starý/chybný binár, který nezvládá specifické chování ptrace v jádru Android 16. Build z projektu `green-green-avk/build-proot-android` (2023) měl analogický problém (viz Phase 2 / Pokus 2).

**Oprava:**  
Přeložit proot ze zdrojů přes **`green-green-avk/build-proot-android`**:
- NDK r27, cíl `aarch64-linux-android29`
- staticky linkovaný **talloc 2.4.2** (nevyžaduje `libtalloc.so` v LD_LIBRARY_PATH)
- flag `-Wl,-z,max-page-size=16384` (16KB page alignment - pro budoucí 16KB zařízení)

Výsledný `libproot.so` (217 KB): dynamicky linkuje pouze `libc.so` + `libdl.so` (bionic), jinak self-contained. Spouštěn přímo z `nativeLibraryDir` - W^X bypass přes jniLibs funguje.

---

### N2 - `libcups.so.2`: 82 nedefinovaných symbolů po odebrání závislostí

**Symptom:**  
`build-bundle.sh` odstraní `libgssapi_krb5.so.2`, `libavahi-client.so.3`, `libavahi-common.so.3`, `libgnutls.so.30` z DT_NEEDED v `libcups.so.2` (nepotřebné pro rasterový tisk). Ale libcups stále importuje jejich symboly (UND):
```
undefined symbol GSS_C_NT_HOSTBASED_SERVICE
```

Navíc `patchelf --remove-needed` odstraní DT_NEEDED záznamy, ale **nechá DT_VERNEED** záznamy → glibc assert / "version 0" chyba při dynamickém linkování.

**Oprava (dvě části):**

1. **Vynulovat DT_VERNEED + DT_VERNEEDNUM** přímo v `.dynamic` sekci (Python inline v `build-bundle.sh`).

2. **Vygenerovat `libcupsstub.so`** - prázdné stuby pro všechny UND symboly z gss/GSS/gnutls/avahi skupin:
   - Generováno dynamicky z `readelf --dyn-syms` na patchovaném libcups (81 FUNC + 1 OBJECT).
   - Přidat do libcups přes `patchelf --add-needed libcupsstub.so` + `--set-rpath '$ORIGIN'`.

   **KRITICKÉ:** Kompilovat stub s `-nostdlib -nodefaultlibs -fno-stack-protector` - jinak NDK clang přidá `libc.so` / `libdl.so` do DT_NEEDED stubu, což způsobí:
   ```
   error loading libdl.so
   ```
   (bionic libdl.so v `nativeLibraryDir` se nedá otevřít z guest procesu).

---

### N3 - AGP přejmenuje asset: `rootfs.tar.gz` → `rootfs.tar`

**Symptom:**  
Android Gradle Plugin (AGP) při buildu dekomprimuje `.gz` assety a přibalí je bez přípony `.gz`. Pevně zakódované `assets.open("rootfs.tar.gz")` v `RootfsManager` vyhazuje `FileNotFoundException`.

**Oprava:**  
`RootfsManager.ensureExtracted()` enumeruje všechny assety a hledá ten začínající na `"rootfs.tar"`:
```kotlin
val assetName = context.assets.list("")?.firstOrNull { it.startsWith("rootfs.tar") }
```
Navíc detekuje formát magic byty (`0x1f 0x8b` = gzip), aby správně volil `GzipCompressorInputStream` vs. prostý `TarArchiveInputStream`.

---

### N4 - Stale rootfs: aktualizace APK neaktualizuje rootfs

**Symptom:**  
Po aktualizaci APK (nové `jniLibs/proot`, nový `rootfs.tar.gz`) zůstal v `filesDir/rootfs` starý rootfs z předchozí instalace - filtr pak volal staré binárky nebo chyběly nové knihovny.

**Příčina:**  
Původní version marker byl pevný řetězec v kódu. Při aktualizaci APK se `jniLibs` extrahují znovu, ale `filesDir` zůstane nedotčen.

**Oprava:**  
`build-bundle.sh` na konci zapíše sha256 tarballu do `assets/rootfs.version`:
```bash
sha256sum "$OUT_DIR/rootfs.tar.gz" | awk '{print $1}' > "$OUT_DIR/rootfs.version"
```
`RootfsManager` při každém startu porovná tento sha256 se souborem `.rootfs_version` v `filesDir/rootfs`. Nesoulad → smazání celého rootfs + nová extrakce.

---

### N5 - Špatné `LD_LIBRARY_PATH` pro guest

**Symptom:**  
`rastertoufr2` a jeho child procesy (`cnjbigufr2`) nemohou najít sdílené knihovny - `dlopen` selhává.

**Příčina:**  
`LD_LIBRARY_PATH` byl nastaven na `nativeLibraryDir` (bionic knihovny), nikoli na glibc multiarch cesty uvnitř rootfs.

**Oprava:**  
Oddělení host LD_LIBRARY_PATH (pro proot samotný - nepotřebuje žádné, je statický) od guest LD_LIBRARY_PATH (pro glibc dynamické knihovny uvnitř rootfs):

```kotlin
// proot je staticky linkovaný - žádné LD_LIBRARY_PATH pro proot samotný
// Guest LD_LIBRARY_PATH - cesty UVNITŘ rootfs (glibc Debian arm64 multiarch)
put("LD_LIBRARY_PATH",
    "/usr/lib/aarch64-linux-gnu:/lib/aarch64-linux-gnu:/usr/lib:/lib:/usr/lib/cups/filter")
```

---

### N6 - Hlavní crash: `libcanon_pdlwrapper.c:634 err=-1` → `free(): invalid pointer`

**Symptom:**  
`rastertoufr2` havaroval uprostřed zpracování rasteru s:
```
libcanon_pdlwrapper.c:634 err=-1
free(): invalid pointer
Aborted
```

**Debugovací metoda (klíčová):**  
Crash byl reprodukován na **amd64 dev boxu** (WSL2) spuštěním amd64 filtru pod amd64 proot s minimálním rootfs:
```bash
proot -r minimal_rootfs rastertoufr2 1 user title 1 "CNColorMode=color"
# → reproducuje přesný crash

proot -r / rastertoufr2 …
# → funguje (plný host rootfs)
```
Bisekce bind mounty:
```bash
proot -b /usr/lib -r minimal_rootfs …   # → funguje
proot -b /usr/lib/x86_64-linux-gnu …    # → stále crash
proot -b /usr/lib/aarch64-linux-gnu …   # → nutné vybrat správnou arch
```
`strace` na pracujícím bare-metal spuštění zachytil seznam otevíraných `.so` souborů - diff oproti minimálnímu rootfs odhalil chybějící knihovny.

**POZNÁMKA:** `strace` **nevidí skrze proot** (oba používají ptrace); amd64 ovladač v6.30 má identický zdrojový kód jako arm64 - perfektní referenční platforma.

**Skutečná příčina:**  
Canon knihovny `libcanonufr2r.so` a `libuictlufr2r.so` volají za běhu `dlopen("libxml2.so.2")` (přes interní strukturu `g_libxml_info`, zkouší `libxml2.so` / `.so.16` / `.so.2`). `libxml2` pak potřebuje:
- `libicuuc.so.72`
- `libicudata.so.72`
- `liblzma.so.5`

Žádná z těchto knihoven není v DT_NEEDED Canon binárky (čisté `dlopen` za běhu), takže `build-bundle.sh` je původně nezahrnul. Chybějící `libxml2` způsobil, že Canon inicializační kód zapsal na nevalidní ukazatel → `free(): invalid pointer`.

**Oprava:**  
Přidat do `PACKAGES` v `build-bundle.sh`:
```bash
"libx/libxml2/libxml2_2.9.14+dfsg-1.3~deb12u5_arm64.deb"
"i/icu/libicu72_72.1-3+deb12u1_arm64.deb"
"x/xz-utils/liblzma5_5.4.1-1_arm64.deb"
```

---

### N7 - `rastertoufr2` argv: 5 argumentů, ne 6

**Symptom:**  
Volání s 6 argumenty (včetně názvu tiskárny jako argv[0] na pozici před číslem jobu):
```
ENAMETOOLONG → exit 255
```

**Skutečná signatura:**  
```
rastertoufr2 <job-id> <user> <title> <copies> <options>
```
Přesně 5 pozičních argumentů. Název tiskárny **NENÍ** prefixový argument (to byl mylný předpoklad z Phase 1.5 záznamu); argv[0] je binárka samotná, jak obvykle.

**Ověřeno:** na amd64 dev boxu přímým voláním filtru ze scaffoldu.

**Výsledné volání v kódu:**
```kotlin
val cmd = listOf(
    proot.absolutePath, "-0", "-r", rootfsDir.absolutePath, …,
    "/usr/lib/cups/filter/rastertoufr2",
    "1",            // job-id
    "androiduser",  // user
    "androidprint", // title
    "1",            // copies
    optionsStr      // options (CNColorMode=color|mono …)
)
```

---

### N8 - Symlinky libjbig + ld.so.conf

**Symptom:**  
`cnjbigufr2` (Canon JBIG encoder) nemohl za běhu najít `libjbig.so`/`libjbig.so.0` - glibc dynamický linker prohledává `/usr/lib` jako hardcoded fallback, ale Debian arm64 má soubory pouze v `/usr/lib/aarch64-linux-gnu/`.

**Oprava:**  
`build-bundle.sh` vytváří symlinky v `/usr/lib`:
```bash
usr/lib/libjbig.so → aarch64-linux-gnu/libjbig.so.0
usr/lib/libjbig.so.0 → …
usr/lib/libjbig.so.2.0 → …
usr/lib/libjbig.so.2.1 → …
```
A přidává `/etc/ld.so.conf` s `include /etc/ld.so.conf.d/*.conf` pro správné chování ldconfig cache.

---

## Finální pipeline: parametry

### CUPS Raster v3 (generovaný CupsRasterWriter)

| Parametr | Hodnota |
|---|---|
| Sync word | `RaS3` (0x33 0x53 0x61 0x52, little-endian) |
| Header | 1796 B (`cups_page_header2_t`), golden reference z asset `cups_page_header_a4.bin` |
| Rozměry | 4724 × 6780 px (A4 @ 600 DPI) |
| Barevný prostor | RGB, 3 bajty/pixel, bez komprese |
| `BYTES_PER_LINE` | 14172 (= 4724 × 3) |
| Stránka | 4724 × 6780 × 3 = ~96 MB nekomprimovaně |
| Banding | 256 řádků/band; reusable Bitmap + IntArray + ByteArray |

**Poznámka k rozměrům:** CupsRasterWriter používá 4724 × 6780 px, zatímco golden reference z Phase 1.5 zachytila 4961 × 7016 px. Oba formáty filtr akceptuje - rozměry jsou zakódovány v hlavičce, která je zkopírována verbatim z golden asset.

### UFR-II výstup (měřeno)

| Režim | Velikost UFR-II |
|---|---|
| Barva | ~833 KB |
| Mono | ~269 KB |

### Env vars pro rastertoufr2 (uvnitř proot guest)

```
PPD=/etc/cups/ppd/CanonMF8030.ppd
CUPS_DATADIR=/usr/share/cups
CUPS_SERVERROOT=/etc/cups
CUPS_SERVERBIN=/usr/lib/cups
CUPS_CACHEDIR=/var/cache/cups
CONTENT_TYPE=application/vnd.cups-raster
FINAL_CONTENT_TYPE=application/vnd.cups-raster
PRINTER=CanonMF8030
LD_LIBRARY_PATH=/usr/lib/aarch64-linux-gnu:/lib/aarch64-linux-gnu:/usr/lib:/lib:/usr/lib/cups/filter
```

---

## Runtime pipeline a error handling

### Přehled pipeline

```
Sdílené URI / vestavěné PDF
  │
  ▼  [Main Thread]
  Kopie do cacheDir/print_input.tmp   (ContentResolver → seekable soubor)
  │
  ▼  [Worker Thread - Thread {}]
  CupsRasterWriter.writePdf()
    PdfRenderer → Bitmap (ARGB_8888)
    → banding po 256 řádcích → RGB konverze
    → CUPS Raster v3 hlavička (1796 B golden asset)
    → surová data stránky (~96 MB, 4724×6780 px @600 DPI)
  │
  ▼  ProotLauncher.runFilterPipeline()
  │
  ├── [Thread 1 - feeder]
  │     CupsRasterWriter píše raster → process.outputStream (stdin rastertoufr2)
  │     Po dokončení zavře stdin → filtr dostane EOF
  │
  ├── [Thread 2 - socket drainer]
  │     Socket.connect(192.168.0.62:9100, timeout=5 000 ms)
  │     process.inputStream (stdout rastertoufr2) → UFR-II byty → Socket.getOutputStream()
  │     Při chybě: ConnectException nebo SocketTimeoutException → zpráva do VÝSLEDEK
  │
  └── [Thread 3 - stderr reader]
        process.errorStream → seznam řádků → VÝSLEDEK blok
        (bidiCommon.c err=0 jsou nezávadné)
  │
  ▼  process.waitFor(120, TimeUnit.SECONDS)      ← celkový timeout 120 s
     join: feeder 5 s, socket 10 s, stderr 5 s
  │
  ▼  VÝSLEDEK: exitCode, rasterBytesFed, ufrBytesSent, socketResultMsg, stderr
```

### Časové limity

| Limit | Hodnota | Kde v kódu |
|---|---|---|
| Socket connect timeout | **5 000 ms** | `sock.connect(InetSocketAddress(ip, port), 5000)` |
| Celkový process timeout | **120 s** | `process.waitFor(120, TimeUnit.SECONDS)` |
| Feeder join | 5 s | `feederThread.join(5000)` |
| Socket join | 10 s | `socketThread.join(10000)` |
| Stderr join | 5 s | `stderrThread.join(5000)` |

### Typy chyb ze socketThread

| Výjimka | Zpráva v logu | Příčina |
|---|---|---|
| `ConnectException` | `socket ConnectException: … >> Zkontroluj WiFi a IP tiskárny 192.168.0.62` | tiskárna nedostupná / zavřený port |
| `SocketTimeoutException` | `socket Timeout: … >> Tiskárna nereaguje na 192.168.0.62:9100` | tiskárna nereaguje v 5 s |
| ostatní `Exception` | `socket error (ClassName): …` | jiná síťová chyba |

### Formát bloku VÝSLEDEK

```
VÝSLEDEK:
=== TISK PDF (rastertoufr2) ===
colorMode=COLOR  printer=192.168.0.62:9100
exitCode=0  elapsed=42000ms  timedOut=false
--- raster generátor ---
OK, 96288384 bajtů zapsáno (očekáváno 96288384 na stránku)
UFR-II výstup: socket OK: 852992 UFR-II bajtů odesláno na 192.168.0.62:9100
--- stderr (posledních N řádků) ---
bidiCommon.c: bidiCommon() err=0    ← nezávadné, ignorovat
```

Pokud je `exitCode ≠ 0`, příčina je ve stderr sekci nebo v chybějících libs (viz N6).

---

## Metodika ladění: amd64 pod amd64 proot

Klíčová technika, která odhalila příčinu crashe (N6):

1. **Instalace amd64 filtru** na WSL2 dev boxu (Phase 1 scaffold, vždy přítomný).
2. **Sestavení minimálního rootfs** - jen Canon arm64 deb extrahovaný do prázdného adresáře (bez systémových knihoven).
3. **Spuštění amd64 proot v6.30** nad tímto minimálním rootfs:
   ```bash
   proot -0 -r ./minimal -b /proc -b /dev -b /sys \
     /usr/lib/cups/filter/rastertoufr2 1 u t 1 "CNColorMode=color" \
     < raster.bin > /dev/null
   # → free(): invalid pointer  (identický crash jako na telefonu)
   ```
4. **Bisekce bind mounty** - přidávat `/usr/lib` postupně, dokud crash nezmizí.
5. **strace bare-metal** - `strace -e trace=openat rastertoufr2 … 2>&1 | grep '\.so'` → seznam reálně otevíraných `.so` souborů.
6. **Diff** mezi strace výstupem a minimálním rootfs → chybějící libxml2/ICU.

`strace` nelze použít uvnitř proot (oba používají ptrace), proto se ladí na amd64 bare-metal a výsledky se přenesou na arm64.

---

## Co zbývá (polish)

- **Velikost APK** - ~102 MB, z toho rootfs.tar.gz ~50 MB. Lze ořezat caepcm data (jen MF8000 série), odstranit libtest_hello.so/libptraceprobe.so/runBinaryDirect/runProotTest/runPtraceProbe/runCnjbigProbe z release buildu.
- **Konfigurovatelná IP tiskárny** - hardcoded `192.168.0.62`. Přidat nastavení (Settings screen nebo v UI).
- **Share flow** - funguje přes `ACTION_SEND` pro PDF a obrázky; ověřit s dalšími aplikacemi.
- **Ověření obrázků** - `printImage()` implementováno, fyzicky neověřeno (jen PDF).
- **Diagnostické sondy** - `runBinaryDirect`, `runProotTest`, `runPtraceProbe`, `runCnjbigProbe` jsou vývojové nástroje; v release verzi odstranit nebo skrýt.
- **targetSdk** - aktuálně 35 (Android 15). Android 16 (SDK 36) funguje, SDK 37 (Android 17) přidá `ACCESS_LOCAL_NETWORK` permission.

---

*Viz [docs/08-review-rizika-roadmap.md](08-review-rizika-roadmap.md) pro aktualizovaný stav rizik a roadmapy.*
