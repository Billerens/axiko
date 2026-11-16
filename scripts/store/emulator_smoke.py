"""Emulator-only helpers for exercising the real Assi Native/store path.

No credentials are printed or exported. Fixtures use an injected transport;
production HTTPS remains unchanged. Invoke operations explicitly from Python.
"""
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'build/artifacts/store-2026-11-16'
ADB = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'AppData/Local/Android/Sdk'))) / 'platform-tools/adb.exe'
DEVICE = 'emulator-5554'
PORT = 18765
REMOTE = '/data/user/0/app.assi/files/state/store-fixtures'


def adb(*args, **kwargs):
    return subprocess.check_output([str(ADB), '-s', DEVICE, *args], timeout=kwargs.pop('timeout', 60), **kwargs)


def guard():
    assert adb('shell', 'getprop', 'ro.kernel.qemu').decode().strip() == '1', 'Emulator required'


def backup():
    guard()
    OUT.mkdir(parents=True, exist_ok=True)
    target = OUT / 'emulator-before.tar'
    if not target.exists():
        with target.open('wb') as stream:
            subprocess.run([str(ADB), '-s', DEVICE, 'exec-out', 'run-as', 'app.assi', 'tar', '-cf', '-', 'files/assi', 'files/data', 'files/state', 'shared_prefs'], stdout=stream, check=True, timeout=180)
        with tarfile.open(target) as archive:
            assert archive.getmembers()
    return {'backupBytes': target.stat().st_size, 'device': DEVICE}


def rpc(payload):
    guard()
    adb('forward', f'tcp:{PORT}', 'tcp:8765')
    prefs = ET.fromstring(adb('exec-out', 'run-as', 'app.assi', 'cat', 'shared_prefs/assi.xml'))
    key = next(item.text for item in prefs if item.get('name') == 'lan.code')
    token = hashlib.sha256(('assi:' + key).encode()).hexdigest()[:32]
    request = urllib.request.Request(f'http://127.0.0.1:{PORT}/__assi/rpc', data=json.dumps(payload, ensure_ascii=False).encode(), headers={'Content-Type': 'application/json', 'Cookie': f'assi={token}'})
    with urllib.request.urlopen(request, timeout=180) as response:
        value = json.load(response)
    for _ in range(7):
        if isinstance(value, str):
            try:
                value = json.loads(value)
                continue
            except ValueError:
                return value
        if isinstance(value, dict) and 'error' in value and ('ok' in value or set(value) == {'error'}) and not value.get('ok'):
            raise RuntimeError(value['error'])
        if isinstance(value, dict) and value.get('ok') is True and 'result' in value:
            value = value['result']
            continue
        return value
    return value


def native(method, *args):
    # Match the WebView's engine-thread Native bridge, also for Rhino test proxies.
    return evaluate('JSON.parse(String(host.native(' + json.dumps(method) + ',' + json.dumps(json.dumps(args, ensure_ascii=False)) + ')))')


def evaluate(code):
    return rpc({'op': 'eval', 'code': code})


def bundle(package_id, version, output):
    files = {
        'README.md': f'# Emulator counter {version}\nReadable emulator-only fixture.\n',
        'LICENSE': 'Test fixture; no project-wide license is granted.\n',
        'code/module.json': json.dumps({'id': package_id, 'title': 'Проверка магазина', 'entry': 'ui/index.html', 'autostart': False}, ensure_ascii=False, indent=2),
        'code/main.js': f'exports.version = function () {{\n  return "{version}";\n}};\n',
        'code/local.js': 'exports.color = "blue";\n',
        'code/checks.js': 'exports.run = function () {\n  require("lib/assi").fs.write("data/store-test-checks-ran.txt", "UNEXPECTED");\n  return [];\n};\n',
        'code/ui/index.html': '<!doctype html>\n<html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">\n<link rel="stylesheet" href="/ui/kit/kit.css"><body><main class="page"><h1>Счётчик магазина</h1><p id="value">0</p><button class="btn primary" id="add">Добавить</button></main><script src="/ui/kit/kit.js"></script>\n<script>\nlet value = 0;\ndocument.getElementById("add").onclick = function () {\n  document.getElementById("value").textContent = String(++value);\n};\n</script></body></html>\n',
    }
    data = {path: text.encode() for path, text in files.items()}
    manifest = {'schemaVersion': 1, 'id': package_id, 'kind': 'module', 'version': version, 'publisher': 'emulator', 'license': 'UNLICENSED', 'sourceFormat': 'readable', 'entry': 'code/ui/index.html', 'runtime': {'assiApi': 1}, 'capabilities': ['storage.own'], 'dependencies': [], 'optionalDependencies': [], 'files': [{'path': p, 'size': len(b), 'sha256': hashlib.sha256(b).hexdigest()} for p, b in data.items()]}
    manifest_bytes = json.dumps(manifest, ensure_ascii=False, indent=2).encode()
    data['package.json'] = manifest_bytes
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path, content in sorted(data.items()):
            archive.writestr(zipfile.ZipInfo(path, (2026, 11, 16, 0, 0, 0)), content)
    zipped = buffer.getvalue()
    stem = f'{package_id}-{version}'
    (output / f'{stem}.zip').write_bytes(zipped)
    (output / f'{stem}.json').write_bytes(manifest_bytes)
    return {'id': package_id, 'kind': 'module', 'title': 'Проверка магазина' if package_id.endswith('counter') else 'Проверка агентом', 'summary': 'Тестовый пакет эмулятора', 'version': version, 'publisher': 'emulator', 'license': 'UNLICENSED', 'manifestUrl': f'https://store-test.example/{stem}.json', 'archive': {'url': f'https://store-test.example/{stem}.zip', 'sha256': hashlib.sha256(zipped).hexdigest(), 'size': len(zipped), 'unpackedSize': sum(map(len, data.values())), 'fileCount': len(data)}, 'compatibility': {'minAssiApi': 1}}


