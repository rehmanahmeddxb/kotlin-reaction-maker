#!/usr/bin/env python3
"""Generates res/mipmap/ic_launcher.png for Ahmed Reaction Studio.

Pure-python PNG writer (stdlib only).  Draws a dark rounded square with an
orange circle and a white play-triangle - no fonts, no external deps.
"""
import math
import struct
import sys
import zlib
import os

SIZE = 192
SS = 3  # supersample factor
W = SIZE * SS


def rounded_alpha(x, y, r):
    # rounded-rect signed distance
    cx = min(max(x, r), W - r)
    cy = min(max(y, r), W - r)
    d = math.hypot(x - cx, y - cy)
    edge = r
    a = 1.0
    if d > edge - 1.0:
        a = 1.0 - (d - edge + 1.0) * 0.5
    return max(0.0, min(1.0, a))


def inside_circle(x, y, cx, cy, r):
    return math.hypot(x - cx, y - cy) <= r


def dist_circle(x, y, cx, cy, r):
    return math.hypot(x - cx, y - cy) - r


def sdf_circle(x, y, cx, cy, r):
    return math.hypot(x - cx, y - cy) - r


def in_triangle(x, y, cx, cy, size):
    # equilateral-ish triangle pointing right, roughly centered at (cx, cy)
    top = (cx - size * 0.42, cy - size * 0.62)
    bot = (cx - size * 0.42, cy + size * 0.62)
    tip = (cx + size * 0.72, cy)
    d1 = (top[1] - bot[1]) * (x - bot[0]) + (bot[0] - top[0]) * (y - bot[1])
    d2 = (bot[1] - tip[1]) * (x - tip[0]) + (tip[0] - bot[0]) * (y - tip[1])
    d3 = (tip[1] - top[1]) * (x - top[0]) + (top[0] - tip[0]) * (y - top[1])
    has_neg = d1 < 0 or d2 < 0 or d3 < 0
    has_pos = d1 > 0 or d2 > 0 or d3 > 0
    return not (has_neg and has_pos)


def triangle_sdf(x, y, cx, cy, size):
    top = (cx - size * 0.42, cy - size * 0.62)
    bot = (cx - size * 0.42, cy + size * 0.62)
    tip = (cx + size * 0.72, cy)
    # inside test + distance to edges approx (soft edge)
    if in_triangle(x, y, cx, cy, size):
        return -1.0
    # distance to segment
    best = 1e9
    for a, b in ((top, bot), (bot, tip), (tip, top)):
        ax, ay = a
        bx, by = b
        px = bx - ax
        py = by - ay
        t = max(0.0, min(1.0, ((x - ax) * px + (y - ay) * py) / (px * px + py * py)))
        dx = x - (ax + t * px)
        dy = y - (ay + t * py)
        best = min(best, math.hypot(dx, dy))
    return best


def shade(x, y):
    """returns (r,g,b,a) 0..255 for a supersampled pixel"""
    bg = (13, 15, 21)
    panel = (33, 36, 46)
    orange = (255, 92, 44)
    orange2 = (255, 150, 44)
    white = (245, 245, 250)

    cx = W / 2.0
    cy = W / 2.0
    corner_r = W * 0.21

    # background: rounded square with slight vertical gradient + orange ring
    a_bg = rounded_alpha(x, y, corner_r)
    frac = y / W
    base_r = bg[0] + (panel[0] - bg[0]) * frac
    base_g = bg[1] + (panel[1] - bg[1]) * frac
    base_b = bg[2] + (panel[2] - bg[2]) * frac
    col = [base_r, base_g, base_b]

    # orange ring
    ring_center = W * 0.5
    d = math.hypot(x - cx, y - cy)
    r_outer = W * 0.42
    r_inner = W * 0.335
    ring = max(0.0, 1.0 - abs(d - (r_outer + r_inner) / 2.0) / ((r_outer - r_inner) / 2.0))
    ring = max(0.0, min(1.0, ring))
    if ring > 0.004:
        t = (math.atan2(y - cy, x - cx) + math.pi) / (2 * math.pi)
        gx = orange[0] + (orange2[0] - orange[0]) * t
        gy = orange[1] + (orange2[1] - orange[1]) * t
        gb = orange[2] + (orange2[2] - orange[2]) * t
        col[0] = col[0] * (1 - ring) + gx * ring
        col[1] = col[1] * (1 - ring) + gy * ring
        col[2] = col[2] * (1 - ring) + gb * ring

    # big play triangle (white) with slight orange glow
    ts = triangle_sdf(x, y, cx, cy + W * 0.02, W * 0.24)
    if ts < 2.5:
        a_tri = max(0.0, 1.0 - ts / 1.2)
        col[0] = col[0] * (1 - a_tri) + white[0] * a_tri
        col[1] = col[1] * (1 - a_tri) + white[1] * a_tri
        col[2] = col[2] * (1 - a_tri) + white[2] * a_tri
    return (col[0], col[1], col[2], a_bg)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "res/mipmap/ic_launcher.png"
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    raw = bytearray()
    for j in range(W):
        row = bytearray()
        row.append(0)  # filter: none
        for i in range(W):
            rs = gs = bs = aas = 0.0
            for dy in (0, 1, 2):
                for dx in (0, 1, 2):
                    r, g, b, a = shade(i + dx / 3.0, j + dy / 3.0)
                    rs += r
                    gs += g
                    bs += b
                    aas += a
            n = 9.0
            rr = max(0, min(255, int(round(rs / n))))
            gg = max(0, min(255, int(round(gs / n))))
            bb = max(0, min(255, int(round(bs / n))))
            aa = max(0, min(255, int(round(aas / n))))
            row += bytes((rr, gg, bb, aa))
        raw += row

    # downscale
    final = bytearray()
    for j in range(SIZE):
        final.append(0)
        for i in range(SIZE):
            rs = gs = bs = aas = 0
            for sy in range(SS):
                for sx in range(SS):
                    idx = ((j * SS + sy) * W + (i * SS + sx)) * 4
                    rs += raw[idx]
                    gs += raw[idx + 1]
                    bs += raw[idx + 2]
                    aas += raw[idx + 3]
            n = SS * SS
            final += bytes((rs // n, gs // n, bs // n, aas // n))

    def chunk(typ, data):
        c = struct.pack(">I", len(data)) + typ + data
        return c + struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(final), 9))
    png += chunk(b"IEND", b"")
    open(out, "wb").write(png)
    print("wrote", out)


if __name__ == "__main__":
    main()
