# 21 - Stav a schopnosti tiskárny (SNMP)

**Datum:** 2026-10-23
**Stav:** průzkum hotov (živě ověřeno na MF8030Cn, ~93 unicast SNMP GET), implementace čeká (UI etapa D1/D3).

Cíl: appka po přidání tiskárny sama zjistí, **co tiskárna umí** (barva, zásobníky, duplex, sken/ADF),
a průběžně čte **stav** (tonery, papír) pro kartu tiskárny a push notifikace.

---

## 1. Zdroj: SNMP v1/v2c, komunita `public`, UDP 161

Jediná praktická cesta. Port 80 (Remote UI), IPP, 515, 631 zavřené. Bidi kanál ovladače Canon
(`cnpkbidir`, CPCA) čte jen kalibrační data, ne stav. MFNP (8610) nese jen stav ADF skeneru.

⚠️ Pravidla provozu: **jen unicast** na IP tiskárny, jen GET/GETNEXT, timeout 2 s, 1–2 pokusy.
Nikdy broadcast a nic na 8610–8612 mimo skenování (viz [docs/20](20-skenovani-navrh.md), Gotcha 1).

Implementace: SNMPv1 GET s více varbindy v jednom paketu, vlastní BER enkodér (~100 řádků Kotlinu
nad `DatagramSocket`), bez snmp4j.

## 2. Schopnosti (číst při přidání tiskárny, cache)

| Schopnost | OID | MF8030Cn | Spolehlivost / fallback |
|---|---|---|---|
| Model | `sysDescr` 1.3.6.1.2.1.1.1.0, `hrDeviceDescr` .25.3.2.1.3.1 | `Canon MF8030 /P`, `Canon MF8030` | vysoká; přípona Cn/Cdn/Cw se nedá zjistit |
| Barva / mono | `prtMarkerSuppliesDescription` .43.11.1.1.6.1.x | 4 tonery `Canon CRG316 Black/Yellow/Magenta/Cyan Toner` | vysoká; pravidlo: popis obsahuje Cyan/Magenta/Yellow → barva. `prtMarkerColorantTable` (.43.12) **není** |
| Zásobníky | `prtInputType` .43.8.2.1.2, `Name` .13, `MaxCapacity` .9 | idx1 = 5 `Stack Bypass` (ruční, 1 list), idx2 = 4 `Drawer1` (kazeta, 150 listů) | vysoká; mapování na PPD `InputSlot`: `Manual` / `Cas1` (+ `Auto`) |
| Formát v zásobníku | `prtInputMediaName` .43.8.2.1.12 | prázdné; Declared = max (Legal) | nezjistitelné → formáty z PPD |
| Duplex | `prtMediaPathType` .43.13.4.1.9 | jediná dráha, 5 = simplex | vysoká pro tento kus; PPD navíc duplex nenabízí |
| Výstup | `prtOutput` .43.9.2.1 | `Tray1`, 125 listů | — |
| Sken + ADF | SNMP **nic** (hrDeviceTable = jen printer) | MFNP `GET_ID` → `CLS:IMG`; ADF ověřen ručně | tabulka modelů + jednorázový dotaz uživatele; při „no paper in ADF" nabídnout sklo |
| Počitadlo stran | `prtMarkerLifeCount` .43.10.2.1.4.1.1 | 53 739 | info na kartu / do detailu |

## 3. Stav (číst při otevření appky, po tisku, periodicky)

