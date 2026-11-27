"""WalkPlay (SchemeNo11) HID PEQ helpers for CrinEar Protocol Micro (3302:C20F).

Mirrors devicePEQ walkplayHidHandler.js @ 0617f382e76629792a5933e6933e4b396a756a93.
hidapi buffers include the report ID at [0]; WebHID `data[k]` == buf[k+1].
"""
import math
import time

import hid

VID, PID = 0x3302, 0xC20F
REPORT_ID = 0x4B
REPORT_LEN = 63            # payload bytes after report ID (HID descriptor: 0x4B, count 0x3F)
READ, WRITE, END = 0x80, 0x01, 0x00
CMD_GLOBAL_GAIN, CMD_PEQ, CMD_VERSION = 0x03, 0x09, 0x0C
MAX_FILTERS = 8
FREQ_FACTOR = 0.9775       # freqCompensation ratio (SchemeNo11)
DESIGN_FS = 96000          # qCompensation cosNyquist designFs
TYPE_NAMES = {1: "LSQ", 2: "PK", 3: "HSQ", 4: "LP", 5: "HP"}
TYPE_CODES = {v: k for k, v in TYPE_NAMES.items()}
APO_TYPES = {"PK": "PK", "LSQ": "LSC", "HSQ": "HSC", "LP": "LPQ", "HP": "HPQ"}


def hx(b):
    return " ".join(f"{x:02x}" for x in b)


class Log:
    def __init__(self, path=None, echo=True):
        self.f = open(path, "a", encoding="utf-8") if path else None
        self.echo = echo
        self.t0 = time.perf_counter()

    def __call__(self, direction, data):
        line = f"{(time.perf_counter() - self.t0) * 1000:9.1f} ms {direction} {hx(data)}"
        if self.echo:
            print(line)
        if self.f:
            self.f.write(line + "\n")
            self.f.flush()


def find_path():
    # The Micro exposes one HID interface; its number differs per host (MI_03 on the
    # first PC, MI_02 on 2026-09-29), so match on the Consumer Control collection.
    devs = [d for d in hid.enumerate(VID, PID) if d["usage_page"] == 0x0C] or hid.enumerate(VID, PID)
    if not devs:
        raise SystemExit("Protocol Micro HID interface not found")
    return devs[0]["path"]


class Device:
    def __init__(self, log, dry_run=False):
        self.log, self.dry = log, dry_run
        self.h = None
        if not dry_run:
            self.h = hid.device()
            self.h.open_path(find_path())

    def close(self):
        if self.h:
            self.h.close()

    def send(self, payload):
        buf = bytes([REPORT_ID]) + bytes(payload) + bytes(REPORT_LEN - len(payload))
        self.log("TX" + (" (dry-run, not sent)" if self.dry else ""), buf)
        if not self.dry:
            n = self.h.write(buf)
            if n < 0:
                raise IOError(f"hid write failed: {self.h.error()}")

    def drain(self, ms):
        """Collect every 0x4B input report arriving within `ms`."""
        out = []
        if self.dry:
            time.sleep(ms / 1000)
            return out
        end = time.perf_counter() + ms / 1000
        while True:
            left = int((end - time.perf_counter()) * 1000)
            if left <= 0:
                break
            r = self.h.read(64, left)
            if r:
                self.log("RX", r)
                if r[0] == REPORT_ID:
                    out.append(bytes(r))
        return out

    def request(self, payload, cmd, timeout_ms=2000, attempts=3):
        """Send and wait for a reply whose data[1] (WebHID) == cmd."""
        for attempt in range(1, attempts + 1):
            self.send(payload)
            end = time.perf_counter() + timeout_ms / 1000
            while time.perf_counter() < end:
                r = self.h.read(64, max(1, int((end - time.perf_counter()) * 1000)))
                if r:
                    self.log("RX", r)
                    if r[0] == REPORT_ID and r[2] == cmd:
                        return bytes(r)
            print(f"  no reply to cmd 0x{cmd:02x} (attempt {attempt}/{attempts})")
        raise TimeoutError(f"device did not answer cmd 0x{cmd:02x} after {attempts} attempts")


