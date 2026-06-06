# 12 - Jak sestavit APK od nuly

> **Aktuální stav (2026-06-05):** Phase 3 dokončena - tento dokument popisuje aktuální build.
> Viz [docs/13](13-phase3-vysledky.md) pro výsledky Phase 3 a vysvětlení všech nálezů.

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

### Android NDK r27 (jen pro kompilaci nových nativních binárků)
```bash
sdkmanager "ndk;27.2.12479018"
# NDK sídlí v ~/android-sdk/ndk/27.2.12479018/
```

### patchelf (nutný pro opravu libtalloc SONAME)
```bash
sudo apt install -y patchelf
```

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

### Zkompilovat libtest_hello.so (NDK bionic)
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
cp libtest_hello.so     $JNILIBS/
cp libtest_hello.so     app/src/main/assets/spike/test-hello
```

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

Skript provádí (5 kroků):
1. Extrakce Canon arm64 driveru (`cnrdrvcups-ufr2-uk_6.30-1.07_arm64.deb`)
2. Stažení arm64 runtime knihoven z Debian bookworm: glibc, libcups2, libcupsimage2, libjpeg62-turbo, libjbig0, libgcrypt20, libgpg-error0, zlib1g, **libxml2, libicu72, liblzma5** (nutné - Canon libs je dynamicky dlopenují)
3. Oprava libcups.so.2: odebrání gssapi/gnutls/avahi z DT_NEEDED, vynulování DT_VERNEED, **kompilace `libcupsstub.so`** (81 FUNC + 1 OBJECT) přes NDK clang s `-nostdlib -nodefaultlibs`
4. Vytvoření adresářové struktury, PPD, symlinky libjbig, ld.so.conf
5. Zabalení do `assets/rootfs.tar.gz` (~50 MB) + **`assets/rootfs.version`** (sha256)

**Bundlované assety po sestavení:**
- `assets/rootfs.tar.gz` - rootfs (~50 MB gzip)
- `assets/rootfs.version` - sha256 tarballu (řídí re-extrakci při aktualizaci APK)
- `assets/cups_page_header_a4.bin` - golden CUPS header template (1796 B)
- `assets/sample.pdf` - vestavěné testovací PDF
- `assets/spike/test-hello` - NDK binárka pro smoke test

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
app/build/outputs/apk/debug/app-debug.apk   (~102 MB)
```

**Proč 102 MB:** rootfs.tar.gz (~50 MB) + AGP ho dekomprimuje → `assets/rootfs.tar` (~150 MB nekomprimovaně, pak znovu zkomprimovaný do APK). Dominantní část je Canon caepcm barevná data pro MF8000 sérii.

### Instalace na zařízení
```bash
# USB debugging musí být povoleno v Developer options
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Sledovat logy (tag CanonPrint)
adb logcat -s CanonPrint:V
```

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
→ Binárka používá syscall blokovaný Android seccomp (nejčastěji `personality()`).
→ Překompilovat s Android NDK (ne musl/glibc toolchainem).

**Gradle: `Unrecognized option: -`**
→ Cesta projektu obsahuje mezery. Použít symlink `/tmp/canonbuild`.

**Gradle: `SDK location not found`**
→ Zkontrolovat `local.properties` - `sdk.dir` musí být absolutní cesta bez ~.
