English · [Русский](README.ru.md)

# Repository and package contracts

English is the primary documentation language. Russian translations are supplementary; changes
to a rule or format must update both versions. Schemas and machine identifiers are shared across
languages and must not be translated.

| Contract | English | Russian |
|---|---|---|
| Required index, package structure and limits | [Repository format v1](repository-format-v1.md) | [Русский](repository-format-v1.ru.md) |
| Paged catalog, descriptions and localized listings | [Catalog pages](catalog-pages.md) | [Русский](catalog-pages.ru.md) |
| Review workflow and bound reports | [Agent review](agent-review.md) | [Русский](agent-review.ru.md) |
| Rule codes, evidence, severity and release bans | [Review rules](review-rules.md) | [Русский](review-rules.ru.md) |
| Installation, updates, local edits and trust limits | [Implementation](implementation.md) | [Русский](implementation.ru.md) |
| Mod references and adapting changes to a target package | [Mods](mods.md) | [Русский](mods.ru.md) |

Machine-readable schemas: [index](index.schema.json), [page](page.schema.json),
[details](details.schema.json), [package](package.schema.json). Metadata-only [examples](examples/)
use placeholder URLs/hashes; they are not downloadable releases.

Use the [author tools](../../scripts/store/README.md) ([Русский](../../scripts/store/README.ru.md))
to validate metadata and build catalog artifacts. Read the trust limits before installing a
third-party package: metadata declarations and an AI review do not create a runtime sandbox.
