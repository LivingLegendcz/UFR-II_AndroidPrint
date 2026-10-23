# 14 - Uživatelský návod: aplikace UFR-II (verze 1.3.0)

Jak tisknout a skenovat na tiskárně Canon z Android telefonu. Bez počítače, bez kabelů.

Návod je pro všechny, kdo aplikaci jen používají. Nic složitého nastavovat nebudete.

---

## Co potřebujete

- Telefon s Androidem 10 nebo novějším (s procesorem arm64 - to má skoro každý telefon z posledních let).
- Tiskárnu Canon zapnutou a připojenou ke **stejné Wi-Fi** jako telefon.
- Soubor aplikace s koncovkou `.apk`. Dostanete ho od toho, kdo aplikaci připravil.

Tisk přes mobilní data nebo cizí Wi-Fi nefunguje. Telefon musí být doma ve stejné síti jako tiskárna.

---

## 1. Instalace aplikace

1. Soubor `.apk` si pošlete do telefonu (e-mailem, přes cloud, kabelem).
2. Klepněte na soubor. Telefon se zeptá, jestli povolit instalaci z neznámých zdrojů.
3. Klepněte na **Nastavení** a povolte to pro aplikaci, ze které soubor otevíráte (například Soubory nebo Chrome).
4. Vraťte se zpět a klepněte na **Instalovat**.
5. Po instalaci najdete aplikaci **UFR-II** mezi ostatními aplikacemi.

Aplikace se při prvním spuštění chvíli připravuje (asi půl minuty). Počkejte, až skončí.

---

## 2. První spuštění: průvodce

Průvodce má dva bloky. Každý krok stačí odklepnout.

**Blok Telefon**

- **Tisková služba**: klepněte na tlačítko a v nastavení telefonu zapněte **UFR-II**. Díky tomu půjde tisknout z libovolné aplikace.
- **Upozornění**: klepněte na **Povolit**. Upozornění vám řeknou, kdy otočit papír při oboustranném tisku. Pokud je nechcete, zvolte **Ne, děkuji**. Pak ale výzvy k otočení papíru nepřijdou.
- **Hlídání stavu tiskárny**: když ho zapnete, telefon vás upozorní, že dochází toner nebo papír.

**Blok Tiskárna**

- Buď **přidejte tiskárnu** (viz další část),
- nebo ji **načtěte z QR kódu** z jiného telefonu, kde už vše funguje.

Až jsou oba bloky hotové, objeví se tlačítko **Začít**. Dřív ne.

> Po nové instalaci aplikace na místě staré přepněte tiskovou službu v nastavení telefonu jednou vypnout a zapnout. Jinak se nemusí správně připojit.

---

## 3. Přidání tiskárny

Aplikace žádnou tiskárnu nezná předem. Můžete ji přidat třemi způsoby.

### a) Hledání v síti

1. Klepněte na **Přidat tiskárnu**.
2. Chvíli počkejte (asi 20 sekund). Aplikace v síti hledá.
3. Klepněte na nalezenou tiskárnu a potom na **Uložit**.

### b) Zadání adresy (IP)

Když ji aplikace nenajde:

1. Klepněte na **Zadat IP ručně**.
2. Napište adresu tiskárny, například `192.168.0.62`. Adresu najdete na displeji tiskárny nebo se zeptejte toho, kdo tiskárnu spravuje.
3. Klepněte na **Otestovat spojení**. Aplikace zjistí, co tiskárna umí (barva, zásobníky, oboustranný tisk).
4. Klepněte na **Uložit**.

### c) QR kód z jiného telefonu (nejrychlejší)

Na telefonu, kde tiskárna už funguje:

1. Otevřete aplikaci a klepněte na tiskárnu.
2. Klepněte na **Sdílet nastavení (QR)**. Ukáže se QR kód.

Na novém telefonu:

1. V průvodci klepněte na **Načíst z jiného telefonu (QR)**. Později to najdete v **Tiskárny → Načíst nastavení (QR)**.
2. Klepněte na **Naskenovat QR** a zamiřte telefon na kód.
3. Klepněte na **Uložit tiskárnu**.

Přenese se i vše ostatní: model, nastavení oboustranného tisku a skeneru. Nic nemusíte znovu zkoušet.

Při prvním použití čtečky QR si telefon jednou stáhne malý modul od Googlu. Potřebuje k tomu internet. Když to nejde, požádejte o **odkaz** a klepněte na **Vložit odkaz ze schránky**.

---

## 4. Tisk

### Z jiné aplikace (nejčastější)

1. V aplikaci s dokumentem, fotkou nebo webovou stránkou otevřete nabídku a zvolte **Tisk**.
2. Nahoře vyberte tiskárnu **UFR-II** (s názvem, který jste jí dali).
3. Nastavte počet kopií, barvu nebo černobílý tisk, případně **Oboustranně**.
4. Chcete-li vybrat zásobník papíru, klepněte na **Další možnosti**.
5. Klepněte na **Tisk**.

