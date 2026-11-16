#!/usr/bin/env python3
"""Makes the launcher icon's layers from the mascot's head (ui/mascot/head.svg).

    python scripts/brand/icon.py

Writes app/src/main/res/drawable/ic_launcher_fg.xml (the head in the art's own colours),
ic_launcher_<coat>_fg.xml for every other coat in COATS, and ic_launcher_mono.xml (the same drawing as lines only — Android 13+ tints it for themed icons).
The icon is part of the APK: unlike the mascot in the app, it does not follow themes or
ui/mascot/mascot.json.
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HEAD = ROOT / 'app/src/main/assets/factory/ui/mascot/head.svg'
RES = ROOT / 'app/src/main/res/drawable'
CANVAS = 108   # an adaptive icon's layer, dp
WIDTH = 64     # the head with its gills: inside the 66 dp circle every launcher keeps

HEADER = ('<?xml version="1.0" encoding="utf-8"?>\n<!-- Made by scripts/brand/icon.py from ui/mascot/head.svg. -->\n'
          '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
          '    android:width="108dp"\n    android:height="108dp"\n    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
          '    <group\n        android:scaleX="%(scale).5f"\n        android:scaleY="%(scale).5f"\n'
          '        android:translateX="%(dx).3f"\n        android:translateY="%(dy).3f">\n')
# Other coats of the head, by the drawing's part (class `axiko-<part>`): the natural pink axolotl.
COATS = {
    'pink': {'body': '#F6B8C8', 'gill': '#8B7CFF', 'mouth': '#E46F92', 'line': '#4A2A6A', 'pupil': '#4A2A6A'},
}
PATH = '        <path\n            android:fillColor="%s"\n            android:fillType="evenOdd"\n            android:pathData="%s" />\n'


def main():
    svg = HEAD.read_text(encoding='utf-8')
    x, y, width, height = (float(value) for value in re.search(r'viewBox="([^"]+)"', svg).group(1).split())
    scale = WIDTH / width
    place = {'scale': scale, 'dx': (CANVAS - width * scale) / 2 - x * scale, 'dy': (CANVAS - height * scale) / 2 - y * scale}
    shapes = re.findall(r'<path class="axiko-([\w-]+)"[^>]*?fill="var\(--mascot-[\w-]+, (#[0-9A-Fa-f]{6})\)"[^>]*? d="([^"]+)"', svg)

    colour = HEADER % place + ''.join(PATH % (fill, d) for _, fill, d in shapes) + '    </group>\n</vector>\n'
    (RES / 'ic_launcher_fg.xml').write_text(colour, encoding='utf-8')
    # The same head in other coats: one more foreground layer for each (the launcher entries
    # that use them are in the manifest, the list of looks is in core/Launcher.kt).
    for name, coat in COATS.items():
        layer = HEADER % place + ''.join(PATH % (coat.get(part, fill), d) for part, fill, d in shapes) + '    </group>\n</vector>\n'
        (RES / ('ic_launcher_%s_fg.xml' % name)).write_text(layer, encoding='utf-8')

    # Lines only: the silhouette with every fill cut out of it (even-odd). The pupils lie inside the
    # body, so adding them turns them solid again; their highlights are cut back out. The mouth
    # is a hole of the body already and stays solid.
    cut = ' '.join(d for name, _, d in shapes if name in ('line', 'body', 'gill', 'fin', 'pupil', 'shine'))
    mono = HEADER % place + PATH % ('#FFFFFF', cut) + '    </group>\n</vector>\n'
    (RES / 'ic_launcher_mono.xml').write_text(mono, encoding='utf-8')
    print('layers:', [name for name, _, _ in shapes], 'scale %.4f' % scale)


if __name__ == '__main__':
    main()
