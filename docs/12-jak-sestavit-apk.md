# 12 - Jak sestavit APK od nuly

> **Aktuální stav (verze 1.3.0, versionCode 17):** dokument popisuje celý aktuální build.
> Pořadí kroků: (1) Canon ovladač → (2) `build-bundle.sh` (rootfs + balík PPD + ořez) →
> (3) `build-scanimage-android.sh` (skener) → (4) `gradlew assembleRelease` (podepsaný APK).
> Viz [docs/13](13-phase3-vysledky.md) pro výsledky Phase 3 a [docs/22](22-v1.3-funkce-a-architektura.md)
> pro architekturu v1.2–v1.3.
>
> Repo je veřejné, ale **neobsahuje** Canon ovladač, vygenerovaný rootfs, balík PPD ani APK
> (vše v `.gitignore`). Sestavený APK nese Canon binárky: jen pro osobní / rodinný sideload.

## Prerekvizity

### Systém
- WSL2 Ubuntu 24.04 LTS (amd64) nebo nativní Linux x86_64
- Projekt nesmí být v cestě s mezerami - workaround viz níže

### Java 17
Gradle 8.9 + Android SDK 35 vyžadují právě Java 17.
```bash
sudo apt install -y openjdk-17-jdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
java -version   # → openjdk 17.x.x
```

### Android SDK (platform 35, build-tools 35)
```bash
mkdir -p ~/android-sdk/cmdline-tools
cd /tmp
wget "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
unzip commandlinetools-linux-*.zip -d /tmp/ct
cp -r /tmp/ct/cmdline-tools ~/android-sdk/cmdline-tools/latest

export ANDROID_HOME=~/android-sdk
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin"

yes | sdkmanager --licenses
sdkmanager "platform-tools" "build-tools;35.0.0" "platforms;android-35"
```

`local.properties` musí obsahovat:
```
sdk.dir=/home/<user>/android-sdk
```

### Android NDK r27 (stub knihovna rootfs, `scanimage`, nové nativní binárky)
```bash
sdkmanager "ndk;27.2.12479018"
# NDK sídlí v ~/android-sdk/ndk/27.2.12479018/
```
NDK je od v1.3 potřeba vždy: `build-bundle.sh` s ním kompiluje `libcupsstub.so`
a `build-scanimage-android.sh` s ním staví skener.

### patchelf, dpkg-deb, wget, curl, python3
```bash
sudo apt install -y patchelf dpkg wget curl python3 build-essential file
```
`patchelf` upravuje `libcups.so.2` v rootfs, `python3` běží `build-ppd-pack.py`
a `sane-patch-mf8000.py`, `curl` stahuje zdroje SANE a libjpeg.

---

## Proot: sestavit z green-green-avk/build-proot-android (NDK r27)

**DŮLEŽITÉ:** Termux proot 5.1.107.76 selhává na Android 16 (`execve ENOSYS`). Proot je třeba
přeložit čerstvě přes `green-green-avk/build-proot-android` s NDK r27.
Viz [docs/13 N1](13-phase3-vysledky.md) pro detail.

### Postup sestavení proot
```bash
# Naklonovat build systém
git clone https://github.com/green-green-avk/build-proot-android
cd build-proot-android

# Nastavit NDK
export ANDROID_NDK_HOME=~/android-sdk/ndk/27.2.12479018

# Sestavit pro arm64 (Android 29+)
./build.sh arm64

# Výsledek:
#   build/arm64/proot         → libproot.so
#   build/arm64/loader        → libproot_loader.so
#   build/arm64/loader32      → libproot_loader32.so
```

Build používá:
- NDK r27, cíl `aarch64-linux-android29`
- Staticky linkovaný talloc 2.4.2 (libproot.so závisí jen na `libc.so` + `libdl.so`)
- Flag `-Wl,-z,max-page-size=16384` (16KB page alignment pro budoucí zařízení)

### Umístit proot binárky
```bash
JNILIBS=app/src/main/jniLibs/arm64-v8a
cp build/arm64/proot      $JNILIBS/libproot.so
cp build/arm64/loader     $JNILIBS/libproot_loader.so
cp build/arm64/loader32   $JNILIBS/libproot_loader32.so
```

**Patchelf pro proot již není potřeba** - nový proot má talloc staticky linkovaný,
`LD_LIBRARY_PATH` pro libtalloc.so není nutné. (Termux libtalloc.so je stále v jniLibs
jako záloha, ale novou proot verzí se nepoužívá.)

