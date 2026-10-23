# Security audit — UFR-II AndroidPrint

**Datum:** 2026-06-10
**Rozsah:** AndroidManifest.xml, všech 9 Kotlin souborů (`app/src/main/kotlin/cz/ufrii/print/`), build.gradle.kts (root + app), `scripts/build-bundle.sh`, `.gitignore`
**Verze:** v1.0 (Phase 1–4 complete)

---

## Souhrn

Kód je na hobby projekt nadprůměrně čistý. Tar extrakce má path-traversal guard, PrintService má správně `BIND_PRINT_SERVICE` permission, proprietární Canon driver je korektně mimo repo. Nebyl nalezen žádný kriticky exploitovatelný nález; identifikováno **3× střední** a **4× nízká/dlouhodobá** závažnost. Všechny střední nálezy jsou triviálně opravitelné (souhrnně ~1 den práce, viz v1.1 v PROJECT-LOG.md).

| # | Nález | Závažnost | Stav |
|---|---|---|---|
| S1 | Distribuce debug buildu (debuggable, bez R8) | 🟠 střední | ✅ opraveno v1.1 (signing+R8) |
| S2 | Vytištěné dokumenty zůstávají trvale v cache | 🟠 střední (privacy) | ✅ opraveno v1.1 (finally + sweep) |
| S3 | build-bundle.sh: HTTP download bez ověření integrity | 🟠 střední (supply chain) | ✅ opraveno v1.1 (https+sha256, hashe doplnit) |
| S4 | `AddPrinterActivity` zbytečně `exported=true` | 🟡 nízká | ✅ exported=false (⚠️ ověřit launch z Nastavení) |
| S5 | Chybí `android:allowBackup="false"` | 🟡 nízká | ✅ opraveno v1.1 |
| S6 | World-rwx permissions na extrahovaném rootfs | 🟡 nízká | ✅ opraveno v1.1 (owner-only) |
| S7 | Pinované bundled knihovny bez patch procesu | 🟡 dlouhodobá | ✅ proces zdokumentován v build-bundle.sh |

> **Stav 2026-06-26:** všechny body opraveny v kódu (vydání v1.1); ověření na zařízení čeká
> (zejm. S4 — že systémové Nastavení stále spustí AddPrinterActivity).

---

## Detaily nálezů

### S1 — Distribuce debug buildu 🟠

README odkazuje na `app-debug.apk`; release buildType nemá signing config ani `isMinifyEnabled`. Debug APK je `android:debuggable=true` → kdokoli s fyzickým/adb přístupem k telefonu může přes `run-as cz.ufrii.print` číst privátní data aplikace (rootfs, printservice.log, uložené tiskárny v SharedPreferences).

**Oprava:** release signing config (keystore mimo repo, cesty přes `local.properties` nebo env), `isMinifyEnabled = true` + `isShrinkResources = true` (R8 navíc výrazně srazí velikost APK). Distribuovat výhradně release.

### S2 — Vytištěné dokumenty zůstávají v cache 🟠 (privacy)

- `CanonPrintService.dupOrCopyPdf()` → `printjob_<id>.pdf` v `cacheDir`, mazání jen přes `deleteOnExit()`, který na Androidu prakticky nikdy neproběhne (proces nekončí čistě).
- `MainActivity.doPrint()` → `print_input.tmp` se nikdy nemaže.
- `MainActivity.doTestTisk()` → `sample_test.tmp` se nikdy nemaže.

Důsledek: každý kdy vytištěný dokument leží v cache donekonečna. Na debug buildu (S1) navíc čitelný přes `run-as`.

**Oprava:** smazat temp soubor ve `finally` bloku po dokončení/selhání jobu (po `pfd.close()`). V `onCreate` služby volitelně úklid sirotčích `printjob_*.pdf`.

### S3 — build-bundle.sh: HTTP bez ověření integrity 🟠 (supply chain)

`DEB_MIRROR="http://ftp.debian.org/debian/pool/main"` — balíčky se stahují přes plain HTTP a rovnou rozbalují do rootfs, který se zabalí do APK. Žádný checksum, žádná signatura. MITM při buildu = kompromitovaný rootfs spouštěný na telefonu.

