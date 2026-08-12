#!/usr/bin/env python3
"""Bake the world radio globe into its bundled asset.

    python3 tools/build_world_radio.py

Writes:
    app/src/main/assets/world_radio.json   coastline + cities that broadcast

Reads two committed inputs and never goes to the network:
    tools/world_coastline.geojson  Natural Earth 110m coastline (public domain)
    tools/world_radio.json         the research cache from fetch_world_radio.py

Run tools/validate_world_radio.py afterwards.

Why the coordinates are integers
--------------------------------
The globe is drawn from 5,127 coastline points, and at 0.01° — about a kilometre
— the difference is far below one screen pixel at any zoom the map offers. Stored
as floats the coastline is 130 KB of "-61.08languageetc"; stored as hundredths of
a degree it is 55 KB of small integers, and the renderer divides by 100 once per
point. The precision thrown away here is precision nobody could ever see.
"""

from __future__ import annotations

import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
CACHE = os.path.join(ROOT, "tools", "world_radio.json")
COASTLINE = os.path.join(ROOT, "tools", "world_coastline.geojson")
BORDERS = os.path.join(ROOT, "tools", "world_borders.geojson")

ATTRIBUTION = (
    "City names, coordinates and populations from GeoNames (geonames.org), "
    "licensed CC BY 4.0. Coastlines and country borders from Natural Earth "
    "(naturalearthdata.com), public domain. Station listings from the "
    "radio-browser.info community "
    "database. A city appears only when live stations actually name it; station "
    "counts are measured at build time and the listings are fetched live."
)

LICENSE = "GeoNames CC BY 4.0; Natural Earth public domain"

# Degrees per stored unit. 0.01 is ~1.1 km at the equator — a fiftieth of a pixel
# on the whole-Earth view, and still under a pixel at maximum zoom.
SCALE = 100

# Provenance the cache keeps for review and the app has no use for.
INTERNAL = ("capital",)


def load_lines(path: str) -> list[list[int]]:
    """Natural Earth line strings as flat, quantised [lon, lat, lon, lat, ...].

    Serves both layers. The coastline and the borders are the same shape of
    thing at the same resolution — the borders file is the 110m
    admin_0_boundary_lines_land set, which is Natural Earth's own and carries
    *land* boundaries only, no maritime lines. Using their dataset as published
    is also how this avoids the app taking a position on a disputed one.
    """
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)

    out: list[list[int]] = []
    for feature in data["features"]:
        geometry = feature["geometry"]
        parts = (
            [geometry["coordinates"]] if geometry["type"] == "LineString"
            else geometry["coordinates"]
        )
        for part in parts:
            flat: list[int] = []
            previous = None
            for lon, lat in part:
                point = (round(lon * SCALE), round(lat * SCALE))
                # Quantising collapses neighbouring points onto each other; a
                # repeated point draws nothing and costs six bytes.
                if point == previous:
                    continue
                previous = point
                flat.extend(point)
            if len(flat) >= 4:
                out.append(flat)
    return out


def load_cities() -> list[dict]:
    """Cities that broadcast, ordered so the biggest draw last (on top)."""
    if not os.path.exists(CACHE):
        return []
    with open(CACHE, encoding="utf-8") as fh:
        cities = json.load(fh).get("cities", {})

    out = []
    for city in cities.values():
        entry = {k: v for k, v in city.items() if k not in INTERNAL}
        # Coordinates ride at the same scale as the coastline so the renderer
        # has one convention, not two.
        entry["lat"] = round(entry["lat"] * SCALE)
        entry["lon"] = round(entry["lon"] * SCALE)
        out.append(entry)
    return sorted(out, key=lambda c: (c["stations"], c["id"]))


def main() -> int:
    coastline = load_lines(COASTLINE)
    borders = load_lines(BORDERS)
    cities = load_cities()
    if not cities:
        print("cities     none — run tools/fetch_world_radio.py first")

    payload = {
        "version": 1,
        "attribution": ATTRIBUTION,
        "license": LICENSE,
        "scale": SCALE,
        "coastline": coastline,
        "borders": borders,
        "cities": cities,
    }

    os.makedirs(ASSETS, exist_ok=True)
    path = os.path.join(ASSETS, "world_radio.json")
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, separators=(",", ":"), sort_keys=False)
        fh.write("\n")

    points = sum(len(line) // 2 for line in coastline)
    border_points = sum(len(line) // 2 for line in borders)
    stations = sum(c["stations"] for c in cities)
    countries = len({c["country"] for c in cities})
    print(f"coastline  {len(coastline)} lines, {points} points")
    print(f"borders    {len(borders)} lines, {border_points} points")
    print(f"cities     {len(cities)} across {countries} countries, "
          f"{stations} station matches")
    print(f"asset      {os.path.getsize(path) / 1024:.0f} KB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
