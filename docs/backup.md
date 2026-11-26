# Encrypted backup contract

English · [Русский](backup.ru.md)

A backup is one encrypted `.axb` file containing personal workspace state. Use Settings →
System → Backup to save or restore it through Android's system document picker. It is a
portable backup, unlike the [code-only snapshot history](snapshot-storage.md).

Implementation: [`app/axiko/backup/`](../app/src/main/java/app/axiko/backup/): `BackupFormat`
(encryption), `BackupArchive` (inventory and validation), `BackupSwap` (recoverable directory
replacement), and `Backup` (Android integration).

## Scope

| Part | Archive location |
|---|---|
| Editable workspace | `files/axiko/` |
| Module/game data and databases | `files/data/` |
| Conversations, tasks and store state | `files/state/` |
| Drafts | `files/drafts/` |
| Snapshot history, only when requested | `files/history/` |
| Typed settings from SharedPreferences `axiko` | `prefs/axiko.json` |
| Vault values, inside backup encryption | `secrets.json` |

Excluded: downloaded models, Linux, app cache directories (`cache/`, `code_cache/`) and
`data/.webcache`, WebView cookies / localStorage / IndexedDB, transient restore/install
journals, store downloads and temporary state files. Symlinks and special files are omitted and listed in the manifest's `skipped` field.

Device-local settings (`boot_failures`, `widget.*`, `backup.*`), Android permissions and
widget bindings do not travel. The destination keeps its own local backup configuration.
AndroidKeyStore keys never leave their installation: the backup decrypts vault values inside
the shell, encrypts them under its own password, then seals them again with the destination's
key during restore. The automatic-backup password is excluded from the backup itself.

Game saves in `files/data` are included; WebView localStorage holds only their spare copy.
Website sign-ins and other WebView-only state are not portable through this format.

## Outer format v1

Integers are big-endian. The file is an 80-byte header followed by authenticated frames.

| Offset | Bytes | Field |
|---|---:|---|
| 0 | 8 | ASCII `AXIKOBAK` |
| 8 | 2 | Format version: `1` |
| 10 | 1 | KDF: `1` = PBKDF2-HMAC-SHA256 |
| 11 | 1 | Cipher: `1` = framed AES-256-GCM |
| 12 | 4 | PBKDF2 iteration count |
| 16 | 4 | Plaintext frame size; writer defaults to 1,048,576 |
| 20 | 16 | Salt |
| 36 | 7 | Random nonce prefix |
| 43 | 1 | Reserved: `0` |
| 44 | 32 | Password verifier |
| 76 | 4 | CRC-32 of header bytes 0–75 |

The reader checks magic/version before interpreting a newer layout, validates CRC and
accepts iteration counts 1…50,000,000 and frame sizes 16…16,777,216. Unknown formats or values
outside the supported bounds are rejected.

```text
password = UTF-8(NFC(input characters))
master   = PBKDF2-HMAC-SHA256(password, salt, iterations, 32 bytes)
key      = HMAC-SHA256(master, "axiko-backup/v1/encrypt")
verifier = HMAC-SHA256(master, "axiko-backup/v1/check")
```

The writer calibrates approximately one second of derivation on the device, bounded to
600,000…4,000,000 iterations, and records the result. A verifier mismatch reports an incorrect
password; a matching verifier followed by failed frame authentication reports damaged data.

```text
nonce (12 bytes) = prefix (7) || zero-based frame index (4) || final flag (1)
AAD              = all 80 header bytes
frame            = ciphertext || GCM tag (16 bytes)
```

The final flag is 1 only for the last frame. No frame lengths are stored: all non-final
frames have `frame size + 16` bytes; the final one has 16 through that size. A final frame
always exists, even for empty plaintext. Index and final-flag authentication detect reordered,
missing/repeated frames, truncation and appended bytes. An unfinished write lacks an
authenticated final frame and is not accepted as a complete backup.

## Inner archive and inventory

Decryption yields a ZIP. Its first entry is `manifest.json`, followed by directories
(including empty ones) and files in manifest order. Only `prefs/axiko.json`, `secrets.json`
and descendants of the five `files/` roots above are allowed. Absolute paths, backslashes,
control characters, empty segments, `.` and `..` are rejected.

