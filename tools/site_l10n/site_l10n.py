#!/usr/bin/env python3
"""tryptify.com in the app's eight languages.

    python3 tools/site_l10n/site_l10n.py extract   # English text → site/l10n/source.json
    python3 tools/site_l10n/site_l10n.py check     # what each language still lacks
    python3 tools/site_l10n/site_l10n.py build     # site/public/<lang>/<page>.html

The English pages in site/public/ stay the source. `extract` collects every
piece of text a visitor reads (each paragraph, heading, list item, button and
label as one unit, with its inline markup, plus alt, title, aria-label,
placeholder and the page's title and description) into source.json, keyed by
a hash of the English. Translators fill site/l10n/<lang>.json with the same
keys. `build` writes each page again per language under /<lang>/, the text
swapped, its links kept inside the language, <html lang> set, and the
hreflang alternates and the language list filled in. A unit with no
translation stays English, and `check` lists them.

Mark an element `translate="no"` to leave it out (a brand name, the release
notes on /changelog, which stay English). An element with `data-l10n-only`
is shown only in the translations (the note on the legal pages that the
English text is the binding one).
"""
import hashlib
import html
import json
import re
import sys
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SITE = ROOT / "site" / "public"
L10N = ROOT / "site" / "l10n"

# The app's languages (app/l10n/strings.tsv), as URL prefixes and <html lang>.
LANGS = {"zh": "zh-CN", "ja": "ja", "fr": "fr", "es": "es", "tr": "tr", "de": "de", "it": "it"}
NAMES = {"en": "English", "zh": "简体中文", "ja": "日本語", "fr": "Français", "es": "Español",
         "tr": "Türkçe", "de": "Deutsch", "it": "Italiano"}
# 404.html is served for every missing address, whatever the language, so it stays one page.
PAGES = ["index", "cloud", "autoeq", "player", "account", "contact", "download",
         "terms", "privacy", "refunds", "changelog", "party"]
INLINE = {"a", "strong", "em", "b", "i", "code", "br", "span", "small", "abbr", "kbd", "sup", "sub", "time", "mark"}
VOID = {"br", "img", "input", "meta", "link", "hr", "source", "wbr", "area", "col", "embed", "track", "param", "base"}
SKIP = {"script", "style", "svg", "template", "noscript", "canvas", "video", "audio", "select", "textarea"}
ATTRS = ("alt", "title", "aria-label", "placeholder", "data-monthly", "data-yearly")
# Boxes of other things: a unit only when they hold words of their own, else
# each of their items is (a menu's links, a card's heading and paragraph).
CONTAINERS = {"div", "nav", "section", "header", "footer", "main", "article", "aside", "ul", "ol", "form",
              "figure", "table", "thead", "tbody", "tr", "dl", "fieldset", "details", "menu", "body", "html"}


def key(text):
    return hashlib.sha1(text.encode("utf-8")).hexdigest()[:12]


def has_words(s):
    return bool(re.search(r"[A-Za-z]{2,}", re.sub(r"<[^>]+>|&[a-z#0-9]+;", " ", s)))


class Node:
    __slots__ = ("tag", "attrs", "start", "open_end", "close_start", "end", "kids", "parent", "raw")

    def __init__(self, tag, attrs, start, open_end, raw, parent):
        self.tag, self.attrs, self.start, self.open_end, self.raw, self.parent = tag, dict(attrs), start, open_end, raw, parent
        self.close_start = self.end = None
        self.kids = []  # Node or (start, end) text spans


class Tree(HTMLParser):
    """The page as nested elements with their source offsets, so text can be swapped in place."""

    def __init__(self, src):
        super().__init__(convert_charrefs=False)
        self.src = src
        self.lines = [0]
        for m in re.finditer("\n", src):
            self.lines.append(m.end())
        self.root = Node("#root", [], 0, 0, "", None)
        self.cur = self.root
        self.feed(src)
        self.close()

    def off(self):
        line, col = self.getpos()
        return self.lines[line - 1] + col

    def handle_starttag(self, tag, attrs):
        start = self.off()
        raw = self.get_starttag_text()
        n = Node(tag, attrs, start, start + len(raw), raw, self.cur)
        self.cur.kids.append(n)
        if tag in VOID:
            n.close_start = n.end = n.open_end
        else:
            self.cur = n

    def handle_startendtag(self, tag, attrs):
        start = self.off()
        raw = self.get_starttag_text()
        n = Node(tag, attrs, start, start + len(raw), raw, self.cur)
        n.close_start = n.end = n.open_end
        self.cur.kids.append(n)

    def handle_endtag(self, tag):
        start = self.off()
        node = self.cur
        while node is not self.root and node.tag != tag:
            node = node.parent
        if node is self.root:
            return
        # Close anything left open inside it (an unclosed <p> or <li>).
        n = self.cur
        while n is not node:
            n.close_start = n.end = start
            n = n.parent
        node.close_start = start
        node.end = self.src.index(">", start) + 1
        self.cur = node.parent

    def _text(self, data):
        start = self.off()
        self.cur.kids.append((start, start + len(data)))

    handle_data = _text
    handle_entityref = lambda self, name: self._text("&" + name + ";")
    handle_charref = lambda self, name: self._text("&#" + name + ";")


