#!/usr/bin/env python3
"""Dibuja los iconos de los botones tactiles (estilo de la placa oscura del juego: contorno negro,
sombreado de tres tonos, luz arriba).

Salida (PNG de 16x16, solo el dibujo, sin placa, centrado):
  - gm8emulator/src/art/<icono>.png y glyph_<letra>.png  -> los lee el motor (include_bytes!)
  - android/app/src/main/assets/icons/<icono>.png        -> los usa el editor de controles

La Poke Ball, la mochila y las letras Z X C V salen de dibujos ya hechos (tools/icon-src/).
Uso: python3 tools/gen-icons.py
"""
import os
import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_RUST = os.path.join(ROOT, 'src/OpenGMK/gm8emulator/src/art')
OUT_APP = os.path.join(ROOT, 'android/app/src/main/assets/icons')
SRC = os.path.join(ROOT, 'tools/icon-src')
SIZE = 16

PAL = {
 'R': ((221, 43, 35), (255, 118, 96), (136, 1, 42)),
 'Y': ((250, 215, 40), (255, 245, 150), (190, 140, 10)),
 'G': ((186, 180, 187), (248, 247, 249), (124, 118, 132)),
 'W': ((236, 236, 242), (255, 255, 255), (160, 160, 178)),
 'B': ((70, 110, 232), (140, 175, 255), (35, 55, 150)),
 'N': ((110, 205, 60), (185, 245, 125), (50, 125, 40)),
 'O': ((240, 130, 30), (255, 190, 90), (170, 70, 10)),
 'T': ((160, 100, 52), (205, 155, 95), (95, 52, 26)),
 'C': ((60, 200, 212), (150, 240, 246), (20, 120, 142)),
 'K': ((43, 40, 52), (80, 76, 96), (20, 18, 28)),
 'P': ((238, 222, 170), (255, 246, 208), (190, 165, 110)),
 'M': ((150, 80, 215), (200, 150, 250), (88, 40, 150)),
 'F': ((240, 95, 190), (255, 160, 225), (170, 40, 125)),
 'S': ((250, 200, 150), (255, 232, 195), (200, 140, 92)),
}

def parse(rows, w=12, h=12):
    rows = [r.ljust(w, '.')[:w] for r in rows]
    top = (h - len(rows)) // 2
    grid = [['.'] * w for _ in range(h)]
    for i, r in enumerate(rows):
        for j, c in enumerate(r):
            grid[top + i][j] = c
    return grid

ICONS = {}

def rot(grid, k):
    a = np.array(grid)
    return np.rot90(a, k).tolist()

ICONS['left'] = parse([
 '....W.......',
 '...WW.......',
 '..WWWWWWWW..',
 '.WWWWWWWWWW.',
 '.WWWWWWWWWW.',
 '..WWWWWWWW..',
 '...WW.......',
 '....W.......',
])
# la flecha de arriba la hago girando una cuadrada
sq = parse([
 '............',
 '....W.......',
 '...WW.......',
 '..WWWWWWWW..',
 '.WWWWWWWWWW.',
 '.WWWWWWWWWW.',
 '..WWWWWWWW..',
 '...WW.......',
 '....W.......',
 '............',
 '............',
 '............',
], 12, 12)
ICONS['left'] = sq
ICONS['up'] = rot(sq, -1)
ICONS['right'] = rot(sq, 2)
ICONS['down'] = rot(sq, 1)

