// Protocol tests without listening, provider configuration, files from the user, or Android.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const filename = path.join(__dirname, 'mock-review-llm.mjs');
const source = fs.readFileSync(filename, 'utf8');
const core = source.slice(source.indexOf('const ROOT ='), source.indexOf('check(Number.isInteger(PORT)'))
  .replace('fileURLToPath(import.meta.url)', JSON.stringify(filename));
const names = ['ask_owner', 'store_apply', 'store_plan', 'store_read', 'store_resolve', 'store_review'];
const digest = 'a'.repeat(64);
const fileA = { path: 'code/main.js', sha256: 'b'.repeat(64), executable: true };
const fileB = { path: 'README.md', sha256: 'c'.repeat(64), executable: false };
function fixture() {
  let report;
  const sandbox = { path, fs: { mkdirSync() {}, writeFileSync(_path, text) { report = JSON.parse(text); } }, process: { argv: ['node', filename, '--plan', 'test-plan-0001'] } };
  vm.createContext(sandbox);
  vm.runInContext(core + '\nthis.api={decide,state,sse};', sandbox);
  const { api } = sandbox;
  const history = [];
  function request(toolResult) {
    if (toolResult !== undefined) history.push({ role: 'tool', tool_call_id: api.state.pending.id, content: typeof toolResult === 'string' ? toolResult : JSON.stringify(toolResult) });
    return api.decide({ stream: true, tools: names.map(name => ({ function: { name } })), messages: history });
  }
  function plan(extra = {}) { return { id: 'test-plan-0001', digest, status: 'ready', reviewFiles: [fileA], conflicts: [], findings: [], nextFilesOffset: null, nextConflictsOffset: null, nextFindingsOffset: null, ...extra }; }
  function read(file, text, offset, length) { return { path: file.path, sha256: file.sha256, offset, nextOffset: offset + text.length, totalLength: length, content: text, eof: offset + text.length === length }; }
  return { api, request, plan, read, report: () => report };
}
let checks = 0;
function test(name, fn) { fn(); checks++; process.stdout.write(`PASS ${name}\n`); }
test('full SSE flow with paginated files/findings, segmented read, current coverage and one apply', () => {
  const f = fixture();
  assert.equal(f.request().tool.name, 'js_eval');
  assert.equal(f.request('Ошибка: инструмент js_eval вам недоступен').tool.name, 'store_plan');
  let action = f.request(f.plan({ nextFilesOffset: 1, nextFindingsOffset: 12 }));
  assert.equal(action.tool.args.filesOffset, 1); assert.equal(action.tool.args.findingsOffset, 12);
  action = f.request(f.plan({ reviewFiles: [fileB], nextFilesOffset: null, findings: [{ code: 'fixture' }] }));
  assert.equal(action.tool.name, 'store_read'); assert.equal(action.tool.args.path, fileA.path);
  action = f.request(f.read(fileA, 'var ', 0, 10)); assert.equal(action.tool.args.offset, 4);
  action = f.request(f.read(fileA, 'x = 1;', 4, 10)); assert.equal(action.tool.args.path, fileB.path);
  assert.equal(f.request(f.read(fileB, 'Fixture', 0, 7)).tool.name, 'store_plan');
  assert.equal(f.request(f.plan({ auditCoverage: { complete: true } })).tool.name, 'store_review');
  assert.equal(f.request({ status: 'pass', planDigest: digest, coverage: { complete: true }, checksExecuted: false }).tool.name, 'store_apply');
  const done = f.request({ ok: true, status: 'applied', dataPreserved: true, packageCodeStarted: false });
  assert.equal(typeof done.text, 'string'); assert.equal(f.report().status, 'passed');
  assert.equal(f.report().events.filter(e => e.name === 'store_apply').length, 1);
  assert.equal(JSON.stringify(f.report()).includes('var x'), false);
});
test('forbidden tool execution is detected', () => {
  const f = fixture(); f.request();
  assert.throws(() => f.request('Ошибка: SHOULD_NOT_EXECUTE'), /scope:forbidden-tool-not-rejected/);
  assert.equal(f.report().status, 'failed');
});
test('extra advertised tool fails closed', () => {
  const f = fixture();
  assert.throws(() => f.api.decide({ stream: true, messages: [], tools: [...names, 'js_eval'].map(name => ({ function: { name } })) }), /scope:offered-tools-not-exactly-six/);
});
test('clipped JSON is detected before claiming complete reading', () => {
  const f = fixture(); f.request(); f.request('инструмент недоступен'); f.request(f.plan());
  assert.throws(() => f.request('{"path":"code/main.js","content":"...'), /store_read:invalid-or-clipped-json/);
});
test('claimed read range must equal content delivered', () => {
  const f = fixture(); f.request(); f.request('инструмент недоступен'); f.request(f.plan());
  assert.throws(() => f.request({ ...f.read(fileA, 'var ', 0, 10), nextOffset: 10, eof: true }), /content-not-fully-delivered/);
});
test('SSE splits arguments and terminates with DONE', () => {
  const f = fixture(); const action = f.request(); let body = '';
  const response = { writeHead(status, headers) { assert.equal(status, 200); assert.equal(headers['Content-Type'], 'text/event-stream; charset=utf-8'); }, write(s) { body += s; }, end(s) { body += s; } };
  f.api.sse(response, action);
  const chunks = body.split('\n').filter(s => s.startsWith('data: ') && !s.includes('[DONE]')).map(s => JSON.parse(s.slice(6)));
  const calls = chunks.flatMap(c => c.choices[0]?.delta.tool_calls || []);
  assert.equal(calls.length, 2);
  assert.equal(calls.map(c => c.function.arguments).join(''), JSON.stringify(action.tool.args));
  assert.ok(body.endsWith('data: [DONE]\n\n'));
});
process.stdout.write(`${checks} protocol checks passed; no server started.\n`);
