"""Contract tests for metadata only: no network, archive extraction or package code."""
import copy
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator

SCRIPT = Path(__file__).resolve().parents[1] / 'validate_metadata.py'
ROOT = SCRIPT.parents[2]
SPEC = importlib.util.spec_from_file_location('validate_metadata', SCRIPT)
validator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validator)


class MetadataTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        examples = ROOT / 'docs' / 'marketplace' / 'examples'
        cls.index = json.loads((examples / 'index.json').read_text(encoding='utf-8'))
        cls.package = json.loads((examples / 'package.json').read_text(encoding='utf-8'))

    def check(self, value, kind='package', raw=None):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / (kind + '.json')
            path.write_bytes(raw if raw is not None else json.dumps(value, ensure_ascii=False).encode('utf-8'))
            return validator.validate_path(path, kind)

    def invalid(self, value, kind='package', path=None, raw=None):
        result = self.check(value, kind, raw)
        self.assertFalse(result['ok'], result)
        self.assertEqual(result['scope'], 'metadata-only')
        self.assertTrue(result['errors'])
        self.assertTrue(all(error['path'].startswith('$') for error in result['errors']))
        if path:
            self.assertTrue(any(error['path'].startswith(path) for error in result['errors']), result)
        return result

    def test_schemas_are_valid_2020_12_and_examples_are_consistent_metadata(self):
        for kind in ['index', 'package']:
            schema = json.loads((ROOT / 'docs' / 'marketplace' / (kind + '.schema.json')).read_text())
            Draft202012Validator.check_schema(schema)
            result = self.check(getattr(self, kind), kind)
            self.assertTrue(result['ok'], result)
        for key in ['id', 'kind', 'version', 'publisher', 'license']:
            self.assertEqual(self.index['packages'][0][key], self.package[key])
        self.assertEqual(self.index['packages'][0]['archive']['fileCount'], len(self.package['files']) + 1)

    def test_missing_index_fields_and_unknown_properties_fail_closed(self):
        for field in ['schemaVersion', 'repository', 'packages']:
            value = copy.deepcopy(self.index)
            del value[field]
            self.invalid(value, 'index')
        value = copy.deepcopy(self.index)
        value['limits'] = {'size': 999999999}
        self.invalid(value, 'index')
        value = copy.deepcopy(self.package)
        value['installHook'] = 'fetch("https://example.org/")'
        self.invalid(value)

    def test_unknown_versions_and_non_semver_are_rejected(self):
        for kind in ['index', 'package']:
            value = copy.deepcopy(getattr(self, kind))
            value['schemaVersion'] = 2
            self.invalid(value, kind, '$.schemaVersion')
        for version in ['latest', 'v1.0.0', '01.0.0', '1.0', '1.0.0-01']:
            value = copy.deepcopy(self.package)
            value['version'] = version
            self.invalid(value, path='$.version')
        value['version'] = '1.0.0-beta.2+local.42'
        self.assertTrue(self.check(value)['ok'])

    def test_duplicate_package_ids_are_rejected(self):
        value = copy.deepcopy(self.index)
        value['packages'].append(copy.deepcopy(value['packages'][0]))
        self.invalid(value, 'index', '$.packages[1].id')

    def test_https_rejects_plain_http_credentials_fragments_and_malformed_hosts(self):
        for url in ['http://example.org/index.json', 'https://user:secret@example.org/a',
                    'https://@example.org/a', 'https://example.org/a#section',
                    'https://example.org/a#', 'https://example.org:99999/a',
                    'https://bad%20host/a', 'https://example.org\\evil/a']:
            value = copy.deepcopy(self.index)
            value['packages'][0]['manifestUrl'] = url
            self.invalid(value, 'index', '$.packages[0].manifestUrl')
        value['packages'][0]['manifestUrl'] = 'https://example.org/a?revision=1'
        self.assertTrue(self.check(value, 'index')['ok'])

    def test_relative_paths_reject_traversal_ambiguity_and_arbitrary_destinations(self):
        paths = ['code/../main.js', '../main.js', '/code/main.js', 'C:/main.js',
                 'code\\main.js', 'code//main.js', 'code/./main.js', 'code/.secret',
                 'code/name.', 'code/name ', 'code/CON.js', 'data/settings.json',
                 'package.json', 'code/main.js:stream']
        for path in paths:
            value = copy.deepcopy(self.package)
            value['files'][2]['path'] = path
            self.invalid(value, path='$.files')
        value = copy.deepcopy(self.package)
        value['entry'] = 'assets/main.js'
        self.invalid(value, path='$.entry')

    def test_casefold_and_unicode_collisions_are_rejected(self):
        value = copy.deepcopy(self.package)
        value['files'].append({'path': 'code/MAIN.js', 'size': 1, 'sha256': '1' * 64})
        self.invalid(value, path='$.files[3].path')
        value = copy.deepcopy(self.package)
        for path in ['assets/caf\u00e9.txt', 'assets/cafe\u0301.txt']:
            value['files'].append({'path': path, 'size': 1, 'sha256': '1' * 64})
        result = self.invalid(value)
        self.assertTrue(any('NFC' in error['message'] for error in result['errors']))

    def test_file_directory_conflicts_and_directory_case_aliases_are_rejected(self):
        for paths in [('code/main.js/child.js',), ('assets/Art/a.svg', 'assets/art/b.svg')]:
            value = copy.deepcopy(self.package)
            value['files'] += [{'path': path, 'size': 1, 'sha256': '1' * 64} for path in paths]
            self.invalid(value, path='$.files')

    def test_readme_license_entry_and_readable_source_are_mandatory(self):
        for path in ['README.md', 'LICENSE', 'code/main.js']:
            value = copy.deepcopy(self.package)
            value['files'] = [record for record in value['files'] if record['path'] != path]
            self.invalid(value)
        value = copy.deepcopy(self.package)
        value['sourceFormat'] = 'minified'
        self.invalid(value, path='$.sourceFormat')
        value = copy.deepcopy(self.package)
        value['entry'] = 'code/missing.js'
        self.invalid(value, path='$.entry')

    def test_game_requires_explicit_engine_compatibility(self):
        value = copy.deepcopy(self.package)
        value['kind'] = 'game'
        self.invalid(value, path='$.runtime')
        value['runtime']['gamesEngine'] = 1
        self.assertTrue(self.check(value)['ok'])
        index = copy.deepcopy(self.index)
        index['packages'][0]['kind'] = 'game'
        self.invalid(index, 'index', '$.packages[0].compatibility')
        index['packages'][0]['compatibility']['gamesEngine'] = 1
        self.assertTrue(self.check(index, 'index')['ok'])

    def test_limits_include_manifest_and_cannot_be_raised_by_metadata(self):
        for field, limit in [('size', 5242880), ('unpackedSize', 20971520), ('fileCount', 512)]:
            value = copy.deepcopy(self.index)
            value['packages'][0]['archive'][field] = limit + 1
            self.invalid(value, 'index', '$.packages[0].archive.' + field)
        value = copy.deepcopy(self.package)
        value['files'][2]['size'] = 2097153
        self.invalid(value, path='$.files[2].size')
        value = copy.deepcopy(self.package)
        value['files'] += [{'path': f'assets/f{i}', 'size': 0, 'sha256': '0' * 64} for i in range(509)]
        self.invalid(value, path='$.files')  # 512 inventory records + manifest = 513.
        value['files'].pop()
        self.assertTrue(self.check(value)['ok'])  # 511 inventory records + manifest = 512.
        value = copy.deepcopy(self.package)
        value['files'] += [{'path': f'assets/data{i}', 'size': 2097152, 'sha256': '0' * 64} for i in range(10)]
        self.invalid(value, path='$.files')
        self.invalid(None, 'index', raw=b' ' * (validator.MAX_METADATA_BYTES + 1))

    def test_index_count_limit_produces_a_compact_error(self):
        value = copy.deepcopy(self.index)
        item = value['packages'][0]
        item['title'] = item['summary'] = item['license'] = item['publisher'] = 'x'
        item['manifestUrl'] = item['archive']['url'] = 'https://e/a'
        item.pop('tags')
        value['packages'] = [dict(item, id=f'p{i}') for i in range(2001)]
        raw = json.dumps(value, separators=(',', ':')).encode()
        self.assertLessEqual(len(raw), validator.MAX_METADATA_BYTES)
        result = self.invalid(None, 'index', path='$.packages', raw=raw)
        self.assertLess(len(json.dumps(result)), 1000)

    def test_duplicate_json_keys_non_json_numbers_and_invalid_utf8_are_rejected(self):
        raw = json.dumps(self.index).replace('"schemaVersion": 1', '"schemaVersion": 1, "schemaVersion": 1').encode()
        self.invalid(None, 'index', path='$.schemaVersion', raw=raw)
        raw = json.dumps(self.index).replace('"kind": "module"', '"kind": "module", "kind": "game"').encode()
        self.invalid(None, 'index', path='$.packages[0].kind', raw=raw)
        for raw in [b'{"value":NaN}', b'{"value":Infinity}', b'\xff', b'\xef\xbb\xbf{}', br'{"value":"\ud800"}']:
            self.invalid(None, raw=raw)

    def test_dependency_identity_and_supported_range_syntax(self):
        value = copy.deepcopy(self.package)
        value['dependencies'] = [{'id': 'example.library', 'version': '>=1.0.0'}]
        self.assertTrue(self.check(value)['ok'])
        value['optionalDependencies'] = copy.deepcopy(value['dependencies'])
        self.invalid(value, path='$.optionalDependencies[0].id')
        value['optionalDependencies'] = []
        value['dependencies'][0]['id'] = value['id']
        self.invalid(value, path='$.dependencies[0].id')
        value['dependencies'][0] = {'id': 'example.library', 'version': 'latest'}
        self.invalid(value, path='$.dependencies[0].version')

    def test_cli_exit_codes_and_json_scope_are_explicit(self):
        good = ROOT / 'docs' / 'marketplace' / 'examples' / 'index.json'
        result = subprocess.run([sys.executable, str(SCRIPT), '--index', str(good)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)['scope'], 'metadata-only')
        with tempfile.TemporaryDirectory() as directory:
            bad = Path(directory) / 'bad.json'
            bad.write_text('{"schemaVersion":42}', encoding='utf-8')
            result = subprocess.run([sys.executable, str(SCRIPT), '--package', str(bad)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 2, result.stderr)
            self.assertFalse(json.loads(result.stdout)['ok'])


if __name__ == '__main__':
    unittest.main()
