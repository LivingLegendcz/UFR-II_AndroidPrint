# Project Log

Chronologický log zjištění a rozhodnutí. Nejnovější nahoře v rámci dne.

---

## 2026-06-05 - ✅ PHASE 3 GATE PASSED - fyzický tisk z holé APK

**Výsledek:** `CanonPrint-0.3-phase3.apk` (~102 MB) fyzicky vytiskl barevnou i monochromatickou stránku na Canon i-SENSYS MF8030Cn. Žádný Termux, žádný root, žádný adb.

**Zařízení:** Samsung Galaxy S24+ (SM-S926B), Android 16 (SDK 36), page size 4KB, arm64-v8a

**Pipeline:** PdfRenderer → CUPS Raster v3 (96 MB/stránku, banding 256 řádků) → proot → rastertoufr2 → UFR-II (~833 KB barva / ~269 KB mono) → java.net.Socket → 192.168.0.62:9100

**Filtr exit 0.** Zprávy `bidiCommon.c err=0` na stderr jsou nezávadné.

**Klíčové nálezy (8 bugs → oprav, viz [docs/13](docs/13-phase3-vysledky.md) pro detail):**

1. **Termux proot 5.1.107.76 selhal na Android 16** (`execve ENOSYS` + ptrace I/O error) - nebyl to 16KB page size (S24+ má 4KB) ani seccomp blokování. Špatný/starý binár. **Oprava:** přeložit proot přes `green-green-avk/build-proot-android` s NDK r27, staticky linkovaný talloc 2.4.2, `-Wl,-z,max-page-size=16384`.

2. **libcups.so.2 - 82 nedefinovaných symbolů** po odebrání gssapi/gnutls/avahi z DT_NEEDED, plus DT_VERNEED záznamy. **Oprava:** Python inline skript vynuluje DT_VERNEED; generovat `libcupsstub.so` (81 FUNC + 1 OBJECT, dynamicky z readelf) s `-nostdlib -nodefaultlibs` (bez bionic libc.so/libdl.so v DT_NEEDED stubu).

3. **AGP přejmenuje asset:** `rootfs.tar.gz` → `rootfs.tar`. **Oprava:** RootfsManager enumeruje assets + detekuje gzip magic.

4. **Stale rootfs** při aktualizaci APK. **Oprava:** `rootfs.version` = sha256 tarballu (z build-bundle.sh); RootfsManager smaže + re-extrahuje při neshodě.

5. **LD_LIBRARY_PATH pro guest** nesmí být nativeLibraryDir (bionic). Musí být glibc multiarch cesty uvnitř rootfs: `/usr/lib/aarch64-linux-gnu:/lib/aarch64-linux-gnu:/usr/lib:/lib:/usr/lib/cups/filter`.

6. **Hlavní crash** `libcanon_pdlwrapper.c:634 err=-1` → `free(): invalid pointer`: Canon libs `libcanonufr2r.so` / `libuictlufr2r.so` dlopenují `libxml2.so.2` za běhu (ne v DT_NEEDED), libxml2 potřebuje `libicuuc.so.72` + `libicudata.so.72` + `liblzma.so.5`. **Oprava:** přidat bookworm arm64 `libxml2`, `libicu72`, `liblzma5` do build-bundle.sh.

7. **rastertoufr2 argv:** 5 argumentů (job-id user title copies options), **ne 6** (bez printer-name prefixu). Více → ENAMETOOLONG → exit 255.

8. **Debugging metoda:** reprodukce crashe na amd64 dev boxu (amd64 filtr pod amd64 proot s minimálním rootfs) + bisekce bind mounty + strace bare-metal run → diff chybějících .so. `strace` nevidí skrze proot (oba používají ptrace).

**Soubory:**
- `docs/13-phase3-vysledky.md` - podrobný technický záznam (nová)
- `docs/08-review-rizika-roadmap.md` - Gate 3 označen ✅, R1 aktualizován
- `docs/12-jak-sestavit-apk.md` - aktualizován pro Phase 3 build
- `README.md` - Phase 3 DONE, zbývající polish
- `app/build/outputs/apk/debug/app-debug.apk` - 102 MB, aktuální build

---

## 2026-06-03 - ✅ PHASE 2 SPIKE OVĚŘENA