def inline_only(n):
    for k in n.kids:
        if isinstance(k, Node) and (k.tag not in INLINE or not inline_only(k)):
            return False
    return True


def units(src):
    """(start, end, text, kind) for every translatable piece of a page."""
    tree = Tree(src)
    out = []

    def attr_units(n):
        if not n.raw:
            return
        for name in ATTRS:
            m = re.search(r'\s%s="([^"]*)"' % re.escape(name), n.raw)
            if m and has_words(m.group(1)):
                out.append((n.start + m.start(1), n.start + m.end(1), m.group(1), "attr"))
        if n.tag == "meta" and re.search(r'name="description"|property="og:(title|description)"', n.raw):
            m = re.search(r'\scontent="([^"]*)"', n.raw)
            if m and has_words(m.group(1)):
                out.append((n.start + m.start(1), n.start + m.end(1), m.group(1), "attr"))

    def walk(n):
        if isinstance(n, tuple):
            return
        if n.tag in SKIP or n.attrs.get("translate") == "no":
            return
        attr_units(n)
        if n.tag == "#root" or n.close_start is None:
            for k in n.kids:
                walk(k)
            return
        inner = src[n.open_end:n.close_start]
        own_words = any(isinstance(k, tuple) and has_words(src[k[0]:k[1]]) for k in n.kids)
        if n.tag not in INLINE and inline_only(n) and has_words(inner) and (n.tag not in CONTAINERS or own_words):
            lead = len(inner) - len(inner.lstrip())
            text = inner.strip()
            out.append((n.open_end + lead, n.open_end + lead + len(text), text, "html"))
            for k in n.kids:  # alt or aria-label on inline parts still count
                if isinstance(k, Node):
                    attr_units(k)
            return
        for k in n.kids:
            if isinstance(k, tuple):
                s, e = k
                t = src[s:e]
                if has_words(t):
                    lead = len(t) - len(t.lstrip())
                    out.append((s + lead, s + lead + len(t.strip()), t.strip(), "text"))
            else:
                walk(k)

    walk(tree.root)
    return sorted(out)


JS_SKIP = re.compile(r"(^|/)(vendor|l10n)/|motion-|theme-boot")


def unescape_js(raw):
    """A JS string literal's body as its text: \\n, \\t and any other escaped character."""
    return re.sub(r"\\(.)", lambda m: {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), raw)


T_CALL = re.compile(r"""\bT\(\s*(?:"((?:[^"\\\n]|\\.)*)"|'((?:[^'\\\n]|\\.)*)'|`((?:[^`\\$]|\\.)*)`)""")


def js_strings():
    """{english: [files]} for every T("…") the site's scripts call with a plain string."""
    found = {}
    for f in sorted((SITE / "js").rglob("*.js")):
        rel = str(f.relative_to(SITE))
        if JS_SKIP.search(rel):
            continue
        for m in T_CALL.finditer(f.read_text(encoding="utf-8")):
            text = unescape_js(next(g for g in m.groups() if g is not None))
            files = found.setdefault(text, [])
            if rel not in files:
                files.append(rel)
    return found


def placeholders(s):
    return sorted(re.findall(r"\{(\w+)\}", s))


def page_path(name):
    return SITE / f"{name}.html"


