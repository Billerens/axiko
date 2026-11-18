#!/usr/bin/env python3
"""Validate local Axiko marketplace metadata; never download, unpack or execute it."""
from __future__ import annotations

import argparse
import json
import re
import sys
import unicodedata
from pathlib import Path
from urllib.parse import urlsplit

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / "docs" / "marketplace"
MAX_METADATA_BYTES = 1_048_576
MAX_FILE_BYTES = 2_097_152
MAX_UNPACKED_BYTES = 20_971_520
MAX_FILES = 512  # Includes package.json, which is excluded from its own inventory.
MAX_ERRORS = 50
UNSAFE_COMPONENT = re.compile(r'[\x00-\x1f\x7f<>:"|?*]')
RESERVED_COMPONENT = re.compile(r"^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|$)", re.I)


class JsonObject(dict):
    """Retain duplicate keys so their actual location can be reported after parsing."""

    def __init__(self, pairs):
        super().__init__()
        self.duplicates = []
        for key, value in pairs:
            if key in self:
                self.duplicates.append(key)
            self[key] = value


def json_path(parts) -> str:
    path = "$"
    for part in parts:
        if isinstance(part, int):
            path += f"[{part}]"
        elif re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*", part):
            path += "." + part
        else:
            path += "[" + json.dumps(part, ensure_ascii=True) + "]"
    return path


def issue(parts, message):
    return {"path": json_path(parts), "message": message}


def schema_issue(error):
    if error.validator == 'maxItems':
        message = f"Array has {len(error.instance)} items; maximum is {error.validator_value}"
    elif error.validator == 'maxLength':
        message = f"String exceeds {error.validator_value} characters"
    else:
        message = error.message
    if len(message) > 500:
        message = message[:497] + '...'
    return issue(tuple(error.absolute_path), message)


def walk(value, parts=()):
    yield parts, value
    if isinstance(value, dict):
        for key, child in value.items():
            yield from walk(child, parts + (key,))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from walk(child, parts + (index,))


def reject_constant(text):
    raise ValueError(f"Non-JSON numeric constant: {text}")


def parse_metadata(raw: bytes):
    if len(raw) > MAX_METADATA_BYTES:
        raise ValueError(f"Metadata exceeds {MAX_METADATA_BYTES} UTF-8 bytes")
    if raw.startswith(b"\xef\xbb\xbf"):
        raise ValueError("Use UTF-8 without a byte-order mark")
    text = raw.decode("utf-8", errors="strict")
    value = json.loads(text, object_pairs_hook=JsonObject, parse_constant=reject_constant)
    errors = []
    for parts, item in walk(value):
        if isinstance(item, JsonObject):
            for key in item.duplicates:
                errors.append(issue(parts + (key,), "Duplicate JSON property"))
            strings = list(item)
        else:
            strings = [item] if isinstance(item, str) else []
        if any(any(0xD800 <= ord(ch) <= 0xDFFF for ch in text) for text in strings):
            errors.append(issue(parts, "Unpaired Unicode surrogate is not valid UTF-8 text"))
    return value, errors


def check_https(url: str) -> str | None:
    if any(ord(ch) <= 32 or ord(ch) == 127 for ch in url) or "\\" in url:
        return "URL contains whitespace, control characters or backslashes"
    try:
        parsed = urlsplit(url)
        if parsed.scheme != "https" or not parsed.hostname:
            return "A complete HTTPS URL is required"
        if parsed.username is not None or parsed.password is not None:
            return "URL credentials are not allowed"
        if "#" in url:
            return "URL fragments are not allowed"
        _ = parsed.port  # Reject malformed/out-of-range ports too.
        host = parsed.hostname.encode("idna").decode("ascii")
        if not re.fullmatch(r"[A-Za-z0-9.:-]+", host):
            return "Invalid URL host"
    except (ValueError, UnicodeError):
        return "Malformed HTTPS URL"
    return None


