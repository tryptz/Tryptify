#!/usr/bin/env python3
"""Find every major city that actually broadcasts, and how much.

    python3 tools/fetch_world_radio.py            # fill in what the cache lacks
    python3 tools/fetch_world_radio.py --refresh  # re-fetch every country

Writes tools/world_radio.json — the committed research cache — which
build_world_radio.py bakes into app/src/main/assets/world_radio.json.

Why a cache in the repo
-----------------------
Same reason as genre_reach.json and genre_history.json: the asset has to be
rebuildable offline and byte-identical for a given input. A globe whose dots move
depending on whether the network was up that afternoon is not a map you can
learn. Refreshing is an explicit act, and the diff of what changed is reviewable.

Country-at-a-time, not city-at-a-time
-------------------------------------
The obvious shape — ask radio-browser "what plays in Munich" once per city — is
19,000 requests and well over an hour, and it hides the matching rule inside
someone else's query parser. Asking once per country instead is ~240 requests and
about ten minutes, and it puts the rule here, in code you can read and argue
with. Germany comes back as 6,127 live stations in a single two-second response.

What counts as a city with radio
--------------------------------
A city is written only when at least one live station actually names it. Nothing
is inferred from population, and nothing is assumed from the fact that a country
has stations: a dot on the globe is a claim that you can tune into something
there, so a city nobody broadcasts from gets no dot rather than an empty one.

Station location on radio-browser is free text — the `state` field holds
everything from "Berlin" to "Bayern, München, AAC 128" to "munich" — so a city is
matched by looking for its name as a whole word in the station's state, name or
tags, after folding away accents and case. Whole word, because a substring test
files every station in Abakan under the Nigerian city of Aba.

A station may legitimately count for more than one city — a regional broadcaster
naming two towns serves both — so counts are per city and not a partition.

Licensing
---------
City coordinates are GeoNames (CC BY 4.0, attribution required and shipped in the
asset). Station data is radio-browser.info, which asks only that clients identify
themselves with a real User-Agent.
"""

from __future__ import annotations

import difflib
import json
import os
import re
import sys
import time
import unicodedata
import urllib.parse
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.path.join(ROOT, "tools", "world_radio.json")
CITIES_ZIP = os.path.join(ROOT, "tools", "cities15000.zip")

GEONAMES = "https://download.geonames.org/export/dump/cities15000.zip"
RADIO_BROWSER = "https://de1.api.radio-browser.info"
UA = "Tryptify/1.0 ( https://github.com/tryptz/tryptify ) world-radio"

# radio-browser asks callers to identify themselves and be reasonable rather
# than publishing a rate. A second between country requests is far inside
# anything they enforce and puts a full run at about five minutes.
PACE_S = 1.0

# Population floor for a city to be considered at all. Every national capital is
# included regardless — a capital with radio belongs on the globe whatever its
# size, and several (Papeete, Nuuk, Vaduz) sit far below any sensible floor.
MIN_POPULATION = 100_000

# GeoNames feature code for a national capital.
CAPITAL = "PPLC"

# Shortest city name allowed to match on its own. Below this a name is more
# likely to appear inside an unrelated word than to identify a place, even with
# whole-word matching — "Ube", "Ede" and "Ica" are real cities and hopeless
# needles.
MIN_CITY_NAME = 4

# How many of a city's alternate names to keep. Generous, because lookup is a
# dictionary hit rather than a scan: a tight cap ranked by similarity quietly
# drops "münchen" behind near-duplicates of "munich" like "munic" and "munih",
# and the local spelling is the one German stations actually print.
MAX_ALIASES = 14

# How like the city's own name an alias must be to be trusted. Tuned on Munich:
# "muenchen" and "munchen" clear it, "minkhen" and "lungsod ng muenchen" do not.
MIN_ALIAS_SIMILARITY = 0.6


