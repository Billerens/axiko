#!/usr/bin/env python3
"""What a package shows in the store besides its code: icon, tags, description, screenshots, changes.

Everything here is read from the package's own folder and published in place: a picture is named
in the catalog by its path in the repository, its size and its SHA-256, and the client fetches it
by that. The `store/` folder of a package holds this material and never goes into its archive, so
editing a listing does not need a new version of the package.

The listing is written in English, the required language, and may be given again in others: the
fields of `store.json`, `store/description.md` and `store/changelog.md` hold the English text, and
what another language says goes into `locales` of `store.json` and into `store/description.<code>.md`
and `store/changelog.<code>.md`. A language may give some of the texts only: the rest is English.
"""
from __future__ import annotations

import hashlib
import re
import struct
import unicodedata
from pathlib import Path

MAX_ICON_BYTES = 131_072
MAX_SCREENSHOT_BYTES = 1_048_576
MAX_SCREENSHOTS = 8
MAX_CHANGES = 20
MAX_DESCRIPTION = 20_000
MAX_NOTES = 4_000
MAX_TAGS = 12
MAX_TITLE = 120
MAX_SUMMARY = 600
MAX_CAPTION = 200
MAX_LOCALES = 16
BASE_LANGUAGE = 'en'
LANGUAGE_CODE = re.compile(r'^[a-z]{2,3}(?:-[A-Za-z]{2,4})?$')
ICON_TYPES = ('image/svg+xml', 'image/png', 'image/webp', 'image/jpeg')
SCREENSHOT_TYPES = ('image/png', 'image/webp', 'image/jpeg')
PICTURE_SUFFIXES = ('.svg', '.png', '.webp', '.jpg', '.jpeg')
SEMVER = (r'(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)'
          r'(?:-(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*)?'
          r'(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?')
HEADING = re.compile(r'^##\s+v?(' + SEMVER + r')(?:\s*(?:—|–|-|\()\s*(\d{4}-\d{2}-\d{2})\)?)?\s*$')
UNSAFE_CONTROL = re.compile(r'[\x00-\x1f\x7f]')
UNSAFE = re.compile(r'[\x00-\x1f\x7f<>:"|?*]')
SVG_START = re.compile(r'^﻿?\s*(?:<\?xml[^>]*>\s*)?(?:<!--[\s\S]*?-->\s*)*<svg[\s>]')
SVG_ACTIVE = re.compile(r'<\s*(?:script|foreignObject|iframe|object|embed|use\s[^>]*href\s*=\s*["\'](?!#))|\son[a-z]+\s*=|javascript:|<!ENTITY|<!DOCTYPE[^>]*\[', re.I)


class ListingError(ValueError):
    """What is wrong with a listing, in words its author can act on."""


def language_code(code, where: str) -> str:
    """A language a listing is given in. English is the base text and has no entry of its own."""
    if not isinstance(code, str) or not LANGUAGE_CODE.match(code):
        raise ListingError(f'{where}: "{code}" is not a language code such as "ru" or "pt-BR"')
    if code == BASE_LANGUAGE:
        raise ListingError(f'{where}: English is the base text, not a locale: leave "en" out and write English in the base fields')
    return code


def text_of(value, limit: int, where: str) -> str:
    if not isinstance(value, str) or not 0 < len(value.strip()) <= limit:
        raise ListingError(f'{where}: a text of 1 to {limit} characters')
    return value.strip()


def tag_ok(tag) -> bool:
    return isinstance(tag, str) and 0 < len(tag) <= 32 and tag == tag.strip() and not UNSAFE_CONTROL.search(tag)


