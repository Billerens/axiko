// Renders promo.html to video: headless Chrome puts the page at each moment (window.seek) and
// its frames go to ffmpeg.
//   node assets/promo/render.mjs                 → out/axiko-promo.mp4, .webp, poster.png
//   node assets/promo/render.mjs --shots 2,9,15  → out/shot-<t>.png only (to look at moments)
//   node assets/promo/render.mjs --prep <folder> → stills/ from full-size screenshots of the app
// Needs Chrome, ffmpeg and (for --prep) ImageMagick on the machine.
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.dirname(fileURLToPath(import.meta.url));
const out = path.join(root, 'out');
const FPS = 30;
const W = 1280, H = 720;
const args = process.argv.slice(2);
const flag = name => { const i = args.indexOf(name); return i < 0 ? null : args[i + 1] || ''; };

if (flag('--prep') !== null) {
  // Half-size copies are enough for a 290 px phone; the notification icons of the emulator's
  // status bar are painted over with the colour next to them.
  const from = path.resolve(flag('--prep'));
  const to = path.join(root, 'stills');
  fs.mkdirSync(to, { recursive: true });
  // stills.json lists the stills the page uses; an entry's "cover" holds rectangles [x0, y0, x1, y1]
  // of the full-size screenshot to paint over with the colour of their lower right corner.
  const listed = JSON.parse(fs.readFileSync(path.join(from, 'stills.json'), 'utf8'));
  const pixel = (file, x, y) => execFileSync('magick', [file, '-format', '%[pixel:p{' + x + ',' + y + '}]', 'info:']).toString().trim();
  for (const name of fs.readdirSync(to).filter(n => n.endsWith('.png'))) fs.rmSync(path.join(to, name));
  for (const still of listed) {
    const file = path.join(from, still.name + '.png');
    const paint = ['-fill', pixel(file, 20, 20), '-draw', 'rectangle 120,0 260,62'];
    for (const [x0, y0, x1, y1] of still.cover || []) paint.push('-fill', pixel(file, x1, y1), '-draw', 'rectangle ' + [x0, y0].join(',') + ' ' + [x1, y1].join(','));
    execFileSync('magick', [file, ...paint, '-resize', '540x', '-strip', path.join(to, still.name + '.png')]);
  }
  // A script, not JSON: the page then opens straight from the folder, without a server.
  fs.writeFileSync(path.join(to, 'stills.js'), 'window.STILLS = ' + JSON.stringify(listed.map(({ name, what }) => ({ name, what })), null, 1) + ';\n');
  console.log('stills:', fs.readdirSync(to).length);
  process.exit(0);
}

const TYPES = { '.html': 'text/html', '.png': 'image/png', '.svg': 'image/svg+xml', '.json': 'application/json', '.js': 'text/javascript' };
const server = http.createServer((req, res) => {
  const file = path.join(root, decodeURIComponent(new URL(req.url, 'http://x').pathname));
  if (!file.startsWith(root) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const base = 'http://127.0.0.1:' + server.address().port;

const chromePath = [process.env.CHROME, 'C:/Program Files/Google/Chrome/Application/chrome.exe', '/usr/bin/google-chrome'].find(p => p && fs.existsSync(p));
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'axiko-promo-'));
const DEBUG = 9300 + Math.floor(Math.random() * 500);
const chrome = spawn(chromePath, ['--headless=new', '--remote-debugging-port=' + DEBUG, '--user-data-dir=' + profile, '--hide-scrollbars',
  '--force-device-scale-factor=1', '--window-size=' + W + ',' + H, 'about:blank'], { stdio: 'ignore' });

async function target() {
  for (let i = 0; i < 60; i++) {
    try {
      const pages = await (await fetch('http://127.0.0.1:' + DEBUG + '/json')).json();
      const page = pages.find(p => p.type === 'page');
      if (page) return page.webSocketDebuggerUrl;
    } catch { /* not up yet */ }
    await new Promise(r => setTimeout(r, 250));
  }
  throw new Error('Chrome did not start');
}
const ws = new WebSocket(await target());
await new Promise(resolve => { ws.onopen = resolve; });
let next = 0;
const waiting = new Map();
ws.onmessage = event => {
  const message = JSON.parse(event.data);
  if (message.id && waiting.has(message.id)) { waiting.get(message.id)(message); waiting.delete(message.id); }
};
const send = (method, params = {}) => new Promise((resolve, reject) => {
  const id = ++next;
  waiting.set(id, message => message.error ? reject(new Error(method + ': ' + message.error.message)) : resolve(message.result));
  ws.send(JSON.stringify({ id, method, params }));
});
const evaluate = async expression => {
  const result = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
  return result.result.value;
};
const frame = async seconds => {
  await evaluate('window.seek(' + seconds + '); new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))');
  return Buffer.from((await send('Page.captureScreenshot', { format: 'png' })).data, 'base64');
};

try {
  await send('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: 1, mobile: false });
  await send('Page.enable');
  await send('Page.navigate', { url: base + '/promo.html?still' });
  for (let i = 0; i < 200 && !(await evaluate('window.ready === true')); i++) await new Promise(r => setTimeout(r, 200));
  const total = await evaluate('window.total');
  if (!total) throw new Error('the page did not get ready');
  fs.mkdirSync(out, { recursive: true });

  if (flag('--shots') !== null) {
    for (const t of flag('--shots').split(',').map(Number)) fs.writeFileSync(path.join(out, 'shot-' + t + '.png'), await frame(t));
    console.log('length ' + total.toFixed(1) + ' s; shots saved');
  } else {
    const mp4 = path.join(out, 'axiko-promo.mp4');
    const ffmpeg = spawn('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', String(FPS), '-c:v', 'png', '-i', '-',
      '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '17', '-preset', 'slow', '-movflags', '+faststart', mp4], { stdio: ['pipe', 'inherit', 'inherit'] });
    const frames = Math.round(total * FPS);
    for (let i = 0; i < frames; i++) {
      const png = await frame(i / FPS);
      if (!ffmpeg.stdin.write(png)) await new Promise(r => ffmpeg.stdin.once('drain', r));
      if (i % 90 === 0) console.log('frame ' + i + ' / ' + frames);
    }
    ffmpeg.stdin.end();
    await new Promise(resolve => ffmpeg.on('close', resolve));
    execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-i', mp4, '-vf', 'fps=15,scale=960:-1:flags=lanczos', '-loop', '0', '-c:v', 'libwebp_anim', '-q:v', '96',
      '-compression_level', '6', path.join(out, 'axiko-promo.webp')]);
    fs.writeFileSync(path.join(out, 'poster.png'), await frame(1.9));
    for (const name of ['axiko-promo.mp4', 'axiko-promo.webp']) console.log(name, (fs.statSync(path.join(out, name)).size / 1048576).toFixed(1) + ' MB');
    console.log('length ' + total.toFixed(1) + ' s');
  }
} finally {
  ws.close();
  chrome.kill();
  server.close();
  setTimeout(() => fs.rmSync(profile, { recursive: true, force: true }), 500);
}
