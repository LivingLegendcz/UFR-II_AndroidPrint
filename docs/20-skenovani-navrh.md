# 20 - Síťový sken: ověření (fáze 0) a plán

**Datum:** 2026-10-23
**Stav:** ✅ **Fáze 0 GO** — reálný síťový sken z MF8030Cn proběhl (WSL2, patchnutý SANE pixma).
**Navazuje na:** [docs/09](09-skenovani.md) (původní průzkum, odhad 50–60 %).

---

## 1. Výsledek

Barevný sken 150 dpi celé plochy skla (1280×1754 px, JPEG 160 kB) za **12 s**, `exit=0`, obraz čistý.
Toolchain: sane-backends **1.4.0** (release tarball, SHA256 ověřen) + **1řádkový patch** pixma,
x86_64 build ve WSL2 → `scanimage` → `mfnp://192.168.0.62`. Skript: [`scripts/sane-test-build.sh`](../scripts/sane-test-build.sh).

## 2. Zjištěná fakta (ověřeno na zařízení)

| | |
|---|---|
| Protokol | **MFNP** — UDP + TCP **8610**. BJNP 8611/8612 neodpovídá. |
| Identita (GET_ID) | `MFG:CANON;CMD:MFNP1,MultiPASS 2.1;MDL:MF8000 Series;CLS:IMG;DES:Canon MF8000 Series;` |
| MAC | `00:1e:8f:38:f7:8c` (Canon OUI) |
| pixma | `generation 2 scanner` (PID MF8030 0x2707), backend 0.28.6 |
| **Patch** | `DEV ("Canon i-SENSYS MF8000 Series", "MF8000", MF8030_PID, 600, 0, 640, 877, 0)` za řádek MF8030 v `backend/pixma/pixma_imageclass.c` (1.4.0 ř. 963) |
| Config bez patche | nejde — `device iclass …` v `pixma.conf` je jen v masteru (commit `8058a8f8`, 2026-08), ne v 1.4.0 |

### ⚠️ Gotcha 1 — broadcast discovery „zasekne" firmware
Po **broadcast** discovery (SANE ji dělá defaultně na všech rozhraních vč. IPv6/docker0) přestane tiskárna
na **UDP 8610 odpovídat až do vypnutí/zapnutí**. TCP 8610 a tisk 9100 dál běží. Reprodukováno 2×:

| Běh | unicast | broadcast | potom |
|---|---|---|---|
| sonda (`mfnp_probe.py`) | ✅ | ne | ✅ další dotazy OK |
| `scanimage -L` | ✅ | ano | ❌ UDP ticho |
| po restartu, `scanimage` | ✅ | ano | ❌ ticho za 0,5 s |
| po restartu, `auto_detection=no` | ✅ | ne | ✅ UDP žije i po skenu |

→ **`auto_detection=no` v `pixma.conf` je povinné** a appka (vč. `DiscoveryEngine` BJNP prongu)
**nesmí posílat broadcast na 8610**. Jen unicast na známou IP.

