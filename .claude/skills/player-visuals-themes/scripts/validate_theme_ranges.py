#!/usr/bin/env python3
"""Validate Tryptify Player Visuals presets without a Gradle build.

Parses the PRESETS lists in LyricsFxSettings.kt and PlayerGlassSettings.kt and
mirrors what the two `presets are all valid after clamping` unit tests (plus the
glass `presets never touch the user colour or preview background` test) check:

  * every numeric field is inside its clamped() range,
  * no preset sets a personal field (one withPersonalFrom carries over),
  * no preset sets a field the data class does not have,
  * preset names are unique within each list.

The ranges and the personal-field set are READ OUT OF THE KOTLIN, not restated
here. The previous version kept its own copy under a comment asking whoever
added a field to keep the two in sync, and that is exactly what stopped
happening: `hazeBlurDp` and `hazeTint` were never added, `miniProgressBar` was
never added to the personal set, `popAmount` stayed behind after the field was
renamed, and the glass `bodyOpacity` floor was 0.2 — the Lyrics FX sibling's
bound, copied onto a field the model clamps from 0. A validator that disagrees
with the model is worse than none: it green-lights themes the tests will reject
and rejects themes that are perfectly legal.

So this fails loudly rather than silently when it cannot read the contract. A
clamp line it cannot parse, or a field with no bound and no personal marking, is
reported — an unchecked field is a finding, not a pass.

Run from anywhere:
    python3 .claude/skills/player-visuals-themes/scripts/validate_theme_ranges.py

Exit code 0 = all good, 1 = at least one preset problem, 2 = could not read the
model. This is a static check, not a substitute for `./gradlew
:app:testDebugUnitTest` or for on-device verification — tilt/motion/light-angle
looks still need a physical device.
"""
from __future__ import annotations
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[4]
MODEL = REPO / "app/src/main/java/tf/monochrome/android/domain/model"

# Fields that carry no range because they are not numeric. Booleans and strings
# are still checked for existence and for personal-field violations.
NON_NUMERIC = {"Boolean", "String"}

# Colour pins are Ints with no clamp line — the model validates them by the
# separate rule that a preset must leave them at 0, which withPersonalFrom
# enforces and a unit test asserts.
UNBOUNDED_OK = {"tintColor", "previewBg"}


class ModelReadError(Exception):
    """The Kotlin could not be read well enough to check anything."""


def _section(text: str, start: str, end: str, what: str) -> str:
    try:
        i = text.index(start)
        return text[i:text.index(end, i)]
    except ValueError as exc:
        raise ModelReadError(f"could not locate {what}") from exc


def fields(text: str, cls: str) -> dict[str, str]:
    """Every constructor property on the data class, name -> declared type."""
    body = _section(text, f"data class {cls}(", "\n) {", f"{cls} constructor")
    found = dict(re.findall(r"^\s*val (\w+):\s*([\w<>?]+)\s*=", body, re.M))
    if not found:
        raise ModelReadError(f"no properties parsed from {cls}")
    return found


def ranges(text: str, cls: str) -> dict[str, tuple[float, float]]:
    """Bounds as clamped() actually applies them."""
    body = _section(text, "fun clamped()", "\n    }", f"{cls}.clamped()")
    out: dict[str, tuple[float, float]] = {}
    # Float fields go through a local `c(min, max, fallback)` helper; Int fields
    # go straight to coerceIn. The back-reference is what ties the bound to the
    # field it is actually assigned to, so a line like
    # `depth = roundness.c(0.5f, 2f, ...)` is not read as a bound on depth.
    for pattern in (
        r"(\w+)\s*=\s*\1\.c\((-?[\d.]+)f,\s*(-?[\d.]+)f",
        r"(\w+)\s*=\s*\1\.coerceIn\((-?\d+),\s*(-?\d+)\)",
    ):
        for name, lo, hi in re.findall(pattern, body):
            out[name] = (float(lo), float(hi))
    if not out:
        raise ModelReadError(f"no clamp bounds parsed from {cls}.clamped()")
    return out


