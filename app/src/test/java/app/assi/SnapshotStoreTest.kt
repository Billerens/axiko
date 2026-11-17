package app.assi

import app.assi.core.SnapshotStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/** Storage regressions assert recoverable bytes and references, including interrupted migrations. */
class SnapshotStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val history get() = File(folder.root, "history").apply { mkdirs() }
    private val live get() = File(folder.root, "live").apply { mkdirs() }
    private fun write(root: File, path: String, text: String): File = File(root, path).apply {
        parentFile.mkdirs()
        writeText(text)
    }
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun objectFile(bytes: ByteArray): File {
        val digest = hash(bytes)
        return File(history, ".objects/${digest.take(2)}/${digest.drop(2)}")
    }
    private fun objectFiles(): List<File> = File(history, ".objects").walkTopDown().filter { it.isFile }.toList()
    private fun manifest(id: String) = File(history, "$id/manifest.json")
    private fun legacy(id: String, files: Map<String, String>): String {
        val directory = File(history, id).apply { mkdirs() }
        File(directory, "files").mkdirs()
        files.forEach { (path, text) -> write(File(directory, "files"), path, text) }
        val metadata = JSONObject().put("id", id).put("time", 1_700_000_000_000L)
            .put("label", "Старый снимок — $id").put("extra", "preserved").toString()
        File(directory, "meta.json").writeText(metadata)
        return metadata
    }
    private fun tree(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to hash(it.readBytes()) }
    private fun ids(store: SnapshotStore): Set<String> {
        val rows = store.snapshots()
        return (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }.toSet()
    }

    @Test
    fun startupProbeFindsLegacyAndInterruptedCleanupWithoutReadingObjects() {
        val store = SnapshotStore(history)
        assertFalse(store.needsMigration())
        val id = "legacy-startup-probe"
        legacy(id, mapOf("main.js" to "saved contents"))
        assertTrue(store.needsMigration())

        store.read(id) // Manifest published; the original tree still needs cleanup.
        assertTrue(store.needsMigration())
        val directory = File(history, id)
        assertTrue(File(directory, "files").renameTo(File(directory, ".migrated-files")))
        assertTrue(store.needsMigration())
        store.migrate()
        assertFalse(store.needsMigration())

        // Startup does not scan/hash blobs; an explicitly requested migration still verifies them.
        objectFile("saved contents".toByteArray()).writeText("bad contents")
        assertFalse(store.needsMigration())
        assertThrows(Exception::class.java) { store.migrate() }
    }

    @Test
    fun identicalContentIsStoredOnceAcrossPathsAndSnapshots() {
        write(live, "modules/one/main.js", "same bytes")
        write(live, "modules/two/main.js", "same bytes")
        val store = SnapshotStore(history)
        val first = store.create(live, "first")
        val second = store.create(live, "second")

        assertNotEquals(first, second)
        assertEquals(setOf(first, second), ids(store))
        assertEquals(1, objectFiles().size)
        assertEquals("same bytes".toByteArray().size.toLong(), objectFiles().single().length())
        val restored = File(folder.root, "restored")
        store.materialize(first, restored)
        assertEquals(tree(live), tree(restored))
    }

    @Test
    fun sourceAndRestoredEditsCannotModifyStoredObjects() {
        val source = write(live, "main.js", "alpha")
        val store = SnapshotStore(history)
        val id = store.create(live, "before")
        val blob = objectFile("alpha".toByteArray())
        source.writeText("ALPHA") // Equal-length, in-place edit exposes accidental hard links.
        assertEquals("alpha", blob.readText())

        val restored = File(folder.root, "restored")
        store.materialize(id, restored)
        File(restored, "main.js").writeText("OMEGA")
        assertEquals("alpha", blob.readText())
        val second = File(folder.root, "second")
        store.materialize(id, second)
        assertEquals("alpha", File(second, "main.js").readText())
    }

    @Test
    fun legacyMigrationKeepsIdsMetadataBytesDirectoriesAndSharedContents() {
        val first = "20260101-010101-001"
        val second = "20260101-010101-002"
        val firstMeta = legacy(first, mapOf("modules/one/main.js" to "shared", "a.txt" to "first"))
        val secondMeta = legacy(second, mapOf("modules/two/main.js" to "shared", "b.txt" to "second"))
        File(history, "$first/files/empty/nested").mkdirs()
        val before = tree(File(history, "$first/files"))
        val store = SnapshotStore(history)

        store.migrate()

        assertEquals(setOf(first, second), ids(store))
        assertEquals(firstMeta, File(history, "$first/meta.json").readText())
        assertEquals(secondMeta, File(history, "$second/meta.json").readText())
        assertFalse(File(history, "$first/files").exists())
        assertFalse(File(history, "$second/files").exists())
        assertEquals(3, objectFiles().size)
        assertEquals(5L * 1024 * 1024, store.read(first).legacyMaxFileBytes)
        val restored = File(folder.root, "restored")
        store.materialize(first, restored)
        assertEquals(before, tree(restored))
        assertTrue(File(restored, "empty/nested").isDirectory)
        store.migrate()
        assertEquals(setOf(first, second), ids(store))
        assertEquals(3, objectFiles().size)
    }

    @Test
    fun migrationDoesNotApplyRetentionToExistingSnapshots() {
        val expected = (0 until 65).map { "20260101-010101-%03d".format(it) }.toSet()
        expected.forEach { legacy(it, mapOf("shared.js" to "same")) }
        val store = SnapshotStore(history)
        store.migrate()
        assertEquals(expected, ids(store))
        assertEquals(1, objectFiles().size)
    }

    @Test
    fun interruptedMigrationRetainsLegacyAndCanResumeFromCommittedManifest() {
        val id = "20260101-010101-003"
        legacy(id, mapOf("main.js" to "recover me", "nested/file.txt" to "also here"))
        val original = tree(File(history, "$id/files"))
        var interrupted = false
        val store = SnapshotStore(history, afterManifestCommitted = {
            interrupted = true
            throw IllegalStateException("simulated termination after manifest commit")
        })
        runCatching { store.migrate() }

        assertTrue("The failure must happen at the intended durable commit boundary", interrupted)
        assertTrue(manifest(id).isFile)
        assertEquals(original, tree(File(history, "$id/files")))
        val recovered = SnapshotStore(history)
        recovered.migrate()
        assertFalse(File(history, "$id/files").exists())
        val restored = File(folder.root, "restored")
        recovered.materialize(id, restored)
        assertEquals(original, tree(restored))
    }

    @Test
    fun migrationResumesAfterVerifiedLegacyTreeWasRenamedAndPartiallyRemoved() {
        val id = "20260101-010101-007"
        legacy(id, mapOf("main.js" to "recover me", "nested/file.txt" to "already moved to objects"))
        val files = File(history, "$id/files")
        val original = tree(files)
        val store = SnapshotStore(history, afterManifestCommitted = {
            throw IllegalStateException("simulated interruption")
        })
        runCatching { store.migrate() }
        assertTrue(manifest(id).isFile)
        val retired = File(history, "$id/.migrated-files")
        assertTrue(files.renameTo(retired))
        assertTrue(File(retired, "nested/file.txt").delete())

        val recovered = SnapshotStore(history)
        recovered.migrate()

        assertFalse(retired.exists())
        val restored = File(folder.root, "restored")
        recovered.materialize(id, restored)
        assertEquals(original, tree(restored))
    }

    @Test
    fun readingLegacySnapshotAloneDoesNotDeleteOriginalFiles() {
        val id = "20260101-010101-004"
        legacy(id, mapOf("main.js" to "original"))
        val store = SnapshotStore(history)
        val snapshot = store.read(id)
        assertEquals("original", store.content(snapshot, "main.js").readText())
        assertEquals("original", File(history, "$id/files/main.js").readText())
    }

    @Test
    fun migrationCannotDeleteLegacyFilesWhenCommittedManifestOmitsOne() {
        val id = "20260101-010101-005"
        legacy(id, mapOf("main.js" to "main", "important.txt" to "keep this"))
        val store = SnapshotStore(history)
        store.read(id)
        val original = tree(File(history, "$id/files"))
        val incomplete = JSONObject(manifest(id).readText())
        incomplete.getJSONObject("files").remove("important.txt")
        manifest(id).writeText(incomplete.toString())

        runCatching { store.migrate() }

        assertEquals(original, tree(File(history, "$id/files")))
    }

    @Test
    fun migrationRetainsLegacyBytesIfAnImportedObjectIsCorrupted() {
        val id = "20260101-010101-006"
        legacy(id, mapOf("main.js" to "original"))
        val store = SnapshotStore(history)
        store.read(id)
        objectFile("original".toByteArray()).writeText("CORRUPT!")

        runCatching { store.migrate() }

        assertEquals("original", File(history, "$id/files/main.js").readText())
    }

    @Test
    fun removingOneSnapshotKeepsObjectsReferencedByAnother() {
        write(live, "shared.js", "shared")
        val unique = write(live, "old.js", "only old")
        val store = SnapshotStore(history)
        val first = store.create(live, "first")
        assertTrue(unique.delete())
        write(live, "new.js", "only new")
        val second = store.create(live, "second")

        store.drop(first)
        store.collectGarbage()

        assertEquals(setOf(second), ids(store))
        assertTrue(objectFile("shared".toByteArray()).isFile)
        assertFalse(objectFile("only old".toByteArray()).exists())
        val restored = File(folder.root, "restored")
        store.materialize(second, restored)
        assertEquals(tree(live), tree(restored))
    }

    @Test
    fun whatSnapshotsReferToIsRememberedAndStaysTrueAsTheyComeAndGo() {
        val store = SnapshotStore(history)
        val file = write(live, "main.js", "first")
        val first = store.create(live, "first")
        file.writeText("second")
        val second = store.create(live, "second")

        // Dropped: its own object goes, the other snapshot's stays, and nothing is remembered about the dropped one.
        store.drop(first)
        assertFalse(objectFile("first".toByteArray()).exists())
        assertTrue(objectFile("second".toByteArray()).isFile)

        // The same bytes again are stored again, and a snapshot taken by another instance is known to this one.
        file.writeText("first")
        val third = SnapshotStore(history).create(live, "third")
        store.drop(second)
        assertEquals(setOf(third), ids(store))
        assertFalse(objectFile("second".toByteArray()).exists())
        val restored = File(folder.root, "restored")
        store.materialize(third, restored)
        assertEquals(tree(live), tree(restored))

        // A manifest damaged after it was read still stops reclamation.
        val objectsBefore = tree(File(history, ".objects"))
        objectFile("orphan".toByteArray()).apply { parentFile.mkdirs(); writeText("orphan") }
        manifest(third).writeText("{broken")
        runCatching { store.collectGarbage() }
        assertEquals(objectsBefore + ("${hash("orphan".toByteArray()).take(2)}/${hash("orphan".toByteArray()).drop(2)}" to hash("orphan".toByteArray())), tree(File(history, ".objects")))
    }

    @Test
    fun aFileIsReadAgainWheneverItMayHaveChanged() {
        val store = SnapshotStore(history)
        fun saved(id: String, name: String): String = File(folder.root, name).also { store.materialize(id, it) }.let { File(it, "main.js").readText() }
        val file = write(live, "main.js", "alpha")
        assertTrue(file.setLastModified(System.currentTimeMillis() - 60_000))
        assertEquals("alpha", saved(store.create(live, "settled"), "one"))
        assertEquals("alpha", saved(store.create(live, "untouched"), "two"))

        // An edit of the same length moves the time of change: seen.
        file.writeText("ALPHA")
        assertEquals("ALPHA", saved(store.create(live, "edited"), "three"))

        // A file changed just before it was read is not trusted by its time: a second write in the same tick is still seen.
        val tick = file.lastModified()
        file.writeText("OMEGA")
        assertTrue(file.setLastModified(tick))
        assertEquals("OMEGA", saved(store.create(live, "same tick"), "four"))
    }

    @Test
    fun anUnchangedFileIsStoredAgainWhenItsObjectWentWithADroppedSnapshot() {
        val store = SnapshotStore(history)
        val file = write(live, "main.js", "only here")
        assertTrue(file.setLastModified(System.currentTimeMillis() - 60_000))
        store.drop(store.create(live, "dropped"))
        assertFalse(objectFile("only here".toByteArray()).exists())

        val id = store.create(live, "again")
        val restored = File(folder.root, "restored")
        store.materialize(id, restored)
        assertEquals(tree(live), tree(restored))
    }

    @Test
    fun pruningPreservesPinnedOldSnapshotAndObjectsSharedWithNewest() {
        write(live, "shared.txt", "shared")
        val store = SnapshotStore(history)
        write(live, "version.txt", "oldest")
        val oldest = store.create(live, "pinned")
        write(live, "version.txt", "middle")
        val middle = store.create(live, "discard")
        write(live, "version.txt", "newest")
        val newest = store.create(live, "keep")

        store.prune(keep = 1, pinned = setOf(oldest))
        store.collectGarbage()

        assertEquals(setOf(oldest, newest), ids(store))
        assertFalse(ids(store).contains(middle))
        assertFalse(objectFile("middle".toByteArray()).exists())
        val restored = File(folder.root, "oldest")
        store.materialize(oldest, restored)
        assertEquals("oldest", File(restored, "version.txt").readText())
        assertEquals("shared", File(restored, "shared.txt").readText())
        val latest = File(folder.root, "newest")
        store.materialize(newest, latest)
        assertEquals(tree(live), tree(latest))
    }

    @Test
    fun garbageCollectionFailsClosedWhenAnyCommittedManifestIsUnreadable() {
        write(live, "main.js", "referenced")
        val store = SnapshotStore(history)
        val id = store.create(live, "valid until damaged")
        val originalManifest = manifest(id).readText()
        val orphan = objectFile("orphan".toByteArray()).apply { parentFile.mkdirs(); writeText("orphan") }
        val objectsBefore = tree(File(history, ".objects"))
        manifest(id).writeText("{broken")

        runCatching { store.collectGarbage() }
        assertEquals(objectsBefore, tree(File(history, ".objects")))

        manifest(id).writeText(originalManifest)
        store.collectGarbage()
        assertFalse(orphan.exists())
        assertTrue(objectFile("referenced".toByteArray()).exists())
    }

    @Test
    fun failedHistoryListingCannotMakeGarbageCollectionDeleteReferencedObjects() {
        write(live, "main.js", "recoverable content")
        val store = SnapshotStore(history)
        val id = store.create(live, "saved")
        val objectsBefore = tree(File(history, ".objects"))
        val unreadableHistory = object : File(history.path) {
            override fun listFiles(): Array<File>? = null
        }

        assertThrows(Exception::class.java) { SnapshotStore(unreadableHistory).collectGarbage() }

        assertEquals(objectsBefore, tree(File(history, ".objects")))
        val restored = File(folder.root, "restored")
        store.materialize(id, restored)
        assertEquals(tree(live), tree(restored))
    }

    @Test
    fun materializeRejectsMissingOrCorruptedObjectsBeforeWritingAnyDestinationFiles() {
        write(live, "a.txt", "first")
        write(live, "z.txt", "last")
        val store = SnapshotStore(history)
        val id = store.create(live, "snapshot")
        val damaged = objectFile("last".toByteArray())
        damaged.writeText("LOST")
        val destination = File(folder.root, "destination")

        assertThrows(Exception::class.java) { store.materialize(id, destination) }
        assertTrue(!destination.exists() || destination.listFiles().orEmpty().isEmpty())
        assertTrue(damaged.delete())
        assertThrows(Exception::class.java) { store.materialize(id, destination) }
        assertTrue(!destination.exists() || destination.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun materializeDoesNotOverwriteAnExistingDestination() {
        write(live, "main.js", "snapshot")
        val store = SnapshotStore(history)
        val id = store.create(live, "saved")
        val destination = File(folder.root, "destination")
        write(destination, "valuable.txt", "leave alone")
        assertThrows(Exception::class.java) { store.materialize(id, destination) }
        assertEquals(mapOf("valuable.txt" to hash("leave alone".toByteArray())), tree(destination))
    }

    @Test
    fun invalidIdsAndPathsCannotReadOrDeleteOutsideTheHistory() {
        write(live, "main.js", "code")
        val outside = write(folder.root, "outside.txt", "valuable")
        val store = SnapshotStore(history)
        val id = store.create(live, "valid")
        val snapshot = store.read(id)
        for (bad in listOf("../outside.txt", "", ".objects", "a/b", "a\\b")) {
            assertThrows(Exception::class.java) { store.read(bad) }
            assertThrows(Exception::class.java) { store.drop(bad) }
        }
        for (bad in listOf("../outside.txt", "/outside.txt", "a/../../outside.txt", "C:\\outside.txt")) {
            assertThrows(Exception::class.java) { store.content(snapshot, bad) }
        }
        assertEquals("valuable", outside.readText())
        assertEquals(setOf(id), ids(store))
    }

    @Test
    fun maliciousManifestCannotEscapeTheMaterializationDirectory() {
        write(live, "main.js", "code")
        val store = SnapshotStore(history)
        val id = store.create(live, "valid")
        val value = JSONObject(manifest(id).readText())
        val entry = value.getJSONObject("files").getJSONObject("main.js")
        value.getJSONObject("files").put("../outside.txt", entry)
        manifest(id).writeText(value.toString())
        val outside = write(folder.root, "outside.txt", "valuable")
        assertThrows(Exception::class.java) { store.materialize(id, File(folder.root, "destination")) }
        assertEquals("valuable", outside.readText())
    }

    @Test
    fun repeatedRapidSnapshotsHaveDistinctIdsAndChronologicalMetadata() {
        write(live, "main.js", "one object")
        val store = SnapshotStore(history)
        val created = (0 until 25).map { store.create(live, "snapshot $it") }
        assertEquals(25, created.toSet().size)
        val times = created.map { store.read(it).meta.getLong("time") }
        assertTrue(times.zipWithNext().all { (a, b) -> b > a })
        assertEquals(created.last(), store.snapshots().getJSONObject(0).getString("id"))
        assertEquals(1, objectFiles().size)
    }
}
