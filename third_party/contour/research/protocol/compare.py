import json, sys
def key(s):
    return ([(f["index"], f["raw"]["freq"], f["raw"]["q_x256"], f["raw"]["gain_x256"], f["raw"]["type"], f["raw"]["slot_byte"], f["raw"]["biquad_hex"]) for f in s["filters"]], s["preamp_db"], s["current_slot"], s["missing_filters"])
a, b = (json.load(open(p, encoding="utf-8")) for p in sys.argv[1:3])
ok = key(a) == key(b); print("IDENTICAL" if ok else "DIFFERENT"); sys.exit(0 if ok else 1)
