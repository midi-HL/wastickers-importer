#!/usr/bin/env python3
"""Rasterize the checked-in launcher vectors for API 24/25 and design inspection.

The foreground and monochrome VectorDrawables are the only shape sources.
Pillow is a development dependency; no image or generator runs in the app.
"""
import argparse
import json
import math
from pathlib import Path
import re
import xml.etree.ElementTree as ET

from PIL import Image, ImageChops, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
ANDROID = "{http://schemas.android.com/apk/res/android}"
SCALE = 8
SIDE = 108 * SCALE


def contours(data):
    """Sample this icon's absolute M/L/H/V/C/Z commands; fail on other input."""
    tokens = re.findall(r"[A-Za-z]|[-+]?(?:\d*\.\d+|\d+)(?:[eE][-+]?\d+)?", data)
    position = 0j
    start = 0j
    points = []
    result = []
    command = None
    i = 0

    def number():
        nonlocal i
        value = float(tokens[i])
        i += 1
        return value

    while i < len(tokens):
        if tokens[i].isalpha():
            command = tokens[i]
            i += 1
        if command == "M":
            if points:
                result.append(points)
            position = complex(number(), number())
            start = position
            points = [position]
            command = "L"
        elif command == "L":
            position = complex(number(), number())
            points.append(position)
        elif command == "H":
            position = complex(number(), position.imag)
            points.append(position)
        elif command == "V":
            position = complex(position.real, number())
            points.append(position)
        elif command == "C":
            p0 = position
            p1 = complex(number(), number())
            p2 = complex(number(), number())
            p3 = complex(number(), number())
            for step in range(1, 33):
                t = step / 32
                points.append((1 - t) ** 3 * p0 + 3 * (1 - t) ** 2 * t * p1 + 3 * (1 - t) * t ** 2 * p2 + t ** 3 * p3)
            position = p3
        elif command == "Z":
            points.append(start)
            result.append(points)
            points = []
            position = start
            command = None
        else:
            raise ValueError(f"Unsupported icon command: {command!r}")
    if points:
        result.append(points)
    return result


def android_color(value):
    """Translate VectorDrawable #RRGGBB / #AARRGGBB, not CSS #RRGGBBAA."""
    digits = value.removeprefix("#")
    if len(digits) == 6:
        return tuple(int(digits[i:i + 2], 16) for i in (0, 2, 4)) + (255,)
    if len(digits) == 8:
        return tuple(int(digits[i:i + 2], 16) for i in (2, 4, 6, 0))
    raise ValueError(f"Unsupported Android color: {value!r}")


def render(name):
    image = Image.new("RGBA", (SIDE, SIDE))
    for path in ET.parse(RES / "drawable" / name).getroot():
        if not path.tag.endswith("path"):
            continue
        mask = Image.new("L", image.size)
        for contour in contours(path.attrib[ANDROID + "pathData"]):
            part = Image.new("L", image.size)
            ImageDraw.Draw(part).polygon([(p.real * SCALE, p.imag * SCALE) for p in contour], fill=255)
            mask = ImageChops.difference(mask, part) if path.get(ANDROID + "fillType") == "evenOdd" else ImageChops.lighter(mask, part)
        rgba = android_color(path.attrib[ANDROID + "fillColor"])
        layer = Image.new("RGBA", image.size, rgba)
        layer.putalpha(mask.point(lambda coverage: round(coverage * rgba[3] / 255)))
        image.alpha_composite(layer)
    return image


MASKS = ("Square", "Rectangle", "Rounded square", "Squircle", "Pebble", "Circle check")


def launcher_mask(name, size):
    """Representative masks, not claims to reproduce any vendor's proprietary mask."""
    mask = Image.new("L", (size, size))
    draw = ImageDraw.Draw(mask)
    box = (0, 0, size - 1, size - 1)
    if name == "Square":
        draw.rectangle(box, fill=255)
    elif name == "Rectangle":
        draw.rounded_rectangle((0, round(size * .06), size - 1, round(size * .94) - 1), radius=round(size * .12), fill=255)
    elif name == "Rounded square":
        draw.rounded_rectangle(box, radius=round(size * .23), fill=255)
    elif name == "Circle check":
        draw.ellipse(box, fill=255)
    elif name == "Squircle":
        points = []
        for step in range(720):
            t = step * math.tau / 720
            c, s = math.cos(t), math.sin(t)
            points.append(((.5 + .5 * math.copysign(abs(c) ** .5, c)) * (size - 1),
                           (.5 + .5 * math.copysign(abs(s) ** .5, s)) * (size - 1)))
        draw.polygon(points, fill=255)
    elif name == "Pebble":
        path = "M50,0 C81,0 100,18 100,47 C100,78 81,100 50,100 C20,100 0,81 0,51 C0,22 19,0 50,0 Z"
        draw.polygon([(p.real / 100 * (size - 1), p.imag / 100 * (size - 1))
                      for p in contours(path)[0]], fill=255)
    else:
        raise ValueError(name)
    return mask