def check_relative_path(path: str, *, entry=False, mod=False) -> str | None:
    if unicodedata.normalize("NFC", path) != path:
        return "Path must be in canonical NFC Unicode form"
    if "\\" in path or path.startswith("/"):
        return "Path must be relative and use forward slashes"
    if entry:
        if not path.startswith("code/"):
            return "Entry must be inside code/"
    elif mod and path not in ("README.md", "LICENSE") and not path.startswith(("mod/", "reference/", "base/", "assets/")):
        return "A mod holds only mod/, reference/, base/, assets/, README.md and LICENSE"
    elif not mod and path not in ("README.md", "LICENSE") and not path.startswith(("code/", "assets/")):
        return "Only code/, assets/, README.md and LICENSE are allowed"
    for component in path.split("/"):
        if not component or component.startswith(".") or component.endswith((".", " ")):
            return "Empty, dot, hidden or trailing-dot/space path components are not allowed"
        if UNSAFE_COMPONENT.search(component) or RESERVED_COMPONENT.match(component):
            return "Path contains an unsafe or reserved component"
    return None


def check_listing_path(path: str) -> str | None:
    """A file published next to index.json: a page, the details of a package or a picture."""
    if unicodedata.normalize("NFC", path) != path:
        return "Path must be in canonical NFC Unicode form"
    if "\\" in path or path.startswith("/") or "://" in path:
        return "Path must be relative to the folder of index.json and use forward slashes"
    for component in path.split("/"):
        if not component or component.startswith(".") or component.endswith((".", " ")):
            return "Empty, dot, hidden or trailing-dot/space path components are not allowed"
        if UNSAFE_COMPONENT.search(component) or RESERVED_COMPONENT.match(component):
            return "Path contains an unsafe or reserved component"
    return None


def locale_errors(locales: dict, path: tuple):
    """English is the base text of a listing and has no locale of its own."""
    return [issue(path + ('locales', code), "English is the base text: write it in the base fields, not in a locale")
            for code in locales if code == 'en']


def card_errors(package: dict, path: tuple):
    errors = locale_errors(package.get('locales', {}), path)
    tags = package.get('tags', [])
    for code, shown in package.get('locales', {}).items():
        words = shown.get('tags')
        if words is None:
            continue
        # A tag is its base text, which is counted and filtered by; a locale gives the words to show for the same tags.
        if len(words) != len(tags):
            errors.append(issue(path + ('locales', code, 'tags'), f"One text for every tag of the card ({len(tags)}), in the same order"))
        for number, word in enumerate(words):
            if word != word.strip() or any(ord(ch) < 32 or ord(ch) == 127 for ch in word):
                errors.append(issue(path + ('locales', code, 'tags', number), "No spaces around the text and no control characters"))
    for where, url in [(path + ('manifestUrl',), package['manifestUrl']), (path + ('archive', 'url'), package['archive']['url'])]:
        problem = check_https(url)
        if problem:
            errors.append(issue(where, problem))
    for number, tag in enumerate(package.get('tags', [])):
        if tag != tag.lower() or tag != tag.strip():
            errors.append(issue(path + ('tags', number), "Tags are lowercase, without surrounding spaces"))
    for field in ('icon', 'details'):
        if field in package:
            problem = check_listing_path(package[field]['path'])
            if problem:
                errors.append(issue(path + (field, 'path'), problem))
    return errors