### Sdílením

1. V aplikaci s PDF nebo obrázkem klepněte na **Sdílet**.
2. Vyberte **UFR-II**.
3. V aplikaci zvolte barvu a klepněte na **Tisknout**.

### Přímo v aplikaci

1. Otevřete **UFR-II**.
2. Klepněte na **Vybrat soubor** a vyberte dokument nebo obrázek.
3. Zvolte **Barevně** nebo **Černobíle**, počet kopií, případně stránky (například `1-3, 5`) a zdroj papíru.
4. Klepněte na **Tisknout**.

**Testovací stránka** na domovské obrazovce vytiskne ukázkovou stranu. Hodí se, když si nejste jistí, že vše funguje.

> Velké dokumenty a fotky se zpracovávají několik sekund na stránku. Tiskárna nezačne hned. Počkejte.

### Zdroj papíru

- **Automaticky**: tiskárna sama vybere. Je-li papír v ručním podavači, vezme ho odtud, jinak z kazety.
- **Kazeta**: běžný papír.
- **Ruční podavač**: jeden list, například obálka, silnější nebo hlavičkový papír.

---

## 5. Oboustranný tisk

### Tiskárna s duplexní jednotkou

Stačí zvolit **Oboustranně**. Tiskárna si list otočí sama.

### Tiskárna bez duplexní jednotky (například MF8030Cn)

Tisk jde ve dvou krocích:

1. Tiskárna nejdřív vytiskne **liché strany** (1, 3, 5…).
2. Telefon ukáže výzvu **Otočte papír** (i jako upozornění).
3. Vezměte potištěné listy z výstupu tiskárny a vložte je zpět **podle obrázku a návodu na obrazovce**. Pořadí listů neměňte.
4. Klepněte na **Pokračovat**.
5. Tiskárna vytiskne **sudé strany** na rub.

Je-li stránek lichý počet, poslední rub zůstane prázdný.

Ruční podavač bere listy po jednom. Návod vám řekne, od kterého listu začít. Tiskárna si o každý list řekne na displeji.

Když to rozmyslíte, klepněte na **Zrušit druhou stranu**.

### Kalibrace: když list vyjde špatně

Při prvním oboustranném tisku aplikace odhadne, jak list vložit. Odhad nemusí sedět. Když se rub vytiskl na špatnou stranu nebo vzhůru nohama, udělejte kalibraci. Stojí **jeden list papíru** a stačí ji udělat jednou pro každý zásobník.

1. Otevřete **UFR-II → klepněte na tiskárnu → Kalibrace oboustranného tisku**.
2. Vyberte zásobník (kazeta nebo ruční podavač). Do něj dejte čistý list.
3. Klepněte na **Vytisknout jedničku**. Vyjede list s velkou **1** a šipkou u horního okraje.
4. Aplikace ukáže **očíslované kroky s obrázkem** (pohled shora, tiskárna ↑ a vy ↓). Vezměte list, otočte ho podle návodu, šipka musí mířit do tiskárny. Vložte ho do zásobníku a klepněte na **Vytisknout 2**.
5. Odpovězte na dvě otázky:
   - Na které straně listu vyjela dvojka (na druhé, nebo na stejné jako jednička)?
   - Je dvojka natočená stejně jako jednička, nebo vzhůru nohama?
6. Když číslo vyjelo na druhé straně, klepněte na **Uložit**. Hotovo.
7. Když vyjelo na **stejné straně**, nezoufejte. Rub listu je ještě čistý. Aplikace vám ukáže nový návod a použije stejný list ještě jednou (čísla 3, 4…). Pak klepněte na **Uložit**.

Čísla na listu jsou v různé výšce (1 nahoře, 2 uprostřed, 3 dole), aby se nepřekrývala.

---

## 6. Skenování

Skenovat se dá z **průhledného skla** i z **podavače dokumentů**, pokud ho tiskárna má.

### Krok 1: na tiskárně

Na panelu tiskárny stiskněte **SCAN → Remote Scanner → OK**.

Displej ukáže **Remote Scanning Waiting**. Stačí to udělat jednou, platí i pro další skeny.

Bez tohoto kroku skener hlásí, že je zaneprázdněný.

### Krok 2: v aplikaci

1. Klepněte na **Skenovat**.
2. **Odkud**: sklo nebo podavač.
   - **Sklo**: předlohu položte textem **dolů**. Horní okraj dejte k zadnímu levému rohu, kde je značka na skle. Víko zavřete.
   - **Podavač**: listy vložte textem **nahoru**, horním okrajem napřed. Naskenují se všechny.
