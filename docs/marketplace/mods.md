English · [Русский](mods.ru.md)

# Mods: customizations layered over packages

A mod is a catalog package that changes another package. It describes the intended result and supplies reference files and resources. It is not a standalone application or a line-based patch. It uses the [catalog format](catalog-pages.md) and the same [installation plans](implementation.md) as ordinary packages.

When the target version and relevant base files match, a mod can apply directly. Otherwise a dedicated agent carries the intent and reference implementation onto the owner's installed version, preserving local edits and asking the owner when choices are needed. The result is a reviewable candidate, not an automatically trusted installation.

## Catalog identity

A mod card has `"kind": "mod"` and `"extends": "<target-package-id>"`. `mod` is owned by the shell, like `module`; a host module cannot claim it. Icons, tags, descriptions, screenshots, changes and their translations use the same `store/` listing format as other packages.

A mod's `compatibility` contains only `minAxikoApi`; the target's own requirements establish its host compatibility. A mod knows its target, not dependencies on other mods.

## Archive layout

```text
package.json           Manifest
README.md
LICENSE
mod/brief.md           Instructions describing the intended customization
reference/…            Target files as changed by the author, using target-relative paths
base/…                 The same target files before the author's edits
assets/…               Material available to the carrying agent: images, sounds, text, etc.
```

There is no `code/` directory and no executable entry point. Direct application copies only `reference/` into the target. A reference file without a base counterpart is a new target file. `assets/` is not automatically copied by the direct path; it is material the carrying agent can use.

Package size, readable-source and executable-binary restrictions also apply to mods.

`base/` makes the mod self-contained: the author's exact change is the difference between `base/` and `reference/`, even if the corresponding target release can no longer be downloaded. The builder and Share customization supply base files; authors do not maintain their hashes by hand. Added files have no base counterpart.

The mod includes original and changed target code. Publish it only when the target's license permits that derivative use. The builder warns when this permission is not evident from the target license and publishers differ; that warning is not a legal authorization.

### The brief

`mod/brief.md` is the main human-readable specification. State what changes for the owner, why, the expected outcome and how to recognize success, what must remain untouched, which reference details must be preserved exactly (wording, balance values, images), and which implementation choices may be adapted.

### Manifest additions

Beyond ordinary manifest fields, a mod contains:

```json
{
  "kind": "mod",
  "extends": "merge-forge",
  "reference": {
    "version": "0.1.1",
    "archiveSha256": "…",
    "base": {"outpost.js": "<original-file-sha256>", "ui/outpost.js": "…"},
    "added": ["ui/dragon-den.js"],
    "removed": ["ui/old-panel.js"]
  }
}
```

`reference.version` is provenance, not a supported-version constraint. It identifies the target release used to create the reference. `reference.base` must describe exactly the archived `base/` files; `added` lists reference files without a base counterpart; a removed file cannot also appear in `reference/`. A base file must be changed in the reference or named in `removed`. The mod must make at least one change.

## Preparation and carrying

`store.prepare` always returns a plan. If direct application is valid, the plan is ready with `mod.mode: "direct"`. Otherwise it remains `carrying`: no live files have changed and it cannot be applied. `mod.carry.reasons` explains mismatched target versions, owner-edited files or changes made after an earlier mod installation.

The owner starts carrying with Carry with agent in the plan or `store_mod_carry` in chat. The dedicated conversation is bound to that one plan and exposes only:

| Tool | Scope |
|---|---|
| `store_mod_task` | Mod, target, reasons, material and file annotations: available sides, base equality, owner edits and whether original reference files exist. |
| `store_mod_read` | Read text from `brief`, `reference`, `base`, `current`, `release`, `candidate` or `asset`. |
| `store_mod_copy` | Copy a complete file from `reference`, `asset`, `base`, `current` or `release`. |
| `store_mod_write`, `store_mod_edit`, `store_mod_delete` | Write text, replace an exact fragment or remove a file in this plan's candidate. |
| `ask_owner` | Clarify the owner's intent or a conflicting choice. |
| `store_mod_done` | Submit the candidate and report. |

Every written file passes the release-file checks for unreadable/hidden code, executable binaries and remote code loading. A refusal while carrying is a failed adaptation, not automatically a ban on the author's original mod. The agent cannot install packages, read arbitrary workspace files, execute code or use other tools in this conversation.

The carrying report is `{summary, verbatim, adapted, skipped, questions}`. Each changed file is accounted for once: copied verbatim or adapted, with a reason. The shell compares claims of verbatim copying with the reference, validates package structure and entry, and records owner-edited files in `mod.ownerEdits` and files carried without an original in `report.withoutBase`. This does not execute the candidate or prove that it will run correctly.

After submission, the candidate returns to the ordinary plan flow. The owner sees the carrying report before the file changes, may request a separate rule-based review conversation, and decides whether to install. The carrying agent does not review its own output.

A rejected adapted candidate does not ban the author's original release: the violation may have been introduced during adaptation. The plan is rejected and the mod can be prepared and carried again.

## Installation flow