### Termux libtalloc.so (záloha, volitelné)
```bash
# Stáhnout z packages.termux.dev (libtalloc_2.4.3_aarch64.deb)
ar x libtalloc_2.4.3_aarch64.deb && tar xf data.tar.xz
cp data/data/com.termux/files/usr/lib/libtalloc.so.2.4.3    libtalloc.so
cp libtalloc.so $JNILIBS/libtalloc.so
```

### Zkompilovat libtest_hello.so (NDK bionic) - jen vývojářská sonda

Od v1.3 jsou vývojářské sondy (`libtest_hello.so`, `libptraceprobe.so`, `spike/test-hello`)
jen v debug source setu (`app/src/debug/jniLibs`, `app/src/debug/assets`), takže release APK
je neobsahuje. Pro běžný build tuto sekci i následující blok přeskočte.

```bash
NDK=~/android-sdk/ndk/27.2.12479018
CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android35-clang

cat > /tmp/hello.c << 'EOF'
#include <stdio.h>
int main() { puts("proot spike: hello from arm64 guest ELF"); return 0; }
EOF

$CC -static -Wl,-z,max-page-size=16384 -o libtest_hello.so /tmp/hello.c
# Ověřit: file libtest_hello.so → ELF 64-bit LSB executable, ARM aarch64, statically linked
```

### Umístit binárky do projektu
```bash
JNILIBS=app/src/main/jniLibs/arm64-v8a
cp libproot.so          $JNILIBS/
cp libtalloc.so         $JNILIBS/
cp libproot_loader.so   $JNILIBS/
cp libproot_loader32.so $JNILIBS/
# jen debug (vývojářské sondy):
cp libtest_hello.so     app/src/debug/jniLibs/arm64-v8a/
cp libtest_hello.so     app/src/debug/assets/spike/test-hello
```
`libscanimage.so` se do `jniLibs/arm64-v8a` zapisuje sám skriptem
`build-scanimage-android.sh` (viz níže).

---

## Sestavit rootfs bundle (scripts/build-bundle.sh)

Před buildem APK je třeba sestavit `assets/rootfs.tar.gz`:

```bash
# Prerekvizity
sudo apt install -y dpkg-deb patchelf wget python3 readelf

# Nejdřív extrahuj Canon driver tarball (pokud ještě není)
cd "/path/to/UFR-II_AndroidPrint"
tar -xzf linux-UFRII-drv-v630-m17n-07.tar.gz

# Sestavit bundle (stáhne arm64 debian balíčky z bookworm, složí rootfs)
ANDROID_NDK=~/android-sdk/ndk/27.2.12479018 \
./scripts/build-bundle.sh
```

Skript provádí (kroky podle výpisu skriptu):
- **[1/5]** Extrakce Canon arm64 driveru (`cnrdrvcups-ufr2-uk_6.30-1.07_arm64.deb`)
- **[2/5]** Stažení arm64 runtime knihoven z Debian bookworm (přes HTTPS): glibc, libcups2, libcupsimage2, libjpeg62-turbo, libjbig0, libgcrypt20, libgpg-error0, zlib1g, **libxml2, libicu72, liblzma5** (nutné - Canon libs je dynamicky dlopenují). Každý balíček se **ověří proti SHA256 před rozbalením** (fail-closed): pokud jsou v poli `SHA256[]` hodnoty `PENDING`, skript při prvním běhu vypíše vypočtené součty a skončí - doplňte je a spusťte znovu.
- **[3/5]** Oprava libcups.so.2: odebrání gssapi/gnutls/avahi z DT_NEEDED, vynulování DT_VERNEED, **kompilace `libcupsstub.so`** (81 FUNC + 1 OBJECT) přes NDK clang s `-nostdlib -nodefaultlibs`
- **[4/5]** Vytvoření adresářové struktury, symlinky libjbig, ld.so.conf
- **[4a/5]** balík PPD (`build-ppd-pack.py`, viz níže)
- **[4b/5]** ořez rootfs (`trim-rootfs.sh`, viz níže)
- **[5/5]** Zabalení do `assets/rootfs.tar.gz` (~43 MB) + **`assets/rootfs.version`** (sha256)

### Balík PPD (scripts/build-ppd-pack.py)

Aplikace podporuje všechny modely Canon UFR-II z ovladače. Místo jednoho PPD v rootfs
proto nese PPD **všech 414 modelů** jako samostatné assety:

```bash
# build-bundle.sh to volá sám; ručně:
python3 scripts/build-ppd-pack.py <adresář s CNRCUPS*.ppd> app/src/main/assets
```

