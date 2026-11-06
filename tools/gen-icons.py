#!/usr/bin/env python3
"""Rasteriza los iconos vectoriales de los botones tactiles.

Las fuentes son los SVG de ``tools/icon-svg/`` (solo el dibujo, sin placa ni fondo).
Se generan PNG transparentes de 160 x 160 para conservar el contorno y el suavizado.
El dibujo ocupa el lienzo original de 128 x 128 y los otros 16 px de cada lado son un
margen de seguridad transparente (el motor lo recorta antes de montar su atlas):

  - gm8emulator/src/art/<icono>.png y glyph_<letra>.png -> los integra el motor.
  - android/app/src/main/assets/icons/<icono>.png       -> los usa el editor.

Uso: python3 tools/gen-icons.py
Requiere CairoSVG (``pip install cairosvg``) o el ejecutable ``rsvg-convert``.
"""
from pathlib import Path
import shutil
import subprocess

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / 'tools' / 'icon-svg'
OUT_RUST = ROOT / 'src' / 'OpenGMK' / 'gm8emulator' / 'src' / 'art'
OUT_APP = ROOT / 'android' / 'app' / 'src' / 'main' / 'assets' / 'icons'
SIZE = 160


def rasterize(source: Path, target: Path) -> None:
    """Convierte un SVG a PNG sin cambiar su lienzo ni su transparencia."""
    target.parent.mkdir(parents=True, exist_ok=True)
    try:
        import cairosvg
    except ImportError:
        if shutil.which('rsvg-convert') is None:
            raise SystemExit('Instala CairoSVG (pip install cairosvg) o rsvg-convert.')
        subprocess.run(
            ['rsvg-convert', '-w', str(SIZE), '-h', str(SIZE), str(source), '-o', str(target)],
            check=True,
        )
    else:
        cairosvg.svg2png(url=str(source), write_to=str(target), output_width=SIZE, output_height=SIZE)
    with Image.open(target) as image:
        if image.size != (SIZE, SIZE) or image.mode != 'RGBA':
            raise RuntimeError(f'{target}: se esperaba un PNG RGBA de {SIZE}x{SIZE}, no {image.size} {image.mode}')
        if image.getchannel('A').getextrema()[1] == 0:
            raise RuntimeError(f'{source.name}: el dibujo ha salido vacio')


def main() -> None:
    sources = sorted(SRC.glob('*.svg'))
    if not sources:
        raise SystemExit(f'No hay SVG en {SRC}')
    for source in sources:
        rasterize(source, OUT_RUST / f'{source.stem}.png')
        # Las letras y cifras solo las usa el motor; el editor elige entre los iconos con nombre.
        if not source.stem.startswith('glyph_'):
            rasterize(source, OUT_APP / f'{source.stem}.png')
    app_count = sum(not source.stem.startswith('glyph_') for source in sources)
    print(f'listo: {len(sources)} SVG -> {len(sources)} PNG del motor y {app_count} del editor ({SIZE}x{SIZE})')


if __name__ == '__main__':
    main()