ICONS['back'] = parse([
 '...WW.......',
 '..WWW.......',
 '.WWWWWWWWW..',
 'WWWWWWWWWWW.',
 '.WWWWWWWWWWW',
 '..WWW....WWW',
 '...WW....WWW',
 '.........WWW',
 '........WWW.',
 '...WWWWWWW..',
 '...WWWWW....',
])
ICONS['pause'] = parse([
 '..WWW..WWW..',
] * 10)
ICONS['star'] = parse([
 '.....YY.....',
 '.....YY.....',
 '....YYYY....',
 'YYYYYYYYYYYY',
 '.YYYYYYYYYY.',
 '..YYYYYYYY..',
 '..YYYYYYYY..',
 '.YYYYYYYYYY.',
 '.YYYY..YYYY.',
 '.YYY....YYY.',
])
ICONS['mega'] = parse([
 '...GGGGGG...',
 '..GGGGGGGG..',
 '.GGFFFCCCGG.',
 'GGFFFFCCCCGG',
 'GGFWFFCCCCGG',
 'GGFFFFCCCCGG',
 'GGFFFFCCCCGG',
 '.GGFFFCCCGG.',
 '..GGGGGGGG..',
 '...GGGGGG...',
])
ICONS['heart'] = parse([
 '.RRRR..RRRR.',
 'RRRRRR.RRRRR',
 'RRRRRRRRRRRR',
 'RRRRRRRRRRRR',
 'RRRRRRRRRRRR',
 '.RRRRRRRRRR.',
 '..RRRRRRRR..',
 '...RRRRRR...',
 '....RRRR....',
 '.....RR.....',
])
ICONS['plus'] = parse([
 '....NNNN....',
 '....NNNN....',
 '....NNNN....',
 'NNNNNNNNNNNN',
 'NNNNNNNNNNNN',
 'NNNNNNNNNNNN',
 'NNNNNNNNNNNN',
 '....NNNN....',
 '....NNNN....',
 '....NNNN....',
])
ICONS['menu'] = parse([
 'WWWWWWWWWWWW',
 'WWWWWWWWWWWW',
 '............',
 '............',
 'WWWWWWWWWWWW',
 'WWWWWWWWWWWW',
 '............',
 '............',
 'WWWWWWWWWWWW',
 'WWWWWWWWWWWW',
])
ICONS['check'] = parse([
 '..........NN',
 '.........NNN',
 '........NNN.',
 'N......NNN..',
 'NN....NNN...',
 'NNN..NNN....',
 '.NNNNNN.....',
 '..NNNN......',
 '...NN.......',
])
ICONS['act'] = parse([
 '....YYYY....',
 '....YYYY....',
 '....YYYY....',
 '....YYYY....',
 '....YYYY....',
 '....YYYY....',
 '.....YY.....',
 '............',
 '.....YY.....',
 '....YYYY....',
 '....YYYY....',
 '.....YY.....',
])
ICONS['run'] = parse([
 '....RRR.....',
 '....RRR.....',
 '....RRRRR...',
 'CC..RRWWRRR.',
 '....RWRRRRRR',
 'CCC.RRRRRRRR',
 '...RRRRRRRRR',
 '...WWWWWWWWW',
 '...GGGGGGGGG',
])
ICONS['hook'] = parse([
 '........TT..',
 '.......TT.W.',
 '......TT..W.',
 '.....TT...W.',
 '....TT....W.',
 '...TT....RRR',
 '..TT.....WWW',
 '.KK.........',
 'BBBBBBBBBBBB',
 '.BB.BB.BB.BB',
])
ICONS['bike'] = parse([
 '........YYY.',
 '...KKK...Y..',
 '....YYYYYY..',
 '....Y.YY.Y..',
 '.WWW..Y..YWW',
 'W...W.Y.W..W',
 'W..YYYYYY..W',
 'W...W...W..W',
 '.WWW.....WW.',
])
ICONS['team'] = parse([
 'KK........KK',
 'YYY......YYY',
 '.YYY....YYY.',
 '..YYYYYYYY..',
 '.YYYYYYYYYY.',
 '.YYKYYYYKYY.',
 '.YYKYYYYKYY.',
 '.RRYYKKYYRR.',
 '.RRYYYYYYRR.',
 '..YYYYYYYY..',
 '...YYYYYY...',
])
ICONS['map'] = parse([
 '...TPPPPT...',
 'PPPTPRRPTPPP',
 'PPPTRRRRTPPP',
 'PPPTRWWRTPPP',
 'PPPTRRRRTPPP',
 'PPPTPRRPTPPP',
 'PPPTPPRPTPPP',
 'PPP.PPPP.PPP',
])
ICONS['exit'] = parse([
 'WWWWWWW.....',
 'WW..........',
 'WW.....W....',
 'WW.....WW...',
 'WW.WWWWWWW..',
 'WW.WWWWWWWW.',
 'WW.WWWWWWW..',
 'WW.....WW...',
 'WW.....W....',
 'WW..........',
 'WWWWWWW.....',
])
ICONS['smile'] = parse([
 '...YYYYYY...',
 '..YYYYYYYY..',
 '.YYYYYYYYYY.',
 '.YYKYYYYKYY.',
 '.YYKYYYYKYY.',
 'YYYYYYYYYYYY',
 'YYKYYYYYYKYY',
 '.YYKKKKKKYY.',
 '.YYYKKKKYYY.',
 '..YYYYYYYY..',
 '...YYYYYY...',
])
ICONS['chat'] = parse([
 '.WWWWWWWWWW.',
 'WWWWWWWWWWWW',
 'WWWWWWWWWWWW',
 'WWWKWWKWWKWW',
 'WWWWWWWWWWWW',
 'WWWWWWWWWWWW',
 '.WWWWWWWWWW.',
 '...WWW......',
 '..WW........',
 '.W..........',
])
ICONS['clock'] = parse([
 '...BBBBBB...',
 '..BBWWWWBB..',
 '.BBWWKWWWWB.',
 'BBWWWKWWWWBB',
 'BWWWWKWWWWWB',
 'BWWWWKKKKWWB',
 'BWWWWWWWWWWB',
 'BBWWWWWWWWBB',
 '.BBWWWWWWBB.',
 '..BBWWWWBB..',
 '...BBBBBB...',
])
ICONS['magnifier'] = parse([
 '..CCCCC.....',
 '.CCWWWCC....',
 'CCWWWWWCC...',
 'CWWWWWWWC...',
 'CWWWWWWWC...',
 'CCWWWWWCC...',
 '.CCWWWCC....',
 '..CCCCCTT...',
 '.......TTT..',
 '........TTT.',
 '.........TT.',
])
ICONS['person'] = parse([
 'BBBBBBBBBBBB',
 'BBBBBBBBBBBB',
 'WWWWWWWWWWWW',
 'WSSWWGGGGGGW',
 'WSSWWWWWWWWW',
 'WBBWWGGGGWWW',
 'WBBWWWWWWWWW',
 'WWWWWWWWWWWW',
])
ICONS['bolt'] = parse([
 '.......YYYY.',
 '......YYYY..',
 '.....YYYY...',
 '....YYYY....',
 '...YYYYYYY..',
 '......YYY...',
 '.....YYY....',
 '....YYY.....',
 '....YY......',
 '...YY.......',
])
ICONS['box'] = parse([
 '....GGGG....',
 '.....GG.....',
 '.....GG.....',
 '...MMMMMM...',
 '..MMMMMMMM..',
 '.MMMMWWMMMM.',
 '.MMMWWWWMMM.',
 '.MMMMWWMMMM.',
 '.MMMMMMMMMM.',
 '..MMMMMMMM..',
])
ICONS['swap'] = parse([
 '........WW..',
 '........WWW.',
 'WWWWWWWWWWWW',
 'WWWWWWWWWWWW',
 '........WWW.',
 '........WW..',
 '..WW........',
 '.WWW........',
 'WWWWWWWWWWWW',
 'WWWWWWWWWWWW',
 '.WWW........',
 '..WW........',
])
ICONS['chart'] = parse([
 'RRRKYYYKBBB',
 'RRRKYYYKBBB',
 'KKKKKKKKKKK',
 'NNNKRRRKCCC',
 'NNNKRRRKCCC',
 'KKKKKKKKKKK',
 'YYYKBBBKNNN',
 'YYYKBBBKNNN',
])
ICONS['save'] = parse([
 'BBBBBBBBBBB.',
 'BBGGGGGGBBBB',
 'BBGGGGKGBBBB',
 'BBGGGGKGBBBB',
 'BBBBBBBBBBBB',
 'BWWWWWWWWWWB',
 'BWKKKKKKKKWB',
 'BWWWWWWWWWWB',
 'BWKKKKKKKKWB',
 'BWWWWWWWWWWB',
])
ICONS['load'] = parse([
 'YYYYY.......',
 'YYYYYYYYYYY.',
 'YYYYYYYYYYYY',
 'YYYYYNNYYYYY',
 'YYYYNNNNYYYY',
 'YYYNNNNNNYYY',
 'YYYYYNNYYYYY',
 'YYYYYNNYYYYY',
 'YYYYYYYYYYYY',
])
ICONS['forward'] = parse([
 'Y.....Y.....',
 'YY....YY....',
 'YYY...YYY...',
 'YYYY..YYYY..',
 'YYYYY.YYYYY.',
 'YYYY..YYYY..',
 'YYY...YYY...',
 'YY....YY....',
 'Y.....Y.....',
])
ICONS['enter'] = parse([
 '.........WW.',
 '.........WW.',
 '.........WW.',
 '.........WW.',
 '..W......WW.',
 '.WW......WW.',
 'WWWWWWWWWWW.',
 'WWWWWWWWWWW.',
 '.WW.........',
 '..W.........',
])
ICONS['space'] = parse([
 'WW........WW',
 'WW........WW',
 'WW........WW',
 'WW........WW',
 'WWWWWWWWWWWW',
 'WWWWWWWWWWWW',
])