- `assets/ppd.zip` - každé PPD bez překladů (řádky `*de.`, `*fr.`… a komentáře `*%`); výstup filtru
  je s oříznutými PPD bajt po bajtu shodný (ověřeno na několika modelech); 47 MB → asi 2,3 MB.
- `assets/ppd-index.json` - na PPD: soubor, názvy a aliasy modelu, barva, volby `InputSlot`,
  `Duplex`, `CNColorMode` a A4 `ImageableArea`. Aplikace tak nepotřebuje parser PPD a umí přiřadit
  model, který tiskárna hlásí (`PpdCatalog.match`).
- Za běhu aplikace PPD zvoleného modelu rozbalí z `ppd.zip` do `etc/cups/ppd/` v rootfs při
  prvním použití (rootfs se při změně verze maže, PPD se vytvoří znovu).
- Oba soubory jsou **vygenerované, Canonovy a v `.gitignore`**.

### Ořez rootfs (scripts/trim-rootfs.sh)

Odstraní z rozbaleného rootfs, co filtr `rastertoufr2` nečte: PPD ostatních modelů (414 kusů, nyní
v `ppd.zip`), dokumentaci, překlady hlášek, pomocná data lintian/gdb/gcc a GUI `cngplp2`.
Rozbalený rootfs 180 → 128 MB, `rootfs.tar.gz` 52 → 43 MB. Barevné tabulky caepcm, gconv a ICU
zůstávají (rozhodnutí o nich čeká na trace skutečně otevíraných souborů). Skript jde pustit i
samostatně: `./scripts/trim-rootfs.sh <rootfs-dir>`.

**Bundlované assety po sestavení:**
- `assets/rootfs.tar.gz` - ořezaný rootfs (~43 MB gzip)
- `assets/rootfs.version` - sha256 tarballu (řídí re-extrakci při aktualizaci APK)
- `assets/ppd.zip`, `assets/ppd-index.json` - balík PPD všech modelů
- `assets/cups_page_header_a4.bin` - golden CUPS header template (1796 B)
- `assets/sample.pdf` - vestavěné testovací PDF
- (jen debug) `app/src/debug/assets/spike/test-hello` - NDK binárka pro smoke test

---

## Sestavit skener (scripts/build-scanimage-android.sh)

Síťový sken zajišťuje `scanimage` ze SANE, spouštěný přímo (bez proot) jako `libscanimage.so`
z `nativeLibraryDir`.

```bash
sudo apt install -y build-essential curl python3 file
NDK=~/android-sdk/ndk/27.2.12479018 bash scripts/build-scanimage-android.sh
```

Skript:
1. Použije uložené zdrojové tarbally (`~/sane-arm64/`, `~/sane-test/`), jinak stáhne sane-backends
   **1.4.0** a IJG **libjpeg 9f**; obojí ověří proti SHA256.
2. Staticky přeloží libjpeg (backend `pixma` ho při konfiguraci vyžaduje).
3. Aplikuje patch `scripts/sane-patch-mf8000.py` na čisté zdroje (alias modelu MF8000 + podavač,
   obnovení visící relace, watchdog při zaseknutí).
4. Nakonfiguruje SANE s jen backendem `pixma` (předlinkovaný, bez dlopen), `--enable-ipv6`,
   `--disable-pthread` (bionic nemá `pthread_cancel`; čtení obrazu běží v procesu po `fork()`),
   `--disable-locking` a bez USB / SNMP / Avahi.
5. Slinkuje `scanimage` dynamicky proti **systémovému bionic** (NEEDED jen `libc.so libm.so libdl.so`)
   a zapíše `app/src/main/jniLibs/arm64-v8a/libscanimage.so` (~490 kB, 16 KB zarovnání stránek).

Za běhu aplikace předá přes `SANE_CONFIG_DIR` adresář s `dll.conf` (`pixma`) a `pixma.conf`
(`auto_detection=no` + `mfnp://<IP>` nebo `mfnp://[<IPv6>%<ifindex>]`). Název zařízení se zjišťuje
z `scanimage -L`. **Nikdy nepovolujte broadcast discovery** - zasekne UDP 8610 skeneru až do
restartu tiskárny (viz [docs/20](20-skenovani-navrh.md)).

