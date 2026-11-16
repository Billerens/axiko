#!/usr/bin/env python3
"""Turns a flat sticker-style picture of the mascot into a layered SVG coloured by CSS variables.

    python scripts/brand/vectorize.py SOURCE.png OUT.svg [--spec SPEC.json] [--debug DIR]

The picture is split into flat colour layers, each layer is traced on its own (potrace) and the
SVG is put together by hand:

    sticker   the white border: everything that is not the page background
    line      the whole silhouette in the outline colour — fills lie on top, so no gaps show
    body      the body colour
    fin       pink shapes named so in the spec (the edge of the tail)
    gills     every other pink shape outside the body: a frond each, data-gill="l1"…"r3",
              numbered top to bottom on each side — so they can light up one by one
    mouth     pink inside a hole of the body (the tongue)
    eye       a hole of the body with a white highlight in it: a group of its own, so it can blink
    extras    more flat colours the pose uses (a cube, sparkles, pebbles): spec "extra"

Every fill is `var(--mascot-<name>, <the art's own colour>)`, so the file looks right by itself
and takes any colouring from CSS.

SPEC.json (all optional):
    { "parts": { "fin": [x0, y0, x1, y1], "tag": […] },     boxes in fractions of the picture
      "extra": { "amber": "#F5B731", "mint": "#5FB9A0" } }  more colours to look for

Needs: pillow, numpy, potracer.
"""
import argparse
import json
from pathlib import Path

import numpy as np
import potrace
from PIL import Image, ImageDraw, ImageFilter

# The colours of the approved art. Every pose gets the same defaults, whatever shade the
# generator happened to give its copy.
ART = {'line': '#44308A', 'body': '#C9B6FD', 'gills': '#F98FAC'}
DEFAULTS = dict(ART, sticker='#FFFFFF', mouth=ART['gills'], shine='#FFFFFF', tag='#FFFFFF')


def rgb(colour):
    return tuple(int(colour[index:index + 2], 16) for index in (1, 3, 5))


def flood(mask):
    """The part of a boolean mask connected to the picture's corners."""
    # A copy: an image made from an array shares its (read-only) memory, and the fill would not take.
    image = Image.fromarray((mask * 255).astype(np.uint8), 'L').copy()
    width, height = image.size
    for corner in ((0, 0), (width - 1, 0), (0, height - 1), (width - 1, height - 1)):
        if image.getpixel(corner) == 255:
            ImageDraw.floodfill(image, corner, 128)
    return np.asarray(image) == 128


def opened(mask, size):
    """Drops what is thinner than `size` pixels: the rims that anti-aliasing leaves between colours."""
    image = Image.fromarray((mask * 255).astype(np.uint8), 'L')
    image = image.filter(ImageFilter.MinFilter(size)).filter(ImageFilter.MaxFilter(size))
    return np.asarray(image) > 127


def split(picture, extra):
    """The picture as boolean layers: sticker, line, body, pink, white and one per extra colour."""
    pixels = np.asarray(picture.convert('RGB')).astype(np.int32)
    corners = np.array([pixels[0, 0], pixels[0, -1], pixels[-1, 0], pixels[-1, -1]])
    palette = {'page': tuple(np.median(corners, axis=0)), 'white': (255, 255, 255),
               'line': rgb(ART['line']), 'body': rgb(ART['body']), 'pink': rgb(ART['gills'])}
    palette.update({name: rgb(colour) for name, colour in extra.items()})
    names = list(palette)

    def classify():
        colours = np.array([palette[name] for name in names])
        distance = ((pixels[:, :, None, :] - colours[None, None, :, :]) ** 2).sum(axis=3)
        return distance.argmin(axis=2)

    # The generator's shades drift a little from picture to picture: after a first pass each
    # colour is replaced by what the picture really has there, then the split is done again.
    nearest = classify()
    for index, name in enumerate(names):
        core = opened(nearest == index, 7)
        if name != 'white' and core.sum() > 200:
            palette[name] = tuple(np.median(pixels[core], axis=0))
    nearest = classify()
    layer = {name: nearest == index for index, name in enumerate(names)}

    page = flood(layer['page'])
    outside = flood(layer['page'] | layer['white'])     # the page and the white border
    silhouette = ~outside
    masks = {'sticker': ~page, 'line': silhouette, 'white': opened(layer['white'] & silhouette, 3)}
    for name in ['body', 'pink'] + list(extra):
        masks[name] = opened(layer[name] & silhouette, 5)
    measured = {name: '#%02X%02X%02X' % tuple(int(value) for value in palette[name]) for name in names}
    return masks, measured


