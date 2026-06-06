# 06 - Reference a zdroje

## Canon ovladač

- **MF8030Cn ovladače (Canon CZ)** - Linux UFR II v6.30:
  `https://www.canon.cz/support/consumer/products/printers/i-sensys/mf-series/i-sensys-mf8030cn.html?type=drivers&os=Linux%20(64-bit)`
- **Canon Asia - UFR II/UFRII LT Linux driver** (tarball, OS list, datum):
  `https://asia.canon/en/support/0100924010`
- **v6.30 System Requirements** (arch + ARM 64-bit):
  `https://oip.manual.canon/USRMA-0587-zz-DR-enGB/contents/dlu-inst-inst_prep-sys_req.html`
- **v6.30 Module install** (přesné arm64/aarch64 názvy balíků + příkazy):
  `https://oip.manual.canon/USRMA-0587-zz-DR-enGB/contents/dlu-inst-module.html`
- **v6.30 User's Guide (PDF)**: `https://sg.canon/en/support/0302983410`

## Ovladač - internals / mirror

- **vicamo/cndrvcups-lb** - Canon GPL glue (`pstoufr2cpca.c`), PPD `CNCUPSMF8000CZS.ppd`:
  `https://github.com/vicamo/cndrvcups-lb`
- **ArchWiki - Canon cnrdrvcups-lb Driver**:
  `https://wiki.archlinux.org/title/Canon_cnrdrvcups-lb_Driver`
- Canon (TORATANI), *Printer Driver for Linux*, OpenPrinting Summit 2007 (OPVP architektura):
  `ftp://ftp.pwg.org/pub/pwg/fsg/Sept2007_OPSummit/MontrealSummit-2007-Canon_Printer_Drivers_for_Linux_070926-2.pdf`

## Ghostscript / OPVP (make-or-break závislost)

- Red Hat #1899885 - UFR-II závisí na Ghostscript OPVP:
  `https://bugzilla.redhat.com/show_bug.cgi?id=1899885`
- Ghostscript deprecation OPVP (~9.53): `https://bugs.ghostscript.com/show_bug.cgi?id=703262`
- Debian re-enable OPVP (bug #980971): `https://packages.debian.org/sid/ghostscript`
- ⚠️ Debian 12 OPVP crash regrese (CVE-2024-33871, bug #1124297):
  `http://www.mail-archive.com/debian-printing@lists.debian.org/msg15354.html`
  `https://www.cve.news/cve-2024-33871/`

## Běh na ARM / komunita

- **Raspberry Pi - Canon UFRII cups drivers solved (arm64)**:
  `https://forums.raspberrypi.com/viewtopic.php?t=313512`
- Canon Community - UFR II arm64 binaries (MF216n):
  `https://community.usa.canon.com/t5/Office-Printers/UFR-II-Linux-Driver-Binaries-in-arm64-Architecture-Model-MF216n/td-p/312676`

## Android - Termux / proot

- **Termux (F-Droid)**: `https://f-droid.org/packages/com.termux/`
- **proot-distro**: `https://github.com/termux/proot-distro`
- **Termux:Widget**: `https://github.com/termux/termux-widget`
- **Termux RUN_COMMAND intent** (pro APK integraci):
  `https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent`
- `PROOT_NO_SECCOMP` (Android 12+ seccomp issue): hledat v termux/proot issues

## Reference k protokolu (jen pro pochopení, NEpotřebné ve zvoleném řešení)

- **CARPS** (rozluštěný starší Canon raster, blueprint): `https://github.com/ondrej-zary/carps-cups`
- **captdriver** (CAPT clean-room RE, `SPECS`): `https://github.com/agalakhov/captdriver`
- **captdriver active fork + wiki**: `https://github.com/mounaiban/captdriver/wiki`

## Android tisk - obecné (pro případný nativní směr)

- Android Print Framework: `https://developer.android.com/training/printing`
- `PdfRenderer`: `https://developer.android.com/reference/android/graphics/pdf/PdfRenderer`
- Local network permission (Android 17/SDK 37 `ACCESS_LOCAL_NETWORK`):
  `https://developer.android.com/privacy-and-security/local-network-permission`
