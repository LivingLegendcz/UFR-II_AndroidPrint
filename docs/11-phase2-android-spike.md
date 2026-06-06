# 11 - Phase 2: Android spike - výsledky (2026-06-03)

## Status: ✅ DOKONČENO

Cíl Phase 2: z holé APK (bez Termuxu, bez root) spustit nativní arm64 binárku
přes ProcessBuilder - ověřit W^X bypass přes jniLibs a funkčnost Termux proot
jako základ pro Phase 3 (spuštění `rastertoufr2`).

---

## Co bylo vyzkoušeno (chronologicky)

### Pokus 1 - statický musl binár (`aarch64-linux-gnu-gcc -static`)

**Výsledek: SIGSYS (exit 159)**

Kompilace standardním `aarch64-linux-gnu-gcc -static -o test-hello main.c`.
Binárka se spustila (Android jí dovolil exec z nativeLibraryDir), ale okamžitě
dostala SIGSYS při inicializaci libc.

**Příčina:**
`aarch64-linux-gnu-gcc` cílí na GNU/Linux ABI s **glibc** (ne musl). glibc startup
volá syscall `personality(0xFFFFFFFF)` (číslo 135 na arm64) jako ASLR hint.
Android seccomp filter na Android 10+ blokuje `personality()` kompletně - filtr
ho v allowlistu nemá → jádro pošle SIGSYS → exit 159 (128 + 31).

Binárka nikdy nedosáhla `main()`.

---

### Pokus 2 - green-green-avk bionic proot + bionic loader

**Výsledek: EFAULT / ENOSYS při spuštění guest přes ptrace**

Proot z projektu `green-green-avk/build-proot-android` (Android NDK, bionic libc).
proot samotný startoval správně. Při interceptování `execve` pro guest binárku ale
selhal s EFAULT - `ptrace(PTRACE_POKEDATA, ...)` nemohl zapsat cestu loaderu
do paměti tracee procesu.

**Verbose log ukázal:**
```
proot info: vpid 1: sysenter end: void(0xfffffffffffffff2, ...)
proot info: vpid 1: seccomp SIGSYS: execve(0xfffffffffffffff2, ...)
```
`0xfffffffffffffff2 = -14 = EFAULT` - proot se pokoušel zapsat loader path
na invalid adresu, protože `process_vm_writev` nebo `PTRACE_POKEDATA` selhaly.

**Příčina:**
Tato verze proot (2023) má specifické problémy s Android 12+ ptrace omezením
při loader injection přes tracee paměť.

---

### Pokus 3 - Termux proot 5.1.107.76 (bionic, z Termux APT)

**Výsledek: ✅ exitCode=0**

**Klíčové rozdíly oproti Pokusu 2:**
- Termux proot je patchován pro aktuální Android
- `LD_LIBRARY_PATH = nativeLibraryDir` - linker najde `libtalloc.so`
- `PROOT_LOADER = nativeLibDir/libproot_loader.so` - statický bionic loader (18 KB)
- `PROOT_NO_SECCOMP = 1` - ptrace-only mód, bez vlastního BPF filtru
- Odstraněny nepodporované flagy: `--mute-setxid`, `--tcsetsf2tcsets`
- `libtalloc.so` patchnut patchelfem: `--replace-needed libtalloc.so.2 libtalloc.so`

---

### Pokus 4 - přímý exec NDK bionic (bez proot)

**Výsledek: ✅ exitCode=0**

`test-hello` zkompilován s **Android NDK r27** (`aarch64-linux-android35-clang -static`),
umístěn jako `libtest_hello.so` v `jniLibs/arm64-v8a/`.

Spuštění přes `ProcessBuilder(bin.absolutePath)` - bez proot, bez rootfs,
bez žádné extra konfigurace. Funguje přímo.

**Proč NDK funguje a musl ne:**
Android NDK bionic libc startup `personality()` nevolá. bionic je navržen přímo
pro Android seccomp prostředí - nepoužívá žádné syscally blokované Android filterem.

---

## Root cause analýza: Android seccomp a `personality()`

Android 10+ instaluje při spuštění každého procesu seccomp BPF **allowlist** (whitelist).
Filtry jsou definovány v AOSP `bionic/libc/SECCOMP_ALLOWLIST_COMMON.TXT` a
`SECCOMP_ALLOWLIST_APP.TXT`. Vše mimo allowlist dostane `SIGSYS` → exit 159.

**Syscally blokované na Android arm64 relevantní pro nás:**

| Syscall | Číslo | Status | Dopad |
|---------|-------|--------|-------|
| `personality` | 135 | **BLOCKED** | glibc startup crash → musl méně pravděpodobně, ale možné |
| `stat` (starý) | 4 | **BLOCKED** | stará glibc volání |
| `access` (starý) | 21 | **BLOCKED** | stará glibc volání |
| `epoll_wait` (starý) | 232 | **BLOCKED** | stará glibc volání |
| `fork` | na arm64 neexistuje | N/A | použít `clone` |
| `set_robust_list` | 99 | EPERM (soft) | ignorováno, nekrashuje |

