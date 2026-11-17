#!/usr/bin/env python3
"""Package versions: SemVer in each store.json, and a published version never changes its contents.

`released/<id>.package.json` in the store is the manifest of the release of that package that is
published now: its version and the hash of every file. A package whose files differ from it must
carry a higher version; one that is the same keeps its version and its published archive.

    python tools/store/versions.py check  --store . --licensefile LICENSE --exclude sparks-ring
    python tools/store/versions.py record --store . --built ../assi-store-dist

`check` reads sources only: no network, nothing is built, installed or run. `record` copies the
manifests of a finished build into `released/` after its archives have been published.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
from pathlib import Path

import validate_metadata as metadata

SEMVER = re.compile(
    r'^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)'
    r'(?:-((?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*))?'
    r'(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$')
RELEASED = 'released'


class VersionError(ValueError):
    """What is wrong with a package's version, in words its author can act on."""


def parse(version):
    match = SEMVER.match(version) if isinstance(version, str) else None
    if not match:
        raise VersionError('store.json needs "version" in SemVer form MAJOR.MINOR.PATCH, for example "0.1.1"')
    return int(match.group(1)), int(match.group(2)), int(match.group(3)), match.group(4)


def compare(left: str, right: str) -> int:
    """SemVer precedence: -1, 0 or 1. Build metadata after `+` is ignored."""
    a, b = parse(left), parse(right)
    if a[:3] != b[:3]:
        return -1 if a[:3] < b[:3] else 1
    if a[3] == b[3]:
        return 0
    if a[3] is None or b[3] is None:
        return 1 if a[3] is None else -1  # A release is higher than its own pre-releases.
    for x, y in zip(a[3].split('.'), b[3].split('.')):
        if x == y:
            continue
        if x.isdigit() and y.isdigit():
            return -1 if int(x) < int(y) else 1
        if x.isdigit() != y.isdigit():
            return -1 if x.isdigit() else 1
        return -1 if x < y else 1
    return (len(a[3].split('.')) > len(b[3].split('.'))) - (len(a[3].split('.')) < len(b[3].split('.')))


def released_manifest(store: Path, identity: str):
    """The manifest of the published release of a package, or None when it was never published."""
    path = store / RELEASED / (identity + '.package.json')
    if not path.is_file():
        return None
    value, errors = metadata.parse_metadata(path.read_bytes())
    if errors or not isinstance(value, dict) or value.get('id') != identity:
        raise VersionError(f'{RELEASED}/{path.name} is not the manifest of {identity}')
    return value


def changed_files(inventory: dict[str, str], manifest: dict) -> list[str]:
    """Paths that were added, removed or changed since the published release."""
    published = {item['path']: item['sha256'] for item in manifest.get('files', [])}
    return sorted(path for path in set(inventory) | set(published) if inventory.get(path) != published.get(path))


def verdict(version, inventory: dict[str, str], manifest) -> tuple[str, list[str]]:
    """`new`, `unchanged` or `release`, with the files that changed; raises when the version is not honest."""
    parse(version)
    if manifest is None:
        return 'new', sorted(inventory)
    published = manifest['version']
    order = compare(version, published)
    changed = changed_files(inventory, manifest)
    if order < 0:
        raise VersionError(f'version {version} is lower than the published {published}; versions only go up')
    if order == 0 and changed:
        shown = ', '.join(changed[:5]) + (f' and {len(changed) - 5} more' if len(changed) > 5 else '')
        raise VersionError(f'contents changed since the published {published} ({shown}) but the version did not: '
                           f'raise "version" in store.json (patch for a fix, minor for a new feature, major for a breaking change)')
    return ('unchanged' if order == 0 else 'release'), changed


def check(store: Path, license_raw: bytes, exclude):
    import build_catalog as builder
    result = {'ok': True, 'scope': 'source-versions', 'unchanged': [], 'toRelease': [], 'errors': []}
    index = {}
    try:
        index = builder.published_entries(store / 'index.json')
    except (builder.BuildError, OSError, ValueError, KeyError) as error:
        result['errors'].append({'id': None, 'error': str(error)})
    seen = set()
    kinds = builder.load_kinds(store)
    for folder in sorted(path.parent for path in store.glob('*/store.json')):
        identity = builder.load_json(folder / 'store.json').get('id')
        if folder.name in exclude or identity in exclude:
            continue
        seen.add(identity)
        try:
            prepared = builder.prepare(folder, license_raw, kinds)
            manifest = released_manifest(store, identity)
            state, changed = verdict(prepared['version'], builder.inventory(prepared['payload']), manifest)
            published = manifest['version'] if manifest else None
            if identity in index and index[identity]['version'] != published:
                raise VersionError(f'index.json offers {index[identity]["version"]} but {RELEASED}/ records {published}: '
                                   f'run "versions.py record" after publishing a build')
            row = {'id': identity, 'folder': folder.name, 'version': prepared['version']}
            if state == 'unchanged':
                result['unchanged'].append(row)
            else:
                result['toRelease'].append({**row, 'published': published, 'changedFiles': len(changed)})
        except (VersionError, builder.BuildError, OSError, ValueError) as error:
            result['errors'].append({'id': identity, 'folder': folder.name, 'error': str(error)})
    for identity in sorted(set(index) - seen):
        result['errors'].append({'id': identity, 'error': 'index.json lists a package that has no source folder here'})
    result['ok'] = not result['errors']
    return result


def record(store: Path, built: Path):
    """After publication: what was built becomes what is published."""
    report, errors = metadata.parse_metadata((built / 'build-report.json').read_bytes())
    if errors or not isinstance(report, dict) or not report.get('ok'):
        raise VersionError('build-report.json is missing or reports a failed build; nothing recorded')
    target = store / RELEASED
    target.mkdir(exist_ok=True)
    recorded = []
    for package in report['packages']:
        if package.get('release') == 'unchanged':
            continue
        source = built / (package['id'] + '-' + package['version'] + '.package.json')
        manifest, errors = metadata.parse_metadata(source.read_bytes())
        if errors or manifest.get('id') != package['id'] or manifest.get('version') != package['version']:
            raise VersionError(f'{source.name} is not the manifest the build reported')
        shutil.copyfile(source, target / (package['id'] + '.package.json'))
        recorded.append({'id': package['id'], 'version': package['version']})
    return {'ok': True, 'recorded': recorded}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    checking = commands.add_parser('check', help='every package has a SemVer version that matches its contents')
    checking.add_argument('--store', type=Path, required=True)
    checking.add_argument('--licensefile', type=Path, required=True, help='The same file the build copies into every package')
    checking.add_argument('--exclude', action='append', default=[], help='Package ID or folder left out of the catalog, repeatable')
    recording = commands.add_parser('record', help='remember a published build as the released state')
    recording.add_argument('--store', type=Path, required=True)
    recording.add_argument('--built', type=Path, required=True, help='Output directory of build_catalog.py')
    args = parser.parse_args(argv)
    try:
        if args.command == 'check':
            result = check(args.store.resolve(), args.licensefile.read_bytes(), args.exclude)
        else:
            result = record(args.store.resolve(), args.built.resolve())
    except (VersionError, OSError, ValueError) as error:
        result = {'ok': False, 'error': str(error)}
    print(json.dumps(result, ensure_ascii=True, indent=2))
    return 0 if result['ok'] else 2


if __name__ == '__main__':
    raise SystemExit(main())
