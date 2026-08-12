#!/usr/bin/env python3
"""Expand tools/genre_source.py into the bundled assets.

    python3 tools/build_genre_graph.py

Writes:
    app/src/main/assets/genre_graph.json       curated nodes + moods
    app/src/main/assets/genre_vocabulary.json  MusicBrainz names -> nearest node
    app/src/main/assets/genre_history.json     researched histories, by genre id

The first two are expanded from genre_source.py. The third is copied out of the
research cache (tools/genre_history.json, written by fetch_genre_history.py) and
filtered to the genres that still exist — this script never goes to the network.

Run the validator afterwards; CI and the pre-push check both do.
"""

from __future__ import annotations

import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from genre_source import FAMILIES, GENRES, MOODS  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
MB_LIST = os.path.join(ROOT, "tools", "musicbrainz_genres.txt")

ATTRIBUTION = (
    "Genre vocabulary from MusicBrainz (musicbrainz.org), released under CC0. "
    "Lineage, tempo and era researched from public sources including Ishkur's "
    "Guide to Electronic Music and Beatport's genre taxonomy; no third-party "
    "dataset is reproduced. Energy, valence and mood assignments are original."
)

# Words that carry no discriminating power when matching a MusicBrainz name
# against a curated node. "progressive house" must not collapse onto "house"
# purely because it ends in it, but "uk hip hop" collapsing onto "hip-hop" is
# exactly right — so the head noun wins and modifiers are what we strip.
STOPWORDS = {"music", "style", "styles", "genre"}


def slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


def fold(text: str) -> str:
    """Normalised comparison key: lowercase, punctuation and spacing removed.

    `&` becomes `and` first so "d&b" and "d and b" agree, and "drum & bass"
    folds to the same key as "drum and bass" and "drumandbass".
    """
    t = text.lower().replace("&", " and ")
    t = re.sub(r"[^a-z0-9]+", " ", t)
    return " ".join(w for w in t.split() if w not in STOPWORDS)


def tight(text: str) -> str:
    return fold(text).replace(" ", "")


def build_genres() -> list[dict]:
    out = []
    for (gid, name, family, parents, aka, bpm, era, energy, valence, moods, near) in GENRES:
        out.append({
            "id": gid,
            "name": name,
            "family": family,
            "parents": list(parents),
            "aka": list(aka),
            "bpm": list(bpm),
            "era": [era[0], era[1]],
            "energy": round(float(energy), 3),
            "valence": round(float(valence), 3),
            "moods": list(moods),
            "near": [[t, round(float(w), 3)] for t, w in near],
        })
    return out


def symmetrise(nodes: list[dict]) -> None:
    """Make every `near` edge bidirectional, keeping the stronger weight.

    A one-way sideways edge is nearly always an authoring oversight rather than
    a real claim — nobody means "liquid DnB is near deep house but deep house is
    not near liquid DnB". Doing it here keeps the source file half the size and
    the graph honest.
    """
    by_id = {n["id"]: n for n in nodes}
    for node in nodes:
        for target, weight in list(node["near"]):
            other = by_id.get(target)
            if other is None:
                continue  # the validator will flag it
            existing = dict((t, w) for t, w in other["near"])
            if existing.get(node["id"], -1.0) < weight:
                other["near"] = [[t, w] for t, w in existing.items() if t != node["id"]]
                other["near"].append([node["id"], weight])
    for node in nodes:
        node["near"] = sorted(node["near"], key=lambda e: (-e[1], e[0]))


import math

# A family with three genres still needs a readable slice of its cluster, or
# every small root crowds into one arc while the big ones eat the rest.
MIN_ROOT_LEAVES = 10

# Cluster radius = BASE + SPAN * sqrt(population). Square root, so a family with
# four times the genres is twice as wide rather than four times — which is what
# stops electronic (178 nodes) from swallowing the canvas.
CLUSTER_BASE = 44.0
CLUSTER_SPAN = 26.0

# Clearance between neighbouring clusters on the ring, as a multiple of radius.
CLUSTER_GAP = 1.14

# How far a fusion genre leans toward the families it also belongs to, and how
# far outside its own cluster it is ever allowed to end up. The cap is the load
# bearing part: an uncapped pull tears nodes out of their cluster entirely and
# the map stops reading as clusters at all.
BRIDGE_PULL = 0.85
BRIDGE_REACH = 1.12
BRIDGE_PASSES = 3


