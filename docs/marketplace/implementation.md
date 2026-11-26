English · [Русский](implementation.ru.md)

# Axiko Store: implementation and guarantees

The `ui/store/` screen ships in both the factory and editable layers. It is available from modules, Home and the web hub. Its tabs are Catalog, Installed, Updates and Sources. Browsing reads the cached catalog; adding or refreshing a source and preparing a package use the network.

## Catalog and selected updates

Catalog lists packages absent from the device (`store.catalog({only:'notInstalled'})`). Installed packages belong in Installed and their newer versions in Updates. An unmanaged local module or an already applied mod is not repeated as a new catalog offering.

Updates is a selectable list with old and new versions, expandable release notes, Select all/Clear all and one Update selected action. Its trust acknowledgement covers the selected packages and versions; changing the selection requires it again.

Selected updates run sequentially through the same plan and digest checks as an owner's individual update. A package is applied only when no decision remains. Local conflicts, blockers, a mod that needs carrying, inherited warnings or a ban leave a prepared plan with a reason and an Open plan action. One failure does not stop other selected updates. Progress and each result are shown; Stop finishes the current item and skips the rest. This path does not start agent review or authorize an agent: agent installation uses `store_review_start`.

## Sources and installation

- Accept an HTTPS `index.json` URL or a GitHub repository URL, resolving its default branch. The index is mandatory; arbitrary ZIP URLs are not installable sources.
- The factory default is `Billerens/axiko-store`. The shell adds it once on first store access and validates it like any other source; an offline attempt can be retried later. The owner may disable or delete it. A deleted default is not silently restored.
- Validate metadata, UTF-8, duplicate keys, versions, compatibility, actual byte and file counts, archive and per-file SHA-256, ZIP paths and inventory. The catalog reader tolerates unknown optional listing fields; package manifests and author-side schemas remain strict.
- Reject traversal, links, executable binaries, remote code and detected minification/obfuscation. Established validator violations ban the release. A download whose size or SHA-256 differs from the catalog is a source error, not a ban. Static heuristics are not proof of safety.
- Prepare a plan before applying anything. It identifies the exact package, changes, conflicts, declared capabilities, autostart and findings. Trust acknowledgement is scoped to that plan and digest.
- Compare the previous release, live files and incoming release. Preserve one-sided owner edits, additions and deletions. Two-sided conflicts stop replacement; the owner can retain the local file.
- Change only the package code directory. Do not replace `data/`, settings or game saves. Create a snapshot before the transaction and recover interrupted replacement through its journal. Rollback is also a plan that accounts for current local edits.
- Do not execute package code, `checks.js`, migrations or autostart while preparing or installing. Opening the installed package or a later ordinary Axiko startup may run its regular logic, including declared autostart.

## Adopting an unmanaged local package

A module installed manually, by an agent or before the store existed is not silently taken over. It appears in Updates as `unmanaged`; the store does not claim to know its version.

The owner can explicitly adopt it (`store.prepare(sourceId, packageId, true)`, shell feature `store.adopt`). There is no previous package base for this first plan. Files identical to the release remain unchanged. Each differing file requires Keep mine or Use store version, individually or together. Local files absent from the release remain.

The shell snapshots before replacement and restores an interrupted transaction. After adoption, the package is managed normally: retained files count as local edits and future releases use ordinary update plans. A normal installation still refuses to overwrite an unmanaged module.

## Paged listings and host-defined kinds

A repository publishes an `index.json` header and pages of cards. Refresh downloads changed pages by hash and accepts a complete validated catalog or keeps the old one. The UI requests slices through `store.catalog`; search, filters and sorting run in the shell, and scrolling appends the next slice.

Kinds and tags are derived from catalog data. The shell owns `module` and `mod`; other kinds are declared by an installed host module in `module.json.hosts`. Hosted packages install only inside that host's directory.

`store.details` loads descriptions, screenshots and changes on demand. `store.media` resolves a verified media reference, checks size, hash and content type, and returns cached data to the page. The page does not fetch repository media directly. Author Markdown is rendered without executing HTML. See [catalog pages](catalog-pages.md).

## Mods

A `mod` package names its target with `extends`. Matching target versions and unchanged source files can use direct application. Otherwise the plan is `carrying` until a dedicated agent adapts the mod to the owner's version. That conversation reads the brief, reference and target and writes only into its plan's candidate. It preserves owner edits, can ask the owner and submits a report.

