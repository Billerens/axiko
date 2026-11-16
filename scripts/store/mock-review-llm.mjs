#!/usr/bin/env node
/**
 * Emulator-only deterministic OpenAI SSE provider for the real Axiko audit conversation.
 * Run explicitly; this file never changes provider settings or starts an Android app.
 * Example: node scripts/store/mock-review-llm.mjs --plan <prepared-plan-id>
 * The report contains tool names, assertions and test-file metadata, never prompts,
 * request headers, API keys or source contents. This is a plumbing test, not an AI audit.
 */
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const EXPECTED = ['ask_owner', 'store_apply', 'store_plan', 'store_read', 'store_resolve', 'store_review'].sort();
const REVIEWABLE = new Set(['js', 'mjs', 'cjs', 'html', 'htm', 'css', 'json', 'md', 'txt', 'svg', 'xml', 'sql']);
const arg = (name, fallback) => { const i = process.argv.indexOf(name); return i < 0 ? fallback : process.argv[i + 1]; };
const PORT = Number(arg('--port', '8796'));
const REPORT = path.resolve(arg('--report', path.join(ROOT, 'build/artifacts/store-2026-11-16/mock-review-llm-report.json')));
const explicitPlan = arg('--plan', '');
const state = { planId: explicitPlan, digest: '', pending: null, serial: 0, requests: 0, files: new Map(), queue: [], readIndex: 0, offset: 0, phase: 'initial', failed: false, complete: false, events: [] };

