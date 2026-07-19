#!/usr/bin/env python3
"""Validate Tryptify Player Visuals presets without a Gradle build.

Parses the PRESETS lists in LyricsFxSettings.kt and PlayerGlassSettings.kt and
mirrors what the two `presets are all valid after clamping` unit tests (plus the
glass `presets never touch the user colour` test) check:

  * every numeric field is inside its clamped() range,
  * no preset sets a personal field (glass tintColor/previewBg/sampleRings; the
    lyric font/perf/glow-behind-art fields),
  * preset names are unique within each list.

Run from the repo root:
    python3 .claude/skills/player-visuals-themes/scripts/validate_theme_ranges.py

Exit code 0 = all good, 1 = at least one problem. This is a static check, not a
substitute for on-device verification — tilt/motion/light-angle looks still need
a physical device.
"""
from __future__ import annotations
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[4]
MODEL = REPO / "app/src/main/java/tf/monochrome/android/domain/model"

# clamped() ranges — keep in sync with the data classes if a field is added.
LYR_RANGES = {
    "fontSizeSp": (14, 34), "letterSpacingSp": (-1, 1), "edgeMarginDp": (0, 48),
    "maxWrapLines": (1, 3), "glassBodyOpacity": (0.2, 1), "glassRefraction": (0, 0.4),
    "glassRimBrightness": (0, 2), "glassDispersion": (0, 2), "rotationDegrees": (0, 25),
    "waveSpeed": (0.25, 3), "wavePhaseStep": (0.05, 0.9), "waveTravelDp": (0, 8),
    "shadowDepth": (0, 1), "bassReact": (0, 1), "pumpAmount": (0, 0.25),
    "attackMs": (4, 60), "releaseMs": (40, 500), "bounce": (0, 1), "popAmount": (0, 0.2),
    "glowRadiusDp": (0, 160), "glowBrightness": (0, 0.6),
}
LYR_PERSONAL = {
    "customFont", "customFontPath", "bluetoothDelayMs", "glassSampleRings",
    "fxaa", "fxaaStrength", "glowBehindArt",
}

GLS_RANGES = {
    "bodyOpacity": (0.2, 1), "refraction": (0, 0.4), "rimBrightness": (0, 2),
    "dispersion": (0, 2), "roundness": (0.5, 2), "depth": (0.5, 2), "shadowDepth": (0, 1),
    "reflection": (0, 2), "gloss": (0, 1), "surfaceMotion": (0, 1),
    "tiltReactivity": (0, 1.5), "lightAngleDeg": (0, 360), "edgeWidth": (0, 1),
    "frost": (0, 1), "shadowSoftness": (0, 1), "shadowTint": (0, 1),
}
GLS_PERSONAL = {"sampleRings", "tintColor", "previewBg"}

ENTRY_RE_TMPL = r'"([^"]+)"\s*to\s*{cls}\(([^)]*)\)'
FLOAT_RE = re.compile(r'(\w+)\s*=\s*(-?\d+(?:\.\d+)?)f?\b')
BOOL_RE = re.compile(r'(\w+)\s*=\s*(true|false)\b')


def presets_block(text: str) -> str:
    i = text.index("PRESETS")
    return text[i:]


def parse(path: Path, cls: str):
    text = presets_block(path.read_text())
    out = []
    for name, body in re.findall(ENTRY_RE_TMPL.format(cls=cls), text, re.S):
        nums = {k: float(v) for k, v in FLOAT_RE.findall(body)}
        bools = {k: (v == "true") for k, v in BOOL_RE.findall(body)}
        out.append((name, nums, bools))
    return out


def check(path: Path, cls: str, ranges: dict, personal: set) -> int:
    problems = 0
    names_seen = {}
    presets = parse(path, cls)
    for name, nums, bools in presets:
        key = name.lower()
        if key in names_seen:
            print(f"  DUP NAME [{cls}] '{name}' (also '{names_seen[key]}')")
            problems += 1
        names_seen[key] = name
        for field, val in nums.items():
            if field in personal:
                print(f"  PERSONAL FIELD SET [{cls}] {name}.{field}={val} — remove it")
                problems += 1
                continue
            if field in ranges:
                lo, hi = ranges[field]
                if not (lo <= val <= hi):
                    print(f"  OUT OF RANGE [{cls}] {name}.{field}={val} not in {lo}..{hi}")
                    problems += 1
        for field in bools:
            if field in personal:
                print(f"  PERSONAL FIELD SET [{cls}] {name}.{field} — remove it")
                problems += 1
    print(f"  {cls}: {len(presets)} presets parsed")
    return problems


def main() -> int:
    lyr = MODEL / "LyricsFxSettings.kt"
    gls = MODEL / "PlayerGlassSettings.kt"
    if not lyr.exists() or not gls.exists():
        print(f"Could not find model files under {MODEL}", file=sys.stderr)
        return 2
    problems = 0
    problems += check(lyr, "LyricsFxSettings", LYR_RANGES, LYR_PERSONAL)
    problems += check(gls, "PlayerGlassSettings", GLS_RANGES, GLS_PERSONAL)
    print("RESULT:", "ALL VALID ✓" if problems == 0 else f"{problems} PROBLEM(S) ✗")
    return 0 if problems == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
