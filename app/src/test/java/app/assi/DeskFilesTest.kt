package app.assi

import app.assi.core.DeskAssets
import app.assi.core.DeskFiles
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DeskFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun file(path: String, text: String): File = File(folder.root, path).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    @Test
    fun staleRevisionDoesNotOverwriteAnAgentsEdit() {
        val target = file("modules/notes/main.js", "old")
        var snapshots = 0
        val desk = DeskFiles(folder.root, beforeWrite = { snapshots++ })
        val opened = desk.read("modules/notes/main.js")
        target.writeText("agent edit")
        val error = assertThrows(DeskFiles.Conflict::class.java) {
            desk.write(opened.path, "editor edit", opened.revision)
        }
        assertEquals(desk.read(opened.path).revision, error.actualRevision)
        assertEquals("agent edit", target.readText())
        assertEquals(0, snapshots)
    }

    @Test
    fun eachSaveAcknowledgesOnlyTheSubmittedTextAndRevision() {
        file("one.js", "initial")
        val desk = DeskFiles(folder.root)
        val opened = desk.read("one.js")
        val saved = desk.write("one.js", "first edit", opened.revision)
        assertEquals("first edit", saved.text)
        assertNotEquals(opened.revision, saved.revision)
        assertEquals(saved, desk.read("one.js"))
        assertThrows(DeskFiles.Conflict::class.java) { desk.write("one.js", "newer edit", opened.revision) }
        assertEquals("newer edit", desk.write("one.js", "newer edit", saved.revision).text)
    }

    @Test
    fun editMadeDuringSnapshotIsNotOverwritten() {
        val target = file("main.js", "before")
        val desk = DeskFiles(folder.root, beforeWrite = { target.writeText("changed during snapshot") })
        val opened = desk.read("main.js")
        assertThrows(DeskFiles.Conflict::class.java) { desk.write(opened.path, "editor edit", opened.revision) }
        assertEquals("changed during snapshot", target.readText())
    }

    @Test
    fun simultaneousEditorsCannotBothSaveTheSameRevision() {
        file("shared.js", "before")
        val desk = DeskFiles(folder.root)
        val revision = desk.read("shared.js").revision
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val work = listOf("a", "b").map { text ->
                pool.submit(Callable {
                    start.await()
                    try { desk.write("shared.js", text, revision); true } catch (conflict: DeskFiles.Conflict) { false }
                })
            }
            start.countDown()
            assertEquals(1, work.count { it.get(5, TimeUnit.SECONDS) })
            assertTrue(desk.read("shared.js").text in listOf("a", "b"))
        } finally { pool.shutdownNow() }
    }

    @Test
    fun creationRequiresMissingRevisionAndReadsDoNotCreateFiles() {
        var snapshots = 0
        val desk = DeskFiles(folder.root, beforeWrite = { snapshots++ })
        assertThrows(DeskFiles.Missing::class.java) { desk.read("new/main.js") }
        assertFalse(File(folder.root, "new").exists())
        val made = desk.write("new/main.js", "Привет", null)
        assertEquals(12, made.size)
        assertTrue(made.exists)
        assertEquals(1, snapshots)
        assertThrows(DeskFiles.Conflict::class.java) { desk.write("new/main.js", "overwrite", null) }
        desk.write(made.path, made.text, made.revision)
        assertEquals("Unchanged text needs no additional snapshot", 1, snapshots)
        assertEquals(listOf("main.js"), File(folder.root, "new").list()!!.toList())
    }

    @Test
    fun rejectsOutsidePathsDataBinaryAndOversizeText() {
        val desk = DeskFiles(folder.root)
        for (path in listOf("../outside", "/tmp/file", "C:/file", "ui\\desk.js", "ui/../file", "data/notes.json")) {
            assertThrows(path, IllegalArgumentException::class.java) { desk.write(path, "bad", null) }
        }
        val outside = folder.newFolder("outside")
        val bound = DeskFiles(folder.newFolder("workspace"), locate = { _, _ -> File(outside, "file.js") })
        assertThrows(IllegalArgumentException::class.java) { bound.write("file.js", "bad", null) }
        file("binary.bin", "ok").writeBytes(byteArrayOf(0, 1, 2))
        assertThrows(IllegalArgumentException::class.java) { desk.read("binary.bin") }
        file("invalid.bin", "ok").writeBytes(byteArrayOf(0xff.toByte(), 0xfe.toByte()))
        assertThrows(IllegalArgumentException::class.java) { desk.read("invalid.bin") }
        assertThrows(IllegalArgumentException::class.java) { desk.write("large.js", "x".repeat(DeskFiles.MAX_BYTES + 1), null) }
        assertFalse(File(folder.root, "large.js").exists())
    }

    @Test
    fun aDraftReadsLiveFallbackAndWritesOnlyItsOwnLayer() {
        val live = folder.newFolder("live")
        val draft = folder.newFolder("draft")
        File(live, "main.js").writeText("live")
        val desk = DeskFiles(live, locate = { path, write ->
            File(draft, path).takeIf { write || it.exists() } ?: File(live, path)
        }, allowedRoots = { listOf(live, draft) })
        val opened = desk.read("main.js")
        val result = desk.write("main.js", "preview", opened.revision)
        assertEquals("preview", result.text)
        assertEquals("preview", File(draft, "main.js").readText())
        assertEquals("live", File(live, "main.js").readText())
        assertEquals(result, desk.read("main.js"))
    }

    @Test
    fun factoryBundleIsFallbackAndExplicitRecoveryIgnoresCustomFiles() {
        val root = folder.root
        file("ui/desk/index.html", "custom")
        val fallback = mapOf("ui/desk/index.html" to "factory", "ui/desk/client.js" to "bridge")
        fun read(path: String, recovery: Boolean): String? = DeskAssets.read(path, recovery,
            { File(root, it) }, { fallback[it]?.toByteArray() })?.toString(Charsets.UTF_8)
        assertEquals("ui/desk/index.html", DeskAssets.path("/"))
        assertEquals("ui/desk/client.js", DeskAssets.path("/__assi/client.js"))
        assertEquals("custom", read("ui/desk/index.html", false))
        assertEquals("factory", read("ui/desk/index.html", true))
        assertEquals("bridge", read("ui/desk/client.js", false))
        assertEquals("bridge", read("ui/desk/client.js", true))
        assertNull(read("ui/desk/missing.js", false))
        assertNull(DeskAssets.path("/modules/notes/ui/index.html"))
        assertThrows(IllegalArgumentException::class.java) { DeskAssets.path("/ui/desk/../secret") }
    }

    @Test
    fun recoveryUsesFactorySharedDependenciesWithoutLoadingBrokenCustomVersions() {
        val factory = mapOf("ui/kit/kit.css" to "factory styles", "ui/kit/kit.js" to "factory helpers",
            "ui/vendor/preact.js" to "factory library")
        for ((path, expected) in factory) {
            file(path, "broken user version")
            assertNull("Normal serving must keep the existing themed Pages path", DeskAssets.path("/" + path))
            assertEquals(path, DeskAssets.path("/" + path, true))
            val result = DeskAssets.read(path, true, { fail("Recovery must not read the user's dependency"); File(folder.root, it) },
                { factory[it]?.toByteArray() })
            assertEquals(expected, result?.toString(Charsets.UTF_8))
        }
        assertNull(DeskAssets.path("/modules/notes/main.js", true))
        assertNull(DeskAssets.path("/data/notes.json", true))
        assertThrows(IllegalArgumentException::class.java) { DeskAssets.path("/ui/kit/../../data/notes.json", true) }
    }
}