def locale_texts(locales, tags: list):
    """`locales` of store.json: {code: {title, summary, tags}} with only the texts a language has.

    A tag is its English text: that is what the store counts and filters by. A language gives the
    words to show for the same tags as a list of the same length, in the same order.
    """
    if not isinstance(locales, dict) or len(locales) > MAX_LOCALES:
        raise ListingError(f'store.json locales: an object of up to {MAX_LOCALES} languages')
    found = {}
    for code, given in locales.items():
        language_code(code, 'store.json locales')
        where = f'store.json locales.{code}'
        if not isinstance(given, dict) or not given or set(given) - {'title', 'summary', 'tags'}:
            raise ListingError(f'{where}: an object with title, summary and tags, at least one of them')
        shown = {}
        if 'title' in given:
            shown['title'] = text_of(given['title'], MAX_TITLE, where + '.title')
        if 'summary' in given:
            shown['summary'] = text_of(given['summary'], MAX_SUMMARY, where + '.summary')
        if 'tags' in given:
            words = given['tags']
            if not isinstance(words, list) or len(words) != len(tags) or not tags:
                raise ListingError(f'{where}.tags: one text for every tag of store.json ({len(tags)}), in the same order')
            if not all(tag_ok(word) for word in words) or len(set(words)) != len(words):
                raise ListingError(f'{where}.tags: different texts of 1 to 32 characters, without spaces at the ends')
            shown['tags'] = words
        found[code] = shown
    return found


def picture_type(raw: bytes):
    """What a picture really is, by its bytes. An SVG is a drawing only: no scripts, handlers or embedded pages."""
    if raw.startswith(b'\x89PNG\r\n\x1a\n'):
        return 'image/png'
    if raw.startswith(b'\xff\xd8\xff'):
        return 'image/jpeg'
    if raw[:4] == b'RIFF' and raw[8:12] == b'WEBP':
        return 'image/webp'
    try:
        text = raw.decode('utf-8', errors='strict')
    except UnicodeError:
        return None
    if '\x00' in text or not SVG_START.search(text) or SVG_ACTIVE.search(text):
        return None
    return 'image/svg+xml'


def picture_size(raw: bytes, kind: str):
    """Width and height in pixels of a raster picture, read from its header."""
    try:
        if kind == 'image/png':
            return struct.unpack('>II', raw[16:24])
        if kind == 'image/webp':
            chunk = raw[12:16]
            if chunk == b'VP8X':
                return 1 + int.from_bytes(raw[24:27], 'little'), 1 + int.from_bytes(raw[27:30], 'little')
            if chunk == b'VP8L':
                bits = int.from_bytes(raw[21:25], 'little')
                return (bits & 0x3fff) + 1, ((bits >> 14) & 0x3fff) + 1
            if chunk == b'VP8 ':
                width, height = struct.unpack('<HH', raw[26:30])
                return width & 0x3fff, height & 0x3fff
        if kind == 'image/jpeg':
            position = 2
            while position + 9 < len(raw):
                if raw[position] != 0xff:
                    position += 1
                    continue
                marker = raw[position + 1]
                if marker in (0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf):
                    height, width = struct.unpack('>HH', raw[position + 5:position + 9])
                    return width, height
                if marker in (0xd8, 0x01) or 0xd0 <= marker <= 0xd7:
                    position += 2
                else:
                    position += 2 + struct.unpack('>H', raw[position + 2:position + 4])[0]
    except (struct.error, IndexError):
        pass
    raise ListingError('cannot read the size of the picture from its header')


def check_path(path: str):
    if unicodedata.normalize('NFC', path) != path or '\\' in path or path.startswith('/'):
        raise ListingError(f'{path}: a listing file is named by a relative path with forward slashes, in NFC')
    for part in path.split('/'):
        if not part or part.startswith('.') or part.endswith(('.', ' ')) or UNSAFE.search(part):
            raise ListingError(f'{path}: empty, hidden or unsafe path component')


def picture(folder: Path, relative: str, limit: int, types):
    """A picture of the package as the catalog names it: its path in the repository, size, hash and real type."""
    check_path(relative)
    source = folder / relative
    if source.is_symlink() or not source.is_file():
        raise ListingError(f'{relative}: no such file in the package folder')
    raw = source.read_bytes()
    if not 0 < len(raw) <= limit:
        raise ListingError(f'{relative}: {len(raw)} bytes; the limit is {limit}')
    kind = picture_type(raw)
    if kind not in types:
        raise ListingError(f'{relative}: must be one of {", ".join(types)} by its content'
                           + (' (an SVG may hold a drawing only, no scripts or handlers)' if relative.lower().endswith('.svg') else ''))
    return {'path': folder.name + '/' + relative, 'sha256': hashlib.sha256(raw).hexdigest(), 'size': len(raw), 'type': kind}, raw