def center_of_mass(image, background=None):
    sums = [0., 0., 0.]
    for y in range(image.height):
        for x in range(image.width):
            r, g, b, a = image.getpixel((x, y))
            weight = a / 255
            if background is not None:
                weight *= abs(.2126 * (r - background[0]) + .7152 * (g - background[1]) + .0722 * (b - background[2]))
            sums[0] += (x + .5) / SCALE * weight
            sums[1] += (y + .5) / SCALE * weight
            sums[2] += weight
    if sums[2] == 0:
        raise ValueError("Empty icon")
    return [round(sums[0] / sums[2], 3), round(sums[1] / sums[2], 3)]


def validate_layers(foreground, monochrome, background):
    alpha = foreground.getchannel("A")
    for y in range(SIDE):
        for x in range(SIDE):
            if alpha.getpixel((x, y)) > 32:
                if math.hypot((x + .5) / SCALE - 54, (y + .5) / SCALE - 54) > 33:
                    raise ValueError("Icon mark leaves the adaptive safe circle")
    report = {
        "foreground_alpha_center_dp": center_of_mass(foreground),
        "foreground_contrast_center_dp": center_of_mass(foreground, background),
        "monochrome_alpha_center_dp": center_of_mass(monochrome),
        "masks": [],
    }
    for key in ("foreground_alpha_center_dp", "foreground_contrast_center_dp", "monochrome_alpha_center_dp"):
        cx, cy = report[key]
        if abs(cx - 54) > .35 or abs(cy - 54) > .8:
            raise ValueError(f"Unbalanced {key}: {report[key]}")
    crop = tuple(round(v * SCALE) for v in (18, 18, 90, 90))
    for name in MASKS:
        mask = launcher_mask(name, 72 * SCALE)
        for label, image in (("color", foreground), ("monochrome", monochrome)):
            missing = ImageChops.subtract(image.getchannel("A").crop(crop), mask)
            if missing.getbbox() is not None:
                raise ValueError(f"{name} clips the {label} symbol")
        report["masks"].append({"mask": name, "clipped_mark_pixels": 0})
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--preview-dir", type=Path, help="Optional directory for multi-mask previews and checks")
    args = parser.parse_args()
    foreground = render("ic_launcher_foreground.xml")
    monochrome = render("ic_launcher_monochrome.xml")
    color = ET.parse(RES / "values/ic_launcher_background.xml").getroot()[0].text
    background = android_color(color)[:3]
    report = validate_layers(foreground, monochrome, background)
    legacy = Image.new("RGBA", (SIDE, SIDE), android_color(color))
    legacy.alpha_composite(foreground)
    crop = tuple(round(v * SCALE) for v in (18, 18, 90, 90))
    legacy = legacy.crop(crop)
    densities = (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192))
    targets = [(RES / f"mipmap-{density}/ic_launcher.png", size) for density, size in densities]
    for target, _ in targets:
        if not target.is_file():
            raise FileNotFoundError(f"Expected existing resource: {target}")
    for target, size in targets:
        # API 24/25 fallback is a complete square; do not bake a launcher mask into it.
        image = legacy.resize((size, size), Image.Resampling.LANCZOS)
        image.save(target, optimize=True)
    if args.preview_dir is None:
        print(f"Validated {len(MASKS)} masks and regenerated five non-circular legacy icons.")
        return
    args.preview_dir.mkdir(parents=True, exist_ok=True)
    foreground.save(args.preview_dir / "icon-foreground.png")
    monochrome.save(args.preview_dir / "icon-monochrome.png")
    (args.preview_dir / "icon-checks.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    canvas = Image.new("RGBA", (220 * len(MASKS), 570), "#f6f5fa")
    draw = ImageDraw.Draw(canvas)
    mono = Image.new("RGBA", (SIDE, SIDE), "#e1dcff")
    symbol = Image.new("RGBA", (SIDE, SIDE), "#302665")
    symbol.putalpha(monochrome.getchannel("A"))
    mono.alpha_composite(symbol)
    for col, name in enumerate(MASKS):
        draw.text((col * 220 + 24, 16), name, fill="#292332", font_size=19)
        for row, source in enumerate((legacy, mono.crop(crop))):
            image = source.resize((168, 168), Image.Resampling.LANCZOS)
            mask = launcher_mask(name, 168 * SCALE).resize(image.size, Image.Resampling.LANCZOS)
            canvas.paste(image, (col * 220 + 24, row * 250 + 58), mask)
    draw.text((24, 530), "Color / themed monochrome | representative launcher masks", fill="#494253", font_size=18)
    canvas.convert("RGB").save(args.preview_dir / "icon-shape-preview.png")
    print(json.dumps(report))
    print("Regenerated five legacy PNGs and multi-mask previews.")


if __name__ == "__main__":
    main()