function persist() {
  fs.mkdirSync(path.dirname(REPORT), { recursive: true });
  fs.writeFileSync(REPORT, JSON.stringify({ test: 'real-assi-conversation-scoped-store', simulatedAudit: true, status: state.failed ? 'failed' : state.complete ? 'passed' : 'running', planId: state.planId || null, requests: state.requests, files: state.queue.map(f => ({ path: f.path, sha256: f.sha256 })), events: state.events }, null, 2));
}
function event(kind, data = {}) { state.events.push({ kind, ...data }); persist(); }
function check(ok, code) { if (!ok) { state.failed = true; event('assertion-failed', { code }); throw new Error(code); } }
function contentText(value) { return typeof value === 'string' ? value : Array.isArray(value) ? value.filter(x => x.type === 'text').map(x => x.text || '').join('\n') : ''; }
function jsonResult(message, name) {
  let result;
  try { result = JSON.parse(contentText(message.content)); } catch { check(false, `${name}:invalid-or-clipped-json`); }
  check(result && typeof result === 'object', `${name}:result-not-object`);
  return result;
}
function readable(file) { return file.executable || ['package.json', 'README.md', 'LICENSE'].includes(file.path) || REVIEWABLE.has(file.path.split('.').pop().toLowerCase()); }
function tool(name, args) {
  const call = { id: `call_mock_store_${++state.serial}`, name, args };
  state.pending = call;
  event('tool-request', { name, ...(name === 'store_read' ? { path: args.path, offset: args.offset, limit: args.limit } : {}) });
  return { tool: call };
}
function pagePlan(filesOffset = 0, conflictsOffset = 0, findingsOffset = 0) { return tool('store_plan', { planId: state.planId, filesOffset, filesLimit: 16, conflictsOffset, findingsOffset }); }
function nextRead() {
  if (state.readIndex >= state.queue.length) { state.phase = 'verify-plan'; return pagePlan(); }
  return tool('store_read', { planId: state.planId, path: state.queue[state.readIndex].path, side: 'candidate', offset: state.offset, limit: 4096 });
}
function planResult(result) {
  check(result.id === state.planId, 'store_plan:wrong-plan');
  check(result.status === 'ready', 'store_plan:fixture-must-be-ready');
  check(typeof result.digest === 'string' && result.digest.length === 64, 'store_plan:missing-digest');
  check(!state.digest || state.digest === result.digest, 'store_plan:fixture-changed-during-audit');
  state.digest = result.digest;
  check(Array.isArray(result.reviewFiles), 'store_plan:missing-files-page');
  check(!result.conflicts || result.conflicts.every(c => c.resolved), 'store_plan:unexpected-conflicts');
  for (const f of result.reviewFiles) {
    check(typeof f.path === 'string' && typeof f.sha256 === 'string', 'store_plan:invalid-file');
    if (readable(f)) state.files.set(f.path, f);
  }
  event('plan-page', { files: result.reviewFiles.length, nextFilesOffset: result.nextFilesOffset ?? null, nextConflictsOffset: result.nextConflictsOffset ?? null, nextFindingsOffset: result.nextFindingsOffset ?? null });
  if (state.phase === 'verify-plan') {
    check(result.auditCoverage?.complete === true, 'store_plan:coverage-not-complete');
    state.phase = 'submit-review';
    return tool('store_review', { planId: state.planId, digest: state.digest, report: { summary: 'Детерминированная проверка тестового пакета в эмуляторе: все текстовые файлы прочитаны. Это тест маршрута инструментов, не заключение о безопасности стороннего кода: семантический анализ моделью здесь не выполняется.', findings: [], unknowns: [] } });
  }
  const lists = [['reviewFiles', 'filesOffset', 'nextFilesOffset'], ['conflicts', 'conflictsOffset', 'nextConflictsOffset'], ['findings', 'findingsOffset', 'nextFindingsOffset']];
  if (lists.some(([, , next]) => Number.isInteger(result[next]) && result[next] >= 0)) {
    const offsets = lists.map(([list, offset, next]) => {
      const current = state.pending.args[offset] || 0;
      if (Number.isInteger(result[next]) && result[next] >= 0) {
        check(result[next] > current, 'store_plan:pagination-not-advancing');
        return result[next];
      }
      return current + (result[list] || []).length;
    });
    return pagePlan(...offsets);
  }
  state.queue = [...state.files.values()];
  check(state.queue.length > 0, 'store_plan:no-reviewable-files');
  state.phase = 'read-files';
  return nextRead();
}
function advance(message) {
  const pending = state.pending;
  const text = contentText(message.content);
  if (pending.name === 'js_eval') {
    check(text.includes('недоступен') || text.includes('разрешает только'), 'scope:forbidden-tool-not-rejected');
    check(!text.includes('SHOULD_NOT_EXECUTE'), 'scope:forbidden-code-executed');
    event('scope-rejected-forbidden-tool', { name: 'js_eval' });
    state.phase = 'enumerate-files';
    return pagePlan();
  }
  const result = jsonResult(message, pending.name);
  if (pending.name === 'store_plan') return planResult(result);
  if (pending.name === 'store_read') {
    const file = state.queue[state.readIndex];
    check(result.path === file.path && result.sha256 === file.sha256, 'store_read:wrong-file-or-hash');
    check(result.offset === state.offset && Number.isInteger(result.nextOffset) && Number.isInteger(result.totalLength), 'store_read:invalid-range');
    check(result.nextOffset >= result.offset && result.nextOffset <= result.totalLength, 'store_read:range-outside-file');
    check(typeof result.content === 'string' && result.content.length === result.nextOffset - result.offset, 'store_read:content-not-fully-delivered');
    check(result.eof === (result.nextOffset === result.totalLength), 'store_read:invalid-eof');
    event('file-fragment-read', { path: file.path, offset: result.offset, nextOffset: result.nextOffset, totalLength: result.totalLength, eof: result.eof });
    if (result.eof) { state.readIndex++; state.offset = 0; }
    else { check(result.nextOffset > state.offset, 'store_read:range-not-advancing'); state.offset = result.nextOffset; }
    return nextRead();
  }
  if (pending.name === 'store_review') {
    check(result.status === 'pass' && result.planDigest === state.digest, 'store_review:pass-not-persisted');
    check(result.coverage?.complete === true, 'store_review:missing-complete-coverage');
    check(result.checksExecuted === false, 'store_review:executed-package-checks');
    event('review-persisted', { status: result.status, completeCoverage: true });
    state.phase = 'apply';
    return tool('store_apply', { planId: state.planId, digest: state.digest });
  }
  if (pending.name === 'store_apply') {
    check(result.ok === true && result.status === 'applied', 'store_apply:installation-failed');
    check(result.dataPreserved === true && result.packageCodeStarted === false, 'store_apply:invalid-safety-result');
    state.pending = null; state.complete = true; state.phase = 'done';
    event('installation-completed', { status: result.status, dataPreserved: result.dataPreserved, packageCodeStarted: result.packageCodeStarted });
    return { text: 'Тестовый пакет установлен через реальный разговор Axiko. Ограничение инструментов проверено, все исходники переданы полностью, сохранения не затронуты. Это детерминированный тест маршрута, а не ИИ-аудит безопасности.' };
  }
  check(false, 'protocol:unexpected-tool-result');
}
function decide(body) {
  state.requests++;
  check(body.stream === true, 'protocol:expected-stream');
  const names = (body.tools || []).map(t => t.function?.name).sort();
  check(names.length === EXPECTED.length && names.every((n, i) => n === EXPECTED[i]), 'scope:offered-tools-not-exactly-six');
  const messages = Array.isArray(body.messages) ? body.messages : [];
  if (!state.planId) {
    const request = messages.filter(m => m.role === 'user').map(m => contentText(m.content)).find(s => s.includes('Проверить и установить с агентом')) || '';
    state.planId = request.match(/подготовленного плана ([a-z0-9-]+)/i)?.[1] || '';
  }
  check(/^[a-zA-Z0-9_-]{8,100}$/.test(state.planId), 'protocol:explicit-test-plan-required');
  check(state.requests < 2000, 'protocol:request-limit');
  if (state.failed) throw new Error('test-already-failed');
  if (state.complete) return { text: 'Детерминированный тест уже завершён.' };
  if (!state.pending) {
    event('scope-exact-tool-list', { tools: names });
    return tool('js_eval', { code: 'throw new Error("SHOULD_NOT_EXECUTE")' });
  }
  const result = messages.findLast(m => m.role === 'tool' && m.tool_call_id === state.pending.id);
  return result ? advance(result) : { tool: state.pending }; // Safe replay of an interrupted SSE request.
}
function sse(res, response) {
  res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-store', 'Connection': 'keep-alive' });
  const id = `mock-store-response-${state.requests}`;
  const chunk = delta => res.write(`data: ${JSON.stringify({ id, object: 'chat.completion.chunk', choices: [{ index: 0, delta }] })}\n\n`);
  chunk({ role: 'assistant' });
  if (response.tool) {
    const t = response.tool; const args = JSON.stringify(t.args); const split = Math.floor(args.length / 2);
    chunk({ tool_calls: [{ index: 0, id: t.id, type: 'function', function: { name: t.name, arguments: args.slice(0, split) } }] });
    chunk({ tool_calls: [{ index: 0, function: { arguments: args.slice(split) } }] });
  } else chunk({ content: response.text });
  res.write(`data: ${JSON.stringify({ id, choices: [{ index: 0, delta: {}, finish_reason: response.tool ? 'tool_calls' : 'stop' }], usage: { prompt_tokens: 100, completion_tokens: 20, total_tokens: 120 } })}\n\n`);
  res.end('data: [DONE]\n\n');
}

check(Number.isInteger(PORT) && PORT > 0 && PORT < 65536, 'config:invalid-port');
const server = http.createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/health') { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ ready: true, status: state.failed ? 'failed' : state.complete ? 'passed' : 'running' })); return; }
  if (req.method !== 'POST' || !['/v1/chat/completions', '/chat/completions'].includes(req.url)) { res.writeHead(404); res.end(); return; }
  try {
    // Only the emulator fixture should connect; an accidental real provider key is rejected.
    check(!req.headers.authorization, 'privacy:authorization-header-not-allowed');
    let raw = ''; for await (const part of req) { raw += part; check(raw.length <= 16 * 1024 * 1024, 'protocol:request-too-large'); }
    const body = JSON.parse(raw);
    sse(res, decide(body));
  } catch (error) {
    if (!state.failed) { state.failed = true; event('protocol-failed', { code: 'unexpected-protocol-input' }); }
    res.writeHead(400, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: { message: 'Mock store audit failed; see the assertion-only report.' } }));
  }
});
server.listen(PORT, '127.0.0.1', () => { persist(); process.stdout.write(`Store review test provider listening on 127.0.0.1:${PORT}\nReport: ${REPORT}\n`); });
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(() => { persist(); process.exit(0); }));
