# Native shell contracts

English · [Русский](native-contracts.ru.md)

These are the shared contracts between the Kotlin shell and editable modules. Use
`axiko.native(method, ...args)` in a page, or `native(method, ...args)` in the script engine.
Check `native('shell.features')` before depending on an optional feature. An absent feature
must have an explicit fallback or an explanation to the owner; do not infer support from the
app's name or version. The implemented feature list is in
[Native.kt](../app/src/main/java/app/axiko/core/Native.kt).

See [module conventions](modules.md), [game development](game-development.md),
[store contracts](marketplace/implementation.md) and [backup](backup.md). The detailed reference
shipped to the agent is [agent/api.md](../app/src/main/assets/factory/agent/api.md).

## Notifications

| Feature | Contract |
|---|---|
| `notice.full` | `notification` events and `notices.active` / `notices.recent` contain `app`, `package`, `title`, `text`, `time`, `key`, `canReply`, `messages: [{sender,text,time}]`, `conversationTitle`, `isGroup`, `shortcutId`, `channel`, `category`, `user`, `id`, `tag`, `ongoing`, `group`. |
| `notice.removed` | `notification.removed` carries `{key,package,time,reason}`. Reasons: `click`, `cancel`, `app`, `other`. `time` is removal time. |
| `notice.open` | `notices.open(key)` returns whether the retained `contentIntent` could be launched. The library equivalent is `axiko.notifications.open(key)`. |
| `notify.actions` | `axiko.notify(options)` returns a string ID; reuse `options.id` to replace the notification. `notify.cancel(id)` removes it. |

Every update of the same notification key is another event; the module decides how to deduplicate
it. Missing `title` and `text` are empty strings; optional conversation/channel/shortcut/category,
tag and group fields may be `null`. An empty `messages` array means no MessagingStyle;
`sender: null` denotes the owner's message when the platform can identify it. `user` is the Android
profile ID. Axiko's own notifications and group summaries are not forwarded. The shell retains
intents for the last 300 notifications; `notices.open` returns `false` if the intent is absent or
revoked.

Removal reasons distinguish a tap (`click`), dismissal by the owner including clearing a group
(`cancel`), removal by its app (`app`) and other causes, including Axiko dismissal or timeout.

Notification options:

- `actions: [{title,call,fn,args,reply}]`: up to three actions. Both `call` and `fn` are required.
  The shell invokes `require(call)[fn](args, replyText)` without opening Axiko. `args` is JSON;
  the second argument contains the inline answer when `reply: true`.
- `ongoing`, `when` in milliseconds, `chronometer`, `countdown`, `progress: {max,value}`, `open`.
- `channel`: `quiet`, `normal` or `alarm`. `silent: true` selects the quiet channel unless a
  channel is specified. Alarm bypass of Do Not Disturb depends on Android policy access and
  the owner's channel settings; it is not guaranteed by the field alone.

An ordinary notification is removed before its action runs; an ongoing one remains. The shell
refreshes an inline-replied notification if its handler did not do so. Android may still allow
the owner to dismiss an ongoing notification. The simple `axiko.notify('text')` form remains valid.

## Secrets and authenticated requests

The feature is `secrets.sealed`. Use secrets without displaying them.

- Provider keys are encrypted under `provider.<id>`, not stored in plain `models.providers`.
  Provider records expose `hasKey`; legacy consumers may see the mask `••••••••`. Setting a
  new `key` replaces it; an empty string or the mask keeps it; `dropKey: true` removes it.
  `models.resolve` exposes `hasKey`, not the value.
- Shell-owned names (`provider.*`, `mcp.*`, `oauth.*`, `hf.token`, `backup.password`) are rejected
  by script `secret.get`, `secret.set`, `secret.remove` and omitted from `secret.names`.
  `secret.has(name)` tests presence. `secret.ask` obtains a value through a native owner dialog.
- Module requests can refer to `{{secret:name}}` in the URL, headers or body of
  `axiko.http.request`. The shell's `http.secret` path substitutes the value, requires HTTPS
  and redacts secrets from the response. Direct access to module-owned secrets is still
  supported for compatibility; prefer references in new code.
