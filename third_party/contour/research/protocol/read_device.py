"""READ-ONLY dump of CrinEar Protocol Micro PEQ (devicePEQ read path only).

usage: read_device.py [--json] [--out PREFIX] [--log FILE]
"""
import argparse
import json
import sys

import walkplay as wp


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true", help="print JSON instead of APO text")
    ap.add_argument("--out", help="write PREFIX.json and PREFIX.txt")
    ap.add_argument("--log", help="append raw hex TX/RX log here")
    a = ap.parse_args()
    log = wp.Log(a.log, echo=not a.json)
    dev = wp.Device(log)
    try:
        state = wp.read_all(dev)
    finally:
        dev.close()
    if a.out:
        with open(a.out + ".json", "w", encoding="utf-8") as f:
            json.dump(state, f, indent=2)
        with open(a.out + ".txt", "w", encoding="utf-8") as f:
            f.write(wp.to_apo(state))
    if a.json:
        print(json.dumps(state, indent=2))
    else:
        print(wp.to_apo(state), end="")
        print("firmware", state["firmware_version"], "slot", state["current_slot"], "timing", state["timing_ms"])
    return 1 if state["missing_filters"] else 0


if __name__ == "__main__":
    sys.exit(main())