The result returns to the ordinary plan/review/owner-install flow. The target registry records a mod layer; copies of affected pre-mod files live outside the workspace. Removing the mod reconstructs the state without it. Updating a mod replaces its prior layer, and the owner can request that an installed mod be carried again. Share customization creates a local mod draft for the owner to publish. See [mods](mods.md).

## Dedicated agent review

An ordinary agent uses `store_sources`, `store_add_source`, `store_catalog`, `store_prepare` and, when the owner requested installation, `store_review_start`. Starting review returns a task, not an installation success. `store_review_status` reports its state and `outcome`: installed, owner decisions required, or banned. The ordinary chat must report that actual outcome.

The review conversation can only use `store_plan`, `store_read`, `store_resolve`, `store_review`, `store_apply` and `ask_owner`. The shell enforces the restriction before tool execution, not just in the offered tool list. JavaScript, shell, MCP, previews and unrestricted delegation are unavailable. A conflict can only be resolved there by keeping the local file.

Coverage is persisted by file, hash and delivered character ranges. Gaps, reads of another conflict side or digest changes do not constitute complete coverage. The shell derives a report's verdict from findings. `pass` requires complete required-source reading and no findings; installation additionally requires current consent, a current report and the installer's final recheck. Source, dependency or file changes invalidate earlier approval.

Plan inventories and findings are paginated. Reads return at most 12,000 characters and shrink when JSON escaping would exceed the complete-output limit; `nextOffset` is the delivered boundary. Store review permits up to 240 model responses; ordinary conversations retain their 80-response bound. The owner's daily spending limit applies to every model request. Independent fragments may be requested with several tool calls in one response. Exhausting the budget or context does not result in an automatic installation.

Package instructions are untrusted review material. Complete reading records coverage, not proven understanding or safety. This stage does not run package tests in isolation; reports contain `checksExecuted: false`.

## Rules, bans and warnings

[Review rules](review-rules.md) are compiled into `StoreRules`, included in the review conversation's system message and used to validate its report. Unknown rule codes, missing file/line evidence and incompatible severities are rejected.

- An observed **violation** gives `reject` and immediately bans the archive SHA-256, recording source, package, version, time and findings. Bans live outside the editable workspace and are checked in both installer and service. The banned artifact cannot be prepared, reviewed again or installed manually or by an agent. Validator violations follow the same rule. A new archive can be reviewed independently; the catalog still shows the previous ban. Only the owner may unban a release, through a native shell confirmation dialog.
- A **suspicious** finding or unknown gives `needsAttention`. The agent cannot install it. The plan and chat show evidence, consequences and proposed resolution. The owner may decline or install despite warnings; the latter requires native confirmation for the same plan, digest and report. Neither model nor page can supply that decision. Warnings follow the archive across newly prepared plans. The report and owner decision remain visible with the installation.

Review uses the normal chat panel above the store, or the web hub's side panel. The underlying plan continues to display status. Applied reports are historical evidence, not permission for a new plan.

## Trust boundary

Installed modules run under `trustedLegacy`. `capabilitiesEnforced: false`: manifest capabilities do not restrict Rhino/Java or the shared bridge. Workspace scripts may call Native directly; owner-only warning acceptance and unbanning therefore require the shell's native dialog. Direct installation without model review remains available with explicit trust acknowledgement, but cannot bypass bans or unresolved warnings.

Hashes protect integrity of the selected artifact, not the publisher's identity. Publisher signatures, a package runtime sandbox, unattended updates and automatic merging of arbitrary package updates are not guarantees of v1.

Public catalogs need no GitHub credentials. The transport does not send credentials, disable TLS or permit HTTPS-to-HTTP redirects. Removing a source retains installed packages and their data.

## Verification

JVM tests cover schemas and limits, ZIP validation, preservation of data and edits, conflicts, stale plans, crash recovery, rollback, source continuity, measured agent reading, verdict derivation, validator/review bans and owner-only warning decisions. Browser tests exercise real UI with mock Native, including warnings, bans and review chat. Device behavior is checked separately on Android.

See the [author tools](../../scripts/store/README.md) for validation commands. Test artifacts belong in local build output; personal backups and device reports are not documentation or release assets.
