# 15 - Phase 4: Android PrintService - implementace a finální verze (2026-06-06)

## Status: BRÁNA SPLNĚNA ✅

**Datum:** 2026-06-06  
**APK:** `UFR-1.0.apk`  
**Zařízení:** Samsung Galaxy S24+ (SM-S926B), Android 16 (SDK 36), arm64-v8a  
**Základ:** Phase 3 (fyzicky ověřeno ✅) + PrintService vrstva + seekable-fd oprava + ladící log  
**Výsledek:** fyzický výtisk na Canon i-SENSYS MF8030Cn ze systémového tiskového dialogu;  
~2,2 s, exitCode=0, socket OK, papír vyjet.

---

## 1. Cíl a princip

Cílem Phase 4 je zpřístupnit aplikaci jako **systémovou tiskárnu** - tj. aby se Canon MF8030Cn
objevil v nativním tiskovém dialogu systému Android (Nastavení → Tisk; Chrome → Tisk; jakákoli
aplikace volající `PrintManager`).

Vnitřní pipeline (proot → rastertoufr2 → UFR-II → socket) zůstala beze změny; Phase 4 je
čistě integrace rozhraní `android.printservice.PrintService`.

Sdílení souborů přes `ACTION_SEND` a tlačítko **TEST TISK** v `MainActivity` jsou zachovány
jako záloha a diagnostický nástroj.

---

## 2. Kořenová příčina a oprava - `dupOrCopyPdf`

Toto je nejdůležitější technický nález Phase 4.

### Symptom (zavádějící)

Tisk přes systémový dialog selhal s chybou v logu:

```
socket error (InterruptedIOException): read interrupted by close() on another thread
```

Tento řádek pochází z `socketThread` v `ProotLauncher` - zdánlivě se jedná o síťovou chybu.

### Skutečná příčina

Systémový spooler předá `printJob.document.data` jako **non-seekable stream** (roura /
spoolerový fd). Volání `ParcelFileDescriptor.dup(data.fileDescriptor)` uspěje - ale vrátí fd,
který **není seekovatelný**. Při pokusu `PdfRenderer(dupovaný_fd)` Android hodí:

```
IllegalArgumentException: File descriptor not seekable
```

Výjimka zabije `feederThread` po několika bajtech. Pipeline pak:

1. `feederThread` zemře ihned.
2. `socketThread` drénuje stdout rastertoufr2 - filtr dostane stdin EOF okamžitě, skončí.
3. `process.waitFor(120 s)` čeká na ukončení procesu.
4. Po uplynutí 120 s: `process.destroyForcibly()` zavře stream procesu uprostřed čtení.
5. To vyvolá `InterruptedIOException: read interrupted by close() on another thread`
   v `socketThread`.

**Viditelná chyba (socketThread) je tedy downstream symptom; skutečnou příčinou je
non-seekovatelný fd, který zabije feederThread při PdfRenderer init.**

### Oprava (`dupOrCopyPdf`)

`CanonPrintService.dupOrCopyPdf()` **vždy** zkopíruje bajty spooleru do regulárního souboru
v `cacheDir` a otevře jej read-only:

```kotlin
val tmp = File(cacheDir, "printjob_${jobId}.pdf")
ParcelFileDescriptor.AutoCloseInputStream(data).use { input ->
    tmp.outputStream().use { out -> input.copyTo(out) }
}
val pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
```

Regulární soubor IS seekovatelný - `PdfRenderer` jej přijme. Toto zrcadlí přesně to, co
`MainActivity.doPrint()` dělala pro sdílené URI (ContentResolver → `cacheDir/print_input.tmp`),
a proč cesta **Sdílet PDF** vždy fungovala.

**Pravidlo: nikdy nepředávat dup'd spoolerový fd přímo do `PdfRenderer`.**

Původní kód měl strategii A (dup) + fallback B (kopie do souboru). Po tomto nálezu zůstala
**jen kopie** - dup byl zcela odstraněn.

### Jak byla diagnóza provedena

Zařízení nemá adb. Diagnóza byla možná až po přidání **perzistentního log souboru**:

- `svcLog()` v `CanonPrintService` zapisuje timestampované řádky do `filesDir/printservice.log`
  (capped 256 KB, staré log smazán při překročení).