def changelog(text: str):
    """Sections `## 1.2.3 — 2026-11-17` of a changelog, newest first as written."""
    changes, current = [], None
    for line in text.replace('\r', '').split('\n'):
        heading = HEADING.match(line)
        if heading:
            current = {'version': heading.group(1), 'notes': []}
            if heading.group(2):
                current['date'] = heading.group(2)
            changes.append(current)
        elif line.startswith('## '):
            raise ListingError(f'changelog heading is not "## <version> — <YYYY-MM-DD>": {line.strip()[:60]}')
        elif current is not None:
            current['notes'].append(line)
    seen = set()
    for change in changes:
        change['notes'] = '\n'.join(change['notes']).strip()
        if not change['notes'] or len(change['notes']) > MAX_NOTES:
            raise ListingError(f'changelog section {change["version"]} must have 1 to {MAX_NOTES} characters of notes')
        if change['version'] in seen:
            raise ListingError(f'changelog names version {change["version"]} twice')
        seen.add(change['version'])
    return changes[:MAX_CHANGES]


def localized_files(folder: Path, stem: str) -> dict:
    """`store/<stem>.<code>.md` of a package: the same text in another language."""
    found = {}
    directory = folder / 'store'
    for path in sorted(directory.glob(stem + '.*.md')) if directory.is_dir() else []:
        code = language_code(path.name[len(stem) + 1:-3], 'store/' + path.name)
        if path.is_symlink() or not path.is_file():
            raise ListingError(f'store/{path.name}: not a file of the package folder')
        found[code] = path
    return found