**Proč Android build a ne glibc-static:** starší `scripts/build-scanimage-arm64.sh` (glibc, statický)
na telefonu končí `exit 159` = SIGSYS - startup glibc 2.39 volá syscall, který aplikační seccomp
filtr Androidu blokuje (podezření: registrace `rseq`, `clone3` v `pthread_create`). Binárka
slinkovaná proti bionicu používá jen syscally, které vydává sám systémový libc. Starý skript
zůstává v repu pro referenci a pro testy na počítači; do APK patří jen výstup
`build-scanimage-android.sh`.

`libscanimage.so` je výstup buildu: sestavuje se ze zdrojů SANE a patche tímto skriptem.
Licence SANE: GPL-2.0+ s výjimkou pro linkování; libjpeg: licence IJG.

---

## Build APK

### Workaround pro mezery v cestě
Gradle wrapper selhává pokud cesta projektu obsahuje mezery (např. `My Documents`).
```bash
ln -sf "/path/to/UFR-II_AndroidPrint" \
       /tmp/canonbuild
cd /tmp/canonbuild
```

### Sestavit debug APK
```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
ANDROID_HOME=~/android-sdk \
./gradlew assembleDebug
```

První run stáhne Gradle 8.9 (~300 MB cache). Výsledek:
```
app/build/outputs/apk/debug/app-debug.apk   (větší než release, ~115 MB)
```

Debug APK je větší, protože není minifikovaný a nese vývojářské sondy z `app/src/debug`
(`libtest_hello.so`, `libptraceprobe.so`, `spike/test-hello`).

### Sestavit release APK (ten se instaluje)

Release build je minifikovaný a zmenšený (R8, `isMinifyEnabled` + `isShrinkResources`, pravidla
v `app/proguard-rules.pro`) a **musí být podepsaný**, aby šel nainstalovat.

**Jednorázově** vytvořte keystore a držte ho **mimo repo**:
```bash
keytool -genkeypair -v -keystore ~/canonprint-release.jks -alias canonprint \
        -keyalg RSA -keysize 4096 -validity 10000
```

Přihlašovací údaje načítá `app/build.gradle.kts` z `local.properties` (v `.gitignore`) nebo
z proměnných prostředí stejného jména:
```properties
RELEASE_STORE_FILE=/home/vy/canonprint-release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=canonprint
RELEASE_KEY_PASSWORD=...
```

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
ANDROID_HOME=~/android-sdk \
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk   (~50 MB)
```

- Bez `RELEASE_STORE_FILE` zůstane release APK **nepodepsané** (debug build to neovlivní).
- Keystore, hesla ani podepsaný APK nikdy necommitujte.
- **Zachovejte stejný keystore** pro další verze: Android jinak nepovolí instalaci jako aktualizaci
  (a data aplikace, včetně uložených tiskáren a kalibrací, by se musela smazat).
- Release APK obsahuje Canon binárky: **jen pro osobní / rodinný sideload**, nešiřte ho.

**Proč ~50 MB (release):** `rootfs.tar.gz` ~43 MB (ořezaný) + `ppd.zip` ~2,3 MB + `libscanimage.so`,
proot a aplikace. Dominantní část rootfs je Canon barevná data caepcm.

### Instalace na zařízení
```bash
# USB debugging musí být povoleno v Developer options
adb install -r app/build/outputs/apk/release/app-release.apk

