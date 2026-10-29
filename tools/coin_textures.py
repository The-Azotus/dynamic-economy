"""Draws the placeholder coin and Bank Card textures (16x16 PNGs) into the mod's resources.

Each coin is a shaded disc in its metal's colour with an embossed emblem; the card is a dark blue card with a gold
chip. Run from the repository root:
    python tools/coin_textures.py
Replace the PNGs by hand to use real art; this script overwrites them.
"""
import os
import struct
import zlib

OUT = os.path.join('src', 'main', 'resources', 'assets', 'dynamic_economy', 'textures', 'item')

# name: (face colour, emblem colour, emblem rows). Emblems are 7x7, '#' marks a pixel, centred on the coin.
COINS = {
    'bit': ((0xC2, 0x7C, 0x4E), None, [
        '.......',
        '.......',
        '..###..',
        '..###..',
        '..###..',
        '.......',
        '.......']),
    'chip': ((0xB0, 0x8D, 0x3C), None, [
        '.......',
        '...#...',
        '..###..',
        '.#####.',
        '..###..',
        '...#...',
        '.......']),
    'stack': ((0xB4, 0xB4, 0xB8), None, [
        '.......',
        '.#####.',
        '.......',
        '.#####.',
        '.......',
        '.#####.',
        '.......']),
    'mark': ((0xD8, 0xE2, 0xEC), None, [
        '.......',
        '.#...#.',
        '.##.##.',
        '.#.#.#.',
        '.#...#.',
        '.#...#.',
        '.......']),
    'crown': ((0xE8, 0xB8, 0x30), None, [
        '.......',
        '.#.#.#.',
        '.#.#.#.',
        '.#####.',
        '.#####.',
        '.......',
        '.......']),
    'sun': ((0xF2, 0x94, 0x38), (0xFF, 0xE0, 0x70), [
        '...#...',
        '.#...#.',
        '..###..',
        '#.###.#',
        '..###..',
        '.#...#.',
        '...#...']),
    'divine': ((0xA8, 0x74, 0xD8), (0xF4, 0xE8, 0xFF), [
        '...#...',
        '...#...',
        '.#####.',
        '..###..',
        '..#.#..',
        '.#...#.',
        '.......']),
    'omega': ((0x3C, 0x36, 0x56), (0x5F, 0xE3, 0xE0), [
        '.......',
        '..###..',
        '.#...#.',
        '.#...#.',
        '..#.#..',
        '##...##',
        '.......']),
}


def scale(colour, factor):
    return tuple(max(0, min(255, round(c * factor))) for c in colour)


def draw(face, emblem_colour, emblem):
    size = 16
    centre = 7.5
    pixels = [[(0, 0, 0, 0)] * size for _ in range(size)]
    for y in range(size):
        for x in range(size):
            dx, dy = x - centre, y - centre
            d = (dx * dx + dy * dy) ** 0.5
            if d > 7.2:
                continue
            if d > 6.0:
                colour = scale(face, 0.55)  # rim
            elif d > 5.0:
                colour = scale(face, 0.8)  # bevel
            else:
                # Light from the top left.
                colour = scale(face, 1.0 + (-dx - dy) * 0.025)
            pixels[y][x] = colour + (255,)
    # Highlight on the upper-left rim.
    for x, y in [(4, 3), (3, 4), (5, 2), (2, 5)]:
        pixels[y][x] = scale(face, 1.35) + (255,)
    ink = emblem_colour or scale(face, 0.6)
    for row, line in enumerate(emblem):
        for col, ch in enumerate(line):
            if ch == '#':
                pixels[4 + row][4 + col] = ink + (255,)
    return pixels


def draw_card():
    blue, edge, light = (0x2A, 0x4A, 0x8C), (0x16, 0x26, 0x4A), (0x4A, 0x72, 0xC0)
    gold, gold_dark = (0xF2, 0xC9, 0x4C), (0xB0, 0x8A, 0x26)
    pixels = [[(0, 0, 0, 0)] * 16 for _ in range(16)]
    top, bottom, left, right = 3, 12, 1, 14
    for y in range(top, bottom + 1):
        for x in range(left, right + 1):
            corner = (x in (left, right)) and (y in (top, bottom))
            if corner:
                continue
            border = x in (left, right) or y in (top, bottom)
            pixels[y][x] = (edge if border else blue) + (255,)
    for x in range(left + 1, right):
        pixels[top + 1][x] = light + (255,)  # sheen along the top
    for x in range(left + 1, right):
        pixels[bottom - 2][x] = edge + (255,)  # magnetic stripe
    for y in range(6, 9):
        for x in range(3, 6):
            pixels[y][x] = (gold_dark if (x + y) % 2 else gold) + (255,)  # chip
    for x in range(8, 13, 2):
        pixels[7][x] = gold + (255,)  # number
    return pixels


def png(pixels):
    height, width = len(pixels), len(pixels[0])
    raw = b''.join(b'\x00' + b''.join(bytes(p) for p in row) for row in pixels)

    def chunk(kind, data):
        body = kind + data
        return struct.pack('>I', len(data)) + body + struct.pack('>I', zlib.crc32(body) & 0xFFFFFFFF)

    header = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', header) + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, (face, emblem_colour, emblem) in COINS.items():
        with open(os.path.join(OUT, name + '.png'), 'wb') as f:
            f.write(png(draw(face, emblem_colour, emblem)))
        print('wrote', name)
    with open(os.path.join(OUT, 'bank_card.png'), 'wb') as f:
        f.write(png(draw_card()))
    print('wrote bank_card')


if __name__ == '__main__':
    main()