| Údaj | OID | Naměřeno | Ověřeno? |
|---|---|---|---|
| Toner % | `prtMarkerSuppliesLevel` .43.11.1.1.9.1.x / MaxCapacity .8 (=100), jednotka .7 = 19 (%) | 100/100/100/100 | čte se; **pokles neověřen** (granularita?) |
| Pořadí tonerů | indexy 1–4 | **K, Y, M, C** (ne CMYK!) | barvu brát z popisu `.6` |
| Papír v zásobníku | `prtInputCurrentLevel` .43.8.2.1.10.1.x | ruční = 0 (prázdný), kazeta = −3 („něco zbývá") | kazeta = 0 jako „došel" **neověřeno** |
| Stav vstupu | `prtInputStatus` .43.8.2.1.11.1.x | 1 / 0 | bity nedekódovány |
| Chyby (jam, kryt, papír, toner) | `hrPrinterDetectedErrorState` .25.3.5.1.2.1 | `00000000` | **neověřeno**, zda Canon bity nastavuje |
| Stav tiskárny | `hrPrinterStatus` .25.3.5.1.1.1 | 1 (other) | busy/sleep nerozlišeno |
| Alerty, kryty, LCD | .43.18 / .43.6 / .43.16 | neimplementováno | — |

### Ruční test chybových stavů (2026-10-23, ověřeno)

| Akce | `prtAlertTable` (.43.18.1.1) | hrDeviceStatus | hrErrorState | vstup level / status |
|---|---|---|---|---|
| klid | prázdná | 2 running | `00000000` | ruční 0/1, kazeta −3/0 |
| **kazeta vytažena** | kód **808** inputMediaSupplyEmpty, severity 4 (warning) | **3 warning** | `10000000` | kazeta **0/1** |
| **přední kryt otevřen** | kód **3** coverOpen, severity **3 (critical)** | **5 down** | `00001000` | — |
| **zadní kryt otevřen** | kód **3** coverOpen, severity 3, location 1 — **shodné s předním** | 5 down | `00001000` | — |
| kryt zavřen + list v ručním podavači | prázdná (alert zmizí sám) | 2 | `00000000` | ruční **−3/0** |

**Závěry:**
- ✅ **`prtAlertTable` je hlavní zdroj** — standardní kódy (RFC 3805 `prtAlertCode`), hlásí jen aktivní
  stavy, po odstranění zmizí. Mapovat: 3/5 kryt/interlock, 8 jam, 801/808 zásobník chybí/prázdný,
  1101–1104 toner (ověřit až při skutečném nízkém toneru).
- ⚠️ **`hrPrinterDetectedErrorState` Canon nastavuje nestandardně** (kazeta → bit „noToner", kryt → bit
  „outputNearFull" dle RFC) → **nepoužívat** pro význam, nanejvýš jako „něco se děje".
- `prtInputStatus` (.11): 0 = OK, 1 = prázdný/chybí. `prtInputCurrentLevel`: 0 prázdný, −3 „něco je".
- Přední a zadní kryt **nejdou rozlišit** (stejný kód, location, groupIndex) → text „Otevřený kryt tiskárny".
- Zaseknutý papír a nízký toner zatím neověřeny (nelze bezpečně nasimulovat).

## 6. UX požadavky z testu (plán)
- **Ruční duplex (D2, V4):** tiskárna nemá duplex → při volbě oboustranného tisku: liché strany →
  průvodce s obrázkem a textem, jak list vrátit (strana nahoru/dolů, záhlaví/zápatí napřed) zvlášť pro
  **kazetu** a **ruční podavač** → sudé strany v obráceném pořadí. Orientaci **nehádat**: jednorázový
  kalibrační tisk (šipka „TATO STRANA / HORNÍ OKRAJ"), uživatel potvrdí výsledek, uložit profil per
  tiskárna × zásobník.
- **Pokyny ke skenu (D3):** sklo „textem dolů, horní okraj k šipce v rohu", podavač „textem nahoru,
  horním okrajem napřed" + ilustrace; roh skla ověřit testovacím skenem (případně auto-rotace).
- **Počitadla po barvách tiskárna NEMÁ** (celý Canon MIB 1602 prošlý) → jen celkový `prtMarkerLifeCount`.
  Rozhodnutí uživatele: žádné dopočty, spoléhat jen na hladinu toneru v %.

### ADF discovery bez katalogu (ověřeno)
SNMP ADF neukazuje a model nerozliší Cn/Cdn/Cw → **zjistit pokusem**: stav skeneru (MFNP `query_status`,
v SANE `--source ADF`, bez Remote Scanner → nic se neskenuje):

| podavač | stav | výsledek |
|---|---|---|
| list vložen | `paper=0x00` | `EBUSY` (sken nezačne bez Remote Scanner) |
| prázdný | `paper=0x01` | `ENO_PAPER` „Document feeder out of documents" |

Postup v appce: přečíst stav → „Vložte list do podavače dokumentů" → přečíst znovu; změna 0x01→0x00
= ADF potvrzen, jinak „Tiskárna podavač nemá".

### Typ toneru
Popis `.43.11.1.1.6` = `Canon CRG316 <Barva> Toner`. CRG-316 = japonské značení; v EU se prodává jako
**Canon Cartridge 716** (Bk/C/M/Y) — *ověřit při implementaci*. U toneru tlačítko **Kopírovat**
(text pro vyhledání nákupu).

## 4. Push notifikace (návrh)

- Čtení: při otevření appky, **po každém tisku**, WorkManager periodicky 15–30 min (timeout 2 s, tiché
  selhání mimo síť / ve spánku). Foreground služba kvůli stavu ne (baterie).
- Notifikace **jen při změně**: toner ≤ 15 % a ≤ 5 % (s hysterezí), došel papír, chyba (pokud test potvrdí).
- Doze/Wi-Fi sleep → zpoždění v řádu desítek minut; „dochází toner" nebude okamžité.
- Android 16+: ověřit oprávnění pro místní síť (`ACCESS_LOCAL_NETWORK` / `NEARBY_WIFI_DEVICES`).

## 5. Postup při přidání tiskárny (D1)

1. „Otestovat spojení" = TCP 9100 + SNMP dávka (model, tonery, zásobníky, media path).
2. Odvodit barva / zásobníky (→ volba `InputSlot` při tisku) / duplex.
3. Sken + ADF: tabulka modelů (MF8030/8050/8080) + jednorázový unicast MFNP `GET_ID`; co nejde zjistit,
   uložit jako „odhad" a nechat uživatele opravit.
4. Cache; znovu číst jen při změně `sysDescr` nebo na žádost.
