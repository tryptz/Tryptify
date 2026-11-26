#!/usr/bin/env python3
"""Compiles the 3D spectrogram's shader with Skia and draws a frame with it.

The cover's 3D spectrogram lights its mesh with an AGSL shader
(SPECTROGRAM_3D_SHADER in ui/player/Spectrogram3D.kt). A phone that refuses
the shader says nothing: the view falls back to lighting the mesh's points.
Skia's runtime-effect compiler is what Android runs AGSL through, so this
compiles the source as it stands in the Kotlin, then draws a made-up
landscape the way the app does (drawVertices with texture coordinates, the
shader as the paint's shader, the heights as two 8-bit alpha textures) and
checks the picture has the floor's colour, white and the tops' colour in it.

Needs skia-python and numpy. Run after changing the shader:

    python3 tools/spectrogram_3d/check.py [out.png]
"""
import math, os, re, sys
import numpy as np
import skia

HERE = os.path.dirname(os.path.abspath(__file__))
KT = os.path.join(HERE, '../../app/src/main/java/tf/monochrome/android/ui/player/Spectrogram3D.kt')
src = re.search(r'internal const val SPECTROGRAM_3D_SHADER = """\n(.*?)"""', open(KT).read(), re.S).group(1)
effect = skia.RuntimeEffect.MakeForShader(src)  # raises with the compiler's message

W, H = 720, 288
COLS, ROWS = 256, 97
# A made-up landscape: a flat floor, three bars along time, one wall across pitch.
j, k = np.mgrid[0:ROWS, 0:COLS]
h = np.zeros((ROWS, COLS))
for row, level in ((12, 1.0), (34, 0.7), (60, 0.5)):
    h = np.maximum(h, level * np.exp(-0.5 * ((j - row) / 3.0) ** 2) * (k > 40))
h = np.maximum(h, 0.85 * np.exp(-0.5 * ((k - 150) / 4.0) ** 2))
q = np.round(np.clip(h, 0, 1) * 65535).astype(np.uint32)
hi = (q >> 8).astype(np.uint8)
lo = (q & 255).astype(np.uint8)

# The app's camera (Spectrogram3DScene.Camera.of) at 18 degrees, and its grid.
angle, distance, target = math.radians(18), 1.6, 0.5
sin_a, cos_a = math.sin(angle), math.cos(angle)
cam_y, cam_z = distance * sin_a, target - distance * cos_a
py = lambda y, z: ((y - cam_y) * cos_a + (z - cam_z) * sin_a) / (-(y - cam_y) * sin_a + (z - cam_z) * cos_a)
scale = H * (0.985 - 0.16) / (py(0, 1) - py(0, 0))
origin_y = H * 0.985 + py(0, 0) * scale
front_half = (W / 2 * (distance - target * cos_a)) / scale
dx, dz, lift = 2 * front_half / 128, 1.0 / (ROWS - 1), 0.045

def project(x, y, z):
    qy, qz = y - cam_y, z - cam_z
    cz = max(-qy * sin_a + qz * cos_a, 0.05)
    return W / 2 + x / cz * scale, origin_y - (qy * cos_a + qz * sin_a) / cz * scale

right = front_half * 1.9
mesh_cols, mesh_rows = 120, 49
pos, tex, idx = [], [], []
for c in range(mesh_cols):
    for m in range(mesh_rows):
        row = m * 2
        col = c * 2
        x = right - col * dx
        pos.append(skia.Point(*project(x, h[row, col] * lift, row * dz)))
        tex.append(skia.Point(col + 0.5, row + 0.5))
for m in range(mesh_rows - 2, -1, -1):
    for c in range(mesh_cols - 1):
        a, b = c * mesh_rows + m, (c + 1) * mesh_rows + m
        idx += [a + 1, b + 1, a, b + 1, b, a]

builder = skia.RuntimeShaderBuilder(effect)
sampling = skia.SamplingOptions(skia.FilterMode.kLinear)
for name, arr in (('uHi', hi), ('uLo', lo)):
    image = skia.Image.fromarray(np.ascontiguousarray(arr), colorType=skia.kAlpha_8_ColorType)
    builder.setChild(name, image.makeShader(skia.TileMode.kRepeat, skia.TileMode.kClamp, sampling))
for name, value in dict(
    uGrid=[dx, dz, lift], uOrigin=[0.5, right], uCam=[cam_y, cam_z, scale], uTilt=[sin_a, cos_a],
    uBase=[0.29, 0.30, 0.88], uGlow=[1.0, 0.2, 0.3], uLook=[0.6, 0.7, 0.55, ROWS - 1.0],
).items():
    builder.setUniform(name, [float(v) for v in value])
paint = skia.Paint()
paint.setShader(builder.makeShader())

surface = skia.Surface(W, H)
canvas = surface.getCanvas()
canvas.clear(skia.Color(20, 14, 40))
canvas.drawVertices(skia.Vertices(skia.Vertices.kTriangles_VertexMode, pos, tex, None, idx), paint, skia.BlendMode.kModulate)
image = surface.makeImageSnapshot()
out = sys.argv[1] if len(sys.argv) > 1 else 'spectrogram_3d.png'
image.save(out, skia.kPNG)

rgb = image.toarray(colorType=skia.kRGBA_8888_ColorType)[..., :3].reshape(-1, 3).astype(int)
blue = np.sum((rgb[:, 2] > 150) & (rgb[:, 0] < 120))
white = np.sum(rgb.min(axis=1) > 200)
red = np.sum((rgb[:, 0] > 120) & (rgb[:, 0] > rgb[:, 2] + 40))
total = len(rgb)
print(f'compiled; drew {out}: floor {blue / total:.0%}, white {white / total:.1%}, tops {red / total:.1%}')
assert blue > total * 0.15, 'no floor in the album colour'
assert white > total * 0.005, 'no white flanks'
assert red > total * 0.001, 'no tops in the second colour'
print('ok')