- `MainActivity.onResume()` automaticky zobrazí obsah `printservice.log` v UI, pokud soubor
  existuje.

Tím bylo možné číst logy ze `CanonPrintService` bez adb, přímo na obrazovce zařízení.

---

## 3. Gotcha: přepnutí tiskové služby po reinstalaci

Po každé reinstalaci APK je nutné tiskovou službu **vypnout a znovu zapnout** v:

> Nastavení → Připojení → Další nastavení připojení → Tisk → UFR → přepínač OFF → ON

Samsung One UI (a Android obecně) si po reinstalaci ponechá starý vázaný service. Dokud
není přepínač recyklován, OS stále komunikuje se starým (odinstalovaným) instancem →
PrintService se chová jako by nefungoval. Tento problém způsobil několik falešných
„stále rozbité" výsledků během ladění.

---

## 4. Architektura

### Nové zdrojové soubory (Phase 4)

| Soubor | Role |
|---|---|
| `print/Printer.kt` | Datová třída `Printer` (id, name, ip, port, colorCapable, source, model) + enum `PrinterSource` (MANUAL / DISCOVERED) + objekt `PrinterStore` |
| `print/CanonPrintService.kt` | Rozšiřuje `android.printservice.PrintService`; serializuje tiskové úlohy přes single-thread executor |
| `print/CanonPrinterDiscoverySession.kt` | Rozšiřuje `PrinterDiscoverySession`; spravuje seznam tiskáren a spouští `DiscoveryEngine` |
| `print/DiscoveryEngine.kt` | Tři paralelní discovery větve (mDNS, TCP scan, BJNP UDP) |
| `print/AddPrinterActivity.kt` | UI pro ruční správu tiskáren (přidat / upravit / smazat) + spouštění skenu |
| `res/xml/print_service_capabilities.xml` | Meta-data PrintService: `settingsActivity` i `addPrintersActivity` nastaveny na `AddPrinterActivity` |

### PrinterStore (Printer.kt)

`PrinterStore` je tenká persistence nad `SharedPreferences` (`"canon_printers"`, klíč
`"manual_printers"`) s JSON serializací přes platform `org.json`. Při prvním spuštění
automaticky vytvoří výchozí záznam:

```
Canon MF8030Cn (default)  -  192.168.0.62 : 9100  -  barva  -  MANUAL
```

### CanonPrintService

Životní cyklus:
- `onCreate` - spustí pre-warm extrakce rootfs (neblokující; chyba není fatální)
- `onCreatePrinterDiscoverySession` - vytvoří `CanonPrinterDiscoverySession`
- `onPrintJobQueued` - okamžitě zavolá `printJob.start()`, ověří tiskárnu + formát (jen A4),
  zkopíruje PDF do seekovatelného cache souboru (`dupOrCopyPdf`), odešle úlohu do executoru
- `onRequestCancelPrintJob` - zavolá `future.cancel(false)` (bez interrupt, viz sekce 5)

`printExecutor` je záměrně **jednopříkový** (`newSingleThreadExecutor`): filtr `rastertoufr2`
a `cnjbigufr2` nejsou bezpečné pro souběžné volání a pro každou úlohu se otevírá jeden RAW TCP
socket na tiskárnu.

Úspěch tiskové úlohy:
```kotlin
val ok = result.contains("exitCode=0") && result.contains("socket OK:")
```

### CanonPrinterDiscoverySession

Při `onStartPrinterDiscovery` načte perzistované ruční tiskárny z `PrinterStore` (vždy alespoň
výchozí), ihned je publikuje a spustí `DiscoveryEngine`. Nově nalezené tiskárny jsou přidány
pouze pokud pro dané `ip:port` neexistuje žádný MANUAL záznam (ruční záznamy mají přednost).

`PrinterCapabilitiesInfo` se připojuje lazily v `onStartPrinterStateTracking`.

Capabilities pro barevnou tiskárnu:
- Formáty: A4 (výchozí)
- Rozlišení: 600 × 600 dpi (výchozí)
- Barevné režimy: COLOR + MONOCHROME (výchozí COLOR)
- Okraje: min. 118 thou (~5 mm) na každé straně

### Blok v AndroidManifest.xml

