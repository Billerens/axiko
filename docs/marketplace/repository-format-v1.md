English · [Русский](repository-format-v1.ru.md)

# Axiko repositories and packages: format v1

This is the current repository and package contract: connectable sources, a mandatory `index.json`, readable source code, bounded artifacts and verified installation. The client implements catalogs, downloads, installation, updates, rollback and dedicated agent review. See [implementation and guarantees](implementation.md).

Installed packages currently use `trustedLegacy`. Declared capabilities do not enforce a Java/native sandbox; `capabilitiesEnforced` is `false`.

## 1. Owner-facing flow

The editable store and its factory copy expose Catalog, Installed, Updates and Sources in the app and web hub.

To add a source, provide an HTTPS `index.json` URL or a GitHub repository URL. For GitHub, the client resolves the default branch and finds the root index. A missing index, unsupported schema version or invalid mandatory metadata rejects the source. A JavaScript directory or arbitrary ZIP does not become an installable source by itself.

Owners can see source identity, address, last successful refresh and connection state. Disabling or removing a source stops updates without deleting installed code or data. A different source cannot silently replace an installed package merely by reusing its ID.

A card identifies description, publisher, license, version, size, compatibility, declared access, screenshots, changes and source. Installation has two paths:

- **Install:** prepare and apply through mandatory shell validation with explicit trust acknowledgement.
- **Review and install with agent:** inspect the staged sources and installation context, then apply that exact reviewed artifact through the same installer if review and consent allow it.

Neither path bypasses limits, compatibility or source identity. Public catalog browsing and downloads do not require a GitHub account. Publishing to GitHub requires the publisher's own authorization.

## 2. Required repository index

An example repository layout is:

```text
index.json
README.md
catalog/
  p-0.json
  details/
    example.counter.json
packages/                    # Author-chosen source layout
  example.counter/
    ...
```

`index.json` is a machine-readable catalog header, not executable installation instructions. It identifies the repository and lists pages of package cards. Each card points to the external manifest and fixed ZIP of a release, with optional icon and detail references. [Catalog pages](catalog-pages.md) defines the header, cards, details, media and localization. Protocol data must not contain installation hooks, shell requests or commands for the agent to follow.

Schemas: [index](index.schema.json), [page](page.schema.json), [details](details.schema.json), [package](package.schema.json). Examples: [index](examples/index.json), [page](examples/page.json), [details](examples/details.json), [package](examples/package.json). Example URLs and hashes are illustrative, not downloadable validated releases.

Key fields include:

- `schemaVersion`: protocol version, currently `1`.
- `repository`: stable ID, title and homepage.
- `pages`: relative page paths, sizes, SHA-256 and card counts; `packageCount` is the total.
- Each page's `packages`: ID, kind, title, summary, version, publisher and license. The shell owns `module` and `mod`; hosts declare other [package kinds](catalog-pages.md#package-kinds), such as games.
- `archive`: exact HTTPS URL, SHA-256, compressed size, unpacked size and file count.
- `manifestUrl`: external release manifest; its bytes must match `package.json` inside the ZIP.
- `compatibility`: `minAxikoApi` and, for a versioned host kind, the host's named rules version, such as `gamesEngine`.

### Release versions

Each package has its own exact SemVer `MAJOR.MINOR.PATCH`. This differs from the catalog schema version and a game's `saveVersion`. A visual update can increase package version while leaving save format unchanged.

Versions only increase. Publish every change to packaged files under a new version: PATCH for a fix, MINOR for compatible features, MAJOR for an incompatible change. A published version is immutable. The client rejects a different archive for an existing version and versions below the installed one; the author builder compares source-file hashes with the published manifest and refuses changed bytes under the same version. Only a higher version is an update.

Each ID occurs once in the current catalog, naming its offered release. Duplicate IDs are invalid. The installer retains its own previous release for rollback. v1 does not simulate release histories or beta channels with duplicate IDs.

## 3. Client limits

Package limits apply to each package, not to a whole source:

