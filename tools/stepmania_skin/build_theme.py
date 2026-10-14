#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""
Cut StepMania 5's default theme into Trypt Mania's menus and gameplay art.

    python3 tools/stepmania_skin/build_theme.py /path/to/stepmania /path/to/fonts

Reads Themes/default from a StepMania checkout (MIT licensed) and writes to
app/src/main/assets/stepmania/theme/:

- judgment_<w1..w5|miss>_<early|late>.png, from "Judgment Normal 2x6.png".
  StepMania picks the frame as row * 2, plus one when the step was late
  ("Player judgment/default.lua"), so the left column is early.
- grade_tier01.png .. grade_tier17.png and grade_failed.png: AAA with stars
  down to D-, and F.
- fullcombo_w1.png .. fullcombo_w3.png: the FULL COMBO banners.

and the theme's typefaces to fonts/, which StepMania draws from bitmaps of
Roboto and Roboto Condensed ("Common Normal", "Combo Numbers", "OptionRow
title"). The bitmaps are fixed sizes; the typefaces themselves (SIL Open Font
License) are what the app wants, so the second argument is a folder holding
static instances of them:

    stepmania_bold.ttf                  Roboto Bold
    stepmania_medium.ttf                Roboto Medium
    stepmania_condensed_bold.ttf        Roboto Condensed Bold
    stepmania_condensed_bold_italic.ttf Roboto Condensed Bold Italic
    stepmania_condensed_medium.ttf      Roboto Condensed Medium

cut from google/fonts' variable fonts with fontTools' instancer, plus their
OFL.txt (the two families share one copyright line and licence).
"""

import shutil
import subprocess
import sys
from pathlib import Path

from PIL import Image

REPO = Path(__file__).resolve().parents[2]
OUT = REPO / "app/src/main/assets/stepmania/theme"

JUDGMENTS = ["w1", "w2", "w3", "w4", "w5", "miss"]
FONTS = [
    "stepmania_bold.ttf",
    "stepmania_medium.ttf",
    "stepmania_condensed_bold.ttf",
    "stepmania_condensed_bold_italic.ttf",
    "stepmania_condensed_medium.ttf",
]


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    stepmania = Path(sys.argv[1]).resolve()
    fonts = Path(sys.argv[2]).resolve()
    graphics = stepmania / "Themes/default/Graphics"
    if not graphics.is_dir():
        print(f"no default theme under {stepmania}")
        return 1

    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("*.png"):
        old.unlink()

    sheet = Image.open(graphics / "Judgment Normal 2x6.png").convert("RGBA")
    cell_w, cell_h = sheet.width // 2, sheet.height // 6
    for row, name in enumerate(JUDGMENTS):
        for column, timing in enumerate(("early", "late")):
            cell = sheet.crop((column * cell_w, row * cell_h, (column + 1) * cell_w, (row + 1) * cell_h))
            cell.save(OUT / f"judgment_{name}_{timing}.png", optimize=True)

    for tier in range(1, 18):
        source = graphics / f"GradeDisplay Grade Grade_Tier{tier:02d}.png"
        Image.open(source).convert("RGBA").save(OUT / f"grade_tier{tier:02d}.png", optimize=True)
    Image.open(graphics / "GradeDisplay Grade Grade_Failed.png").convert("RGBA").save(
        OUT / "grade_failed.png", optimize=True,
    )

    for n in (1, 2, 3):
        Image.open(graphics / f"FullCombo W{n} text.png").convert("RGBA").save(
            OUT / f"fullcombo_w{n}.png", optimize=True,
        )

    font_out = OUT / "fonts"
    font_out.mkdir(exist_ok=True)
    for name in FONTS:
        shutil.copyfile(fonts / name, font_out / name)
    shutil.copyfile(fonts / "OFL.txt", font_out / "OFL.txt")

    revision = subprocess.run(
        ["git", "-C", str(stepmania), "rev-parse", "HEAD"],
        capture_output=True, text=True, check=False,
    ).stdout.strip() or "unknown"
    licence = (stepmania / "Docs/Licenses.txt").read_text(encoding="utf-8")
    (OUT / "LICENSE.txt").write_text(
        "The images in this folder are cut from the default theme of StepMania\n"
        f"(https://github.com/stepmania/stepmania, revision {revision}),\n"
        "Themes/default/Graphics, by tools/stepmania_skin/build_theme.py.\n"
        "StepMania's licence follows, as its notice requires.\n\n"
        "The typefaces in fonts/ are Roboto and Roboto Condensed, which StepMania's\n"
        "theme draws its text from, under the SIL Open Font License in fonts/OFL.txt.\n\n"
        + licence,
        encoding="utf-8",
    )
    print(f"theme from {revision[:12]} -> {OUT.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
