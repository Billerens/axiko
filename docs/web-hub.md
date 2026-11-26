# Web hub contracts

English · [Русский](web-hub.ru.md)

The hub is the owner's browser workspace for modules, games, chat and Studio. The phone
serves it over the LAN, normally at `http://axiko.local:8765`, after access is enabled and
the owner enters the connection key. Host name and port are configurable.

## Editable files and recovery

The single factory source lives in `app/src/main/assets/factory/ui/desk/`:

```text
index.html       hub.css          hub.js
transport.js     client.js
editor-model.js  editor.js        editor.css
```

`Workspace.installFactory` places it under device `files/axiko/ui/desk/`. Fresh working and
factory copies are identical. Updates replace untouched files and preserve local changes.
The LAN server normally serves working files, with a factory fallback for missing files.

`/?factory=1` uses the factory hub, client and common `ui/kit` / `ui/vendor` dependencies;
`/?factory=0` returns to working files. Safe mode also uses factory content. Recovery does
not overwrite personal files: it opens a usable interface for repairing them.

## Navigation and visibility

The overview exposes modules, games, tasks and Studio. Navigation reads `ui/nav.json` while
keeping system sections and modules accessible independently of a customized Home screen.
The chat can remain beside the current activity; a narrow layout switches between them.
Ctrl/Cmd+K opens quick navigation. Routes support browser back/forward.

Agent navigation changes the user's screen only when Follow is enabled. A game's
`axiko.stage` request affects the browser hub, not the phone. Browser visibility follows the
browser tab and active hub panel: switching to Studio or mobile chat hides a game, while
turning off the phone's screen does not itself hide that browser game.

Editor assets load on first use. Modules, navigation, tasks and device information render as
their independent requests finish. Preserve unchanged DOM nodes and focus rather than
recreating the whole view. Motion must respect reduced-motion and hidden-page state.

## Transport and embedded pages

One `AxikoTransport` serves the hub and its frames. Nested widgets find `axikoDesk` through
parent windows, share the event stream and navigate in the main area. Do not create a
separate EventSource per widget.

The transport exposes `rpc`, `native`, `call`, `eval`, `subscribe`, `onStatus`, `status`,
`login` and `close`. Preserve error codes, HTTP status and file-conflict revisions. A timeout
must not automatically replay a write: the original operation may already have completed.

After EventSource reconnects, `desk.reconnected` refreshes navigation, modules and tasks.
Chat reconciles with authoritative history while preserving input. If an answer is still
streaming, final reconciliation waits for idle. This is not a general cursor/replay event protocol.
An HTTP 401 keeps unsaved work and offers sign-in instead of a reload loop.

Pages continue using `window.axiko`. Browser-local `ui.open`, `ui.back`, `ui.chat`, `ui.stage`
and `draft.open` stay in the browser. Preview resources and RPC retain their draft context;
a live page beside a draft must retain its own live context.

Readiness comes from `DOMContentLoaded` or `ui.ready`, not completion of optional images.
Accept readiness only from the expected main frame/document. Clear loading warnings after
readiness or navigation; Retry reloads that screen. Hash navigation needs no new load event.

## Studio and file revisions

Files have independent tabs and buffers. A late read for file A cannot overwrite file B.
Saving captures the submitted text and revision; edits typed during the request remain dirty.
Unsaved buffers are retained in this browser, and conflicting remote changes require comparison.

The editor accepts UTF-8 text up to 2 MiB and only edits workspace code, not `data/`. A live
write takes a snapshot and uses a temporary file plus rename. Preview preserves draft context;
previewing the hub itself opens a separate tab rather than recursively framing the hub.
Log polling runs only while the log section is visible.

Operations use `POST /__axiko/rpc`:

| Operation | Contract |
|---|---|
| `desk.read` | `path`, optional `draft` → `path`, `text`, `revision`, `size`, `exists`; missing files return `code=not_found` |
| `desk.write` | `path`, `text`, `expectedRevision`, optional `draft`; use `expectedRevision=null` to create; mismatch returns `code=conflict` and current revision |
| `desk.status` | Assistant/host/device/app information, protocol, factory mode and capabilities; never the connection key |

Explicit `draft:null` means the live workspace. If omitted, the server may infer context from
`where`/Referer. Successful writes emit `workspace.changed` with `path`, `revision` and `draft`.

## Verification

```powershell
node --test scripts/web/editor-model.test.js scripts/web/transport.test.js scripts/web/chat-reconnect.test.js
node scripts/web/editor-browser.cjs
node scripts/web/hub-loading-browser.cjs
node scripts/web/hub-runtime-browser.cjs
.\gradlew.bat testDebugUnitTest assembleDebug
```

Browser scripts require Playwright; an external installation can use `PLAYWRIGHT_MODULE`
and `PLAYWRIGHT_CHROMIUM_EXECUTABLE`. Verify wide/narrow layouts, nested navigation, one
shared event stream, reconnects, unsaved text, revision conflicts and factory fallback.
`DeskFilesTest` covers file boundaries, UTF-8, atomic writes and drafts.

`live-visibility.cjs` is for a separate QA installation because it injects visibility events.
Configure `AXIKO_HUB_SESSION` (a local JSON file) or `AXIKO_HUB_URL` / `AXIKO_HUB_CODE`, with
`AXIKO_PLAYWRIGHT_MODULE` / `AXIKO_CHROMIUM_EXECUTABLE` when needed. Never publish session keys.
Native `ui_open`, `ui_eval` and `ui_screenshot` target the phone; they do not verify desktop layout.
