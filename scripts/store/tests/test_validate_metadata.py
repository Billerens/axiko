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
        cls.page = json.loads((examples / 'page.json').read_text(encoding='utf-8'))
        cls.details = json.loads((examples / 'details.json').read_text(encoding='utf-8'))

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
        for kind in ['index', 'page', 'details', 'package']:
            schema = json.loads((ROOT / 'docs' / 'marketplace' / (kind + '.schema.json')).read_text(encoding='utf-8'))
            Draft202012Validator.check_schema(schema)
            result = self.check(getattr(self, kind), kind)
            self.assertTrue(result['ok'], result)
        for key in ['id', 'kind', 'version', 'publisher', 'license']:
            self.assertEqual(self.page['packages'][0][key], self.package[key])
        self.assertEqual(self.page['packages'][0]['archive']['fileCount'], len(self.package['files']) + 1)

    def test_missing_index_fields_and_unknown_properties_fail_closed(self):
        for field in ['schemaVersion', 'repository', 'packageCount', 'pages']:
            value = copy.deepcopy(self.index)
            del value[field]
            self.invalid(value, 'index')
        value = copy.deepcopy(self.index)
        value['limits'] = {'size': 999999999}
        self.invalid(value, 'index')
        for kind, extra in [('page', 'limits'), ('details', 'html')]:
            value = copy.deepcopy(getattr(self, kind))
            value[extra] = 'x'
            self.invalid(value, kind)
        card = copy.deepcopy(self.page)
        card['packages'][0]['screenshots'] = ['https://example.org/a.png']
        self.invalid(card, 'page', '$.packages[0]')
        value = copy.deepcopy(self.package)
        value['installHook'] = 'fetch("https://example.org/")'
        self.invalid(value)

    def test_unknown_versions_and_non_semver_are_rejected(self):
        for kind in ['index', 'page', 'details', 'package']:
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
        value = copy.deepcopy(self.page)
        value['packages'].append(copy.deepcopy(value['packages'][0]))
        self.invalid(value, 'page', '$.packages[1].id')

    def test_https_rejects_plain_http_credentials_fragments_and_malformed_hosts(self):
        for url in ['http://example.org/index.json', 'https://user:secret@example.org/a',
                    'https://@example.org/a', 'https://example.org/a#section',
                    'https://example.org/a#', 'https://example.org:99999/a',
                    'https://bad%20host/a', 'https://example.org\\evil/a']:
            value = copy.deepcopy(self.page)
            value['packages'][0]['manifestUrl'] = url
            self.invalid(value, 'page', '$.packages[0].manifestUrl')
        value['packages'][0]['manifestUrl'] = 'https://example.org/a?revision=1'
        self.assertTrue(self.check(value, 'page')['ok'])

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

    def test_kind_is_a_name_and_a_package_names_one_version_of_its_hosts_rules(self):
        # The schema keeps no list of kinds: which kinds exist and what they need is for the modules that host them.
        for kind, extra in [('game', {'gamesEngine': 1}), ('theme', {}), ('module', {})]:
            value = copy.deepcopy(self.package)
            value['kind'] = kind
            value['runtime'].update(extra)
            self.assertTrue(self.check(value)['ok'], kind)
            page = copy.deepcopy(self.page)
            page['packages'][0]['kind'] = kind
            page['packages'][0]['compatibility'].update(extra)
            self.assertTrue(self.check(page, 'page')['ok'], kind)
        for kind in ['Game', 'two words', '', '9lives', 'x' * 33]:
            value = copy.deepcopy(self.package)
            value['kind'] = kind
            self.invalid(value, path='$.kind')
        for runtime in [{'axikoApi': 1, 'gamesEngine': 0}, {'axikoApi': 1, 'gamesEngine': '1'}, {'axikoApi': 1, 'a': 1, 'b': 1},
                        {'axikoApi': 1, 'Games-Engine': 1}, {'gamesEngine': 1}]:
            value = copy.deepcopy(self.package)
            value['runtime'] = runtime
            self.invalid(value, path='$.runtime')
            page = copy.deepcopy(self.page)
            page['packages'][0]['compatibility'] = {('minAxikoApi' if key == 'axikoApi' else key): number for key, number in runtime.items()}
            self.invalid(page, 'page', '$.packages[0].compatibility')

    def test_limits_include_manifest_and_cannot_be_raised_by_metadata(self):
        for field, limit in [('size', 5242880), ('unpackedSize', 20971520), ('fileCount', 512)]:
            value = copy.deepcopy(self.page)
            value['packages'][0]['archive'][field] = limit + 1
            self.invalid(value, 'page', '$.packages[0].archive.' + field)
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
        value = copy.deepcopy(self.page)
        item = value['packages'][0]
        item['title'] = item['summary'] = item['license'] = item['publisher'] = 'x'
        item['manifestUrl'] = item['archive']['url'] = 'https://e/a'
        for key in ('tags', 'locales', 'icon', 'updated', 'details'):
            item.pop(key)
        value['packages'] = [dict(item, id=f'p{i}') for i in range(2001)]
        raw = json.dumps(value, separators=(',', ':')).encode()
        self.assertLessEqual(len(raw), validator.MAX_METADATA_BYTES)
        result = self.invalid(None, 'page', path='$.packages', raw=raw)
        self.assertLess(len(json.dumps(result)), 1000)

    def test_duplicate_json_keys_non_json_numbers_and_invalid_utf8_are_rejected(self):
        raw = json.dumps(self.index).replace('"schemaVersion": 1', '"schemaVersion": 1, "schemaVersion": 1').encode()
        self.invalid(None, 'index', path='$.schemaVersion', raw=raw)
        raw = json.dumps(self.page).replace('"kind": "module"', '"kind": "module", "kind": "game"').encode()
        self.invalid(None, 'page', path='$.packages[0].kind', raw=raw)
        for raw in [b'{"value":NaN}', b'{"value":Infinity}', b'\xff', b'\xef\xbb\xbf{}', br'{"value":"\ud800"}']:
            self.invalid(None, raw=raw)

    def test_head_pages_and_listing_files_stay_inside_the_catalog(self):
        value = copy.deepcopy(self.index)
        value['packageCount'] = 2
        self.invalid(value, 'index', '$.packageCount')
        value = copy.deepcopy(self.index)
        value['pages'].append(copy.deepcopy(value['pages'][0]))
        value['packageCount'] = 2
        self.invalid(value, 'index', '$.pages[1].path')
        for bad in ['../p.json', '/catalog/p.json', 'https://evil.example/p.json', 'catalog\\p.json', 'catalog/.p.json', 'catalog/p.txt']:
            value = copy.deepcopy(self.index)
            value['pages'][0]['path'] = bad
            self.invalid(value, 'index', '$.pages[0].path')
        empty = copy.deepcopy(self.index)
        empty.update(packageCount=0, pages=[])
        self.assertTrue(self.check(empty, 'index')['ok'])
        for field, bad in [('icon', '../icon.svg'), ('details', 'https://evil.example/d.json')]:
            value = copy.deepcopy(self.page)
            value['packages'][0][field]['path'] = bad
            self.invalid(value, 'page', '$.packages[0].' + field)
        for field, bad in [('tags', ['Big']), ('updated', '17.11.2026')]:
            value = copy.deepcopy(self.page)
            value['packages'][0][field] = bad
            self.invalid(value, 'page', '$.packages[0].' + field)
        value = copy.deepcopy(self.page)
        value['packages'][0]['icon']['type'] = 'text/html'
        self.invalid(value, 'page', '$.packages[0].icon.type')
        value = copy.deepcopy(self.page)
        value['packages'][0]['icon']['size'] = 131073
        self.invalid(value, 'page', '$.packages[0].icon.size')

    def test_details_limit_texts_pictures_and_links(self):
        value = copy.deepcopy(self.details)
        value['screenshots'][0]['type'] = 'image/svg+xml'
        self.invalid(value, 'details', '$.screenshots[0].type')
        value = copy.deepcopy(self.details)
        value['screenshots'].append(copy.deepcopy(value['screenshots'][0]))
        self.invalid(value, 'details', '$.screenshots[1].path')
        value = copy.deepcopy(self.details)
        value['changes'].append(copy.deepcopy(value['changes'][0]))
        self.invalid(value, 'details', '$.changes')
        value = copy.deepcopy(self.details)
        value['links']['support'] = 'http://example.org/help'
        self.invalid(value, 'details', '$.links.support')
        value = copy.deepcopy(self.details)
        value['description'] = 'x' * 20001
        self.invalid(value, 'details', '$.description')
        minimal = {key: self.details[key] for key in ('schemaVersion', 'id', 'version')}
        self.assertTrue(self.check(minimal, 'details')['ok'])

    def test_a_card_gives_its_texts_in_other_languages_and_tags_stay_what_is_counted(self):
        self.assertEqual(['ru'], list(self.page['packages'][0]['locales']))
        for field, bad in [('title', ''), ('summary', 'x' * 601), ('title', 'x' * 121), ('tags', ['one', 'one'])]:
            value = copy.deepcopy(self.page)
            value['packages'][0]['locales']['ru'][field] = bad
            self.invalid(value, 'page', '$.packages[0].locales.ru.' + field)
        for bad in ['RU', 'russian', 'r', 'ru_RU', 'ru-', '']:
            value = copy.deepcopy(self.page)
            value['packages'][0]['locales'] = {bad: {'title': 'Счётчик'}}
            self.invalid(value, 'page', '$.packages[0].locales')
        value = copy.deepcopy(self.page)
        value['packages'][0]['locales'] = {'pt-BR': {'title': 'Contador'}, 'zh-Hans': {'summary': '计数器'}, 'de': {'title': 'Zähler'}}
        self.assertTrue(self.check(value, 'page')['ok'])
        # Only what a language translates; an empty locale and an unknown field say nothing or something unread.
        for bad in [{}, {'description': 'x'}]:
            value = copy.deepcopy(self.page)
            value['packages'][0]['locales']['ru'] = bad
            self.invalid(value, 'page', '$.packages[0].locales.ru')
        value = copy.deepcopy(self.page)
        value['packages'][0]['locales'] = {'en': {'title': 'Counter'}}
        self.invalid(value, 'page', '$.packages[0].locales.en')
        value = copy.deepcopy(self.page)
        value['packages'][0]['locales'] = {'l%02d' % number: {'title': 'x'} for number in range(17)}
        self.invalid(value, 'page', '$.packages[0].locales')
        # The words of a language go with the base tags one to one: the base tag is the identity.
        value = copy.deepcopy(self.page)
        value['packages'][0]['locales']['ru']['tags'] = ['пример']
        self.invalid(value, 'page', '$.packages[0].locales.ru.tags')
        value = copy.deepcopy(self.page)
        value['packages'][0]['locales']['ru']['tags'] = ['пример', ' счётчик']
        self.invalid(value, 'page', '$.packages[0].locales.ru.tags[1]')
        value = copy.deepcopy(self.page)
        del value['packages'][0]['tags']
        self.invalid(value, 'page', '$.packages[0].locales.ru.tags')
        # Words are text, not identities: capitals are fine in a locale, and a locale may leave the tags in English.
        value = copy.deepcopy(self.page)
        value['packages'][0]['locales']['ru']['tags'] = ['Пример', 'Счётчик']
        self.assertTrue(self.check(value, 'page')['ok'])
        del value['packages'][0]['locales']['ru']['tags']
        self.assertTrue(self.check(value, 'page')['ok'])

    def test_details_give_description_changes_and_captions_in_other_languages(self):
        self.assertEqual(['ru'], list(self.details['locales']))
        value = copy.deepcopy(self.details)
        value['locales']['ru']['changes']['2.0.0'] = 'Нет такой версии.'
        self.invalid(value, 'details', '$.locales.ru.changes')
        value = copy.deepcopy(self.details)
        value['locales']['ru']['captions'] = ['Один', 'Два']
        self.invalid(value, 'details', '$.locales.ru.captions')
        value = copy.deepcopy(self.details)
        del value['screenshots'][0]['caption']
        self.invalid(value, 'details', '$.locales.ru.captions[0]')
        value = copy.deepcopy(self.details)
        del value['description']
        self.invalid(value, 'details', '$.locales.ru.description')
        value = copy.deepcopy(self.details)
        value['locales']['ru']['captions'] = [None]
        value['locales']['ru']['changes'] = {}
        self.assertTrue(self.check(value, 'details')['ok'])
        for field, bad in [('description', ''), ('description', 'x' * 20001), ('captions', ['x' * 201]), ('changes', {'1.0.0': 'x' * 4001}),
                           ('changes', {'one': 'Первый.'}), ('unknown', 'x')]:
            value = copy.deepcopy(self.details)
            value['locales']['ru'][field] = bad
            self.invalid(value, 'details', '$.locales.ru')
        value = copy.deepcopy(self.details)
        value['locales'] = {'en': {'description': 'x'}}
        self.invalid(value, 'details', '$.locales.en')
        value = copy.deepcopy(self.details)
        value['locales'] = {'ru': {}}
        self.invalid(value, 'details', '$.locales.ru')

    def mod(self):
        """The manifest of a mod: no entry, the package it changes, and what its sample was made on."""
        value = copy.deepcopy(self.package)
        del value['entry']
        value.update(kind='mod', extends='example.clock', reference={'version': '1.0.0', 'base': {'main.js': '1' * 64}, 'added': ['ui/night.css'], 'removed': []})
        value['files'] = [record for record in value['files'] if record['path'] in ('README.md', 'LICENSE')] + [
            {'path': path, 'size': 1, 'sha256': '1' * 64} for path in ('mod/brief.md', 'reference/main.js', 'reference/ui/night.css', 'base/main.js')]
        return value

    def test_mod_manifest_names_its_target_and_what_its_sample_was_made_on(self):
        self.assertTrue(self.check(self.mod())['ok'], self.check(self.mod()))
        removing = self.mod()
        removing['reference']['removed'] = ['old.js']
        self.assertTrue(self.check(removing)['ok'])
        spoiled = {
            '$': lambda value: value.update(entry='code/main.js'),
            '$ without reference': lambda value: value.pop('reference'),
            '$.extends': lambda value: value.update(extends=value['id']),
            '$.files': lambda value: value['files'].pop(2),
            '$.reference.base': lambda value: value['reference']['base'].update({'other.js': '2' * 64}),
            '$.reference.added': lambda value: value['reference'].update(added=[]),
            '$.reference.removed': lambda value: value['reference'].update(removed=['main.js']),
            '$.reference.version': lambda value: value['reference'].update(version='latest'),
            '$.files[2]': lambda value: value['files'][2].update(path='code/brief.md'),
            '$.reference.archiveSha256': lambda value: value['reference'].update(archiveSha256='abc'),
        }
        for path, spoil in spoiled.items():
            with self.subTest(path=path):
                value = self.mod()
                spoil(value)
                self.invalid(value, path=path.split(' ')[0])
        # What is a mod's is not an ordinary package's, and the other way round.
        plain = copy.deepcopy(self.package)
        plain['extends'] = 'example.clock'
        self.invalid(plain)
        plain = copy.deepcopy(self.package)
        plain['files'].append({'path': 'reference/main.js', 'size': 1, 'sha256': '1' * 64})
        self.invalid(plain, path='$.files')
        page = copy.deepcopy(self.page)
        page['packages'][0]['kind'] = 'mod'
        self.invalid(page, 'page', '$.packages[0]')
        page['packages'][0]['extends'] = 'example.clock'
        self.assertTrue(self.check(page, 'page')['ok'])
        page['packages'][0]['kind'] = 'module'
        self.invalid(page, 'page', '$.packages[0]')

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
