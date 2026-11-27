"""Round-trip CrinEar Protocol Micro write test; dry-run by default.

Physical writes require --execute --confirm WRITE-RESTORE. Volatile mode is the default;
--mode devicepeq also commits to flash. Original raw registers are restored and checked
in finally even when the modified write or read-back fails (best effort, not a guarantee).
"""
import argparse
import json
import sys
import time

import walkplay as wp


def write_state(dev, filters, preamp, slot, mode):
    for f in filters:
        r = f["raw"]
        dev.send(wp.filter_write_payload(f["index"], r["freq"], r["gain_x256"] / 256,
                                         r["q_x256"] / 256, wp.TYPE_NAMES[r["type"]], slot))
        time.sleep(0.020)
    time.sleep(0.100)
    dev.send(wp.global_gain_payload(preamp))
    time.sleep(0.050)
    if mode == "devicepeq":
        delays = [0.020, 0.020, 0.050, 0]
        for p, d in zip(wp.COMMIT_SEQUENCE, delays):
            dev.send(p)
            time.sleep(d)
    time.sleep(0.2)


def regs(state):
    return [(f["index"], f["raw"]["freq"], f["raw"]["q_x256"], f["raw"]["gain_x256"], f["raw"]["type"])
            for f in state["filters"]] + [("preamp", state["preamp_db"])]


def validate_state(state):
    """Preflight the complete snapshot and every payload before any physical write."""
    if state.get("missing_filters") or len(state["filters"]) != wp.MAX_FILTERS:
        raise ValueError("incomplete state: eight filter registers required")
    slot = state["current_slot"]
    preamp = state["preamp_db"]
    if type(slot) is not int or slot not in range(256):
        raise ValueError("invalid slot")
    if type(preamp) is not int or preamp not in range(-30, 1):
        raise ValueError("invalid preamp")
    if [f["index"] for f in state["filters"]] != list(range(wp.MAX_FILTERS)):
        raise ValueError("filters must have ordered indices 0..7")
    for f in state["filters"]:
        raw = f["raw"]
        for key, lo, hi in (("freq", 1, 65534), ("q_x256", 1, 65535),
                            ("gain_x256", -32768, 32767), ("type", 1, 5)):
            v = raw[key]
            if type(v) is not int or not lo <= v <= hi:
                raise ValueError(f"band {f['index']}: invalid {key}")
        if raw["type"] not in wp.TYPE_NAMES:
            raise ValueError(f"band {f['index']}: unknown filter type")
        wp.filter_write_payload(f["index"], raw["freq"], raw["gain_x256"] / 256,
                                raw["q_x256"] / 256, wp.TYPE_NAMES[raw["type"]], slot)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="default; retained for older invocations")
    ap.add_argument("--execute", action="store_true", help="permit physical writes")
    ap.add_argument("--confirm", help="required with --execute: WRITE-RESTORE")
    ap.add_argument("--mode", choices=["volatile", "devicepeq"], default="volatile")
    ap.add_argument("--state", default="device-backup-2026-09-25.json", help="state used in dry-run")
    ap.add_argument("--log", default=None)
    a = ap.parse_args(argv)
    if a.execute and (a.dry_run or a.confirm != "WRITE-RESTORE"):
        ap.error("--execute requires --confirm WRITE-RESTORE and cannot be combined with --dry-run")
    if not a.execute and a.confirm:
        ap.error("--confirm requires --execute")
    dry = not a.execute
    orig = {}
    slot = 0
    if dry:
        with open(a.state, encoding="utf-8") as source:
            orig = json.load(source)
        validate_state(orig)
        print(f"# dry-run: state from {a.state}; no device opened or reports sent")
    log = wp.Log(a.log)
    dev = wp.Device(log, dry_run=dry)
    modified_attempted = False
    ok = True
    error = None
    restore_error = None
    try:
        if not dry:
            orig = wp.read_all(dev)
            validate_state(orig)
        slot = orig["current_slot"]
        mod = json.loads(json.dumps(orig))
        g = mod["filters"][0]["raw"]["gain_x256"] - 128
        if g < -10 * 256:
            raise ValueError("band 1 already at -10 dB; refusing")
        mod["filters"][0]["raw"]["gain_x256"] = g
        validate_state(mod)
        print(f"# step 1: write modified state (band 1 gain {orig['filters'][0]['raw']['gain_x256']/256} -> {g/256} dB), mode={a.mode}")
        modified_attempted = True
        write_state(dev, mod["filters"], mod["preamp_db"], slot, a.mode)
        if not dry:
            back = wp.read_all(dev)
            validate_state(back)
            same = regs(back) == regs(mod)
            print("# read-back matches modified:", same)
            ok &= same
    except Exception as exc:
        error = exc
    finally:
        try:
            if modified_attempted:
                print("# step 2: restore original state (best effort after any failure)")
                try:
                    write_state(dev, orig["filters"], orig["preamp_db"], slot, a.mode)
                    if not dry:
                        back = wp.read_all(dev)
                        validate_state(back)
                        same = regs(back) == regs(orig)
                        print("# read-back matches original:", same)
                        ok &= same
                        if not same:
                            restore_error = RuntimeError("restored registers differ from original")
                except Exception as exc:
                    restore_error = exc
        finally:
            dev.close()
    if restore_error:
        print(f"# RESTORE NOT VERIFIED; inspect DAC manually: {restore_error}", file=sys.stderr)
    if error:
        print(f"# test failed: {error}", file=sys.stderr)
    return 0 if ok and error is None and restore_error is None else 2


if __name__ == "__main__":
    sys.exit(main())