GLYPHS = {
 'A': [[(0, 10), (4.5, 0), (9, 10)], [(2, 6.5), (7, 6.5)]],
 'B': [[(1, 0), (1, 10)], [(1, 0), (6, 0), (8, 2), (8, 3.5), (6, 5), (1, 5)], [(6, 5), (8, 6.5), (8, 8.5), (6, 10), (1, 10)]],
 'E': [[(8, 0), (1, 0), (1, 10), (8, 10)], [(1, 5), (6, 5)]],
 'F': [[(8, 0), (1, 0), (1, 10)], [(1, 5), (6, 5)]],
 'L': [[(1, 0), (1, 10), (8, 10)]],
 'M': [[(1, 10), (1, 0), (4.5, 6), (8, 0), (8, 10)]],
 'N': [[(1, 10), (1, 0), (8, 10), (8, 0)]],
 'P': [[(1, 10), (1, 0), (6, 0), (8, 2), (8, 4), (6, 6), (1, 6)]],
 'S': [[(8, 2), (6.5, 0), (3, 0), (1, 2), (1, 3.5), (3, 5), (6, 5), (8, 6.5), (8, 8), (6.5, 10), (2.5, 10), (1, 8)]],
 'T': [[(0, 0), (9, 0)], [(4.5, 0), (4.5, 10)]],
 '1': [[(2, 2), (5, 0), (5, 10)], [(2, 10), (8, 10)]],
 '2': [[(1, 2), (3, 0), (6, 0), (8, 2), (8, 4), (1, 10), (8, 10)]],
 '3': [[(1, 1), (3, 0), (6, 0), (8, 2), (8, 3.5), (6, 5), (3.5, 5)], [(6, 5), (8, 6.5), (8, 8), (6, 10), (3, 10), (1, 9)]],
 '5': [[(8, 0), (1.5, 0), (1, 5), (5, 4), (7.5, 5), (8, 7), (7, 9.5), (4, 10), (1, 9)]],
 '7': [[(0.5, 0), (8, 0), (3.5, 10)]],
 'O': [[(1, 2), (3, 0), (6, 0), (8, 2), (8, 8), (6, 10), (3, 10), (1, 8), (1, 2)]],
}
def thick_line(mask, p, q, t=2):
    (x0, y0), (x1, y1) = p, q
    n = int(max(abs(x1 - x0), abs(y1 - y0)) * 4) + 1
    for i in range(n + 1):
        x = x0 + (x1 - x0) * i / n
        y = y0 + (y1 - y0) * i / n
        for dx in range(t):
            for dy in range(t):
                xx, yy = int(round(x - t / 2 + 0.01)) + dx, int(round(y - t / 2 + 0.01)) + dy
                if 0 <= xx < mask.shape[1] and 0 <= yy < mask.shape[0]:
                    mask[yy, xx] = True


