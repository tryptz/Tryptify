#!/usr/bin/env python3
"""Builds tryptify.com/changelog from the release notes in docs/releases/.

    python3 tools/site_changelog/build.py

Writes site/public/changelog.html, and the notes' pictures beside the site's
own: a JPG or PNG becomes a WebP under site/public/img/releases/<version>/, a
GIF a silent looping MP4 (with a WebP poster) under
site/public/video/releases/<version>/, which is a tenth of the size. Run it
again whenever a note or its pictures change; it rewrites all of it.

The notes are Markdown with a little HTML (tables of screenshots), as GitHub
shows them. This reads just that much: headings, paragraphs, lists, bold,
italics, code, links and images, and HTML blocks passed through with their
pictures moved to the site. A list line that starts "New:", "Changed:",
"Removed:" or "Fixed:" gets a label.
"""
import html
import re
import subprocess
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
NOTES = ROOT / "docs" / "releases"
SITE = ROOT / "site" / "public"
TEMPLATE = SITE / "refunds.html"  # the plain page whose header and footer this page shares
GITHUB = "https://github.com/tryptz/Tryptify/blob/main/"
MAX_WIDTH = 1100
KINDS = {"New": "new", "Changed": "changed", "Removed": "removed", "Fixed": "fixed"}


def version_key(v):
    return tuple(int(p) for p in v.split("."))


def note_date(path):
    """The day a note was first committed, or None for one not committed yet."""
    out = subprocess.run(["git", "log", "--diff-filter=A", "--format=%ad", "--date=short", "--", str(path)],
                         cwd=ROOT, capture_output=True, text=True).stdout.split()
    return out[-1] if out else None


def local_picture(src, version):
    """The file in docs/releases a picture's address names, or None for one from elsewhere."""
    m = re.search(r"docs/releases/(\d+(?:\.\d+)+)/([^\"'?#)\s]+)", src)
    if m:
        return NOTES / m.group(1) / m.group(2)
    if not re.match(r"[a-z]+:", src) and not src.startswith("/"):
        return NOTES / src  # relative to the note, as GitHub reads it ("1.9.5/x.jpg")
    return None


def site_picture(src, version):
    """(kind, site path, poster) for a picture: kind "img" or "video"."""
    f = local_picture(src, version)
    if not f or not f.exists():
        return ("img", src, None)
    ver = f.parent.name
    if f.suffix.lower() == ".gif":
        out = SITE / "video" / "releases" / ver / (f.stem + ".mp4")
        poster = SITE / "img" / "releases" / ver / (f.stem + "-poster.webp")
        out.parent.mkdir(parents=True, exist_ok=True)
        poster.parent.mkdir(parents=True, exist_ok=True)
        if not out.exists() or out.stat().st_mtime < f.stat().st_mtime:
            subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(f), "-movflags", "+faststart",
                            "-pix_fmt", "yuv420p", "-vf", f"scale='min({MAX_WIDTH},iw)':-2", "-c:v", "libx264",
                            "-crf", "26", "-preset", "slow", "-an", str(out)], check=True)
        if not poster.exists() or poster.stat().st_mtime < f.stat().st_mtime:
            with Image.open(f) as im:
                im.seek(0)
                frame = im.convert("RGB")
                frame.thumbnail((MAX_WIDTH, MAX_WIDTH * 4))
                frame.save(poster, "WEBP", quality=78, method=6)
        return ("video", "/" + str(out.relative_to(SITE)), "/" + str(poster.relative_to(SITE)))
    out = SITE / "img" / "releases" / ver / (f.stem + ".webp")
    out.parent.mkdir(parents=True, exist_ok=True)
    if not out.exists() or out.stat().st_mtime < f.stat().st_mtime:
        with Image.open(f) as im:
            im = im.convert("RGB")
            im.thumbnail((MAX_WIDTH, MAX_WIDTH * 4))
            im.save(out, "WEBP", quality=80, method=6)
    return ("img", "/" + str(out.relative_to(SITE)), None)


def picture_html(src, alt, width, version):
    kind, path, poster = site_picture(src, version)
    w = f' width="{width}"' if width else ""
    alt = html.escape(html.unescape(alt), quote=True)
    if kind == "video":
        return (f'<video src="{path}" poster="{poster}"{w} autoplay muted loop playsinline '
                f'aria-label="{alt}"></video>')
    return f'<img src="{path}" alt="{alt}"{w} loading="lazy" decoding="async">'


def rewrite_html_block(block, version):
    """An HTML block from a note, its pictures moved to the site and its video links kept."""
    def img(m):
        tag = m.group(0)
        src = re.search(r'src="([^"]+)"', tag).group(1)
        alt = (re.search(r'alt="([^"]*)"', tag) or [None, ""])[1]
        width = (re.search(r'width="(\d+)"', tag) or [None, None])[1]
        return picture_html(src, alt, width, version)
    return re.sub(r"<img\b[^>]*>", img, block)


def link_target(url):
    if url.startswith("../../"):
        return GITHUB + url[6:]
    return url


