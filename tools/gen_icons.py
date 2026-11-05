#!/usr/bin/env python3
"""Генератор PNG-иконок приложения (mipmap-*dpi) без внешних зависимостей.

Рисует тёмную скруглённую плашку с зелёной "молнией" и пишёт обычный
RGBA PNG (zlib + struct), поэтому Pillow/Inkscape не нужны.

Запуск:  python3 tools/gen_icons.py
"""
from __future__ import annotations

import math
import os
import struct
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
SS = 4  # суперсэмплинг для сглаживания

BG = (0x0D, 0x15, 0x24)
BOLT = (0x34, 0xD3, 0x7A)
RING = (0x22, 0xC5, 0x5E)

# Молния в системе координат 0..108 (как в vector-иконке)
BOLT_POLY = [(60, 16), (32, 62), (51, 62), (45, 92), (76, 44), (56, 44)]


def _cross(o, a, b):
    return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])


def _convex_hull(points):
    pts = sorted(set(points))
    if len(pts) <= 2:
        return pts
    lower = []
    for p in pts:
        while len(lower) >= 2 and _cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    upper = []
    for p in reversed(pts):
        while len(upper) >= 2 and _cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return lower[:-1] + upper[:-1]


def _point_in_poly(x, y, poly):
    inside = False
    n = len(poly)
    j = n - 1
    for i in range(n):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi + 1e-12) + xi:
            inside = not inside
        j = i
    return inside


def _dist_to_segment(px, py, ax, ay, bx, by):
    vx, vy = bx - ax, by - ay
    wx, wy = px - ax, py - ay
    denom = vx * vx + vy * vy
    t = 0.0 if denom == 0 else max(0.0, min(1.0, (wx * vx + wy * vy) / denom))
    dx, dy = px - (ax + t * vx), py - (ay + t * vy)
    return math.hypot(dx, dy)


def _dist_to_poly(x, y, poly):
    return min(
        _dist_to_segment(x, y, poly[i][0], poly[i][1], poly[(i + 1) % len(poly)][0], poly[(i + 1) % len(poly)][1])
        for i in range(len(poly))
    )


def render(size: int) -> bytes:
    radius = size * 0.22
    scale = size / 108.0
    bolt = [(x * scale, y * scale) for (x, y) in BOLT_POLY]
    cx = cy = size / 2.0
    ring_r = size * 0.40
    ring_w = max(1.0, size * 0.018)
    step = 1.0 / SS
    out = bytearray()
    for py in range(size):
        for px in range(size):
            r = g = b = 0
            a = 0
            for sy in range(SS):
                y = py + (sy + 0.5) * step
                for sx in range(SS):
                    x = px + (sx + 0.5) * step
                    # скруглённый квадрат
                    qx = max(abs(x - cx) - (size / 2.0 - radius), 0.0)
                    qy = max(abs(y - cy) - (size / 2.0 - radius), 0.0)
                    if math.hypot(qx, qy) > radius:
                        continue
                    a += 1
                    rr, gg, bb = BG
                    d = abs(math.hypot(x - cx, y - cy) - ring_r)
                    if d <= ring_w:
                        t = 1.0 - d / ring_w
                        rr = int(BG[0] + (RING[0] - BG[0]) * t * 0.55)
                        gg = int(BG[1] + (RING[1] - BG[1]) * t * 0.55)
                        bb = int(BG[2] + (RING[2] - BG[2]) * t * 0.55)
                    inside = _point_in_poly(x, y, bolt)
                    if not inside and _dist_to_poly(x, y, bolt) < size * 0.012:
                        inside = True
                    if inside:
                        rr, gg, bb = BOLT
                    r += rr
                    g += gg
                    b += bb
            total = SS * SS
            out += bytes((r // total, g // total, b // total, a * 255 // total))
    return bytes(out)


def write_png(path: str, size: int, rgba: bytes) -> None:
    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    raw = b"".join(b"\x00" + rgba[y * size * 4 : (y + 1) * size * 4] for y in range(size))
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(png)


def main() -> None:
    for density, size in SIZES.items():
        rgba = render(size)
        folder = os.path.join(RES, f"mipmap-{density}")
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            path = os.path.join(folder, name)
            write_png(path, size, rgba)
            print(f"{os.path.relpath(path, ROOT)}  {size}x{size}")


if __name__ == "__main__":
    main()