def extract():
    source = {}
    for name in PAGES:
        src = page_path(name).read_text(encoding="utf-8")
        for _, _, text, kind in units(src):
            k = key(text)
            entry = source.setdefault(k, {"en": text, "kind": kind, "pages": []})
            if name not in entry["pages"]:
                entry["pages"].append(name)
    L10N.mkdir(parents=True, exist_ok=True)
    (L10N / "source.json").write_text(json.dumps(source, ensure_ascii=False, indent=1), encoding="utf-8")
    words = sum(len(re.sub(r"<[^>]+>", " ", v["en"]).split()) for v in source.values())
    print(f"source.json: {len(source)} units, {words} words, {len(PAGES)} pages")
    js = {key(t): {"en": t, "files": files} for t, files in js_strings().items()}
    (L10N / "source-js.json").write_text(json.dumps(js, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"source-js.json: {len(js)} strings from the scripts, {sum(len(v['en'].split()) for v in js.values())} words")


def load(lang):
    p = L10N / f"{lang}.json"
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else {}


def tags_of(s):
    return sorted(re.findall(r"</?([a-z0-9]+)", s))


def check():
    source = json.loads((L10N / "source.json").read_text(encoding="utf-8"))
    bad = False
    for lang in LANGS:
        tr = load(lang)
        missing = [k for k in source if k not in tr]
        broken = [k for k in source if k in tr and source[k]["kind"] == "html" and tags_of(tr[k]) != tags_of(source[k]["en"])]
        stale = [k for k in tr if k not in source]
        print(f"{lang}: {len(source) - len(missing)}/{len(source)} translated, {len(broken)} with markup that differs, {len(stale)} no longer used")
        for k in broken[:5]:
            print(f"   markup {k}: {source[k]['en'][:70]!r}")
        bad = bad or missing or broken
        js = json.loads((L10N / "source-js.json").read_text(encoding="utf-8")) if (L10N / "source-js.json").exists() else {}
        if js:
            jt = load(f"js-{lang}")
            jmiss = [k for k in js if k not in jt]
            jbad = [k for k in js if k in jt and placeholders(jt[k]) != placeholders(js[k]["en"])]
            print(f"   scripts: {len(js) - len(jmiss)}/{len(js)} translated, {len(jbad)} with blanks ({{name}}) that differ")
            for k in jbad[:5]:
                print(f"   blanks {k}: {js[k]['en'][:70]!r}")
            bad = bad or jmiss or jbad
    return 1 if bad else 0


def local_link(href):
    """The page an href names on this site, as (page, rest), or None for a file or another site."""
    m = re.match(r"^/([a-z]*)(\.html)?([?#].*)?$", href)
    if not m:
        return None
    page = m.group(1) or "index"
    return (page, m.group(3) or "") if page in PAGES else None


def lang_href(lang, page, rest=""):
    path = "/" if page == "index" else f"/{page}"
    if lang == "en":
        return path + rest
    return (f"/{lang}" if page == "index" else f"/{lang}/{page}") + rest


def alternates(page):
    links = [f'<link rel="alternate" hreflang="{LANGS.get(l, "en")}" href="https://tryptify.com{lang_href(l, page)}">'
             for l in ["en", *LANGS]]
    links.append(f'<link rel="alternate" hreflang="x-default" href="https://tryptify.com{lang_href("en", page)}">')
    return "\n  ".join(links)


def picker(lang, page):
    items = []
    for l in ["en", *LANGS]:
        cur = ' aria-current="true"' if l == lang else ""
        items.append(f'<a href="{lang_href(l, page)}" hreflang="{LANGS.get(l, "en")}" lang="{LANGS.get(l, "en")}" data-lang="{l}"{cur}>{NAMES[l]}</a>')
    return '<nav class="langs" aria-label="Language" translate="no">' + "".join(items) + "</nav>"


def localise(src, lang, page, tr):
    """[src] (an English page) in [lang]: text swapped, links kept in the language."""
    edits = []
    for s, e, text, kind in units(src):
        t = tr.get(key(text))
        if t is None:
            continue
        if kind == "attr":
            t = html.escape(html.unescape(t), quote=True)
        edits.append((s, e, t))
    out = src
    for s, e, t in sorted(edits, reverse=True):
        out = out[:s] + t + out[e:]
    if lang != "en":
        out = re.sub(r'<html lang="[^"]*"', f'<html lang="{LANGS[lang]}"', out, count=1)

        def href(m):
            target = local_link(m.group(2))
            return m.group(0) if not target else f'{m.group(1)}"{lang_href(lang, *target)}"'
        out = re.sub(r'(\shref=)"([^"]*)"', href, out)
        out = out.replace(" data-l10n-only hidden", " data-l10n-only").replace(" hidden data-l10n-only", " data-l10n-only")
        # The scripts' own text, ahead of the scripts.
        out = out.replace('<script src="/js/theme-boot.js"></script>',
                          f'<script src="/js/theme-boot.js"></script>\n  <script src="/js/l10n/{lang}.js"></script>', 1)
    out = re.sub(r"\s*<!-- l10n:alternates -->.*?<!-- /l10n -->", "", out, flags=re.S)
    out = out.replace("</head>", f"  <!-- l10n:alternates -->\n  {alternates(page)}\n  <!-- /l10n -->\n</head>", 1)
    out = re.sub(r'<nav class="langs"[^>]*>.*?</nav>', picker(lang, page), out, flags=re.S)
    return out


def build_js():
    js = json.loads((L10N / "source-js.json").read_text(encoding="utf-8")) if (L10N / "source-js.json").exists() else {}
    (SITE / "js" / "l10n").mkdir(parents=True, exist_ok=True)
    for lang in LANGS:
        jt = load(f"js-{lang}")
        table = {v["en"]: jt[k] for k, v in js.items() if k in jt}
        body = json.dumps(table, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        (SITE / "js" / "l10n" / f"{lang}.js").write_text(
            f"// The scripts' text in {NAMES[lang]}. Built by tools/site_l10n/site_l10n.py from site/l10n/js-{lang}.json.\n"
            f"window.TRYPTIFY_L10N = {body};\n", encoding="utf-8")


def build():
    build_js()
    for name in PAGES:
        p = page_path(name)
        src = p.read_text(encoding="utf-8")
        # The English page gets the same alternates and language list.
        en = localise(src, "en", name, {})
        if en != src:
            p.write_text(en, encoding="utf-8")
            src = en
        for lang in LANGS:
            out = SITE / lang / f"{name}.html"
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text(localise(src, lang, name, load(lang)), encoding="utf-8")
    print(f"built {len(PAGES)} pages in {len(LANGS)} languages")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "build"
    sys.exit({"extract": extract, "check": check, "build": build}[cmd]() or 0)
