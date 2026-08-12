#!/usr/bin/env python3
"""Research every curated genre against English Wikipedia and cache the result.

    python3 tools/fetch_genre_history.py            # fill in what the cache lacks
    python3 tools/fetch_genre_history.py --search   # second pass over the misses
    python3 tools/fetch_genre_history.py --refresh  # re-fetch everything

Writes tools/genre_history.json — the committed research cache — which
build_genre_graph.py bakes into app/src/main/assets/genre_history.json.

Why a cache in the repo
-----------------------
Same reason as genre_reach.json: the asset has to be rebuildable offline and
byte-identical for a given input. A build that says something different about
hard bop depending on whether the network was up that afternoon is not a build.
Refreshing is an explicit act, and the diff of what changed is reviewable.

What counts as an answer
------------------------
Nothing is written that hasn't been *verified* to be about the genre asked for.
Two independent checks, either of which is sufficient:

  infobox    the article carries {{Infobox music genre}}
  wikidata   the article's Wikidata item is P31 (instance of) Q188451, music
             genre — which catches the older forms English Wikipedia never gave
             a genre infobox: oratorio, ars nova, qawwali, tarantella

and then, because a verified article can still be the *wrong* verified article,
a naming check. A redirect that lands somewhere unrelated is the failure mode
that matters here: "Samba de Roda" redirects to "Samba", and Samba's lead is a
true article about the wrong genre. So a target whose title doesn't match the
genre we asked about has to *name* it in the lead, or it is refused.

A genre that fails every check gets no entry. The panel shows what the curated
dataset knows — era, tempo, lineage — and says the history isn't written yet.
Silence is the honest answer; a plausible paragraph about a genre nobody has
documented is not.

Licensing
---------
Wikipedia prose is CC BY-SA 4.0. Every entry carries the article title, its
canonical URL and the revision the text was taken from, and the app shows the
attribution with the text. That is the licence's requirement and also just what
you'd want: a claim about where a genre came from is worth exactly as much as
the source you can go and check it against.
"""

from __future__ import annotations

import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.path.join(ROOT, "tools", "genre_history.json")
GRAPH = os.path.join(ROOT, "app", "src", "main", "assets", "genre_graph.json")

WIKIPEDIA = "https://en.wikipedia.org/w/api.php"
WIKIDATA = "https://www.wikidata.org/w/api.php"
UA = "Tryptify/1.0 ( https://github.com/tryptz/tryptify ) genre-history"

# Wikipedia asks for a single sequential client and no more; a fifth of a second
# between calls is far inside that and puts a cold run at about fifteen minutes.
PACE_S = 0.2

# Wikidata's "music genre" class. The only claim value trusted as verification
# on its own, and deliberately not widened to musical form or dance: a page
# about a *dance* that shares its name with the music is the mistake this whole
# file exists to avoid making.
MUSIC_GENRE_QID = "Q188451"

# Sections worth keeping. A genre article's headings are not standardised, so
# this matches the words that actually recur across the corpus rather than
# hoping for a schema. Order here is scoring order, not output order — output
# follows the article, because an article's own order is its argument.
SECTION_PATTERNS = [
    r"^etymology",
    r"^(name|naming|terminology)\b",
    r"^(origin|origins|roots|precursors?|background|antecedents?)\b",
    r"^history",
    r"^(development|evolution)\b",
    r"^(characteristics?|musical characteristics|style|sound|traits)\b",
    r"^\d{4}s",                      # "1980s", "1990s: golden age"
    r"^(early|late|mid)[- ]",
    r"^(rise|emergence|birth|beginnings?)\b",
    r"^(decline|revival|resurgence|legacy|influence|impact)\b",
    r"^(golden age|mainstream|commercial)",
]

# Headings never worth carrying: they are lists, apparatus, or the parts of an
# article that are about Wikipedia rather than about the music.
SECTION_SKIP = [
    r"^(see also|references|notes|further reading|external links|bibliography)",
    r"^(sources|citations|footnotes|works cited)",
    r"^(list of|notable|discography|artists|musicians|performers|examples)",
    r"^(subgenres|derivatives|related genres|fusion genres|offshoots)",
    r"^(gallery|media|soundtracks?|in popular culture)",
]

# Caps, in characters. The whole point of the panel is to be read on a phone
# between two taps, and the asset ships in the APK — an entry that carries the
# full article would be neither.
LEAD_CHARS = 900
SECTION_CHARS = 1100
MAX_SECTIONS = 6

