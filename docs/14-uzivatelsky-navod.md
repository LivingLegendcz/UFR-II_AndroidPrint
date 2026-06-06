# 14 - Uživatelský návod: CanonPrint

Jak nainstalovat aplikaci a tisknout na Canon i-SENSYS MF8030Cn z Android telefonu.

---

## Požadavky

| Požadavek | Detail |
|---|---|
| Telefon | arm64 (aarch64), Android 10 nebo novější |
| Ověřeno na | Samsung Galaxy S24+ (SM-S926B), Android 16 |
| Soubor APK | `CanonPrint-0.3-phase3.apk` (~102 MB) |
| Síť | Telefon na **stejné WiFi** jako tiskárna |
| IP tiskárny | **192.168.0.62** (natvrdo v kódu, port 9100) |

---

## 1. Instalace (bez adb, bez počítače)

1. Zkopírujte soubor `CanonPrint-0.3-phase3.apk` do telefonu (přes kabel, cloud úložiště, AirDrop apod.).
2. V nastavení telefonu povolte **Instalace z neznámých zdrojů** pro správce souborů nebo prohlížeč, přes který APK otevřete.
   - Android 8+: Nastavení → Aplikace → (váš správce souborů) → Instalace neznámých aplikací → Povolit.
3. Otevřete APK soubor ve správci souborů a potvrďte instalaci.
4. Po instalaci najdete aplikaci **CanonPrint** v seznamu aplikací.

---

## 2. Síť

Tiskárna musí být dostupná na IP adrese **`192.168.0.62`**, port `9100`.
Tato adresa je natvrdo zakódována v `ProotLauncher.kt` (parametr `printerIp = "192.168.0.62"`).
Telefon musí být na **stejné WiFi** jako tiskárna - tisk přes mobilní data nebo jiné podsítě nefunguje.

> Konfigurovatelná IP je plánována jako polish po Phase 3 (viz README).

---

## 3. Použití

### Rychlý test (ověření, že tisk funguje)

1. Otevřete aplikaci **CanonPrint**.
2. Vyberte barevný režim: **Barva (color)** nebo **Černobíle (mono)**.
3. Stiskněte **TEST TISK (vestavěné PDF)**.
4. Sledujte log - viz sekci [Log a diagnostika](#5-log-a-diagnostika).

Aplikace:
- zkopíruje vestavěné `sample.pdf` do cache,
- rozbalí rootfs (první spuštění - viz níže),
- spustí `rastertoufr2` pod proot,
- odešle UFR-II stream na `192.168.0.62:9100`.

### Tisk PDF z jiné aplikace

1. V jiné aplikaci (prohlížeč, Files, čtečka PDF apod.) otevřete dokument.
2. Použijte **Sdílet** → vyberte **CanonPrint** ze seznamu.
3. V CanonPrint vyberte **Barva / Černobíle**.
4. Stiskněte tlačítko **TISK: &lt;název souboru&gt;**.

Aplikace přijme soubor přes `ACTION_SEND` (sdílení) nebo `ACTION_VIEW` (otevření přidružené aplikace).

### Tisk obrázků (experimentální)

Aplikace deklaruje v manifestu příjem `image/*` přes sdílení a obsahuje implementaci `printImage()`.
Tisk obrázků **nebyl fyzicky ověřen** - používejte na vlastní riziko a ověřte výsledek.
Pokud obrázek selže, zkuste ho nejprve převést na PDF v jiné aplikaci a tisknout jako PDF.

---

## 4. První spuštění - rozbalení rootfs

Při prvním spuštění (nebo po aktualizaci APK) aplikace rozbalí `rootfs.tar` (~50 MB) z assets do interního úložiště. Tato operace trvá **přibližně 30–60 sekund** v závislosti na rychlosti telefonu. Průběh je vidět v logu (`rootfs: …`).

Při dalších spuštěních kontrola verze (sha256) proběhne rychle a rootfs se znovu nerozbaluje.

---

## 5. Log a diagnostika

### Blok VÝSLEDEK

Po tisku se v logu zobrazí blok:

```
VÝSLEDEK:
=== TISK PDF (rastertoufr2) ===
colorMode=COLOR  printer=192.168.0.62:9100
exitCode=0  elapsed=42000ms  timedOut=false
--- raster generátor ---
OK, 96288384 bajtů zapsáno (očekáváno 96288384 na stránku)
UFR-II výstup: socket OK: 852992 UFR-II bajtů odesláno na 192.168.0.62:9100
--- stderr (posledních N řádků) ---
bidiCommon.c: bidiCommon() err=0
```

| Položka | Co znamená |
|---|---|
| `exitCode=0` | Filtr `rastertoufr2` skončil úspěšně - stránka vytištěna. |
| `exitCode≠0` | Filtr selhal - příčina je ve stderr sekci níže. |
| `rasterBytesFed` | Počet bajtů CUPS rasteru předaných filtru (očekáváno ~96 MB na stránku A4). |
| `UFR-II bajtů odesláno` | Počet bajtů odeslaných na tiskárnu (~833 KB barva, ~269 KB mono). |
| `socket OK` | Data úspěšně odeslána na tiskárnu. |
| `bidiCommon.c: bidiCommon() err=0` | **Nezávadné** - hlásí to vždy Canon ovladač; ignorujte. |

### Tlačítko Kopírovat log

Tlačítko **Kopírovat log** zkopíruje celý obsah log boxu do schránky. Užitečné pro sdílení při ladění.

### Tlačítko Diagnostika

Spustí sérii sond: přímý exec (`libtest_hello.so`), ptrace probe (`libptraceprobe.so`), proot test, cnjbig probe. Jde o **vývojářský nástroj** - pro normální tisk ho ignorujte. Použijte ho, pokud tisk vůbec nespustí proot nebo se zhroutí před fází rasteru.

---

## 6. Řešení potíží

### Tiskárna není dostupná

**Příznaky v logu:**
```
socket ConnectException: Connection refused
  >> Zkontroluj WiFi a IP tiskárny 192.168.0.62
```
nebo
```
socket Timeout: connect timed out
  >> Tiskárna nereaguje na 192.168.0.62:9100
```

**Co zkontrolovat:**
- Je telefon připojen na **správnou WiFi** (stejná síť jako tiskárna)?
- Je **tiskárna zapnutá** a má zelenou / pohotovostní světlo?
- Je IP tiskárny opravdu `192.168.0.62`? (Zkontrolujte na displeji tiskárny nebo přes admin web `192.168.0.62:8080`.)
- Timeout připojení je **5 sekund** - pokud tiskárna teprve nabíhá, zkuste znovu za chvíli.

### exitCode ≠ 0

Filtr `rastertoufr2` skončil s chybou. Příčina je ve stderr sekci v bloku VÝSLEDEK.
Typické příčiny: chybějící soubor v rootfs, špatná verze rootfs po aktualizaci APK (aplikace by to měla detekovat automaticky a rootfs znovu rozbalit).

### Celkový timeout (timedOut=true)

Filtr nedoběhl do **120 sekund**. Může se stát při extrémně pomalém telefonu nebo vícestránkovém dokumentu. Zkuste tisk znovu; pokud se opakuje, kontaktujte vývojáře.

### Aplikace nevidí CanonPrint v nabídce Sdílet

Zkontrolujte, zda je aplikace nainstalována. Některé aplikace omezují cíle sdílení - zkuste jiný způsob otevření souboru (správce souborů → Otevřít s…).

---

*Viz [docs/13-phase3-vysledky.md](13-phase3-vysledky.md) pro technické detaily pipeline a debugovací metodiku.*
