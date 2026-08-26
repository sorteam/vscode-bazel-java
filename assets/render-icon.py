#!/usr/bin/env python3
"""Renders assets/icon.svg to extension/icon.png without any third-party dependency.

The shapes in icon.svg are flat polygons and one rounded rectangle, so they are re-declared here
rather than parsed: a scanline fill at 4x with a box downsample gives clean edges, and the whole
thing stays in the standard library. Keep the two files in sync when the artwork changes.
"""

import os
import struct
import zlib

SIZE = 256
SCALE = 4
SS = SIZE * SCALE

BACKGROUND = (0x1B, 0x24, 0x30)
CORNER_RADIUS = 48

# Same geometry as icon.svg, in the 256x256 coordinate system.
POLYGONS = [
    ([(128, 44), (214, 92), (128, 140), (42, 92)], (0x7C, 0xC3, 0x6B)),   # top face
    ([(42, 92), (128, 140), (128, 212), (42, 164)], (0x3F, 0x8F, 0x4A)),  # left face
    ([(214, 92), (128, 140), (128, 212), (214, 164)], (0x2C, 0x6B, 0x39)),  # right face
    ([(128, 140), (214, 92), (214, 103), (128, 151)], (0xE8, 0x8A, 0x34)),  # seam, right
    ([(42, 92), (128, 140), (128, 151), (42, 103)], (0xF0, 0xA7, 0x5C)),   # seam, left
]


def blank():
    return bytearray(SS * SS * 4)


def fill_rounded_rect(buffer, radius, colour):
    r = radius * SCALE
    for y in range(SS):
        if y < r:
            dy = r - 1 - y
        elif y >= SS - r:
            dy = y - (SS - r)
        else:
            dy = None
        if dy is None:
            x0, x1 = 0, SS
        else:
            inset = r - int((r * r - dy * dy) ** 0.5)
            x0, x1 = inset, SS - inset
        row = y * SS * 4
        for x in range(x0, x1):
            offset = row + x * 4
            buffer[offset:offset + 4] = bytes((*colour, 255))


def fill_polygon(buffer, points, colour):
    scaled = [(x * SCALE, y * SCALE) for x, y in points]
    top = max(0, int(min(y for _, y in scaled)))
    bottom = min(SS - 1, int(max(y for _, y in scaled)))
    packed = bytes((*colour, 255))
    for y in range(top, bottom + 1):
        centre = y + 0.5
        crossings = []
        for i in range(len(scaled)):
            x0, y0 = scaled[i]
            x1, y1 = scaled[(i + 1) % len(scaled)]
            if (y0 <= centre < y1) or (y1 <= centre < y0):
                crossings.append(x0 + (centre - y0) * (x1 - x0) / (y1 - y0))
        crossings.sort()
        row = y * SS * 4
        for i in range(0, len(crossings) - 1, 2):
            start = max(0, int(crossings[i] + 0.5))
            end = min(SS, int(crossings[i + 1] + 0.5))
            for x in range(start, end):
                offset = row + x * 4
                buffer[offset:offset + 4] = packed


def downsample(buffer):
    """Box filter over SCALE x SCALE blocks, averaging premultiplied colour so that edge pixels
    against the transparent background do not pick up a dark fringe."""
    out = bytearray(SIZE * SIZE * 4)
    area = SCALE * SCALE
    for y in range(SIZE):
        for x in range(SIZE):
            r = g = b = a = 0
            for dy in range(SCALE):
                row = ((y * SCALE + dy) * SS + x * SCALE) * 4
                for dx in range(SCALE):
                    offset = row + dx * 4
                    alpha = buffer[offset + 3]
                    if alpha:
                        r += buffer[offset]
                        g += buffer[offset + 1]
                        b += buffer[offset + 2]
                        a += alpha
            target = (y * SIZE + x) * 4
            if a == 0:
                continue
            covered = a / 255
            out[target] = min(255, round(r / covered))
            out[target + 1] = min(255, round(g / covered))
            out[target + 2] = min(255, round(b / covered))
            out[target + 3] = round(a / area)
    return out


def write_png(path, pixels):
    raw = bytearray()
    for y in range(SIZE):
        raw.append(0)  # filter type: none
        raw.extend(pixels[y * SIZE * 4:(y + 1) * SIZE * 4])

    def chunk(kind, payload):
        return (struct.pack(">I", len(payload)) + kind + payload
                + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))

    header = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0)
    with open(path, "wb") as handle:
        handle.write(b"\x89PNG\r\n\x1a\n")
        handle.write(chunk(b"IHDR", header))
        handle.write(chunk(b"IDAT", zlib.compress(bytes(raw), 9)))
        handle.write(chunk(b"IEND", b""))


def main():
    buffer = blank()
    fill_rounded_rect(buffer, CORNER_RADIUS, BACKGROUND)
    for points, colour in POLYGONS:
        fill_polygon(buffer, points, colour)
    here = os.path.dirname(os.path.abspath(__file__))
    target = os.path.join(here, os.pardir, "extension", "icon.png")
    write_png(target, downsample(buffer))
    print("wrote", os.path.normpath(target))


if __name__ == "__main__":
    main()
