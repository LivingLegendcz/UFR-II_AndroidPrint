#!/usr/bin/env python3
"""Read-only probe: BJNP/MFNP discover + get-identity on a Canon MFP.
Usage: python3 -I scripts/mfnp_probe.py 192.168.0.62
UNICAST ONLY — never broadcast: MF8030Cn firmware stops answering UDP 8610 after
broadcast discovery until power-cycled (verified 2026-10-23, docs/20).
Header (16 B): ID(4) dev_type(1) cmd(1) unk(2) seq(2) session(2) payload_len(4); big-endian.
Source of layout: sane-backends pixma_bjnp_private.h (struct BJNP_command), set_cmd_for_dev().
"""
import socket, struct, sys

ip = sys.argv[1] if len(sys.argv) > 1 else "192.168.0.62"

def pkt(proto, cmd, seq):
    return struct.pack(">4sBBHHHI", proto, 2, cmd, 0, seq, 0, 0)  # dev_type 2 = scanner

for proto, port in ((b"MFNP", 8610), (b"BJNP", 8612), (b"BJNP", 8611)):
    for cmd, name in ((0x01, "DISCOVER"), (0x30, "GET_ID")):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(2)
        try:
            s.sendto(pkt(proto, cmd, 1), (ip, port))
            data, _ = s.recvfrom(2048)
            print(f"[{proto.decode()}:{port}] {name}: {len(data)} B reply, hdr={data[:16].hex()}")
            if cmd == 0x30:
                # BJNP: 2-byte id_len then ASCII; MFNP: ASCII directly (pixma_bjnp.c get_scanner_id)
                body = data[16:]
                if proto == b"BJNP":
                    body = body[2:]
                print("   IEEE1284 ID:", body.decode("latin1", "replace"))
            else:
                print("   mac:", data[22:28].hex(":") if len(data) >= 28 else "?")
        except socket.timeout:
            print(f"[{proto.decode()}:{port}] {name}: no reply (timeout)")
        finally:
            s.close()

for port in (8610, 8612):
    c = socket.socket()
    c.settimeout(2)
    r = c.connect_ex((ip, port))
    print(f"TCP {port}: {'OPEN' if r == 0 else 'closed/filtered (%s)' % r}")
    c.close()