# Sledovat logy (tag CanonPrint)
adb logcat -s CanonPrint:V
```

Bez adb: soubor `.apk` zkopírujte do telefonu a otevřete ho (povolte instalaci z neznámých zdrojů).
Po přeinstalaci přepněte tiskovou službu v Nastavení → Tisk → UFR-II vypnout a zapnout.

---

## Kritické build flagy

| Flag | Umístění | Důvod |
|------|----------|-------|
| `android:extractNativeLibs="true"` | AndroidManifest.xml | Extrahuje `.so` do nativeLibraryDir při instalaci; bez toho jsou komprimované v APK a nelze je exec() |
| `useLegacyPackaging = true` | `build.gradle.kts` (packaging) | Zabraňuje AGP komprimovat `.so`; nutné kombinovat s extractNativeLibs |
| `minSdk = 29` | `build.gradle.kts` | Android 10 - dolní hranice pro spolehlivý PROOT_NO_SECCOMP bypass |
| `targetSdk = 35` | `build.gradle.kts` | Android 15 (SDK 35) - cílová platforma; Android 16 (SDK 36) funguje |
| `-Wl,-z,max-page-size=16384` | proot + NDK binárky | 16KB page alignment - preventivní pro Pixel 8+/9 a budoucí 16KB zařízení |
| `-nostdlib -nodefaultlibs` | libcupsstub.so (build-bundle.sh) | Zabrání NDK clang přidat bionic libc.so/libdl.so do DT_NEEDED stubu |

---

## Troubleshooting

**`INSTALL_FAILED_NO_MATCHING_ABIS`**
→ Zařízení není arm64. Ověřit: `adb shell uname -m` → musí být `aarch64`.

**`libproot.so: cannot execute: Permission denied`**
→ `extractNativeLibs` nebo `useLegacyPackaging` není správně nastaveno.
→ Ověřit: `adb shell ls -la /data/app/cz.ufrii.print*/lib/arm64/` - soubory musí mít `-rwxr-xr-x`.

**`execve ENOSYS` nebo `ptrace(PEEKDATA): I/O error`**
→ Starý/chybný proot binár (typicky Termux 5.1.107.76 na Android 16).
→ Přeložit znovu přes `green-green-avk/build-proot-android` s NDK r27. Viz [docs/13 N1](13-phase3-vysledky.md).

**`undefined symbol GSS_C_NT_HOSTBASED_SERVICE`** nebo jiné gss/gnutls/avahi symboly
→ `libcupsstub.so` chybí nebo nebyl přidán do libcups DT_NEEDED.
→ Znovu spustit `build-bundle.sh`. Viz [docs/13 N2](13-phase3-vysledky.md).

**`free(): invalid pointer` / `libcanon_pdlwrapper.c:634 err=-1`**
→ Chybí `libxml2.so.2`, `libicuuc.so.72` nebo `liblzma.so.5` v rootfs.
→ Znovu spustit `build-bundle.sh` (verze skriptu musí zahrnovat libxml2/libicu72/liblzma5 v PACKAGES).
→ Viz [docs/13 N6](13-phase3-vysledky.md).

**`rastertoufr2` exit 255 nebo ENAMETOOLONG**
→ Špatný počet argv - filtr bere přesně 5 argumentů (job-id, user, title, copies, options), ne 6.
→ Viz [docs/13 N7](13-phase3-vysledky.md).

**Asset rootfs nenalezen (FileNotFoundException)**
→ AGP přejmenoval `rootfs.tar.gz` na `rootfs.tar`. Zkontrolovat, zda `RootfsManager` enumeruje assety.
→ Viz [docs/13 N3](13-phase3-vysledky.md).

**`CANNOT LINK EXECUTABLE: library "libtalloc.so.2" not found`**
→ Stará verze proot z Termuxu. Nový proot (green-green-avk, NDK r27) má talloc staticky linkovaný.

**`exitCode=159` nebo `SIGSYS`**
→ Binárka používá syscall blokovaný Android seccomp (`personality()`, u glibc 2.39 nejspíš i `rseq` / `clone3`).
→ Překompilovat s Android NDK (ne musl/glibc toolchainem). U skeneru použít `build-scanimage-android.sh`, ne `build-scanimage-arm64.sh`.

**`build-bundle.sh`: „SHA256 pro … není nastaven“ / „SHA256 NESOUHLASÍ“**
→ Při prvním běhu doplňte vypsané součty do pole `SHA256[]` ve skriptu. Nesouhlas po doplnění znamená změněný balíček na zrcadle: neignorujte ho, zkontrolujte verzi v cestě.

**`build-scanimage-android.sh`: „missing NDK clang“**
→ Nastavte `NDK=` na cestu k NDK r27 (`…/ndk/27.2.12479018`).

**Aplikace: „libscanimage.so chybí“**
→ Před `gradlew` nebyl spuštěn `build-scanimage-android.sh`.

**Aplikace: „… není v ppd.zip“ / prázdný seznam ovladačů**
→ Chybí `assets/ppd.zip` nebo `assets/ppd-index.json`: spusťte `build-bundle.sh` (volá `build-ppd-pack.py`).

**Release APK nejde nainstalovat**
→ Je nepodepsaný (chybí `RELEASE_*` v `local.properties`), nebo je jiným klíčem podepsaná už nainstalovaná verze. Odinstalujte starou, nebo použijte stejný keystore.

**Skener: `EBUSY` / „Device busy“**
→ Na panelu tiskárny zvolte SCAN → Remote Scanner → OK. Viz [docs/20](20-skenovani-navrh.md).

**Gradle: `Unrecognized option: -`**
→ Cesta projektu obsahuje mezery. Použít symlink `/tmp/canonbuild`.

**Gradle: `SDK location not found`**
→ Zkontrolovat `local.properties` - `sdk.dir` musí být absolutní cesta bez ~.
