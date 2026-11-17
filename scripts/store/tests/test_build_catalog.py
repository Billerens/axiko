"""Offline author build tests; package source is never imported or executed."""
import importlib.util
import io
import json
import shutil
import struct
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
listing = builder.listing


def png(width, height):
    """The start of a PNG file: enough for its type and size to be read."""
    return b'\x89PNG\r\n\x1a\n' + struct.pack('>I', 13) + b'IHDR' + struct.pack('>II', width, height) + b'\x08\x06\x00\x00\x00' + b'\x00' * 4


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
        # What the games module declares about itself, repeated for the builder.
        (self.store / 'kinds.json').write_text(json.dumps({'game': {'host': 'games', 'descriptor': 'game.json', 'engine': 'gamesEngine', 'version': 1}}))
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

    def package(self, folder='clock', identity=None, kind='module', target=None, requires=None, version='0.1.0', descriptor_name=None):
        identity = identity or folder
        target = target or ('modules/' if kind == 'module' else 'modules/' + kind + 's/') + identity
        described = {'id': identity, 'kind': kind, 'title': 'Clock', 'target': target, 'requires': requires or []}
        if version is not None:
            described['version'] = version
        self.write(folder + '/store.json', json.dumps(described))
        self.write(folder + '/README.md', '# Clock\nReadable source.\n')
        descriptor = {'description': 'A local clock.'}
        if kind != 'module':
            descriptor.update(id=identity, saveVersion=7, entry='ui/index.html')
        self.write(folder + ('/module.json' if kind == 'module' else '/' + (descriptor_name or kind + '.json')), json.dumps(descriptor))
        self.write(folder + '/main.js', "exports.start = function () { return 'local'; };\n")
        self.write(folder + '/ui/index.html', '<html><body>Clock</body></html>\n')

    def cards(self, base=None):
        """The packages of a built catalog: its head names the pages, the pages hold the cards."""
        base = base or self.out
        head = json.loads((base / 'index.json').read_bytes())
        found = []
        for page in head['pages']:
            raw = (base / page['path']).read_bytes()
            self.assertEqual((page['sha256'], page['size']), (builder.sha256(raw), len(raw)))
            packages = json.loads(raw)['packages']
            self.assertEqual(page['count'], len(packages))
            found.extend(packages)
        self.assertEqual(head['packageCount'], len(found))
        return sorted(found, key=lambda card: card['id'])

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
        before = {path.relative_to(self.out).as_posix(): path.read_bytes() for path in self.out.rglob('*') if path.is_file()}
        self.build()
        self.assertEqual(before, {path.relative_to(self.out).as_posix(): path.read_bytes() for path in self.out.rglob('*') if path.is_file()})
        self.assertEqual(original, (self.store / 'clock/module.json').read_bytes())
        self.assertEqual('{"important":true}', (self.store / 'clock/data/save.json').read_text())
        entry = self.cards()[0]
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
        shutil.rmtree(self.store / 'catalog', ignore_errors=True)
        shutil.copytree(self.out / 'catalog', self.store / 'catalog')
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
        published = self.cards(self.store)
        self.assertTrue(versions.check(self.store, self.license.read_bytes(), [])['ok'])

        # Nothing changed: nothing is rebuilt, and the index keeps the published archive of each package.
        for stale in list(self.out.glob('*.zip')) + list(self.out.glob('*.package.json')):
            stale.unlink()
        again = self.build()
        self.assertEqual([], again['toPublish'])
        self.assertEqual(published, self.cards())
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
        entries = {entry['id']: entry for entry in self.cards()}
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
        self.assertEqual(1, len(self.cards()))

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

    def test_listing_is_published_in_place_and_stays_out_of_the_archive(self):
        self.package('clock', kind='game')
        icon = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 8 8"><path d="M1 1h6v6H1z"/></svg>'
        self.write('clock/icon.svg', icon)
        self.write('clock/store/shots/1.png', png(1080, 2340))
        self.write('clock/store/shots/2.png', png(720, 1280))
        self.write('clock/store/description.md', '# Clock\n\nShows the **time**.\n')
        self.write('clock/store/changelog.md', '# Changes\n\n## 0.1.0 — 2026-11-17\n\n- First release.\n\n## 0.0.9\n\nA preview.\n')
        described = json.loads((self.store / 'clock/store.json').read_text())
        described.update(summary='A clock for the home screen.', tags=['time', 'часы'], links={'homepage': 'https://example.test/clock'},
                         screenshots=[{'file': 'store/shots/2.png', 'caption': 'Night'}, 'store/shots/1.png'])
        self.write('clock/store.json', json.dumps(described))
        report = self.build()
        card = self.cards()[0]
        self.assertEqual(('A clock for the home screen.', ['time', 'часы'], '2026-11-17'), (card['summary'], card['tags'], card['updated']))
        self.assertEqual({'path': 'clock/icon.svg', 'sha256': builder.sha256(icon.encode()), 'size': len(icon), 'type': 'image/svg+xml'}, card['icon'])
        raw = (self.out / card['details']['path']).read_bytes()
        self.assertEqual((builder.sha256(raw), len(raw), 'catalog/details/clock.json'), (card['details']['sha256'], card['details']['size'], card['details']['path']))
        details = json.loads(raw)
        self.assertEqual(('clock', '0.1.0', '# Clock\n\nShows the **time**.'), (details['id'], details['version'], details['description']))
        self.assertEqual([('clock/store/shots/2.png', 720, 1280, 'Night'), ('clock/store/shots/1.png', 1080, 2340, None)],
                         [(shot['path'], shot['width'], shot['height'], shot.get('caption')) for shot in details['screenshots']])
        self.assertEqual([{'version': '0.1.0', 'date': '2026-11-17', 'notes': '- First release.'}, {'version': '0.0.9', 'notes': 'A preview.'}], details['changes'])
        self.assertEqual({'homepage': 'https://example.test/clock'}, details['links'])
        self.assertEqual({'icon': True, 'tags': 2, 'description': True, 'screenshots': 2, 'changes': 2, 'notes': []}, report['packages'][0]['listing'])
        # The listing is what the store shows, not what gets installed: nothing of store/ is in the archive.
        with zipfile.ZipFile(self.out / 'clock-0.1.0.zip') as archive:
            self.assertFalse(any('store/' in name for name in archive.namelist()))
            self.assertIn('code/icon.svg', archive.namelist())
        # Editing the listing does not make a new release of the package.
        self.publish()
        self.write('clock/store/description.md', 'A new text.')
        again = self.build()
        self.assertEqual(([], 'unchanged'), (again['toPublish'], again['packages'][0]['release']))
        self.assertNotEqual(card['details']['sha256'], self.cards()[0]['details']['sha256'])
        self.assertEqual(card['archive'], self.cards()[0]['archive'])

    def test_listing_material_is_checked_by_content_and_limits(self):
        cases = [
            ('store/icon.svg', '<svg xmlns="http://www.w3.org/2000/svg"><script>steal()</script></svg>', {}, 'drawing only'),
            ('store/icon.png', b'not a picture', {}, 'by its content'),
            ('store/icon.png', png(8, 8) + b'\x00' * listing.MAX_ICON_BYTES, {}, 'the limit is'),
            ('store/shots/1.png', '<svg xmlns="http://www.w3.org/2000/svg"/>', {}, 'by its content'),
            ('store/changelog.md', '## next\n\nSoon.\n', {}, 'changelog heading'),
            ('store/changelog.md', '## 0.1.0\n\n## 0.0.9\n\nText.\n', {}, 'characters of notes'),
            ('store/description.md', 'x' * (listing.MAX_DESCRIPTION + 1), {}, 'store/description.md'),
            (None, None, {'tags': ['Big']}, 'store.json tags'),
            (None, None, {'icon': '../clock/icon.svg'}, 'unsafe path component'),
            (None, None, {'screenshots': ['store/shots/missing.png']}, 'no such file'),
            (None, None, {'links': {'blog': 'https://example.test/'}}, 'store.json links'),
        ]
        for name, raw, extra, reason in cases:
            with self.subTest(reason=reason, name=name):
                shutil.rmtree(self.store / 'clock', ignore_errors=True)
                self.package()
                if name:
                    self.write('clock/' + name, raw)
                if extra:
                    described = json.loads((self.store / 'clock/store.json').read_text())
                    described.update(extra)
                    self.write('clock/store.json', json.dumps(described))
                self.assertIn(reason, self.build(False)['blocked'][0]['error'])
        self.assertEqual(('image/webp', (64, 32)), (listing.picture_type(b'RIFF\x00\x00\x00\x00WEBPVP8X' + b'\x00' * 8 + b'\x3f\x00\x00\x1f\x00\x00'),
                                                   listing.picture_size(b'RIFF\x00\x00\x00\x00WEBPVP8X' + b'\x00' * 8 + b'\x3f\x00\x00\x1f\x00\x00', 'image/webp')))
        jpeg = b'\xff\xd8\xff\xe0\x00\x04\x00\x00\xff\xc0\x00\x11\x08\x00\x20\x00\x40\x03' + b'\x00' * 12
        self.assertEqual(('image/jpeg', (64, 32)), (listing.picture_type(jpeg), listing.picture_size(jpeg, 'image/jpeg')))

    def test_a_large_catalog_is_split_so_that_one_release_changes_one_page(self):
        self.assertEqual('catalog/p-0.json', listing.page_name('clock', 200))
        names = {listing.page_name('package-%d' % number, 201) for number in range(400)}
        self.assertEqual(16, len(names))
        self.assertTrue(all(len(name) == len('catalog/p-0.json') for name in names))
        self.assertEqual(listing.page_name('clock', 201), listing.page_name('clock', 3200))
        self.assertEqual(len('catalog/p-00.json'), len(listing.page_name('clock', 3201)))
        for number in range(3):
            self.package('tool-%d' % number)
        self.build()
        self.assertEqual(['tool-0', 'tool-1', 'tool-2'], [card['id'] for card in self.cards()])
        self.assertEqual(1, len(json.loads((self.out / 'index.json').read_bytes())['pages']))

    def test_kinds_are_declared_by_host_modules_not_by_the_builder(self):
        self.package('orbit', kind='game')
        self.package('sunset', kind='theme', descriptor_name='look.json')
        # Nobody declared themes for this store: the builder does not know where such a package lives.
        blocked = self.build(False)['blocked']
        self.assertEqual(['sunset'], [item['id'] for item in blocked])
        self.assertIn('not declared in kinds.json', blocked[0]['error'])
        kinds = json.loads((self.store / 'kinds.json').read_text())
        kinds['theme'] = {'host': 'themes', 'descriptor': 'look.json'}
        (self.store / 'kinds.json').write_text(json.dumps(kinds))
        self.build()
        cards = {card['id']: card for card in self.cards()}
        self.assertEqual({'minAssiApi': 1, 'gamesEngine': 1}, cards['orbit']['compatibility'])
        self.assertEqual({'minAssiApi': 1}, cards['sunset']['compatibility'])
        self.assertEqual('theme', cards['sunset']['kind'])
        with zipfile.ZipFile(self.out / 'sunset-0.1.0.zip') as archive:
            self.assertIn('code/look.json', archive.namelist())
            self.assertEqual({'assiApi': 1}, json.loads(archive.read('package.json'))['runtime'])
        with zipfile.ZipFile(self.out / 'orbit-0.1.0.zip') as archive:
            self.assertEqual({'assiApi': 1, 'gamesEngine': 1}, json.loads(archive.read('package.json'))['runtime'])
        # A package lives inside its host and nowhere else.
        described = json.loads((self.store / 'sunset/store.json').read_text())
        described['target'] = 'modules/sunset'
        self.write('sunset/store.json', json.dumps(described))
        self.assertIn('expected modules/themes/sunset', self.build(False)['blocked'][0]['error'])
        for bad in [{'module': {'host': 'x', 'descriptor': 'x.json'}}, {'theme': {'host': '../x', 'descriptor': 'x.json'}},
                    {'theme': {'host': 'themes', 'descriptor': '../x.json'}}, {'theme': {'host': 'themes', 'descriptor': 'x.json', 'engine': 'assiApi', 'version': 1}},
                    {'theme': {'host': 'themes', 'descriptor': 'x.json', 'engine': 'themesEngine'}}, {'theme': {'host': 'themes', 'descriptor': 'x.json', 'root': '/'}}]:
            (self.store / 'kinds.json').write_text(json.dumps(bad))
            with self.assertRaises(builder.BuildError):
                builder.build(self.args)

    def mod(self, folder='clock-night', target='clock', version='1.0.0', made_on='0.1.0', sample=None, before=None, **extra):
        """A mod of the clock: a night face. Its sample changes main.js and adds a stylesheet."""
        shutil.rmtree(self.store / folder, ignore_errors=True)
        self.write(folder + '/store.json', json.dumps({'id': folder, 'kind': 'mod', 'version': version, 'title': 'Night clock',
                                                       'summary': 'A dark face for the clock.', 'extends': target,
                                                       'reference': {'version': made_on}, **extra}))
        self.write(folder + '/README.md', '# Night clock\n')
        self.write(folder + '/mod/brief.md', 'Make the face dark at night. The numbers stay as they are.\n')
        sample = {'main.js': "exports.start = function () { return 'night'; };\n", 'ui/night.css': 'body { background: #000; }\n'} if sample is None else sample
        before = {'main.js': "exports.start = function () { return 'local'; };\n"} if before is None else before
        for path, raw in sample.items():
            self.write(folder + '/reference/' + path, raw)
        for path, raw in before.items():
            self.write(folder + '/base/' + path, raw)

    def test_a_mod_is_a_brief_and_a_sample_of_a_change_to_another_package(self):
        self.args.base_download_url = None
        self.args.download_url_template = 'https://example.test/releases/{id}-v{version}'
        self.package()
        self.build()
        self.publish()
        self.mod()
        self.write('clock-night/assets/moon.svg', '<svg xmlns="http://www.w3.org/2000/svg"/>')
        self.write('clock-night/store/changelog.md', '## 1.0.0 — 2026-11-17\n\n- The night face.\n')
        report = self.build()
        self.assertEqual(['clock-night'], [item['id'] for item in report['toPublish']])
        cards = {card['id']: card for card in self.cards()}
        card = cards['clock-night']
        self.assertEqual(('mod', 'clock', {'minAssiApi': 1}, 'A dark face for the clock.'), (card['kind'], card['extends'], card['compatibility'], card['summary']))
        self.assertNotIn('extends', cards['clock'])
        with zipfile.ZipFile(self.out / 'clock-night-1.0.0.zip') as archive:
            self.assertEqual({'LICENSE', 'README.md', 'assets/moon.svg', 'base/main.js', 'mod/brief.md', 'package.json', 'reference/main.js', 'reference/ui/night.css'}, set(archive.namelist()))
            manifest = json.loads(archive.read('package.json'))
        self.assertNotIn('entry', manifest)
        self.assertEqual(('mod', 'clock', {'assiApi': 1}), (manifest['kind'], manifest['extends'], manifest['runtime']))
        # What the sample was made on is recorded, down to the published archive of the target: information, not a requirement.
        before = builder.sha256(b"exports.start = function () { return 'local'; };\n")
        self.assertEqual({'version': '0.1.0', 'archiveSha256': cards['clock']['archive']['sha256'], 'base': {'main.js': before},
                          'added': ['ui/night.css'], 'removed': []}, manifest['reference'])
        # A mod is versioned like any package: its files cannot change under a published version.
        self.publish()
        self.assertEqual([], self.build()['toPublish'])
        self.write('clock-night/mod/brief.md', 'Make the face dark from dusk to dawn.\n')
        self.assertIn('raise "version"', self.build(False)['blocked'][0]['error'])

    def test_a_mod_must_say_what_it_changes_and_hold_nothing_else(self):
        self.package()
        cases = [
            (dict(), lambda: (self.store / 'clock-night/mod/brief.md').unlink(), 'mod/brief.md is the task'),
            (dict(before={'main.js': 'x', 'gone.js': 'y'}), None, 'base/gone.js'),
            (dict(removed=['main.js']), None, 'both in reference/ and in removed'),
            (dict(removed=['../escape.js'], sample={'a.js': 'x'}, before={}), None, 'store.json removed'),
            (dict(target='clock-night'), None, 'names the package it changes'),
            (dict(made_on='latest'), None, 'reference.version'),
            (dict(sample={}, before={}), None, 'changes nothing'),
            (dict(), lambda: self.write('clock-night/main.js', 'x'), 'holds folders only'),
            (dict(), lambda: self.write('clock-night/code/main.js', 'x'), 'holds mod/, reference/, base/, assets/ and store/ only'),
            (dict(), lambda: self.write('clock-night/reference/vendor.min.js', 'x'), 'minified'),
        ]
        for options, spoil, reason in cases:
            with self.subTest(reason=reason):
                self.mod(**options)
                if spoil:
                    spoil()
                blocked = self.build(False)['blocked']
                self.assertEqual(['clock-night'], [item['id'] for item in blocked])
                self.assertIn(reason, blocked[0]['error'])
        # A file the mod removes is named; its copy in base/ is welcome and its absence only noted.
        self.mod(sample={'ui/night.css': 'x'}, before={'old.js': 'gone'}, removed=['old.js', 'older.js'])
        report = self.build()
        notes = next(item for item in report['packages'] if item['id'] == 'clock-night')['listing']['notes']
        self.assertIn('removed file older.js has no copy in base/', notes)
        manifest = json.loads((self.out / 'clock-night-1.0.0.package.json').read_bytes())
        self.assertEqual((['ui/night.css'], ['old.js', 'older.js'], ['old.js']), (manifest['reference']['added'], manifest['reference']['removed'], list(manifest['reference']['base'])))

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
