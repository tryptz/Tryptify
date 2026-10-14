#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""
Cut StepMania's "retro" dance noteskin into Trypt Mania's playfield artwork.

    python3 tools/stepmania_skin/build_retro_skin.py /path/to/stepmania

Reads NoteSkins/dance/retro from a StepMania checkout
(https://github.com/stepmania/stepmania, MIT licensed) and writes one PNG per
Trypt Mania asset name to app/src/main/assets/stepmania/skins/retro/, with the
manifest the app reads, the licence and a README naming the revision.

StepMania draws one Down arrow and turns it per column with Lua and metrics;
the app has neither, so the turning is done here, once:

- Taps come from the 8x8 sheet. Its rows are the beat divisions, in the order
  GlyphBeatDivision lists them (4th red, 8th blue, 12th green, 16th yellow,
  24th purple, 32nd teal, 48th pink, 64th grey); its first column is the
  resting frame.
- Lanes are rotated as the skin's NoteSkin.lua rotates them: Left 90,
  Down 0, Up 180, Right -90 degrees, clockwise on screen.
- Hold and roll bodies and end caps are flipped vertically. StepMania's notes
  rise toward receptors at the top; Trypt Mania's fall toward the bottom, so
  a hold's far end is above it rather than below.
- Explosions are 96 px against 64 px notes. They are centred on a 128 px
  canvas, because the playfield draws effects at twice a note's size, which
  keeps them at StepMania's proportion of one and a half notes.
- The pressed receptor is the base with the skin's tap flash over it, cropped
  to the note's 64 px.
- Hold heads keep only the arrow: the stub of body behind it would otherwise
  turn sideways with the arrow in three of the four lanes.

The art is pixel art and is written at its own resolution; the app scales it
up by whole pixels before smoothing to the final size, so it stays sharp.
"""

import json
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageChops

LANES = [("left", 90), ("down", 0), ("up", 180), ("right", -90)]
DIVISIONS = ["4th", "8th", "12th", "16th", "24th", "32nd", "48th", "64th"]
NOTE = 64
EFFECT = 128

REPO = Path(__file__).resolve().parents[2]
OUT = REPO / "app/src/main/assets/stepmania/skins/retro"


def rotate(image: Image.Image, clockwise_degrees: int) -> Image.Image:
    # Quarter turns as transposes, which move whole pixels and lose nothing.
    # StepMania's rotationz is clockwise on screen; PIL's ROTATE_* are
    # counter-clockwise, so 90 clockwise is ROTATE_270.
    turns = {0: None, 90: Image.ROTATE_270, 180: Image.ROTATE_180, -90: Image.ROTATE_90}
    op = turns[clockwise_degrees]
    return image if op is None else image.transpose(op)


def flip(image: Image.Image) -> Image.Image:
    return image.transpose(Image.FLIP_TOP_BOTTOM)


def centred(image: Image.Image, size: int) -> Image.Image:
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(image, ((size - image.width) // 2, (size - image.height) // 2))
    return canvas


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    stepmania = Path(sys.argv[1]).resolve()
    skin = stepmania / "NoteSkins/dance/retro"
    if not skin.is_dir():
        print(f"no retro noteskin under {stepmania}")
        return 1

    def load(name: str) -> Image.Image:
        return Image.open(skin / name).convert("RGBA")

    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("*.png"):
        old.unlink()
    assets = []

    def save(name: str, image: Image.Image, category: str) -> None:
        path = f"{name}.png"
        image.save(OUT / path, optimize=True)
        assets.append({"category": category, "name": name, "path": path})

    sheet = load("_down tap note 8x8.png")
    # The hold head is drawn over a stub of hold body, which StepMania leaves
    # pointing at the tail. Turned with the arrow, that stub would point
    # sideways in three lanes. Only the arrow is kept — the tap note's outline
    # is the same arrow in the same place — and the playfield's own body
    # meets it from above, where the tail is.
    hold_head = load("Down Hold Head inactive.png")
    inside_arrow = sheet.crop((0, 0, NOTE, NOTE)).getchannel("A").point(lambda a: 255 if a else 0)
    hold_head.putalpha(ImageChops.darker(hold_head.getchannel("A"), inside_arrow))
    receptor = load("_down receptor base.png")
    flash = load("_down receptor tap.png")
    explosion = load("Down Tap Explosion Bright.png")
    pressed = receptor.copy()
    offset = (flash.width - NOTE) // 2
    pressed.alpha_composite(flash.crop((offset, offset, offset + NOTE, offset + NOTE)))

    for lane, degrees in LANES:
        for row, division in enumerate(DIVISIONS):
            cell = sheet.crop((0, row * NOTE, NOTE, row * NOTE + NOTE))
            save(f"tap_{lane}_{division}", rotate(cell, degrees), "noteskin/taps")
        resting = sheet.crop((0, 0, NOTE, NOTE))
        save(f"tap_{lane}", rotate(resting, degrees), "noteskin/taps")
        save(f"hold_head_{lane}", rotate(hold_head, degrees), "noteskin/holds")
        # The skin's Lua sends roll heads to the tap note.
        save(f"roll_head_{lane}", rotate(resting, degrees), "noteskin/holds")
        save(f"receptor_{lane}", rotate(receptor, degrees), "noteskin/receptors")
        save(f"receptor_{lane}_active", rotate(pressed, degrees), "noteskin/receptors")
        burst = centred(rotate(explosion, degrees), EFFECT)
        save(f"tap_explosion_{lane}", burst, "feedback/effects")
        # The skin's hold explosion loads the tap explosion.
        save(f"hold_glow_{lane}", burst, "feedback/effects")

    for kind in ("Hold", "Roll"):
        base = kind.lower()
        for state, suffix in (("inactive", ""), ("active", "_active")):
            save(f"{base}_body{suffix}", flip(load(f"Down {kind} Body {state}.png")), "noteskin/holds")
            save(f"{base}_tail{suffix}", flip(load(f"Down {kind} BottomCap {state}.png")), "noteskin/holds")

    mine = load("Down Tap Mine 5x1.png").crop((0, 0, NOTE, NOTE))
    save("mine", mine, "noteskin/special")

    revision = subprocess.run(
        ["git", "-C", str(stepmania), "rev-parse", "HEAD"],
        capture_output=True, text=True, check=False,
    ).stdout.strip() or "unknown"

    manifest = {
        "name": "StepMania retro",
        "version": 1,
        "pixelArt": True,
        "source": "https://github.com/stepmania/stepmania/tree/"
        f"{revision}/NoteSkins/dance/retro",
        "license": "LICENSE.txt",
        "assets": sorted(assets, key=lambda a: a["name"]),
    }
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

    licence = (stepmania / "Docs/Licenses.txt").read_text(encoding="utf-8")
    (OUT / "LICENSE.txt").write_text(
        "The artwork in this folder is derived from the \"retro\" dance noteskin of\n"
        f"StepMania (https://github.com/stepmania/stepmania, revision {revision}),\n"
        "cut, rotated and flipped by tools/stepmania_skin/build_retro_skin.py.\n"
        "StepMania's licence follows, as its notice requires.\n\n" + licence,
        encoding="utf-8",
    )
    print(f"{len(assets)} images from {revision[:12]} -> {OUT.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
