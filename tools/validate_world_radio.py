#!/usr/bin/env python3
"""Check the bundled world radio asset is internally consistent.

    python3 tools/validate_world_radio.py

Exits non-zero on any violation, the way validate_genre_graph.py does.

What it enforces, and why each one is worth enforcing:

  coordinates in range    a city off the sphere projects to nonsense or nowhere
  every city broadcasts   a dot is a claim you can tune in there; an empty one lies
  ids unique              the panel is keyed by id; a duplicate makes one unreachable
  coastline present       without it the globe is dots floating on nothing
  land rings closed       an open ring fills as a chord across the sphere
  lines well formed       an odd-length run means a lon with no lat, and the
                          renderer reads past the end of the array
  attribution shipped     GeoNames is CC BY 4.0; the credit is not optional
  size budget             the asset parses whole on first open
"""

from __future__ import annotations

import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSET = os.path.join(ROOT, "app", "src", "main", "assets", "world_radio.json")

# Kilobytes. The asset is parsed in one go the first time the globe opens, so
# the ceiling is set by that parse rather than by download size.
MAX_KB = 400

# Below this the dataset is not a world map, it is an accident.
MIN_CITIES = 500
MIN_COASTLINE_POINTS = 2000
MIN_BORDER_POINTS = 1500


def main() -> int:
    if not os.path.exists(ASSET):
        print(f"FAIL: {ASSET} not found — run tools/build_world_radio.py")
        return 1

    with open(ASSET, encoding="utf-8") as fh:
        data = json.load(fh)

    errors: list[str] = []
    scale = data.get("scale") or 1
    cities = data.get("cities", [])
    coastline = data.get("coastline", [])
    borders = data.get("borders", [])
    land = data.get("land", [])

    if not data.get("attribution") or not data.get("license"):
        errors.append("asset ships without its attribution or licence")

    if len(cities) < MIN_CITIES:
        errors.append(f"only {len(cities)} cities, expected at least {MIN_CITIES}")

    seen: set[str] = set()
    for city in cities:
        cid = city.get("id", "")
        if cid in seen:
            errors.append(f"duplicate city id: {cid}")
        seen.add(cid)

        lat = city.get("lat", 0) / scale
        lon = city.get("lon", 0) / scale
        if not -90 <= lat <= 90 or not -180 <= lon <= 180:
            errors.append(f"{cid}: off the sphere at {lat},{lon}")
        if city.get("stations", 0) < 1:
            errors.append(f"{cid}: on the globe with no stations")
        if not city.get("name") or not city.get("country"):
            errors.append(f"{cid}: missing name or country")

    points = sum(len(line) // 2 for line in coastline)
    if points < MIN_COASTLINE_POINTS:
        errors.append(f"coastline has only {points} points")

    border_points = sum(len(line) // 2 for line in borders)
    if border_points < MIN_BORDER_POINTS:
        errors.append(f"borders have only {border_points} points")

    if not land:
        errors.append("no land rings — the continents would draw unfilled")
    # A fill needs closed rings. An open one draws a chord across the sphere.
    unclosed = [
        i for i, ring in enumerate(land)
        if len(ring) < 6 or ring[0] != ring[-2] or ring[1] != ring[-1]
    ]
    if unclosed:
        errors.append(f"{len(unclosed)} land rings do not close")

    for name, lines in (("coastline", coastline), ("borders", borders), ("land", land)):
        for index, line in enumerate(lines):
            if len(line) % 2:
                errors.append(f"{name} line {index} has an odd number of values")
                break

    kilobytes = os.path.getsize(ASSET) / 1024
    if kilobytes > MAX_KB:
        errors.append(f"asset is {kilobytes:.0f} KB, over the {MAX_KB} KB budget")

    countries = len({c.get("country") for c in cities})
    stations = sum(c.get("stations", 0) for c in cities)
    print(f"  cities:    {len(cities)} across {countries} countries")
    print(f"  stations:  {stations} matches")
    print(f"  coastline: {len(coastline)} lines, {points} points")
    print(f"  borders:   {len(borders)} lines, {border_points} points")
    print(f"  land:      {len(land)} rings, {sum(len(r) // 2 for r in land)} points")
    print(f"  asset:     {kilobytes:.0f} KB of {MAX_KB} KB")

    if errors:
        print(f"\nRESULT: {len(errors)} problem(s)")
        for error in errors[:40]:
            print(f"  - {error}")
        if len(errors) > 40:
            print(f"  … and {len(errors) - 40} more")
        return 1

    print("\nRESULT: ALL VALID ✓")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
