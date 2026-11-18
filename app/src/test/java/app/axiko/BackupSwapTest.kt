package app.axiko

import app.axiko.backup.BackupSwap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Putting a restored copy in place is a handful of renames with a journal. Whatever step the
 * phone dies on, the next start ends with either the old folders or the new ones — and the old
 * ones stay beside until they are no longer needed.
 */
class BackupSwapTest {
    private lateinit var files: File
    private lateinit var swap: BackupSwap
    private val token = "0123456789abcdef0123456789abcdef"
    private val other = "ffffffffffffffffffffffffffffffff"

    @Before
    fun open() {
        files = Files.createTempDirectory("axiko-swap").toFile()
        swap = BackupSwap(files)
    }

    @After
    fun close() {
        files.deleteRecursively()
    }

    private fun put(path: String, text: String) = File(files, path).apply {
        parentFile.mkdirs()
        writeText(text)
    }

    private fun text(path: String): String? = File(files, path).takeIf { it.isFile }?.readText()

    private val all = listOf("axiko", "data", "state", "drafts")

    /** The app as it is ("old"), and a copy unpacked beside it ("new"), the way the restore leaves them. */
    private fun plant() {
        put("axiko/ui/home.html", "old")
        put("data/notes.db", "old")
        put("state/chats/t.json", "old")
        put("history/keep.txt", "old")
        put("models/llm/gemma.bin", "model")
        for (name in all) put("backup/restore-$token/files/$name/mark.txt", "new $name")
        put("backup/restore-$token/files/axiko/ui/home.html", "new")
    }

    @Test
    fun `the copy takes the place of the folders and the old ones wait beside`() {
        plant()
        swap.request(token, all, 1L)
        val journal = swap.recover()!!
        assertEquals(BackupSwap.SETTINGS, journal.phase)
        assertEquals("new", text("axiko/ui/home.html"))
        assertEquals("new data", text("data/mark.txt"))
        assertNull(text("data/notes.db"))
        assertEquals("new drafts", text("drafts/mark.txt"))
        assertEquals("what the copy does not carry is left alone", "old", text("history/keep.txt"))
        assertEquals("model", text("models/llm/gemma.bin"))
        assertEquals("old", text("backup/before-$token/axiko/ui/home.html"))
        assertEquals("old", text("backup/before-$token/data/notes.db"))
        assertTrue("a folder that was not there is remembered as an empty one", File(files, "backup/before-$token/drafts").isDirectory)

        // The second start does nothing more to the folders.
        assertEquals(BackupSwap.SETTINGS, swap.recover()!!.phase)
        assertEquals("new", text("axiko/ui/home.html"))
        swap.advance(BackupSwap.CHECK)
        assertEquals(BackupSwap.CHECK, swap.recover()!!.phase)

        swap.advance(BackupSwap.CLEANUP)
        swap.finish()
        assertNull(swap.read())
        assertFalse(File(files, "backup/before-$token").exists())
        assertFalse(File(files, "backup/restore-$token").exists())
        assertEquals("new", text("axiko/ui/home.html"))
    }

    @Test
    fun `an interruption between any two renames is finished at the next start`() {
        // The moves the swap makes, in order: each prefix of them is a place the phone could have died.
        val moves = ArrayList<Pair<String, String>>()
        for (name in all) {
            if (name != "drafts") moves.add(name to "backup/before-$token/$name")
            moves.add("backup/restore-$token/files/$name" to name)
        }
        for (done in 0..moves.size) {
            files.deleteRecursively()
            files.mkdirs()
            plant()
            swap.request(token, all, 1L)
            File(files, "backup/before-$token").mkdirs()
            moves.take(done).forEach { (from, to) ->
                if (to == "drafts") File(files, "backup/before-$token/drafts").mkdirs()
                assertTrue(File(files, from).renameTo(File(files, to)))
            }
            assertEquals("after $done moves", BackupSwap.SETTINGS, swap.recover()!!.phase)
            for (name in all) assertEquals("after $done moves", "new $name", text("$name/mark.txt"))
            assertEquals("old", text("backup/before-$token/axiko/ui/home.html"))
            assertEquals("old", text("backup/before-$token/state/chats/t.json"))
        }
    }

