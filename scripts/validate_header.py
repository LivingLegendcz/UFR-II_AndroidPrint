#!/usr/bin/env python3
"""
Byte-match validation for cups_page_header_a4.bin asset.

Checks:
  1. golden_raster.bin[0:4] == CUPS Raster v3 sync word (0x33 0x53 0x61 0x52)
  2. cups_page_header_a4.bin (1796 bytes) == golden_raster.bin[4:1800] byte-for-byte

Run from project root:
  python3 scripts/validate_header.py
"""

import sys
import os

GOLDEN_PATH = "captures/golden_raster.bin"
ASSET_PATH  = "app/src/main/assets/cups_page_header_a4.bin"
EXPECTED_SYNC = bytes([0x33, 0x53, 0x61, 0x52])
HEADER_SIZE = 1796

def run():
    errors = []

    # --- Load golden file ---
    if not os.path.exists(GOLDEN_PATH):
        print(f"FAIL: golden file not found: {GOLDEN_PATH}")
        sys.exit(1)

    with open(GOLDEN_PATH, "rb") as f:
        golden_first1800 = f.read(1800)

    golden_size = os.path.getsize(GOLDEN_PATH)
    print(f"golden_raster.bin : {golden_size} bytes on disk")
    print(f"  first 1800 bytes read: {len(golden_first1800)}")

    # --- Check 1: sync word ---
    sync = golden_first1800[0:4]
    sync_hex = " ".join(f"{b:02x}" for b in sync)
    sync_ascii = "".join(chr(b) if 32 <= b < 127 else "." for b in sync)
    print(f"\nSync word  : {sync_hex}  (ascii: '{sync_ascii}')")

    if sync == EXPECTED_SYNC:
        print("  [PASS] sync word matches 0x33 0x53 0x61 0x52 ('3SaR')")
    else:
        msg = f"sync word mismatch: got {sync_hex}, expected 33 53 61 52"
        print(f"  [FAIL] {msg}")
        errors.append(msg)

    # --- Load asset file ---
    if not os.path.exists(ASSET_PATH):
        print(f"\nFAIL: asset file not found: {ASSET_PATH}")
        sys.exit(1)

    with open(ASSET_PATH, "rb") as f:
        asset_bytes = f.read()

    print(f"\ncups_page_header_a4.bin : {len(asset_bytes)} bytes")

    # Size check
    if len(asset_bytes) != HEADER_SIZE:
        msg = f"asset size {len(asset_bytes)} != expected {HEADER_SIZE}"
        print(f"  [FAIL] {msg}")
        errors.append(msg)
    else:
        print(f"  [PASS] size == {HEADER_SIZE}")

    # --- Check 2: byte-identical comparison ---
    golden_header = golden_first1800[4:1800]   # bytes[4:1800]
    print(f"\nComparing asset ({len(asset_bytes)} B) vs golden[4:1800] ({len(golden_header)} B) ...")

    if len(asset_bytes) == len(golden_header) and asset_bytes == golden_header:
        print(f"  [PASS] byte-identical ({len(asset_bytes)} bytes)")
    else:
        # Find first difference
        diff_count = 0
        first_diff = None
        for i, (a, g) in enumerate(zip(asset_bytes, golden_header)):
            if a != g:
                diff_count += 1
                if first_diff is None:
                    first_diff = (i, a, g)
        if len(asset_bytes) != len(golden_header):
            msg = f"length mismatch: asset={len(asset_bytes)} golden_slice={len(golden_header)}"
        else:
            msg = f"{diff_count} differing bytes; first diff at offset {first_diff[0]}: asset=0x{first_diff[1]:02x} golden=0x{first_diff[2]:02x}"
        print(f"  [FAIL] {msg}")
        errors.append(msg)

    # --- Summary ---
    print()
    if not errors:
        print("=== RESULT: PASS — header asset is byte-identical to golden_raster.bin[4:1800] ===")
        sys.exit(0)
    else:
        print("=== RESULT: FAIL ===")
        for e in errors:
            print(f"  - {e}")
        sys.exit(1)

if __name__ == "__main__":
    # Run from the project root regardless of working directory
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.dirname(script_dir)
    os.chdir(project_root)
    run()
