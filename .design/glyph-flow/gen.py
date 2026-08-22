# -*- coding: utf-8 -*-
import json, os

# ── tokens lifted verbatim from ui/glyph/GlyphTheme.kt ──────────────────
INK        = "#0B1020"
INK_RAISED = "#141A2E"
INK_PANEL  = "#10162A"
PAPER      = "#F8FAFF"
MUTED      = "#78839C"
HAIRLINE   = "rgba(248,250,255,0.10)"
POSITIVE   = "#63F2A2"
WARNING    = "#FFD95A"
NEGATIVE   = "#FF5F6D"
EARLY      = "#58D9FF"
LATE       = "#FF9659"

LANES = [("left", -90, "#FF74C8"), ("down", 180, "#58D9FF"),
         ("up", 0, "#63F2A2"), ("right", 90, "#FFD95A")]
BEAT = {"4th":"#FF5F6D","8th":"#58D9FF","12th":"#A77BFF","16th":"#FFD95A",
        "24th":"#FF74C8","32nd":"#FF9659","48th":"#52E6D8","64th":"#63F2A2"}

ARROW = ("M32 4 L59 31 Q61 33 59 35 L47 47 Q45 49 43 47 L39 43 V57 "
         "Q39 60 36 60 H28 Q25 60 25 57 V43 L21 47 Q19 49 17 47 L5 35 Q3 33 5 31 Z")
INNER = "M32 14 L50 32 L43 39 L35 31 V51 H29 V31 L21 39 L14 32 Z"

W, H = 390, 844

def tap(rot, colour, size=52):
    return (f'<svg viewBox="0 0 64 64" width="{size}" height="{size}" style="display:block">'
            f'<g transform="rotate({rot} 32 32)">'
            f'<path d="{ARROW}" fill="{INK}" opacity="0.85" transform="translate(0 2)"/>'
            f'<path d="{ARROW}" fill="{colour}" stroke="{PAPER}" stroke-width="2" stroke-linejoin="round"/>'
            f'<path d="{INNER}" fill="none" stroke="{INK}" stroke-width="2.5" stroke-linejoin="round" opacity="0.55"/>'
            f'</g></svg>')

def receptor(rot, colour, size=52, active=False):
    return (f'<svg viewBox="0 0 64 64" width="{size}" height="{size}" style="display:block">'
            f'<g transform="rotate({rot} 32 32)">'
            f'<path d="{ARROW}" fill="{INK}" fill-opacity="{0.9 if active else 0.72}" '
            f'stroke="{PAPER}" stroke-width="3" stroke-linejoin="round"/>'
            f'<path d="{INNER}" fill="{colour if active else "none"}" fill-opacity="0.35" stroke="{colour}" '
            f'stroke-width="3" stroke-linejoin="round"/>'
            f'<circle cx="32" cy="31" r="3" fill="{colour}"/>'
            f'</g></svg>')

# ── shared chrome, matching the real composables ────────────────────────
def label(t, c=MUTED):
    return (f'<div style="font-size:11px;font-weight:500;letter-spacing:0.8px;'
            f'text-transform:uppercase;color:{c}">{t}</div>')

def title(t):
    return f'<div style="font-size:18px;font-weight:600;color:{PAPER}">{t}</div>'

def body(t, c=PAPER):
    return f'<div style="font-size:14px;line-height:1.55;color:{c}">{t}</div>'

def mono(t, c=PAPER, s=13):
    return f'<div style="font-size:{s}px;font-weight:500;color:{c}">{t}</div>'

def panel(inner, pad=16, extra=""):
    return (f'<div style="border-radius:12px;background:{INK_PANEL};'
            f'border:1px solid {HAIRLINE};padding:{pad}px;{extra}">{inner}</div>')

def stat(l, v, c=PAPER):
    return (f'<div style="display:flex;flex-direction:column;gap:2px">{label(l)}'
            f'<div style="font-size:18px;font-weight:600;color:{c}">{v}</div></div>')