def parse_filter(buf):
    """buf includes report ID. Offsets follow parseFilterPacket (+1)."""
    d = buf[1:]
    idx = d[4]
    freq_raw = d[27] | (d[28] << 8)
    q_raw = d[29] | (d[30] << 8)
    gain_raw = d[31] | (d[32] << 8)
    if gain_raw > 32767:
        gain_raw -= 65536
    type_code = d[33]
    typ = TYPE_NAMES.get(type_code, "PK")
    gain = round(gain_raw / 256, 2)
    q_stored = round(q_raw / 256, 2)
    freq = freq_raw * FREQ_FACTOR if freq_raw > 0 else freq_raw
    ratio = math.cos(math.pi * min(max(freq_raw / DESIGN_FS, 0), 0.5 - 1e-9)) if typ in ("PK", "LSQ", "HSQ") else 1.0
    q = q_stored * ratio if q_stored > 0 else q_stored
    uninit = freq_raw == 0xFFFF and q_raw == 0xFFFF and gain_raw == -1
    disabled = uninit or freq_raw in (0, 0xFFFF) or q == 0
    return {
        "index": idx,
        "enabled": not disabled,
        "type": typ,
        "freq_hz": round(freq, 1),
        "gain_db": gain,
        "q": round(q, 3),
        "raw": {"freq": freq_raw, "q_x256": q_raw, "gain_x256": gain_raw, "type": type_code,
                "slot_byte": d[35], "biquad_hex": hx(d[7:27])},
    }


def read_all(dev):
    """devicePEQ read path: getCurrentSlot() then pullFromDevice()."""
    t = {}
    t0 = time.perf_counter()
    v = dev.request([READ, CMD_VERSION, END], CMD_VERSION)
    version = bytes(v[4:7]).decode("ascii", "replace")          # response.slice(3,6)
    t["version_ms"] = (time.perf_counter() - t0) * 1000
    t1 = time.perf_counter()
    s = dev.request([READ, CMD_PEQ, END], CMD_PEQ)
    slot = s[36]                                                 # response[35]
    t["slot_ms"] = (time.perf_counter() - t1) * 1000

    filters, per_band = {}, {}
    t2 = time.perf_counter()
    for i in range(MAX_FILTERS):
        ts = time.perf_counter()
        dev.send([READ, CMD_PEQ, 0x00, 0x00, i, END])
        for r in dev.drain(50):                                  # devicePEQ: delay(50) between requests
            if r[2] == CMD_PEQ and len(r) >= 33:
                f = parse_filter(r)
                filters[f["index"]] = f
                per_band.setdefault(f["index"], (time.perf_counter() - ts) * 1000)
    tries = 0
    while len(filters) < MAX_FILTERS and tries < 3:              # devicePEQ waits up to 10 s
        for r in dev.drain(500):
            if r[2] == CMD_PEQ and len(r) >= 33:
                f = parse_filter(r)
                filters[f["index"]] = f
        tries += 1
    t["bands_ms"] = (time.perf_counter() - t2) * 1000
    t3 = time.perf_counter()
    g = dev.request([READ, CMD_GLOBAL_GAIN, END], CMD_GLOBAL_GAIN, timeout_ms=1000)
    gg = g[5] - 256 if g[5] > 127 else g[5]                      # Int8(data[4])
    t["gain_ms"] = (time.perf_counter() - t3) * 1000
    t["total_ms"] = (time.perf_counter() - t0) * 1000
    t["per_band_first_reply_ms"] = {k: round(v, 1) for k, v in sorted(per_band.items())}
    missing = [i for i in range(MAX_FILTERS) if i not in filters]
    return {
        "device": "CrinEar Protocol Micro", "vid": "0x3302", "pid": "0xC20F",
        "firmware_version": version, "current_slot": slot,
        "preamp_db": gg,
        "filters": [filters[i] for i in sorted(filters)],
        "missing_filters": missing,
        "compensation": {"freq_factor": FREQ_FACTOR, "q_model": "cosNyquist", "design_fs": DESIGN_FS,
                         "note": "freq_hz/q are devicePEQ-decompensated (realised); raw.* are register values"},
        "timing_ms": {k: (round(v, 1) if isinstance(v, float) else v) for k, v in t.items()},
    }


