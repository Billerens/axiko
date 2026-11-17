"""Offline author build tests; package source is never imported or executed."""
import importlib.util
import io
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / 'build_catalog.py'
sys.path.insert(0, str(SCRIPT.parent))
SPEC = importlib.util.spec_from_file_location('build_catalog', SCRIPT)
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)
versions = builder.versioning


class CatalogBuildTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.store = self.base / 'store'
        self.store.mkdir()
        self.out = self.base / 'output'
        self.license = self.base / 'LICENSE'
        self.license.write_text('No project-wide open-source license has been selected.\n', encoding='utf-8')
        self.args = builder.parser().parse_args([
            '--store', str(self.store), '--out', str(self.out),
            '--base-download-url', 'https://example.test/releases/v0.1.0',
            '--publisher', 'author', '--licensefile', str(self.license),
            '--repository-id', 'author.demo', '--repository-title', 'Demo',
            '--repository-homepage', 'https://example.test/demo',
        ])

    def write(self, path, raw):
        path = self.store / path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw if isinstance(raw, bytes) else raw.encode('utf-8'))
        return path

    def package(self, folder='clock', identity=None, kind='module', target=None, requires=None, version='0.1.0'):
        identity = identity or folder
        target = target or ('modules/games/' if kind == 'game' else 'modules/') + identity
        described = {'id': identity, 'kind': kind, 'title': 'Clock', 'target': target, 'requires': requires or []}
        if version is not None:
            described['version'] = version
        self.write(folder + '/store.json', json.dumps(described))
        self.write(folder + '/README.md', '# Clock\nReadable source.\n')
        descriptor = {'description': 'A local clock.'}
        if kind == 'game':
            descriptor.update(id=identity, saveVersion=7, entry='ui/index.html')
        self.write(folder + ('/game.json' if kind == 'game' else '/module.json'), json.dumps(descriptor))
        self.write(folder + '/main.js', "exports.start = function () { return 'local'; };\n")
        self.write(folder + '/ui/index.html', '<html><body>Clock</body></html>\n')

    def build(self, valid=True):
        report = builder.build(self.args)
        self.assertEqual(valid, report['ok'], report)
        return report

    def test_deterministic_hashes_inventory_and_no_source_or_user_data_mutation(self):
        self.package()
        original = (self.store / 'clock/module.json').read_bytes()
        self.write('clock/tests/private-test.js', "throw new Error('must never execute');\n")
        self.write('clock/data/save.json', '{"important":true}')
        report = self.build()
        before = {path.name: path.read_bytes() for path in self.out.iterdir()}
        self.build()
        self.assertEqual(before, {path.name: path.read_bytes() for path in self.out.iterdir()})
        self.assertEqual(original, (self.store / 'clock/module.json').read_bytes())
        self.assertEqual('{"important":true}', (self.store / 'clock/data/save.json').read_text())
        index = json.loads((self.out / 'index.json').read_bytes())
        entry = index['packages'][0]
        raw = (self.out / 'clock-0.1.0.zip').read_bytes()
        self.assertEqual(entry['archive']['sha256'], builder.sha256(raw))
        self.assertEqual(entry['archive']['size'], len(raw))
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            names = archive.namelist()
            manifest = json.loads(archive.read('package.json'))
            self.assertEqual((self.out / 'clock-0.1.0.package.json').read_bytes(), archive.read('package.json'))
            self.assertEqual(len(names), entry['archive']['fileCount'])
            self.assertEqual(sum(item.file_size for item in archive.infolist()), entry['archive']['unpackedSize'])
            self.assertEqual(set(names), {'package.json'} | {item['path'] for item in manifest['files']})
            for item in manifest['files']:
                content = archive.read(item['path'])
                self.assertEqual(len(content), item['size'])
                self.assertEqual(builder.sha256(content), item['sha256'])
            self.assertEqual('clock', json.loads(archive.read('code/module.json'))['id'])
            self.assertFalse(any('/tests/' in name or '/data/' in name for name in names))
        self.assertEqual('add-runtime-id', report['packages'][0]['transforms'][0]['kind'])

    def test_runtime_id_alias_maps_dependencies_and_preserves_save_version(self):
        self.package('life-rpg', 'rpg', version='0.3.2')
        self.package('puzzle', kind='game', requires=['life-rpg'], version='1.4.0')
        self.build()
        manifest = json.loads((self.out / 'puzzle-1.4.0.package.json').read_bytes())
        # Each package has its own version; an optional neighbour is named as it is in the store now.
        self.assertEqual([{'id': 'rpg', 'version': '>=0.3.2'}], manifest['optionalDependencies'])
        self.assertEqual([], manifest['dependencies'])
        self.assertEqual('1.4.0', manifest['version'])
        with zipfile.ZipFile(self.out / 'puzzle-1.4.0.zip') as archive:
            self.assertEqual(7, json.loads(archive.read('code/game.json'))['saveVersion'])

    def test_version_is_the_packages_own_semver_from_store_json(self):
        for version in (None, '0.1', 'v1.0.0', '1.0.0.0', '01.0.0'):
            with self.subTest(version=version):
                self.package(version=version)
                self.assertIn('SemVer', self.build(False)['blocked'][0]['error'])
        self.package(version='2.0.0-rc.1')
        self.assertEqual('2.0.0-rc.1', self.build()['packages'][0]['version'])
        self.assertEqual([-1, 0, 1, -1, -1, 1, 0], [
            versions.compare('0.1.9', '0.1.10'), versions.compare('1.0.0', '1.0.0'), versions.compare('1.0.0', '1.0.0-rc.1'),
            versions.compare('1.0.0-alpha', '1.0.0-alpha.1'), versions.compare('1.0.0-alpha.2', '1.0.0-alpha.10'),
            versions.compare('1.0.0-beta', '1.0.0-alpha.1'), versions.compare('1.0.0+build.1', '1.0.0+build.2')])

    def publish(self):
        """What an author does after uploading a build: the index goes to the store and the build is recorded."""
        (self.store / 'index.json').write_bytes((self.out / 'index.json').read_bytes())
        return versions.record(self.store, self.out)

    def test_published_version_never_changes_its_contents_and_an_unchanged_package_keeps_its_release(self):
        self.args.base_download_url = None
        self.args.download_url_template = 'https://example.test/releases/{id}-v{version}'
        self.package()
        self.package('timer')
        first = self.build()
        self.assertEqual(['new', 'new'], [item['release'] for item in first['packages']])
        self.assertEqual('https://example.test/releases/clock-v0.1.0', first['toPublish'][0]['downloadBase'])
        self.assertEqual([{'id': 'clock', 'version': '0.1.0'}, {'id': 'timer', 'version': '0.1.0'}], self.publish()['recorded'])
        published = json.loads((self.store / 'index.json').read_bytes())['packages']
        self.assertTrue(versions.check(self.store, self.license.read_bytes(), [])['ok'])

        # Nothing changed: nothing is rebuilt, and the index keeps the published archive of each package.
        for stale in list(self.out.glob('*.zip')) + list(self.out.glob('*.package.json')):
            stale.unlink()
        again = self.build()
        self.assertEqual([], again['toPublish'])
        self.assertEqual(published, json.loads((self.out / 'index.json').read_bytes())['packages'])
        self.assertEqual([], list(self.out.glob('*.zip')))

        # A changed file under the published version stops the build and names the file.
        self.write('clock/main.js', "exports.start = function () { return 'fixed'; };\n")
        blocked = self.build(False)['blocked'][0]
        self.assertEqual('clock', blocked['id'])
        self.assertIn('code/main.js', blocked['error'])
        self.assertIn('raise "version"', blocked['error'])
        checked = versions.check(self.store, self.license.read_bytes(), [])
        self.assertFalse(checked['ok'])
        self.assertEqual(['clock'], [error['id'] for error in checked['errors']])

        # Going down is refused as well; going up releases this package alone.
        self.package(version='0.0.9')
        self.write('clock/main.js', "exports.start = function () { return 'fixed'; };\n")
        self.assertIn('lower than the published 0.1.0', self.build(False)['blocked'][0]['error'])
        self.package(version='0.1.1')
        self.write('clock/main.js', "exports.start = function () { return 'fixed'; };\n")
        self.assertEqual([{'id': 'clock', 'folder': 'clock', 'version': '0.1.1', 'published': '0.1.0', 'changedFiles': 1}],
                         versions.check(self.store, self.license.read_bytes(), [])['toRelease'])
        report = self.build()
        self.assertEqual({'clock': 'update', 'timer': 'unchanged'}, {item['id']: item['release'] for item in report['packages']})
        entries = {entry['id']: entry for entry in json.loads((self.out / 'index.json').read_bytes())['packages']}
        self.assertEqual('https://example.test/releases/clock-v0.1.1/clock-0.1.1.zip', entries['clock']['archive']['url'])
        self.assertEqual(published[1], entries['timer'])
        self.assertEqual([{'id': 'clock', 'version': '0.1.1'}], self.publish()['recorded'])
        self.assertEqual([], self.build()['toPublish'])

    def test_legacy_target_excluded_explicitly_without_renaming(self):
        self.package()
        self.package('sparks-ring', kind='game', target='modules/games/daily/2026-11-15')
        self.build(False)
        self.assertFalse((self.out / 'index.json').exists())
        self.args.exclude = ['sparks-ring']
        report = self.build()
        self.assertIn('legacy runtime target alias', report['excluded'][0]['reason'])
        self.assertEqual(1, len(json.loads((self.out / 'index.json').read_bytes())['packages']))

    def test_read_only_dictionary_assets_preserve_third_party_license(self):
        self.package('word-weaver', kind='game')
        self.write('word-weaver/_data/assets/dict.txt', 'слово\n')
        self.write('word-weaver/_data/assets/LICENSE.txt', 'Third-party terms unchanged\n')
        self.build()
        with zipfile.ZipFile(self.out / 'word-weaver-0.1.0.zip') as archive:
            self.assertEqual('слово\n'.encode(), archive.read('assets/dict.txt'))
            self.assertEqual(b'Third-party terms unchanged\n', archive.read('assets/LICENSE.txt'))
            self.assertFalse(any('data/' in name for name in archive.namelist()))

    def test_mapped_asset_collision_and_mutable_legacy_data_rejected(self):
        self.package()
        self.write('clock/assets/a.txt', 'code asset')
        self.write('clock/_data/assets/a.txt', 'duplicate asset')
        report = self.build(False)
        self.assertIn('collides', report['blocked'][0]['error'])
        (self.store / 'clock/_data/assets/a.txt').unlink()
        self.write('clock/_data/state.json', '{"old":true}')
        self.assertIn('mutable legacy _data', self.build(False)['blocked'][0]['error'])

    def test_secret_report_contains_locations_never_values_and_failed_build_preserves_previous(self):
        self.package()
        self.build()
        before = (self.out / 'index.json').read_bytes()
        secret = 'ghp_' + 'A' * 36
        self.write('clock/main.js', 'var secret = "' + secret + '";\n')
        report = self.build(False)
        self.assertNotIn(secret, json.dumps(report))
        self.assertEqual('github-token', report['blocked'][0]['audit'][0]['kind'])
        self.assertEqual('code/main.js', report['blocked'][0]['audit'][0]['path'])
        self.assertEqual(before, (self.out / 'index.json').read_bytes())

    def test_minified_remote_and_binary_sources_are_blocked(self):
        self.package()
        for filename, raw, reason in [('vendor.min.js', 'var readable = true;\n', 'minified'),
                                     ('ui/external.html', '<script src="https://example.test/run.js"></script>', 'remote executable'),
                                     ('lib.wasm', b'\x00asm', 'binary executable')]:
            with self.subTest(filename=filename):
                path = self.write('clock/' + filename, raw)
                self.assertIn(reason, self.build(False)['blocked'][0]['error'])
                path.unlink()

    def test_https_only_output_outside_store_and_unknown_exclusion(self):
        self.package()
        self.args.download_url_template = 'https://example.test/{id}'
        with self.assertRaises(builder.BuildError):
            builder.build(self.args)
        self.args.download_url_template = None
        self.args.base_download_url = 'http://example.test/releases'
        with self.assertRaises(builder.BuildError):
            builder.build(self.args)
        self.args.base_download_url = 'https://example.test/releases'
        self.args.out = self.store / 'dist'
        with self.assertRaises(builder.BuildError):
            builder.build(self.args)
        self.args.out = self.out
        self.args.exclude = ['typo']
        with self.assertRaises(builder.BuildError):
            builder.build(self.args)

    def test_sensitive_hidden_file_and_oversized_payload_are_blocked(self):
        self.package()
        hidden = self.write('clock/.env', 'API_KEY=redacted-not-inspected')
        report = self.build(False)
        self.assertEqual('sensitive-filename', report['blocked'][0]['audit'][0]['kind'])
        hidden.unlink()
        self.write('clock/assets/huge.txt', b'a' * (builder.metadata.MAX_FILE_BYTES + 1))
        self.assertIn('exceeds 2 MiB', self.build(False)['blocked'][0]['error'])


if __name__ == '__main__':
    unittest.main()