**`PROOT_NO_SECCOMP=1` - proč pomáhá:**
Termux proot má dva režimy:
1. **Seccomp mode** - instaluje vlastní BPF filter nad Android filterem (kaskáda filtrů),
   zachycuje syscally pro chroot emulaci. Na Android 10+ kaskáda občas nefunguje.
2. **ptrace-only mode** (`PROOT_NO_SECCOMP=1`) - spoléhá jen na ptrace PTRACE_SYSCALL,
   nevkládá vlastní BPF filter → méně konfliktů s Android filterem.

---

## Finální pracující konfigurace

### Test 1: Přímý exec (NDK bionic, bez proot)

```kotlin
val bin = File(nativeLibDir, "libtest_hello.so")
ProcessBuilder(bin.absolutePath)
    .redirectErrorStream(true)
    .start()
```

Předpoklady:
- Binárka zkompilována s **Android NDK r27** (`aarch64-linux-android35-clang -static`)
- Pojmenována `lib*.so`, umístěna v `jniLibs/arm64-v8a/`
- `android:extractNativeLibs="true"` v AndroidManifest.xml
- `useLegacyPackaging = true` v `build.gradle.kts`

### Test 2: proot (Termux 5.1.107.76)

```kotlin
val cmd = listOf(
    proot.absolutePath,           // libproot.so z nativeLibraryDir
    "-0",                         // fake root (UID 0 uvnitř rootfs)
    "-r", rootfsDir.absolutePath, // rootfs adresář (povinný i pro statické binárky)
    "-b", "/proc:/proc",
    "-b", "/dev:/dev",
    "-b", "/sys:/sys",
    "-b", "/system:/system",
    "--link2symlink",             // emulace hardlinků jako symlinků
    "-w", "/",                    // working dir uvnitř rootfs
    "/spike/test-hello"           // cesta guest binárky uvnitř rootfs
)

// Env vars pro ProcessBuilder
environment().apply {
    put("PROOT_NO_SECCOMP", "1")                     // NUTNÉ na Android 10+
    put("PROOT_TMP_DIR", prootTmpDir.absolutePath)
    put("PROOT_LOADER", loaderBin.absolutePath)       // libproot_loader.so
    put("LD_LIBRARY_PATH", nativeLibDir.absolutePath) // pro libtalloc.so
    put("TMPDIR", cacheDir.absolutePath)
    put("HOME", "/")
    put("PATH", "/system/bin:/system/xbin")
}
```

**Odstraněné flagy (Termux 5.1.107.76 je nepodporuje):**
- `~~--mute-setxid~~` - flag specifický pro green-green-avk fork
- `~~--tcsetsf2tcsets~~` - flag specifický pro green-green-avk fork

---

## Bundlované binárky (jniLibs/arm64-v8a/)

| Soubor | Zdroj | Verze | Velikost |
|--------|-------|-------|----------|
| `libproot.so` | packages.termux.dev proot .deb | 5.1.107.76 | 258 KB |
| `libtalloc.so` | packages.termux.dev libtalloc .deb | 2.4.3 | 31 KB |
| `libproot_loader.so` | extrahováno z proot .deb (statický) | - | 18 KB |
| `libproot_loader32.so` | extrahováno z proot .deb (32bit) | - | 6 KB |
| `libtest_hello.so` | NDK r27 bionic static arm64 | - | 426 KB |

**Kritická patchelf operace:**
```bash
# Termux proot linkoval proti libtalloc.so.2, ale soubor se jmenuje libtalloc.so
patchelf --replace-needed libtalloc.so.2 libtalloc.so libproot.so
# Ověřit: readelf -d libproot.so | grep NEEDED → libtalloc.so (bez .2)
```

---

## Klíčová architektonická rozhodnutí

### Všechny guest binárky = NDK bionic nebo pod proot+glibc rootfs

Phase 2 potvrdila:
1. **Přímý exec bez proot** - pouze NDK bionic static binárky (bez personality() problému)
2. **Pod proot** - fungují i glibc dynamic binárky, pokud rootfs obsahuje glibc closure

`rastertoufr2` (Canon arm64 filter) je glibc dynamicky linkovaný → **musí běžet pod proot**
s plným glibc rootfs. To je cílem Phase 3.

### W^X bypass přes jniLibs

`nativeLibraryDir` je jediné místo v Android app sandboxu s exec permissions.
Všechny spustitelné binárky musí:
1. Být pojmenovány `lib*.so`
2. Být v `app/src/main/jniLibs/arm64-v8a/`
3. APK mít `android:extractNativeLibs="true"` + `useLegacyPackaging=true`

---

## Příští kroky - Phase 3

1. **Bundle rootfs** - `scripts/build-bundle.sh`: Canon arm64 driver + glibc + libcups + caepcm
2. **Kotlin raster generátor** - `PdfRenderer` → CUPS Raster v3 (reference: `captures/golden_raster.bin`)
3. **Proot pipeline** - stdin CUPS raster → `rastertoufr2` → stdout UFR-II → TCP `:9100`
4. **UI** - file picker, print options (barva/mono), share target

Viz [docs/12-jak-sestavit-apk.md](12-jak-sestavit-apk.md) pro build guide.