    @Test
    fun `a restore can be taken back`() {
        plant()
        swap.request(token, all, 1L)
        swap.recover()
        swap.advance(BackupSwap.CHECK)

        swap.requestUndo()
        val journal = swap.recover()!!
        assertEquals(BackupSwap.UNDO, journal.action)
        assertEquals(BackupSwap.SETTINGS, journal.phase)
        assertEquals("old", text("axiko/ui/home.html"))
        assertEquals("old", text("data/notes.db"))
        assertEquals("old", text("state/chats/t.json"))
        assertNull("the folder the copy brought is gone", text("drafts/mark.txt"))
        assertEquals("new", text("backup/undone-$token/axiko/ui/home.html"))

        swap.advance(BackupSwap.CLEANUP)
        swap.finish()
        assertNull(swap.read())
        assertEquals(emptyList<String>(), File(files, "backup").list()!!.toList())
        assertEquals("old", text("axiko/ui/home.html"))
    }

    @Test
    fun `taking back survives an interruption too`() {
        plant()
        swap.request(token, all, 1L)
        swap.recover()
        swap.advance(BackupSwap.CHECK)
        swap.requestUndo()
        // The phone died after the restored workspace was moved away and before the old one came back.
        File(files, "backup/undone-$token").mkdirs()
        assertTrue(File(files, "axiko").renameTo(File(files, "backup/undone-$token/axiko")))
        swap.recover()
        assertEquals("old", text("axiko/ui/home.html"))
        assertEquals("old", text("data/notes.db"))
        assertEquals("new", text("backup/undone-$token/axiko/ui/home.html"))
    }

    @Test
    fun `what cannot be right is refused and nothing is moved`() {
        plant()
        fun refused(block: () -> Unit) = assertTrue(runCatching(block).exceptionOrNull() is IllegalArgumentException)
        refused { swap.request("../../etc", listOf("axiko"), 1L) }
        refused { swap.request(token, listOf("models"), 1L) }
        refused { swap.request(token, listOf("history"), 1L) } // the copy has no such folder unpacked
        refused { swap.requestUndo() } // nothing was restored
        assertNull(swap.recover())
        assertEquals("old", text("axiko/ui/home.html"))

        // A journal written by something else: a folder outside the known ones, a later version.
        put("backup/restore.json", """{"version":1,"token":"$token","action":"restore","phase":"dirs","replace":["../x"],"at":1}""")
        refused { swap.recover() }
        put("backup/restore.json", """{"version":2,"token":"$token","action":"restore","phase":"dirs","replace":["axiko"],"at":1}""")
        refused { swap.recover() }
        assertEquals("old", text("axiko/ui/home.html"))
        File(files, "backup/restore.json").delete()

        // Both the old and the kept folder present for a name: not a state the swap produces.
        swap.request(token, all, 1L)
        put("backup/before-$token/axiko/x.txt", "?")
        refused { swap.recover() }
        assertEquals("old", text("axiko/ui/home.html"))
    }

    @Test
    fun `folders left by work that never finished are found, the old state is not among them`() {
        plant()
        put("backup/work-$other/copy.axb", "half")
        put("backup/before-$other/axiko/x", "old")
        assertEquals(setOf("restore-$token", "work-$other"), swap.leftovers().map { it.name }.toSet())
        swap.request(token, all, 1L)
        assertEquals("the copy being restored is not a leftover", setOf("work-$other"), swap.leftovers().map { it.name }.toSet())
        swap.leftovers().forEach { swap.deleteTree(it) }
        assertFalse(File(files, "backup/work-$other").exists())
        assertTrue(runCatching { swap.deleteTree(File(files, "axiko")) }.isFailure)
        assertEquals("old", text("axiko/ui/home.html"))
        assertEquals("old", text("backup/before-$other/axiko/x"))
    }
}
