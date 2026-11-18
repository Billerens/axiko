#!/usr/bin/env python3
"""Whether a package can be used in English, as a report and not as a gate.

Every module and game of the store must be fully usable in English: its interface texts are written
in English, or they are written in Russian and `i18n-en.json` next to `store.json` covers every one
of them (a flat dictionary, Russian text to English; `{}` in a key stands for what is put into the
text). Most existing packages are Russian-source with a partial dictionary, so the check only
reports: the number of texts without an English entry and up to five examples.

    python tools/store/language.py --store .                  every package, as a table
    python tools/store/language.py --store . --package focus  one package (an ID or a folder)
    python tools/store/language.py --store . --json           the same, as JSON

Interface texts are found the way scripts/i18n.py of the shell finds them: the text of HTML markup
and of its readable attributes, string literals of the scripts (pieces joined by `+` or put into
a template become one text with `{}`), and the string values of the JSON files of the package
(`module.json`, `game.json`, configuration, content). Tests, fixtures, author tools, checks, prompts, the `store/` listing
and README files are not interface. A package without Cyrillic interface text is written in English
and passes. Nothing is executed, nothing is fetched; the result depends on the files only.

The listing, what the store shows before an install, is checked the same way: its base fields
(title, summary, tags and screenshot captions of `store.json`, `store/description.md`,
`store/changelog.md`) are the required English text, so Cyrillic in them is reported as a warning,
with the languages the listing is also given in (`locales` of `store.json`, `store/*.<code>.md`).
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path

EXAMPLES = 5
MAX_TEXT = 400
CYRILLIC = re.compile('[А-яЁё]')
SKIP_DIRS = {'tests', 'test', 'tools', 'fixtures', 'prompts', 'store', 'node_modules', '_data'}
SKIP_FILES = {'checks.js', 'i18n-en.json', 'store.json', 'package.json'}
SKIP_SUFFIXES = ('.test.js', '.min.js')


def literals(code: str):
    """Yields the text of string literals; `${...}` inside a template becomes `{}`."""
    i, n = 0, len(code)
    while i < n:
        ch = code[i]
        if code.startswith('//', i) and (i == 0 or code[i - 1] != ':'):
            i = code.find('\n', i)
            if i < 0:
                return
            continue
        if code.startswith('/*', i):
            i = code.find('*/', i)
            if i < 0:
                return
            i += 2
            continue
        if ch in '\'"`':
            start = i
            i += 1
            out = []
            while i < n and code[i] != ch:
                if code[i] == '\\' and i + 1 < n:
                    out.append({'n': '\n', 't': '\t'}.get(code[i + 1], code[i + 1]))
                    i += 2
                    continue
                if ch == '`' and code.startswith('${', i):
                    depth = 0
                    while i < n:
                        if code[i] == '{':
                            depth += 1
                        elif code[i] == '}':
                            depth -= 1
                            if depth == 0:
                                break
                        i += 1
                    i += 1
                    out.append('{}')
                    continue
                if ch != '`' and code[i] == '\n':
                    break
                out.append(code[i])
                i += 1
            i += 1
            yield start, i, ''.join(out)
            continue
        i += 1


def chains(code: str):
    """Joins literals that the code adds together: 'a' + x + 'b' becomes 'a{}b'."""
    found = list(literals(code))
    used = set()
    for index, (start, _end, text) in enumerate(found):
        if index in used:
            continue
        parts = []
        if code[max(0, start - 24):start].rstrip().endswith('+'):
            parts.append('{}')
        parts.append(text)
        at = index
        while True:
            if not re.match(r'\s*\+\s*', code[found[at][1]:found[at][1] + 200]):
                break
            nxt = at + 1
            if nxt < len(found):
                between = code[found[at][1]:found[nxt][0]]
                if re.fullmatch(r'\s*\+\s*', between):
                    parts.append(found[nxt][2])
                    used.add(nxt)
                    at = nxt
                    continue
                if re.fullmatch(r'\s*\+\s*[^\'"`;{}\n]{1,160}?\s*\+\s*', between) and between.count('(') == between.count(')'):
                    parts.append('{}')
                    parts.append(found[nxt][2])
                    used.add(nxt)
                    at = nxt
                    continue
            parts.append('{}')
            break
        yield ''.join(parts)


def clean(text: str) -> str:
    return re.sub(r'(\{\})+', '{}', re.sub(r'\s+', ' ', text.strip()))


def worth(text: str) -> bool:
    """A text of the interface: some Russian words, not a pattern of a regular expression."""
    if len(CYRILLIC.findall(text)) < 2 or len(text) > MAX_TEXT:
        return False
    if '{}' in text and len(CYRILLIC.findall(text.replace('{}', ''))) < 2:
        return False
    return '\\' not in text and '[А-' not in text


def markup(html: str):
    """Texts of static markup: between tags, and in the attributes people read."""
    body = re.sub(r'<script[\s\S]*?</script>|<style[\s\S]*?</style>|<!--[\s\S]*?-->', ' ', html)
    yield from re.findall(r'(?:placeholder|aria-label|title|alt)="([^"]*)"', body)
    yield from re.findall(r'>([^<>]+)<', body)
    title = re.search(r'<title>([^<]*)</title>', html)
    if title:
        yield title.group(1)


def values(node):
    """The string values of a JSON document; keys are identifiers, not texts."""
    if isinstance(node, str):
        yield node
    elif isinstance(node, list):
        for item in node:
            yield from values(item)
    elif isinstance(node, dict):
        for item in node.values():
            yield from values(item)


def read(path: Path) -> str:
    return path.read_bytes().decode('utf-8', errors='replace')


def interface_texts(folder: Path) -> dict[str, str]:
    """Every Russian interface text of the package folder, with the file it was found in."""
    texts: dict[str, str] = {}

    def add(text: str, where: str):
        text = clean(text)
        if worth(text):
            texts.setdefault(text, where)

    for directory, directories, files in os.walk(folder, followlinks=False):
        directories[:] = sorted(name for name in directories if name not in SKIP_DIRS and not name.startswith('.'))
        here = Path(directory)
        for name in sorted(files):
            if name in SKIP_FILES or name.startswith('.') or name.endswith(SKIP_SUFFIXES):
                continue
            path = here / name
            where = path.relative_to(folder).as_posix()
            if path.is_symlink():
                continue
            if name.endswith('.html'):
                html = read(path)
                for text in markup(html):
                    add(text, where)
                for script in re.findall(r'<script[^>]*>([\s\S]*?)</script>', html):
                    for text in chains(script):
                        add(text, where)
            elif name.endswith('.js'):
                for text in chains(read(path)):
                    add(text, where)
            elif name.endswith('.json') and not name.startswith('i18n-'):
                try:
                    document = json.loads(read(path))
                except ValueError:
                    continue
                for text in values(document):
                    add(text, where)
    return texts


def dictionary(folder: Path) -> dict[str, str]:
    """The English dictionary of the package: `i18n-en.json`, keys cleaned like the texts."""
    path = folder / 'i18n-en.json'
    if not path.is_file():
        return {}
    try:
        document = json.loads(read(path))
    except ValueError:
        return {}
    if not isinstance(document, dict):
        return {}
    return {clean(key): value for key, value in document.items()
            if key != '_name' and isinstance(value, str) and value.strip()}


def patterns(words: dict[str, str]):
    return [re.compile('^' + '(.+?)'.join(re.escape(part) for part in key.split('{}')) + '$', re.S) for key in words if '{}' in key]


def check(folder: Path) -> dict:
    """What the package lacks to be usable in English; `complete` is true when nothing."""
    texts = interface_texts(folder)
    words = dictionary(folder)
    matchers = patterns(words)
    missing = sorted(text for text in texts if text not in words and not any(rule.match(text) for rule in matchers))
    source = 'ru' if texts else 'en'
    return {'source': source, 'texts': len(texts), 'dictionary': (folder / 'i18n-en.json').is_file(),
            'dictionaryEntries': len(words), 'untranslated': len(missing),
            'examples': [{'text': text, 'file': texts[text]} for text in missing[:EXAMPLES]],
            'complete': not missing, 'listing': listing(folder)}


def listing_texts(folder: Path, store: dict, summary: str | None = None) -> dict[str, str]:
    """The base (English) texts of a listing by the field that holds them."""
    found: dict[str, str] = {}
    if isinstance(store.get('title'), str):
        found['store.json title'] = store['title']
    if summary is None:
        summary = store.get('summary')
    if summary is None:
        for name in ('module.json', 'game.json'):
            try:
                descriptor = json.loads(read(folder / name))
            except (OSError, ValueError):
                continue
            if isinstance(descriptor, dict):
                summary = descriptor.get('description') or descriptor.get('about')
                if summary:
                    break
    if isinstance(summary, str):
        found['summary'] = summary
    tags = store.get('tags')
    if isinstance(tags, list):
        found['store.json tags'] = ' '.join(tag for tag in tags if isinstance(tag, str))
    shots = store.get('screenshots')
    for shot in shots if isinstance(shots, list) else []:
        if isinstance(shot, dict) and isinstance(shot.get('caption'), str):
            found['screenshot captions'] = found.get('screenshot captions', '') + ' ' + shot['caption']
    for name in ('description.md', 'changelog.md'):
        path = folder / 'store' / name
        if path.is_file() and not path.is_symlink():
            found['store/' + name] = read(path)
    return found


def listing(folder: Path, summary: str | None = None) -> dict:
    """Whether the listing's base fields are English, and which other languages it is given in."""
    try:
        store = json.loads(read(folder / 'store.json'))
    except (OSError, ValueError):
        store = {}
    store = store if isinstance(store, dict) else {}
    texts = listing_texts(folder, store, summary)
    russian = [field for field, value in texts.items() if CYRILLIC.search(value)]
    languages = set(store['locales']) if isinstance(store.get('locales'), dict) else set()
    directory = folder / 'store'
    for path in directory.glob('*.*.md') if directory.is_dir() else []:
        stem, _, rest = path.name.partition('.')
        if stem in ('description', 'changelog') and rest.endswith('.md') and re.fullmatch(r'[a-z]{2,3}(?:-[A-Za-z]{2,4})?', rest[:-3]):
            languages.add(rest[:-3])
    return {'english': not russian, 'notEnglish': russian, 'languages': sorted(languages)}


