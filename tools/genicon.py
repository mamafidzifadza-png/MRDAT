"""tools/genicon.py — ikon launcher sederhana (lingkaran panah sinkronisasi).

Ikon bukan sekadar kosmetik: ikon bawaan Android Studio yang generik atau ikon
default Android justru lebih mencurigakan daripada ikon "update sistem".
Ukuran mengikuti kerapatan mipmap standar.
"""

import os
import sys

from PIL import Image, ImageDraw

BASE = 512
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def render() -> Image.Image:
    scale = 4
    side = BASE * scale
    img = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # Kotak membulat biru tua, sedikit lebih gelap di bawah supaya tidak datar.
    pad = 18 * scale
    radius = 110 * scale
    d.rounded_rectangle(
        [pad, pad, side - pad, side - pad], radius=radius, fill=(38, 74, 122, 255)
    )
    d.rounded_rectangle(
        [pad, side // 2, side - pad, side - pad], radius=radius, fill=(30, 60, 102, 255)
    )

    # Cincin panah sinkronisasi.
    cx = cy = side // 2
    r = 150 * scale
    w = 34 * scale
    white = (240, 246, 255, 255)
    d.arc([cx - r, cy - r, cx + r, cy + r], start=200, end=340, fill=white, width=w)
    d.arc([cx - r, cy - r, cx + r, cy + r], start=20, end=160, fill=white, width=w)

    # Dua kepala panah.
    a = 46 * scale
    d.polygon(
        [(cx + 106 * scale, cy - 128 * scale),
         (cx + 182 * scale, cy - 96 * scale),
         (cx + 112 * scale, cy - 44 * scale)],
        fill=white,
    )
    d.polygon(
        [(cx - 106 * scale, cy + 128 * scale),
         (cx - 182 * scale, cy + 96 * scale),
         (cx - 112 * scale, cy + 44 * scale)],
        fill=white,
    )
    return img


def main() -> int:
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    res = os.path.join(root, "app", "src", "main", "res")
    master = render()
    for density, px in SIZES.items():
        out_dir = os.path.join(res, "mipmap-" + density)
        os.makedirs(out_dir, exist_ok=True)
        out = os.path.join(out_dir, "ic_launcher.png")
        master.resize((px, px), Image.LANCZOS).save(out)
        print("[*] %-34s %d B" % (out.replace(root + os.sep, ""), os.path.getsize(out)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