def semantic_errors(value: dict, kind: str, byte_count: int):
    errors = []
    if kind == "index":
        problem = check_https(value['repository']['homepage'])
        if problem:
            errors.append(issue(('repository', 'homepage'), problem))
        seen = set()
        for number, page in enumerate(value['pages']):
            problem = check_listing_path(page['path'])
            if problem:
                errors.append(issue(('pages', number, 'path'), problem))
            if page['path'].casefold() in seen:
                errors.append(issue(('pages', number, 'path'), "The same page is listed twice"))
            seen.add(page['path'].casefold())
        if sum(page['count'] for page in value['pages']) != value['packageCount']:
            errors.append(issue(('packageCount',), "packageCount differs from the sum of the pages' counts"))
        return errors
    if kind == "page":
        seen = set()
        for number, package in enumerate(value['packages']):
            identity = unicodedata.normalize('NFC', package['id']).casefold()
            if identity in seen:
                errors.append(issue(('packages', number, 'id'), "Duplicate package ID on the page"))
            seen.add(identity)
            errors.extend(card_errors(package, ('packages', number)))
        return errors
    if kind == "details":
        if byte_count > 262_144:
            errors.append(issue((), "Details exceed 262144 UTF-8 bytes"))
        seen = set()
        for number, shot in enumerate(value.get('screenshots', [])):
            problem = check_listing_path(shot['path'])
            if problem:
                errors.append(issue(('screenshots', number, 'path'), problem))
            if shot['path'] in seen:
                errors.append(issue(('screenshots', number, 'path'), "The same picture is listed twice"))
            seen.add(shot['path'])
        versions = [change['version'] for change in value.get('changes', [])]
        if len(set(versions)) != len(versions):
            errors.append(issue(('changes',), "A version is listed twice"))
        errors.extend(locale_errors(value.get('locales', {}), ()))
        shots = value.get('screenshots', [])
        for code, shown in value.get('locales', {}).items():
            if 'description' in shown and 'description' not in value:
                errors.append(issue(('locales', code, 'description'), "A translated description needs the English one"))
            for version in shown.get('changes', {}):
                if version not in versions:
                    errors.append(issue(('locales', code, 'changes', version), "A version that is not in changes"))
            captions = shown.get('captions', [])
            if len(captions) > len(shots):
                errors.append(issue(('locales', code, 'captions'), f"More captions than screenshots ({len(shots)})"))
            for number, caption in enumerate(captions):
                if caption is not None and (number >= len(shots) or 'caption' not in shots[number]):
                    errors.append(issue(('locales', code, 'captions', number), "A translated caption needs the English caption"))
        for key, url in value.get('links', {}).items():
            problem = check_https(url)
            if problem:
                errors.append(issue(('links', key), problem))
        return errors

    mod = value['kind'] == 'mod'
    if not mod:
        problem = check_relative_path(value['entry'], entry=True)
        if problem:
            errors.append(issue(('entry',), problem))
    seen = {}
    original_paths = set()
    directories = {}
    for number, record in enumerate(value['files']):
        path = record['path']
        problem = check_relative_path(path, mod=mod)
        if problem:
            errors.append(issue(('files', number, 'path'), problem))
        canonical = unicodedata.normalize('NFC', path).casefold()
        if canonical in seen:
            errors.append(issue(('files', number, 'path'), f"Path collides with files[{seen[canonical]}] after NFC/case normalization"))
        seen[canonical] = number
        original_paths.add(path)
        pieces = path.split('/')
        for length in range(1, len(pieces)):
            directory = '/'.join(pieces[:length])
            normalized = unicodedata.normalize('NFC', directory).casefold()
            if normalized in directories and directories[normalized] != directory:
                errors.append(issue(('files', number, 'path'), "Directory name collides after NFC/case normalization"))
            directories[normalized] = directory
    for normalized, number in seen.items():
        if normalized in directories:
            errors.append(issue(('files', number, 'path'), "File path is also used as a directory"))
    if not mod and value['entry'] not in original_paths:
        errors.append(issue(('entry',), "Entry is missing from the file inventory"))
    if mod:
        # A mod is a brief and a sample: what it says about its sample must be what its files are.
        reference = value['reference']
        sample = {path[len('reference/'):] for path in original_paths if path.startswith('reference/')}
        before = {path[len('base/'):] for path in original_paths if path.startswith('base/')}
        if 'mod/brief.md' not in original_paths:
            errors.append(issue(('files',), "A mod needs mod/brief.md: the task for whoever carries it over"))
        if value['extends'] == value['id']:
            errors.append(issue(('extends',), "A mod cannot extend itself"))
        if not sample and not reference['removed']:
            errors.append(issue(('reference',), "A mod changes nothing: no file in reference/ and nothing removed"))
        if set(reference['base']) != before:
            errors.append(issue(('reference', 'base'), "reference.base must list exactly the files in base/"))
        if set(reference['added']) != sample - before:
            errors.append(issue(('reference', 'added'), "reference.added must list exactly the files of reference/ that have no copy in base/"))
        if set(reference['removed']) & sample or before - sample - set(reference['removed']):
            errors.append(issue(('reference', 'removed'), "A removed file is not in reference/, and a file of base/ is either changed or removed"))
        for field in ('added', 'removed'):
            for number, name in enumerate(reference[field]):
                if check_relative_path('reference/' + name, mod=True):
                    errors.append(issue(('reference', field, number), "Not a valid path of a file in the target"))
    if len(value['files']) + 1 > MAX_FILES:
        errors.append(issue(('files',), f"Archive would exceed {MAX_FILES} files including package.json"))
    if byte_count + sum(record['size'] for record in value['files']) > MAX_UNPACKED_BYTES:
        errors.append(issue(('files',), f"Declared files plus package.json exceed {MAX_UNPACKED_BYTES} unpacked bytes"))
    # No implicit precedence between optional/required declarations of the same package.
    dependencies = set()
    for field in ('dependencies', 'optionalDependencies'):
        for number, dependency in enumerate(value[field]):
            identity = dependency['id']
            if identity == value['id']:
                errors.append(issue((field, number, 'id'), "A package cannot depend on itself"))
            if identity in dependencies:
                errors.append(issue((field, number, 'id'), "Duplicate dependency ID"))
            dependencies.add(identity)
    return errors