def outline(rgba):
    """Rodea de negro (4 vecinos) los pixeles opacos de una imagen RGBA."""
    h, w = rgba.shape[:2]
    out = np.zeros((h + 2, w + 2, 4), np.uint8)
    out[1:-1, 1:-1] = rgba
    op = out[:, :, 3] > 0
    ring = np.zeros_like(op)
    ring[1:, :] |= op[:-1, :]
    ring[:-1, :] |= op[1:, :]
    ring[:, 1:] |= op[:, :-1]
    ring[:, :-1] |= op[:, 1:]
    ring &= ~op
    out[ring] = (0, 0, 0, 255)
    return out

def render(grid):
    """El dibujo con sombreado en tres tonos y contorno negro."""
    h, w = len(grid), len(grid[0])
    img = np.zeros((h, w, 4), np.uint8)
    for y in range(h):
        for x in range(w):
            c = grid[y][x]
            if c == '.':
                continue
            base, light, dark = PAL[c]
            same = lambda xx, yy: 0 <= xx < w and 0 <= yy < h and grid[yy][xx] == c
            col = base
            if not same(x, y - 1) and same(x, y + 1):
                col = light
            elif not same(x, y + 1) and same(x, y - 1):
                col = dark
            img[y, x] = (*col, 255)
    return outline(img)

