English · [Русский](catalog-pages.ru.md)

# Paged catalogs: cards, details and media

The current v1 catalog uses an `index.json` header referencing pages of package cards. The legacy single file containing every package is not accepted by the client. Paging keeps refreshes and rendering bounded as the catalog grows, while details and media load when needed.

## Published layout

All referenced catalog/media paths are relative to the directory containing `index.json`. Paths may not escape it.

```text
index.json                       Repository header and page references
catalog/p-<hex>.json              Pages of package cards
catalog/details/<id>.json         Details for one package
<package folder>/store/…         Listing images and text, excluded from the package ZIP
```

### Index header

The structure below abbreviates hashes and URLs for readability; [examples/index.json](examples/index.json) is the metadata fixture.

```json
{
  "schemaVersion": 1,
  "repository": {
    "id": "billerens.axiko-store",
    "title": "Axiko Store",
    "homepage": "https://…",
    "description": "Optional text, up to 600 characters"
  },
  "packageCount": 40,
  "pages": [
    {"path": "catalog/p-0.json", "sha256": "…", "size": 51234, "count": 40}
  ]
}
```

There may be at most 256 pages, 1 MiB per page, 2,000 cards per page and 20,000 packages total. The builder assigns packages using leading hexadecimal characters of SHA-256 of the package ID, choosing 1, 16 or 256 pages according to package count. Updating one package therefore normally changes one page. The client follows the published page list; it does not require this particular sharding algorithm.

### Page and card

A page is `{ "schemaVersion": 1, "packages": [ ... ] }`.

Required card fields are `id`, `kind`, `title`, `summary`, `version`, `publisher`, `license`, `manifestUrl`, `archive` and `compatibility`. A mod additionally requires `extends`. Optional listing fields are:

| Field | Content |
|---|---|
| `tags` | At most 12 lowercase strings, 32 characters each. |
| `locales` | Up to 16 additional languages: `{ "<language>": { "title"?, "summary"?, "tags"? } }`. |
| `icon` | `{path, sha256, size, type}`; up to 128 KiB; SVG, PNG, WebP or JPEG. |
| `updated` | Release date, `YYYY-MM-DD`. |
| `details` | `{path, sha256, size}` referencing a details file of at most 256 KiB. |

### Package details

```json
{
  "schemaVersion": 1,
  "id": "merge-forge",
  "version": "0.1.1",
  "description": "Markdown, up to 20,000 characters",
  "screenshots": [
    {
      "path": "merge-forge/store/shots/1.webp",
      "sha256": "…",
      "size": 81234,
      "type": "image/webp",
      "width": 1080,
      "height": 2340,
      "caption": "Optional caption"
    }
  ],
  "changes": [
    {"version": "0.1.1", "date": "2026-11-17", "notes": "Markdown, up to 4,000 characters"}
  ],
  "links": {"homepage": "https://…", "source": "https://…", "support": "https://…"}
}
```

Only `schemaVersion`, `id` and `version` are mandatory. There may be at most 8 screenshots, 1 MiB each, in PNG/WebP/JPEG, and 20 changelog entries.

Additional languages live in `locales`, also with at most 16 languages:

```json
"locales": {
  "ru": {
    "description": "Описание в Markdown",
    "changes": {"0.1.1": "Заметки этой версии"},
    "captions": ["Подпись первого снимка", null]
  }
}
```

`changes` maps versions already present in the base `changes` array to translated notes. `captions` follows screenshot order; `null` keeps the English caption. A translated description requires an English description, and a translated caption requires an English caption on that screenshot. The 256 KiB limit applies to the entire details file, including translations.

## Authoring a listing

Place listing fields next to the package's `id`, `kind` and `version` in `store.json`:

| Field | Builder behavior |
|---|---|
| `summary` | Short card description; otherwise use `description` from the runtime descriptor. |
| `tags` | Card tags. |
| `icon` | Package-relative icon path; otherwise try `store/icon.*`, then the game's `icon.svg`. |
| `screenshots` | Paths or `{file, caption}` objects; otherwise use `store/shots/*` in filename order. |
| `links` | `homepage`, `source`, `support`. |
| `locales` | Additional-language listing text as described below. |

Use `store/description.md` for the full description and `store/changelog.md` for entries headed `## <version> — <YYYY-MM-DD>`. `store/` is excluded from the package ZIP, so changing only listing material does not require a package version increase.

The builder inspects image content, byte size and pixel dimensions, emits hashed media references and produces `index.json`, `catalog/` and a report. Publish the validated index and catalog directory at the source root.

## Listing language

English is required. Primary `title`, `summary`, `tags`, captions, `store/description.md` and `store/changelog.md` contain English text. Russian and every other language are supplementary and optional; they never replace the primary fields.

```json
{
  "id": "clock",
  "kind": "module",
  "version": "1.0.0",
  "title": "Clock",
  "summary": "A clock for the home screen.",
  "tags": ["time", "widget"],
  "screenshots": [
    {"file": "store/shots/1.webp", "caption": "Night", "locales": {"ru": {"caption": "Ночь"}}}
  ],
  "locales": {
    "ru": {"title": "Часы", "summary": "Часы для главного экрана.", "tags": ["время", "виджет"]}
  }
}
```