def validate_path(path: Path, kind: str):
    result = {"ok": False, "kind": kind, "path": str(path), "scope": "metadata-only"}
    try:
        with path.open('rb') as stream:
            raw = stream.read(MAX_METADATA_BYTES + 1)
        value, errors = parse_metadata(raw)
        result['bytes'] = len(raw)
        if not errors:
            schema = json.loads((SCHEMAS / f'{kind}.schema.json').read_text(encoding='utf-8'))
            validator = Draft202012Validator(schema, format_checker=FormatChecker())
            errors = [schema_issue(error) for error in validator.iter_errors(value)]
        if not errors:
            errors = semantic_errors(value, kind, len(raw))
        if errors:
            result['errors'] = errors[:MAX_ERRORS]
            result['errorCount'] = len(errors)
        else:
            result['ok'] = True
            counted = {'index': 'pages', 'page': 'packages', 'package': 'files'}.get(kind)
            if counted:
                result[counted] = len(value[counted])
    except (OSError, ValueError, UnicodeError, RecursionError) as error:
        result['errors'] = [issue((), str(error))]
        result['errorCount'] = 1
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--index', type=Path, metavar='PATH', help='Validate a local index.json (the head of a catalog)')
    mode.add_argument('--page', type=Path, metavar='PATH', help='Validate a local page of a catalog')
    mode.add_argument('--details', type=Path, metavar='PATH', help='Validate the local details file of a package')
    mode.add_argument('--package', type=Path, metavar='PATH', help='Validate a local package.json')
    args = parser.parse_args(argv)
    kind = next(name for name in ('index', 'page', 'details', 'package') if getattr(args, name) is not None)
    result = validate_path(getattr(args, kind), kind)
    print(json.dumps(result, ensure_ascii=True, indent=2))
    return 0 if result['ok'] else 2


if __name__ == '__main__':
    raise SystemExit(main())