def primary(t):
    return (f'<div style="height:48px;border-radius:8px;background:{PAPER};color:{INK};'
            f'display:flex;align-items:center;justify-content:center;'
            f'font-size:13px;font-weight:500;flex:1">{t}</div>')

def secondary(t, c=PAPER):
    return (f'<div style="height:48px;border-radius:8px;background:{INK_RAISED};color:{c};'
            f'display:flex;align-items:center;justify-content:center;'
            f'font-size:13px;font-weight:500;flex:1">{t}</div>')

def chip(t, sel=False):
    bg, fg = (PAPER, INK) if sel else (INK_RAISED, PAPER)
    return (f'<div style="border-radius:8px;background:{bg};color:{fg};padding:10px 14px;'
            f'font-size:13px;font-weight:500;white-space:nowrap">{t}</div>')

def meter(frac, colour=PAPER, track=INK_RAISED, h=4):
    return (f'<div style="height:{h}px;border-radius:{h//2 or 2}px;background:{track};overflow:hidden">'
            f'<div style="width:{frac*100:.0f}%;height:100%;border-radius:{h//2 or 2}px;background:{colour}"></div></div>')

def icon(d, c=PAPER, size=20):
    return (f'<svg viewBox="0 0 24 24" width="{size}" height="{size}" fill="none" stroke="{c}" '
            f'stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="display:block">{d}</svg>')

I_BACK  = '<path d="M15 5 L8 12 L15 19"/>'
I_STEM  = '<path d="M4 12 H8"/><path d="M10 6 V18"/><path d="M14 3 V21"/><path d="M18 8 V16"/><path d="M20 12 H21"/>'
I_PAUSE = '<path d="M9 5 V19"/><path d="M15 5 V19"/>'
I_PLAY  = '<path d="M8 5 L19 12 L8 19 Z"/>'
I_RESET = '<path d="M4 10 A8 8 0 1 1 6 16"/><path d="M4 5 V10 H9"/>'
I_METRO = '<path d="M8 20 H16 L14 4 H10 Z"/><path d="M12 15 L16 7"/><path d="M6 20 H18"/>'
I_MIRROR= '<path d="M12 4 V20"/><path d="M8 8 L4 12 L8 16"/><path d="M16 8 L20 12 L16 16"/>'
I_SHUF  = '<path d="M4 6 H8 L16 18 H20"/><path d="M4 18 H8 L16 6 H20"/><path d="M17 3 L20 6 L17 9"/><path d="M17 15 L20 18 L17 21"/>'
I_GHOST = '<path d="M6 20 V10 A6 6 0 0 1 18 10 V20 L15 18 L12 20 L9 18 Z"/><circle cx="10" cy="11" r="1"/><circle cx="14" cy="11" r="1"/>'
I_NOTE  = '<path d="M9 18 V6 L19 4 V16"/><circle cx="7" cy="18" r="2"/><circle cx="17" cy="16" r="2"/>'
I_LOOP  = '<path d="M4 8 H16 A4 4 0 0 1 16 16 H8"/><path d="M7 5 L4 8 L7 11"/>'

def shell(children, pad=16, gap=8, bg=INK):
    return (f'<div style="width:{W}px;height:{H}px;background:{bg};overflow:hidden;'
            f'display:flex;flex-direction:column;gap:{gap}px;padding:{pad}px;'
            f'box-sizing:border-box">{children}</div>')

def topbar(t, right=""):
    return (f'<div style="display:flex;align-items:center;gap:8px;height:48px;flex:0 0 auto">'
            f'<div style="width:48px;height:48px;display:flex;align-items:center;justify-content:center">'
            f'{icon(I_BACK, PAPER, 22)}</div>'
            f'<div style="flex:1;font-size:18px;font-weight:600;color:{PAPER}">{t}</div>{right}</div>')