class Shape:
    """One closed curve of a traced layer: its path, its box and its signed area."""

    def __init__(self, curve):
        def n(value):
            return ('%.1f' % value).rstrip('0').rstrip('.')

        def p(point):
            return n(point.x) + ' ' + n(point.y)

        d, points = ['M' + p(curve.start_point)], [curve.start_point]
        for segment in curve.segments:
            if segment.is_corner:
                d.append('L' + p(segment.c) + ' L' + p(segment.end_point))
                points += [segment.c, segment.end_point]
            else:
                d.append('C' + p(segment.c1) + ' ' + p(segment.c2) + ' ' + p(segment.end_point))
                points += [segment.c1, segment.c2, segment.end_point]
        self.d = ' '.join(d) + 'Z'
        xs, ys = [point.x for point in points], [point.y for point in points]
        self.box = (min(xs), min(ys), max(xs), max(ys))
        ends = [curve.start_point] + [segment.end_point for segment in curve.segments]
        self.area = sum(a.x * b.y - b.x * a.y for a, b in zip(ends, ends[1:] + ends[:1])) / 2

    @property
    def middle(self):
        return (self.box[0] + self.box[2]) / 2, (self.box[1] + self.box[3]) / 2

    @property
    def size(self):
        return (self.box[2] - self.box[0]) * (self.box[3] - self.box[1])

    def within(self, other, slack=0):
        return (self.box[0] >= other.box[0] - slack and self.box[1] >= other.box[1] - slack
                and self.box[2] <= other.box[2] + slack and self.box[3] <= other.box[3] + slack)


def trace(mask):
    """One mask -> its closed curves, outer edges and holes alike."""
    # Potrace fits few, long curves to a hard bitmap — the result stays small and smooth.
    # This port fills where the array is False, hence the inversion.
    curves = potrace.Bitmap(~mask).trace(turdsize=30, alphamax=1.0, opticurve=True, opttolerance=0.4)
    return [Shape(curve) for curve in curves]


