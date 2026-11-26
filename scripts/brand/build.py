#!/usr/bin/env python3
"""Rebuilds the mascot's vector poses from the approved pictures and draws a sheet to check them.

    python scripts/brand/build.py [pose …]

For every assets/brand/mascot/poses/<pose>.webp (lossless; with its optional <pose>.json spec) writes
app/src/main/assets/factory/ui/mascot/<pose>.svg, then draws build/artifacts/brand/poses-sheet.png:
each pose as traced, on the dark and on a light background, with how far it is from its picture.
Needs: pillow, numpy, potracer, playwright (with Chrome or Edge).
"""
import io
import json
import re
import sys
from pathlib import Path

import numpy as np
from PIL import Image
from playwright.sync_api import sync_playwright

sys.path.insert(0, str(Path(__file__).parent))
import vectorize  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
POSES = ROOT / 'assets/brand/mascot/poses'
OUT = ROOT / 'app/src/main/assets/factory/ui/mascot'
SHEET = ROOT / 'build/artifacts/brand/poses-sheet.png'


def main():
    wanted = sys.argv[1:]
    OUT.mkdir(parents=True, exist_ok=True)
    SHEET.parent.mkdir(parents=True, exist_ok=True)
    # A pose is a picture, or a spec that names another picture as its source (and a part of it
    # to take: "crop" in fractions). A spec with "skip" keeps a picture that is only such a source.
    stems = sorted({path.stem for path in POSES.glob('*.webp')} | {path.stem for path in POSES.glob('*.json')})
    reports, pictures = {}, {}
    for name in stems:
        spec_file = POSES / (name + '.json')
        spec = json.loads(spec_file.read_text(encoding='utf-8-sig')) if spec_file.is_file() else {}
        if spec.get('skip') or (wanted and name not in wanted):
            continue
        picture = Image.open(POSES / spec.get('source', name + '.webp')).convert('RGB')
        if spec.get('crop'):
            left, top, right, bottom = spec['crop']
            picture = picture.crop((int(left * picture.width), int(top * picture.height), int(right * picture.width), int(bottom * picture.height)))
        svg, _, report = vectorize.build(picture, spec)
        (OUT / (name + '.svg')).write_text(svg, encoding='utf-8')
        reports[name] = dict(report, bytes=len(svg.encode('utf-8')))
        pictures[name] = picture
    names = list(reports)

    every = [path.stem for path in sorted(OUT.glob('*.svg'))]
    with sync_playwright() as play:
        try:
            browser = play.chromium.launch()
        except Exception:
            browser = play.chromium.launch(channel='msedge')
        # How far each traced pose is from its picture: both drawn at the picture's size.
        for name in names:
            picture = pictures[name]
            width, height = picture.size
            svg = (OUT / (name + '.svg')).read_text(encoding='utf-8')
            full = re.sub(r'viewBox="[^"]+"', 'viewBox="0 0 %d %d" width="%d" height="%d"' % (width, height, width, height), svg, count=1)
            page = browser.new_page(viewport={'width': width, 'height': height})
            page.set_content('<body style="margin:0;background:%s">%s' % (reports[name]['measured']['page'], full))
            rendered = Image.open(io.BytesIO(page.screenshot())).convert('RGB')
            page.close()
            delta = np.abs(np.asarray(picture).astype(int) - np.asarray(rendered).astype(int)).max(axis=2)
            reports[name]['differs'] = round(100 * float((delta > 60).mean()), 2)

        cards = ''.join(
            '<figure><div class="pair"><span class="dark">%s</span><span class="light">%s</span></div><figcaption>%s</figcaption></figure>'
            % ((OUT / (name + '.svg')).read_text(encoding='utf-8'), (OUT / (name + '.svg')).read_text(encoding='utf-8'), name)
            for name in every)
        html = ('<style>body{margin:0;background:#101010;color:#EDEDED;display:grid;grid-template-columns:repeat(5,1fr);font:600 18px Segoe UI,sans-serif}'
                'figure{margin:0;padding:10px 10px 14px;text-align:center}.pair{display:grid;grid-template-columns:1fr 1fr}'
                '.pair span{padding:10px;aspect-ratio:1;display:flex}.light{background:#FBF8F5}svg{width:100%;height:100%}figcaption{padding-top:6px}</style>' + cards)
        rows = -(-len(every) // 5)
        page = browser.new_page(viewport={'width': 1800, 'height': rows * 230})
        page.set_content(html)
        page.screenshot(path=str(SHEET), full_page=True)
        browser.close()

    for name, report in reports.items():
        print(name, json.dumps({key: report[key] for key in ('bytes', 'differs', 'gills', 'eyes', 'pink', 'white', 'extra')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