def bake_layout(nodes: list[dict], families: list[dict]) -> None:
    """Lay the graph out as several radial clusters that intertwine.

    One cluster per family, each a radial tree around its own centre. Three
    things then make the clusters relate to each other rather than merely sit
    side by side:

    * **Ring order follows affinity.** Families are walked greedily by how many
      edges cross between them, so hip-hop lands beside electronic and metal
      beside rock instead of wherever the source file happened to list them.
    * **Each cluster is rotated** so its bridging genres face the families they
      bridge to. Jazz's fusion arm points at electronic; electronic's jazz-house
      arm points back.
    * **Fusion genres lean into the gap**, pulled toward their other families
      but capped at `BRIDGE_REACH` of their own cluster radius, so they come to
      rest on the rim facing their partner instead of escaping.

    That is the point of the arrangement: a fusion genre's position is an
    argument about what it is. A single radial tree can only file each genre
    under one parent and draw the rest as long crossing lines; separate
    clusters let the bridges physically sit in the space between.

    Emits absolute `x`/`y` in layout units (the renderer scales and centres
    them), plus `ring` — depth in the parent forest — which the renderer uses
    for dot size and label density.
    """
    by_id = {n["id"]: n for n in nodes}

    children: dict[str, list[str]] = {}
    for n in nodes:
        if n["parents"]:
            children.setdefault(n["parents"][0], []).append(n["id"])
    for kids in children.values():
        kids.sort(key=lambda c: by_id[c]["name"])

    _bake_rings(nodes, by_id)

    members: dict[str, list[str]] = {}
    for n in nodes:
        members.setdefault(n["family"], []).append(n["id"])

    radii = {f: CLUSTER_BASE + CLUSTER_SPAN * math.sqrt(len(ids))
             for f, ids in members.items()}
    order = _ring_order(nodes, by_id, members)
    ring_radius = _ring_radius(order, radii)

    centres: dict[str, tuple[float, float]] = {}
    # Angular share proportional to width, so a wide cluster gets the arc it
    # needs and a narrow one doesn't reserve space it won't use.
    widths = {f: 2 * math.asin(min(1.0, CLUSTER_GAP * radii[f] / ring_radius))
              for f in order}
    slack = (2 * math.pi - sum(widths.values())) / len(order)
    cursor = 0.0
    for f in order:
        share = widths[f] + slack
        angle = cursor + share / 2
        centres[f] = (ring_radius * math.cos(angle), ring_radius * math.sin(angle))
        cursor += share

    # ── radial tree inside each cluster ──────────────────────────────────
    for fam, ids in members.items():
        cx, cy = centres[fam]
        own = set(ids)
        # A genre filed under a parent in another family is not reachable from
        # any local root; it becomes a root of its own cluster instead, which is
        # also where it belongs — it is the family's doorway to that other one.
        roots = [i for i in ids
                 if not by_id[i]["parents"] or by_id[i]["parents"][0] not in own]
        roots.sort(key=lambda r: by_id[r]["name"])

        def leaf_count(gid, guard=0):
            kids = [c for c in children.get(gid, []) if c in own]
            if not kids or guard > 12:
                return 1
            return sum(leaf_count(c, guard + 1) for c in kids)

        budget = {r: max(leaf_count(r), MIN_ROOT_LEAVES) for r in roots}
        total = sum(budget.values()) or 1
        # Deepest local chain sets the gap, so every cluster fills its radius
        # rather than a shallow family drawing a tiny dot inside a wide circle.
        depth = max((_local_depth(r, children, own) for r in roots), default=0)
        gap = radii[fam] / (depth + 2)
        rotation = _rotation_for(fam, ids, by_id, centres, own)

        cursor = 0.0
        for r in roots:
            span = 2 * math.pi * budget[r] / total
            _place(r, cursor + rotation, cursor + span + rotation, leaf_count(r),
                   by_id, children, own, cx, cy, gap)
            cursor += span

    # ── lean the bridges into the gaps between their clusters ────────────
    for _ in range(BRIDGE_PASSES):
        moves: dict[str, tuple[float, float]] = {}
        for n in nodes:
            edges = [(p, 1.0) for p in n["parents"]] + [(t, w) for t, w in n["near"]]
            edges = [(by_id[o], w) for o, w in edges if o in by_id]
            crossing = [(o, w) for o, w in edges if o["family"] != n["family"]]
            if not crossing:
                continue
            total = sum(w for _o, w in edges) or 1.0
            outward = sum(w for _o, w in crossing) / total
            tw = sum(w for _o, w in crossing)
            tx = sum(o["x"] * w for o, w in crossing) / tw
            ty = sum(o["y"] * w for o, w in crossing) / tw
            # Scaled by how much of the genre actually points elsewhere, so
            # house — which has a couple of soul ancestors and forty electronic
            # relatives — stays home, while jazz house, whose every edge but one
            # crosses, travels most of the way to the border.
            #
            # And damped with depth, because a root dragged sideways takes its
            # whole subtree with it while a leaf takes only itself.
            pull = BRIDGE_PULL * outward / (1 + n["ring"])
            x = n["x"] + (tx - n["x"]) * pull
            y = n["y"] + (ty - n["y"]) * pull
            moves[n["id"]] = _clamped(x, y, centres[n["family"]],
                                      radii[n["family"]] * BRIDGE_REACH)
        for gid, (x, y) in moves.items():
            by_id[gid]["x"], by_id[gid]["y"] = x, y

    # ── separate anything that ended up on top of something else ─────────
    _relax(nodes)

    for n in nodes:
        n["x"] = round(n["x"], 2)
        n["y"] = round(n["y"], 2)
        n.pop("angle", None)