3. Zvolte barvy (barevně, odstíny šedi, černobíle) a rozlišení. Pro obrazovku a e-mail stačí 150, pro archivaci 300.
4. U skla můžete klepnout na **Náhled**. Za pár sekund se ukáže celé sklo s rámečkem. Rámeček se nastaví sám podle předlohy. Táhnutím za rohy ho můžete upravit.
5. Zvolte, **kam uložit**: do telefonu (Stažené soubory / UFR-II), do vybraného místa (složka, Disk Google, OneDrive), sdílet (e-mail, zprávy), nebo hned **vytisknout kopii**.
6. Zvolte formát: PDF, JPEG nebo PNG.
7. Klepněte na **Skenovat**. Víko nechte zavřené, dokud sken neskončí.

Z podavače se naskenují všechny vložené listy do jednoho PDF. Ze skla se skenuje jedna strana; další list naskenujete znovu tlačítkem **Skenovat znovu**.

Hotovo? Na panelu tiskárny skenování **ukončete** (tlačítko Stop nebo Zpět). Dokud je zapnutý Remote Scanner, tiskárna tisk zdržuje.

---

## 7. Zrušení

Tlačítko **Zrušit** najdete:

- na domovské obrazovce,
- na obrazovce Tisk,
- v detailu tiskárny,
- v upozornění o probíhajícím tisku.

Tisk se zastaví. Co už tiskárna dostala, může ještě dotisknout.

---

## 8. „Tisk čeká v tiskárně“

Tuto zprávu uvidíte (jako kartu v aplikaci i jako upozornění), když tiskárna úlohu přijala, ale netiskne.

**Příčina:** na panelu tiskárny je zapnutý režim skenování (Remote Scanner). V něm tiskárna tisk drží.

**Co dělat:**

1. Na panelu tiskárny **ukončete skenování** (tlačítko Stop nebo Zpět).
2. Počkejte. Úloha se vytiskne hned potom.

**Nic neposílejte znovu.** Úloha už v tiskárně je. Kdybyste ji poslali podruhé, vytiskla by se dvakrát.

Aplikace čeká nejvýše 10 minut. Pak to vzdá a úloha zůstane v tiskárně, dokud skenování neukončíte.

---

## 9. Časté potíže

**Tiskárna je „Nedostupná“ nebo „Tiskárna neodpovídá“**
- Je tiskárna zapnutá? Je telefon na stejné Wi-Fi?
- Vyzkoušejte klepnout na tiskárnu a **Znovu zjistit schopnosti**.
- Změnila tiskárna adresu? Smažte ji a přidejte znovu (nebo opravte adresu přes **Upravit**). Máte-li uloženou i záložní adresu IPv6, aplikace ji zkusí sama.

**Tisk se nespustí**
- Je tisková služba zapnutá? V Nastavení telefonu: Tisk → UFR-II → zapnuto. V aplikaci: **Nastavení → Tisková služba**.
- Po nové instalaci aplikace službu vypněte a znovu zapněte.

**Dokument se tiskne dlouho**
- Je to normální. Kvalitní tisk je velký a telefon ho musí nejdřív připravit.

**Tiskárna píše, že došel papír nebo je otevřený kryt**
- Doplňte papír, zavřete kryt. Aplikace ukazuje stav na kartě tiskárny.

**Rub při ručním oboustranném tisku vyšel špatně**
- Udělejte kalibraci (viz část 5).

**Skener hlásí „zaneprázdněný“ nebo „neodpovídá“**
- Na panelu tiskárny zvolte **SCAN → Remote Scanner → OK** a zkuste znovu.
- Zkuste v detailu tiskárny **Test skeneru**. Když ani ten neodpoví, skener tohoto modelu aplikace nezná.

**V podavači není papír**
- Vložte listy textem nahoru, nebo skenujte ze skla.

**Zaseknutý papír**
- Vyjměte ho podle návodu na displeji tiskárny.

**QR kód se nenačte**
- Potřebujete internet (stáhne se čtečka). Případně použijte **Vložit odkaz ze schránky**.

**Nic z toho nepomohlo**
- V aplikaci otevřete **Nastavení → Log a diagnostika**, klepněte na kopírování logu a pošlete ho tomu, kdo aplikaci spravuje.

---

## 10. Hlídání stavu a historie

- **Nastavení → Hlídat stav tiskárny**: telefon se každých 15 minut na Wi-Fi zeptá tiskárny a upozorní vás, když dochází toner, došel papír nebo se zasekl.
- **Historie** ukazuje, co jste tiskli a skenovali.

---

## Poznámka k aplikaci

Aplikace obsahuje části ovladače Canon. Je určena jen pro osobní a rodinné použití. Neposílejte ji dál cizím lidem ani ji nenahrávejte na veřejné weby.