def defaults(text: str, cls: str) -> dict[str, str]:
    """Each field's declared default, as written (minus Kotlin's `f` suffix)."""
    body = _section(text, f"data class {cls}(", "\n) {", f"{cls} constructor")
    out = {}
    for name, value in re.findall(r"^\s*val (\w+):\s*[\w<>?]+\s*=\s*(.+?),\s*$", body, re.M):
        out[name] = value[:-1] if re.fullmatch(r"-?[\d.]+f", value) else value
    return out


def personal(text: str, cls: str) -> set[str]:
    """Fields withPersonalFrom carries over, so a preset must never set them."""
    body = _section(text, "fun withPersonalFrom", "\n    )", f"{cls}.withPersonalFrom")
    found = set(re.findall(r"(\w+)\s*=\s*other\.\1", body))
    if not found:
        raise ModelReadError(f"no personal fields parsed from {cls}.withPersonalFrom")
    return found


FLOAT_RE = re.compile(r"(\w+)\s*=\s*(-?\d+(?:\.\d+)?)f?\b")
BOOL_RE = re.compile(r"(\w+)\s*=\s*(true|false)\b")


def parse_presets(text: str, cls: str):
    try:
        block = text[text.index("PRESETS"):]
    except ValueError as exc:
        raise ModelReadError(f"could not locate {cls}.PRESETS") from exc
    out = []
    for name, body in re.findall(rf'"([^"]+)"\s*to\s*{cls}\(([^)]*)\)', block, re.S):
        nums = {k: float(v) for k, v in FLOAT_RE.findall(body)}
        bools = {k: (v == "true") for k, v in BOOL_RE.findall(body)}
        out.append((name, nums, bools))
    return out


def audit_contract(cls: str, declared: dict[str, str], bounds: dict, own: set) -> int:
    """Report any field this script would not actually be checking.

    The drift this replaces was invisible precisely because nothing complained
    about a field nobody had listed.
    """
    problems = 0
    for field, kind in declared.items():
        if kind in NON_NUMERIC or field in own or field in bounds or field in UNBOUNDED_OK:
            continue
        print(f"  UNCHECKED FIELD [{cls}] {field}: {kind} has no clamp bound and is not personal")
        problems += 1
    return problems


DOC = Path(__file__).resolve().parent.parent / "SKILL.md"

ROW_RE = re.compile(r"^\|\s*`(\w+)`\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|", re.M)


def _num(text: str):
    try:
        return float(text)
    except ValueError:
        return None


