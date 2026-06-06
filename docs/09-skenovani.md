# 09 - Skenování (nice-to-have)

> **Status: nice-to-have, NEplánováno aktivně.** Priorita je tisk. Sken je samostatný problém
> (není symetrický s tiskem) a řešil by se až po dokončení tiskové části. Tento dokument
> shrnuje průzkum (3 agenti, 2026-06-03), aby se nemuselo bádat znovu.

## Verdikt

| | |
|---|---|
| Zařízení | MF8030Cn = flatbed multifunkce (sken + kopie + tisk), **bez ADF** |
| **USB sken (Linux)** | ✅ Funguje - SANE `pixma`, model `MF8030`, PID `0x2707`, status `:complete` |
| **Síťový sken (Linux)** | ⚠️ **Rozbitý out-of-the-box**, ale protokol prokazatelně funguje → opravitelné patchem |
| Standardy eSCL/AirScan/Mopria Scan | ❌ Zařízení z 2010 nemá |
| WSD-Scan | ❓ Nepotvrzeno, nepravděpodobné |
| Canon arm64 sken ovladač | ❌ Neexistuje (`scangearmp2` je jen pro inkousty/PIXMA) |

**Tisk ≠ sken.** Pro tisk máme hotový Canon arm64 UFR-II ovladač. Pro sken **žádný Canon arm64
ovladač není** - ale open-source SANE `pixma` tenhle model zná (přes USB) a protokol je dosažitelný.

## Jádro problému (potvrzeno reálným reportem)

sane-devel thread **„Canon iSENSYS MF8030Cn support"** (2020) - přesně tenhle model:
- Tisk po síti fungoval, **sken selhal**:
  `"Scanner MF8000 Series is not supported, model is unknown!"`
- Příčina: tiskárna se po síti hlásí jako **„MF8000 Series"**, ale `pixma` tabulka zná jen jméno
  **„MF8030"** (zjištěné přes USB). Jména nesedí → síťová identita/discovery se odmítne.

**Důkaz, že to jde:** **VueScan** (komerční, closed-source) skenuje MF8030 **přes síť i na Linuxu**.
Podmínka: před skenem na displeji tiskárny **SCAN → „Remote Scanner" → OK**. Tedy blokuje jen
SANE model-matching, ne samotné zařízení/protokol.

## Protokol

- Laser MF řada: **MFNP (UDP 8610)**; starší/inkousty: **BJNP (UDP 8612)**. SANE `pixma` umí oba.
- Discovery = broadcast na UDP 8612 (sken) / 8610 (MFNP). Odpověď nese IEEE-1284 device-ID
  s polem `MDL:` (model) - tady přijde `MDL:MF8000 Series`.
- BJNP discovery paket = 16-byte hlavička: `42 4A 4E 50 | 02(scanner) 01(discover) | 0000 | 0001 | 0000 | 00000000`.

## Cesta k řešení (kdyby se sken dělal)

1. Zabalit **SANE + `pixma`** do proot (stejný model jako tisk; `pixma` je open-source C → arm64 OK).
   Je to vlastně **čistší než tisk** - žádná proprietární binárka, jen `sane-utils` + `libsane`.
2. **Patchnout `pixma`**: aliasovat `MF8000 Series` → konfiguraci `MF8030` (model-string guard),
   `mfnp://192.168.0.62` do `/etc/sane.d/pixma.conf`.
3. Ověřit, že **datová cesta doběhne** (může chtít víc než jen alias - protokolová práce).
4. `scanimage -L` → `scanimage --format=png > out.png` → PDF → soubor do sdíleného úložiště → appka.

**Odhad proveditelnosti síťového skenu: ~50–60 %.** Protokol funguje (VueScan), ale chce patch
SANE + nejistota datové cesty. Porting/debug projekt, ne plug-and-play.

## UX a omezení

- Pravděpodobně nutné **před každým skenem** na tiskárně zmáčknout „Remote Scanner" (per VueScan).
- **Flatbed-only** (bez ADF) → multi-page = opakovaný sken + stitching do PDF v appce.
  (Pozn.: `pixma` tabulka u MF8030 uvádí `PIXMA_CAP_ADF`, ale reálné zařízení ADF nemá - ignorovat.)

## Živá sonda (spustit, AŽ budeš u tiskárny na stejné síti)

```bash
# go/no-go pro síťový sken: odpovídá? na jakém portu? jaký model string?
nmap -sU -p 8610,8612 --script bjnp-discover 192.168.0.62
```
Když odpoví na 8610 → MFNP; na 8612 → BJNP. `MDL:` potvrdí „MF8000 Series".
(Read-only sonda - neprovedena, uživatel byl mimo síť tiskárny.)

## Fallback

USB tiskárny do **Raspberry Pi / x86 Linux** s `saned`, sdílet sken po síti. „Nudné, ale funguje"
(stejný princip jako tiskový bridge fallback v [08](08-review-rizika-roadmap.md)).

## Zdroje

- sane-devel: MF8030Cn support - https://www.mail-archive.com/sane-devel@alioth-lists.debian.net/msg01855.html (+ reply msg01856)
- sane-pixma(5) - https://manpages.ubuntu.com/manpages/noble/man5/sane-pixma.5.html
- VueScan MF8030 (síť+Linux, „Remote Scanner") - https://www.hamrick.com/vuescan/canon_mf8030.html
- BJNP protokol / nmap bjnp-discover - https://github.com/nmap/nmap/blob/master/scripts/bjnp-discover.nse
- scangearmp2 (jen inkousty) - https://github.com/ThierryHFR/scangearmp2
- sane-airscan (eSCL/WSD) - https://github.com/alexpevzner/sane-airscan