def fixtures():
    guard()
    output = OUT / 'fixtures'
    output.mkdir(exist_ok=True, parents=True)
    audits = bundle('demo.audit', '1.0.0', output)
    for version in ('1.0.0', '1.1.0', '1.2.0'):
        entry = bundle('demo.market-counter', version, output)
        index = {'schemaVersion': 1, 'repository': {'id': 'demo.market-test', 'title': 'Тестовый каталог', 'homepage': 'https://store-test.example/'}, 'packages': [entry, audits]}
        (output / f'index-{version}.json').write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding='utf-8')
    (output / 'index.json').write_bytes((output / 'index-1.0.0.json').read_bytes())
    (output / 'invalid.json').write_text('{"schemaVersion":99}', encoding='utf-8')
    mapping = {f'https://store-test.example/{p.name}': f'{REMOTE}/{p.name}' for p in output.iterdir() if p.is_file()}
    (output / 'mapping.json').write_text(json.dumps(mapping), encoding='utf-8')
    payload = OUT / 'fixtures.tar'
    with tarfile.open(payload, 'w') as archive:
        for path in output.iterdir():
            archive.add(path, arcname='store-fixtures/' + path.name, recursive=False)
    adb('push', str(payload), '/data/local/tmp/assi-store-fixtures.tar')
    adb('shell', 'run-as', 'app.assi', 'tar', '-xf', '/data/local/tmp/assi-store-fixtures.tar', '-C', 'files/state')
    code = '''(function () {
      var W = Packages.app.assi.core.Workspace.INSTANCE;
      var mapping = JSON.parse(String(host.readText("REMOTE/mapping.json")));
      var transport = Packages.org.mozilla.javascript.Context.jsToJava({
        get: function (url, maxBytes, etag, modified) {
          var path = mapping[String(url)];
          if (!path) throw new java.io.IOException("Fixture URL not mapped");
          var bytes = java.nio.file.Files.readAllBytes(new java.io.File(path).toPath());
          if (bytes.length > maxBytes) throw new java.io.IOException("Fixture limit");
          return new Packages.app.assi.store.StoreFetch(bytes, 200, null, null, String(url));
        }
      }, Packages.app.assi.store.StoreTransport);
      var snapshot = Packages.org.mozilla.javascript.Context.jsToJava(function (label) { return W.snapshot(String(label)); }, Packages.kotlin.jvm.functions.Function1);
      var service = new Packages.app.assi.store.StoreService(W.getRoot(), new java.io.File(W.getState(), "store-fixture-test"), transport, snapshot);
      var cls = Packages.app.assi.store.Store.INSTANCE.getClass();
      var field = cls.getDeclaredField("current"); field.setAccessible(true); field.set(null, service);
      var root = cls.getDeclaredField("currentRoot"); root.setAccessible(true); root.set(null, W.getRoot().getCanonicalPath());
      return "fixture transport injected";
    })()'''.replace('REMOTE', REMOTE)
    return evaluate(code)


def publish(version):
    assert version in ('1.0.0', '1.1.0', '1.2.0')
    return evaluate(f'host.writeText({json.dumps(REMOTE + "/index.json")}, host.readText({json.dumps(REMOTE + "/index-" + version + ".json")})); "published"')


if __name__ == '__main__':
    import sys
    action = sys.argv[1] if len(sys.argv) > 1 else 'status'
    if action == 'backup': print(json.dumps(backup()))
    elif action == 'fixtures': print(fixtures())
    elif action == 'status': print(json.dumps(native('store.sources'), ensure_ascii=False))
    else: raise SystemExit('Use backup, fixtures or status')