def collect(folder: Path, store: dict, descriptor: dict, kind: str, version: str):
    """The card's listing fields, the details of the package (or None) and notes for the author."""
    card, details, notes = {}, {}, []
    locales, captions, texts = {}, {}, {}
    summary = store.get('summary') or descriptor.get('description') or descriptor.get('about') or store.get('title') or store.get('id')
    card['summary'] = str(summary).strip()[:600]
    tags = store.get('tags', [])
    if not isinstance(tags, list) or len(tags) > MAX_TAGS or len(set(tags)) != len(tags) or any(
            not isinstance(tag, str) or not 0 < len(tag) <= 32 or tag != tag.lower() or tag != tag.strip() for tag in tags):
        raise ListingError(f'store.json tags: up to {MAX_TAGS} different lowercase strings of 1 to 32 characters')
    if tags:
        card['tags'] = tags
    if store.get('locales') is not None:
        locales = locale_texts(store['locales'], tags)
    icon = store.get('icon')
    if icon is None:
        candidates = ['store/icon' + suffix for suffix in PICTURE_SUFFIXES] + ['icon.svg']
        icon = next((name for name in candidates if (folder / name).is_file()), None)
    if icon is not None:
        if not isinstance(icon, str):
            raise ListingError('store.json icon must be a path inside the package folder')
        card['icon'], _ = picture(folder, icon, MAX_ICON_BYTES, ICON_TYPES)
    else:
        notes.append('no icon: the store shows the generic one')

    shots = store.get('screenshots')
    if shots is None:
        found = sorted((folder / 'store' / 'shots').glob('*')) if (folder / 'store' / 'shots').is_dir() else []
        shots = ['store/shots/' + path.name for path in found if path.suffix.lower() in PICTURE_SUFFIXES[1:]]
    if not isinstance(shots, list) or len(shots) > MAX_SCREENSHOTS:
        raise ListingError(f'store.json screenshots: a list of up to {MAX_SCREENSHOTS} files')
    listed = []
    for shot in shots:
        name, caption = (shot, None) if isinstance(shot, str) else (shot.get('file'), shot.get('caption')) if isinstance(shot, dict) else (None, None)
        if not isinstance(name, str) or caption is not None and (not isinstance(caption, str) or not 0 < len(caption) <= MAX_CAPTION):
            raise ListingError('store.json screenshots: each is a path or {"file": path, "caption": text up to 200 characters, "locales": {code: {"caption": text}}}')
        if isinstance(shot, dict) and set(shot) - {'file', 'caption', 'locales'}:
            raise ListingError(f'store.json screenshots: {name}: only file, caption and locales are known')
        said = shot.get('locales') if isinstance(shot, dict) else None
        if said is not None:
            if not isinstance(said, dict) or not caption:
                raise ListingError(f'store.json screenshots: {name}: a caption in another language needs the English caption first')
            for code, given in said.items():
                language_code(code, f'store.json screenshots {name} locales')
                if not isinstance(given, dict) or set(given) != {'caption'}:
                    raise ListingError(f'store.json screenshots: {name} locales.{code}: {{"caption": text}}')
                captions.setdefault(code, {})[len(listed)] = text_of(given['caption'], MAX_CAPTION, f'store.json screenshots {name} locales.{code}.caption')
        record, raw = picture(folder, name, MAX_SCREENSHOT_BYTES, SCREENSHOT_TYPES)
        record['width'], record['height'] = picture_size(raw, record['type'])
        if not (0 < record['width'] <= 10_000 and 0 < record['height'] <= 10_000):
            raise ListingError(f'{name}: unreasonable picture size {record["width"]}x{record["height"]}')
        if caption:
            record['caption'] = caption
        listed.append(record)
    if len({record['path'] for record in listed}) != len(listed):
        raise ListingError('store.json screenshots: the same file is listed twice')
    if listed:
        details['screenshots'] = listed

    def description_text(path: Path) -> str:
        text = path.read_text(encoding='utf-8').replace('\r', '').strip()
        if not 0 < len(text) <= MAX_DESCRIPTION:
            raise ListingError(f'store/{path.name} must have 1 to {MAX_DESCRIPTION} characters')
        return text

    description = folder / 'store' / 'description.md'
    if description.is_file():
        details['description'] = description_text(description)
    for code, path in localized_files(folder, 'description').items():
        if 'description' not in details:
            raise ListingError(f'store/{path.name}: write the English store/description.md first: it is the base text')
        texts.setdefault(code, {})['description'] = description_text(path)
    history = folder / 'store' / 'changelog.md'
    if history.is_file():
        changes = changelog(history.read_text(encoding='utf-8'))
        for code, path in localized_files(folder, 'changelog').items():
            known = {change['version'] for change in changes}
            said = {change['version']: change['notes'] for change in changelog(path.read_text(encoding='utf-8'))}
            stale = sorted(set(said) - known)
            if stale:
                raise ListingError(f'store/{path.name}: version {stale[0]} is not in the English store/changelog.md')
            if said:
                texts.setdefault(code, {})['changes'] = said
        if changes:
            details['changes'] = changes
            current = next((change for change in changes if change['version'] == version), None)
            if current is None:
                notes.append(f'store/changelog.md has no section for the current version {version}')
            elif current.get('date'):
                card['updated'] = current['date']
    for code in localized_files(folder, 'changelog'):
        if not history.is_file():
            raise ListingError(f'store/changelog.{code}.md: write the English store/changelog.md first: it is the base text')
    for code, said in captions.items():
        texts.setdefault(code, {})['captions'] = [said.get(index) for index in range(max(said) + 1)]
    if len(set(locales) | set(texts)) > MAX_LOCALES:
        raise ListingError(f'a listing is given in up to {MAX_LOCALES} languages')
    if locales:
        card['locales'] = locales
    if texts:
        details['locales'] = {code: texts[code] for code in sorted(texts)}
    links = store.get('links')
    if links is not None:
        if not isinstance(links, dict) or not links or any(key not in ('homepage', 'source', 'support') or not isinstance(value, str) for key, value in links.items()):
            raise ListingError('store.json links: homepage, source and support, each an HTTPS address')
        details['links'] = links
    return card, (details or None), notes


def page_name(identity: str, total: int) -> str:
    """The page a package is on. By the hash of its ID, so releasing one package changes one page."""
    digits = 0 if total <= 200 else 1 if total <= 3200 else 2
    return 'catalog/p-' + (hashlib.sha256(identity.encode('utf-8')).hexdigest()[:digits] or '0') + '.json'