def dc(body_html, note):
    return f'''<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
  <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;500;600;700&display=swap">
  <style>
    body {{ margin:0; font-family:'JetBrains Mono', ui-monospace, 'SF Mono', Menlo, monospace;
            background:{INK}; color:{PAPER}; -webkit-font-smoothing:antialiased; }}
    * {{ box-sizing:border-box; }}
    a {{ color:{EARLY}; }} a:hover {{ color:{PAPER}; }}
  </style>
</helmet>
{body_html}
</x-dc>
<script data-dc-script data-props='{{"$preview":{{"width":{W},"height":{H}}}}}'>
class Component extends DCLogic {{}}
</script>
</body>
</html>
'''

files = {}

# ── 1. Entry — where Glyph is reached from ──────────────────────────────
entry_pill = (f'<div style="border-radius:999px;background:{INK_RAISED};'
              f'border:1px solid {POSITIVE};color:{POSITIVE};padding:8px 14px;'
              f'font-size:12px;font-weight:600">Glyph</div>')
entry_pill_dim = (f'<div style="border-radius:999px;background:{INK_RAISED};'
                  f'color:{MUTED};padding:8px 14px;font-size:12px;font-weight:500">Patterns</div>')

files["Entry.dc.html"] = dc(shell(
    topbar("Sampler", f'<div style="display:flex;gap:8px">{entry_pill}{entry_pill_dim}</div>')
    + panel(
        label("Stems") +
        f'<div style="height:8px"></div>' +
        f'<div style="display:flex;gap:8px">'
        + "".join(
            f'<div style="flex:1;border-radius:8px;background:{INK_RAISED};padding:12px 8px;'
            f'text-align:center"><div style="font-size:12px;color:{PAPER}">{n}</div>'
            f'<div style="height:6px"></div>{meter(f, c)}</div>'
            for n, f, c in (("Drums",1.0,POSITIVE),("Bass",1.0,EARLY),
                            ("Vocals",1.0,"#A77BFF"),("Other",1.0,MUTED)))
        + '</div>'
        + f'<div style="height:12px"></div>'
        + mono("Separated by CPU &middot; htdemucs", MUTED, 11))
    + f'<div style="height:4px"></div>'
    + panel(
        f'<div style="display:flex;align-items:center;gap:10px">{icon(I_STEM, POSITIVE, 22)}'
        f'<div style="flex:1">{title("Glyph")}'
        f'<div style="height:2px"></div>'
        f'{mono("Turn these stems into a step chart", MUTED, 12)}</div>'
        f'<div style="font-size:20px;color:{MUTED}">&rsaquo;</div></div>')
    + f'<div style="flex:1"></div>'
    + panel(body("The mode sits next to the sampler because that is where the "
                 "stems are. Whoever just pulled the drums out of a track is who "
                 "wants a chart from them.", MUTED))
), "entry")

# ── 2. Main — the Glyph hub, mixed chart states ─────────────────────────
def song_row(t, artist, meta, right, right_colour, sub=None, selected=False):
    return (f'<div style="border-radius:12px;background:{INK_RAISED if selected else INK_PANEL};'
            f'border:1px solid {HAIRLINE if not selected else "rgba(248,250,255,0.18)"};'
            f'padding:12px;display:flex;align-items:center;gap:12px">'
            f'<div style="width:44px;height:44px;border-radius:8px;background:{INK_RAISED};'
            f'flex:0 0 auto;display:flex;align-items:center;justify-content:center">'
            f'{icon(I_NOTE, MUTED, 18)}</div>'
            f'<div style="flex:1;min-width:0">'
            f'<div style="font-size:14px;color:{PAPER};white-space:nowrap;overflow:hidden;'
            f'text-overflow:ellipsis">{t}</div>'
            f'<div style="height:2px"></div>{label(meta)}</div>'
            f'<div style="text-align:right;flex:0 0 auto">'
            f'<div style="font-size:11px;font-weight:500;letter-spacing:0.8px;'
            f'text-transform:uppercase;color:{right_colour}">{right}</div>'
            + (f'<div style="height:2px"></div>{label(sub)}' if sub else '')
            + f'</div></div>')

search = (f'<div style="height:44px;border-radius:8px;background:{INK_PANEL};'
          f'display:flex;align-items:center;padding:0 12px;font-size:13px;color:{MUTED}">'
          f'Search songs</div>')

