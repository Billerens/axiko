# Game development conventions

English · [Русский](game-development.ru.md)

These rules apply to games hosted by the Games module. They complement the
[module conventions](modules.md), [native contracts](native-contracts.md) and
[package format](marketplace/repository-format-v1.md). The implemented engine is in
[`factory/modules/games`](../app/src/main/assets/factory/modules/games/); its runtime
[`AGENT.md`](../app/src/main/assets/factory/modules/games/AGENT.md) documents individual helpers.
Game-specific rules belong with that game's source and `guide.md`, not in duplicate project plans.

## Layout and identity

Start from [`engine/template`](../app/src/main/assets/factory/modules/games/engine/template).
The installed game lives in `modules/games/<id>/`; the catalog discovers `game.json` there.
The Games host declares the `game` package kind. A game is not a top-level module and does not
need an additional Home registration.

```text
game.json          identity, engine/save versions, entry, currencies, prestige, achievements
config.json        balance values and settings metadata
sim.js             deterministic shared simulation
ui/index.html      kit, engine and game scripts
ui/game.js         Game.start(), controls and rendering
ui/game.css        theme-aware styles
icon.svg           catalog icon
checks.js          standard and game-specific checks
content/*.json     built-in content
prompts/*.md       content-generation prompts and response schemas
fixtures/*.json    saves used to check migration
guide.md           optional detailed player guide
```

Use `Core.validId` as the implemented ID rule: 1–96 lowercase ASCII letters/digits/dots/hyphens,
starting with a letter or digit, with no `..` and no trailing dot or hyphen. Reserved IDs are
`engine`, `ui`, `links`, `daily`, `archive`, `template`. Legacy daily-game IDs have their own
`daily/YYYY-MM-DD` form; do not rename existing games or move their saves to satisfy a new package ID.

Keep package SemVer (`store.json.version`), game engine version (`game.json.engine`) and saved-state
schema version (`game.json.saveVersion`) separate. Follow the store's immutable-release rule when
any packaged file changes, including README or comments. Store packages put immutable resources
in their declared `assets/`; downloaded/generated resources and caches belong in game-owned data.
Do not package the owner's `data/` directory or overwrite it during an update.

## Simulation is portable and deterministic

`sim.js` runs in WebView, Rhino and the Node test harness. Use the template's shared module wrapper
and the engine-compatible subset: `var`, `function`, plain data, no `class`, `async`/`await`,
ES module `import`/`export` syntax, spread calls, or `for (let/const ...)`. CommonJS `exports`
in the template wrapper remain supported. The renderer can use browser APIs;
simulation must not depend on a DOM, Android object or a network request. `Core.lint(code,false)`
checks the shared subset.

- Random decisions in simulation use `ctx.rng` or the supplied `rng`, never `Math.random`.
  The engine preserves generator state. Cosmetic randomness must not consume the game RNG.
- Simulation time comes from `ctx.now` (Unix epoch milliseconds); `dt` and offline duration are seconds. Do not infer
  elapsed game time from a wall-clock read inside the simulation.
- Prices, growth, income, thresholds and durations belong in `config.json`; read their values
  through `ctx.config`, `cfg` or `g.config`. Keep content lists in data files.
- Use `Big` for unbounded currencies. Serialized state may hold ordinary numbers or strings
  such as `"1.5e12"`; `Big.of` accepts either. Do not convert a currency that can exceed `1e15`
  to `Number` for arithmetic.
- State is JSON data: no functions, `Date`, `Map`, `NaN` or `Infinity`. Put run-local data in
  `state.run`, layer state in `state.layers.<id>.state`, persistent game data in `state.meta`.

The main simulation hooks are:

| Hook | Responsibility |
|---|---|
| `init(ctx)` | Return `{run,layers?,meta?}` for a new game/run. |
| `tick(state,dt,ctx)` | Advance state in place; apply `ctx.mult` to income where the game supports it. |
| `offline(state,sec,ctx)` | Optional formula-based catch-up, returning `{gains?,events?}`. The engine has already accounted for offline efficiency in `sec`. |
| `prestigeGain(state,layerId,cfg)` | Compute the layer currency awarded by a reset. |
| `prestigeHint(state,layerId,cfg,info)` | Optional `true`/`false` recommendation; `null` leaves the engine's gain-ratio hint in place. This changes the hint, not eligibility or the reward. |
| `bonus(state,layerId,amount,cfg)` | Optional bonus preview for a chosen amount of layer currency. |
| `onPrestige(state,layerId,before,ctx)` | Optional transfer after the engine reset; `before` is the previous state. |
| `migrate(state,fromVer)` | Upgrade old saved-state schemas; required when supporting `saveVersion > 1`. |
| `validate(state)` | Return `null` or a description of an invalid state. |
| `summary(state,cfg)` | Optional catalog summary `{line}`. |
| `next(state,now,cfg)` | Optional future notification entries with IDs declared in the manifest. |
| `procedural(kind,rng)`, `validateStock(kind,item)` | Offline content fallback and extra validation of generated data. |

`ctx` also supplies link status, available stock, the offline flag and offline report support.
Export game actions such as `buy`, `tap` or `hammer` for both UI and bots rather than maintaining
two different implementations.

