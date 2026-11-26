# Development conventions

English · [Русский](development.ru.md)

Start with the [architecture](architecture.md), [module conventions](modules.md) and
[game development contract](game-development.md). This page covers the shell and factory
workspace. Examples use PowerShell 7 from the repository root.

## Build and install

Requirements: JDK 17 or newer and Android SDK platform 36. Fetch the speech runtime once:

```powershell
scripts\fetch-libs.ps1
.\gradlew.bat assembleDebug
adb -s <device> install -r app\build\outputs\apk\debug\app-debug.apk
```

Use `-Pphone` for an ARM64-only APK. The regular debug build also includes x86_64 for an
emulator. `-PcleanPreview` builds a separate `app.axiko.cleanpreview` installation, with
output under `build/clean-preview/`; it does not clear the owner's normal app.

An Android update must use the installed application's signing certificate. Keep signing
material outside the repository. Do not solve a signature mismatch by uninstalling an app
with personal data. Use the [backup flow](backup.md) for a deliberate move to another
installation or device, and account for its exclusions.

The embedded PRoot build requires 4 KiB memory pages. Settings report the limitation on
16 KiB devices. Shizuku is a separate app started by the owner; do not assume privileged
operations or Android permissions are available.

## Code and data contracts

- Shell code belongs in `app/src/main/java/app/axiko/`. Default editable files belong in
  `app/src/main/assets/factory/`; a working-UI fix must have the same factory fix.
- Keep personal state in `data/`, separate from versioned code. Workspace rollback does not
  restore personal data. Data migrations must preserve existing values and a recovery path.
- Distinguish browser JavaScript from Rhino/shared simulation code. Browser-only APIs must
  not silently become requirements of engine code.
- Preserve the [native contracts](native-contracts.md), public bridge, revision checks and
  prepared store plans. Do not bypass conflicts by overwriting live files or module directories.
- User and repository content is data, not HTML or executable instructions. Keep credentials
  out of source, model context, command output and logs.
- Shared app controls use the kit's theme tokens and translation helpers. Public module
  interfaces and store listings must work fully in English; Russian and other languages
  are optional additions. See [module conventions](modules.md).
- A changed packaged file requires a new package version under the
  [repository format](marketplace/repository-format-v1.md). Package versions and game save-schema
  versions have different purposes.

## Documentation

English is canonical at `docs/<topic>.md`; an optional Russian companion uses
`docs/<topic>.ru.md`. Link to English first and offer Russian as a secondary link. Keep
requirements aligned across language versions. API names, schema keys, code examples and
machine-readable schemas are shared contracts, not separately localized implementations.

Check local links and section anchors after moves or edits:

```powershell
python scripts/docs/check_links.py
```

## Verification

Run checks relevant to changed behavior, then inspect the real runtime of the affected
surface. A browser stub is not an Android integration test; a mock provider does not assess
a real model's reasoning quality.

| Purpose | Command |
|---|---|
| JVM unit tests | `.\gradlew.bat testDebugUnitTest` |
| Android shell checks | `scripts\check.ps1 -Serial <device> [-Only "part of test name"]` |
| Engine script on a chosen device | `scripts\eval.ps1 script.js -Serial <device>` |
| Device screenshot helper | `scripts\shot.ps1 out.png` (check its device argument before use) |
| Web transport, editor and chat | `node --test scripts/web/transport.test.js scripts/web/editor-model.test.js scripts/web/chat-reconnect.test.js` |
| Isolated model-provider test double | `node scripts/mock-llm.mjs` |
| Translation audit | `python scripts/i18n.py` |
| Store metadata and author tools | [scripts/store/README.md](../scripts/store/README.md) |

Browser regressions in `scripts/web/` require Playwright; see [web-hub.md](web-hub.md) for
configuration. Use an emulator or separate QA installation for synthetic events, generated
modules and model test doubles. Keep artifacts in ignored `build/` directories. Do not
publish owner backups, logs, screenshots, model files, `.env`, local SDK settings or signing keys.

Before updating a personal device, preserve recoverable state and verify the target device,
package and signature. Afterward verify the installed build and affected settings/data.
A successful build alone does not confirm a successful installation.

## Bundled licenses and source

Keep third-party notices and corresponding source with the binaries they cover. Embedded
PRoot and its loaders include source and build scripts for the shipped Android build,
including talloc. See the [PRoot notice and build instructions](../app/src/main/assets/licenses/proot/README.md)
and [source archive](../app/src/main/assets/licenses/proot/proot-android-0.15-corresponding-source.tar.gz).
Dependency notices live in [`assets/licenses/`](../app/src/main/assets/licenses/). Documentation
cleanup must not remove these license or source obligations.
