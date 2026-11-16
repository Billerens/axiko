#!/usr/bin/env python3
"""Draws the screens that show the mascot in a desktop browser, with the shell stubbed.

    python scripts/brand/preview.py OUT_DIR

Serves the factory copy of the workspace as the app does (the theme appended to kit.css), gives
the pages a stand-in for `window.assi` and takes screenshots at a phone's size: the tours, the
chat button with the mascot climbing over the menu bar, the home screen in several themes, the
mascot off and replaced, the chat at work, the mascot's own screen. The shell's own parts — the
menu bar and the chat button's page — are drawn separately and laid over the screen the way the
shell lays them. Prints what each page wrote to its console as an error. No device is involved:
this checks the web side only. The hub for a computer is drawn too, with the phone's side of its
link stubbed here. Needs: pillow, playwright (with Chrome or Edge).
"""
import io
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from PIL import Image
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[2]
FACTORY = ROOT / 'app/src/main/assets/factory'
MIMES = {'.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png'}
# What Themes.render adds for a theme that names no mascot colours (app/…/core/Themes.kt).
FROM_ACCENT = ('@supports (color: color-mix(in oklab, red, blue)) {\n:root {\n  --mascot-gills: var(--accent);\n'
               '  --mascot-body: color-mix(in oklab, var(--accent) 38%, white);\n  --mascot-line: color-mix(in oklab, var(--accent) 45%, black);\n}\n}\n')
# The shell's layout (MainActivity): the menu bar, the chat button's page and how far it hangs over the bar.
WIDTH, HEIGHT, BAR, FACE, OVERLAP, SCALE = 400, 860, 62, 132, 14, 2

state = {'theme': None, 'files': {}, 'shell': {}, 'streams': []}


def theme_css():
    theme = state['theme']
    if not theme:
        return ''
    tokens = theme.get('tokens', {})
    css = ':root {\n' + ''.join('  --%s: %s;\n' % item for item in tokens.items()) + '}\n'
    if tokens.get('accent') and not any(key.startswith('mascot-') for key in tokens):
        css += FROM_ACCENT
    return css


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        if self.path.startswith('/__assi/rpc'):      # the hub's link to the phone
            answer = json.dumps({'result': json.dumps(phone(body), ensure_ascii=False)}).encode('utf-8')
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(answer)
            return
        state['files'][body['path']] = body['text'].encode('utf-8')
        self.send_response(204)
        self.end_headers()

    def do_GET(self):
        path = self.path.split('?')[0].lstrip('/')
        if path == '__assi/events':                  # the phone's events, sent by hand: emit()
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.end_headers()
            self.wfile.flush()
            state['streams'].append(self.wfile)
            threading.Event().wait(60)
            return
        if path == '':
            path = 'ui/desk/index.html'
        if path == '__assi/client.js':
            path = 'ui/desk/client.js'
        if path in state['files']:
            data = state['files'][path]
        else:
            file = FACTORY / path
            if not file.is_file():
                self.send_response(404)
                self.end_headers()
                return
            data = file.read_bytes()
        if path == 'ui/kit/kit.css':
            data += theme_css().encode('utf-8')
        # In the hub every page gets the browser's stand-in for window.assi, as the phone's server gives it.
        if state['shell'].get('hub') and path.endswith('.html'):
            data = data.replace(b'<!doctype html>', b'<!doctype html><script src="/__assi/client.js"></script>', 1)
        self.send_response(200)
        self.send_header('Content-Type', MIMES.get(Path(path).suffix, 'application/octet-stream') + '; charset=utf-8')
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(data)


def phone(request):
    """What the phone answers the hub."""
    shell = state['shell']
    if request.get('op') == 'desk.status':
        return {'name': 'Аксико', 'factory': False}
    if request.get('op') == 'eval':
        return '{"items":[]}' if 'ui/nav.json' in request.get('code', '') else None
    if request.get('op') != 'native':
        return None
    method, args = request.get('method'), json.loads(request.get('args') or '[]')
    if method == 'pref.get':
        return ('' if shell.get('tours') else '*') if args[0] == 'tours.done' else (args[1] if len(args) > 1 else '')
    answers = {
        'sys.info': {'safeMode': False, 'version': '0.1', 'device': 'Preview', 'android': '16'}, 'modules.list': shell.get('modules', []),
        'agent.list': [{'id': 'assi', 'title': 'Аксико', 'icon': 'spark', 'description': ''}], 'models.roles': {'main': {'model': 'demo'}},
        'models.providers': [{'id': 'p', 'name': 'Demo'}], 'lang.status': {'language': 'ru', 'setting': 'auto', 'languages': []},
        'agent.history': [], 'agent.busy': False, 'ui.where': {}, 'agent.threads': [], 'theme.list': [], 'snap.list': [], 'tasks.list': [],
        'agent.thread': 't1', 'speech.status': {'tts': False},
    }
    return answers.get(method, {})