def _bake_rings(nodes: list[dict], by_id: dict) -> None:
    """Depth in the first-parent forest, regardless of which family it crosses.

    Kept separate from the per-cluster tree depth so that a child is always on a
    higher ring than its parent even when the two sit in different clusters.
    """
    def depth(gid, guard=0):
        node = by_id[gid]
        if "ring" in node:
            return node["ring"]
        parent = node["parents"][0] if node["parents"] else None
        node["ring"] = 0 if parent is None or parent not in by_id or guard > 12 \
            else depth(parent, guard + 1) + 1
        return node["ring"]

    for n in nodes:
        depth(n["id"])


def _local_depth(gid, children, own, guard=0):
    kids = [c for c in children.get(gid, []) if c in own]
    if not kids or guard > 12:
        return 0
    return 1 + max(_local_depth(c, children, own, guard + 1) for c in kids)


def _ring_order(nodes, by_id, members) -> list[str]:
    """Order families around the ring so the ones that touch end up adjacent.

    Greedy nearest-neighbour on the cross-family edge count. Not optimal — that
    is a travelling-salesman problem — but the difference between "adjacent to
    the family it shares fifty edges with" and "adjacent to whichever family was
    declared next in the source file" is the whole legibility win.
    """
    affinity: dict[tuple[str, str], float] = {}
    for n in nodes:
        for other, weight in [(p, 1.0) for p in n["parents"]] + list(n["near"]):
            o = by_id.get(other)
            if o is None or o["family"] == n["family"]:
                continue
            key = tuple(sorted((n["family"], o["family"])))
            affinity[key] = affinity.get(key, 0.0) + float(weight)

    remaining = sorted(members, key=lambda f: -len(members[f]))
    order = [remaining.pop(0)]
    while remaining:
        last = order[-1]
        nxt = max(remaining,
                  key=lambda f: (affinity.get(tuple(sorted((last, f))), 0.0),
                                 len(members[f])))
        remaining.remove(nxt)
        order.append(nxt)
    return order


def _ring_radius(order, radii) -> float:
    """Smallest ring on which every cluster fits without touching its neighbours.

    Each cluster of radius R needs an arc of 2·asin(GAP·R / ring); the ring is
    big enough once those arcs sum to under a full turn. Bisection rather than
    algebra because asin makes the closed form unpleasant.
    """
    def fits(ring):
        return sum(2 * math.asin(min(1.0, CLUSTER_GAP * radii[f] / ring))
                   for f in order) <= 2 * math.pi

    lo = max(radii.values()) * CLUSTER_GAP
    hi = lo
    while not fits(hi):
        hi *= 1.5
    for _ in range(48):
        mid = (lo + hi) / 2
        if fits(mid):
            hi = mid
        else:
            lo = mid
    return hi