def check_doc(cls: str, heading: str, declared: dict, bounds: dict,
              defaults: dict, own: set) -> int:
    """Hold SKILL.md's parameter table to the model.

    This is the check that was missing, and its absence is the whole reason
    this task existed: every Player Glass default in the table had drifted from
    the model — bodyOpacity read 0.5 against a shipped 0.2, lightAngleDeg read
    135 against 215.1965 — while hard rule 3 tells an author that the table is
    what a field they omit will inherit. A wrong default table is worse than no
    table, because it is specific enough to act on.
    """
    if not DOC.exists():
        print(f"  DOC MISSING [{cls}] {DOC} — cannot check the table")
        return 1

    text = DOC.read_text()
    try:
        start = text.index(heading)
    except ValueError:
        print(f"  DOC SECTION MISSING [{cls}] no '{heading}' heading in SKILL.md")
        return 1
    nxt = text.find("\n## ", start + len(heading))
    section = text[start:nxt if nxt != -1 else len(text)]

    problems = 0
    documented = set()
    for field, rng, default in ROW_RE.findall(section):
        documented.add(field)
        if field not in declared:
            print(f"  DOC UNKNOWN FIELD [{cls}] table documents `{field}`, "
                  f"not on the data class")
            problems += 1
            continue
        if field in own:
            print(f"  DOC PERSONAL IN TABLE [{cls}] `{field}` is personal and "
                  f"should not sit in the tunable table")
            problems += 1

        want_default = defaults.get(field)
        if want_default is not None:
            got, wanted = _num(default), _num(want_default)
            same = (got is not None and wanted is not None and abs(got - wanted) < 1e-9) \
                or default.strip() == want_default.strip()
            if not same:
                print(f"  DOC DEFAULT WRONG [{cls}] `{field}` documented {default}, "
                      f"model says {want_default}")
                problems += 1

        if field in bounds:
            lo, hi = bounds[field]
            parts = rng.split("..")
            ok = len(parts) == 2 and _num(parts[0]) is not None \
                and abs(_num(parts[0]) - lo) < 1e-9 and abs(_num(parts[1]) - hi) < 1e-9
            if not ok:
                shown = f"{lo:g}..{hi:g}"
                print(f"  DOC RANGE WRONG [{cls}] `{field}` documented {rng}, "
                      f"model clamps {shown}")
                problems += 1

    for field in declared:
        if field in own or field in documented:
            continue
        print(f"  DOC MISSING FIELD [{cls}] `{field}` is tunable and absent from the table")
        problems += 1

    return problems


def check(path: Path, cls: str, doc_heading: str) -> int:
    text = path.read_text()
    declared = fields(text, cls)
    bounds = ranges(text, cls)
    own = personal(text, cls)

    problems = audit_contract(cls, declared, bounds, own)
    problems += check_doc(cls, doc_heading, declared, bounds, defaults(text, cls), own)
    names_seen: dict[str, str] = {}
    presets = parse_presets(text, cls)

    for name, nums, bools in presets:
        key = name.lower()
        if key in names_seen:
            print(f"  DUP NAME [{cls}] '{name}' (also '{names_seen[key]}')")
            problems += 1
        names_seen[key] = name

        for field, val in nums.items():
            if field not in declared:
                print(f"  UNKNOWN FIELD [{cls}] {name}.{field}={val} — not on the data class")
                problems += 1
                continue
            if field in own:
                print(f"  PERSONAL FIELD SET [{cls}] {name}.{field}={val} — remove it")
                problems += 1
                continue
            if field in bounds:
                lo, hi = bounds[field]
                if not (lo <= val <= hi):
                    print(f"  OUT OF RANGE [{cls}] {name}.{field}={val} not in {lo}..{hi}")
                    problems += 1

        for field in bools:
            if field not in declared:
                print(f"  UNKNOWN FIELD [{cls}] {name}.{field} — not on the data class")
                problems += 1
            elif field in own:
                print(f"  PERSONAL FIELD SET [{cls}] {name}.{field} — remove it")
                problems += 1

    print(f"  {cls}: {len(presets)} presets parsed, "
          f"{len(bounds)} bounded fields, {len(own)} personal ({', '.join(sorted(own))})")
    return problems


def main() -> int:
    targets = [
        ("LyricsFxSettings.kt", "LyricsFxSettings",
         "## Lyrics FX parameter reference"),
        ("PlayerGlassSettings.kt", "PlayerGlassSettings",
         "## Player Glass parameter reference"),
    ]
    for filename, _, _ in targets:
        if not (MODEL / filename).exists():
            print(f"Could not find {filename} under {MODEL}", file=sys.stderr)
            return 2

    problems = 0
    for filename, cls, heading in targets:
        try:
            problems += check(MODEL / filename, cls, heading)
        except ModelReadError as exc:
            print(f"Could not read the contract from {filename}: {exc}", file=sys.stderr)
            print("The model's shape changed; fix this script before trusting it.",
                  file=sys.stderr)
            return 2

    print("RESULT:", "ALL VALID ✓" if problems == 0 else f"{problems} PROBLEM(S) ✗")
    return 0 if problems == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