def fold(text: str) -> str:
    """Accent-free, lowercase, punctuation-as-space. The comparison key."""
    stripped = "".join(
        c for c in unicodedata.normalize("NFKD", text)
        if not unicodedata.combining(c)
    )
    return " " + re.sub(r"[^a-z0-9]+", " ", stripped.lower()).strip() + " "


def slug(country: str, name: str) -> str:
    """Stable id: country code plus the ASCII name, punctuation flattened."""
    base = re.sub(r"[^a-z0-9]+", "-", fold(name).strip()).strip("-")
    return f"{country.lower()}-{base}"


def api(path: str, params: dict | None = None) -> list | dict:
    url = RADIO_BROWSER + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    request = urllib.request.Request(url, headers={"User-Agent": UA})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                return json.load(response)
        except Exception:  # noqa: BLE001 - one flaky country must not end the run
            if attempt == 3:
                raise
            time.sleep(2 ** attempt)
    return []


def download_cities() -> None:
    """Fetch the GeoNames gazetteer once; it is an input, not an output."""
    if os.path.exists(CITIES_ZIP):
        return
    print(f"gazetteer  downloading {GEONAMES}")
    request = urllib.request.Request(GEONAMES, headers={"User-Agent": UA})
    with urllib.request.urlopen(request, timeout=300) as response:
        data = response.read()
    with open(CITIES_ZIP, "wb") as fh:
        fh.write(data)
    print(f"gazetteer  {len(data) / 1024 / 1024:.1f} MB")


def load_cities() -> dict[str, list[dict]]:
    """Candidate cities, grouped by country code.

    GeoNames `cities15000` columns used: 1 name, 2 asciiname, 3 alternatenames,
    4 latitude, 5 longitude, 7 feature code, 8 country code, 14 population.
    """
    download_cities()
    raw = zipfile.ZipFile(CITIES_ZIP).read("cities15000.txt").decode("utf-8")

    by_country: dict[str, list[dict]] = {}
    for line in raw.strip().split("\n"):
        f = line.split("\t")
        population = int(f[14]) if f[14].isdigit() else 0
        if population < MIN_POPULATION and f[7] != CAPITAL:
            continue

        # Names worth matching on: the local name, the ASCII transliteration,
        # and the aliases that are recognisably the same word. Aliases are
        # folded *before* they are judged, because the alias that matters most
        # is usually the local spelling — GeoNames files Munich under "Munich"
        # and hides "München", which is what German stations actually print, in
        # the alias list where an ASCII-only filter would throw it away.
        #
        # Similarity is what separates that from the rest of the list, which
        # runs to dozens of transliterations: "muenchen" and "münchen" score
        # close to "munich" and stay, "minkhen" and "lungsod ng muenchen" do
        # not. A junk alias is not merely noise — it is a false positive
        # waiting to file some unrelated station under this city.
        base = fold(f[2] or f[1]).strip()
        labels = {fold(f[1]).strip(), base}
        aliases = []
        for alias in f[3].split(",")[:60]:
            key = fold(alias).strip()
            if len(key) < MIN_CITY_NAME or not key.replace(" ", "").isalnum():
                continue
            score = difflib.SequenceMatcher(None, key, base).ratio()
            # Longer than three words can never be found: the index emits
            # runs of at most three.
            if score >= MIN_ALIAS_SIMILARITY and len(key.split()) <= 3:
                aliases.append((score, key))
        for _, key in sorted(aliases, reverse=True)[:MAX_ALIASES]:
            labels.add(key)

        folded = sorted(x for x in labels if len(x) >= MIN_CITY_NAME)
        if not folded:
            continue

        by_country.setdefault(f[8], []).append({
            "id": slug(f[8], f[2] or f[1]),
            "name": f[1],
            "ascii": f[2],
            "country": f[8],
            "lat": round(float(f[4]), 4),
            "lon": round(float(f[5]), 4),
            "population": population,
            "capital": f[7] == CAPITAL,
            "_labels": folded,
        })
    return by_country