files["Main.dc.html"] = dc(shell(
    topbar("GLYPH", f'<div style="width:48px;height:48px;display:flex;align-items:center;'
                    f'justify-content:center">{icon(I_STEM, PAPER, 22)}</div>')
    + search
    + f'<div style="height:4px"></div>'
    + f'<div style="display:flex;flex-direction:column;gap:8px">'
    + song_row("Vessels", "Nero", "Nero &middot; 5:12 &middot; FLAC", "148 BPM", POSITIVE, "B E M H C")
    + song_row("Ghost Train", "Tessela", "Tessela &middot; 4:03 &middot; MP3", "No chart", MUTED)
    + song_row("Kernel Panic", "Machinedrum", "Machinedrum &middot; 3:41 &middot; FLAC", "172 BPM", POSITIVE, "E M H")
    + song_row("Half Light", "Burial", "Burial &middot; 6:28 &middot; FLAC", "Needs regenerating", WARNING)
    + '</div>'
    + f'<div style="flex:1"></div>'
), "main")

# ── 3. Generate — the conversion service reporting itself ───────────────
def stage(n, t, state):
    dot = {"done":POSITIVE,"active":PAPER,"todo":INK_RAISED}[state]
    txt = {"done":MUTED,"active":PAPER,"todo":MUTED}[state]
    ring = f'border:2px solid {dot};background:{dot if state=="done" else "transparent"}'
    return (f'<div style="display:flex;align-items:center;gap:10px;padding:6px 0">'
            f'<div style="width:14px;height:14px;border-radius:7px;{ring};flex:0 0 auto"></div>'
            f'<div style="flex:1;font-size:13px;color:{txt}">{t}</div>'
            f'{mono(n, MUTED, 11)}</div>')

files["Generate.dc.html"] = dc(shell(
    topbar("GLYPH")
    + panel(
        f'<div style="display:flex;align-items:center">'
        f'<div style="flex:1;font-size:14px;color:{PAPER}">Separating drums</div>'
        f'{mono("62%", MUTED)}</div>'
        f'<div style="height:8px"></div>{meter(0.62, POSITIVE)}'
        f'<div style="height:8px"></div>{label("Separated by CPU &middot; htdemucs")}'
        f'<div style="height:8px"></div>{mono("Cancel", MUTED)}')
    + f'<div style="height:4px"></div>'
    + panel(
        label("Pipeline")
        + f'<div style="height:6px"></div>'
        + stage("1", "Decode the file to PCM", "done")
        + stage("2", "Separate the drum stem", "active")
        + stage("3", "Read the rhythm off the transients", "todo")
        + stage("4", "Write five difficulties to an SSC", "todo"))
    + f'<div style="height:4px"></div>'
    + panel(body("It runs offline and reuses the sampler's decoder and stem "
                 "registry. Nothing here is a second audio path.", MUTED))
    + f'<div style="flex:1"></div>'
), "generate")

# ── 4. Ready — difficulty and the two ways to start ─────────────────────
files["Ready.dc.html"] = dc(shell(
    topbar("GLYPH")
    + search
    + f'<div style="height:4px"></div>'
    + song_row("Vessels", "Nero", "Nero &middot; 5:12 &middot; FLAC", "148 BPM", POSITIVE, "B E M H C", selected=True)
    + f'<div style="flex:1"></div>'
    + panel(
        title("Vessels")
        + f'<div style="height:8px"></div>{label("Difficulty")}'
        + f'<div style="height:8px"></div>'
        + f'<div style="display:flex;gap:8px;flex-wrap:wrap">'
        + chip("Beginner 3") + chip("Easy 5") + chip("Medium 8", sel=True) + chip("Hard 11")
        + chip("Challenge 14")
        + '</div>'
        + f'<div style="height:12px"></div>'
        + f'<div style="display:flex;gap:8px">{primary("Play")}{secondary("Training Ground")}</div>'
        + f'<div style="height:8px"></div>{label("612 notes &middot; 148 BPM &middot; meter 8")}')
), "ready")

