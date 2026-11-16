package app.assi

import app.assi.core.Workspace
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Public workspace operations must retain rollback and undo after changing the storage format. */
class WorkspaceHistoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Before
    fun open() = Workspace.open(folder.root)

    private fun write(path: String, text: String): File = File(Workspace.root, path).apply {
        parentFile.mkdirs()
        writeText(text)
    }
    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun blob(text: String): File {
        val hash = digest(text.toByteArray())
        return File(Workspace.history, ".objects/${hash.take(2)}/${hash.drop(2)}")
    }
    private fun tree(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to digest(it.readBytes()) }
    private fun ids(): Set<String> {
        val list = Workspace.snapshots()
        return (0 until list.length()).map { list.getJSONObject(it).getString("id") }.toSet()
    }
    private fun legacy(id: String, contents: Map<String, String>) {
        val directory = File(Workspace.history, id).apply { mkdirs() }
        File(directory, "files").mkdirs()
        contents.forEach { (path, text) -> File(directory, "files/$path").apply {
            parentFile.mkdirs(); writeText(text)
        } }
        File(directory, "meta.json").writeText(JSONObject().put("id", id).put("time", 1_700_000_000_000L)
            .put("label", "Сохранённая старая версия").toString())
    }

    @Test
    fun newSnapshotsRestoreLargeFilesAndEmptyDirectoriesWithoutTouchingModuleData() {
        val bytes = ByteArray(5 * 1024 * 1024 + 71) { (it * 37).toByte() }
        val large = File(Workspace.root, "assets/large.bin").apply { parentFile.mkdirs(); writeBytes(bytes) }
        File(Workspace.root, "modules/example/empty").mkdirs()
        File(Workspace.data, "saved.json").writeText("personal state")
        val id = Workspace.snapshot("complete")
        large.writeText("changed")
        write("new.txt", "only after snapshot")

        Workspace.restore(id)

        assertArrayEquals(bytes, large.readBytes())
        assertTrue(File(Workspace.root, "modules/example/empty").isDirectory)
        assertFalse(File(Workspace.root, "new.txt").exists())
        assertEquals("personal state", File(Workspace.data, "saved.json").readText())
    }

    @Test
    fun missingObjectRejectsRollbackWithoutChangingLiveFilesOrHistory() {
        write("main.js", "saved version")
        val id = Workspace.snapshot("before")
        write("main.js", "current version")
        write("extra.txt", "current extra")
        val before = tree(Workspace.root)
        val beforeIds = ids()
        assertTrue(blob("saved version").delete())

        assertThrows(Exception::class.java) { Workspace.restore(id) }

        assertEquals(before, tree(Workspace.root))
        assertEquals(beforeIds, ids())
    }

    @Test
    fun equalLengthObjectCorruptionCannotReplaceTheWorkingTree() {
        write("first.js", "alpha")
        write("last.js", "omega")
        val id = Workspace.snapshot("before")
        write("first.js", "current first")
        write("last.js", "current last")
        val before = tree(Workspace.root)
        blob("omega").writeText("OMEGA")

        assertThrows(Exception::class.java) { Workspace.restore(id) }

        assertEquals(before, tree(Workspace.root))
    }

    @Test
    fun restoringOldestRetainedSnapshotStillWorksWhenUndoCreationTriggersPruning() {
        val values = LinkedHashMap<String, String>()
        repeat(60) { index ->
            val text = "version-$index"
            write("main.js", text)
            values[Workspace.snapshot("version $index")] = text
        }
        val list = Workspace.snapshots()
        assertEquals(60, list.length())
        val oldest = list.getJSONObject(list.length() - 1).getString("id")
        val beforeIds = ids()
        val latest = File(Workspace.root, "main.js").readText()

        Workspace.restore(oldest)

        assertEquals(values.getValue(oldest), File(Workspace.root, "main.js").readText())
        val undo = (ids() - beforeIds).single()
        Workspace.restore(undo)
        assertEquals(latest, File(Workspace.root, "main.js").readText())
    }

    @Test
    fun migratedLegacySnapshotKeepsChangesDiffAndUndoWorking() {
        val id = "20260101-010101-005"
        legacy(id, mapOf("main.js" to "old", "removed.txt" to "original"))
        write("main.js", "new")
        write("added.txt", "new file")
        val before = tree(Workspace.root)

        Workspace.migrateHistory()

        assertEquals(before, tree(Workspace.root))
        val changes = Workspace.changes(id)
        val byPath = (0 until changes.length()).associate {
            val item = changes.getJSONObject(it)
            item.getString("path") to item.getString("kind")
        }
        assertEquals(mapOf("main.js" to "changed", "removed.txt" to "removed", "added.txt" to "added"), byPath)
        val diff = Workspace.diff(id, "main.js")
        assertTrue(diff.contains("-old"))
        assertTrue(diff.contains("+new"))
        val beforeIds = ids()
        Workspace.restore(id)
        assertEquals("old", File(Workspace.root, "main.js").readText())
        assertEquals("original", File(Workspace.root, "removed.txt").readText())
        assertFalse(File(Workspace.root, "added.txt").exists())
        Workspace.restore((ids() - beforeIds).single())
        assertEquals(before, tree(Workspace.root))
    }

    @Test
    fun legacySnapshotsDoNotDeleteCurrentLargeFilesThatOldFormatCouldNotCapture() {
        val id = "20260101-010101-006"
        legacy(id, mapOf("main.js" to "old"))
        write("main.js", "current")
        val large = File(Workspace.root, "assets/unsnapshotted.bin").apply {
            parentFile.mkdirs(); writeBytes(ByteArray(5 * 1024 * 1024 + 1) { 42 })
        }
        val expectedHash = digest(large.readBytes())
        Workspace.migrateHistory()
        val changes = Workspace.changes(id)
        assertEquals(1, changes.length())
        assertEquals("main.js", changes.getJSONObject(0).getString("path"))

        Workspace.restore(id)

        assertEquals("old", File(Workspace.root, "main.js").readText())
        assertEquals(expectedHash, digest(large.readBytes()))
    }

    @Test
    fun legacyRestoreRefusesAFileThatWouldReplaceTheParentOfAnUnsnapshottedLargeFile() {
        val id = "20260101-010101-007"
        legacy(id, mapOf("assets" to "historically this was a file"))
        write("main.js", "current")
        File(Workspace.root, "assets/important.bin").apply {
            parentFile.mkdirs(); writeBytes(ByteArray(5 * 1024 * 1024 + 1) { 19 })
        }
        Workspace.migrateHistory()
        val before = tree(Workspace.root)

        assertThrows(Exception::class.java) { Workspace.restore(id) }

        assertEquals(before, tree(Workspace.root))
    }

    @Test
    fun openingAfterInterruptedRootMoveRecoversPreviousTreeBeforeCreatingAnEmptyRoot() {
        write("main.js", "previous working version")
        write("nested/file.txt", "recover all of me")
        File(Workspace.data, "notes.txt").writeText("personal notes")
        val before = tree(Workspace.root)
        val token = UUID.randomUUID().toString()
        val previous = File(folder.root, ".assi-before-$token")
        val stage = File(folder.root, ".assi-restore-$token").apply { mkdirs() }
        File(stage, "main.js").writeText("prepared replacement")
        val journal = File(Workspace.state, "restore-transaction.json")
        journal.writeText(JSONObject().put("version", 1).put("stage", stage.name)
            .put("previous", previous.name).toString())
        assertTrue(Workspace.root.renameTo(previous))
        assertFalse(Workspace.root.exists())

        Workspace.open(folder.root)

        assertEquals(before, tree(Workspace.root))
        assertEquals("personal notes", File(Workspace.data, "notes.txt").readText())
        assertFalse(previous.exists())
        assertFalse(stage.exists())
        assertFalse(journal.exists())
    }

    @Test
    fun openingAfterPublishedRestoreKeepsNewTreeAndCleansPreviousCopy() {
        write("main.js", "old working version")
        val token = UUID.randomUUID().toString()
        val previous = File(folder.root, ".assi-before-$token")
        val stage = File(folder.root, ".assi-restore-$token").apply { mkdirs() }
        File(stage, "main.js").writeText("published rollback")
        File(stage, "restored.txt").writeText("part of restored tree")
        val journal = File(Workspace.state, "restore-transaction.json")
        journal.writeText(JSONObject().put("version", 1).put("stage", stage.name)
            .put("previous", previous.name).toString())
        assertTrue(Workspace.root.renameTo(previous))
        assertTrue(stage.renameTo(Workspace.root))
        val published = tree(Workspace.root)

        Workspace.open(folder.root)

        assertEquals(published, tree(Workspace.root))
        assertEquals("published rollback", File(Workspace.root, "main.js").readText())
        assertFalse(previous.exists())
        assertFalse(stage.exists())
        assertFalse(journal.exists())
    }

    @Test
    fun invalidRestoreOrDiffPathsCannotReachModuleDataOrParentFiles() {
        write("main.js", "code")
        val id = Workspace.snapshot("valid")
        File(Workspace.data, "private.txt").writeText("personal")
        val before = tree(Workspace.root)

        assertThrows(Exception::class.java) { Workspace.restore("../data") }
        assertThrows(Exception::class.java) { Workspace.dropSnapshot("../data") }
        assertThrows(Exception::class.java) { Workspace.diff(id, "../data/private.txt") }

        assertEquals(before, tree(Workspace.root))
        assertEquals("personal", File(Workspace.data, "private.txt").readText())
    }
}
