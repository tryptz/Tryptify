#!/usr/bin/env python3
"""Check the bundled genre graph is internally consistent.

    python3 tools/validate_genre_graph.py

Exits non-zero on any violation, so it can gate a commit the way
validate_theme_ranges.py does for the Player Visuals presets.

What it enforces, and why each one is worth enforcing:

  ids/aliases unique      a duplicate makes one of the two unreachable, silently
  references resolve      a typo'd parent or neighbour is a dead edge nobody sees
  no cycles in parents    the resolver walks parents to a family root; a cycle hangs it
  reachable from a root   an orphan node can never be reached by a graph walk
  bpm ordered, sane       (176, 170) would silently exclude every track
  energy/valence in 0..1  the mood matcher assumes the unit square
  moods >= 4 genres       a mood that resolves to one dead genre renders an empty page
  edges symmetric         asymmetry here is always an oversight, never a claim
  history keyed to real   an entry under an unknown id is text nothing can show
  history cited           prose without an article and revision can't be checked,
                          and can't be attributed as its licence requires
"""

from __future__ import annotations

import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GRAPH = os.path.join(ROOT, "app", "src", "main", "assets", "genre_graph.json")
VOCAB = os.path.join(ROOT, "app", "src", "main", "assets", "genre_vocabulary.json")
HISTORY = os.path.join(ROOT, "app", "src", "main", "assets", "genre_history.json")

# The ceiling is set by the speedcore branch rather than by dance music: an
# extratone track runs past 1,000 BPM by definition, and clamping it would turn
# the one fact that defines the genre into a lie.
BPM_MIN, BPM_MAX = 40, 4000
MIN_MOOD_GENRES = 4