# ── 5. Gameplay ─────────────────────────────────────────────────────────
lane_w = (W - 32) // 4
notes = [(0,"4th",120),(1,"8th",260),(2,"16th",200),(3,"4th",360),(1,"4th",420),(2,"8th",500)]
falling = ""
for li, div, top in notes:
    name, rot, _ = LANES[li]
    falling += (f'<div style="position:absolute;left:{li*lane_w + (lane_w-52)//2}px;'
                f'top:{top}px">{tap(rot, BEAT[div])}</div>')
hold = (f'<div style="position:absolute;left:{2*lane_w + (lane_w-24)//2}px;top:150px;'
        f'width:24px;height:120px;background:linear-gradient(180deg,#58D9FF,#3AA8CC);'
        f'border-radius:4px;opacity:0.9"></div>')

recep_row = "".join(
    f'<div style="position:absolute;left:{i*lane_w + (lane_w-52)//2}px;top:0">'
    f'{receptor(rot, col, active=(i==1))}</div>'
    for i, (n, rot, col) in enumerate(LANES))

lane_bg = "".join(
    f'<div style="position:absolute;left:{i*lane_w}px;top:0;width:{lane_w}px;height:100%;'
    f'background:{"rgba(255,255,255,0.038)" if i%2 else "rgba(255,255,255,0.015)"}"></div>'
    for i in range(4))

judgement = (f'<div style="position:absolute;left:0;right:0;top:300px;text-align:center;'
             f'font-size:26px;font-weight:700;letter-spacing:2px;color:{POSITIVE}">MARVELOUS</div>')

files["Gameplay.dc.html"] = dc(
    f'<div style="width:{W}px;height:{H}px;background:{INK};position:relative;overflow:hidden">'
    f'<div style="position:absolute;inset:0">{lane_bg}</div>'
    f'{hold}{falling}{judgement}'
    f'<div style="position:absolute;left:0;top:648px;width:{W}px;height:56px">{recep_row}</div>'
    f'<div style="position:absolute;left:0;right:0;top:640px;height:1px;background:{HAIRLINE}"></div>'
    # HUD
    f'<div style="position:absolute;left:16px;right:16px;top:16px">'
    f'<div style="display:flex;gap:16px;align-items:center">'
    f'{stat("Score","742,180")}{stat("Combo","148",POSITIVE)}{stat("Accuracy","96.4%")}'
    f'<div style="flex:1"></div>'
    f'<div style="width:48px;height:48px;display:flex;align-items:center;justify-content:center">'
    f'{icon(I_PAUSE, PAPER, 22)}</div></div>'
    f'<div style="height:8px"></div>'
    f'<div style="display:flex;gap:8px">{label("148 BPM")}{label("Measure 42")}{label("C400")}</div>'
    f'<div style="height:6px"></div>{meter(0.38, "rgba(248,250,255,0.6)")}'
    f'</div></div>', "gameplay")