**Výsledek:** NDK bionic statická arm64 binárka executa z nativeLibraryDir bez proot → exitCode=0, výstup "proot spike: hello from arm64 guest ELF"

**Co bylo zjištěno:**
- W^X bypass přes jniLibs/ funguje: binárky jako lib*.so v jniLibs/arm64-v8a/ jsou nainstalovány do nativeLibraryDir (exec-able, read-only), lze je spawnat přes ProcessBuilder
- Klíčový požadavek: kompilovat s Android NDK (bionic libc), NE s aarch64-linux-gnu-gcc (glibc)
  - Důvod: glibc startup volá `personality()` syscall → Android seccomp BLOKUJE → SIGSYS (exit 159)
  - NDK bionic nepoužívá personality() ani jiné blokované syscally
- LD_LIBRARY_PATH = nativeLibraryDir je správný fix pro dynamicky linkované binárky

**Co proot stále selhává:**
- proot-tracee SIGSYS: Android seccomp blokuje execve v ptrace-modifikovaném kontextu (EFAULT při zápisu loader path do tracee paměti)
- proot je potřeba jen pro glibc dynamické binárky (rastertoufr2)
- Long-term fix: build-proot-android (green-green-avk) = proot se staticky linkovaným talloc

**Soubory:**
- libtest_hello.so (426KB NDK bionic, staticky linkovaný, arm64)
- libproot.so (Termux proot 5.1.107.76, bionic)
- libtalloc.so (Termux 2.4.3, bundlovaný)
- app-debug.apk (7.7MB)

**Příští krok - Phase 3:**
Kotlin raster generator: PdfRenderer → CUPS raster v3 (bez CUPS, bez proot) → rastertoufr2 přes NDK exec pipeline

---

## 2026-06-03 - Phase 2 Spike zahájena

**Co bylo připraveno:**
- Android APK skeleton vytvořen v `app/`
- Build toolchain: Java 17 + Android SDK (platform-tools 37, build-tools 35, platforms;android-35) nainstalováno v `~/android-sdk`
- `local.properties`: `sdk.dir=/root/android-sdk`
- proot binary: green-green-avk Android build (dynamicky linkovaný s bionic, 209 KB) umístěn jako `app/src/main/jniLibs/arm64-v8a/libproot.so`
- proot loadery: `libproot_loader.so` (5,5 KB), `libproot_loader32.so` (5,8 KB)
- test-hello: staticky linkovaný arm64 ELF (620 KB, 16KB page aligned) v `app/src/main/assets/spike/`
- Klíčové zdrojové soubory: `ProotLauncher.kt`, `MainActivity.kt`
- Kritické příznaky: `android:extractNativeLibs=true` + `useLegacyPackaging=true`
- `PROOT_NO_SECCOMP=1` nastaven v ProotLauncheru (nutné na Android 10+)

**Stav buildu:** `./gradlew assembleDebug` spuštěn

**Příští krok:** nainstalovat APK na telefon a zkontrolovat Logcat pro "proot spike: hello from arm64 guest ELF"

---

## 2026-06-03 - ✅ PHASE 1.5 DOKONČENA - golden capture

**Metoda:** WSL2 Ubuntu 24.04 amd64 použito jako dev sandbox (místo Termuxu).
Canon driver `cnrdrvcups-ufr2-uk_6.30-1.07_amd64.deb` + CUPS na x86_64 fyzicky tiskne
na MF8030Cn - text i barvy ověřeny.

**Golden captures uloženy v `captures/`:**
- `golden_raster.bin` - 92 MB CUPS Raster v3 LE, A4 @600 DPI (vstup do rastertoufr2)
- `ufrii_stream.pcap` - 335 KB UFR-II TCP stream na port 9100 (výstup z rastertoufr2)
- `cups_env.txt` - prostředí které CUPS předává filtru (PPD, CUPS_DATADIR, CUPS_SERVERBIN...)
- `cups_args.txt` - argumenty volání filtru (printer, job_id, user, options, ...)

**Klíčové parametry CUPS raster page header:**
- Sync: `RaS3` (CUPS Raster v3 LE)
- PageSize: A4 (595×842 pt)
- HWResolution: 600 DPI
- MediaType: 'Auto'
- cupsPageSizeName: 'A4'
- cupsRenderingIntent: 'auto'