def build(picture, spec):
    width, height = picture.size
    parts, extra = spec.get('parts', {}), spec.get('extra', {})
    masks, measured = split(picture, extra)
    traced = {name: trace(mask) for name, mask in masks.items()}
    colours = dict(DEFAULTS, **extra)

    def named(shape):
        x, y = shape.middle[0] / width, shape.middle[1] / height
        return next((name for name, (x0, y0, x1, y1) in parts.items() if x0 <= x <= x1 and y0 <= y <= y1), None)

    def path(name, variable, shapes, more=''):
        # Holes are subpaths of the same element, so the fill rule is even-odd.
        return '<path class="axiko-%s"%s fill="var(--mascot-%s, %s)" fill-rule="evenodd" d="%s"/>' % (
            name, more, variable, colours[variable], ' '.join(shape.d for shape in shapes))

    # The body: its outer edges, and its holes. A hole with a white highlight in it is an eye.
    body = traced['body']
    outer = max(body, key=lambda shape: abs(shape.area)).area > 0 if body else True
    holes = [shape for shape in body if (shape.area > 0) != outer]
    eyes = []
    for hole in holes:
        shine = [shape for shape in traced['white'] if shape.within(hole)]
        if shine and hole.size < width * height * 0.03:
            eyes.append((hole, shine))
    eye_holes = [eye for eye, _ in eyes]
    eye_shine = [shape for _, shine in eyes for shape in shine]
    body = [shape for shape in body if shape not in eye_holes]

    # White inside the silhouette that is no eye highlight: a name tag, or a stray highlight.
    white = {}
    for shape in traced['white']:
        if shape not in eye_shine:
            white.setdefault(named(shape) or 'shine', []).append(shape)

    # Pink: named boxes first (the tail fin), then the tongue (inside a hole of the body),
    # the rest are gill fronds; a pink curve inside a frond is that frond's own hole.
    pink, fronds = {}, []
    for shape in sorted(traced['pink'], key=lambda item: -item.size):
        name = named(shape)
        # The tongue reaches the edge of the mouth, so its box may stick out of the hole's by a few pixels.
        if not name and any(shape.within(hole, 10) for hole in holes if hole not in eye_holes):
            name = 'mouth'
        if name:
            pink.setdefault(name, []).append(shape)
            continue
        owner = next((frond for frond in fronds if shape.within(frond[0])), None)
        owner.append(shape) if owner else fronds.append([shape])
    x0, y0, x1, y1 = (min(shape.box[index] for shape in traced['sticker']) if index < 2
                      else max(shape.box[index] for shape in traced['sticker']) for index in range(4))
    # Left and right of the head: the middle between the eyes when there are two, else of the picture.
    centre = sum(eye.middle[0] for eye in eye_holes) / len(eye_holes) if len(eye_holes) == 2 else (x0 + x1) / 2
    sides = {'l': [], 'r': []}
    for frond in fronds:
        sides['l' if frond[0].middle[0] < centre else 'r'].append(frond)

    pad = 6
    out = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="%.0f %.0f %.0f %.0f" class="axiko" role="img" aria-label="Axiko">'
           % (x0 - pad, y0 - pad, x1 - x0 + 2 * pad, y1 - y0 + 2 * pad)]
    out.append(path('sticker', 'sticker', traced['sticker']))
    out.append(path('line', 'line', traced['line']))
    out.append(path('body', 'body', body))
    for name in extra:
        if traced[name]:
            out.append(path(name, name, traced[name]))
    for name in sorted(pink):
        if name != 'mouth':
            out.append(path(name, 'gills', pink[name]))
    out.append('<g class="axiko-gills">')
    for side in ('l', 'r'):
        for index, frond in enumerate(sorted(sides[side], key=lambda item: item[0].middle[1]), 1):
            out.append(path('gill', 'gills', frond, ' data-gill="%s%d"' % (side, index)))
    out.append('</g>')
    if 'mouth' in pink:
        out.append(path('mouth', 'mouth', pink['mouth']))
    for name in sorted(white):
        out.append(path(name, 'tag' if name == 'tag' else 'shine', white[name]))
    for index, (eye, shine) in enumerate(sorted(eyes, key=lambda item: item[0].middle[0])):
        out.append('<g class="axiko-eye" data-eye="%s">' % ('left', 'right')[min(index, 1)])
        out.append(path('pupil', 'line', [eye]))
        out.append(path('shine', 'shine', shine))
        out.append('</g>')
    out.append('</svg>')
    report = {
        'gills': {side: len(items) for side, items in sides.items()}, 'eyes': len(eyes),
        'pink': {name: len(shapes) for name, shapes in pink.items()},
        'white': {name: len(shapes) for name, shapes in white.items()},
        'extra': {name: len(traced[name]) for name in extra}, 'measured': measured,
    }
    return '\n'.join(out) + '\n', masks, report


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('source')
    parser.add_argument('out')
    parser.add_argument('--spec', help='JSON: named boxes and extra colours of this pose')
    parser.add_argument('--debug', help='folder for the layer masks')
    args = parser.parse_args()

    spec = json.loads(Path(args.spec).read_text(encoding='utf-8-sig')) if args.spec and Path(args.spec).is_file() else {}
    svg, masks, report = build(Image.open(args.source), spec)
    Path(args.out).write_text(svg, encoding='utf-8')
    if args.debug:
        Path(args.debug).mkdir(parents=True, exist_ok=True)
        for name, mask in masks.items():
            Image.fromarray((mask * 255).astype(np.uint8), 'L').save(Path(args.debug, name + '.png'))
    print(json.dumps({'out': Path(args.out).name, 'bytes': len(svg.encode('utf-8')), **report}, ensure_ascii=False))


if __name__ == '__main__':
    main()