```xml
<service
    android:name=".print.CanonPrintService"
    android:exported="true"
    android:permission="android.permission.BIND_PRINT_SERVICE">
    <intent-filter>
        <action android:name="android.printservice.PrintService" />
    </intent-filter>
    <meta-data
        android:name="android.printservice"
        android:resource="@xml/print_service_capabilities" />
</service>
```

### Oprávnění (Phase 4 přidala oproti Phase 3)

| Oprávnění | Důvod |
|---|---|
| `ACCESS_NETWORK_STATE` | Zjištění aktivní sítě a lokální IP (TCP scan) |
| `ACCESS_WIFI_STATE` | Záložní zjištění IP přes `WifiManager.connectionInfo` (API < 31) |
| `CHANGE_WIFI_MULTICAST_STATE` | Vyžadováno pro `WifiManager.MulticastLock` - bez něj Samsung zahazuje mDNS pakety |

---

## 5. Discovery: tři metody

`DiscoveryEngine` spouští tři větve souběžně. Každá tiskárna je nahlášena přes `onFound` na
main vlákně nejvýše jednou za session (deduplikace přes `ConcurrentHashMap<"ip:port", Boolean>`).

### A - mDNS (NsdManager)

Registruje NSD discovery pro typy: `_pdl-datastream._tcp`, `_printer._tcp`, `_ipp._tcp`,
`_ipps._tcp`. Před spuštěním se získá `WifiManager.MulticastLock` (`"canonprint-mdns"`).
Resolve requesty jsou serializovány přes frontu - `NsdManager.resolveService` zvládne na
starších API jen jeden souběžný resolve.

**Praktická poznámka:** Canon MF8030Cn nepoužívá mDNS. Větev A je přítomna pro kompatibilitu
s jinými síťovými tiskárnami.

### B - TCP sken podsítě :9100

48 vláken prochází `.1`–`.254` aktuální `/24` podsítě, timeout 300 ms na adresu.

**Toto je spolehlivá cesta pro MF8030Cn** - port 9100 tiskárna otevřený má.

### C - Canon BJNP UDP broadcast (best-effort)

16bajtový discovery broadcast na porty 8610 a 8612. Formát modelován ze zdrojů cups-bjnp
(magic `BJNP`, device_type=tiskárna, command=discover). Celá větev je obalena v try/catch.

**Poznámka:** Byte-layout paketu nebyl výslovně ověřen Wireshark zachycením vůči fyzickému
MF8030Cn; pro účely projektu to nevadí (tiskárna se discoveryuje přes výchozí MANUAL záznam
a TCP sken B).

### Ruční zadání + výchozí záznam

`AddPrinterActivity` umožňuje přidat tiskárnu ručně, otestovat port 9100 (timeout 3 s) a
spravovat seznam. Výchozí `192.168.0.62:9100` je přítomen vždy po instalaci.

---

## 6. Klíčová technická rozhodnutí

### Podmínka úspěchu: exitCode=0 AND socket OK

```kotlin
val ok = result.contains("exitCode=0") && result.contains("socket OK:")
```
Samotné `exitCode=0` nestačí - filtr může skončit 0, zatímco socket thread selhal.

### Single-thread executor

`Executors.newSingleThreadExecutor()` serializuje všechny tiskové úlohy. Viz sekce 2 (nativní
pipeline není thread-safe, jeden socket na úlohu).

### A4-only validace

`CupsRasterWriter` generuje raster s pevnou geometrií 4724 × 6780 px (A4 @ 600 DPI). Jiný
formát papíru by prošel pipelineou s chybným výsledkem. Úloha s jiným formátem je odmítnuta
přes `printJob.fail("Podporováno jen A4")`.

### PrinterCapabilitiesInfo: lazy v onStartPrinterStateTracking

Capabilities nelze připojit při prvním `addPrinters`. Framework je vyžaduje až při výběru
tiskárny uživatelem - to je v `onStartPrinterStateTracking`.

### Zrušení úlohy: `cancel(false)`, bez interrupt

`future.cancel(false)` - záměrně BEZ přerušení vlákna. Původní kód používal `cancel(true)`;
přerušení executor vlákna zanechá interrupt flag nastaven na sdíleném workeru → **příští** tisková
úloha startuje s nastaveným interrupt flagem a ihned selže s
`"read interrupted by close() on another thread"`. Oprava: `cancel(false)` + na začátku každé
úlohy `Thread.interrupted()` pro vyčištění případného starého flagu.