# ── 6. Pause — where the scroll mods live ───────────────────────────────
files["Pause.dc.html"] = dc(
    f'<div style="width:{W}px;height:{H}px;background:{INK};position:relative;overflow:hidden">'
    f'<div style="position:absolute;inset:0;opacity:0.18">{lane_bg}{falling}</div>'
    f'<div style="position:absolute;inset:0;background:rgba(11,16,32,0.94);'
    f'display:flex;align-items:center;justify-content:center;padding:24px">'
    + panel(
        title("PAUSED")
        + f'<div style="height:16px"></div>'
        + f'<div style="display:flex;align-items:center"><div style="flex:1">{label("Scroll")}</div>'
        + mono("C400") + '</div>'
        + f'<div style="height:8px"></div>'
        + f'<div style="display:flex;gap:8px">{chip("XMod")}{chip("CMod", sel=True)}{chip("MMod")}</div>'
        + f'<div style="height:8px"></div>'
        + f'<div style="display:flex;gap:6px;flex-wrap:wrap">'
        + chip("200") + chip("300") + chip("400", sel=True) + chip("500") + chip("600")
        + '</div>'
        + f'<div style="height:6px"></div>'
        + label("Constant reading speed. Tempo changes and stops do not move the field.")
        + f'<div style="height:16px"></div>'
        + f'<div style="display:flex;align-items:center"><div style="flex:1">{label("Speed")}</div>'
        + mono("1.00&times;") + '</div>'
        + f'<div style="height:8px"></div>'
        + f'<div style="display:flex;gap:6px;flex-wrap:wrap">'
        + chip("0.8&times;") + chip("0.9&times;") + chip("1.00&times;", sel=True) + chip("1.1&times;")
        + '</div>'
        + f'<div style="height:8px"></div>{secondary("Pitch follows speed")}'
        + f'<div style="height:16px"></div>'
        + f'<div style="display:flex;gap:8px">{primary("Resume")}{secondary("Restart")}</div>'
        + f'<div style="height:8px"></div>{secondary("Quit to songs", MUTED)}',
        extra="width:100%")
    + '</div></div>', "pause")

# ── 7. Results ──────────────────────────────────────────────────────────
def jrow(n, count, colour, frac):
    return (f'<div style="display:flex;align-items:center;gap:8px;padding:3px 0">'
            f'<div style="flex:0 0 84px;font-size:13px;font-weight:500;color:{colour}">{n}</div>'
            f'<div style="flex:1">{meter(frac, colour)}</div>'
            f'<div style="flex:0 0 40px;text-align:right;font-size:13px;color:{PAPER}">{count}</div></div>')

bars = [0.98,0.95,0.99,0.88,0.62,0.91,0.97,0.94]
graph = ""
bw = (W - 64) / len(bars)
for i, v in enumerate(bars):
    col = POSITIVE if v>=0.95 else (EARLY if v>=0.85 else (WARNING if v>=0.7 else NEGATIVE))
    sel = (i == 4)
    graph += (f'<div style="position:absolute;left:{i*bw + bw*0.15:.1f}px;bottom:0;'
              f'width:{bw*0.7:.1f}px;height:{v*100:.0f}%;background:{col};'
              f'opacity:{1 if sel else 0.75}"></div>')
    if sel:
        graph += (f'<div style="position:absolute;left:{i*bw + bw*0.15:.1f}px;bottom:0;'
                  f'width:{bw*0.7:.1f}px;height:100%;border:2px solid {PAPER}"></div>')

files["Results.dc.html"] = dc(shell(
    title("Vessels")
    + panel(
        f'<div style="display:flex;align-items:center;gap:16px">'
        f'<div style="width:76px;height:76px;border-radius:38px;border:3px solid {POSITIVE};'
        f'display:flex;align-items:center;justify-content:center;font-size:28px;'
        f'font-weight:700;color:{POSITIVE}">S</div>'
        f'<div><div style="font-size:28px;font-weight:700;letter-spacing:-0.5px;color:{PAPER}">93.78%</div>'
        f'{label("937,820 points &middot; Hard")}</div></div>'
        f'<div style="height:16px"></div>'
        f'<div style="display:flex;justify-content:space-between">'
        f'{stat("Max combo","118")}{stat("Mean","7 ms")}{stat("Spread","&plusmn;21 ms")}</div>'
        f'<div style="height:8px"></div>{label("New best, up 34,120 points", POSITIVE)}')
    + panel(
        label("Judgements") + '<div style="height:6px"></div>'
        + jrow("Marvelous","200",POSITIVE,0.65) + jrow("Perfect","80","#52E6D8",0.26)
        + jrow("Great","20",EARLY,0.07) + jrow("Good","6",WARNING,0.02)
        + jrow("Boo","2",LATE,0.01) + jrow("Miss","4",NEGATIVE,0.01))
    + panel(
        f'<div style="display:flex;align-items:center"><div style="flex:1">'
        f'{label("Accuracy over time")}</div>{label("1:20 &middot; 62% &middot; 9 missed", PAPER)}</div>'
        f'<div style="height:8px"></div>'
        f'<div style="position:relative;height:96px">'
        f'<div style="position:absolute;left:0;right:0;top:10%;height:1px;background:{HAIRLINE}"></div>'
        f'<div style="position:absolute;left:0;right:0;top:30%;height:1px;background:{HAIRLINE}"></div>'
        f'{graph}</div>')
    + primary("Practise 1:20 in Training Ground")
    + f'<div style="flex:1"></div>'
), "results")