The manifest contains `format: 1`, `kind: "axiko-backup"`, creation time, app/device metadata,
a history flag, secret count, `parts`, `dirs`, `files` and `skipped`. App ID is informational;
format and permitted archive paths determine compatibility, not a promise to accept arbitrary
legacy storage layouts.

- Each file has `path`, `size`, `sha256`, `mtime` (milliseconds) and `x` (executable flag).
- Part IDs are `workspace`, `data`, `state`, `drafts`, `history`, `settings`, `secrets`.
  Each part hash covers `path\nsize\nsha256\n` for its files in manifest order.
- Every extracted file must match size and SHA-256. Extra, missing or out-of-order entries
  reject the archive.
- Settings use `{ "key": { "t": "string|bool|int|long|float|set", "v": value } }`.
- Secrets use `{ "vault": { "name": "value" } }` inside the encrypted backup.

## Creating a backup

The shell asks for a password twice through a native dialog (minimum eight characters);
it is not passed through pages, the JavaScript engine or the model. Work runs off the UI
thread and reports `backup` events with stage and byte counters.

Files up to 8 MiB are staged and hashed once; larger files are hashed and read again during
writing. Workspace and history collection uses the workspace lock. An active store install
is rejected. If a large file changes, creation retries up to three times, then fails.

The copy is written under `files/backup/work-<token>/copy.axb` and fully read back using the
restore validator before publication to the selected document. An interrupted output is
removed where the document provider supports removal; otherwise it remains an invalid partial copy.

SQLite databases are copied as files while modules can still run. File integrity validation
is not a transaction-consistent snapshot across a live database and its journal. Prefer a
quiet period and test restore behavior for modules that maintain databases.

## Restoring and undo

1. Read the header and ask for the password in a native dialog.
2. Decrypt and validate the complete archive into `files/backup/restore-<token>/`; live state
   is unchanged so far.
3. Show the owner the source, date, size, contents and replacement scope. History is replaced
   only when included in the archive.
4. After confirmation, preserve recoverable current state, write `files/backup/restore.json`
   and restart the application.
5. Before normal startup, `BackupSwap` renames staged directories into place and retains the
   old state under `files/backup/before-<token>/`. Settings are loaded with their types;
   secrets are encrypted with the destination key. Previous WebView localStorage is retained
   with old state so a newer spare game save cannot override the restored save.
6. A successful page startup outside safe mode completes cleanup of old state.

The journal has `version`, `token`, `action` (`restore`/`undo`), `phase`
(`dirs`/`settings`/`check`/`cleanup`), `replace` and `at`. Steps are repeatable across process
interruption. Before successful cleanup, safe mode exposes Undo restore in Settings and the
native recovery menu; it swaps the previous directories, settings and secrets back.

## Scheduled backups

Modes are off, daily or weekly. Default time is 03:30; default retention is three copies.
The owner chooses a document-tree folder and a password through system UI. The password
stays in the device vault as `backup.password`. Automatic writes require a provider that
supports persistent directory access; otherwise use a manual document export.

Job `backup.auto` checks daily; weekly mode skips intermediate days. Automatic filenames
follow `axiko-backup-YYYY-MM-DD-auto.axb`. Retention deletes only recognized automatic files,
not manual exports. Failures notify the owner; latest success and error are visible in Settings.

## Standalone recovery and checks

```powershell
python -m pip install cryptography
python scripts/backup/decrypt.py copy.axb
python scripts/backup/decrypt.py copy.axb --out recovered
python scripts/backup/decrypt.py copy.axb --zip recovered.zip --password-file password.txt
```

The resulting ZIP/tree contains personal data in plaintext, including vault values. Keep
outputs and password files private and outside version control. The script validates the
same encryption and inventory format as the app.

`BackupFormatTest` checks round trips, normalization, wrong passwords, corrupted/reordered
frames, truncation, future versions, path validation and large files. `BackupSwapTest` checks
restore/undo interrupted at each rename and cleanup. A test sample is written under
`app/build/backup-sample/` for standalone-decryptor verification.

```powershell
.\gradlew.bat testDebugUnitTest
python scripts/backup/decrypt.py app/build/backup-sample/sample.axb --zip build/backup-check.zip --out build/backup-check --password-file app/build/backup-sample/password.txt
```