def _rotation_for(fam, ids, by_id, centres, own) -> float:
    """Spin a cluster so its bridging genres face the families they bridge to.

    Sampled rather than solved: 72 candidate rotations, scored by how well each
    lines up every cross-family edge with the direction of the family it points
    at. Five degrees of precision is far finer than anyone can perceive on the
    map, and it costs nothing at build time.
    """
    cx, cy = centres[fam]
    links: list[tuple[float, float]] = []  # (angle within cluster, target bearing)
    for gid in ids:
        node = by_id[gid]
        for other, weight in [(p, 1.0) for p in node["parents"]] + list(node["near"]):
            o = by_id.get(other)
            if o is None or o["family"] == fam or o["family"] not in centres:
                continue
            ox, oy = centres[o["family"]]
            links.append((_slot_angle(gid, ids, own, by_id), math.atan2(oy - cy, ox - cx)))
    if not links:
        return 0.0

    best, best_score = 0.0, -1e9
    for step in range(72):
        theta = 2 * math.pi * step / 72
        score = sum(math.cos(a + theta - bearing) for a, bearing in links)
        if score > best_score:
            best, best_score = theta, score
    return best


def _slot_angle(gid, ids, own, by_id) -> float:
    """Where a genre would sit before rotation — its index in the local order.

    An approximation of the tree's own angular assignment, which is not known
    until the tree is placed. Good enough to choose a rotation with: what
    matters is the *relative* arrangement, and that is what this preserves.
    """
    order = sorted(ids, key=lambda i: (by_id[i]["ring"], by_id[i]["name"]))
    return 2 * math.pi * order.index(gid) / max(len(order), 1)


def _clamped(x, y, centre, reach) -> tuple[float, float]:
    cx, cy = centre
    dx, dy = x - cx, y - cy
    d = math.hypot(dx, dy)
    if d <= reach or d < 1e-6:
        return x, y
    return cx + dx / d * reach, cy + dy / d * reach


def _place(gid, start, end, leaf_total, by_id, children, own, cx, cy, gap):
    """Radial tree inside one cluster; returns the node's angle in radians."""
    slot = (end - start) / max(leaf_total, 1)
    taken = [0]

    def walk(nid, d, g):
        node = by_id[nid]
        kids = [c for c in children.get(nid, []) if c in own] if g <= 12 else []
        if not kids:
            angle = start + (taken[0] + 0.5) * slot
            taken[0] += 1
        else:
            angle = sum(walk(c, d + 1, g + 1) for c in kids) / len(kids)
        r = (d + 1) * gap
        node["x"] = cx + r * math.cos(angle)
        node["y"] = cy + r * math.sin(angle)
        return angle

    return walk(gid, 0, 0)