## Saves, migration and reset

The engine owns persistence, sequence numbers, backups, offline reconciliation and prestige.
Change state through `g.act(fn,options?)`; use `{now:true,reason,keep}` for an important action
that must be saved immediately, or `g.saveNow(reason?,keep?)`. Ordinary actions are debounced
with a maximum delay and mirrored to browser storage. Do not bypass the sequence check or replace
the owner's SQLite save history with a test state.

For a schema change, increment `saveVersion`, implement migration and add a previous-version
fixture. Preserve existing progress and unknown extension fields unless the change explicitly
requires a documented transformation. A visual redesign alone does not require a save migration.
Test the migrated save through the real loader and validation, not just a hand-written JSON edit.
When the installed game is older than a save, the engine offers recovery and a read-only result;
do not coerce that save into a new run.

Prestige resets the configured run/lower layers, increments the selected layer and preserves
`meta`, then calls `onPrestige`. Show what is lost, kept and awarded. Use the engine's confirmation
and backup path, including hold-to-confirm for destructive actions. A game's custom
`prestigeHint` can explain whether a reset is worthwhile without changing the reset contract.

## UI and progressive visuals

Build the page with `Game.start({sim,init,render,animate?,help?,root?,id?})`. Use the shared zones
`g.el.top`, `g.el.field`, `g.el.actions`; keep important actions reachable by a thumb. Render is
a view of state, not another simulation loop. Respect `g.readonly` in previews and recovery.

- Reusable games must work fully in English. English source text is acceptable; Russian-source
  games need complete `i18n-en.json`. Russian and other languages are optional dictionaries.
- Use kit color tokens and shared controls so the owner's theme remains usable. Board-specific
  art can have its own palette. Use kit/GameIcons assets rather than emoji for UI icons.
- Keep interactive targets at least 44 CSS px (standard game controls use 56), readable text,
  explicit labels and feedback. Preserve keyboard/desktop controls where the game supports them;
  do not make a browser game depend on a phone-only gesture.
- Check narrow and wide layouts and resizing. Keep edge gestures away from system edges;
  coordinate overlays with the native chat hit area. Let the engine choose the platform's
  screen mode instead of unconditionally forcing desktop fullscreen.
- Provide concise `game.json.help` and, when needed, a `guide.md` with controls, currencies,
  progression, reset effects and non-obvious mechanics. Label configurable numbers as defaults.

For a decorative scene, create `Game.scene(...)` once in `init` and update a small scene model
from `render`. Drawing must not mutate saves, advance time, consume game RNG or change balance.
Distinguish unlocked, purchased, active and prestige-retained objects. The world should reveal
progression gradually; do not show the entire late game on the first screen.

Aggregate large populations and purchases, cap effects, and show an offline summary instead of
replaying thousands of events. `scene.pulse` follows a confirmed action. Dispose scenes when
removed; the shared renderer handles visibility, reduced motion and frame/pixel budgets.
Fast gameplay keeps its own supported `animate` path; decorations share the real camera and
entity positions rather than inventing another view of the rules.

## Offline play, AI and integrations

Games must work without a network. Bundle readable code and local assets; no remote executable
scripts or CDN dependencies. Keep model-generated stock outside the save, validate its schema
and values, and treat prompts, returned text and art as data rather than executable instructions.
Use `g.stock.take(kind)` with `sim.procedural` or built-in content when stock is empty. A model
failure or exhausted budget must not block the core loop. Respect the owner's generation,
animation, network and spending settings.

Use the engine's art sanitization and image helpers. Animated artwork needs a still/reduced-motion
fallback. Avoid inserting independent generated SVGs with colliding IDs into one inline DOM.

Passenger, steps and daily bets are optional manifest integrations. Use their public engine API
and mailbox, not edits to another game's save. Games remain playable when an integration or its
upstream module is absent. Do not copy integration prices and timing into a game: they are
settings owned by the shared engine.

## Verification

Run the game's `checks.js` using `engine/checks-kit.js` and add checks for its own mechanics.
The standard suite covers a new state, JSON round-trip, migration fixtures, 12-hour offline
agreement, pacing, network independence and narrow/wide screen opening. Formula-based offline
progress must agree with stepped simulation within 1% in the standard offline check. If a game
has a different custom check, make its tolerance explicit rather than assuming a configurable
standard tolerance.

The standard idle bot targets a first purchase within 10 seconds and first prestige in trips
3–6. These are test defaults, not a universal design for every genre: supply a meaningful bot or
`pace:false` with game-specific pace checks when the standard scenario does not fit.

Check early, developed and late states, successful/denied purchases, an event, reset, restart,
offline return and old saves. On the device, use `module_check('games/<id>')` and
`ui_check` with `modules/games/ui/preview.html?game=<id>&w=both`. The probe is separate from the
owner's screen; use `ui_show` only when presenting the result. Independently check the web hub
when changing desktop behavior.

Run potentially destructive checks against synthetic data in a test installation. Drafts isolate
code edits, not personal data or native permissions. A store agent review must not execute a
candidate's tests in the owner's workspace. Report what ran and what remains unverified.