**Oprava:** `https://` + tabulka SHA256 hashů per balíček (verze už jsou pinované, hashe stačí doplnit jednou a ověřovat `sha256sum -c` před `dpkg-deb -x`).

### S4 — `AddPrinterActivity` exported=true 🟡

Aktivita se spouští výhradně interně z `MainActivity`. `exported=true` umožňuje libovolné aplikaci ji otevřít a vyvolat síťový sken /24 nebo upravit/smazat uložené tiskárny. UI-only, ale zbytečná attack surface.

**Oprava:** `android:exported="false"`.

### S5 — Chybí allowBackup=false 🟡

Default `allowBackup=true` → SharedPreferences (`canon_printers`) a filesDir jdou do auto-backupu (cloud / adb backup na starších API). Nic kritického, ale `printservice.log` může obsahovat názvy tištěných souborů.

**Oprava:** `android:allowBackup="false"` v manifestu (nebo `fullBackupContent` rules vylučující logy a rootfs — rootfs v backupu navíc zbytečně bobtná).

### S6 — World-rwx na extrahovaném rootfs 🟡

`RootfsManager` a `ProotLauncher.extractAsset()` nastavují `setReadable/setWritable/setExecutable(true, false)` — druhý parametr `false` = pro všechny uid. Na API 29+ je `filesDir` izolovaný sandboxem, takže prakticky neexploitovatelné, ale jde proti principu least privilege.

**Oprava:** `(true, true)` (owner-only) všude; exec bit potřebuje jen vlastní uid.

### S7 — Pinované knihovny bez patch procesu 🟡 (dlouhodobé)

Rootfs bundluje konkrétní bookworm verze (glibc 2.36, libxml2, libicu72, liblzma5…). Vstup filtru je raster generovaný vlastní appkou (ne přímo útočníkem), takže reálná exploitabilita je nízká, ale libxml2/icu mají CVE pravidelně a neexistuje update kanál.

**Oprava (proces):** při každém rebuildu bundle aktualizovat na nejnovější `+debXuY` revize v build-bundle.sh; 1× za čas zkontrolovat Debian security tracker pro bundlované balíčky. commons-compress 1.26.2 je po CVE-2024-25710/26308 OK — hlídat při upgrade Gradle.

---

## Vědomé kompromisy / co je OK

- **Tar extrakce:** path-traversal guard přes `canonicalPath.startsWith(rootfsCanonical)` chrání i proti symlink escape (canonicalPath resolvuje symlinky). ✅
- **RAW port 9100 je plaintext** — vlastnost protokolu, ne chyba. Tisková data jsou na LAN odposlechnutelná; na cizí/firemní síti s tím počítat.
- **`PROOT_NO_SECCOMP=1`** — nutnost pro proot na Android 10+; guest binárky stejně běží pod app uid v sandboxu.
- **TCP sken /24 (48 vláken)** — na domácí síti OK; na firemní síti spolehlivě spustí IDS/EDR alert. Zmínit v README.
- **Service `exported=true` + `android.permission.BIND_PRINT_SERVICE`** — správný vzor, bindovat může jen systém. ✅
- **`.gitignore`** — driver, rootfs, APK, `local.properties` korektně vyloučeny. ✅

---

## Code quality (mimo security)

- `PrinterStore.saveManual()` používá `.commit()` (blokující, main thread) → `.apply()`.
- Validace IP v `AddPrinterActivity` je jen "neprázdné, bez mezer" — regex na IPv4/hostname by chytil překlepy dřív než socket timeout.
- Default `192.168.0.62` duplikován ve 3 místech (`ProotLauncher` defaulty ×3, `PrinterStore`, README) — nechat jediný zdroj v `PrinterStore`.
- Žádné unit testy — kandidáti: `RootfsManager` (path traversal, version marker), `PrinterStore.parseJson` (malformed JSON), `CanonPrintService.shortMsg`.
