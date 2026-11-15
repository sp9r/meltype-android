#!/usr/bin/env python3
"""
Generate Compact-Rail styled adaptive launcher icons and XML vector foreground
for Meltype Android (neutral charcoal surface + single steel-blue glide trail).
"""

from __future__ import annotations

import math
import pathlib
from PIL import Image, ImageDraw, ImageFont

PROJECT_ROOT = pathlib.Path(__file__).resolve().parent.parent
RES_DIR = PROJECT_ROOT / "app" / "src" / "main" / "res"

BG_COLOR = (37, 40, 45, 255)          # #25282D Compact Rail primary charcoal
BG_GRID = (52, 56, 63, 255)           # #34383F subtle grid line
KEY_FILL = (245, 246, 247, 255)       # #F5F6F7 neutral key surface
KEY_SUBTLE = (223, 226, 230, 255)     # #DFE2E6 muted key border/face
ACCENT_BLUE = (92, 138, 196, 255)     # #5C8AC4 restrained steel blue glide trail
ACCENT_LIGHT = (146, 184, 230, 255)   # #92B8E6 inner core
DARK_TEXT = (32, 35, 40, 255)         # #202328


def catmull_rom(points: list[tuple[float, float]], steps_per_seg: int = 24) -> list[tuple[float, float]]:
    if len(points) < 2:
        return points
    ext = [points[0]] + points + [points[-1]]
    out: list[tuple[float, float]] = []
    for i in range(1, len(ext) - 2):
        p0, p1, p2, p3 = ext[i - 1], ext[i], ext[i + 1], ext[i + 2]
        for s in range(steps_per_seg):
            t = s / steps_per_seg
            t2 = t * t
            t3 = t2 * t
            x = 0.5 * (
                (2 * p1[0])
                + (-p0[0] + p2[0]) * t
                + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2
                + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3
            )
            y = 0.5 * (
                (2 * p1[1])
                + (-p0[1] + p2[1]) * t
                + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2
                + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3
            )
            out.append((x, y))
    out.append(points[-1])
    return out


def render_master_layers(size: int = 1024) -> tuple[Image.Image, Image.Image, Image.Image]:
    # 1. Background (1024x1024)
    bg = Image.new("RGBA", (size, size), BG_COLOR)
    bg_draw = ImageDraw.Draw(bg)
    step = size // 8
    for i in range(1, 8):
        pos = i * step
        bg_draw.line([(pos, 0), (pos, size)], fill=BG_GRID, width=max(2, size // 256))
        bg_draw.line([(0, pos), (size, pos)], fill=BG_GRID, width=max(2, size // 256))

    # 2. Foreground (1024x1024, safe zone inside 18%..82%)
    fg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(fg)

    # Compact Rail styled minimal keycaps arranged in subtle 3-row hint inside safe zone
    pad_x = size * 0.22
    top_y = size * 0.28
    key_w = size * 0.125
    key_h = size * 0.115
    gap = size * 0.018
    radius = size * 0.022

    rows = [
        (0.0, 4),
        (key_w * 0.45, 3),
        (key_w * 0.9, 3),
    ]
    centers: list[tuple[float, float]] = []
    for r_idx, (offset, count) in enumerate(rows):
        y0 = top_y + r_idx * (key_h + gap)
        for c_idx in range(count):
            x0 = pad_x + offset + c_idx * (key_w + gap)
            x1 = x0 + key_w
            y1 = y0 + key_h
            # Highlight keycaps on the glide path
            fill = KEY_FILL if (r_idx == 0 and c_idx == 0) or (r_idx == 1 and c_idx == 1) or (r_idx == 2 and c_idx == 2) else (72, 78, 88, 220)
            outline = KEY_SUBTLE if fill == KEY_FILL else (96, 104, 116, 220)
            draw.rounded_rectangle([x0, y0, x1, y1], radius=radius, fill=fill, outline=outline, width=max(2, size // 220))
            centers.append(((x0 + x1) / 2.0, (y0 + y1) / 2.0))

    # Smooth M-shaped glide trail sweeping over the keys (Q -> A -> E -> S -> M)
    trail_anchors = [
        (size * 0.28, size * 0.66),
        (size * 0.34, size * 0.34),
        (size * 0.50, size * 0.54),
        (size * 0.64, size * 0.34),
        (size * 0.72, size * 0.65),
    ]
    curve = catmull_rom(trail_anchors, steps_per_seg=28)

    # Outer soft-crisp stroke and inner tapered trail
    n = len(curve)
    for i in range(n - 1):
        t = (i + 1) / float(n)
        w_outer = max(4, int(size * (0.018 + 0.040 * t)))
        w_inner = max(2, int(size * (0.008 + 0.016 * t)))
        draw.line([curve[i], curve[i + 1]], fill=ACCENT_BLUE, width=w_outer, joint="curve")
        draw.line([curve[i], curve[i + 1]], fill=ACCENT_LIGHT, width=w_inner, joint="curve")

    # Tapered circular joints at endpoints
    for pt, r_frac, col in [
        (curve[0], 0.018, ACCENT_BLUE),
        (curve[-1], 0.036, ACCENT_BLUE),
        (curve[-1], 0.020, KEY_FILL),
    ]:
        r = size * r_frac
        draw.ellipse([pt[0] - r, pt[1] - r, pt[0] + r, pt[1] + r], fill=col)

    # Subtle spacebar / rail bar at bottom of safe zone
    bar_x0 = size * 0.30
    bar_x1 = size * 0.70
    bar_y0 = size * 0.735
    bar_y1 = size * 0.778
    draw.rounded_rectangle([bar_x0, bar_y0, bar_x1, bar_y1], radius=size * 0.018, fill=KEY_SUBTLE)

    # 3. Full legacy square composite
    composite = Image.alpha_composite(bg, fg)
    return bg, fg, composite


def make_round(img: Image.Image) -> Image.Image:
    size = img.size[0]
    mask = Image.new("L", (size, size), 0)
    mdraw = ImageDraw.Draw(mask)
    mdraw.ellipse([0, 0, size - 1, size - 1], fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def write_adaptive_xmls() -> None:
    anydpi = RES_DIR / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    drawable = RES_DIR / "drawable"
    drawable.mkdir(parents=True, exist_ok=True)

    launcher_xml = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""
    (anydpi / "ic_launcher.xml").write_text(launcher_xml, encoding="utf-8")
    (anydpi / "ic_launcher_round.xml").write_text(launcher_xml, encoding="utf-8")


def main() -> int:
    _, fg, composite = render_master_layers(1024)
    round_master = make_round(composite)

    densities = {
        "mdpi": 48,
        "hdpi": 72,
        "xhdpi": 96,
        "xxhdpi": 144,
        "xxxhdpi": 192,
    }
    fg_densities = {
        "mdpi": 108,
        "hdpi": 162,
        "xhdpi": 216,
        "xxhdpi": 324,
        "xxxhdpi": 432,
    }

    for dpi, px in densities.items():
        d = RES_DIR / f"mipmap-{dpi}"
        d.mkdir(parents=True, exist_ok=True)
        composite.resize((px, px), Image.Resampling.LANCZOS).save(d / "ic_launcher.png")
        round_master.resize((px, px), Image.Resampling.LANCZOS).save(d / "ic_launcher_round.png")
        fg_px = fg_densities[dpi]
        fg.resize((fg_px, fg_px), Image.Resampling.LANCZOS).save(d / "ic_launcher_foreground.png")

    write_adaptive_xmls()
    print("[ok] Generated launcher icons across mdpi..xxxhdpi and adaptive mipmap-anydpi-v26.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