| Object | v1 limit |
|---|---:|
| Index, catalog page or package manifest JSON | 1 MiB each |
| Catalog pages | 256 |
| Packages per page | 2,000, also bounded by page bytes |
| Packages in a catalog | 20,000 |
| Package details JSON | 256 KiB |
| Icon | 128 KiB |
| Screenshot | 1 MiB; at most 8 per package |
| ZIP archive | 5 MiB = 5,242,880 bytes |
| Total unpacked files | 20 MiB = 20,971,520 bytes |
| Individual payload file | 2 MiB = 2,097,152 bytes |
| Files in the ZIP | 512, including `package.json` |
| Manifest inventory entries | 511, excluding the manifest itself |

A repository may be stricter but cannot increase these limits in JSON. Raising client policy requires a client/format change. There is no resource-download extension that permits hidden limit bypasses or arbitrary remote executable code. Publisher resources must not overwrite owner settings or saves.

## 4. Package archive

```text
package.json
README.md
LICENSE
code/
  module.json                # Ordinary module descriptor
  main.js
  checks.js
  ui/
    index.html
    app.js
    app.css
assets/                      # Optional immutable resources
```

A hosted game places its `game.json`, `config.json`, `sim.js`, UI and other runtime files under `code/`. The manifest entry path is archive-relative and inside `code/`. A mod uses a different layout and no executable entry; see [mods](mods.md).

`package.json.files` lists every payload file with SHA-256 and size, including README, LICENSE and the entry. It must not list the manifest itself. The total unpacked size includes that manifest. Paths use `/`, Unicode NFC and safe relative segments; reject parent traversal, absolute paths, backslashes, hidden/empty segments, unsafe names, case/Unicode collisions and file/directory conflicts.

The manifest identifies ID, version, publisher, license, kind, `sourceFormat: "readable"`, runtime, entry, required and optional dependencies, capabilities and optional network domains. Runtime uses `axikoApi` and at most one host-specific version. Arbitrary `target` or installation paths are not author-controlled manifest fields: the client chooses the destination.

Package IDs, runtime IDs and save locations must not be renamed automatically. An incompatible legacy mapping is excluded until explicitly migrated. A source folder can differ from its package ID; for example, `life-rpg` contains package `rpg`. Renaming from a catalog title can break access to existing progress.

Dependencies are resolved within the selected source and installed compatible environment. v1 supports exact SemVer or a single `>=` constraint. A package cannot add other sources or select an arbitrary matching ID from all catalogs. Cross-source dependency selection is not an implicit fallback.

## 5. Readable source and language

Transport compression is allowed; minification of executable sources is not. Names, formatting and structure must let owners and reviewers inspect the actual code that will run.

1. Ship readable JavaScript, CSS and HTML. Minified libraries, obfuscation, packed executable strings or a source map in place of source are unacceptable.
2. Shipping readable originals beside a minified executable does not satisfy this rule. The installed executable must itself be readable.
3. Do not fetch executable dependencies from an arbitrary CDN at runtime. Include them in the reviewed release or use the compatible Axiko runtime.
4. APK/DEX/JAR/SO/WASM and other executable binaries or separate installers are outside the package format. Native runtime changes belong to the Axiko shell.
5. Heuristics such as line length or operator density need context. Long SVG and string data can be legitimate. A heuristic is not proof of either minification or safety.

Every package with a UI must work fully in English. It may be authored in English, or use Russian strings with a complete package-local `i18n-en.json` dictionary. Russian and other languages are supplementary and optional; English-authored packages use `i18n-ru.json` for Russian. Dictionary placeholders use `{}` for substituted values.