# How many distinct articles a genre's names may be tried against. Bounds the
# run: past the first few, a candidate is reaching a page that shares a word
# with the genre rather than one about it.
MAX_CANDIDATES = 6

# Shortest single-word alias allowed to prove an article is about a genre.
# Below this an alias is an abbreviation, and abbreviations collide.
MIN_SPECIFIC_ALIAS = 7


def fold(text: str) -> str:
    """Comparison key, matching GenreGraph.fold on the Kotlin side."""
    t = text.lower().replace("&", " and ")
    t = re.sub(r"[^a-z0-9]+", " ", t)
    return " ".join(w for w in t.split() if w not in {"music", "style", "styles", "genre"})


def titlekey(text: str) -> str:
    """Key for matching a title against the API's own normalisation.

    Deliberately *not* [fold]: fold drops the word "music", which makes "Jungle"
    and "Jungle music" the same string — so the redirect the API reported for
    the rainforest article gets applied to the genre's title as well, and the
    genre is never looked up at all. Wikipedia only normalises capitalisation
    and underscores, so matching those is both necessary and enough.
    """
    return " ".join(text.replace("_", " ").split()).lower()


def api(url: str, params: dict) -> dict:
    params = dict(params, format="json", formatversion="2")
    request = urllib.request.Request(
        url + "?" + urllib.parse.urlencode(params), headers={"User-Agent": UA}
    )
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                return json.load(response)
        except Exception:  # noqa: BLE001 - one flaky call must not end a 15-minute run
            if attempt == 3:
                raise
            time.sleep(2 ** attempt)
    return {}


def candidates(node: dict) -> list[str]:
    """Titles worth trying for a genre, most likely first.

    The bare name first because it is right roughly two times in three, then the
    disambiguated forms English Wikipedia uses when a genre shares its name with
    something else ("Wave (music)", "Bhangra (music)"), then the aliases the
    dataset already records because listeners type them — which is exactly the
    property that makes them good redirect sources.
    """
    name = node["name"]
    out = [
        name,
        f"{name} music",
        f"{name} (music)",
        f"{name} (music genre)",
        f"{name} (genre)",
        node["id"].replace("-", " "),
    ]
    out += list(node.get("aka") or [])
    # Deduped on the literal title, not on [fold], for the same reason the
    # redirect table is: folding drops "music", so "Jungle music" would be
    # thrown away as a duplicate of "Jungle" — discarding the genre's article
    # in favour of the rainforest's.
    seen, unique = set(), []
    for candidate in out:
        key = titlekey(candidate)
        if key and key not in seen:
            seen.add(key)
            unique.append(candidate)
    return unique


def resolve(node: dict) -> list[dict]:
    """Every article a genre's names lead to, best candidate first.

    A list rather than one answer because the first page a name reaches is
    routinely the wrong subject entirely — "Jungle" is a rainforest, "Trap" is
    a plumbing fitting, "Wave" is physics — and the genre's article is sitting
    one candidate further down. Verification decides; this only enumerates.
    """
    titles = candidates(node)[:20]
    data = api(WIKIPEDIA, {
        "action": "query",
        "titles": "|".join(titles),
        "redirects": 1,
        "prop": "pageprops",
    })
    query = data.get("query", {})
    # Wikipedia reports the normalisation and redirect it applied rather than
    # echoing our titles back, so both hops have to be followed to know which
    # candidate produced which page.
    normalised = {titlekey(n["from"]): n["to"] for n in query.get("normalized", [])}
    redirects = {
        titlekey(r["from"]): (r["to"], r.get("tofragment"))
        for r in query.get("redirects", [])
    }
    pages = {p["title"]: p for p in query.get("pages", []) if not p.get("missing")}

    out, seen = [], set()
    for candidate in titles:
        step = normalised.get(titlekey(candidate), candidate)
        target, fragment = redirects.get(titlekey(step), (step, None))
        page = pages.get(target)
        if page is None or (target, fragment) in seen:
            continue
        seen.add((target, fragment))
        out.append({
            "asked": candidate,
            "title": target,
            "fragment": fragment,
            "wikidata": page.get("pageprops", {}).get("wikibase_item"),
        })
    # Deliberately left in candidate order rather than preferring whole articles
    # over sections. The genre's own name is tried first, so a genre with an
    # article gets it, and a genre that only exists as a section of its parent's
    # article ("Xtra Raw" → Rawstyle#Xtra Raw) reaches that section by the same
    # route. Reordering here would hand a genre its neighbour's page.
    return out[:MAX_CANDIDATES]


