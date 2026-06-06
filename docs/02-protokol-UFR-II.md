# 02 - Protokol UFR-II a Canon ovladač

## Co je UFR-II LT

**UFR-II (Ultra Fast Rendering II), varianta "LT" (Lite)** = proprietární **host-based** tiskový
jazyk Canonu. "Host-based" znamená, že **veškeré renderování dělá ovladač na počítači** -
tiskárna je jen "hloupý" engine, který dostane hotová komprimovaná raster data. Tiskárna sama
neumí interpretovat PDF/PostScript/PCL.

- SNMP hlásí `prtInterpreterLangFamily = other (1)` → potvrzuje proprietární jazyk.
- MF8030Cn je barevná → UFR-II LT pro ni přenáší i barvu (CMYK), neredukuje na ČB.

> ⚠️ **Pozn.:** Tato sekce popisuje **starší generaci** Canon ovladače (gs/OPVP). Rozbor
> reálně použitého balíku **v6.30 arm64 (docs/07)** ukázal, že shipped ovladač je
> **self-contained přes `rastertoufr2`** a tuhle gs/OPVP cestu **nepoužívá**. Ber níže jako kontext.

## Jak Canon ovladač funguje (Linux pipeline)

```
Aplikace (PDF/PS)
  → CUPS
  → pdftops / pdftopdf            (standardní CUPS filtry)
  → pstoufr2cpca                  ← GPL "glue" filtr (OPEN, github vicamo/cndrvcups-lb)
       └─ spustí: gs -sDEVICE=opvp -sDriver=libcanon*ufr2 -sModel=MF8000CSeries ...
              └─ Ghostscript "opvp" device (GPL, v Ghostscriptu)
                     └─ dlopen() libcanon*ufr2.so   ← UZAVŘENÝ Canon blob = TADY vznikají UFR-II byty
  → CUPS backend (socket / lpd)   → tiskárna :9100
```

**Klíčové:** Otevřená je jen ta "lepící" vrstva (`pstoufr2cpca` + Ghostscript opvp device).
Vlastní generování UFR-II bytů je v **uzavřené binární knihovně `libcanon*ufr2.so`**
(rodina `libcanon_slimufr2.so`, `libColorGearCufr2.so`, …). Pipeline NENÍ self-contained -
**vyžaduje Ghostscript s OPVP device** (`gs -h | grep opvp` musí vypsat `opvp oprp`).

## Proč reverse-engineering nedává smysl

Zvažovali jsme reimplementaci UFR-II encoderu přímo v Android appce. Zamítnuto:

- **Žádná open-source implementace UFR-II neexistuje** (na rozdíl od CAPT/CARPS).
- ~**80 % encoderu** by se muselo odvodit z odchycených streamů (všechny opcody, komprese, barvy).
- **Barva (CMYK) UFR-II nebyla nikdy veřejně rozluštěna** - CMYK plane layout je neznámý.
- `captdriver` (jediný clean-room Canon RE) umí jen **CAPT**, ne UFR-II, a jen ČB LBP tiskárny.
- Velké úsilí, nejistý výsledek, právně sporné.

→ Místo RE použijeme **reálný Canon ovladač** (viz [03-zvazovane-cesty.md](03-zvazovane-cesty.md)).

## Užitečné reference pro pochopení Canon formátů

- **CARPS** (`ondrej-zary/carps-cups`) - kompletně rozluštěný starší Canon raster formát
  (spec `carps.txt` + encoder/decoder). Nejlepší veřejný blueprint Canon idiomu:
  ESC-bracket grammar (`ESC [ params 'písmeno`), strip/band raster, proprietární bit-level
  komprese + CCITT-G4 fallback, XOR `0x43` obfuskace, blokový kontejner po 4096 B.
- **captdriver** (`agalakhov/captdriver`) - clean-room RE Canon **CAPT** (jiný protokol),
  má `SPECS` soubor. Mentální model komprese/pásů, ale UFR-II to NEpohání.
- **vicamo/cndrvcups-lb** - Canon GPL glue + PPD `CNCUPSMF8000CZS.ppd`
  (potvrzuje `opvpDriver=libcanon*`, 600 dpi, color/mono).

> Pozn.: Tyto reference jsou jen pro pochopení - ve zvoleném řešení je NEpotřebujeme,
> protože renderuje přímo oficiální Canon ovladač.
