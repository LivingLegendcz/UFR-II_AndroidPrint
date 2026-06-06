# 04 - Zvolené řešení: architektura (self-contained APK, bez Termuxu)

> **Aktualizace po rozboru ovladače** ([07-ovladac-rozbor.md](07-ovladac-rozbor.md)):
> ovladač v6.30 je **self-contained arm64** (nepotřebuje Ghostscript/OPVP) a má malý
> závislostní closure → můžeme ho zabalit přímo do vlastní APK a **vyhnout se Termuxu**.

## Idea v jedné větě

Naše APK **zabalí kompletní Canon arm64 ovladač + glibc knihovny + statický `proot`**,
při prvním spuštění je rozbalí do svého privátního adresáře a sama spustí filtr `rastertoufr2`,
který vyrenderuje UFR-II. Výsledek pošle přes TCP socket na tiskárnu `:9100`.
**Žádný Termux, žádný proot-distro, žádná externí appka, žádný cupsd démon.**

## Proč bez Termuxu (požadavek uživatele)

Termux je jen prostředí, kde běží glibc binárky. Závislost na něm je křehká (projekt může
"umřít", uživatel ho musí zvlášť instalovat z F-Droid). Protože je Canon ovladač self-contained
a glibc closure malý, **zabalíme runtime do vlastní APK** → appka je plně samostatná a přežije
osud Termuxu. `proot` zabalíme jako zmrazený statický binár (i kdyby upstream zanikl, naše kopie
funguje).

## Pipeline (vše uvnitř naší APK)

```
PDF / obrázek
  → Android PdfRenderer → bitmapa (600 dpi, po pásech)
  → zápis do CUPS raster formátu (application/vnd.cups-raster)
  → proot → /usr/lib/cups/filter/rastertoufr2  (Canon arm64 filtr + libs + color data)
  → UFR-II byty
  → java.net.Socket na 192.168.0.62:9100, write, flush, close
  → Canon MF8030Cn tiskne
```

> PDF→raster děláme sami z PdfRenderer bitmap (nepotřebujeme pdftoraster/gs). Přesný formát
> CUPS rasteru (hlavička + data) se odladí ve Fázi 1. Alternativně Canon `cnpdfdrv`/`pdftocpca`.

## Co APK obsahuje (bundle v assets/)

| Komponenta | Zdroj | ~Velikost |
|---|---|---|
| `rastertoufr2`, `pdftocpca`, backend | Canon deb `/usr/lib/cups/` | ~50 KB |
| Canon `.so` knihovny | Canon deb `/usr/lib/libcanon*`, `libColorGear*` … | ~2 MB |
| Color data `caepcm/ufr2` (ořezané na MF8000C) | Canon deb `/usr/share/caepcm/` | ~10–60 MB |
| PPD `CNRCUPSMF8000CZK.ppd` | Canon deb `/usr/share/cups/model/` | malé |
| glibc + loader + libcups2/libcupsimage2/libjpeg62/libjbig0/libgcrypt20 | Debian arm64 .deb | ~15 MB |
| `proot` (statický aarch64 binár) | proot-distro / vlastní build | ~1 MB |

→ APK řádově **20–80 MB** (dle ořezu color dat). Pro sideload v pohodě.

## Vrstvy

| Vrstva | Co to je | Kde běží |
|---|---|---|
| UI | Kotlin appka: file/share picker, nastavení IP, status | Android (ART) |
| Orchestrace | unpack bundle, sestavit raster, spustit proot+filtr, socket | Android NDK / Runtime.exec |
| Runtime | `proot` + glibc rootfs (jen potřebné liby + Canon driver) | privátní filesDir appky |
| Renderer | `rastertoufr2` + Canon `.so` + color data | pod proot |
| Tiskárna | Canon MF8030Cn | LAN `:9100` |

## Ověřené technické fakty

| Věc | Stav |
|---|---|
| Binárky ovladače | ✅ **AArch64 (arm64)** - potvrzeno z ELF hlaviček |
| Ghostscript/OPVP | ✅ **NEpotřeba** - `cupsFilter: ... rastertoufr2` (self-contained) |
| Závislosti | cups, libcups2, libcupsimage2, libjpeg62, libjbig0, libgcrypt20 (žádný gs) |
| PPD MF8030Cn | `CNRCUPSMF8000CZK.ppd` (MF8000C Series UFRII LT, 600 dpi) |
| Síť | outbound TCP :9100 → žádný server, žádné inbound problémy |
| Telefon | **device-agnostic** - libovolný **arm64** Android, přežít výměnu telefonu; stavět na nejpřísnější (A16/16KB stránky) |

## Role Termuxu = pouze vývojová (volitelná)

Termux + proot-distro Debian může posloužit jako **dev scaffold** k odladění přesného volání
filtru (args, env proměnné, formát CUPS rasteru) - viz [05-navod-setup.md](05-navod-setup.md).
Jakmile chain funguje, zapečeme ho do APK a **koncový uživatel Termux nepotřebuje**.

## Rizika

> ⚠️ Plný rozbor rizik + opravená roadmapa s bránami: **[08-review-rizika-roadmap.md](08-review-rizika-roadmap.md)**.

| Riziko | Závažnost | Mitigace |
|---|---|---|
| **Android zákaz exec z filesDir** (Android 10+, W^X/SELinux) | 🔴 P0 | proot zabalit jako **`libproot.so` v `jniLibs`** (ne unpack+exec z filesDir); proot pak přes ptrace spouští guest binárky. Ověřit spikem (Fáze 2). |
| **16KB stránky na nejnovějších arm64** (A15/16) | 🟠 | zabalený `proot` + glibc loader musí být **16KB-page-safe**; detekovat page size za běhu (device-agnostic) |
| **Generování CUPS rasteru** (správný header dle PPD) | 🔴 P0 | filtr chce **RGB vstup** (`DefaultColorSpace: RGB`) → PdfRenderer RGB stačí, žádná vlastní CMYK konverze; zachytit **referenční raster** ze scaffoldu a replikovat |
| `rastertoufr2` env contract (PPD, CUPS_DATADIR, RIP_CACHE, argv, color data na cestách) | 🟠 | nafejkovat env; PPD + caepcm na napevno daných cestách |
| „self-contained" ověřeno jen z PPD řádky | 🟠 | potvrdit `strace -f` reálné úlohy (manifest souborů) |
| proot na novém Androidu (seccomp) | 🟡 | `PROOT_NO_SECCOMP=1`; zmrazený statický proot binár |
| glibc closure neúplný | 🟡 | `ldd` nad filtry → dotáhnout chybějící `.so` |
| Velikost APK (color data) | 🟡 | ořezat caepcm na MF8000C podmnožinu (dle strace) |
| Licence (bundlování Canon ovladače) | 🟡 | **jen personal/sideload**, neredistribuovat → ne Play Store |

**Barva/mono = výběr při tisku** (požadavek): jeden barevný PPD `CNRCUPSMF8000CZK.ppd` + option
**`CNColorMode=color|mono`** předaná filtru. Dev pořadí: validovat mono, pak barvu - ale finální
appka přepíná oba režimy per-job (detail [08-review-rizika-roadmap.md](08-review-rizika-roadmap.md)).
