#!/usr/bin/env python3
"""build-ppd-pack.py — PPDs of all Canon UFR-II models as app assets

Usage: python3 scripts/build-ppd-pack.py <dir with CNRCUPS*.ppd> <assets dir>
Called by build-bundle.sh; works on any extracted cnrdrvcups-ufr2 driver.

Writes:
  ppd.zip         every PPD without translations (*de. *fr. … lines) and *% comments.
                  The filter output is byte-identical with the stripped PPDs (checked on
                  MF8000C, MF4600, MF8300C, MF731C); 47 MB raw → about 2 MB.
  ppd-index.json  per PPD: file, names, model aliases, colour, InputSlot choices, Duplex,
                  CNColorMode choices and the A4 ImageableArea — so the app needs no PPD
                  parser and can match the model the printer reports (SNMP / MFNP MDL).
"""
import json
import re
import sys
import zipfile
from pathlib import Path

TRANSLATION = re.compile(rb"^\*[a-z]{2}(_[A-Za-z]{2})?\.")


def strip(data: bytes) -> bytes:
    out = []
    for line in data.splitlines(keepends=True):
        if line.startswith(b"*%") or TRANSLATION.match(line):
            continue
        out.append(line)
    return b"".join(out)


def value(text: str, key: str):
    m = re.search(r'^\*%s:\s*"([^"]*)"' % re.escape(key), text, re.M)
    return m.group(1).strip() if m else None


def choices(text: str, key: str):
    seen = []
    for m in re.finditer(r"^\*%s\s+([^/:\s]+)" % re.escape(key), text, re.M):
        if m.group(1) not in seen:
            seen.append(m.group(1))
    return seen


def norm(s: str) -> str:
    """Same normalisation as PpdCatalog.norm in the app."""
    s = s.upper()
    for w in ("CANON", "I-SENSYS", "ISENSYS", "IMAGECLASS", "IMAGERUNNER", "SERIES", "UFRII", "UFR II", "LT"):
        s = s.replace(w, " ")
    return re.sub(r"[^A-Z0-9-]", "", s)


def aliases(short: str):
    """'MF642C/643C/644C' → MF642C MF643C MF644C; 'MF1238 / 1238iF/i' → MF1238 MF1238IF MF1238I.
    Ranges ('MF4320-4350') are returned separately as (prefix, lo, hi)."""
    names, ranges = [], []
    parts = [p.strip() for p in short.replace(" Series", "").split("/") if p.strip()]
    if not parts:
        return names, ranges
    first = norm(parts[0])
    m = re.match(r"^([A-Z-]*?)(\d+)", first)
    prefix, base = (m.group(1), m.group(1) + m.group(2)) if m else ("", first)
    for i, p in enumerate(parts):
        n = norm(p)
        if not n:
            continue
        if i > 0:
            if n[0].isdigit():
                n = prefix + n
            elif len(n) <= 2:
                n = base + n
        r = re.match(r"^([A-Z-]*?)(\d+)-(\d+)$", n)
        if r:
            ranges.append([r.group(1), int(r.group(2)), int(r.group(3))])
            n = r.group(1) + r.group(2)
        names.append(n)
        base = re.match(r"^[A-Z-]*\d+", n).group(0) if re.match(r"^[A-Z-]*\d+", n) else base
    return names, ranges


def main():
    src, out = Path(sys.argv[1]), Path(sys.argv[2])
    index = []
    with zipfile.ZipFile(out / "ppd.zip", "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for ppd in sorted(src.glob("CNRCUPS*.ppd")):
            data = strip(ppd.read_bytes())
            z.writestr(ppd.name, data)
            text = data.decode("latin-1")
            short = value(text, "ShortNickName") or ppd.stem
            names, ranges = aliases(short)
            area = re.search(r'^\*ImageableArea A4(?:/[^:]*)?:\s*"([^"]+)"', text, re.M)
            index.append({
                "file": ppd.name,
                "short": short,
                "nick": value(text, "NickName"),
                # *ColorDevice is unquoted (True/False)
                "color": re.search(r"^\*ColorDevice:\s*True", text, re.M) is not None,
                "aliases": names,
                "ranges": ranges,
                "slots": choices(text, "InputSlot"),
                "duplex": "DuplexNoTumble" in choices(text, "Duplex"),
                "colorModes": choices(text, "CNColorMode"),
                "a4": [float(v) for v in area.group(1).split()] if area else None,
            })
    (out / "ppd-index.json").write_text(json.dumps(index, separators=(",", ":")))
    print(f"  ✓ {len(index)} PPD → {out / 'ppd.zip'} ({(out / 'ppd.zip').stat().st_size // 1024} kB)"
          f" + ppd-index.json ({(out / 'ppd-index.json').stat().st_size // 1024} kB)")


if __name__ == "__main__":
    main()