def search_candidates(node: dict) -> list[dict]:
    """Articles Wikipedia's own search returns for a genre's name.

    A second pass, run only for genres that guessing titles failed to find. The
    guesses cover the cases where a genre's article is named after it; search
    covers the ones where it isn't — Hi-Tech psytrance is documented under
    another title entirely, and no amount of appending "(music genre)" reaches
    it.

    Search results are candidates and nothing more. They go through exactly the
    same verification and naming checks as a guessed title, which is what stops
    this from turning into "the closest article wins" — the failure mode search
    would otherwise introduce, since search always returns *something*.
    """
    data = api(WIKIPEDIA, {
        "action": "query",
        "list": "search",
        "srsearch": f'{node["name"]} music genre',
        "srlimit": 5,
        "srnamespace": 0,
    })
    return [
        {"asked": node["name"], "title": hit["title"], "fragment": None, "wikidata": None}
        for hit in data.get("query", {}).get("search", [])
    ]


def is_music_genre_item(qid: str) -> bool:
    """True when Wikidata says this item is, itself, a music genre."""
    data = api(WIKIDATA, {"action": "wbgetentities", "ids": qid, "props": "claims"})
    claims = data.get("entities", {}).get(qid, {}).get("claims", {})
    for claim in claims.get("P31", []):
        try:
            if claim["mainsnak"]["datavalue"]["value"]["id"] == MUSIC_GENRE_QID:
                return True
        except (KeyError, TypeError):
            continue
    return False


def fetch_page(title: str) -> tuple[str, str, int]:
    """Plain-text extract, wikitext and revision id for an article."""
    data = api(WIKIPEDIA, {
        "action": "query",
        "titles": title,
        "prop": "extracts|revisions",
        "explaintext": 1,
        "exsectionformat": "wiki",
        "rvprop": "ids|content",
        "rvslots": "main",
    })
    pages = data.get("query", {}).get("pages", [])
    if not pages or pages[0].get("missing"):
        return "", "", 0
    page = pages[0]
    revision = (page.get("revisions") or [{}])[0]
    return (
        page.get("extract", ""),
        revision.get("slots", {}).get("main", {}).get("content", ""),
        int(revision.get("revid") or 0),
    )


# ─── wikitext → plain text ───────────────────────────────────────────────────

def strip_markup(value: str) -> str:
    """Infobox field to readable text.

    Wikipedia's infoboxes are wikitext, not data: a field is a pile of links,
    flag templates, nowrap wrappers and HTML comments. Everything here is
    reduction to the visible words — nothing is inferred, nothing is added.
    """
    text = value
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    text = re.sub(r"<ref[^>]*/>", "", text)
    text = re.sub(r"<ref.*?</ref>", "", text, flags=re.S | re.I)
    # {{hlist|a|b}}, {{flatlist|...}} and friends: keep the arguments, drop the
    # template. Named arguments are formatting instructions, not content.
    for _ in range(6):
        replaced = re.sub(
            r"\{\{([^{}]*)\}\}",
            # Comma, not space: {{hlist|Chicago house|hi-NRG}} is a *list*, and
            # joining it with spaces produces the single nonexistent genre
            # "Chicago house hi-NRG".
            lambda m: ", ".join(
                part.strip() for part in m.group(1).split("|")[1:]
                if "=" not in part and part.strip()
            ),
            text,
        )
        if replaced == text:
            break
        text = replaced
    text = re.sub(r"\[\[([^\]|]*)\|([^\]]*)\]\]", r"\2", text)
    text = re.sub(r"\[\[([^\]]*)\]\]", r"\1", text)
    text = re.sub(r"<[^>]+>", " ", text)
    text = text.replace("'''", "").replace("''", "")
    text = re.sub(r"\s+", " ", text)
    return text.strip(" ,;·*")