- Agent tool results, MCP tool results and error text are redacted. Existing saved chats and
  tasks are cleaned on the corresponding migration. Redaction covers complete values,
  JSON/URL representations and sufficiently long prefixes; do not treat it as an invitation
  to return sensitive material.
- Rhino does not expose the direct `Vault`, `Models`, `java.security.KeyStore`,
  `java.security.KeyStoreSpi` or `android.security.*` entry points. This is defense in depth,
  not a sandbox: reflection and the broader legacy runtime remain powerful. The store's
  `secrets.access` rule also reviews attempts to bypass these boundaries.

The LAN access code remains a normal setting. Review of a package and redaction do not prove
that intentionally malicious code cannot disclose data; see the [review rules](marketplace/review-rules.md).

## Scheduling, lifecycle and device state

| Feature | Contract |
|---|---|
| `off` | `off(name,key)` removes an event subscription; `off(name,fn)` also removes matching function subscriptions. |
| `job.once` | `job(key,{once: epochMilliseconds},fn)` schedules one execution. A past deadline runs immediately; an execution missed while the phone was off runs after boot. |
| `vibrate.pattern` | `axiko.vibrate([pause,duration,pause,...])` plays a non-repeating pattern. A number still means one vibration in milliseconds. |
| `app.visible` | Pages and the engine receive `{on}` when Axiko becomes visible/hidden. WebViews and their timers are paused while the app is unseen. |
| `dnd` | `dnd.get` returns `{filter: 'all'\|'priority'\|'alarms'\|'none',access}`; `dnd.set(filter)` returns `true` or fails; `dnd.request` opens the access settings. |

The one-shot callback is removed before invocation, so it can schedule its successor. A fired
deadline is remembered: registering the same key and deadline again does not execute it twice.
It is absent from `job.list`; `unjob` clears its remembered state too.

Visibility follows activity start/stop. A system dialog over Axiko is not a background transition;
a screen going dark is. Modules must stop visual work when hidden. In the web hub, page visibility
also follows the browser and containing panes; use the common page transport rather than another
independent event stream.

Setting the current DND filter is a no-op success. The shell first tries the notification listener,
then policy access; unknown filter values fail. Do not repeatedly request permissions or keep
re-registering sensors and jobs to work around missing access.

## Speech

- `speech.stream`: `speech.stream.start({maxMinutes})` and `speech.stream.stop`. Streaming needs
  a local recognizer and is mutually exclusive with ordinary listening and live conversation.
  The default maximum is 10 minutes; the accepted range is 0.1–180. Wake-word listening pauses
  during the stream and resumes afterwards.
- Each VAD phrase produces `speech.phrase: {text,startMs,endMs,time}`. The first two times are
  offsets from the stream start; `time` is the phrase start in epoch milliseconds.
  `speech.stream: {active,error?}` announces state changes. `speech.status` includes `stream`.
- `transcribe.segments`: `speech.transcribe(path,{segments:true})` returns
  `[{startMs,endMs,text}]`, with offsets from the file start and empty phrases omitted. Without
  this option the result is text.
- `speech.render`: `speech.render(text,path,mode?)` writes audio and returns its actual path.
  Modes are `cloud` and `local`; omitting one follows voice settings and available providers.
  The actual extension may differ from the requested path. Local output is WAV; raw cloud PCM
  is wrapped as WAV, while encoded output retains its format. Cloud rendering uses the `tts`
  role and its usage accounting. Empty text or no usable voice produces an error.

## UI, mascot and visual extensions

| Feature | Contract |
|---|---|
| `button.face` | `ui/button/index.html` draws the chat button appearance. `button.face(on)` returns `{bar}` and `button.edge` broadcasts `{bar}`. Native controls retain press/long-press handling and recovery access; safe mode uses the shell fallback. |
| `launcher.icon`, `launcher.icons` | `launcher.icon` reads the active ID, `launcher.icon.set(id)` switches it, and `launcher.icons` enumerates `{id,on,image}`. Use that list instead of assuming a fixed number of launcher aliases. |
| `bubble.looks` | `bubble.looks` returns `{now,looks:[{id,title,on,image}]}`. `bubble.look.set(id)` selects; `bubble.refresh` reloads definitions from `ui/bubble/`. |
| `ui.scale` | `ui.scale` returns `{value,min,max,step,wide}`; `ui.scale.set(percent)` updates the corresponding narrow/wide preference. The shell coordinates page viewport and native control scale. |
| `ui.probe` | `ui.openWait(path,waitMs?,width?)`, `ui.eval(code,waitMs?,owner?)`, `ui.shot(selector?,owner?)` use a separate probe by default. The owner screen is selected explicitly with `owner: true`. Probe pages expose `window.axikoProbe === true`. |
| `theme.decor` | `theme.decor(css)` writes `themes/_decor.css`; an empty string removes the decoration. It layers after the selected theme without changing that selection; safe mode omits it. |
| `widget.image` | A module widget may return `{title,text,open,image}`. A missing image degrades to text; supported Android image formats are reduced to widget size. |
| `home.cards` | `module.json.home` points to a card HTML page; `homeHeight` defaults to 120. `modules.list` exposes the resolved path and height. |

