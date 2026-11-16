"""Rewrites CSS in the conventional shape: one declaration per line, nested blocks indented.

    python scripts/format-css.py file.css page.html ...

In an HTML file only the contents of <style> blocks are touched.
"""
import io
import re
import sys


def pretty(css, base=''):
    """Formats CSS text; `base` is the indentation of the block it sits in."""
    out = []
    depth = 0
    buffer = ''
    i = 0

    def line(text):
        text = text.strip()
        if text:
            out.append(base + '  ' * depth + text)

    while i < len(css):
        char = css[i]
        if css.startswith('/*', i):
            end = css.index('*/', i) + 2
            line(buffer)
            buffer = ''
            comment = css[i:end]
            for part in comment.split('\n'):
                line(part)
            i = end
            continue
        if char == '{':
            line(re.sub(r'\s+', ' ', buffer).strip() + ' {')
            buffer = ''
            depth += 1
        elif char == '}':
            if buffer.strip():
                line(re.sub(r'\s+', ' ', buffer).rstrip(';') + ';')
            buffer = ''
            depth -= 1
            line('}')
            if depth == 0:
                out.append('')
        elif char == ';':
            line(re.sub(r'\s+', ' ', buffer) + ';')
            buffer = ''
        else:
            buffer += char
        i += 1
    while out and out[-1] == '':
        out.pop()
    # A blank line between top-level rules only; none inside blocks or before a closing brace.
    return '\n'.join(out)


def format_file(path):
    text = io.open(path, encoding='utf-8').read()
    if path.endswith('.css'):
        result = pretty(text) + '\n'
    else:
        def block(match):
            return match.group(1) + '\n' + pretty(match.group(2), '  ') + '\n' + match.group(3)
        result = re.sub(r'(<style>)(.*?)(</style>)', block, text, flags=re.S)
    io.open(path, 'w', encoding='utf-8', newline='\n').write(result)
    print('formatted', path)


for name in sys.argv[1:]:
    format_file(name)
