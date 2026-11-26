"""Check maintained project-document links without network access.

Usage: python scripts/docs/check_links.py [--root ../assi-store]
Package READMEs and runtime prompts are outside this documentation-only check.
"""
import argparse
import html
import json
from pathlib import Path
import re
import unicodedata
from urllib.parse import unquote, urlsplit


def without_fences(text):
    lines = []
    fence = None
    for line in text.splitlines():
        match = re.match(r"^\s*(`{3,}|~{3,})", line)
        if match:
            marker = match.group(1)
            if fence is None:
                fence = marker
            elif marker[0] == fence[0] and len(marker) >= len(fence):
                fence = None
            lines.append("")
        else:
            lines.append("" if fence else line)
    return "\n".join(lines)


def anchors(text):
    result, used = set(), {}
    for heading in re.findall(r"^\s{0,3}#{1,6}\s+(.+?)\s*#*\s*$", without_fences(text), re.M):
        heading = re.sub(r"<[^>]+>", "", html.unescape(heading)).lower()
        slug = "".join(c for c in heading if c in "_- " or unicodedata.category(c)[0] in "LN")
        slug = slug.replace(" ", "-")
        number = used.get(slug, 0)
        used[slug] = number + 1
        result.add(slug + ("-" + str(number) if number else ""))
    result.update(re.findall(r"\b(?:id|name)=[\"']([^\"']+)[\"']", text))
    return result


def links(text):
    clean = without_fences(text)
    # Inline examples such as `exports[action](args)` are code, not Markdown links.
    clean = re.sub(r"(`+)([^\n]*?)\1", lambda match: " " * len(match.group(0)), clean)
    patterns = [r"!?\[[^\]\n]*\]\(([^)\n]+)\)", r"\b(?:href|src)=[\"']([^\"']+)[\"']", r"^\s{0,3}\[[^\]\n]+\]:\s*(\S+)"]
    for pattern in patterns:
        for match in re.finditer(pattern, clean, re.M):
            value = html.unescape(match.group(1).strip())
            if value.startswith("<"):
                value = value[1:].split(">", 1)[0]
            else:
                value = re.split(r"\s+[\"']", value, 1)[0]
            yield clean.count("\n", 0, match.start()) + 1, value


def check(root):
    documents = set(root.glob("README*.md"))
    for folder in ("docs", "scripts", "tools", "assets"):
        base = root / folder
        pattern = "*.md" if folder == "docs" else "README*.md"
        if base.is_dir():
            documents.update(p for p in base.rglob(pattern) if not any(part in {"out", "build", "node_modules", "__pycache__"} for part in p.relative_to(root).parts))
    errors, checked = [], 0
    for file in sorted(documents):
        text = file.read_text(encoding="utf-8-sig")
        for line, destination in links(text):
            if re.match(r"^[A-Za-z]:[\\/]", destination) or destination.startswith("file:"):
                errors.append({"file": file.relative_to(root).as_posix(), "line": line, "target": destination, "reason": "machine-specific path"})
                continue
            parsed = urlsplit(destination)
            if parsed.scheme or parsed.netloc or not destination:
                continue
            checked += 1
            path = unquote(parsed.path)
            target = (root / path.lstrip("/")) if path.startswith("/") else file.parent / path if path else file
            target = target.resolve()
            reason = None
            if not target.is_relative_to(root):
                reason = "target outside the repository"
            elif not target.exists():
                reason = "missing local target"
            elif parsed.fragment and target.is_file() and target.suffix.lower() == ".md":
                anchor = unquote(parsed.fragment)
                if not anchor.startswith("L") or not re.fullmatch(r"L\d+(?:-L\d+)?", anchor):
                    if anchor not in anchors(target.read_text(encoding="utf-8-sig")):
                        reason = "missing Markdown anchor"
            if reason:
                errors.append({"file": file.relative_to(root).as_posix(), "line": line, "target": destination, "reason": reason})
    return {"ok": not errors, "scope": "project documentation; excludes packaged module READMEs and runtime prompts", "documents": len(documents), "localLinks": checked, "errors": errors}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    result = check(args.root.resolve())
    print(json.dumps(result, ensure_ascii=False, indent=2))
    raise SystemExit(0 if result["ok"] else 1)
