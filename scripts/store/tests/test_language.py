"""The language check is a report: it counts what lacks English and never stops a build."""
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / 'build_catalog.py'
sys.path.insert(0, str(SCRIPT.parent))
SPEC = importlib.util.spec_from_file_location('build_catalog', SCRIPT)
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)
language = builder.language

HTML = '<html><head><title>Часы</title></head><body><h1>Мои часы</h1><button title="Сбросить всё">Начать</button></body></html>\n'
SCRIPT_JS = "const say = (n) => 'Осталось ' + n + ' минут';\nconst plain = 'Clock ready';\n"


class LanguageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.store = self.base / 'store'
        self.store.mkdir()

    def write(self, path, text):
        target = self.store / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding='utf-8')

    def package(self, folder='clock', html=HTML, script=SCRIPT_JS, kind='module', version='0.1.0'):
        self.write(folder + '/store.json', json.dumps({'id': folder, 'kind': kind, 'title': 'Clock', 'version': version, 'target': 'modules/' + folder}))
        self.write(folder + '/README.md', '# Clock\n')
        self.write(folder + '/module.json', json.dumps({'description': 'A local clock.'}))
        self.write(folder + '/main.js', script)
        self.write(folder + '/ui/index.html', html)
        return self.store / folder

    def test_russian_source_without_a_dictionary_lists_what_lacks_english(self):
        report = language.check(self.package())
        self.assertEqual('ru', report['source'])
        self.assertFalse(report['complete'])
        self.assertFalse(report['dictionary'])
        # Title, heading, a readable attribute, the button and the text put together from pieces.
        self.assertEqual(5, report['untranslated'])
        self.assertEqual(5, report['texts'])
        self.assertTrue(any(item['text'] == 'Осталось {} минут' and item['file'] == 'main.js' for item in report['examples']))
        self.assertIn('English is not complete', language.warnings(report)[0])

    def test_examples_are_at_most_five(self):
        html = '<body>' + ''.join(f'<p>Строка номер {word}</p>' for word in ('один', 'два', 'три', 'четыре', 'пять', 'шесть', 'семь')) + '</body>'
        report = language.check(self.package(html=html, script=''))
        self.assertEqual(7, report['untranslated'])
        self.assertEqual(5, len(report['examples']))

    def test_a_dictionary_that_covers_every_text_makes_english_complete(self):
        folder = self.package()
        self.write('clock/i18n-en.json', json.dumps({
            '_name': 'English', 'Часы': 'Clock', 'Мои часы': 'My clock', 'Сбросить всё': 'Reset all',
            'Начать': 'Start', 'Осталось {} минут': '{} minutes left'}))
        report = language.check(folder)
        self.assertTrue(report['complete'])
        self.assertEqual((0, []), (report['untranslated'], language.warnings(report)))

    def test_a_key_with_a_slot_covers_a_text_with_something_put_in(self):
        folder = self.package(html='<body><p>Удалить тему «Закат»?</p></body>', script='')
        self.write('clock/i18n-en.json', json.dumps({'Удалить тему «{}»?': 'Delete the theme “{}”?'}))
        self.assertTrue(language.check(folder)['complete'])

    def test_an_empty_translation_does_not_count(self):
        folder = self.package(html='<body><p>Начать</p></body>', script='')
        self.write('clock/i18n-en.json', json.dumps({'Начать': ' '}))
        self.assertEqual(1, language.check(folder)['untranslated'])

    def test_a_package_written_in_english_passes(self):
        report = language.check(self.package(html='<body><p>Start the clock</p></body>', script="const a = 'Clock ready';"))
        self.assertEqual(('en', 0, True), (report['source'], report['texts'], report['complete']))

    def test_tests_prompts_and_listing_are_not_interface(self):
        folder = self.package(html='<body><p>Start</p></body>', script='')
        self.write('clock/tests/a.js', "const x = 'Проверка работает';\n")
        self.write('clock/prompts/p.md', 'Ты помощник')
        self.write('clock/prompts/p.json', json.dumps({'system': 'Ты помощник'}))
        self.write('clock/store/description.md', 'Описание пакета')
        self.assertTrue(language.check(folder)['complete'])

    def test_json_values_count_but_keys_do_not(self):
        folder = self.package(html='<body></body>', script='')
        self.write('clock/config.json', json.dumps({'Ключ в тексте': {'title': 'Золотая шахта'}}))
        report = language.check(folder)
        self.assertEqual(['Золотая шахта'], [item['text'] for item in report['examples']])

    def test_build_report_has_a_language_section_and_the_build_still_passes(self):
        self.package()
        license_file = self.base / 'LICENSE'
        license_file.write_text('No license selected.\n', encoding='utf-8')
        out = self.base / 'out'
        args = builder.parser().parse_args([
            '--store', str(self.store), '--out', str(out), '--base-download-url', 'https://example.test/v0.1.0',
            '--publisher', 'author', '--licensefile', str(license_file), '--repository-id', 'author.demo',
            '--repository-title', 'Demo', '--repository-homepage', 'https://example.test/demo'])
        report = builder.build(args)
        self.assertTrue(report['ok'], report)
        section = report['packages'][0]['language']
        self.assertEqual(('ru', False, 5), (section['source'], section['complete'], section['untranslated']))
        self.assertEqual(1, len(section['warnings']))
        self.assertEqual(report, json.loads((out / 'build-report.json').read_text(encoding='utf-8')))

    def test_the_command_lists_packages_and_skips_mods(self):
        self.package()
        self.package('modest', html='<body>Hello</body>', script='')
        self.write('tweak/store.json', json.dumps({'id': 'tweak', 'kind': 'mod'}))
        found = [identity for identity, _, kind in language.packages(self.store, []) if kind != 'mod']
        self.assertEqual(['clock', 'modest'], found)
        self.assertEqual(['modest'], [identity for identity, _, _ in language.packages(self.store, ['modest'])])


if __name__ == '__main__':
    unittest.main()