def to_apo(state):
    lines = [f"Preamp: {state['preamp_db']:.1f} dB"]
    for n, f in enumerate(state["filters"], 1):
        on = "ON" if f["enabled"] else "OFF"
        typ = APO_TYPES[f["type"]]
        if f["type"] in ("LP", "HP"):
            lines.append(f"Filter {n}: {on} {typ} Fc {f['freq_hz']:.0f} Hz Q {f['q']:.2f}")
        else:
            lines.append(f"Filter {n}: {on} {typ} Fc {f['freq_hz']:.0f} Hz Gain {f['gain_db']:.1f} dB Q {f['q']:.2f}")
    return "\n".join(lines) + "\n"


def compute_iir(freq, gain, q, typ="PK"):
    """Port of computeIIRFilter (walkplayHidHandler.js L742-822): 5 x int32 LE, Q30."""
    A = math.sqrt(10 ** (gain / 20))
    w0 = freq * 6.283185307179586 / 96000
    s, c = math.sin(w0), math.cos(w0)
    alpha = s / (2 * q)
    if typ in ("LSQ", "HSQ"):
        sa = (s / 2) * math.sqrt((A + 1 / A) * (1 / q - 1) + 2)
        k = 2 * math.sqrt(A) * sa
        if typ == "LSQ":
            b0 = A * ((A + 1) - (A - 1) * c + k); b1 = 2 * A * ((A - 1) - (A + 1) * c)
            b2 = A * ((A + 1) - (A - 1) * c - k); a0 = (A + 1) + (A - 1) * c + k
            a1 = -2 * ((A - 1) + (A + 1) * c); a2 = (A + 1) + (A - 1) * c - k
        else:
            b0 = A * ((A + 1) + (A - 1) * c + k); b1 = -2 * A * ((A - 1) + (A + 1) * c)
            b2 = A * ((A + 1) + (A - 1) * c - k); a0 = (A + 1) - (A - 1) * c + k
            a1 = 2 * ((A - 1) - (A + 1) * c); a2 = (A + 1) - (A - 1) * c - k
    else:  # PK; note LP/HP also fall through to the PK formula in devicePEQ
        b0 = 1 + alpha * A; b1 = -2 * c; b2 = 1 - alpha * A
        a0 = 1 + alpha / A; a1 = -2 * c; a2 = 1 - alpha / A
    S = 1073741824
    vals = [round(b0 / a0 * S), round(b1 / a0 * S), round(b2 / a0 * S), -round(a1 / a0 * S), -round(a2 / a0 * S)]
    out = b""
    for v in vals:
        out += (v & 0xFFFFFFFF).to_bytes(4, "little")
    return out


def js_round(x):
    return math.floor(x + 0.5)


def filter_write_payload(index, freq_sent, gain, q_sent, typ, slot):
    """pushToDevice packet (L153-163). freq_sent/q_sent are ALREADY compensated."""
    biquad = compute_iir(freq_sent, gain, q_sent, typ)
    f = int(freq_sent) & 0xFFFF                      # JS (value >> 0) truncates
    qq = js_round(q_sent * 256) & 0xFFFF
    gg = js_round(gain * 256) & 0xFFFF
    return ([WRITE, CMD_PEQ, 0x18, 0x00, index, 0x00, 0x00] + list(biquad)
            + [f & 0xFF, f >> 8, qq & 0xFF, qq >> 8, gg & 0xFF, gg >> 8, TYPE_CODES[typ], 0x00, slot, END])


def global_gain_payload(db):
    return [WRITE, CMD_GLOBAL_GAIN, 0x02, 0x00, js_round(db) & 0xFF]


# devicePEQ commit sequence after filter writes (L182-188) - PERSISTS to flash.
COMMIT_SEQUENCE = [
    [WRITE, 0x05, END],
    [WRITE, 0x17, END],
    [WRITE, 0x0A, 0x04, 0x00, 0x00, 0xFF, 0xFF, END],   # TEMP_WRITE
    [WRITE, 0x01, 0x01, END],                            # FLASH_EQ enable+persist (slot byte absent)
]
