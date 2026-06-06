# 03 - Zvažované cesty a rozhodnutí

Přehled všech přístupů, které jsme zvažovali, a proč padly nebo vyhrály.
Tiskárna mluví **jen UFR-II LT** → to vyřazuje většinu standardních cest.

## Klíčové omezení (proč to není triviální)

Pro UFR-II-only tiskárnu nejdou mít **současně** všechny tři věci:
1. samostatná appka na telefonu, co tiskne sama,
2. žádný další zapnutý stroj,
3. žádný reverse engineering.

Jedno musí padnout. → Řešení obchází dilema tím, že **telefon SÁM je ten "Linux stroj"**,
na kterém běží reálný Canon ovladač (žádný RE, žádný extra stroj).

## Tabulka cest

| # | Přístup | Verdikt | Důvod |
|---|---|---|---|
| 1 | JetDirect raw - poslat PCL/PostScript na :9100 | ❌ | Tiskárna nezná PCL ani PS (jen UFR-II) |
| 2 | IPP (cups4j) / Mopria / AirPrint | ❌ | Tiskárna nemá IPP (port 631 zavřený), žádný driverless tisk |
| 3 | Reverse-engineering UFR-II nativně v appce | ❌ (uživatel) | "Blbost"; ~80 % se musí odvodit z captures, barva nikdy nerozluštěna |
| 4 | Windows print-server bridge (PC renderuje, app posílá PDF) | ❌ (uživatel) | "Overkill, žádný server" |
| 5 | Canon ovladač na x86 vždy-zapnutém stroji + sdílení | ❌ (uživatel) | Nemá/nechce vždy-zapnutý stroj |
| 6 | **Canon arm64 ovladač na telefonu (Termux + proot Debian 13)** | ✅ **ZVOLENO** | Reálný ovladač, žádný extra stroj, sideload |

## Proč vyhrála cesta #6

Uživatel našel, že Canon vydal **UFR II ovladač pro Linux v6.30** (2026) i pro **Linux ARM**.
Protože telefon je **arm64 Linux** stroj, může na něm běžet reálný Canon ovladač v Linux
kontejneru (Termux + proot). To elegantně řeší dilema:

- ✅ **Žádný reverse engineering** - renderuje oficiální Canon ovladač.
- ✅ **Žádný separátní vždy-zapnutý stroj** - vše běží na telefonu.
- ✅ **Sideload/personal** - Termux se sideloaduje z F-Droid.
- ✅ **"Android appka"** - tenké APK jako share target spouští tisk přes Termux.

Detaily a ověření v [04-reseni-architektura.md](04-reseni-architektura.md).

## Poznámky k zamítnutým cestám (kdyby se měnily podmínky)

- **#4/#5 (bridge / x86 stroj)** by byly nejrobustnější a Play-Store-friendly, kdyby existoval
  vždy-zapnutý počítač. Pokud by v budoucnu byl (NAS, mini-PC), je to fallback.
- **#3 (RE)** zůstává jako "učící" extrém - CARPS blueprint by dal start, ale barva je hazard.
- **Hotová generická print appka** (NetPrinter apod.) by fungovala jen ve spojení s nějakým
  strojem se sdílenou tiskárnou - stejné omezení jako #4/#5.