### ⚠️ Gotcha 2 — „Remote Scanner" na panelu je nutný
Bez něj: open + status OK, ale `start scanning` → **`EBUSY`** („Device busy").
S **SCAN → Remote Scanner → OK** sken projde. → V appce: výzva před skenem + EBUSY přeložit na
„Na tiskárně zapněte SCAN → Remote Scanner".

### ✅ Opakované skeny bez restartu
Remote Scanner stačí zapnout **jednou** — displej zůstává „Remote Scanning Waiting (Online)" a skeny
jdou opakovaně (3× flatbed po sobě, ~10 s každý, bez dotyku panelu).

### ⚠️ Gotcha 3 — visící relace → `JOB_DETAILS` vrací chybu `0x8502`
Když relace neskončí UDP `CLOSE` (proces zabitý, nečisté ukončení; pravděpodobně i `scanimage --help`
nebo holé TCP connect sondy), tiskárna **odmítne každou další úlohu**:

| | odpověď na JOB_DETAILS (UDP 0x10) | bajty 6–7 (chyba) | session_id |
|---|---|---|---|
| OK | `4d464e50 82 10 0000 0002 0001 …` | `0000` | 1 |
| visí stará relace | `4d464e50 82 10 8502 0002 0000 …` | **`8502`** | 0 |

SANE (1.4.0) chybu **ignoruje**, pokračuje se session 0 a první TCP SEND dostane prázdné potvrzení
(`write confirmation = 0 bytes`) → `ETIMEDOUT`. Restart pomůže, ale **není nutný**:

**Odblokování (ověřeno):** poslat UDP `CLOSE` pro visící relaci (ID 1):
`4d464e50 02 11 0000 <seq> 0001 00000000` → tiskárna odpoví `82 11 0000 … 0001` a další sken projde.
→ **V patchi** (`scripts/sane-patch-mf8000.py`, patch 2): při chybě ≠ 0 `CLOSE` session 1 + JOB_DETAILS znovu. Ověřeno.

### ✅ Podavač (ADF) — kus podavač MÁ
Skutečný kus má ADF (v docs/09 chybně „bez ADF"). S `PIXMA_CAP_ADF` v patchi SANE nabídne
`--source Flatbed|Automatic Document Feeder` a `--adf-wait`. Ověřeno: 1 stránka (15 s) i dávka 2 stran
(`--batch`) → po poslední stránce `paper=0x01` → `abort session` + `CLOSE` čistě, `scanimage` skončí
„Document feeder out of documents" (= normální konec dávky).
- **Stav papíru:** `paper=0x00` = papír v podavači, `0x01` = prázdno.
- **Předčasné ukončení dávky** (např. `--batch-count=1` a v podavači zbývá list) → SANE neposílá
  `abort session` („wait for next page from ADF"), displej ukazuje „Scanning", po čase se sám vrátí
  na „Waiting". → Appka: při Zrušit vždy poslat `abort session` (iclass cmd 0xef20).
- **Zaseknutý papír:** první pokus se 2 listy skončil fyzickým zaseknutím. Tiskárna pak na
  `read_image2` vrací jen stav `06 06 07 00 05/03 …` bez dat a **SANE se zacyklí** (dotazuje se ~7 min,
  nereaguje na SIGINT). → **Do patche:** rozpoznat chybový stav / timeout bez dat → `abort session`
  + hláška „Zaseknutý papír v podavači".

## 3. Ověřovací nástroje

- [`scripts/mfnp_probe.py`](../scripts/mfnp_probe.py) — read-only unicast sonda (DISCOVER + GET_ID, TCP check).
  `python -I scripts/mfnp_probe.py 192.168.0.62`
- [`scripts/sane-test-build.sh`](../scripts/sane-test-build.sh) — build patchnutého SANE (x86_64) + testovací sken.
  `bash scripts/sane-test-build.sh scan 192.168.0.62 150`
- Debug: `SANE_DEBUG_PIXMA=3 SANE_DEBUG_BJNP=3`.

## 4. Plán dalších fází

1. **Fáze 1 — arm64 build do rootfs.** Rozšířit `scripts/build-bundle.sh`: cross/qemu build sane-backends
   1.4.0 + patch, jen `BACKENDS=pixma`, `--without-usb/snmp/avahi`. Do rootfs `scanimage` + `libsane*`
   + `pixma.conf` (`auto_detection=no`, IP doplní appka). Odhad +2–4 MB. Runtime závislosti: libc, libm, libjpeg.
2. **Fáze 2 — `ProotLauncher.runScan(ip, dpi, mode)`.** Stejné env jako tisk (`PROOT_NO_SECCOMP=1`),
   `SANE_CONFIG_DIR` s vygenerovaným `pixma.conf`, výstup JPEG/PNM do `cacheDir`. Timeout (600 dpi barva = minuty).
3. **Fáze 3 — UI.** `ScanActivity`: IP z `PrinterStore`, výzva „položte stránku + Remote Scanner",
   **volba zdroje Sklo / Podavač** (podavač = celá dávka naráz, sklo = smyčka „Další stránka / Hotovo"),
   volba dpi (150/300/600) a barva/šedá → `PdfDocument` (JPEG) →
   `MediaStore.Downloads` + sdílení `ACTION_SEND`.

Alternativa B (nativní MFNP v Kotlinu bez proot) zůstává záložní — protokol je teď prokázaný, ale
port ~2 týdny; má smysl jen kdyby proot cesta na telefonu narazila.

## 5. Otevřené

- [x] ~~ADF~~ → kus má podavač, sken z ADF ověřen (1 i 2 strany).
- [x] ~~Víc skenů za sebou~~ → ano, Remote Scanner drží (Waiting/Online).
- [x] ~~Patch SANE~~ → [`scripts/sane-patch-mf8000.py`](../scripts/sane-patch-mf8000.py) (idempotentní, na čistý 1.4.0):
  (1) alias MF8000 + `PIXMA_CAP_ADF`, (2) JOB_DETAILS status ≠ 0 → `CLOSE` session 1 + retry
  — **ověřeno živě** (úmyslně visící relace → `job refused (0x8502)` → close → retry OK),
  (3) watchdog: 30 s bez obrazových dat → `PIXMA_EPAPER_JAMMED` (ADF) / `ETIMEDOUT` — zkompilováno, **živě neověřeno** (chce zaseknutí).
- [ ] Remote Scanner **sám vyprší** (po nějaké době nečinnosti → `EBUSY`). Změřit timeout; v appce při EBUSY výzva „Zapněte znovu Remote Scanner".
- [ ] Zjistit, co přesně zablokovalo relaci po 1. úspěšném skenu (`--help` vs. holé TCP sondy) — appka nedělá ani jedno.
- [ ] Ověřit 300/600 dpi a režim Gray (rychlost, artefakty).
- [ ] Jak dlouho drží „Remote Scanner" (timeout na panelu?).
- [ ] `DiscoveryEngine` BJNP prong — ověřit, že neposílá broadcast na 8610 (Gotcha 1).
- [ ] **Tisk:** volba zásobníku papíru (kazeta / ruční podávání) — PPD `InputSlot`? (samostatný úkol)
