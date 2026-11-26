# Brand, mascot and interface wording

English · [Русский](brand.ru.md)

The product is **Axiko**, spelled in Latin letters in every interface language. The owner
names the agent independently; its default name is also Axiko. Product naming must not
replace an owner's chosen agent name.

## Character and editable assets

The factory mascot is an original, small axolotl: helpful, mildly curious and warm. Its
sticker style uses a thick rounded outline, flat fills and a white border readable on both
dark and light backgrounds. Default colors are body `#C9B6FD`, gills and mouth `#F98FAC`,
outline `#44308A`, sticker edge `#FFFFFF`. Each of its six gill branches can indicate activity.

The mascot belongs to the workspace, not to a fixed UI requirement:

| File | Contract |
|---|---|
| `ui/mascot/mascot.json` | Name, `enabled`, `alive`, `button`, pose mapping, colors and phrases |
| `ui/mascot/*.svg` or replacement images | Editable pose artwork |
| `ui/mascot/index.html` | The owner's mascot settings |
| `ui/button/index.html` | The drawn face of the native chat control |

- `enabled: false` disables the mascot; screens still work. Do not delete factory files as
  a way of disabling it, because a future factory install can restore missing files.
- `alive: false` makes it still. Also respect the system's reduced-motion preference.
- `poses` maps semantic names such as `idle`, `thinking`, `asking` and `oops` to artwork.
  A replacement character may provide only `idle`; missing poses must fall back gracefully.
- `colors` overrides the character's theme colors. `phrases` contains its optional copy;
  `ui.mascot.say(key, fallback)` must still return useful text without a character.
- `button` chooses `peek`, `badge` or `round`. Legacy `true`/`false` mean `peek`/`round`.
  Missing artwork must fall back without removing the chat entry point.

`ui.mascot()` returns an empty hidden element when disabled. No screen may depend on it.
Characters without marked gills can use activity dots instead. Keep user replacement images,
colors and phrases editable; do not duplicate a second hard-coded mascot in a screen.

## Chat control and launcher

In `peek` mode, `peek-body` rises behind an edge and two copies of `peek-paw` rest over it.
At rest it peeks; during a conversation or agent work it climbs up. A badge shows the head
in a round control, while `round` uses the shell's plain control.

The button page only draws. Tap and long press belong to the shell's native view above it;
long press opens recovery. Safe mode or missing artwork must leave a native way into chat.
A WebView z-index cannot cover that native hit area. Position guide/dialog controls outside
it, including on short screens and with large text.

Launcher icons are APK resources, not workspace artwork. `core/Launcher.kt` selects one
activity alias and disables the others. Current choices are `mascot`, `night`, `light`,
`pink`, `plain` and `plain-night`. New launcher art requires rebuilding the APK; it does
not automatically follow the workspace theme.

## Theme contract

SVGs use `--mascot-body`, `--mascot-gills`, `--mascot-line`, `--mascot-mouth`,
`--mascot-sticker` and `--mascot-light`. Color precedence is:

1. Owner overrides in `mascot.json`.
2. Explicit mascot tokens in the selected theme.
3. Colors derived by `Themes.render` from the theme accent: accent-colored gills, a light
   body and dark outline, where CSS color mixing is supported.
4. The artwork's intrinsic defaults.

Use the same character and token contract in the browser hub. The hub has its own side chat,
not the phone's floating chat control. With the character disabled, its regular controls and
layout remain usable.

## State and wording

Use poses to reinforce actual state, not invent progress: `thinking` for model work,
`searching` for reading/search, `working` for edits/tools, `building` for delegated work or
drafts, `plan` for plans, `asking` for owner input, `listening` for voice, `oops` for an error,
`safe` for recovery, and `celebrating` after a completed change. Idle/night and brief reactions
can add life without blocking interaction. Activity lights represent active conversations
and delegated work, not arbitrary animation counts.

Write character phrases briefly, warmly and honestly. Body metaphors belong only in optional
character copy. Controls keep direct labels such as Restore, History and Settings. Do not
promise an unverified result. In Russian, address the owner politely as «вы» and refer to
the mascot as «он». Interface guidance stays neutral and understandable without a mascot.

## Guided tours

Use `ui.tour.offer` for a screen that benefits from an introduction. The final step points to
chat for further questions. Skipping marks tours as skipped; the owner can reset them in
Settings. Hub frames wait for the hub's own tour, and phone-only steps use `phone: true` so
they do not appear on desktop. Long explanations must scroll without hiding navigation.

## Artwork maintenance

Canonical source poses and tracing specifications are in
[`assets/brand/mascot/poses/`](../assets/brand/mascot/poses/). Runtime SVGs remain in the factory
workspace. Generation references, rejected variants and dated review sheets are not project rules.

- [`scripts/brand/build.py`](../scripts/brand/build.py) rebuilds poses from canonical sources.
- [`vectorize.py`](../scripts/brand/vectorize.py) traces artwork using its layer specification.
- [`icon.py`](../scripts/brand/icon.py) builds launcher layers from the factory `head.svg`.
- [`compare.py`](../scripts/brand/compare.py) and [`preview.py`](../scripts/brand/preview.py)
  render comparisons and UI previews. Keep generated review output under `build/artifacts/`.

Check changed art on dark/light themes, at small sizes, with animation disabled and with
replacement or disabled mascot configuration. Review generated SVG/resource changes before
shipping them; a successful conversion alone is not a visual check.
