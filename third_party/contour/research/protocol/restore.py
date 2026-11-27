"""Restore a complete backup; dry-run by default. Physical flash write requires explicit confirmation."""
import argparse
import json
import sys

import walkplay as wp
from write_test import regs, validate_state, write_state


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("state", nargs="?", default="device-backup-2026-09-25.json")
    ap.add_argument("--log")
    ap.add_argument("--dry-run", action="store_true", help="default; no device opened")
    ap.add_argument("--execute", action="store_true", help="permit physical write and flash commit")
    ap.add_argument("--confirm", help="required with --execute: RESTORE")
    a = ap.parse_args(argv)
    if a.execute and (a.dry_run or a.confirm != "RESTORE"):
        ap.error("--execute requires --confirm RESTORE and cannot be combined with --dry-run")
    if not a.execute and a.confirm:
        ap.error("--confirm requires --execute")
    with open(a.state, encoding="utf-8") as source:
        state = json.load(source)
    validate_state(state)
    dry = not a.execute
    if dry:
        print("# dry-run; no device opened or reports sent")
    dev = wp.Device(wp.Log(a.log), dry_run=dry)
    try:
        write_state(dev, state["filters"], state["preamp_db"], state["current_slot"], "devicepeq")
        if not dry:
            back = wp.read_all(dev)
            validate_state(back)
            same = back["current_slot"] == state["current_slot"] and regs(back) == regs(state)
            print("# read-back matches backup registers and slot:", same)
            if not same:
                return 2
    finally:
        dev.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