**Kritické env vars pro Android APK:**
```
PPD=/etc/cups/ppd/CanonMF8030.ppd
CUPS_DATADIR=/usr/share/cups
CUPS_SERVERBIN=/usr/lib/cups
CUPS_SERVERROOT=/etc/cups
CUPS_CACHEDIR=/var/cache/cups
```

**Příští krok:** Phase 2 spike - APK bez Termuxu. Cíl: `libproot.so` (statický proot
jako native lib v APK) spustí rastertoufr2 z arm64 driveru. Vstup: CUPS raster generovaný
Android PdfRenderer+Kotlin. Výstup: UFR-II na socket:9100.

---

## 2026-06-03 - ✅ PŘÍPRAVA DOKONČENA (session uzavřena)

Plánovací/přípravná fáze hotová. Vše podchyceno: tiskárna + protokol (docs/01–02), zvažované
cesty (03), architektura self-contained APK bez Termuxu, device-agnostic (04), rozbor ovladače
(07), kritická rizika + roadmapa s bránami + fallback (08), sken nice-to-have (09), multi-model
nice-to-have. Rozhodnutí: Claude staví APK; vstupy PDF+obrázky+share (office odloženo); barva/mono
přepínatelné; device-agnostic (nevázat na model). **Implementace zatím nezačala.**

**Příští session začni:** Fáze 1 (u tiskárny na WiFi) - dev scaffold, `rastertoufr2` vytiskne.
Paralelně lze (nezávisle na síti): skeleton appky + příprava bundle. Prerekvizity: build toolchain
(Android Studio+NDK), 16KB-page-safe arm64 `proot`.

---

## 2026-06-03 - Finální rozhodnutí pro plánování

- **Telefon:** **device-agnostic** - uživatel nechce vázat na model (chce přežít výměnu telefonu).
  → stavět na **nejpřísnější arm64 Android** (A16/16KB stránky/SELinux), runtime detekce, bez
  per-model tuningu. Zabalený `proot`+glibc musí být **16KB-page-safe**. Jediný požadavek: arm64.
  Spike F2 (proot bez Termuxu) zůstává první kritická brána.