def station_places(station: dict) -> set[str]:
    """Every place-name a station could be claiming, as folded word runs.

    All three fields, because the location lands in a different one depending on
    who submitted the entry: `state` most often, the station's own `name` for
    city broadcasters ("Radio Hamburg"), and `tags` for the rest.

    Emitting the runs of one to three words and letting the caller look them up
    is what makes this exact *and* fast. Testing every city's every spelling
    against every station is nine million substring searches for Germany alone;
    a dictionary lookup per word-run is a few hundred thousand, and it matches on
    whole words for free — which is the property that stops the Nigerian city of
    Aba from collecting every station in Abakan.
    """
    text = fold(" ".join(filter(None, (
        station.get("state") or "",
        station.get("name") or "",
        station.get("tags") or "",
    ))))
    words = text.split()
    runs = set()
    for size in (1, 2, 3):
        for start in range(len(words) - size + 1):
            runs.add(" ".join(words[start:start + size]))
    return runs


def count_for_country(code: str, cities: list[dict]) -> dict[str, int]:
    """How many live stations name each of a country's cities."""
    stations = api("/json/stations/search", {
        "countrycode": code,
        "hidebroken": "true",
        "limit": 100000,
    })
    live = [s for s in stations if s.get("lastcheckok") == 1]
    if not live:
        return {}

    index: dict[str, set[str]] = {}
    for city in cities:
        for label in city["_labels"]:
            index.setdefault(label, set()).add(city["id"])

    counts: dict[str, int] = {}
    for station in live:
        hit: set[str] = set()
        for run in station_places(station):
            hit |= index.get(run, EMPTY)
        for city_id in hit:
            counts[city_id] = counts.get(city_id, 0) + 1
    return counts


EMPTY: set[str] = frozenset()  # type: ignore[assignment]


def main() -> int:
    by_country = load_cities()
    candidates = sum(len(v) for v in by_country.values())
    print(f"gazetteer  {candidates} candidate cities across {len(by_country)} countries")

    cache: dict[str, dict] = {}
    done: set[str] = set()
    if os.path.exists(CACHE) and "--refresh" not in sys.argv:
        with open(CACHE, encoding="utf-8") as fh:
            payload = json.load(fh)
        cache = payload.get("cities", {})
        done = set(payload.get("countries", []))

    todo = sorted(set(by_country) - done)
    print(f"radio      {len(done)} countries cached, {len(todo)} to fetch "
          f"(~{len(todo) * PACE_S / 60:.0f} min)")

    for index, code in enumerate(todo):
        cities = by_country[code]
        try:
            counts = count_for_country(code, cities)
        except Exception as exc:  # noqa: BLE001 - never lose a run to one country
            print(f"  ! {code}: {exc}")
            continue

        for city in cities:
            if city["id"] in counts:
                entry = {k: v for k, v in city.items() if not k.startswith("_")}
                entry["stations"] = counts[city["id"]]
                cache[city["id"]] = entry
        done.add(code)

        if counts:
            print(f"  {code}: {len(counts)}/{len(cities)} cities, "
                  f"{sum(counts.values())} station matches")
        if (index + 1) % 20 == 0:
            save(cache, done)
        time.sleep(PACE_S)

    save(cache, done)
    print(f"\nworld      {len(cache)}/{candidates} cities broadcast "
          f"({len(cache) / candidates:.0%}), {os.path.getsize(CACHE) / 1024:.0f} KB cache")
    return 0


def save(cache: dict, done: set) -> None:
    with open(CACHE, "w", encoding="utf-8") as fh:
        json.dump(
            {
                "version": 1,
                "source": "Cities from GeoNames (CC BY 4.0); "
                          "stations from radio-browser.info",
                "fetched": time.strftime("%Y-%m-%d"),
                "countries": sorted(done),
                "cities": dict(sorted(cache.items())),
            },
            fh, ensure_ascii=False, indent=1, sort_keys=False,
        )
        fh.write("\n")


if __name__ == "__main__":
    raise SystemExit(main())