# ── 8. Training Ground ──────────────────────────────────────────────────
wave = ""
import math
for i in range(56):
    v = 0.25 + 0.7*abs(math.sin(i*0.55))*(0.5+0.5*abs(math.cos(i*0.21)))
    inloop = 18 <= i <= 34
    wave += (f'<div style="width:{(W-64)/56:.2f}px;height:{v*56:.0f}px;'
             f'background:{POSITIVE if inloop else MUTED};opacity:{0.85 if inloop else 0.45}"></div>')

files["Training.dc.html"] = dc(shell(
    topbar("TRAINING GROUND",
           f'<div style="display:flex">'
           f'<div style="width:48px;height:48px;display:flex;align-items:center;'
           f'justify-content:center">{icon(I_PAUSE, PAPER, 22)}</div>'
           f'<div style="width:48px;height:48px;display:flex;align-items:center;'
           f'justify-content:center">{icon(I_RESET, PAPER, 22)}</div></div>')
    + f'<div style="position:relative;height:210px;border-radius:12px;overflow:hidden;'
      f'background:{INK_PANEL}">'
    + "".join(f'<div style="position:absolute;left:{i*(W-32)//4}px;top:0;width:{(W-32)//4}px;'
              f'height:100%;background:{"rgba(255,255,255,0.038)" if i%2 else "transparent"}"></div>'
              for i in range(4))
    + "".join(f'<div style="position:absolute;left:{li*((W-32)//4) + (((W-32)//4)-40)//2}px;top:{t}px">'
              f'{tap(LANES[li][1], BEAT[d], 40)}</div>'
              for li,d,t in ((0,"4th",30),(2,"8th",70),(1,"16th",110),(3,"4th",20)))
    + f'<div style="position:absolute;left:0;top:150px;width:100%;height:44px">'
    + "".join(f'<div style="position:absolute;left:{i*((W-32)//4) + (((W-32)//4)-40)//2}px;top:0">'
              f'{receptor(rot, col, 40)}</div>' for i,(n,rot,col) in enumerate(LANES))
    + '</div></div>'
    + panel(
        f'<div style="display:flex;justify-content:space-between">'
        f'{stat("Offset","12 ms late",LATE)}{stat("Spread","&plusmn;18 ms")}'
        f'{stat("Accuracy","94.2%")}{stat("Pass","7")}</div>'
        f'<div style="height:8px"></div>'
        f'<div style="display:flex"><div style="flex:1">{label("Early 40", EARLY)}</div>'
        f'{label("62 Late", LATE)}</div>'
        f'<div style="height:4px"></div>'
        f'<div style="position:relative;height:6px;border-radius:3px;overflow:hidden;display:flex">'
        f'<div style="width:39%;background:{EARLY}"></div>'
        f'<div style="width:61%;background:{LATE}"></div>'
        f'<div style="position:absolute;left:50%;top:0;width:2px;height:100%;background:{PAPER}"></div>'
        f'</div>', pad=12)
    + f'<div>'
      f'<div style="display:flex"><div style="flex:1">{label("Segment")}</div>'
      f'{mono("1:20 &ndash; 1:44")}</div>'
      f'<div style="height:6px"></div>'
      f'<div style="display:flex;align-items:flex-end;gap:1px;height:56px;padding:0 8px;'
      f'background:{INK_PANEL};border-radius:8px">{wave}</div></div>'
    + f'<div>'
      f'<div style="display:flex"><div style="flex:1">{label("Scroll")}</div>{mono("C400")}</div>'
      f'<div style="height:6px"></div>'
      f'<div style="display:flex;gap:6px">{chip("XMod")}{chip("CMod", sel=True)}{chip("MMod")}</div></div>'
    + f'<div style="display:flex;gap:8px">'
    + "".join(f'<div style="width:48px;height:48px;border-radius:8px;'
              f'background:{INK_RAISED if a else "transparent"};display:flex;'
              f'align-items:center;justify-content:center">{icon(d, POSITIVE if a else PAPER, 20)}</div>'
              for d, a in ((I_METRO,True),(I_MIRROR,False),(I_SHUF,False),(I_GHOST,True),(I_LOOP,True)))
    + '</div>'
    + f'<div><div style="height:2px"></div>{label("Challenge gauntlets")}'
      f'<div style="height:6px"></div>'
      f'<div style="display:flex;gap:6px;flex-wrap:wrap">'
      + chip("Streams", sel=True) + chip("Jumps") + chip("Holds") + chip("Timing")
      + '</div></div>'
    + f'<div style="flex:1"></div>', gap=10
), "training")