- **Kdo staví:** APK staví Claude, uživatel testuje.
- **Vstupy MVP:** PDF + obrázky + share target. **Office dokumenty odloženy** (konverze do PDF
  na Androidu těžká - žádný snadný LibreOffice; obejít „otevřít → sdílet jako PDF").
- **Prerekvizity:** přesný model telefonu (+`uname -m`, 16KB?), build toolchain (Android Studio
  +NDK na Win stroji, zdroj arm64 proot), pro F1/F1.5 být u tiskárny na WiFi.
- **Lze začít hned (nezávisle na síti):** skeleton Android projektu + příprava bundle
  (extrakce ovladače, glibc closure, proot) → `scripts/build-bundle.sh`. Plný build čeká na F1.5 referenci.
- Plán finalizován (viz `.claude/plans/…` + docs/04+07+08).

## 2026-06-03 - Průzkum skenování (multifunkce) → nice-to-have

- Otázka: jde z MF8030Cn vytěžit i skener z telefonu? Paralelní rešerše (3 agenti).
- **Verdikt:** USB sken funguje (SANE pixma, MF8030 `:complete`). **Síťový sken rozbitý OOTB** -
  zařízení se hlásí jako „MF8000 Series", ale pixma tabulka zná jen „MF8030" → `model is unknown`
  (potvrzeno reálným sane-devel threadem 2020 pro tenhle model). Protokol = MFNP (UDP 8610)/BJNP (8612).
- **VueScan (closed) skenuje MF8030 přes síť na Linuxu** → protokol je dosažitelný (chce na displeji
  „SCAN → Remote Scanner → OK"). Blokuje jen SANE model-matching → opravitelné patchem pixma.
- Žádné eSCL/WSD (2010), žádný Canon arm64 sken ovladač. SANE pixma = open-source C, arm64 OK,
  jde zabalit do proot jako u tisku (čistší - bez proprietární binárky).
- **Odhad ~50–60 %**, samostatný problém, NE symetrický s tiskem.
- **Rozhodnutí:** sken = **nice-to-have**, neplánovat aktivně. Zadokumentováno v [docs/09](docs/09-skenovani.md).
- ⏸️ Živá sonda `nmap -sU -p 8610,8612 --script bjnp-discover 192.168.0.62` **odložena** -
  uživatel byl mimo síť tiskárny. Spustit, až bude u ní (go/no-go pro sken).

## 2026-06-03 - Požadavek: výběr barva/mono při tisku

- Upřesnění: appka musí umět **per-job přepínač barva/mono**.
- Ověřeno v PPD `CNRCUPSMF8000CZK.ppd`: volba **`*CNColorMode` = `color`/`mono`** (default color)
  → stačí jeden barevný PPD + option `CNColorMode=color|mono`, není třeba bundlovat mono PPD.
- Bonus: `*DefaultColorSpace: RGB` → filtr chce RGB vstup, CMYK separaci dělá sám → snižuje R2
  (PdfRenderer dává RGB, žádná vlastní CMYK konverze).
- Zapsáno do docs/08 (Tiskové volby + barva/mono sekce) a docs/04. Mono-first = jen dev pořadí.

## 2026-06-03 - Revize plánů (3 agenti) + zachycení mezer

- Spuštěna paralelní revize: (1) technická proveditelnost no-Termux APK, (2) úplnost/rozpory
  dokumentace, (3) adversariální „co nám uniklo".
- **Kritické nálezy (dříve nezachycené):**
  - 🔴 **Android zakazuje exec z filesDir** (Android 10+) → proot musí jako `libproot.so`
    v jniLibs; proot pak přes ptrace spouští guest binárky. Fixovatelné, ale nutný spike.
  - 🔴 **Generování CUPS rasteru** = nové #1 riziko (nahradilo OPVP); řešení = zachytit
    referenční raster ze scaffoldu a replikovat; nebo `pdftocpca`/`cnpdfdrv`.
  - 🟠 `rastertoufr2` standalone potřebuje env (PPD/CUPS_DATADIR/RIP_CACHE…) + data na cestách.
  - Doporučení **mono-first**; potvrdit „self-contained" `strace`-em; LPD 515 jako fallback.
- **Dokumentační rozpory opraveny:** `setup-debian.sh` přepsán (jeden .deb, bez gs, správný PPD/cesta);
  filename sjednocen na `-07`; gs/OPVP v docs/05 označeno neaktuální; PLAN.md tělo označeno ARCHIV;
  docs/02 banner; docs/04 risk tabulka doplněna; přidána licence + fallback do README.
- **Nový [docs/08-review-rizika-roadmap.md](docs/08-review-rizika-roadmap.md)** = autoritativní
  rizika + opravená fázová roadmapa (1, 1.5, 2-spike, 3) s bránami + fallback žebřík + mezery
  (error handling, tiskové volby, permissions, build-bundle, paměť/banding).
- Uzavřeno: PPD ověřeno = `CNRCUPSMF8000CZK.ppd` (barva) / `CNRCUPSMF8000ZK.ppd` (mono).

## 2026-06-03 - Multi-model jako nice-to-have (roadmap)

- Zjištěno: balík `cnrdrvcups-ufr2-uk` má **414 PPD** → podporuje 414 modelů Canon UFR-II/UFRII LT
  (91 LBP, 76 MF, 57 iR-ADV C, 55 iR-ADV, 53 iR, 29 iR C, …). Jádro sdílené, liší se jen PPD.
- **Rozhodnutí:** multi-model podpora = **nice-to-have AŽ PO MF8030Cn MVP** (ne hned).
  Zabalit celý ovladač + SNMP auto-detekce modelu + výběr ze seznamu. Zapsáno do PLAN.md (Roadmap)
  a README. Pořadí: MVP MF8030Cn → ověřit tisk → teprve pak generalizace.

## 2026-06-03 - Rozbor ovladače + obrat na self-contained APK (bez Termuxu)

- Uživatel vložil do složky `linux-UFRII-drv-v630-m17n-07.tar.gz` (58 MB) a požádal o řešení
  **bez Termuxu** (křehká externí závislost, projekt může umřít).
- **Rozbor `cnrdrvcups-ufr2-uk_6.30-1.07_arm64.deb`** (viz [docs/07-ovladac-rozbor.md](docs/07-ovladac-rozbor.md)):
  - Všechny binárky **AArch64 (arm64)** ✅
  - **Self-contained - NEpotřebuje Ghostscript/OPVP!** `cupsFilter: application/vnd.cups-raster 0 rastertoufr2`.
    Tím odpadá největší riziko projektu (křehká OPVP závislost).
  - Závislosti: cups, libcups2, libcupsimage2, libjpeg62, libjbig0, libgcrypt20 (žádný gs).
  - PPD pro MF8030Cn = **`CNRCUPSMF8000CZK.ppd`** (dříve odhadováno CNCUPS…, správně CNRCUPS…).
  - Velikost 111 MB (60 MB color data caepcm) - pro MF8030 lze ořezat.
- **Rozhodnutí:** místo Termux+proot-distro **zabalit ovladač + glibc libs + statický proot
  do vlastní APK**. Appka je self-contained, Termux nepotřebuje. Termux max. jako dev scaffold.
- Aktualizována architektura ([docs/04](docs/04-reseni-architektura.md)), přidán [docs/07](docs/07-ovladac-rozbor.md).

## 2026-06-03 - Průzkum repozitářů (prior art)

- Paralelní rešerše veřejných repo: CUPS na Androidu, Canon UFR-II na ARM, hotové print appky.
- Klíčové: **AndroidCupsPrint** (hotový frontend), **pelya/android-print-plugin-cups** (blueprint
  proot+CUPS+plugin, ale 2015/bez 64-bit), AUR `cnrdrvcups-lb` (arm64 potvrzeno), **NetPrinter**
  (zero-effort test). Nikdo přesně tenhle stack veřejně neudělal.
- Zapracováno do PLAN.md (sekce Prior art).

## 2026-06-03 - Dokumentace projektu

- Vytvořena projektová složka `CanonMF8030-AndroidPrint` se strukturou docs/scripts/app/captures.
- Zadokumentováno vše zjištěné: discovery tiskárny, analýza protokolu, zvažované cesty, zvolená architektura, setup návod, reference.

---

## 2026-06-02 - Discovery a volba řešení

### Nález tiskárny
- Síťový sken domácí sítě `192.168.0.x` (přes ARP, SNMP, port scan z laptopu `192.168.0.82`).
- Podezřelé zařízení `192.168.0.62`, MAC `00:1E:8F:38:F7:8C` → Canon OUI.
- Otevřené porty: **515 (LPD)**, **9100 (RAW/JetDirect)**, **8080 (admin web)**. Zavřené: 631 (IPP), 80, 443.
- Reverzní DNS: `tiskarna.kocourkovic.cz`.
- **SNMP `sysDescr` = "Canon MF8030 /P"** → potvrzen model **Canon i-SENSYS MF8030Cn**.
- SNMP `prtInterpreterLangFamily` = **1 (other)** → proprietární jazyk, ne PCL/PS.
- PJL dotaz na :9100 bez odpovědi → nepodporuje PJL.

### Analýza protokolu (paralelní výzkum agenty)
- Tiskárna mluví **jen UFR-II LT** - host-based proprietární jazyk.
- `captdriver` (open-source RE) umí jen **CAPT**, ne UFR-II, a jen ČB LBP tiskárny → nepoužitelné.
- Pro UFR-II **neexistuje žádná open-source implementace**.
- Canon ovladač = GPL filtr `pstoufr2cpca` + Ghostscript `opvp` device + **uzavřený blob `libcanon*ufr2`**.
- `CARPS` (`ondrej-zary/carps-cups`) je nejbližší veřejný blueprint Canon raster formátu.

### Rozhodovací proces (klíčová rozhodnutí)
1. ❌ **JetDirect raw PCL/PS** (původní nápad) - tiskárna PCL/PS nezná.
2. ❌ **IPP / cups4j / Mopria** - tiskárna nemá IPP (port 631 zavřený).
3. ❌ **Nativní reverse-engineering UFR-II v appce** - uživatel zamítl ("blbost"); navíc barva UFR-II nikdy veřejně nerozluštěna, ~80 % by se muselo odvodit z captures.
4. ❌ **Windows print-server bridge** - uživatel zamítl ("overkill, žádný server").
5. ❌ **Canon ovladač na x86 vždy-zapnutém stroji** - uživatel nemá/nechce vždy-zapnutý stroj.
6. ✅ **Canon arm64 Linux ovladač na telefonu (Termux + proot Debian 13)** - uživatel našel, že Canon v6.30 má i Linux ARM build. Splňuje VŠECHNY podmínky: reálný ovladač (no RE), žádný extra stroj, sideload.

### Ověření zvolené cesty (paralelní výzkum agenty)
- Canon v6.30 ARM = **arm64/aarch64 only** (ne armhf); MF8030Cn v podporovaných modelech. ✅
- Pipeline pořád **Ghostscript OPVP** (ne self-contained).
- OPVP dostupné v **Debian 13 (trixie) arm64**. ⚠️ Debian 12 (bookworm) má crash regresi (CVE-2024-33871) → použít trixie.
- Běh na telefonu přes **Termux + proot-distro Debian**, bez rootu, arm64-on-arm64 nativně.
- Tisk = outbound TCP na :9100 → žádné síťové problémy, žádný server.
- Footprint ~1–1.5 GB. Fix Android 15/16 seccomp: `export PROOT_NO_SECCOMP=1`.
- Komunita potvrzuje UFR-II na arm64 (RPi/Ubuntu); MF8030Cn na ARM přímo netestováno (stejná pipeline).

---

## Stav fází / TODO

> Aktuální fázová roadmapa s bránami: [docs/08](docs/08-review-rizika-roadmap.md).
> **Phase 3 hotová - viz [docs/13](docs/13-phase3-vysledky.md).**

- ✅ ~~Najít PPD suffix~~ → `CNRCUPSMF8000CZK.ppd` (barva) / `CNRCUPSMF8000ZK.ppd` (mono).
- ✅ ~~Ovladač stažen~~ → `linux-UFRII-drv-v630-m17n-07.tar.gz` (58 MB) ve složce projektu.
- ✅ ~~Rozhodnout barva/mono~~ → **oba, přepínatelné při tisku** (`CNColorMode=color|mono`).
- ✅ ~~Ověřit arm64~~ → Samsung S24+ (SM-S926B), arm64-v8a, Android 16.
- ✅ ~~**Fáze 1**~~ (dev scaffold): `rastertoufr2` tiskne z WSL2 proot Debianu - golden reference zachycena.
- ✅ ~~**Fáze 1.5**~~ zachycen referenční CUPS raster + `strace` manifest + golden UFR-II stream.
- ✅ ~~**Fáze 2 (spike)**~~ z holé APK spuštěn `libproot.so` + filtr bez Termuxu, exitCode=0.
- ✅ ~~**Fáze 3**~~ self-contained appka fyzicky tiskne barva i mono, S24+/Android 16 (2026-06-05).
- ✅ ~~Doplnit `scripts/build-bundle.sh`~~ → sha256 verze, libxml2/ICU, libcupsstub, symlinky.

**Zbývající polish (open):**
- [ ] Konfigurovatelná IP tiskárny (hardcoded `192.168.0.62`).
- [ ] Zmenšení APK (~102 MB) - ořez caepcm, odstranění dev sond z release buildu.
- [ ] Fyzické ověření tisku obrázků (`printImage()` implementováno, neověřeno).
- [ ] targetSdk 36/37 (Android 17 přidá `ACCESS_LOCAL_NETWORK`).

---

## 2026-06-03 - ✅ PHASE 2 SPIKE FINÁLNÍ VÝSLEDEK (exitCode=0 obě cesty)

Proot spike i přímý exec potvrzeny na zařízení:

**Přímý exec** (NDK bionic z nativeLibraryDir): `exitCode=0` ✅
**Proot spike** (Termux proot + rootfs): `exitCode=0` ✅

Finální konfigurace proot:
- libproot.so: Termux 5.1.107.76 (bionic, patchnutý: libtalloc.so.2 → libtalloc.so)
- libtalloc.so: Termux 2.4.3
- libproot_loader.so: Termux statický loader 18KB
- LD_LIBRARY_PATH = nativeLibraryDir
- PROOT_LOADER = nativeLibDir/libproot_loader.so
- PROOT_NO_SECCOMP = 1
- Flagy: -0 -r rootfs -b /proc -b /dev -b /sys -b /system --link2symlink -w /

Odstraněné flagy (Termux nepodporuje): --mute-setxid, --tcsetsf2tcsets