def infobox_fields(wikitext: str) -> dict:
    """Stylistic origins, cultural origins and derivatives, as lists of names.

    These three are the most concentrated history in the article: "Late 1980s,
    Chicago" and "house, disco, funk" say where a genre came from in a form the
    panel can lay out, rather than a paragraph the reader has to mine.
    """
    match = re.search(r"\{\{\s*infobox[ _]music[ _]genre", wikitext, re.I)
    if not match:
        return {}

    # Walk the braces: an infobox is full of nested templates, so a lazy regex
    # to the first "}}" truncates at the first {{nowrap}} it meets.
    depth, index, start = 0, match.start(), None
    while index < len(wikitext):
        if wikitext.startswith("{{", index):
            depth += 1
            if start is None:
                start = index
            index += 2
            continue
        if wikitext.startswith("}}", index):
            depth -= 1
            index += 2
            if depth == 0:
                break
            continue
        index += 1
    body = wikitext[start:index]

    # Split on pipes at depth zero only — a nested {{hlist|a|b}} must survive.
    fields, buffer, depth, bracket = [], "", 0, 0
    for position, char in enumerate(body):
        if body.startswith("{{", position) or body.startswith("[[", position):
            depth += 1
        if body.startswith("}}", position) or body.startswith("]]", position):
            depth -= 1
        if char == "|" and depth <= 1 and bracket == 0:
            fields.append(buffer)
            buffer = ""
        else:
            buffer += char
    fields.append(buffer)

    out = {}
    wanted = {
        "stylistic_origins": "stylistic",
        "cultural_origins": "cultural",
        "derivatives": "derivatives",
    }
    for field in fields:
        if "=" not in field:
            continue
        key, _, value = field.partition("=")
        key = key.strip().lower().replace(" ", "_")
        if key not in wanted:
            continue
        cleaned = strip_markup(value)
        if not cleaned:
            continue
        if wanted[key] == "cultural":
            # One phrase: "Late 1980s, Chicago, United States" is a sentence
            # about an origin, not three separate facts.
            out["cultural"] = cleaned[:160]
        else:
            parts = [p.strip() for p in re.split(r"[,;•·]|\s{2,}", cleaned) if p.strip()]
            trimmed = [p for p in parts if 1 < len(p) <= 48][:8]
            if trimmed:
                out[wanted[key]] = trimmed
    return out


def trim(text: str, limit: int) -> str:
    """Cut to [limit] at a sentence end, so an entry never stops mid-clause."""
    text = text.strip()
    if len(text) <= limit:
        return text
    window = text[: limit + 120]
    cut = max(window.rfind(". "), window.rfind(".\n"), window.rfind("! "), window.rfind("? "))
    if cut > limit * 0.55:
        return window[: cut + 1].strip()
    return text[:limit].rsplit(" ", 1)[0].strip() + "…"


def clean_prose(text: str) -> str:
    """Plain-text extract to displayable prose."""
    text = re.sub(r"\n{2,}", "\n\n", text)
    # Parenthetical pronunciation and script glosses are half the length of some
    # leads and none of the meaning on a phone: "Tarantella (Italian:
    # [taranˈtɛlla]) is a group of..." spends a quarter of the panel's first
    # line on an IPA transcription nobody reads on a phone.
    text = re.sub(
        r"\([^()]*?(?:pronunciation|pronounced|IPA|listen)\b[^()]*?\)", "", text, flags=re.I
    )
    text = re.sub(r"\([A-Za-z ]{0,24}:?\s*[\[/][^)]{0,80}[\]/]\s*\)", "", text)
    text = re.sub(r"\(\s*[;,]?\s*\)", "", text)
    text = re.sub(r"\s+([,.;:])", r"\1", text)
    text = re.sub(r"[ \t]{2,}", " ", text)
    return text.strip()


def split_sections(extract: str) -> list[tuple[int, str, str]]:
    """The article as (level, heading, body). The lead comes back as level 0."""
    out, level, heading, buffer = [], 0, "", []
    for line in extract.split("\n"):
        match = re.match(r"^(={2,6})\s*(.+?)\s*\1$", line.strip())
        if match:
            out.append((level, heading, "\n".join(buffer).strip()))
            level, heading, buffer = len(match.group(1)) - 1, match.group(2), []
        else:
            buffer.append(line)
    out.append((level, heading, "\n".join(buffer).strip()))
    return out


def wanted_section(heading: str) -> bool:
    low = heading.strip().lower()
    if any(re.search(pattern, low) for pattern in SECTION_SKIP):
        return False
    return any(re.search(pattern, low) for pattern in SECTION_PATTERNS)