def inline(text, version):
    """Markdown inline marks to HTML, the text itself escaped."""
    out, i = [], 0
    pattern = re.compile(r"!\[([^\]]*)\]\(([^)\s]+)\)|\[([^\]]+)\]\(([^)\s]+)\)|`([^`]+)`")
    for m in pattern.finditer(text):
        out.append(marks(html.escape(text[i:m.start()], quote=False)))
        if m.group(2) is not None:
            out.append(picture_html(m.group(2), m.group(1), None, version))
        elif m.group(4) is not None:
            href = html.escape(link_target(m.group(4)), quote=True)
            ext = ' rel="noopener"' if href.startswith("http") and "tryptify.com" not in href else ""
            out.append(f'<a href="{href}"{ext}>{marks(html.escape(m.group(3), quote=False))}</a>')
        else:
            out.append(f"<code>{html.escape(m.group(5), quote=False)}</code>")
        i = m.end()
    out.append(marks(html.escape(text[i:], quote=False)))
    return "".join(out)


def marks(s):
    s = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", s)
    s = re.sub(r"(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?!\w)", r"<em>\1</em>", s)
    return s


def list_item(text, version):
    m = re.match(r"(New|Changed|Removed|Fixed):\s*(.*)", text)
    if m:
        kind = m.group(1)
        return f'<li><span class="cl-tag cl-{KINDS[kind]}">{kind}</span> {inline(m.group(2), version)}</li>'
    return f"<li>{inline(text, version)}</li>"


def render(md, version):
    """A note's body (after its title) as HTML."""
    lines = md.splitlines()
    out, para, items, block = [], [], [], []

    def flush():
        nonlocal para, items
        if para:
            out.append(f"<p>{inline(' '.join(para), version)}</p>")
            para = []
        if items:
            out.append("<ul>" + "".join(list_item(t, version) for t in items) + "</ul>")
            items = []

    for raw in lines:
        line = raw.rstrip()
        if block:
            if line.strip() == "":
                out.append(rewrite_html_block("\n".join(block), version))
                block = []
            else:
                block.append(line)
            continue
        if line.startswith("<"):
            flush()
            block = [line]
            continue
        if not line.strip():
            flush()
            continue
        h = re.match(r"(#{2,4})\s+(.*)", line)
        if h:
            flush()
            level = len(h.group(1)) + 1  # the release is the h2; its sections h3
            out.append(f"<h{level}>{inline(h.group(2), version)}</h{level}>")
            continue
        li = re.match(r"\s*[-*]\s+(.*)", line)
        if li:
            if para:
                flush()
            items.append(li.group(1))
            continue
        if items and raw.startswith("  "):
            items[-1] += " " + line.strip()  # a list item carried onto the next line
            continue
        para.append(line.strip())
    flush()
    if block:
        out.append(rewrite_html_block("\n".join(block), version))
    return "\n".join(out)


def main():
    notes = sorted((p for p in NOTES.glob("*.md") if re.fullmatch(r"\d+(?:\.\d+)+", p.stem)),
                   key=lambda p: version_key(p.stem), reverse=True)
    if not notes:
        sys.exit("no release notes in docs/releases")
    sections, index = [], []
    for i, p in enumerate(notes):
        v = p.stem
        md = p.read_text(encoding="utf-8")
        body = re.sub(r"\A#\s+[^\n]*\n", "", md, count=1)
        # The page says this once, at the top; each note says it again for GitHub.
        body = re.sub(r"^Each line is marked New, Changed, Removed or Fixed\..*$\n?", "", body, flags=re.M)
        anchor = "v" + v.replace(".", "-")
        date = note_date(p)
        when = f'<p class="cl-date">{date}</p>' if date else ""
        latest = ' <span class="cl-latest">Latest</span>' if i == 0 else ""
        sections.append(f'<section class="cl-release" id="{anchor}" aria-labelledby="{anchor}-h">\n'
                        f'<h2 id="{anchor}-h"><a href="#{anchor}">Tryptify {v}</a>{latest}</h2>\n{when}\n'
                        f'{render(body, v)}\n</section>')
        index.append(f'<li><a href="#{anchor}">{v}</a></li>')

    template = TEMPLATE.read_text(encoding="utf-8")
    head, rest = template.split("<main", 1)
    tail = rest.split("</main>", 1)[1]
    head = re.sub(r"<title>.*?</title>", "<title>Changelog · Tryptify</title>", head)
    head = re.sub(r'<meta name="description" content="[^"]*">',
                  '<meta name="description" content="What changed in each version of Tryptify, with pictures.">', head)
    main_html = f"""<main class="wrap">
    <div class="cl">
      <header class="cl-head">
        <p class="eyebrow">Changelog</p>
        <h1>What's new in Tryptify</h1>
        <p class="muted">Every version's release notes, newest first. Each line is marked New, Changed, Removed or Fixed; every detail is in the <a href="{GITHUB}CHANGELOG.md" rel="noopener">full changelog</a>. <a href="/download">Download the latest</a>.</p>
      </header>
      <div class="cl-layout">
        <nav class="cl-index" aria-label="Versions"><ol>{''.join(index)}</ol></nav>
        <div class="cl-notes" translate="no">
{chr(10).join(sections)}
        </div>
      </div>
    </div>
  </main>"""
    page = head + main_html + tail
    first, _, others = page.partition("\n")
    page = first + "\n<!-- Built by tools/site_changelog/build.py from docs/releases/*.md. Edit the notes, then run it again. -->\n" + others
    (SITE / "changelog.html").write_text(page, encoding="utf-8")
    print(f"changelog.html: {len(notes)} releases, newest {notes[0].stem}")


if __name__ == "__main__":
    main()