for name, src in files.items():
    open(name, "w", encoding="utf-8").write(src)

# ── canvas layout ───────────────────────────────────────────────────────
PITCH_X, ROW_Y = 470, 964
row1 = ["Entry","Main","Generate","Ready","Gameplay","Results"]
artboards = [{"file": f"{n}.dc.html", "x": i*PITCH_X, "y": 0, "w": W, "h": H}
             for i, n in enumerate(row1)]
artboards += [
    {"file":"Pause.dc.html","x":4*PITCH_X,"y":ROW_Y,"w":W,"h":H},
    {"file":"Training.dc.html","x":5*PITCH_X,"y":ROW_Y,"w":W,"h":H},
]
canvas = {
  "artboards": artboards,
  "annotations": [
    {"id":"a-entry","x":0,"y":-150,"w":390,
     "text":"1 · ENTRY\nGlyph is reached from the Sampler's header, beside Patterns.\nIt consumes the stems the separator just produced."},
    {"id":"a-main","x":470,"y":-150,"w":390,
     "text":"2 · CHOOSE A SONG\nLocal MP3 and FLAC only — the two formats the conversion\nservice is specified for. Each row states its chart status:\ntempo and tiers when ready, 'No chart' when not."},
    {"id":"a-gen","x":940,"y":-150,"w":390,
     "text":"3 · GENERATE\nDecode, separate the drums, read the rhythm off the\ntransients, write five difficulties to an SSC. Offline, and\nit reports which backend actually ran."},
    {"id":"a-ready","x":1410,"y":-150,"w":390,
     "text":"4 · PICK A TIER\nDefaults to the middle of what exists, not the hardest.\nPlay and Training Ground are peers — practice is not an\nadvanced option hidden behind a first run."},
    {"id":"a-play","x":1880,"y":-150,"w":390,
     "text":"5 · PLAY\nFour lanes, receptors above the notes, explosions above\nthose. The scroll mode sits in the HUD because it is the\nfirst thing checked when a chart reads wrong."},
    {"id":"a-results","x":2350,"y":-150,"w":390,
     "text":"6 · RESULTS\nThe graph is the point: the weakest section is one tap\nfrom becoming a practice loop, with the chart still loaded."},
    {"id":"a-pause","x":1880,"y":ROW_Y-130,"w":390,
     "text":"PAUSE — scroll and speed\nXMod / CMod / MMod. Scroll changes only the reading;\nspeed changes the music. They are constantly confused,\nso they are separate controls with separate labels."},
    {"id":"a-training","x":2350,"y":ROW_Y-130,"w":390,
     "text":"TRAINING GROUND — the loop back\nSegment handles on the waveform, live early/late and\nspread, ghost of the last run, and gauntlets that find the\npassage in this chart rather than a synthetic one."},
  ],
  "launch": {"view":"canvas"}
}
open("canvas.json","w",encoding="utf-8").write(json.dumps(canvas, indent=2))
print("artboards:", len(files), "| canvas entries:", len(artboards))