def emit(event, data):
    """An event from the phone to the hub."""
    message = ('data: ' + json.dumps({'event': event, 'data': data}, ensure_ascii=False) + '\n\n').encode('utf-8')
    for stream in state['streams']:
        try:
            stream.write(message)
            stream.flush()
        except Exception:
            pass


# The shell as the pages see it: canned answers, file writes passed to the server above, and
# events sent by hand (window.__emit) instead of by an agent.
STUB = r'''
window.__shell = __SHELL__;
window.__heard = {};
window.__emit = function (name, data) { (window.__heard[name] || []).forEach(function (fn) { fn(data); }); };
window.assi = {
  native: function (op, key, fallback) {
    var answers = {
      'sys.info': { safeMode: __shell.safe, version: '0.1', device: 'Preview', android: '16' },
      'modules.list': __shell.modules, 'agent.list': [{ id: 'assi', title: 'Аксико', icon: 'spark', description: '' }],
      'models.roles': __shell.model ? { main: { model: 'demo' } } : {}, 'models.providers': __shell.model ? [{ id: 'p', name: 'Demo' }] : [],
      'lang.status': { language: 'ru', setting: 'auto', languages: [] }, 'launcher.icon': 'mascot', 'button.face': { bar: __shell.bar },
      'agent.history': [], 'agent.busy': false, 'ui.where': {}, 'agent.threads': [], 'theme.list': [], 'snap.list': [],
    };
    if (op === 'pref.get') return Promise.resolve(key === 'tours.done' ? (__shell.tours ? '' : '*') : fallback === undefined ? '' : fallback);
    return Promise.resolve(op in answers ? answers[op] : {});
  },
  eval: function (code) {
    var written = /files\.(writeJson|write)\(("(?:[^"\\]|\\.)*"), ([\s\S]*)\)$/.exec(code);
    if (!written) return Promise.resolve(null);
    var text = written[1] === 'writeJson' ? JSON.stringify(JSON.parse(written[3]), null, 1) : JSON.parse(written[3]);
    return fetch('/__write', { method: 'POST', body: JSON.stringify({ path: JSON.parse(written[2]), text: text }) }).then(function () { return null; });
  },
  call: function () { return Promise.resolve(null); },
  on: function (name, fn) { (window.__heard[name] = window.__heard[name] || []).push(fn); },
  open: function () {}, back: function () {}, chat: function () {}, stage: function () {},
};
'''

MODULES = [{'id': 'notes', 'title': 'Заметки', 'icon': 'note', 'entry': 'modules/notes/ui/index.html', 'description': 'Быстрые записи'},
           {'id': 'habits', 'title': 'Привычки', 'icon': 'check', 'entry': 'x', 'description': 'Отметки по дням'},
           {'id': 'money', 'title': 'Траты', 'icon': 'wallet', 'entry': 'x', 'description': 'Куда уходят деньги'}]


