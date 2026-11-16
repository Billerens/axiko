package app.assi

import app.assi.core.Workspace
import app.assi.voice.Tts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Snapshots: any change to the workspace can be taken back, and module data is left alone. */
class WorkspaceTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Before
    fun open() = Workspace.open(folder.root)

    private fun file(path: String) = File(Workspace.root, path)

    @Test
    fun restoreBringsBackEditedAndDeletedFilesAndRemovesNewOnes() {
        file("modules/notes").mkdirs()
        file("modules/notes/main.js").writeText("old")
        file("lib.js").writeText("kept")
        val before = Workspace.snapshot("перед изменением")

        file("modules/notes/main.js").writeText("new")
        file("lib.js").delete()
        file("modules/extra.js").writeText("added")
        File(Workspace.data, "notes.json").writeText("user data")

        Workspace.restore(before)

        assertEquals("old", file("modules/notes/main.js").readText())
        assertEquals("kept", file("lib.js").readText())
        assertFalse(file("modules/extra.js").exists())
        assertEquals("user data", File(Workspace.data, "notes.json").readText())
    }

    @Test
    fun restoreItselfCanBeUndone() {
        file("a.txt").writeText("one")
        val first = Workspace.snapshot("first")
        file("a.txt").writeText("two")

        Workspace.restore(first)
        val undo = Workspace.snapshots().getJSONObject(0).getString("id")
        Workspace.restore(undo)

        assertEquals("two", file("a.txt").readText())
    }

    @Test
    fun signatureChangesOnlyWhenTheTreeDoes() {
        file("a.txt").writeText("one")
        val same = Workspace.signature()
        assertEquals(same, Workspace.signature())
        file("b.txt").writeText("x")
        assertNotEquals(same, Workspace.signature())
    }

    @Test
    fun pathsResolveIntoTheWorkspaceOrTheDataFolder() {
        assertEquals(File(Workspace.root, "ui/home/index.html"), Workspace.resolve("ui/home/index.html"))
        assertEquals(File(Workspace.data, "notes.db"), Workspace.resolve("data/notes.db"))
        assertEquals("data/notes.db", Workspace.display(File(Workspace.data, "notes.db")))
        assertTrue(Workspace.resolve(folder.root.path + "/x").isAbsolute)
    }

    @Test
    fun speechLeavesOutWhatIsNotSaidAloud() {
        val spoken = Tts.speakable("## Готово\n\nСм. `main.js` и [справку](https://example.com/a).\n\n```js\nvar x = 1;\n```\n- **первое**\n- второе")
        assertTrue(spoken.startsWith("Готово"))
        assertTrue(spoken.contains("См. main.js и справку."))
        assertTrue(spoken.contains("первое") && spoken.contains("второе"))
        for (unspoken in listOf("var x", "```", "`", "**", "#", "https", "- ")) {
            assertFalse("\"$unspoken\" must not be read aloud", spoken.contains(unspoken))
        }
    }
}