def warnings(report: dict) -> list[str]:
    """Notes for the author; empty when English is complete and the listing is English."""
    found = []
    if not report['complete']:
        have = 'i18n-en.json lacks' if report['dictionary'] else 'no i18n-en.json; lacking'
        found.append(f'English is not complete: {have} {report["untranslated"]} of {report["texts"]} interface texts '
                     f'(e.g. {report["examples"][0]["text"][:60]!r} in {report["examples"][0]["file"]})')
    shown = report.get('listing')
    if shown and not shown['english']:
        found.append('The listing is not in English: ' + ', '.join(shown['notEnglish']) + ' hold Cyrillic. '
                     'The base fields are the English text; the Russian goes into locales.ru and store/*.ru.md')
    return found


def packages(store: Path, wanted: list[str]):
    """(id, folder, kind) of the package folders of a store; a mod has no interface of its own."""
    for folder in sorted(path for path in store.iterdir() if (path / 'store.json').is_file()):
        try:
            described = json.loads(read(folder / 'store.json'))
        except ValueError:
            continue
        identity = described.get('id', folder.name)
        if wanted and identity not in wanted and folder.name not in wanted:
            continue
        yield identity, folder, described.get('kind')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--store', type=Path, default=Path('.'), help='Source root containing */store.json')
    parser.add_argument('--package', action='append', default=[], help='Package ID or folder, repeatable; default all')
    parser.add_argument('--json', action='store_true', help='Print the full report as JSON')
    args = parser.parse_args(argv)
    result = {}
    for identity, folder, kind in packages(args.store, args.package):
        if kind == 'mod':
            continue
        result[identity] = check(folder)
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    print(f'{"package":<22}{"source":<8}{"texts":>6}  {"english":<20}listing')
    for identity, report in result.items():
        state = 'complete' if report['complete'] else f'{report["untranslated"]} untranslated'
        shown = report['listing']
        words = ('english' if shown['english'] else 'not english') + (' + ' + ','.join(shown['languages']) if shown['languages'] else '')
        print(f'{identity:<22}{report["source"]:<8}{report["texts"]:>6}  {state:<20}{words}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