| Text | English source | Additional language `<language>` |
|---|---|---|
| Title, short description, tags | `title`, `summary`, `tags` | `locales.<language>.title`, `.summary`, `.tags` |
| Full description | `store/description.md` | `store/description.<language>.md` |
| Changelog | `store/changelog.md` | `store/changelog.<language>.md`, only versions present in English |
| Screenshot caption | `caption` | Screenshot's `locales.<language>.caption` |

- Language codes use two or three lowercase letters and an optional uppercase region, such as `ru`, `de` or `pt-BR`. Do not use `en` in `locales`: English is the primary text.
- A translation may be partial; missing text falls back to English. A translation without its English source is rejected, including a description without `store/description.md`, a caption without the base caption or translated tags without base tags.
- **A tag's identity is its English text.** Counts and filters use that identity, independent of UI language. `locales.<language>.tags` is a parallel array of the same length and order: `tags[i]` displays as the corresponding translated string. Localized labels may use capitals, but no surrounding spaces or duplicates.
- Limits apply to translations too: title 120 characters, summary 600, tag 32 with at most 12 tags, description 20,000, version notes 4,000, caption 200. Card and details allow at most 16 additional languages each. Page/details byte limits cover all locales together.
- The builder places translated title/summary/tags in card `locales`, and description/version notes/captions in details `locales`.

## Package kinds

The shell owns `module` (a directory under `modules/` with `module.json`) and `mod` (a customization of another package; see [mods](mods.md)). Other kinds are declared by the installed module that hosts them, using `hosts` in its `module.json`:

```json
"hosts": [{
  "kind": "game",
  "title": "Games",
  "item": "Game",
  "descriptor": "game.json",
  "engine": "gamesEngine",
  "version": 1,
  "screen": true,
  "reserved": ["archive", "daily", "engine", "links", "template", "ui"]
}]
```

| Field | Meaning |
|---|---|
| `kind` | Lowercase Latin letters, digits and hyphens. A host cannot claim `module` or `mod`. |
| `title`, `item` | Host-provided plural and singular labels displayed in the store. |
| `descriptor` | File through which the hosted package describes itself. |
| `engine`, `version` | Name and installed version of the host's rules; absent for an unversioned kind. |
| `screen` | Require an HTML entry in the hosted package descriptor. |
| `reserved` | IDs belonging to the host itself that packages may not take. |

Hosted packages install only into `modules/<host>/<id>`. A declaration cannot choose another destination. If several modules declare the same kind, the first host by ID wins. Without an installed host, the catalog marks its packages incompatible with a reason. A catalog package cannot replace a module hosting other packages or the store itself.

A card/manifest declares at most one version beyond the app API, using its host's engine name: for example `"compatibility": {"minAxikoApi": 1, "gamesEngine": 1}`. Format schemas do not hardcode every hosted kind; the installed host supplies the requirements. Games are hosted by `modules/games/module.json`. Factory host declarations remain effective when an edited installed descriptor has lost its `hosts` field; an absent host does not host packages.

For building, authors repeat host declarations in the source root's `kinds.json`, for example:

```json
{"game": {"host": "games", "descriptor": "game.json", "engine": "gamesEngine", "version": 1}}
```

The client does not read this author-side file.

## Client behavior

- **Refresh:** load the header and only pages whose hashes are absent from cache. Verify each page's size, SHA-256 and metadata. Accept a complete new catalog or retain the old one. Publisher continuity and immutable-release checks still apply.
- **Browsing:** keep the catalog locally for offline search/filtering. `store.catalog({query, kind, sourceId, tag, only, sort, offset, limit})` returns `{items, total, offset, limit, counts, tags, kinds}`. Append the next slice near the list end instead of rebuilding all visible cards.
- **Filters:** derive kinds and tags, with their counts, from the catalog. Kind entries contain host labels `title`/`item`, `host` and `hosted`. Grid/list/compact presentation and ordering are owner choices and persist.
- **Details/media:** load on demand through `store.details(sourceId, packageId)` and `store.media(sourceId, packageId, 'icon' | 'screenshot', index)`. The shell resolves the verified reference, bounds the download, checks hash and content type, caches by hash and returns a `data:` URL. The page never fetches directly from the repository. Reject SVG scripts and event handlers; display SVG only as an image.
- **Language:** choose the UI's `I18n.language`, first exact locale, then language without region, then another variant of that language. Otherwise use the English base, also for missing translated fields. `store.catalog`, `store.details` and `store.installed` return selected display text without `locales`. Search matches both English and displayed text; sort uses the displayed title. Tag filters/counts use the English identity, while response tags are `{tag, label, count}`. Kind labels come from their host and are translated by UI dictionaries, not catalog locales.
- **Unknown fields:** the client validates fields it consumes in catalog headers, pages, cards, details and media, while ignoring unfamiliar optional fields. Required fields, `schemaVersion` and compatibility remain strict. Package manifests reject unknown fields. Author-side schemas and metadata tools are also strict; only the listing reader is forward-tolerant.
- **Untrusted text:** descriptions and changes use the shared safe Markdown renderer. Repository HTML is not executed.

Formal definitions: [index schema](index.schema.json), [page schema](page.schema.json) and [details schema](details.schema.json).