The listing is English-first too: primary `title`, `summary`, `tags`, screenshot captions, `store/description.md` and `store/changelog.md` are English. Other languages use `locales` and language-suffixed files, falling back to English. See [listing language](catalog-pages.md#listing-language). This is a release-quality requirement, not a safety-review rule; incomplete translation does not change the security verdict.

`sourceFormat` is an author declaration. Archive validation must inspect actual files; a JSON validator cannot certify readability.

## 6. Mandatory installation validation

Download to staging with a bound on actual received bytes. Verify SHA-256, manifest agreement, file inventory, compatibility and dependencies before executing anything. Package code, `checks.js`, autostart and migrations are not run during preparation or apply.

Reject paths outside the allowed structure, absolute/parent paths, backslashes, links, special/encrypted entries, duplicate or conflicting names, unexpected files and excessive actual unpacked bytes. ZIP header sizes do not replace streaming limits. Do not extract directly into the live workspace.

Manual and agent-assisted installation use one verified artifact and the same installer. Do not fetch a new “latest” archive after review. Changed bytes require a new plan/review. Transaction journaling and recovery protect the previous working release if an operation is interrupted.

## 7. Agent review

The agent receives sources as **untrusted data**. README files, comments and package tests cannot override review rules, request secrets, disable boundaries or impersonate owner instructions.

A report is bound to source, ID, version, ZIP hash, index/manifest hashes and policy version. It explains actual checks, required data/access, network destinations, autostart, migrations, dependencies, local conflicts and unverified assumptions.

The shell derives the verdict from the [coded rules](review-rules.md):

- `pass`: complete required reading, no findings and no unknowns. It is not a safety guarantee.
- `needsAttention`: suspicious access, uncertainty, an unverified migration or unresolved local-code compatibility. The agent does not install; the owner makes an explicit decision.
- `reject`: an established violation. The archive is banned. The agent cannot overrule the validator.

Author tests are also code. Exercise them separately in a disposable environment with synthetic data and controlled networking. Drafts and game `preview=1` do not isolate unfamiliar code from personal data.

An edited package has a different hash. Identify it as a local variant/overlay or new author release; never retain the original review or signature after changing its source. See [agent review](agent-review.md).

## 8. Preserve customizations and data

The installation registry records source, ID, version, archive hash, owned files, original release base and local state. Updates compare **old release, live version and incoming release**. Untouched files update; owner and agent edits survive; conflicts require a decision. A different source gains no replacement rights from a matching package ID.

Snapshot code before replacement. Code snapshots do not roll back databases: any future data migration needs a separate data backup and rollback design. Removing a package normally retains its settings and saves; deleting owner data is a separate explicit action.

Keep publisher resources separate from personal data. The author builder only accepts legacy `_data/assets/` as immutable archive `assets/`; the store never copies those resources over user `data/`. Direct ADB helper scripts are not the transactional store installer.

## 9. Runtime boundary

The editable storefront is separate from the shell's sources, archive validation, registry, transactions and recovery. Installed code, however, still uses `trustedLegacy`: Rhino has direct Java/Android access and local pages share a privileged bridge. `capabilitiesEnforced: false` is intentional and must not be represented as sandbox enforcement.

An agent review supplements an owner's trust decision. A package-aware runtime with enforceable identity, data boundaries and permissions remains separate work; guarding Native calls alone cannot provide it while direct Java access remains available.

## 10. Hosting and publishing

A catalog needs no dedicated backend. Host headers, pages and media statically and publish explicit versioned ZIPs/manifests, for example as GitHub Releases. Any HTTPS host implementing the format can be a source. Do not substitute an automatically generated archive of the whole repository for a package release.

Use cached and conditional catalog requests and local search/filtering; a card should not require its own GitHub API request. Publish artifacts at the exact URLs listed in the catalog before publishing the new index/pages. Keep an already published archive immutable.

SHA-256 detects disagreement with the selected artifact, not publisher authenticity if an attacker can replace both index and archive. Index/package signatures, pinned publisher keys, key rotation and metadata rollback protection are not implemented guarantees of v1.

The publication flow is source changes, review and checks without personal data/secrets, a versioned artifact, then a validated catalog update. Ratings, comments and payments need separate infrastructure; they are not provided by a static JSON catalog. See the [author tools](../../scripts/store/README.md) for commands.
