"""READ-ONLY lock-step read: next request as soon as the reply arrives, 200 ms timeout."""
import json, time, walkplay as wp
log = wp.Log("timing.log", echo=False)
dev = wp.Device(log)
res = {}
try:
    for run in range(3):
        t0 = time.perf_counter(); lat = []
        for p, c in [([0x80, 0x0C, 0], 0x0C), ([0x80, 0x09, 0], 0x09)]:
            ts = time.perf_counter(); dev.request(p, c, 200, 1); lat.append((time.perf_counter() - ts) * 1000)
        bands = {}
        for i in range(8):
            ts = time.perf_counter()
            r = dev.request([0x80, 0x09, 0, 0, i, 0], 0x09, 200, 1)
            f = wp.parse_filter(r); bands[f["index"]] = f["raw"]
            lat.append((time.perf_counter() - ts) * 1000)
            assert f["index"] == i, f"band {i} got index {f['index']}"
        ts = time.perf_counter(); dev.request([0x80, 0x03, 0], 0x03, 200, 1); lat.append((time.perf_counter() - ts) * 1000)
        ref = {f["index"]: f["raw"] for f in json.load(open("device-backup-2026-09-25.json"))["filters"]}
        res[run] = {"total_ms": round((time.perf_counter() - t0) * 1000, 1), "max_reply_ms": round(max(lat), 1),
                    "all_8": len(bands) == 8, "equals_backup": bands == ref}
finally:
    dev.close()
print(res)