def main():
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = 'http://127.0.0.1:%d/' % server.server_address[1]
    themes = {name: json.loads((FACTORY / 'themes' / (name + '.json')).read_text(encoding='utf-8')) for name in ('emerald', 'graphite')}
    themes['sunset'] = {'tokens': {'accent': '#FF7A3D', 'accent-ink': '#1F0B04', 'accent-soft': '#3A2016'}}
    mascot = json.loads((FACTORY / 'ui/mascot/mascot.json').read_text(encoding='utf-8'))
    errors = []

    with sync_playwright() as play:
        try:
            browser = play.chromium.launch()
        except Exception:
            browser = play.chromium.launch(channel='msedge')

        def draw(name, path, size, shell, act, clear=False, wait=900):
            state['shell'] = shell
            context = browser.new_context(viewport={'width': size[0], 'height': size[1]}, device_scale_factor=1 if shell.get('hub') else SCALE)
            if not shell.get('hub'):
                context.add_init_script(STUB.replace('__SHELL__', json.dumps(shell, ensure_ascii=False)))
            page = context.new_page()
            page.on('console', lambda message: errors.append('%s: %s' % (name, message.text)) if message.type == 'error' else None)
            page.on('pageerror', lambda error: errors.append('%s: %s' % (name, error)))
            page.goto(base + path)
            page.wait_for_timeout(wait)
            if act:
                act(page)
                page.wait_for_timeout(900)
            picture = Image.open(io.BytesIO(page.screenshot(omit_background=clear))).convert('RGBA')
            context.close()
            return picture

        def shot(name, path, shell=None, theme=None, files=None, act=None, height=HEIGHT, button=None, whole=False):
            """A screen as the phone shows it: the page, the menu bar under it, the chat button's page over both."""
            state['theme'] = themes.get(theme)
            state['files'] = {key: value.encode('utf-8') for key, value in (files or {}).items()}
            setup = dict({'safe': False, 'model': True, 'modules': MODULES, 'tours': False, 'bar': True}, **(shell or {}))
            if whole:   # a screen that covers the menu and the button: the chat
                draw(name, path, (WIDTH, height), setup, act).convert('RGB').save(out / (name + '.png'))
                return
            screen = Image.new('RGBA', (WIDTH * SCALE, height * SCALE), '#101010')
            screen.alpha_composite(draw(name, path, (WIDTH, height - BAR), setup, act))
            screen.alpha_composite(draw(name + ' menu', 'ui/nav/index.html', (WIDTH, BAR), setup, None), (0, (height - BAR) * SCALE))
            face = draw(name + ' button', 'ui/button/index.html', (FACE, FACE), setup, button, clear=True, wait=2600)
            screen.alpha_composite(face, ((WIDTH - FACE) * SCALE, (height - BAR + OVERLAP - FACE) * SCALE))
            screen.convert('RGB').save(out / (name + '.png'))

        off = json.dumps(dict(mascot, enabled=False), ensure_ascii=False)
        still = json.dumps(dict(mascot, alive=False, button=False), ensure_ascii=False)
        fox = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><path fill="var(--mascot-body, #F28C38)" d="M10 20l25 15h30l25-15-8 40-32 30-32-30z"/>'
               '<circle cx="38" cy="52" r="5" fill="#2B2433"/><circle cx="62" cy="52" r="5" fill="#2B2433"/><path fill="#fff" d="M50 62l-12 14 12 10 12-10z"/></svg>')
        other = json.dumps({'name': 'Лис', 'enabled': True, 'poses': {'idle': 'fox.svg'}, 'colors': {}, 'phrases': {}}, ensure_ascii=False)

        def step(count):
            return lambda page: [page.get_by_text('Далее', exact=True).click() or page.wait_for_timeout(450) for _ in range(count)]

        def agent(*events):
            sent = ''.join("__emit('agent', Object.assign({ agent: 'assi', thread: threadId }, %s));" % json.dumps(event, ensure_ascii=False) for event in events)
            return lambda page: page.evaluate(sent)

        called = lambda page: page.evaluate("__emit('chat.visible', true)")
        three = lambda page: page.evaluate(
            "['экран', 'логика', 'проверки'].forEach(function (name) { __emit('agent', { agent: 'assi', thread: 't', type: 'part', name: name, state: 'run' }); })")

        # The first meeting and its tour.
        first = {'model': False, 'modules': [], 'tours': True}
        shot('01-tour-welcome', 'ui/home/index.html', shell=first)
        shot('02-tour-modules', 'ui/home/index.html', shell=dict(first, modules=MODULES), act=step(2))
        shot('03-tour-chat-button', 'ui/home/index.html', shell=first, act=step(5))
        shot('04-tour-ask-in-chat', 'ui/home/index.html', shell=first, act=step(9))
        shot('05-first-meeting', 'ui/home/index.html', shell={'model': False, 'modules': []})
        # The chat button: peeking, called, at work; without a menu bar; in themes.
        shot('06-home', 'ui/home/index.html')
        shot('07-button-called', 'ui/home/index.html', button=called)
        shot('08-button-three-at-work', 'ui/home/index.html', button=three)
        shot('09-home-emerald', 'ui/home/index.html', theme='emerald')
        shot('10-home-graphite', 'ui/home/index.html', theme='graphite')
        shot('11-home-own-theme', 'ui/home/index.html', theme='sunset', button=three)
        # Plainer: a still picture and the round button; the mascot off; another character.
        shot('12-home-still-plain-button', 'ui/home/index.html', files={'ui/mascot/mascot.json': still})
        shot('13-home-mascot-off', 'ui/home/index.html', files={'ui/mascot/mascot.json': off})
        shot('14-home-other-character', 'ui/home/index.html', theme='sunset', files={'ui/mascot/mascot.json': other, 'ui/mascot/fox.svg': fox})
        # The chat: what the mascot does while the agent works.
        shot('15-chat-empty', 'ui/chat/index.html', whole=True)
        shot('16-chat-tour', 'ui/chat/index.html', whole=True, shell={'tours': True}, act=called)
        shot('17-chat-thinking', 'ui/chat/index.html', whole=True, act=agent({'type': 'user', 'text': 'Сделай экран заметок'}))
        shot('18-chat-searching', 'ui/chat/index.html', whole=True, act=agent(
            {'type': 'user', 'text': 'Какая завтра погода?'}, {'type': 'tool', 'id': 't1', 'name': 'web_search', 'status': 'run', 'args': 'погода завтра'}))
        shot('19-chat-working-three', 'ui/chat/index.html', whole=True, act=agent(
            {'type': 'user', 'text': 'Сделай трекер привычек'}, {'type': 'tool', 'id': 't1', 'name': 'subtasks', 'status': 'run', 'args': '3 части'},
            {'type': 'part', 'call': 't1', 'name': 'экран', 'state': 'run', 'note': 'работает'},
            {'type': 'part', 'call': 't1', 'name': 'логика', 'state': 'run', 'note': 'работает'},
            {'type': 'part', 'call': 't1', 'name': 'проверки', 'state': 'run', 'note': 'работает'}))
        shot('20-chat-asks', 'ui/chat/index.html', whole=True, act=agent(
            {'type': 'user', 'text': 'Сделай мне лендинг'},
            {'type': 'ask', 'id': 'q1', 'form': {'title': 'Пара вопросов', 'questions': [{'text': 'Для чего лендинг?', 'options': ['Портфолио', 'Магазин', 'Событие']}]}}))
        shot('21-chat-error-and-done', 'ui/chat/index.html', whole=True, act=agent(
            {'type': 'user', 'text': 'Сделай экран заметок'},
            {'type': 'changes', 'snapshot': 's1', 'files': [{'path': 'modules/notes/ui/index.html', 'kind': 'added'}, {'path': 'modules/notes/main.js', 'kind': 'added'}]},
            {'type': 'error', 'message': 'Модель не ответила: проверьте ключ в настройках провайдера.'}, {'type': 'idle'}))
        shot('22-chat-mascot-off', 'ui/chat/index.html', whole=True, files={'ui/mascot/mascot.json': off},
             act=agent({'type': 'user', 'text': 'Сделай экран заметок'}))
        # The hub on a computer: the same app in a browser.
        def desk(name, shell=None, act=None, theme=None):
            state['theme'] = themes.get(theme)
            state['files'] = {}
            state['streams'] = []
            setup = dict({'hub': True, 'modules': MODULES, 'tours': False}, **(shell or {}))
            draw(name, '', (1360, 820), setup, act, wait=2200).convert('RGB').save(out / (name + '.png'))

        desk('25-hub')
        desk('26-hub-tour', shell={'tours': True})
        desk('27-hub-agent-searching', theme='emerald', act=lambda page: emit('agent', {'agent': 'assi', 'thread': 't1', 'type': 'tool', 'id': 't', 'name': 'web_search', 'status': 'run'}))
        # The mascot's own screen.
        shot('23-mascot-screen', 'ui/mascot/index.html', height=1240)
        shot('24-mascot-own-colours', 'ui/mascot/index.html', height=1240, act=lambda page: page.get_by_text('Свой', exact=True).click())
        browser.close()
    server.shutdown()
    print('\n'.join(errors) if errors else 'no console errors')


if __name__ == '__main__':
    main()