def build_entry(node: dict, found: dict, extract: str, wikitext: str, revision: int) -> dict | None:
    """Assemble one genre's history from its verified article."""
    sections = split_sections(clean_prose(extract))
    name_key = fold(node["name"])
    aliases = {name_key} | {fold(a) for a in (node.get("aka") or [])}
    # Names specific enough to prove an article is about this genre: the genre's
    # own name, and any alias that is more than one word or long enough not to
    # turn up by chance. "drum and bass" qualifies, "dnb" and "lofi" do not.
    specific = {
        alias for alias in aliases
        if alias and (" " in alias or len(alias) >= MIN_SPECIFIC_ALIAS)
    } | {name_key}

    if found["fragment"]:
        # A section redirect: the article is about something broader and this
        # one section is about our genre. Anything else on the page would be
        # about a different genre, so the section is the whole entry.
        fragment_key = fold(found["fragment"])
        anchor = next(
            (i for i, (_, heading, _) in enumerate(sections) if fold(heading) == fragment_key),
            None,
        )
        if anchor is None:
            return None
        depth = sections[anchor][0]
        block = [sections[anchor]]
        for section in sections[anchor + 1:]:
            if section[0] <= depth:
                break
            block.append(section)
        lead = block[0][2]
        rest = block[1:]
        # A section is all this genre gets, so it may run longer than a lead
        # would: there is no rest of the article to carry the remainder.
        lead = trim(lead, SECTION_CHARS)
    else:
        # The whole lead, not just its first paragraph: the second paragraph of
        # a genre article is usually where it came from, which is the thing the
        # panel is for. The cap decides how much of it survives.
        lead = trim(sections[0][2], LEAD_CHARS)
        rest = sections[1:]
        # The naming check. A title that isn't the genre's own name has to be
        # justified by the lead naming the genre — otherwise this is a redirect
        # to a neighbour and the text would describe the wrong music.
        #
        # Only the full name and the *specific* aliases may justify it. A short
        # alias is too easy to satisfy by accident: "lofi" appears in the lead
        # of "Lo-fi music", an article about a production quality, which is how
        # lo-fi hip hop came to be filed under it. A genre has to be named, not
        # merely alluded to by an abbreviation the article happens to contain.
        if fold(found["title"]) not in aliases:
            head = fold(lead[:600])
            if not any(alias in head for alias in specific):
                return None

    if not lead:
        return None

    body = []
    for level, heading, text in rest:
        if not heading or not text or not wanted_section(heading):
            continue
        # Sections whose text is a stub under a subheading carry no history;
        # the subheadings themselves come through as their own entries.
        paragraph = trim(text, SECTION_CHARS)
        if len(paragraph) < 140:
            continue
        body.append({"heading": heading, "level": level, "text": paragraph})
        if len(body) >= MAX_SECTIONS:
            break

    url = "https://en.wikipedia.org/wiki/" + urllib.parse.quote(found["title"].replace(" ", "_"))
    if found["fragment"]:
        url += "#" + urllib.parse.quote(found["fragment"].replace(" ", "_"))
    entry = {
        "title": found["title"],
        "url": url,
        "revision": revision,
        "verified": found["verified"],
        "summary": lead,
        "sections": body,
    }
    if found["fragment"]:
        entry["fragment"] = found["fragment"]
    if fold(found["asked"]) != fold(node["name"]):
        # Which of the genre's names found the article. Worth recording: it is
        # the first thing to look at when an entry turns out to be wrong.
        entry["matched"] = found["asked"]
    facts = infobox_fields(wikitext)
    if facts and not found["fragment"]:
        # Infobox facts belong to the article as a whole, so they are only
        # carried when the whole article is the genre.
        entry.update({k: v for k, v in facts.items() if v})
    return entry


def wikidata_item(title: str) -> str | None:
    """The Wikidata id behind an article, for candidates that arrived without one."""
    data = api(WIKIPEDIA, {"action": "query", "titles": title, "prop": "pageprops"})
    pages = data.get("query", {}).get("pages", [])
    if not pages or pages[0].get("missing"):
        return None
    return pages[0].get("pageprops", {}).get("wikibase_item")


