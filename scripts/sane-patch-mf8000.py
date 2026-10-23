#!/usr/bin/env python3
"""Apply the MF8000-series patches to an extracted sane-backends 1.4.0 tree.

Usage: python3 -I scripts/sane-patch-mf8000.py <sane-backends-src-dir>

Patches (see docs/20-skenovani-navrh.md):
  1. pixma_imageclass.c — model alias: the device reports MDL "MF8000 Series" over the
     network, the table only knows "MF8030". Same PID → generation-2 code path. The unit
     has a document feeder → PIXMA_CAP_ADF.
  2. pixma_bjnp.c — stale-session recovery: when a previous client never sent UDP CLOSE,
     JOB_DETAILS answers with a non-zero status (seen 0x8502) and session id 0; stock SANE
     ignores it and every TCP command is then refused. Close session 1 and retry once.
  3. pixma_imageclass.c — no-data watchdog: on a paper jam the device keeps answering
     read_image2 with status only and SANE polls forever (observed ~7 min, ignores SIGINT).
     After 30 s without image data abort with PIXMA_EPAPER_JAMMED (ADF) / PIXMA_ETIMEDOUT.

Idempotent: each patch is skipped if its marker is already present. Fails loudly if an
anchor is not found (different sane-backends version).
"""
import sys
from pathlib import Path

MARK = "UFR-II MF8000"


def patch(path: Path, anchor: str, replacement: str, marker: str) -> None:
    text = path.read_text(encoding="utf-8")
    if marker in text:
        print(f"[skip] {path.name}: {marker}")
        return
    if text.count(anchor) != 1:
        sys.exit(f"[FAIL] {path.name}: anchor found {text.count(anchor)}x (expected 1): {anchor[:60]!r}")
    path.write_text(text.replace(anchor, replacement), encoding="utf-8")
    print(f"[ok]   {path.name}: {marker}")


def main() -> None:
    src = Path(sys.argv[1]) / "backend" / "pixma"
    iclass = src / "pixma_imageclass.c"
    bjnp = src / "pixma_bjnp.c"

    # 1. model alias
    a1 = '  DEV ("Canon imageClass MF8030", "MF8030", MF8030_PID, 600, 0, 640, 877, PIXMA_CAP_ADF),\n'
    patch(iclass, a1, a1 +
          f'  /* {MARK}: network MDL is "MF8000 Series" */\n'
          '  DEV ("Canon i-SENSYS MF8000 Series", "MF8000", MF8030_PID, 600, 0, 640, 877, PIXMA_CAP_ADF),\n',
          f"{MARK}: network MDL")

    # 2. stale-session recovery in bjnp_send_job_details
    a2 = """  resp_len = udp_command (devno, cmd_buf,
			  sizeof (struct JOB_DETAILS), resp_buf,
			  BJNP_RESP_MAX);

  if (resp_len > 0)
    {
      PDBG (bjnp_dbg (LOG_DEBUG2, "bjnp_send_job_details: Job details response:\\n"));
      PDBG (bjnp_hexdump (LOG_DEBUG2, resp_buf, resp_len));
      resp = (struct BJNP_command *) resp_buf;
      device[devno].session_id = ntohs (resp->session_id);
    }
}"""
    r2 = f"""  int attempt;

  for (attempt = 0; attempt < 2; attempt++)
    {{
      resp_len = udp_command (devno, cmd_buf,
			      sizeof (struct JOB_DETAILS), resp_buf,
			      BJNP_RESP_MAX);
      if (resp_len <= 0)
        return;

      PDBG (bjnp_dbg (LOG_DEBUG2, "bjnp_send_job_details: Job details response:\\n"));
      PDBG (bjnp_hexdump (LOG_DEBUG2, resp_buf, resp_len));
      resp = (struct BJNP_command *) resp_buf;
      device[devno].session_id = ntohs (resp->session_id);

      /* {MARK}: a session never closed by a previous client makes the scanner refuse
       * new jobs (status 0x8502, session id 0). Close the stale session and retry. */
      if (resp->unknown1 == 0 || attempt > 0)
        return;
      PDBG (bjnp_dbg (LOG_NOTICE,
                      "bjnp_send_job_details: job refused (status 0x%04x), closing stale session 1 and retrying\\n",
                      (unsigned) ntohs (resp->unknown1)));
      device[devno].session_id = 1;
      bjnp_finish_job (devno);
      /* rebuild the header (fresh seq_no, session 0, last_cmd); payload is untouched */
      device[devno].session_id = 0;
      set_cmd_for_dev (devno, (struct BJNP_command *) cmd_buf, CMD_UDP_JOB_DETAILS,
                       sizeof (*job) - sizeof (struct BJNP_command));
    }}
}}"""
    patch(bjnp, a2, r2, f"{MARK}: a session never closed")

    # 3. no-data watchdog in iclass_fill_buffer
    a3a = """  unsigned block_size, lines_size, lineart_lines_size, first_block_size;
  uint8_t info;
"""
    patch(iclass, a3a, a3a +
          f"  time_t nodata_since = 0;   /* {MARK}: jam watchdog */\n",
          f"{MARK}: jam watchdog")
    a3b = """          if (block_size == 0)
            {
              /* no image data at this moment. */"""
    r3b = f"""          if (block_size == 0 && first_block_size == 0)
            {{
              /* {MARK}: on a paper jam the device answers with status only, forever */
              time_t now;
              uint32_t usec;
              pixma_get_time (&now, &usec);
              if (nodata_since == 0)
                nodata_since = now;
              else if (now - nodata_since > 30)
                {{
                  PDBG (pixma_dbg (1, "WARNING: no image data for 30 s (info=0x%02x) -> abort\\n", info));
                  return is_scanning_from_adf (s) ? PIXMA_EPAPER_JAMMED : PIXMA_ETIMEDOUT;
                }}
            }}
          else
            nodata_since = 0;

          if (block_size == 0)
            {{
              /* no image data at this moment. */"""
    patch(iclass, a3b, r3b, f"{MARK}: on a paper jam")


if __name__ == "__main__":
    main()
