#!/usr/bin/env python3
"""Checks a traced mascot against its source picture and shows how it recolours.

    python scripts/brand/compare.py SOURCE.png TRACED.svg OUT_DIR

Writes into OUT_DIR:
    compare.png   source | SVG at the same size | where they differ (red) | a 4x close-up of both
    themes.png    the SVG in the six factory themes and in a few colourings derived from one accent
Prints how many pixels differ noticeably. Needs: pillow, numpy, playwright (with Chrome or Edge).
"""
import re
import sys
from pathlib import Path

import numpy as np
from PIL import Image
from playwright.sync_api import sync_playwright

PAGE = '#F8F3F1'
THEMES = {
    'midnight': None,  # the SVG's own colours
    'rose': ('#FFC2D1', '#FFE6EC', '#FF6F91', '#7A1F3D'),
    'amber': ('#FFD98A', '#FFF1CF', '#F5A33B', '#6B4210'),
    'emerald': ('#A8F0CF', '#E3FBF0', '#2FC486', '#0E4D36'),
    'ocean': ('#BFDFFF', '#E8F3FF', '#4DA3FF', '#123A66'),
    'graphite': ('#3A3A3F', '#5A5A60', '#E6E6E6', '#141416'),
}
# What a theme with nothing but an accent gets: the same rule the app's stylesheet will use.
AUTO = ('color-mix(in oklab, {a} 38%, white)', 'color-mix(in oklab, {a} 14%, white)', '{a}', 'color-mix(in oklab, {a} 45%, black)')
ACCENTS = {'закат #FF7A3D': '#FF7A3D', 'лайм #B6E93B': '#B6E93B', 'бирюза #19C6C6': '#19C6C6', 'серый #9A9A9A': '#9A9A9A'}


def shot(browser, html, width, height, path):
    page = browser.new_page(viewport={'width': width, 'height': height})
    page.set_content(html)
    page.screenshot(path=str(path))
    page.close()


def main():
    source, traced, out = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    out.mkdir(parents=True, exist_ok=True)
    picture = Image.open(source).convert('RGB')
    width, height = picture.size
    svg = traced.read_text(encoding='utf-8')
    full = re.sub(r'viewBox="[^"]+"', 'viewBox="0 0 %d %d" width="%d" height="%d"' % (width, height, width, height), svg, count=1)

    def card(label, colours, dark, more='', picture=None):
        style = '--mascot-body:%s;--mascot-belly:%s;--mascot-gills:%s;--mascot-line:%s' % tuple(colours) if colours else ''
        return ('<figure style="background:%s;color:%s"><div class="%s" style="%s">%s</div><figcaption>%s</figcaption></figure>'
                % ('#101010' if dark else '#FBF8F5', '#EDEDED' if dark else '#2B2433', more, style, picture or svg, label))

    cards = ''.join(card(name, colours, True) for name, colours in THEMES.items())
    cards += ''.join(card('авто: ' + name, [part.format(a=accent) for part in AUTO], index % 2 == 0)
                     for index, (name, accent) in enumerate(ACCENTS.items()))
    # What the separate parts allow: gill fronds lit one by one, eyes that blink.
    cards += card('работают 2 агента', None, True, 'lit2') + card('работают 6 агентов', None, True, 'lit6') + card('моргает', None, True, 'blink')
    sizes = ''.join('<span style="display:inline-block;width:%dpx;margin:0 10px;vertical-align:bottom">%s</span>' % (side, svg) for side in (24, 32, 48, 72, 120))
    cards += card('24 · 32 · 48 · 72 · 120 px', None, True, 'sizes', sizes) + card('то же на светлом', None, False, 'sizes', sizes)
    themes = ('<style>body{margin:0;background:#101010;display:grid;grid-template-columns:repeat(5,1fr);font:600 20px Segoe UI,sans-serif}'
              'figure{margin:0;padding:18px 18px 12px;text-align:center}svg{width:100%%;height:auto;display:block}figcaption{padding-top:8px}'
              '.lit2 [data-gill=l1],.lit2 [data-gill=r1],.lit6 .axiko-gill{fill:#F5B731}'
              '.blink .axiko-eye{transform-box:fill-box;transform-origin:center;transform:scaleY(.12)}'
              '.sizes{display:flex;align-items:flex-end;justify-content:center;height:100%%;min-height:250px}.sizes+figcaption{padding-top:8px}'
              'figure:nth-last-child(-n+2){grid-column:span 2}</style>%s' % cards)

    with sync_playwright() as play:
        try:
            browser = play.chromium.launch()
        except Exception:
            browser = play.chromium.launch(channel='msedge')
        raw = out / 'svg-render.png'
        shot(browser, '<body style="margin:0;background:%s">%s' % (PAGE, full), width, height, raw)
        shot(browser, themes, 1500, 4 * 372, out / 'themes.png')
        browser.close()

    rendered = Image.open(raw).convert('RGB')
    a, b = np.asarray(picture).astype(int), np.asarray(rendered).astype(int)
    delta = np.abs(a - b).max(axis=2)
    differs = delta > 60
    print('pixels that differ noticeably: %.2f%% (mean difference %.1f of 255)' % (100 * differs.mean(), delta.mean()))

    diff = np.asarray(picture.convert('L').convert('RGB')).copy() // 2 + 127
    diff[differs] = (220, 30, 30)
    sheet = Image.new('RGB', (width * 4, height), 'white')
    sheet.paste(picture, (0, 0)); sheet.paste(rendered, (width, 0)); sheet.paste(Image.fromarray(diff.astype(np.uint8)), (width * 2, 0))
    # Close-ups of the busiest spots, source left and SVG right: the left gills, then the hand and mouth.
    for row, (left, top) in enumerate(((0.06, 0.18), (0.56, 0.40))):
        crop = (int(width * left), int(height * top), int(width * left) + width // 4, int(height * top) + height // 4)
        for column, image in enumerate((picture, rendered)):
            sheet.paste(image.crop(crop).resize((width // 2, height // 2), Image.LANCZOS), (width * 3 + column * (width // 2), row * (height // 2)))
    sheet.resize((width * 2, height // 2), Image.LANCZOS).save(out / 'compare.png')
    raw.unlink()


if __name__ == '__main__':
    main()
