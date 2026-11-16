#!/usr/bin/env python3
"""Build local readable Assi packages. Never upload, install or execute package code."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import tempfile
import unicodedata
import zipfile
from pathlib import Path
from urllib.parse import urlsplit

import validate_metadata as metadata

MAX_ARCHIVE_BYTES = 5_242_880
SKIP_NAMES = {'tests', 'node_modules', 'build', 'dist', '__pycache__', '.git', 'data'}
CODE_EXTENSIONS = {'.js', '.mjs', '.css', '.html', '.htm'}
FORBIDDEN_EXTENSIONS = {'.apk', '.dex', '.jar', '.aar', '.class', '.so', '.dll', '.exe', '.wasm', '.zip'}
SECRET_RULES = [
    ('private-key', re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----')),
    ('github-token', re.compile(r'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\b')),
    ('openai-token', re.compile(r'\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{40,}\b')),
    ('aws-access-key', re.compile(r'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b')),
    ('google-api-key', re.compile(r'\bAIza[A-Za-z0-9_-]{35}\b')),
    ('slack-token', re.compile(r'\bxox[baprs]-[A-Za-z0-9-]{20,}\b')),
    ('telegram-bot-token', re.compile(r'\b[0-9]{8,12}:[A-Za-z0-9_-]{35}\b')),
]
SENSITIVE_NAMES = {'.env', 'credentials.json', 'secrets.json', 'id_rsa', 'id_ed25519', 'local.properties'}
URL_PATTERN = re.compile(r'https?://([A-Za-z0-9.-]+)(?::\d+)?(?:[/\s\'"<>]|$)', re.I)
NETWORK_PATTERN = re.compile(r'\bfetch\s*\(|XMLHttpRequest|\.http\b|https?://', re.I)
REMOTE_SOURCE = re.compile(r'<script\b[^>]*\bsrc\s*=\s*[\'"]\s*(?:https?:)?//|(?:importScripts\s*\(|\bimport\s*(?:\(|[^;\n]*?from\s*))\s*[\'"]\s*(?:https?:)?//', re.I)
PACKED_SOURCE = re.compile(r'\beval\s*\(\s*(?:atob|unescape|String\s*\.\s*fromCharCode)\s*\(|\beval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,', re.I)


class BuildError(ValueError):
    """Human-readable error that does not include source contents or secret values."""


def json_bytes(value) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + '\n').encode('utf-8')


def sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def load_json(path: Path):
    value, errors = metadata.parse_metadata(path.read_bytes())
    if errors or not isinstance(value, dict):
        raise BuildError(f'{path.name}: expected an object with unique JSON properties')
    return value


def audit_file(path: str, raw: bytes):
    """Only emit file, line and rule names, never matched credentials or source snippets."""
    findings = []
    name = Path(path).name.lower()
    if name in SENSITIVE_NAMES or name.startswith('.env.') or name.endswith(('.p12', '.pfx', '.jks', '.keystore', '.pem', '.key')):
        findings.append({'path': path, 'line': 0, 'kind': 'sensitive-filename', 'severity': 'blocker'})
    text = raw.decode('utf-8', errors='replace')
    for kind, pattern in SECRET_RULES:
        for match in pattern.finditer(text):
            findings.append({'path': path, 'line': text.count('\n', 0, match.start()) + 1,
                             'kind': kind, 'severity': 'blocker'})
    for kind, pattern in [('private-ip', r'\b(?:192\.168\.\d{1,3}\.\d{1,3}|10\.\d{1,3}\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})\b'),
                          ('local-user-path', r'(?i)(?:[a-z]:[\\/]Users[\\/][^\s\'"<>]+|/home/[^\s\'"<>]+)')]:
        for match in re.finditer(pattern, text):
            findings.append({'path': path, 'line': text.count('\n', 0, match.start()) + 1,
                             'kind': kind, 'severity': 'review'})
    return findings


def scan_source(path: str, raw: bytes):
    """Early checks; the actual Assi installer remains the final archive validator."""
    lower = path.lower()
    if Path(lower).suffix in FORBIDDEN_EXTENSIONS or raw.startswith(b'\x7fELF'):
        raise BuildError(f'{path}: binary executable/archive payload is forbidden')
    if len(raw) > metadata.MAX_FILE_BYTES:
        raise BuildError(f'{path}: file exceeds 2 MiB')
    if Path(lower).suffix not in CODE_EXTENSIONS:
        return
    try:
        text = raw.decode('utf-8', errors='strict')
    except UnicodeError as error:
        raise BuildError(f'{path}: source must be UTF-8') from error
    if re.search(r'\.min\.(?:js|mjs|css)$', lower):
        raise BuildError(f'{path}: minified filenames are forbidden')
    if REMOTE_SOURCE.search(text):
        raise BuildError(f'{path}: remote executable source is forbidden; include readable source locally')
    if PACKED_SOURCE.search(text):
        raise BuildError(f'{path}: packed executable source is forbidden')


def inventory_paths(payload):
    installed = {}
    for path in payload:
        problem = metadata.check_relative_path(path)
        if problem:
            raise BuildError(f'{path}: {problem}')
        mapped = path[5:] if path.startswith('code/') else path if path.startswith('assets/') else None
        if mapped is None:
            continue
        normalized = unicodedata.normalize('NFC', mapped).casefold()
        if normalized in installed:
            raise BuildError(f'{path}: installed path collides with {installed[normalized]}')
        installed[normalized] = path
    for path in installed:
        pieces = path.split('/')
        if any('/'.join(pieces[:i]) in installed for i in range(1, len(pieces))):
            raise BuildError(f'{installed[path]}: installed file is also used as a directory')


def deterministic_zip(payload: dict[str, bytes]) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path, raw in sorted(payload.items()):
            info = zipfile.ZipInfo(path, date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, raw, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    return output.getvalue()


def collect_payload(folder: Path, license_raw: bytes):
    payload, excluded, audit = {}, [], []
    if not (folder / 'README.md').is_file():
        raise BuildError('README.md is required')
    # Do not follow symlinks or copy any mutable user data. _data/assets is a special
    # legacy source-resource directory; it is mapped to immutable package assets/.
    for directory, directories, files in os.walk(folder, followlinks=False):
        here = Path(directory)
        for name in list(directories):
            path = here / name
            rel = path.relative_to(folder).as_posix()
            if path.is_symlink():
                raise BuildError(f'{rel}: symlinks are forbidden')
            if name in SKIP_NAMES or name.startswith('.'):
                directories.remove(name)
                excluded.append(rel + '/')
        for name in sorted(files):
            path = here / name
            rel = path.relative_to(folder).as_posix()
            if path.is_symlink():
                raise BuildError(f'{rel}: symlinks are forbidden')
            if name.startswith('.') or name in SENSITIVE_NAMES or name.endswith(('.p12', '.pfx', '.jks', '.keystore', '.pem', '.key')):
                # A known credential filename anywhere in source is a blocker even
                # though it would never be packaged. Its contents are not printed.
                audit.extend(audit_file(rel, b''))
                excluded.append(rel)
                continue
            if rel in ('store.json', 'README.md', 'LICENSE'):
                continue
            if rel.startswith('_data/'):
                if not rel.startswith('_data/assets/'):
                    raise BuildError(f'{rel}: mutable legacy _data is not supported; only read-only _data/assets can be packaged')
                virtual = 'assets/' + rel[len('_data/assets/'):]
            else:
                virtual = 'code/' + rel
            raw = path.read_bytes()
            payload[virtual] = raw
    payload['README.md'] = (folder / 'README.md').read_bytes()
    payload['LICENSE'] = license_raw
    for path, raw in sorted(payload.items()):
        audit.extend(audit_file(path, raw))
        scan_source(path, raw)
    inventory_paths(payload)
    return payload, sorted(excluded), audit


def validate_written(path: Path, kind: str):
    result = metadata.validate_path(path, kind)
    if not result['ok']:
        # Only generated metadata is used here, never secret-bearing source text.
        raise BuildError('; '.join(error['path'] + ': ' + error['message'] for error in result['errors']))


def package(folder: Path, output: Path, args, license_raw: bytes, ids: dict[str, str]):
    store = load_json(folder / 'store.json')
    identity = store.get('id', '')
    kind = store.get('kind')
    if not re.fullmatch(r'[a-z0-9][a-z0-9.-]{0,95}', identity) or kind not in ('module', 'game'):
        raise BuildError('store.json requires a valid id and kind')
    expected_target = ('modules/games/' if kind == 'game' else 'modules/') + identity
    if store.get('target') != expected_target:
        raise BuildError(f'Legacy target alias is unsupported in v1: expected {expected_target}; existing runtime and save IDs are not renamed')
    payload, excluded, audit = collect_payload(folder, license_raw)
    if any(item['severity'] == 'blocker' for item in audit):
        return None, {'id': identity, 'folder': folder.name, 'error': 'Potential credential material; inspect the reported files before publication', 'audit': audit}
    descriptor_path = 'code/' + ('game.json' if kind == 'game' else 'module.json')
    if descriptor_path not in payload:
        raise BuildError(f'{descriptor_path} is required')
    descriptor, errors = metadata.parse_metadata(payload[descriptor_path])
    if errors or not isinstance(descriptor, dict):
        raise BuildError(f'{descriptor_path}: expected a JSON object with unique properties')
    transforms = []
    if not descriptor.get('id') and kind == 'module':
        original = payload[descriptor_path]
        descriptor['id'] = identity
        payload[descriptor_path] = json_bytes(descriptor)
        transforms.append({'path': descriptor_path, 'kind': 'add-runtime-id', 'id': identity,
                           'sourceSha256': sha256(original), 'packagedSha256': sha256(payload[descriptor_path])})
    if descriptor.get('id') != identity:
        raise BuildError(f'{descriptor_path}: runtime id differs from store id; refusing to rename saves')
    declared_entry = descriptor.get('entry')
    entry = declared_entry or ('ui/index.html' if 'code/ui/index.html' in payload else 'main.js')
    if entry.startswith(expected_target + '/'):
        entry = entry[len(expected_target) + 1:]
    if 'code/' + entry not in payload:
        raise BuildError('Runtime entry is missing from package source')
    requires = store.get('requires', [])
    if not isinstance(requires, list) or any(not isinstance(value, str) for value in requires):
        raise BuildError('store.json requires must be an array of optional integration IDs')
    optional = sorted(set(ids.get(value, value) for value in requires))
    domains = set()
    network_files = []
    for path, raw in payload.items():
        if Path(path).suffix.lower() in CODE_EXTENSIONS:
            source = raw.decode('utf-8')
            domains.update(match.group(1).lower() for match in URL_PATTERN.finditer(source))
            if NETWORK_PATTERN.search(source):
                network_files.append(path)
    manifest = {
        'schemaVersion': 1, 'id': identity, 'kind': kind, 'version': args.version,
        'publisher': args.publisher, 'license': args.license_id, 'sourceFormat': 'readable',
        'entry': 'code/' + entry, 'runtime': {'assiApi': 1}, 'dependencies': [],
        'optionalDependencies': [{'id': dep, 'version': '>=' + args.version} for dep in optional],
        'capabilities': ['storage.own', 'legacy.full-trust'],
        'networkDomains': sorted(domains),
        'files': [{'path': path, 'size': len(raw), 'sha256': sha256(raw)} for path, raw in sorted(payload.items())],
    }
    if kind == 'game':
        manifest['runtime']['gamesEngine'] = 1
    stem = identity + '-' + args.version
    manifest_name = stem + '.package.json'
    manifest_raw = json_bytes(manifest)
    (output / manifest_name).write_bytes(manifest_raw)
    validate_written(output / manifest_name, 'package')
    payload['package.json'] = manifest_raw
    archive_raw = deterministic_zip(payload)
    if len(archive_raw) > MAX_ARCHIVE_BYTES:
        raise BuildError('Compressed archive exceeds 5 MiB')
    archive_name = stem + '.zip'
    (output / archive_name).write_bytes(archive_raw)
    summary = descriptor.get('description') or descriptor.get('about') or store.get('title') or identity
    entry = {
        'id': identity, 'kind': kind, 'title': store.get('title', identity), 'summary': str(summary)[:600],
        'version': args.version, 'publisher': args.publisher, 'license': args.license_id,
        'manifestUrl': args.base_download_url.rstrip('/') + '/' + manifest_name,
        'archive': {'url': args.base_download_url.rstrip('/') + '/' + archive_name,
                    'sha256': sha256(archive_raw), 'size': len(archive_raw),
                    'unpackedSize': sum(map(len, payload.values())), 'fileCount': len(payload)},
        'compatibility': {'minAssiApi': 1},
    }
    if kind == 'game':
        entry['compatibility']['gamesEngine'] = 1
    return entry, {'id': identity, 'folder': folder.name, 'archive': archive_name,
                   'archiveSha256': entry['archive']['sha256'], 'archiveBytes': len(archive_raw),
                   'unpackedBytes': entry['archive']['unpackedSize'], 'fileCount': len(payload),
                   'transforms': transforms, 'excludedSourcePaths': excluded, 'audit': audit,
                   'networkSourcePaths': sorted(network_files), 'literalNetworkHosts': sorted(domains)}


def build(args):
    store, output = args.store.resolve(), args.out.resolve()
    if not store.is_dir():
        raise BuildError('Store directory does not exist')
    if store == output or store in output.parents:
        raise BuildError('Output must be outside the source store to avoid packaging build artifacts')
    for url in [args.base_download_url, args.repository_homepage]:
        problem = metadata.check_https(url)
        if problem or urlsplit(url).query:
            raise BuildError('Repository and download URLs must be HTTPS without credentials, query or fragment')
    license_raw = args.licensefile.read_bytes()
    if not license_raw.strip():
        raise BuildError('A non-empty license/notice file is required')
    license_raw.decode('utf-8', errors='strict')
    folders = sorted(path.parent for path in store.glob('*/store.json'))
    if not folders:
        raise BuildError('No */store.json packages found')
    ids, seen = {}, set()
    for folder in folders:
        if folder.is_symlink():
            raise BuildError(f'{folder.name}: symlink package directory is forbidden')
        identity = load_json(folder / 'store.json').get('id')
        if identity in seen:
            raise BuildError('Duplicate package ID in source store')
        seen.add(identity)
        ids[folder.name] = identity
    unknown = set(args.exclude) - set(ids) - set(ids.values())
    if unknown:
        raise BuildError('Unknown excluded package: ' + ', '.join(sorted(unknown)))
    output.mkdir(parents=True, exist_ok=True)
    report = {'schemaVersion': 1, 'ok': False, 'scope': 'local-package-build',
              'version': args.version, 'repositoryId': args.repository_id,
              'publisher': args.publisher, 'license': args.license_id,
              'limitations': [
                  'No publication, installation, execution or migration is performed.',
                  'Run the actual Assi installer archive validation before publishing this build.',
                  'Capabilities and literal network hosts are declarations, not enforced isolation or exhaustive network analysis.',
                  'Credential pattern scanning is a limited preflight; manual privacy and license review is still required.',
                  'ZIP bytes are reproducible for identical source bytes and Python/zlib versions; source line endings are preserved.',
              ], 'packages': [], 'excluded': [], 'blocked': []}
    with tempfile.TemporaryDirectory(prefix='.assi-build-', dir=output.parent) as temporary:
        stage = Path(temporary)
        entries = []
        for folder in folders:
            identity = ids[folder.name]
            if folder.name in args.exclude or identity in args.exclude:
                target = load_json(folder / 'store.json').get('target', '')
                reason = 'Explicitly excluded by author'
                if target != ('modules/games/' if load_json(folder / 'store.json').get('kind') == 'game' else 'modules/') + str(identity):
                    reason += '; legacy runtime target alias is incompatible with v1; existing save IDs are unchanged'
                report['excluded'].append({'id': identity, 'folder': folder.name, 'reason': reason})
                continue
            try:
                entry, package_report = package(folder, stage, args, license_raw, ids)
                if entry is None:
                    report['blocked'].append(package_report)
                else:
                    entries.append(entry)
                    report['packages'].append(package_report)
            except (BuildError, OSError, ValueError, UnicodeError, TypeError) as error:
                report['blocked'].append({'id': identity, 'folder': folder.name, 'error': str(error)})
        index = {'schemaVersion': 1, 'repository': {'id': args.repository_id,
                  'title': args.repository_title, 'homepage': args.repository_homepage},
                 'packages': sorted(entries, key=lambda value: value['id'])}
        (stage / 'index.json').write_bytes(json_bytes(index))
        try:
            validate_written(stage / 'index.json', 'index')
        except BuildError as error:
            report['blocked'].append({'id': None, 'error': str(error)})
        report['ok'] = not report['blocked']
        if report['ok']:
            # A failed build never replaces the last complete index or artifacts.
            # Publication is an explicit subsequent step, outside this tool.
            for artifact in sorted(stage.iterdir()):
                os.replace(artifact, output / artifact.name)
            (output / 'catalog-validation.json').unlink(missing_ok=True)
            report['indexSha256'] = sha256((output / 'index.json').read_bytes())
        (output / 'build-report.json').write_bytes(json_bytes(report))
    return report


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument('--store', type=Path, required=True, help='Source root containing */store.json')
    result.add_argument('--out', type=Path, required=True, help='Build directory outside source root')
    result.add_argument('--base-download-url', required=True, help='HTTPS release asset base URL; no network request is made')
    result.add_argument('--publisher', required=True, help='Stable lowercase publisher ID')
    result.add_argument('--licensefile', type=Path, required=True, help='License or explicit no-license notice; copied as LICENSE')
    result.add_argument('--license-id', default='UNLICENSED', help='License metadata, defaults to UNLICENSED; does not grant rights')
    result.add_argument('--version', default='0.1.0', help='Package SemVer, independent from game saveVersion')
    result.add_argument('--repository-id', default='billerens.assi-store')
    result.add_argument('--repository-title', default='Assi Store')
    result.add_argument('--repository-homepage', default='https://github.com/Billerens/assi-store')
    result.add_argument('--exclude', action='append', default=[], help='Explicit package ID or source folder to omit, repeatable')
    return result


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        report = build(args)
        result = {'ok': report['ok'], 'packages': len(report['packages']), 'excluded': report['excluded'],
                  'blocked': report['blocked'], 'report': str(args.out / 'build-report.json')}
    except (BuildError, OSError, ValueError, UnicodeError) as error:
        result = {'ok': False, 'error': str(error)}
    print(json.dumps(result, ensure_ascii=True, indent=2))
    return 0 if result['ok'] else 2


if __name__ == '__main__':
    raise SystemExit(main())