def research(node: dict, search: bool = False) -> dict | None:
    """The first candidate article that verifies *and* yields usable text."""
    fetched: dict[str, tuple[str, str, int]] = {}
    candidates = search_candidates(node) if search else resolve(node)
    for found in candidates:
        if found["title"] not in fetched:
            fetched[found["title"]] = fetch_page(found["title"])
            time.sleep(PACE_S)
        extract, wikitext, revision = fetched[found["title"]]
        if not extract:
            continue

        if search:
            # Search is held to a higher bar than a guessed title, because
            # search always returns something: a query for a genre nobody has
            # documented still comes back with its parent, whose lead mentions
            # it in passing, and taking that lead would file the parent's
            # history under the child. So a result is only accepted when the
            # article *is* the genre — its title is one of the genre's names —
            # or when it contains a section that is, which is then the only
            # part used.
            aliases = {fold(node["name"])} | {fold(a) for a in (node.get("aka") or [])}
            if fold(found["title"]) not in aliases:
                heading = next(
                    (h for _, h, _ in split_sections(clean_prose(extract))
                     if h and fold(h) in aliases),
                    None,
                )
                if heading is None:
                    continue
                found["fragment"] = heading

        if re.search(r"\{\{\s*infobox[ _]music[ _]genre", wikitext, re.I):
            found["verified"] = "infobox"
        else:
            # Search results arrive without one, so it is looked up here rather
            # than in the search itself — only for the pages that got far
            # enough to need it.
            qid = found["wikidata"] or wikidata_item(found["title"])
            if qid and is_music_genre_item(qid):
                found["verified"] = "wikidata"
            else:
                continue

        entry = build_entry(node, found, extract, wikitext, revision)
        if entry and search:
            entry["found_by"] = "search"
        if entry:
            return entry
    return None


def main() -> int:
    with open(GRAPH, encoding="utf-8") as fh:
        nodes = json.load(fh)["genres"]

    cache = {}
    if os.path.exists(CACHE) and "--refresh" not in sys.argv:
        with open(CACHE, encoding="utf-8") as fh:
            payload = json.load(fh)
        cache = payload.get("entries", {})
        missed = set(payload.get("unverified", []))
    else:
        missed = set()

    only = None
    if "--only" in sys.argv:
        only = set(sys.argv[sys.argv.index("--only") + 1].split(","))

    # The second pass: everything the first one couldn't find by name, put
    # through Wikipedia's search instead. Separate and explicit because it is a
    # different question — "what is this genre called on Wikipedia" rather than
    # "is there an article at this title" — and because its results deserve
    # reviewing as a batch.
    search = "--search" in sys.argv
    if search:
        todo = [n for n in nodes if n["id"] in missed]
    else:
        todo = [
            n for n in nodes
            if (only and n["id"] in only)
            or (not only and n["id"] not in cache and n["id"] not in missed)
        ]
    print(f"history   {len(cache)} cached, {len(missed)} known-unverified, {len(todo)} to fetch")
    if todo:
        print(f"          ~{len(todo) * PACE_S * 4 / 60:.0f} min at {PACE_S}s/call")

    for index, node in enumerate(todo):
        try:
            entry = research(node, search=search)
        except Exception as exc:  # noqa: BLE001 - never lose a run to one article
            print(f"  ! {node['id']}: {exc}")
            continue
        if entry:
            if search:
                # The search pass is small and its matches are the ones worth
                # reading, so each is named as it lands.
                print(f"  + {node['id']} -> {entry['title']}"
                      f"{'#' + entry['fragment'] if entry.get('fragment') else ''}")
            cache[node["id"]] = entry
            missed.discard(node["id"])
        elif not search:
            missed.add(node["id"])
            cache.pop(node["id"], None)
        if (index + 1) % 25 == 0:
            print(f"  {index + 1}/{len(todo)}  ({len(cache)} verified)")
            save(cache, missed)
        time.sleep(PACE_S)

    save(cache, missed)
    covered = len(cache)
    print(f"history   {covered}/{len(nodes)} genres verified ({covered / len(nodes):.0%}), "
          f"{os.path.getsize(CACHE) / 1024:.0f} KB cache")
    return 0


def save(cache: dict, missed: set) -> None:
    with open(CACHE, "w", encoding="utf-8") as fh:
        json.dump(
            {
                "version": 1,
                "source": "English Wikipedia, text under CC BY-SA 4.0",
                "fetched": time.strftime("%Y-%m-%d"),
                "entries": dict(sorted(cache.items())),
                "unverified": sorted(missed),
            },
            fh, ensure_ascii=False, indent=1, sort_keys=False,
        )
        fh.write("\n")


if __name__ == "__main__":
    raise SystemExit(main())
