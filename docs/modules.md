# Module conventions

English · [Русский](modules.ru.md)

This is the contract for reusable modules, not a catalog of proposed features. Maintained
packages and their own guides live in [Axiko Store](https://github.com/Billerens/axiko-store).
Games also follow the [shared game-engine contract](game-development.md).

## Files and entry points

A module is an editable folder under `modules/<id>/` in the workspace:

```text
module.json
main.js                 # optional Rhino backend
ui/index.html           # screen, when needed
ui/card.html            # optional Home card
checks.js               # module checks
prompts/                # optional model instructions
assets/                 # immutable bundled resources
i18n-en.json             # required for Russian-source UI strings
```

A minimal descriptor for a screen:

```json
{
  "id": "my-notes",
  "title": "Notes",
  "description": "Short personal notes",
  "entry": "ui/index.html",
  "autostart": false
}
```

Use stable IDs. Browser pages use `window.axiko`; backend logic uses `require('lib/axiko')`
and CommonJS exports. HTML runs in WebView or the browser hub; `main.js` runs in Rhino.
Keep browser-only APIs out of backend code. Shared game simulation has a stricter JavaScript
subset defined in the game contract.

`start()` registers engine listeners and jobs. Set `autostart: true` only when needed, and
keep startup fast and failure-isolated. The boot library also supports explicit starters.
A hidden UI page must not act as a background service.

## Data and updates

- Mutable state, databases, downloads and saves belong in `data/`, separate from code.
  Workspace history restores code, not personal-data writes.
- Preserve old values and unknown extension fields in data migrations. Test older saves
  and settings as well as clean installs.
- Drafts share `data/` with the live app. They isolate code, not migrations or side effects.
- Updates preserve local edits. Use prepared store plans and conflict resolution instead
  of replacing a module directory directly.
- Package SemVer and game save-schema versions have different purposes. Follow the
  [repository publication rules](marketplace/repository-format-v1.md).

## Language and appearance

Reusable modules and games must work fully in English: either use English source strings or
provide a complete `i18n-en.json` for Russian source strings. Russian and other languages are
optional `i18n-<code>.json` files. For an English-source module, `i18n-ru.json` maps English to
Russian. One-off personal screens may use the owner's language.

Use `ui.t('Text with {}', value)` for dynamic interface strings. Show owner/model content
with `ui.raw(text)` so translation does not reinterpret it as interface copy. Store listing
defaults are English too; translations follow the [catalog-page format](marketplace/catalog-pages.md).

Application-style screens use kit controls and theme variables (`--bg`, `--surface`, `--ink`,
`--accent`, and related tokens). Avoid fixed colors that break dark or light themes. Provide
readable labels, relevant keyboard controls and layouts for narrow phones and desktop.
Specialized scenes and games may have their own visual language when the task requires it;
ordinary application controls must remain usable.

A Home card declares `home` and optionally `homeSize` in `module.json`. Owners resize and
reorder cards, so their pages must fill the frame and adapt to width and height. A launcher
widget exports `widget()` returning `{title, text, open, image}` and refreshes through
`axiko.widget.refresh(id)`. Verify these as distinct surfaces.

Onboarding can use `ui.tour.offer`. Copy must work without the mascot. Keep controls outside
the native chat button: CSS z-index cannot cover a separate native view above WebView.

## Background work, resources and credentials

Use engine jobs for background work and suspend visual loops when their page is hidden.
Hub visibility follows its browser tab and active panel, not the phone's screen state.
Optional network content must not delay the module's core usable state.

Store packages contain readable local source and resources, with no remote executable code.
Mutable downloads belong in `data/`; retain third-party licenses and exclude personal files
and credentials from archives.

Ask for secrets with protected shell input, check their presence, and reference them in
supported native requests. Do not ask for keys in chat or expose values in code or logs.
Modules remain trusted code with the app's capabilities; see the
[store trust model](marketplace/implementation.md).

## Checks and publication

Keep meaningful checks in `checks.js`; test pure/shared logic on the desktop where possible.
Verify UI behavior and persistence in a test installation. Static store review does not run
package checks on personal data.

The [repository format](marketplace/repository-format-v1.md) defines layout, limits, manifests,
update ownership and conflicts. [Review rules](marketplace/review-rules.md) define audit findings;
localization and presentation are release-quality requirements, not extra security finding codes.

Public shell contracts are described in [native-contracts.md](native-contracts.md).
The detailed on-device reference is kept with the implementation in
[`factory/agent/`](../app/src/main/assets/factory/agent/) and the
[game agent instructions](../app/src/main/assets/factory/modules/games/AGENT.md).
