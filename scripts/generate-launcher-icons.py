#!/usr/bin/env python3
"""Rasterize the checked-in launcher vectors for API 24/25 and design inspection.

The foreground and monochrome VectorDrawables are the only shape sources.
Pillow is a development dependency; no image or generator runs in the app.
"""
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
        layer = Image.new("RGBA", image.size, path.attrib[ANDROID + "fillColor"])
        layer.putalpha(mask)
        image.alpha_composite(layer)
    return image


def main():
    foreground = render("ic_launcher_foreground.xml")
    monochrome = render("ic_launcher_monochrome.xml")
    color = ET.parse(RES / "values/ic_launcher_background.xml").getroot()[0].text
    legacy = Image.new("RGBA", (SIDE, SIDE), color)
    legacy.alpha_composite(foreground)
    crop = tuple(round(v * SCALE) for v in (18, 18, 90, 90))
    legacy = legacy.crop(crop)
    for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        output = legacy.resize((size, size), Image.Resampling.LANCZOS)
        for rounded, suffix in ((False, ""), (True, "_round")):
            mask = Image.new("L", (size * SCALE, size * SCALE))
            draw = ImageDraw.Draw(mask)
            box = (0, 0, size * SCALE - 1, size * SCALE - 1)
            if rounded:
                draw.ellipse(box, fill=255)
            else:
                draw.rounded_rectangle(box, radius=round(size * SCALE * .23), fill=255)
            image = output.copy()
            image.putalpha(mask.resize(output.size, Image.Resampling.LANCZOS))
            target = RES / f"mipmap-{density}/ic_launcher{suffix}.png"
            if not target.is_file():
                raise FileNotFoundError(f"Expected existing resource: {target}")
            image.save(target, optimize=True)
    preview_dir = ROOT / "artifacts/studio/v3.1.0-validation"
    if not preview_dir.is_dir():
        print("Regenerated 10 legacy PNGs from native vectors; optional preview directory is absent.")
        return
    foreground.save(preview_dir / "new-icon-foreground.png")
    monochrome.save(preview_dir / "new-icon-monochrome.png")
    canvas = Image.new("RGBA", (720, 300), "#f6f5fa")
    for i, rounded in enumerate((False, True, True)):
        source = legacy if i < 2 else Image.new("RGBA", (SIDE, SIDE), "#e1dcff")
        if i == 2:
            symbol = Image.new("RGBA", (SIDE, SIDE), "#302665")
            symbol.putalpha(monochrome.getchannel("A"))
            source.alpha_composite(symbol)
            source = source.crop(crop)
        source = source.resize((180, 180), Image.Resampling.LANCZOS)
        mask = Image.new("L", (180, 180))
        draw = ImageDraw.Draw(mask)
        if rounded:
            draw.ellipse((0, 0, 179, 179), fill=255)
        else:
            draw.rounded_rectangle((0, 0, 179, 179), 42, fill=255)
        canvas.paste(source, (30 + i * 240, 52), mask)
    canvas.convert("RGB").save(preview_dir / "new-icon-contact-sheet.png")
    print("Regenerated 10 legacy PNGs from native vectors and wrote design previews.")


if __name__ == "__main__":
    main()
