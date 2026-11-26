English · [Русский](README.ru.md)

# Catalog author tools

Use Python 3.10+ with the dependencies in `requirements.txt`. Run the commands below from the Axiko repository root in PowerShell 7. In the standalone [axiko-store repository](https://github.com/Billerens/axiko-store), the equivalent tools live under `tools/store`.

```powershell
python -m pip install -r scripts/store/requirements.txt
python scripts/store/validate_metadata.py --index docs/marketplace/examples/index.json
python scripts/store/validate_metadata.py --package docs/marketplace/examples/package.json
python -m unittest discover -s scripts/store/tests -p 'test_*.py' -v
```

## Build a catalog

`build_catalog.py` reads source directories containing `store.json` and produces release ZIPs, external manifests, a paged `index.json`/`catalog/` and `build-report.json`. It makes no network requests, publishes nothing, installs nothing and executes no package code. The output directory must be outside the source store.

```powershell
python scripts/store/build_catalog.py `
  --store ../axiko-store `
  --out ../axiko-store-dist `
  --download-url-template 'https://github.com/Billerens/axiko-store/releases/download/{id}-v{version}' `
  --publisher billerens `
  --license-id Apache-2.0 `
  --licensefile ../axiko-store/LICENSE `
  --exclude sparks-ring
```

For your own source, set `--repository-id`, `--repository-title`, `--repository-homepage` and your own `--publisher`. Use either `--download-url-template` with `{id}`/`{version}` for per-package release locations, or `--base-download-url` for one shared download directory. The official source uses `<id>-v<version>` release tags.

## Source layout and payload

A package needs `store.json`, `README.md`, its runtime descriptor and implementation files. `store.json.id`, not the folder name, determines the package ID.

- `module` uses `module.json` and `target: "modules/<id>"` in the author source metadata.
- A hosted kind is declared in the store's `kinds.json`, for example `{"game":{"host":"games","descriptor":"game.json","engine":"gamesEngine","version":1}}`, and targets `modules/<host>/<id>`. The builder has no fixed list of all hosted kinds.
- The builder refuses implicit renaming of historical runtime IDs/save paths. A missing `module.json.id` is injected only into packaged bytes and reported as a transformation.
- Entry comes from the runtime descriptor, otherwise an existing `ui/index.html`, then `main.js`.
- Existing `requires` entries are **optional integrations** and become `optionalDependencies`, with `>=` the sibling's current version. General-format mandatory dependencies belong in `package.json.dependencies`; this builder consumes the store's current author metadata format.

The ZIP contains root `package.json`, `README.md`, `LICENSE`, implementation under `code/` and optional `assets/`. It excludes author `store.json`, `tests/`, `store/`, user `data/`, hidden files, `.git/`, `node_modules/` and build directories. Legacy `_data/assets/` is allowed only as immutable `assets/`; other `_data/` contents are rejected. Installation does not copy resources over user data.

Symlinks, executable binaries, `.min.js`/`.min.css` and external executable scripts are rejected. ZIP order, timestamps and permissions are fixed; identical source bytes and Python/zlib versions produce identical archive bytes. Source line endings are preserved.

On failure, the previous complete artifact set and index remain and the new report has `ok: false`; do not publish that failed report as a release. Revalidate rebuilt archives with the actual installer.

Known-secret-pattern checks report only path, line and category, never the matched value. This is preliminary screening, not proof that personal data is absent. Packages declare `storage.own` and `legacy.full-trust`; literal HTTP(S) source URLs seed `networkDomains`, which is not an exhaustive network analysis.

## Mods

For `kind: "mod"`, the archive contains README, LICENSE, `mod/brief.md`, `reference/`, `base/` and optional `assets/`; it has no `code/` or executable entry. `store.json` requires `extends` and `reference.version`; `removed` names deleted target files.

The builder derives `reference.base` hashes and `reference.added`. If the target's matching version is published in the same source, it also records `reference.archiveSha256`. It rejects a missing brief, unused base files, paths both referenced and removed, and files outside the mod layout. Mod releases follow the same SemVer rule as other packages. See [mods](../../docs/marketplace/mods.md).

## Listing material

`listing.py` collects `title`, `summary`, `tags`, additional-language `locales` and links from `store.json`; icon from explicit `icon`, then `store/icon.*`, then `icon.svg`; screenshots from explicit entries or sorted `store/shots/*`; and Markdown descriptions/changelogs from `store/`.

Changelog headings use `## <version> — <YYYY-MM-DD>`. Additional text lives in `store/description.<language>.md` and `store/changelog.<language>.md`. Images are checked by actual content type, byte size and screenshot pixel dimensions. SVG is accepted only as an image without scripts or event handlers. Media references include the source-relative path, size and SHA-256.

Output includes:

```text
index.json                       Header with page references
catalog/p-*.json                 Cards, assigned by package ID hash
catalog/details/<id>.json        Description, screenshots, changes and links
build-report.json                Build, release, language and privacy findings
```

Each report package includes `listing`, available `languages` and author warnings such as a missing changelog entry for the current version. Listing-only changes do not alter the package payload or require a new package version. See [catalog pages](../../docs/marketplace/catalog-pages.md).

## English-first language policy

Every UI package must be fully usable in English. Either author its interface in English, or provide a complete flat `i18n-en.json` translating its Russian UI strings. `{}` represents interpolated values. Russian and other languages are optional supplementary dictionaries; an English-authored package uses `i18n-ru.json` for Russian.

The listing follows the same policy: primary `title`, `summary`, `tags`, captions, `store/description.md` and `store/changelog.md` are English. Use `locales` and language-suffixed Markdown for other languages, with English fallback. This release-quality rule is separate from security-review verdicts. See [listing language](../../docs/marketplace/catalog-pages.md#listing-language).

`language.py` reports coverage rather than blocking the build. It finds UI text in HTML/readable attributes, JavaScript strings (concatenations/templates become a single `{}`-parameterized text) and package JSON values. Tests, fixtures, tools, checks, prompts, listings and README are not UI source. Russian UI strings without dictionary entries are reported; UI without Russian text passes this check. It runs locally without networking.

```powershell
python scripts/store/language.py --store ../axiko-store
python scripts/store/language.py --store ../axiko-store --package focus
python scripts/store/language.py --store ../axiko-store --json
```

The builder includes the same `packages[].language` report: `source`, `texts`, `dictionary`, `dictionaryEntries`, `untranslated`, up to five `examples`, `complete`, `listing` and `warnings`. Listing diagnostics contain `english`, `notEnglish` and `languages`. The Cyrillic check is a diagnostic, not a language-quality proof or hard release gate. Move Russian primary listing text to `locales.ru` and `store/*.ru.md`. Mods have no standalone UI and are not checked by this language scan.

## Version discipline

Each package's `store.json.version` is exact SemVer `MAJOR.MINOR.PATCH`, independent of `game.json.saveVersion`. There is no shared catalog release version.

`released/<id>.package.json` records the currently published manifest. The builder compares source-file hashes against it:

| Source payload | Requested version | Result |
|---|---|---|
| Unchanged | Same | Reuse the previous archive, hash and URLs; `release: unchanged`. |
| Changed | Same | Fail and list changed files; increase the version. |
| Any | Lower | Fail; release versions only increase. |
| Any | Higher | Build a new artifact; `release: update`. |
| Never published | Any valid SemVer | Build the first artifact; `release: new`. |

File-hash comparison is independent of Python/zlib ZIP encoding. `versions.py check` performs the same check without building, and it is also covered by repository tests. After publishing, `versions.py record` copies new manifests into `released/`.

```powershell
python scripts/store/versions.py check --store ../axiko-store --licensefile ../axiko-store/LICENSE --exclude sparks-ring
python scripts/store/versions.py record --store ../axiko-store --built ../axiko-store-dist
```

The official store uses `Apache-2.0` for its own package code. Choose your own license identifier with `--license-id` and provide its text with `--licensefile`. Omitting `--license-id` produces `UNLICENSED`; the supplied license file becomes root `LICENSE` in every ZIP. Retain third-party license and attribution notices beside their resources.

## Publishing sequence

1. Inspect `build-report.json`: `ok: true`, intended packages, exclusions, transformations and privacy findings. `toPublish` lists only new artifacts with their exact destination URLs and filenames.
2. Validate actual ZIPs with Axiko's installer in a test environment. Python metadata/build checks do not replace its inventory, path, hash and source validation.
3. Test package behavior on synthetic data, updates over local edits and rollback.
4. Upload ZIPs and external `*.package.json` files to the exact referenced URLs. Then publish validated `index.json` and `catalog/` at the source root and run `versions.py record`.
5. Never replace an already published artifact. Increase package SemVer when payload bytes change; both builder and client enforce release continuity.

The app repository includes an optional JVM validation of built archives through the real installer, without executing package code:

```powershell
$env:AXIKO_STORE_CATALOG_DIST = (Resolve-Path ../axiko-store-dist).Path
./gradlew.bat :app:testDebugUnitTest --tests app.axiko.StoreCatalogDistributionTest --rerun-tasks --console=plain
```

It writes `catalog-validation.json` bound to the index SHA-256. Archives present in the output directory are checked; reused packages are listed in `notRebuilt`. Independent authors can use the public [Axiko app repository](https://github.com/Billerens/axiko), Python checks and installation-plan validation in a compatible app build.

## Metadata validation

`validate_metadata.py` checks local catalog headers (`--index`), pages (`--page`), details (`--details`) and manifests (`--package`). It neither downloads nor opens ZIPs, verifies actual archive content nor executes code. Output is JSON with `ok`, `scope: "metadata-only"` and JSON-path errors. Exit code `0` means valid metadata; `2` means invalid metadata.

It uses local JSON Schema 2020-12 files under `docs/marketplace/`, without network resolution. Require UTF-8 without BOM, unique JSON keys, valid Unicode and SemVer. v1 dependency constraints are exact versions or a single `>=` constraint; the client checks installed compatible packages from the same source and does not install dependencies automatically.

| Client limit | Value |
|---|---:|
| Index, page or manifest JSON | 1 MiB each |
| Catalog pages | 256 |
| Packages per page | 2,000 |
| Packages per catalog | 20,000 |
| Details JSON | 256 KiB |
| ZIP | 5 MiB |
| Unpacked package | 20 MiB |
| Individual payload file | 2 MiB |
| Files including `package.json` | 512 |
| Inventory entries excluding the manifest | 511 |

Use safe relative `/` paths in Unicode NFC. Parent traversal, hidden/empty segments, backslashes, unsafe names and case/Unicode collisions are forbidden. README, LICENSE and the entry (or a mod's brief) must be listed; total size includes the manifest.

`publisher`, `license`, `sourceFormat`, `capabilities` and `networkDomains` remain declarations. Neither schema validity, SHA-256 nor model review proves safety or sandboxing. Installed code currently uses `trustedLegacy`, including the app's Java/native capabilities.

Examples under `docs/marketplace/examples` use reserved `example.org` addresses and placeholder hashes. They demonstrate metadata, not downloadable releases. Schema `$id` values also use a demonstration namespace and are resolved locally.