def glyph_sprite(ch):
    """Una letra o cifra: trazo de 2 px, sombra abajo a la derecha y contorno negro."""
    base, shadow = (130, 190, 255), (40, 70, 140)
    mask = np.zeros((14, 12), bool)
    for stroke in GLYPHS[ch]:
        for a, b in zip(stroke, stroke[1:]):
            thick_line(mask, (a[0] + 1, a[1] + 1), (b[0] + 1, b[1] + 1), 2)
    sh = np.zeros_like(mask)
    sh[1:, 1:] = mask[:-1, :-1]
    img = np.zeros((14, 12, 4), np.uint8)
    img[sh & ~mask] = (*shadow, 255)
    img[mask] = (*base, 255)
    return outline(img)

def fixed(name):
    """Dibujo ya hecho (tools/icon-src), con contorno si no lo trae."""
    img = np.array(Image.open(os.path.join(SRC, name + '.png')).convert('RGBA'))
    return img

def centered(img):
    """Recorta al dibujo y lo centra en un lienzo cuadrado de SIZE."""
    ys, xs = np.where(img[:, :, 3] > 0)
    img = img[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    h, w = img.shape[:2]
    assert max(h, w) <= SIZE, (h, w)
    canvas = np.zeros((SIZE, SIZE, 4), np.uint8)
    y0, x0 = (SIZE - h) // 2, (SIZE - w) // 2
    canvas[y0:y0 + h, x0:x0 + w] = img
    return canvas

def save(img, name):
    for folder in (OUT_RUST, OUT_APP) if not name.startswith('glyph_') else (OUT_RUST,):
        os.makedirs(folder, exist_ok=True)
        Image.fromarray(centered(img)).save(os.path.join(folder, name + '.png'))

def main():
    count = 0
    for name, grid in ICONS.items():
        save(render(grid), name)
        count += 1
    save(fixed('ball'), 'ball')
    save(fixed('bag'), 'bag')
    for ch in GLYPHS:
        save(glyph_sprite(ch), 'glyph_' + ch.lower())
        count += 1
    for ch in 'zxcv':
        save(outline(fixed('glyph_' + ch)), 'glyph_' + ch)
    print('listo:', count + 6, 'dibujos')

if __name__ == '__main__':
    main()