def _relax(nodes, min_dist=16.0, passes=24):
    """Push overlapping nodes apart.

    The bridge pull can land two genres on the same pixel, and a node hidden
    under another is one nobody can tap. A few passes of pairwise separation is
    enough — this runs once at build time, so it can afford to be naive.
    """
    for _ in range(passes):
        moved = False
        # Bucket by a grid so this stays O(n) rather than O(n²) per pass.
        cell = min_dist * 2
        grid: dict[tuple[int, int], list[dict]] = {}
        for n in nodes:
            grid.setdefault((int(n["x"] // cell), int(n["y"] // cell)), []).append(n)
        for (gx, gy), bucket in grid.items():
            near = []
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    near.extend(grid.get((gx + dx, gy + dy), ()))
            for a in bucket:
                for b in near:
                    if a is b:
                        continue
                    dx, dy = b["x"] - a["x"], b["y"] - a["y"]
                    d = math.hypot(dx, dy)
                    if d >= min_dist:
                        continue
                    if d < 1e-6:
                        dx, dy, d = 1.0, 0.0, 1.0
                    push = (min_dist - d) / 2.0
                    ux, uy = dx / d, dy / d
                    a["x"] -= ux * push
                    a["y"] -= uy * push
                    b["x"] += ux * push
                    b["y"] += uy * push
                    moved = True
        if not moved:
            return


def build_vocabulary(nodes: list[dict]) -> dict:
    """Map every MusicBrainz genre name onto its nearest curated node.

    Matching, in descending confidence: exact fold, exact tight fold, an alias,
    then longest curated name that is a trailing word-run of the MusicBrainz
    name ("progressive psytrance" -> psytrance, "uk hip hop" -> hip-hop). Names
    that match nothing are still kept with a null target: recognising that a
    string *is* a genre, even an unmapped one, is worth more than dropping it.
    """
    exact: dict[str, str] = {}
    tights: dict[str, str] = {}
    for node in nodes:
        for label in [node["name"], node["id"].replace("-", " "), *node["aka"]]:
            exact.setdefault(fold(label), node["id"])
            tights.setdefault(tight(label), node["id"])

    # Longest first, so "drum and bass" wins over "bass" on a trailing match.
    by_len = sorted(exact.items(), key=lambda kv: -len(kv[0]))

    mapping: dict[str, str | None] = {}
    if not os.path.exists(MB_LIST):
        print(f"warning: {MB_LIST} missing — vocabulary will be empty", file=sys.stderr)
        return mapping

    with open(MB_LIST, encoding="utf-8") as fh:
        names = [line.strip() for line in fh if line.strip()]

    for name in names:
        f = fold(name)
        if not f:
            continue
        hit = exact.get(f) or tights.get(tight(f))
        if hit is None:
            words = f.split()
            for i in range(1, len(words)):
                tail = " ".join(words[i:])
                for key, gid in by_len:
                    if key == tail:
                        hit = gid
                        break
                if hit:
                    break
        mapping[name] = hit
    return mapping



# ─── Popularity ──────────────────────────────────────────────────────────────

# Shared with app/build.gradle.kts. Charts read public tag data, so one key
# for everyone is the right shape; see the note there.
DEFAULT_LASTFM_KEY = "153452aaeaa3e666645274b3a9e5bb0a"

REACH_CACHE = os.path.join(ROOT, "tools", "genre_reach.json")

# Last.fm asks callers to be reasonable rather than publishing a rate; a quarter
# of a second between calls is well inside anything they enforce and puts a full
# cold fetch of the whole graph at roughly four minutes.
REACH_PACE_S = 0.25


def load_reach() -> dict:
    """Cached tag reach, keyed by genre id.

    Committed to the repo on purpose. The asset has to be rebuildable offline
    and byte-identical for a given source — a build that silently produces a
    different map depending on whether the network was up, or on what Last.fm's
    counts happened to be that afternoon, is not reproducible. Refreshing is an
    explicit act (`--popularity`), not a side effect of building.
    """
    if not os.path.exists(REACH_CACHE):
        return {}
    with open(REACH_CACHE, encoding="utf-8") as fh:
        return json.load(fh).get("reach", {})


def fetch_reach(nodes: list[dict], api_key: str, cache: dict) -> dict:
    """Fill in any genre the cache doesn't have yet. Existing entries are kept."""
    import time
    import urllib.parse
    import urllib.request

    missing = [n for n in nodes if n["id"] not in cache]
    if not missing:
        print("popularity  cache already covers every genre")
        return cache

    print(f"popularity  fetching {len(missing)} of {len(nodes)} "
          f"(~{len(missing) * REACH_PACE_S / 60:.1f} min)")
    for index, node in enumerate(missing):
        params = urllib.parse.urlencode({
            "method": "tag.getinfo", "tag": node["name"],
            "api_key": api_key, "format": "json",
        })
        request = urllib.request.Request(
            f"https://ws.audioscrobbler.com/2.0/?{params}",
            headers={"User-Agent": "Tryptify/1.0 ( https://github.com/tryptz/tryptify )"},
        )
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                payload = json.load(response)
            # A tag nobody has ever applied is a real answer, not a failure, and
            # zero is what should be recorded for it.
            cache[node["id"]] = int(payload.get("tag", {}).get("reach") or 0)
        except Exception as exc:  # noqa: BLE001 - one bad tag must not fail a build
            print(f"  ! {node['id']}: {exc}")
        if (index + 1) % 50 == 0:
            print(f"  {index + 1}/{len(missing)}")
        time.sleep(REACH_PACE_S)

    with open(REACH_CACHE, "w", encoding="utf-8") as fh:
        json.dump({"version": 1, "reach": cache}, fh,
                  ensure_ascii=False, indent=0, sort_keys=True)
        fh.write("\n")
    return cache


# ─── Researched history ──────────────────────────────────────────────────────

HISTORY_CACHE = os.path.join(ROOT, "tools", "genre_history.json")

HISTORY_ATTRIBUTION = (
    "Genre histories are extracts from English Wikipedia articles, each verified "
    "to be about the genre it is filed under and cited with the article and "
    "revision it was taken from. Text is available under the Creative Commons "
    "Attribution-ShareAlike 4.0 License."
)

HISTORY_LICENSE = "CC BY-SA 4.0"

# Provenance the research keeps for review but the app has no use for: which
# check passed, which of a genre's names found the article, and whether it took
# a search to get there. All three matter when an entry turns out to be wrong,
# and none of them is worth shipping in the APK.
HISTORY_INTERNAL = ("verified", "matched", "found_by")


def build_history(nodes: list[dict]) -> dict:
    """The researched histories, filtered to genres that still exist.

    A cache entry for a genre that has since been renamed or removed would
    otherwise ship forever, keyed by an id nothing looks up.
    """
    if not os.path.exists(HISTORY_CACHE):
        return {}
    with open(HISTORY_CACHE, encoding="utf-8") as fh:
        entries = json.load(fh).get("entries", {})

    known = {n["id"] for n in nodes}
    return {
        gid: {k: v for k, v in entry.items() if k not in HISTORY_INTERNAL}
        for gid, entry in sorted(entries.items())
        if gid in known
    }


# ─── Mood membership ─────────────────────────────────────────────────────────

# Ceiling for a membership derived from a genre's own `moods` declaration.
# Every hand-authored weight in MOODS sits at 0.6 or above, so derived members
# always sort below curated ones and curation still decides what leads a mood.
DERIVED_MOOD_CEILING = 0.5

# Fitted members — genres that never declared the mood but sit squarely in its
# tempo, energy and valence — rank below declared ones again.
FITTED_MOOD_CEILING = 0.3

# Below this the fit is a coincidence rather than a resemblance, and padding a
# mood with those makes it mean less, not more.
MIN_FITTED_MOOD_FIT = 0.55

# How many genres a mood should reach before it stops being padded.
MOOD_FLOOR = 40


def _mood_fit(node: dict, bpm: tuple, energy: tuple, valence: tuple = (0.0, 1.0)) -> float:
    """How comfortably a genre sits inside a mood's declared windows, 0.5..1.

    Only used to *order* derived members against each other. A genre that
    declared the mood is in it either way — this decides whether it leads the
    derived group or trails it, so a mood built mostly from derivation still
    opens on the genres that actually fit its tempo and energy.
    """
    score = 1.0
    elo, ehi = energy
    e = node["energy"]
    if e < elo:
        score -= min(0.4, (elo - e))
    elif e > ehi:
        score -= min(0.4, (e - ehi))

    vlo, vhi = valence
    v = node["valence"]
    if v < vlo:
        score -= min(0.4, (vlo - v))
    elif v > vhi:
        score -= min(0.4, (v - vhi))

    lo, hi = node["bpm"]
    if lo > 0 and hi >= lo:
        blo, bhi = bpm
        overlap = min(hi, bhi) - max(lo, blo)
        if overlap <= 0:
            score -= 0.3
        else:
            score -= 0.2 * (1 - min(1.0, overlap / max(1, hi - lo)))
    return max(0.0, min(1.0, score))


def mood_members(mood_id, bpm, energy, valence, curated, nodes) -> list[list]:
    """Curated members first, then every genre that declares this mood.

    The genre→mood direction was already authored on 766 of 771 nodes and then
    never read: `genresForMood` only ever looked at the mood→genre weights, so a
    mood chip drew on ten hand-picked genres while the rest of the map sat there
    declaring itself relevant to nobody. This joins the two directions, which is
    why it needs no similarity heuristic — the data is hand-authored on both
    sides, it was simply never connected.
    """
    out = [[gid, round(float(w), 3)] for gid, w in curated]
    taken = {gid for gid, _ in curated}
    derived = [
        (n["id"], DERIVED_MOOD_CEILING * _mood_fit(n, bpm, energy, valence))
        for n in nodes
        if mood_id in n.get("moods", ()) and n["id"] not in taken
    ]
    derived.sort(key=lambda gw: (-gw[1], gw[0]))
    out.extend([gid, round(float(w), 3)] for gid, w in derived)
    taken.update(gid for gid, _ in derived)

    # A mood nobody has declared yet — every newly authored one — would
    # otherwise ship with only its ten curated genres against a 771-node graph,
    # which is the exact thinness this whole pass exists to fix. Fill it from
    # tempo, energy and valence until it is big enough to be worth opening.
    # Fitted members sort below declared ones, which sort below curated ones, so
    # the ordering still reflects how much was actually known about each.
    if len(out) < MOOD_FLOOR:
        fitted = sorted(
            (
                (n["id"], _mood_fit(n, bpm, energy, valence))
                for n in nodes
                if n["id"] not in taken
            ),
            key=lambda gw: (-gw[1], gw[0]),
        )
        for gid, fit in fitted[: MOOD_FLOOR - len(out)]:
            if fit <= MIN_FITTED_MOOD_FIT:
                break
            out.append([gid, round(float(FITTED_MOOD_CEILING * fit), 3)])
    return out


def main() -> int:
    nodes = build_genres()

    reach = load_reach()
    if "--popularity" in sys.argv:
        key = os.environ.get("LASTFM_API_KEY", DEFAULT_LASTFM_KEY)
        if key:
            reach = fetch_reach(nodes, key, reach)
        else:
            print("popularity  no API key; keeping the cached values")
    for node in nodes:
        if node["id"] in reach:
            node["reach"] = reach[node["id"]]

    symmetrise(nodes)
    families = [{"id": k, "name": v} for k, v in FAMILIES.items()]
    bake_layout(nodes, families)

    graph = {
        "version": 1,
        "attribution": ATTRIBUTION,
        "families": families,
        "genres": nodes,
        "moods": [
            {
                "id": mid,
                "label": label,
                "bpm": list(bpm),
                "energy": list(energy),
                "valence": list(valence),
                "genres": mood_members(mid, bpm, energy, valence, genres, nodes),
            }
            for (mid, label, bpm, energy, valence, genres) in MOODS
        ],
    }

    vocabulary = build_vocabulary(nodes)

    os.makedirs(ASSETS, exist_ok=True)
    graph_path = os.path.join(ASSETS, "genre_graph.json")
    vocab_path = os.path.join(ASSETS, "genre_vocabulary.json")
    history_path = os.path.join(ASSETS, "genre_history.json")

    # A separate asset, not a field on each node: the graph is read on startup
    # by search and by every Discover build, and this is several times its size
    # in prose that only matters once a listener expands the map's panel.
    history = build_history(nodes)
    with open(history_path, "w", encoding="utf-8") as fh:
        json.dump(
            {
                "version": 1,
                "attribution": HISTORY_ATTRIBUTION,
                "license": HISTORY_LICENSE,
                "entries": history,
            },
            fh, ensure_ascii=False, separators=(",", ":"), sort_keys=False,
        )
        fh.write("\n")

    with open(graph_path, "w", encoding="utf-8") as fh:
        json.dump(graph, fh, ensure_ascii=False, separators=(",", ":"), sort_keys=False)
        fh.write("\n")
    with open(vocab_path, "w", encoding="utf-8") as fh:
        json.dump(
            {"version": 1, "attribution": ATTRIBUTION, "names": vocabulary},
            fh, ensure_ascii=False, separators=(",", ":"), sort_keys=True,
        )
        fh.write("\n")

    mapped = sum(1 for v in vocabulary.values() if v)
    rings = max(n.get("ring", 0) for n in nodes) if nodes else 0
    print(f"genres     {len(nodes)}  ({os.path.getsize(graph_path) / 1024:.0f} KB), {rings + 1} rings")
    reachable = {e[0] for m in graph["moods"] for e in m["genres"]}
    print(f"moods      {len(graph['moods'])}, reaching {len(reachable)}/{len(nodes)} genres")
    scored = sum(1 for n in nodes if "reach" in n)
    print(f"popularity {scored}/{len(nodes)} genres carry a reach value")
    sections = sum(len(e.get("sections", [])) for e in history.values())
    print(f"history    {len(history)}/{len(nodes)} genres researched "
          f"({os.path.getsize(history_path) / 1024:.0f} KB, {sections} sections)")
    print(f"vocabulary {len(vocabulary)} names, {mapped} mapped "
          f"({os.path.getsize(vocab_path) / 1024:.0f} KB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
