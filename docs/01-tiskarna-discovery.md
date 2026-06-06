# 01 - Discovery tiskárny

Jak jsme tiskárnu našli a co je zač.

## Výsledek

**Canon i-SENSYS MF8030Cn** - barevná laserová multifunkce (~2010), ~14 let po podpoře.

| Vlastnost | Hodnota |
|---|---|
| IP adresa | `192.168.0.62` |
| Hostname | `tiskarna.kocourkovic.cz` |
| MAC | `00:1E:8F:38:F7:8C` (OUI = Canon Inc.) |
| SNMP sysDescr | `Canon MF8030 /P` |
| Tiskový jazyk | UFR-II LT (proprietární) - `prtInterpreterLangFamily = other` |
| Připojení | Ethernet + USB (NEmá WiFi - je na síti přes router) |

## Otevřené / zavřené porty

| Port | Služba | Stav |
|---|---|---|
| 515 | LPD | ✅ otevřený |
| 9100 | RAW / JetDirect | ✅ otevřený |
| 8080 | Admin web (Remote UI) | ✅ otevřený |
| 631 | IPP | ❌ zavřený |
| 80 / 443 | HTTP/HTTPS | ❌ zavřený |

→ Tiskárna přijímá data na 9100 a 515, ale **rozumí jen UFR-II LT** (porty jsou jen "trubky").

## Použité metody zjišťování (read-only)

1. **`Get-WmiObject Win32_Printer`** - lokálně nainstalované tiskárny (jen Universal Print/OneNote/PDF; Canon nainstalovaný NEbyl).
2. **`arp -a`** - zařízení v síti; odhalilo `192.168.0.62` s Canon MAC.
3. **`Test-NetConnection -Port`** - sken portů na `.62`.
4. **Reverzní DNS** `[System.Net.Dns]::GetHostEntry` → `tiskarna.kocourkovic.cz`.
5. **SNMP GET** (UDP 161, community `public`) na:
   - `sysDescr` `1.3.6.1.2.1.1.1.0` → `Canon MF8030 /P`
   - `prtInterpreterLangFamily` `1.3.6.1.2.1.43.15.1.1.2` → `1` (other = proprietární)
6. **PJL probe** na :9100 (`@PJL INFO ID`) → žádná odpověď (nepodporuje PJL).

### Příklad - SNMP sysDescr přes PowerShell (bez nástrojů třetích stran)
```powershell
$udp = New-Object System.Net.Sockets.UdpClient
$udp.Client.ReceiveTimeout = 3000
$udp.Connect("192.168.0.62", 161)
# SNMPv1 GET pro sysDescr 1.3.6.1.2.1.1.1.0
$pkt = [byte[]]@(0x30,0x29,0x02,0x01,0x00,0x04,0x06,0x70,0x75,0x62,0x6c,0x69,0x63,
  0xa0,0x1c,0x02,0x04,0x00,0x00,0x00,0x01,0x02,0x01,0x00,0x02,0x01,0x00,
  0x30,0x0e,0x30,0x0c,0x06,0x08,0x2b,0x06,0x01,0x02,0x01,0x01,0x01,0x00,0x05,0x00)
$udp.Send($pkt,$pkt.Length) | Out-Null
$ep = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any,0)
$resp = $udp.Receive([ref]$ep)
-join ($resp | % { if($_ -ge 32 -and $_ -le 126){[char]$_}else{'.'} })
$udp.Close()
# → ...Canon MF8030 /P
```

## Síťový kontext (domácí síť)

| IP | Zařízení |
|---|---|
| 192.168.0.1 | Router `unifi.kocourkovic.cz` |
| 192.168.0.10 | `DESKTOP-O78UHM4` |
| 192.168.0.46 | `LivingLegend` |
| 192.168.0.62 | **Canon MF8030Cn (tiskárna)** |
| 192.168.0.82 | Laptop `PW0KQY21` (odkud probíhal sken) |

> Pozn.: Tiskárna NEbyla v první ARP tabulce - objevila se až po aktivním dotazu.
> IP `.62` je pravděpodobně fixní (DHCP rezervace / statická). Ověřit lze v routeru `192.168.0.1`.
