"""Collects the Russian texts of the interface and says which of them a dictionary lacks.

    python scripts/i18n.py                 the texts ui/i18n/en.json has no translation for
    python scripts/i18n.py --lang de       the same for another dictionary
    python scripts/i18n.py --all           every text found, as JSON (the keys of a dictionary)
    python scripts/i18n.py --stale         keys of the dictionary that no screen uses any more

A text put together in code from pieces becomes one key with {} where something is put in:
    'Удалить тему «' + name + '»?'   ->   "Удалить тему «{}»?"
The kit matches such keys against what is drawn (see ui.t in ui/kit/kit.js).
"""
import argparse
import io
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, 'app', 'src', 'main', 'assets')
KOTLIN = os.path.join(ROOT, 'app', 'src', 'main', 'java')
CYRILLIC = re.compile('[А-Яа-яЁё]')
# Kotlin texts the owner sees: errors that reach the chat, toasts, the recovery menu, the
# catalogue of on-device models, the names of snapshots, what the shell says in events.
KOTLIN_SITES = re.compile(
    r'LlmError\(|\berror\(|toast\(|Exception\(|arrayOf\(|I18n\.t\(|\?: "|ifEmpty \{ "|ifBlank \{ "'
    r'|Entry\(|Spec\(|snapshot\(|broadcast\(|put\("(name|message|title|about|text)"|Result\(')


def literals(code, kotlin=False):
    """Yields (start, end, text) of string literals; ${...} and $name inside become {}."""
    i = 0
    n = len(code)
    quotes = '"' if kotlin else '\'"`'
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
        if ch in quotes:
            start = i
            i += 1
            out = []
            while i < n and code[i] != ch:
                if code[i] == '\\' and i + 1 < n:
                    out.append({'n': '\n', 't': '\t'}.get(code[i + 1], code[i + 1]))
                    i += 2
                    continue
                if (ch == '`' or kotlin) and code.startswith('${', i):
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
                if kotlin and code[i] == '$' and i + 1 < n and (code[i + 1].isalpha() or code[i + 1] == '_'):
                    i += 1
                    while i < n and (code[i].isalnum() or code[i] == '_'):
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


def chains(code, kotlin=False):
    """Joins literals that the code adds together: 'a' + x + 'b' -> 'a{}b'."""
    found = list(literals(code, kotlin))
    used = set()
    for index, (start, end, text) in enumerate(found):
        if index in used:
            continue
        parts = []
        before = code[max(0, start - 24):start].rstrip()
        if before.endswith('+'):
            parts.append('{}')
        parts.append(text)
        at = index
        while True:
            rest = code[found[at][1]:found[at][1] + 200]
            plus = re.match(r'\s*\+\s*', rest)
            if not plus:
                break
            nxt = at + 1
            if nxt < len(found):
                between = code[found[at][1]:found[nxt][0]]
                if re.fullmatch(r'\s*\+\s*', between):
                    parts.append(found[nxt][2])
                    used.add(nxt)
                    at = nxt
                    continue
                if (re.fullmatch(r'\s*\+\s*[^\'"`;{}\n]{1,160}?\s*\+\s*', between) and between.count('(') == between.count(')')):
                    parts.append('{}')
                    parts.append(found[nxt][2])
                    used.add(nxt)
                    at = nxt
                    continue
            parts.append('{}')
            break
        yield ''.join(parts)


def clean(text):
    text = re.sub(r'(\{\})+', '{}', text.strip())
    return text


def worth(text):
    letters = len(CYRILLIC.findall(text))
    if letters < 2:
        return False
    # "{} МБ" is a text of the interface; "{}: {}" would match anything.
    if '{}' in text and len(CYRILLIC.findall(text.replace('{}', ''))) < 2:
        return False
    if text in ('{}',) or '\\' in text or '[А-' in text or 'а-я' in text.lower() and '[' in text:
        return False
    return len(text) <= 400


def markup(html):
    """Texts of the static markup: between tags, and in the attributes people read."""
    body = re.sub(r'<script[\s\S]*?</script>|<style[\s\S]*?</style>|<!--[\s\S]*?-->', ' ', html)
    for attribute in re.findall(r'(?:placeholder|aria-label|title|alt)="([^"]*)"', body):
        yield attribute
    for text in re.findall(r'>([^<>]+)<', body):
        yield re.sub(r'\s+', ' ', text)
    title = re.search(r'<title>([^<]*)</title>', html)
    if title:
        yield title.group(1)


def collect():
    texts = {}

    def add(text, where):
        text = clean(text)
        if worth(text):
            texts.setdefault(text, where)

    for base, _, names in os.walk(ASSETS):
        for name in sorted(names):
            path = os.path.join(base, name)
            where = os.path.relpath(path, ROOT).replace('\\', '/')
            # The shell's own engine folder, not a module's folder that happens to be called so.
            if '/factory/ui/' not in where and not where.startswith('app/src/main/assets/engine/'):
                continue
            if name.endswith('.html'):
                html = io.open(path, encoding='utf-8').read()
                for text in markup(html):
                    add(text, where)
                for script in re.findall(r'<script[^>]*>([\s\S]*?)</script>', html):
                    for text in chains(script):
                        add(text, where)
            elif name.endswith('.js') and 'vendor' not in where:
                for text in chains(io.open(path, encoding='utf-8').read()):
                    add(text, where)
    factory = os.path.join(ASSETS, 'factory')
    for name in sorted(os.listdir(os.path.join(factory, 'themes'))):
        add(json.load(io.open(os.path.join(factory, 'themes', name), encoding='utf-8')).get('name', ''), 'themes/' + name)
    for item in json.load(io.open(os.path.join(factory, 'ui', 'nav.json'), encoding='utf-8')).get('items', []):
        add(item.get('title', ''), 'ui/nav.json')
    for base, _, names in os.walk(KOTLIN):
        for name in sorted(names):
            if not name.endswith('.kt'):
                continue
            path = os.path.join(base, name)
            where = os.path.relpath(path, ROOT).replace('\\', '/')
            code = io.open(path, encoding='utf-8').read()
            # Only statements at the places whose text reaches the owner.
            for statement in re.split(r'\n(?=\s*(?:val|var|fun|if|else|when|return|throw|[A-Za-z_."]+\s*(?:->|=|\()))', code):
                if KOTLIN_SITES.search(statement):
                    for text in chains(statement, kotlin=True):
                        add(text, where)
    return texts


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--lang', default='en')
    parser.add_argument('--all', action='store_true')
    parser.add_argument('--stale', action='store_true')
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding='utf-8')
    texts = collect()
    if args.all:
        print(json.dumps(sorted(texts), ensure_ascii=False, indent=1))
        return
    path = os.path.join(ASSETS, 'factory', 'ui', 'i18n', args.lang + '.json')
    have = json.load(io.open(path, encoding='utf-8')) if os.path.isfile(path) else {}
    if args.stale:
        print(json.dumps(sorted(key for key in have if key != '_name' and key not in texts), ensure_ascii=False, indent=1))
        return
    missing = {text: where for text, where in texts.items() if not have.get(text)}
    print(json.dumps(sorted(missing), ensure_ascii=False, indent=1))
    print('%d of %d texts have no translation in %s' % (len(missing), len(texts), os.path.relpath(path, ROOT)), file=sys.stderr)


if __name__ == '__main__':
    main()
