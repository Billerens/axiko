English · [Русский](README.ru.md)

# Axiko project documentation

These documents describe the current project contracts and contribution conventions. English is
the primary version; `*.ru.md` files are supplementary Russian translations. Keep both versions
in sync when changing a contract. Release notes belong in GitHub Releases; generated reports,
screenshots and experiments belong in ignored `build/artifacts/` directories.

| Topic | Primary document | Russian |
|---|---|---|
| Application layers and runtime | [Architecture](architecture.md) | [Русский](architecture.ru.md) |
| Build, checks and contribution conventions | [Development](development.md) | [Русский](development.ru.md) |
| Module structure, language, themes and data | [Module development](modules.md) | [Русский](modules.ru.md) |
| Shared game engine and save conventions | [Game development](game-development.md) | [Русский](game-development.ru.md) |
| Native capabilities and feature detection | [Native contracts](native-contracts.md) | [Русский](native-contracts.ru.md) |
| Repository and package format | [Repository format](marketplace/repository-format-v1.md) | [Русский](marketplace/repository-format-v1.ru.md) |
| Catalog pages and localized listings | [Catalog pages](marketplace/catalog-pages.md) | [Русский](marketplace/catalog-pages.ru.md) |
| Agent review and enforceable rule levels | [Agent review](marketplace/agent-review.md), [review rules](marketplace/review-rules.md) | [Проверка](marketplace/agent-review.ru.md), [правила](marketplace/review-rules.ru.md) |
| Installation, updates, rollback and trust | [Store implementation](marketplace/implementation.md) | [Русский](marketplace/implementation.ru.md) |
| Package modifications | [Mods](marketplace/mods.md) | [Русский](marketplace/mods.ru.md) |
| Encrypted backup and restore | [Backup](backup.md) | [Русский](backup.ru.md) |
| Code history and content-addressed snapshots | [Snapshot storage](snapshot-storage.md) | [Русский](snapshot-storage.ru.md) |
| Editable desktop interface | [Web hub](web-hub.md) | [Русский](web-hub.ru.md) |
| System assistant panel and customization | [On-screen assistant](assistant.md) | [Русский](assistant.ru.md) |
| Mascot, themes and naming | [Brand and mascot contracts](brand.md) | [Русский](brand.ru.md) |

Package-specific documentation lives with the package in
[axiko-store](https://github.com/Billerens/axiko-store). The shared rules above replace the old
per-package design proposals in this repository. The app also carries detailed Russian references
for its own agent: [handbook](../app/src/main/assets/factory/agent/handbook.md),
[API](../app/src/main/assets/factory/agent/api.md) and
[game engine reference](../app/src/main/assets/factory/modules/games/AGENT.md).

Editable source artwork is in [assets/brand](../assets/brand/README.md); the README's video and its
reproducible renderer are in [assets/promo](../assets/promo/README.md). Neither is a separate project
specification. Third-party license texts and corresponding source bundles remain in
[`app/src/main/assets/licenses`](../app/src/main/assets/licenses).