def main() -> int:
    if not os.path.exists(GRAPH):
        print(f"FAIL: {GRAPH} not found — run tools/build_genre_graph.py")
        return 1

    with open(GRAPH, encoding="utf-8") as fh:
        graph = json.load(fh)

    errors: list[str] = []
    genres = graph["genres"]
    by_id = {g["id"]: g for g in genres}
    families = {f["id"] for f in graph["families"]}
    mood_ids = {m["id"] for m in graph["moods"]}

    # ── uniqueness ──────────────────────────────────────────────────────
    seen_ids: set[str] = set()
    seen_aliases: dict[str, str] = {}
    for g in genres:
        if g["id"] in seen_ids:
            errors.append(f"duplicate genre id: {g['id']}")
        seen_ids.add(g["id"])
        for alias in [g["name"], *g["aka"]]:
            key = alias.lower()
            if key in seen_aliases and seen_aliases[key] != g["id"]:
                errors.append(
                    f"alias {alias!r} claimed by both {seen_aliases[key]} and {g['id']}"
                )
            seen_aliases[key] = g["id"]

    # ── references, ranges, families ────────────────────────────────────
    for g in genres:
        gid = g["id"]
        if g["family"] not in families:
            errors.append(f"{gid}: unknown family {g['family']!r}")
        for parent in g["parents"]:
            if parent not in by_id:
                errors.append(f"{gid}: unknown parent {parent!r}")
            if parent == gid:
                errors.append(f"{gid}: is its own parent")
        for target, weight in g["near"]:
            if target not in by_id:
                errors.append(f"{gid}: unknown neighbour {target!r}")
            if target == gid:
                errors.append(f"{gid}: is its own neighbour")
            if not 0.0 < weight <= 1.0:
                errors.append(f"{gid}->{target}: weight {weight} outside (0, 1]")
        for mood in g["moods"]:
            if mood not in mood_ids:
                errors.append(f"{gid}: unknown mood {mood!r}")

        lo, hi = g["bpm"]
        # (0, 0) is the deliberate "tempo is not a useful descriptor here"
        # marker — free jazz, drone, most classical.
        if (lo, hi) != (0, 0):
            if lo > hi:
                errors.append(f"{gid}: bpm range reversed {g['bpm']}")
            if not (BPM_MIN <= lo <= BPM_MAX and BPM_MIN <= hi <= BPM_MAX):
                errors.append(f"{gid}: bpm {g['bpm']} outside {BPM_MIN}..{BPM_MAX}")

        for field in ("energy", "valence"):
            if not 0.0 <= g[field] <= 1.0:
                errors.append(f"{gid}: {field} {g[field]} outside 0..1")

        start, end = g["era"]
        if end is not None and start > end:
            errors.append(f"{gid}: era reversed {g['era']}")

    # ── no cycles, and everything reaches a root ────────────────────────
    def root_of(gid: str, seen: frozenset[str] = frozenset()) -> bool:
        if gid in seen:
            errors.append(f"{gid}: parent cycle {sorted(seen)}")
            return False
        node = by_id.get(gid)
        if node is None:
            return False
        if not node["parents"]:
            return True
        return any(root_of(p, seen | {gid}) for p in node["parents"])

    for g in genres:
        if not root_of(g["id"]):
            errors.append(f"{g['id']}: no path to a family root")

    # ── neighbour symmetry ──────────────────────────────────────────────
    for g in genres:
        for target, weight in g["near"]:
            other = by_id.get(target)
            if other is None:
                continue
            back = dict((t, w) for t, w in other["near"]).get(g["id"])
            if back is None:
                errors.append(f"{g['id']}->{target} has no return edge")
            elif abs(back - weight) > 1e-6:
                errors.append(
                    f"{g['id']}<->{target} weights disagree ({weight} vs {back})"
                )

    # ── moods ───────────────────────────────────────────────────────────
    for m in graph["moods"]:
        if len(m["genres"]) < MIN_MOOD_GENRES:
            errors.append(
                f"mood {m['id']}: only {len(m['genres'])} genres, need {MIN_MOOD_GENRES}"
            )
        for gid, weight in m["genres"]:
            if gid not in by_id:
                errors.append(f"mood {m['id']}: unknown genre {gid!r}")
            if not 0.0 < weight <= 1.0:
                errors.append(f"mood {m['id']}->{gid}: weight {weight} outside (0, 1]")
        lo, hi = m["bpm"]
        if lo > hi:
            errors.append(f"mood {m['id']}: bpm range reversed {m['bpm']}")
        vlo, vhi = m.get("valence", [0.0, 1.0])
        if not (0.0 <= vlo <= vhi <= 1.0):
            errors.append(f"mood {m['id']}: valence window {m.get('valence')} invalid")
        elo, ehi = m["energy"]
        if not (0.0 <= elo <= ehi <= 1.0):
            errors.append(f"mood {m['id']}: energy window {m['energy']} invalid")

    # ── vocabulary ──────────────────────────────────────────────────────
    if os.path.exists(VOCAB):
        with open(VOCAB, encoding="utf-8") as fh:
            vocab = json.load(fh)
        unknown = {t for t in vocab["names"].values() if t and t not in by_id}
        if unknown:
            errors.append(f"vocabulary maps to unknown genres: {sorted(unknown)[:5]}")
        mapped = sum(1 for v in vocab["names"].values() if v)
        print(f"  vocabulary: {len(vocab['names'])} names, {mapped} mapped")
    else:
        print("  vocabulary: not built")

    # ── researched history ──────────────────────────────────────────────
    if os.path.exists(HISTORY):
        with open(HISTORY, encoding="utf-8") as fh:
            history = json.load(fh)
        entries = history["entries"]
        unknown = [gid for gid in entries if gid not in by_id]
        if unknown:
            errors.append(f"history filed under unknown genres: {sorted(unknown)[:5]}")
        if not history.get("attribution") or not history.get("license"):
            errors.append("history asset ships without its attribution or licence")
        for gid, entry in entries.items():
            # Every one of these is what separates a researched entry from a
            # written one. An entry that can't name the article and revision it
            # came from can't be checked, so it doesn't ship.
            if not entry.get("summary", "").strip():
                errors.append(f"history {gid}: empty summary")
            if not entry.get("url", "").startswith("https://en.wikipedia.org/wiki/"):
                errors.append(f"history {gid}: no source article")
            if not entry.get("revision"):
                errors.append(f"history {gid}: no source revision")
            for section in entry.get("sections", []):
                if not section.get("heading") or not section.get("text"):
                    errors.append(f"history {gid}: section missing heading or text")
        covered = len(entries) / len(genres) if genres else 0
        sections = sum(len(e.get("sections", [])) for e in entries.values())
        print(f"  history: {len(entries)} genres ({covered:.0%}), {sections} sections")
    else:
        print("  history: not built")

    print(f"  genres: {len(genres)} across {len(families)} families")
    print(f"  moods:  {len(graph['moods'])}")
    electronic = sum(1 for g in genres if g["family"] == "electronic")
    print(f"  electronic: {electronic}")

    reachable = {e[0] for m in graph["moods"] for e in m["genres"]}
    orphans = [g["id"] for g in genres if g["id"] not in reachable]
    if orphans:
        errors.append(
            f"{len(orphans)} genre(s) reachable from no mood, e.g. {orphans[:5]}"
        )

    if errors:
        print(f"\nRESULT: {len(errors)} problem(s)")
        for e in errors[:40]:
            print(f"  - {e}")
        if len(errors) > 40:
            print(f"  … and {len(errors) - 40} more")
        return 1

    print("\nRESULT: ALL VALID ✓")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