1. **Prepare:** download and validate the mod archive. Its target must exist and be store-managed. Adopt an unmanaged target first; offer installation for an absent target.
2. **Direct application:** if the installed target version matches the reference and each relevant base file is unchanged, build the candidate by overlaying reference files without an agent. Once the mod is available locally, this needs no model call and has a deterministic result.
3. **Carry when necessary:** read the brief, reference, base and current target; inspect owner edits; write only into this target's candidate; use reference/assets; ask the owner about intent, alternatives or inapplicable features; submit the adaptation report.
4. **Review the plan:** show the target changes, carrying report and installer findings. Any store safety review examines the actual candidate in a separate conversation.
5. **Apply:** the owner confirms after inspecting the plan/report, and the installer snapshots before replacement.

Before installation, the UI states whether the target version matches and whether a model-assisted carry is needed, including that model requests are required and the result may differ from the reference.

## Recorded mod layer

The managed target records a layer such as:

```json
"mods": [{
  "id": "forge-dragon-den",
  "sourceId": "…",
  "version": "1.2.0",
  "title": "Dragon den",
  "mode": "ported",
  "laying": "<this-mod-installation-id>",
  "targetVersion": "0.1.2",
  "files": {"outpost.js": "<post-carry-sha256>"},
  "added": ["ui/dragon-den.js"],
  "removed": [],
  "ownerEdits": ["outpost.js"],
  "appliedAt": 1794900000000,
  "report": "…"
}]
```

`mode` is `direct` or `ported`. A live file that differs from the release base but matches a mod layer is attributed to the mod; other differences are owner edits. The layer also records which files were initially `pristine`.

Pre-mod copies of affected files live beside the registry, outside the workspace, separately for each `laying`. Preparing an update does not overwrite the installed layer's copies before apply. For initially pristine files, “without the mod” means the current target release's file, not an old version. For owner-edited files, the saved pre-mod copy retains those edits.

## After installation

- **Target update:** the mod's changes go through the ordinary three-way comparison. A conflicting mod file requires a decision: carry again, retain the previous file or remove the mod.
- **Mod update:** prepare against the target as it would be without the old mod. Files still matching the old layer are restored to their pre-mod state in the candidate. Apply directly if the new reference fits, otherwise carry it, including when the old mod's files were edited later. Removing the new mod version returns to the state before the first layer.
- **Carry again:** the owner can re-adapt an installed mod after a target update or an unsatisfactory adaptation. Use the locally retained mod release and the current target without that mod as the candidate base.
- **Remove:** restore the state without the mod. Keep earlier owner edits, restore pristine files from the target's current release, and remove mod-added files. Later edits use a three-way comparison of pre-mod, post-mod and current content; conflicts require an owner decision.
- **Several mods:** install sequentially; each sees the previous result. Removing a mod touched by later mods warns the owner, who may carry the remaining mods again.
- **Rollback:** the pre-apply snapshot restores code to the state before the mod. It does not roll back personal databases.

## Share a customization

The store identifies owner edits separately from installed-mod changes. Share customization creates an author-ready local draft:

```text
data/mod-drafts/<id>/
  store.json            id, kind: mod, version 1.0.0, title, summary, extends, reference.version, removed?
  README.md
  mod/brief.md          Starter brief listing changed files
  reference/…           Owner-modified and added files
  base/…                Changed/deleted files as they were in the release
```

The owner supplies ID, title and intended change. Every included file passes release-file checks; a forbidden file or detected saved secret prevents draft creation. A separate agent conversation may compare base/reference, clarify intent and help write the brief while checking for personal material.

The draft stays on the device. The owner places it in a chosen source repository and publishes through the catalog builder; sharing a draft does not upload anything automatically.

## UI and boundaries

Package details list available mods whose `extends` matches the target. Installed targets list applied mods, their direct/ported provenance, Carry again, Remove and available updates. Owner-edited managed packages offer Share customization. Mods also have their own catalog kind with the target named on the card.

The brief and reference are untrusted author data; they cannot expand tool permissions or override shell rules. The carrying agent writes only into one candidate, never owner data, saves or arbitrary workspace paths. A violation present in the author's reference can ban that mod release; a violation introduced by adaptation rejects the plan without attributing it to the author.

Mods do not migrate target saves. A change requiring another data format needs a separate migration design. v1 has no dependencies between mods, target-version ranges, line-based patch format or mods of the shell itself.

## Versioning and owner edits

A mod has one SemVer: the author's reference release version. Carrying does not create a new release version. The layer records `direct`/`ported` and the target version; catalog update detection continues to use the author's version.

There is no separate mod-specific question or pricing policy; ordinary conversation bounds and the owner's daily model-spending limit apply.

`base/` normally supplies the exact original. If an original is unavailable for a file, direct application is not used. The carrier is told that the exact diff is unknown and must infer intent from the brief and full reference file. Both plan and report mark the missing base for closer owner review.

Owner edits in affected files are part of the version being customized and must be preserved. They force a carry, are identified to the agent and shown in the plan, and are recorded in `ownerEdits`. If a mod's intent directly conflicts with an owner edit, ask the owner rather than silently choosing. Removing the mod restores the earlier owner-edited state where appropriate.