Probíhající nativní pipeline nelze přerušit (JVM interrupt se nepropaguje do nativního procesu);
aktuální stránka vždy doběhne.

### PrintJobId - import z android.print, ne android.printservice

`PrintJobId` pochází z `android.print`, nikoli `android.printservice`. Záměna způsobuje chybu
kompilace.

### ColorMode - top-level `cz.ufrii.print.ColorMode`

Enum `ColorMode` je definován na úrovni balíčku `cz.ufrii.print`. `CanonPrintService`
importuje explicitně:
```kotlin
import cz.ufrii.print.ColorMode
```

---

## 7. Release v1.0

| Atribut | Hodnota |
|---|---|
| **Název aplikace** | `UFR` (label v AndroidManifest) |
| **applicationId** | `cz.ufrii.print` (beze změny) |
| **versionName** | `1.0` |
| **versionCode** | `9` |
| **Ikona** | Adaptivní launcher (tiskárna/printer glyph na modrém pozadí) |
| **Artefakt** | `UFR-1.0.apk` |

Název "CanonPrint" byl přejmenován na "UFR" - uživatel si nepřeje mít ochrannou známku Canon
v názvu aplikace. `applicationId` zůstává `cz.ufrii.print` (zachovává datové adresáře
a sharedPreferences z předchozích verzí).

**Co zůstalo:** Diagnostické sondy (`runBinaryDirect`, `runProotTest`, `runPtraceProbe`,
`runCnjbigProbe`) a on-screen + file logging (`printservice.log`) byly zachovány - jsou užitečné
pro budoucí ladění na zařízeních bez adb.

**Co bylo odstraněno:** Dev-only UDP netlog (broadcastoval logy po síti do WSL2 konzole) -
v release verzi nepotřebný a nežádoucí.

---

## 8. Build (release)

```bash
ln -sfn "/path/to/UFR-II_AndroidPrint" /tmp/canonbuild
cd /tmp/canonbuild
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew assembleDebug
```

Výsledný APK zkopírovat jako `UFR-1.0.apk`.

---

## 9. Ověření na zařízení (splněno)

Postup, který byl úspěšně proveden na Samsung Galaxy S24+ / Android 16:

1. **Sideload APK** - `UFR-1.0.apk` nainstalovat z Files/správce souborů.
2. **Aktivace PrintService:**  
   Nastavení → Připojení → Další nastavení připojení → Tisk → UFR → zapnout přepínač.  
   **Po reinstalaci:** přepínač vypnout a znovu zapnout (viz sekce 3).
3. **Tisk z Chrome (nebo jiné aplikace):**  
   Menu → Tisk → vybrat **Canon MF8030Cn (default)** → Tisk.
4. **Fyzický výsledek:** stránka vytisknuta, ~2,2 s od odeslání, `exitCode=0`, `socket OK`.

Regrese (TEST TISK v `MainActivity`) nadále funguje beze změny.

### Formát bloku VÝSLEDEK (při úspěšném tisku přes PrintService)

V `printservice.log` (zobrazeno v `MainActivity` při dalším spuštění):

```
[HH:mm:ss] onPrintJobQueued jobId=…
[HH:mm:ss] === CanonPrint v1.0 PrintService - job=… ===
[HH:mm:ss] printer resolved: Canon MF8030Cn (default) 192.168.0.62:9100 (source=MANUAL)
[HH:mm:ss] colorMode=COLOR mediaSize=ISO_A4
[HH:mm:ss] PDF zkopírováno do printjob_….pdf (… B) - seekovatelný fd
[HH:mm:ss] spouštím tisk → 192.168.0.62:9100 colorMode=COLOR
[HH:mm:ss] printPdf result:
             exitCode=0  elapsed=…ms  timedOut=false
             UFR-II výstup: socket OK: … UFR-II bajtů odesláno na 192.168.0.62:9100
[HH:mm:ss] ok=true
[HH:mm:ss] HOTOVO: job=… complete
```

---

*Viz [docs/13-phase3-vysledky.md](13-phase3-vysledky.md) pro technické detaily pipeline rastertoufr2 a debugovací metodiku Phase 3.*  
*Viz [docs/14-uzivatelsky-navod.md](14-uzivatelsky-navod.md) pro postup instalace a diagnostiku bloku VÝSLEDEK.*
