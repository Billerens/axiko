# Snapshot storage and rollback

English · [Русский](snapshot-storage.ru.md)

Workspace history stores identical file bytes once, regardless of filename or number of
snapshots. The live workspace remains an independent file tree: editing it must never alter
history. This is code history, not a substitute for a [personal-data backup](backup.md).

## Format and invariants

```text
files/history/
  .objects/<first 2 SHA-256 characters>/<remaining 62 characters>
  <snapshot-id>/
    meta.json
    manifest.json
```

`meta.json` contains the ID, time and label. A format-2 manifest records relative paths,
SHA-256, size, executable status and directories. New snapshots include regular files of
any supported size and empty directories. Symbolic links and special files are rejected so
history cannot escape the workspace.

Objects are written through a temporary file, synchronization and rename. A snapshot becomes
visible only after its objects and manifest are ready. Do not hard-link live files into the
object store or mutate an existing object. Object integrity is checked before restoration.

## Legacy migration

Startup migration converts old `<id>/files/` trees in the background; the old format remains
readable until migration finishes. History operations and migration are serialized.
`needsMigration()` inspects structure/metadata rather than rehashing the entire store at
every startup. Manual `snap.migrate` still performs full validation.

For each snapshot, write objects and manifest first, then verify original paths, sizes and
hashes. Only then rename the legacy tree to `.migrated-files` and remove it. Interrupted
migration or cleanup resumes on a later run. Migration itself does not reduce snapshot count.

Old snapshots retain `legacyMaxFileBytes`: existing live files larger than the old 5 MiB
limit, absent from that snapshot, survive rollback. Historical bytes that were never captured
cannot be reconstructed. A conflict between such a file and the snapshot tree rejects
rollback before modifying the workspace.

## Restore and retention

Rollback verifies every referenced object and materializes a separate restore tree. It
snapshots the current code so rollback can itself be undone, then swaps directories by
rename. `state/restore-transaction.json` recovers an interrupted swap before factory files
are installed. Personal module data and chats are outside the workspace and are not rolled back.

Normal workspace maintenance keeps the latest 60 snapshots. An object is reclaimed only
when no retained snapshot references it. An unreadable or corrupt committed manifest blocks
object garbage collection; never guess which objects it needed. Large/binary files must not
be loaded as unlimited line-oriented text for diffs.

## APIs and checks

Native operations: `snap.list`, `snap.changes`, `snap.diff`, `snap.restore`, `snap.storage`,
`snap.migrate`. Statistics distinguish the logical size of all versions, unique object bytes
and history file bytes including manifests. Filesystem allocated blocks are a separate measurement.

The implementation is in [SnapshotStore.kt](../app/src/main/java/app/axiko/core/SnapshotStore.kt)
and [Workspace.kt](../app/src/main/java/app/axiko/core/Workspace.kt). Their JVM tests cover
shared bytes, immutable objects, legacy migration/resumption, missing/corrupt objects, safe
collection, large files, old-snapshot rollback, undo and interrupted directory swaps.

```powershell
.\gradlew.bat testDebugUnitTest --tests app.axiko.SnapshotStoreTest --tests app.axiko.WorkspaceHistoryTest
```
