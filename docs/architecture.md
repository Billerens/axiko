# Architecture

English · [Русский](architecture.ru.md)

Axiko is an Android shell around an editable workspace. The [project overview](../README.md)
explains the product; this document defines the boundaries developers must preserve.

## Layers and storage

| Layer | Location | Responsibility |
|---|---|---|
| Native shell | `app/src/main/java/app/axiko/` | WebView, Rhino, agent loop, Android services, credentials, installation and recovery |
| Factory workspace | `app/src/main/assets/factory/` | Default screens, libraries, themes, templates and agent instructions |
| Working workspace | Device `files/axiko/` | Files the owner and agent edit and the app runs |
| Personal data | Device `files/data/` | Module state, game saves, databases and downloads |
| Shell state | Device `files/state/` | Conversations, tasks, factory hashes and store state |
| Drafts / history | Device `files/drafts/`, `files/history/` | Proposed code changes and recoverable workspace snapshots |

The application ID and Kotlin namespace are `app.axiko`. Pages use `https://axiko.local/`,
`window.axiko` and the engine library `require('lib/axiko')`. Keep native, browser and engine
contracts in sync when changing these interfaces.

`Workspace.installFactory` updates a working file only when it is missing or still matches
its previously installed factory hash. Locally modified files remain intact. Factory and
working UI share one source tree; there is no separately maintained recovery UI.

## Execution and trust

HTML/CSS/JavaScript pages run directly from the workspace in WebView. Browser code talks to
the shell or engine through the injected bridge. Rhino runs module logic, tools and background
work and can access Android and Java classes. Shell changes require an APK rebuild; workspace
changes normally require only a page or module reload.

Installed modules run with the app's privileges, not in a per-package sandbox. Declared
capabilities describe access; they do not enforce isolation. Source hashes, installation
validation and agent review cannot prove safety. See the [store trust model](marketplace/implementation.md)
and [review rules](marketplace/review-rules.md).

Use the shell's credential interfaces: protected system input, supported secret references
in HTTP requests, and no keys in code, chat or logs. Data sent to a selected remote model
leaves the device; an on-device model is a separate choice. These API protections do not
turn the trusted module runtime into a sandbox.

## Agents and background work

The main agent lives in `agent/`; additional agents live in `agents/<id>/`. Each has its own
conversations and can have its own model and tool set. Models are assigned by role, with
OpenAI-compatible providers and an optional on-device runtime.

The native conversation loop handles streaming, tools, questions, plans, delegated work,
spending limits and context compaction. An ordinary editing turn snapshots workspace code.
Drafts isolate proposed code changes but share personal `data/` with the live app: they are
not a safe place for destructive data migrations.

Background work belongs in engine subscriptions and scheduled jobs. A module's `start()` is
registered explicitly or through `module.json` with `autostart: true`. Safe mode skips
workspace boot scripts. Do not rely on a visible page or a continuous UI timer for background work.

## Recovery and interfaces

[Snapshot history](snapshot-storage.md) restores workspace code, not module data or chats.
An [encrypted backup](backup.md) carries personal state and has a different scope. Android
permissions, downloaded models and WebView storage have separate restore limitations.

The native recovery control must remain usable when workspace pages break. Safe mode serves
the factory UI and suppresses workspace boot scripts. Keep recovery outside editable code.

- The [web hub](web-hub.md) serves modules, games, chat and Studio from the phone over LAN;
  `/mcp` exposes tools. LAN access uses the owner's connection key.
- [Modules](modules.md) provide screens, home cards, widgets and background logic. Games
  follow the [shared game-engine contract](game-development.md).
- [Mascot and theme conventions](brand.md) keep the character optional and editable.
- [Repository packages](marketplace/repository-format-v1.md) use an index, manifests, hashes
  and a prepared installation plan. Updates must preserve local edits and personal data.

[Development conventions](development.md) list build and verification commands. Runtime
references supplied to the agent live beside the implementation in
[`factory/agent/`](../app/src/main/assets/factory/agent/).