Agent `ui_open`, `ui_eval`, `ui_console`, `ui_screenshot` and `ui_check` use the probe;
`ui_show` explicitly presents a page to the owner. Probe checks do not establish desktop-browser
behavior. Home-card pages use the same bridge and events as other pages; handled taps and
interactive controls must not be turned into unintended module navigation.

The `mascot` event notifies pages about edits under `ui/mascot/` or `ui/button/`.
`mascot.changed` can request it explicitly. Native overlays are outside the page's CSS stacking
context: page dialogs and tours must keep controls out of their hit areas. More in [brand.md](brand.md).

## App updates

With `app.update`, `update.state` returns the current status of the public Axiko release check.
`update.check` starts a manual check and returns the current snapshot. Pages can refresh on
the `update.changed` event, which carries a new snapshot. Opening the Android app triggers
the automatic check when at least 24 hours have passed since the last attempt.

Both methods are available to pages, scripts and the paired web hub.

The snapshot contains `installedVersion`, `status`, `canInstall` and `downloaded`. Status values
are `idle`, `checking`, `current`, `available`, `unavailable`, `downloading` and `error`. It can
also include `checkedAt` in epoch milliseconds, a diagnostic `code` and release fields
`version`, `notes`, `url` and `bytes`. Treat release notes as display text.

`update.install(version)` is accepted only from the top-level Axiko Settings page in the
foreground Android app. A `true` result means the request was accepted. The shell verifies
the APK against the offer, including its SHA-256, package ID, version, architecture and pinned
signing certificate. The system installer asks the owner to confirm an in-place update.
Package updates for modules and mods use the store contracts below.

## Store and backup

The shell advertises separate features for repository installation (`store.v1`), adopting an
existing module (`store.adopt`), paged catalogs (`store.pages`), host-defined package kinds
(`store.kinds`), mods (`store.mods`), agent adaptation (`store.carry`) and sharing modifications
(`store.share`). Do not infer that an older client has all of them from `store.v1` alone.

Catalogs, plans, conflict resolution, owner decisions and scoped agent tools are specified in
[marketplace/implementation.md](marketplace/implementation.md),
[catalog-pages.md](marketplace/catalog-pages.md) and [mods.md](marketplace/mods.md).
An owner-removed factory source must not be silently restored. Backup operations and encrypted
restore are covered by [backup.md](backup.md); passwords are collected by the shell, not passed
as script arguments.

## Usage data, permissions and language

`lib/usage.js` provides `access()`, `events(since,until)`, `daily(since,until,events?)` and
`sessions(since,until,events?)`. Events contain `{package,app,type,time}`; daily results contain
`{package,app,ms,opens}`; sessions contain `{from,to,packages}`. Passing preloaded events avoids a
second platform query. Without access, event reading fails with an explanation. Duration before
the requested interval is clipped; a desktop/notification-shade gap is not charged to the last app.

Manifest declarations do not grant access. Notification, screen, usage, DND and Health Connect
features require the appropriate owner-granted system access. The Health Connect rationale
entry is a protected Android activity alias, not an ordinary permission shortcut.

Module dictionaries use `i18n-<language>.json`. English UI support is required for reusable
modules; Russian and other languages are supplementary. English-source modules may supply
`i18n-ru.json`; missing dictionaries must not prevent use. See [modules.md](modules.md) for release
conventions and [the runtime API](../app/src/main/assets/factory/agent/api.md) for individual calls.
