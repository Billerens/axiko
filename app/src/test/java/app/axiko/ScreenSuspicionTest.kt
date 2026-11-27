package app.axiko

import app.axiko.core.ScreenSuspicionStore
import app.axiko.core.ScreenSuspicions.FactoryObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ScreenSuspicionTest {
    @get:Rule val folder = TemporaryFolder()

    private val path = "ui/settings/index.html"
    private val home = "ui/home/index.html"
    private val root get() = File(folder.root, "axiko")
    private val metadata get() = File(folder.root, "state/screen-suspicions.json")
    private var factory = "<main>Заводские настройки</main>"
    private val local = "<main>Мои настройки</main>"

    private fun store(snapshot: (String) -> Unit = {}) =
        ScreenSuspicionStore(root, metadata, { factory.toByteArray() }, snapshot)

    private fun write(text: String, relative: String = path): File = File(root, relative).apply {
        parentFile!!.mkdirs()
        writeText(text)
    }

    private fun hash(content: String): String = MessageDigest.getInstance("SHA-1")
        .digest(content.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun notice(store: ScreenSuspicionStore, previous: String? = factory, preserved: Boolean = true) =
        store.observe(
            listOf(FactoryObservation(path, previous?.let(::hash), hash(factory), preserved)),
            hasLegacyManifest = previous != null,
        )

    @Test
    fun cleanInstallAndOrdinaryLocalEditsDoNotTriggerUntilFactoryChanges() {
        val store = store()
        write(factory)
        notice(store, previous = null, preserved = false)
        assertEquals(0, store.state().getJSONArray("entries").length())
        write(local)
        notice(store)
        assertEquals(0, store.state().getJSONArray("entries").length())

        val previous = factory
        factory = "<main>Новые заводские настройки</main>"
        notice(store, previous)
        val finding = store.state().getJSONArray("entries").getJSONObject(0)
        assertEquals(path, finding.getString("path"))
        assertEquals(local, File(root, path).readText())
        assertTrue(store.compare(path).getString("diff").contains("+" + factory))
    }

    @Test
    fun discoveryPersistsAndDismissalIsSpecificToFactoryRevision() {
        val store = store()
        val file = write(local)
        notice(store) // First adoption: the legacy APK may already have passed over this edit.
        val state = store.state()
        val revision = state.getJSONArray("entries").getJSONObject(0).getString("revision")
        val generation = state.getString("generation")
        assertTrue(generation.isNotEmpty())
        assertEquals(state.toString(), store().state().toString())
        file.writeText(local + "\n<!-- another local edit -->")
        assertEquals(generation, store().state().getString("generation"))
        assertThrows(IllegalArgumentException::class.java) { store.dismiss(path, revision) }
        store.dismiss(path, store.compare(path).getString("revision"))
        notice(store())
        assertEquals(0, store().state().getJSONArray("entries").length())

        val previous = factory
        factory = "<main>Встроили новый раздел</main>"
        notice(store(), previous)
        assertEquals(1, store().state().getJSONArray("entries").length())
        assertNotEquals(generation, store().state().getString("generation"))
    }

    @Test
    fun restoringOnlyTheScreenStartsWithSnapshotAndClearsFinding() {
        val file = write(local)
        val other = write("Моя главная", home)
        val data = File(folder.root, "data/example.json").apply { parentFile!!.mkdir(); writeText("данные") }
        val snapshots = mutableListOf<String>()
        val store = store { snapshots.add(file.readText()) }
        notice(store)
        val comparison = store.compare(path)
        val result = store.restore(path, comparison.getString("revision"))
        assertEquals(listOf(local), snapshots)
        assertEquals(factory, file.readText())
        assertEquals("Моя главная", other.readText())
        assertEquals("данные", data.readText())
        assertEquals(0, result.getJSONArray("entries").length())
        assertFalse(root.walkTopDown().any { it.name.startsWith(".axiko-screen-") })
    }

    @Test
    fun changeWhileSnapshotRunsRejectsStaleReplacement() {
        val file = write(local)
        val latest = "<main>Новые локальные настройки</main>"
        val store = store { file.writeText(latest) }
        notice(store)
        val revision = store.compare(path).getString("revision")
        assertThrows(IllegalArgumentException::class.java) { store.restore(path, revision) }
        assertEquals(latest, file.readText())
        assertEquals(1, store.state().getJSONArray("entries").length())
        assertFalse(root.walkTopDown().any { it.name.startsWith(".axiko-screen-") })
    }

    @Test
    fun mergingUsesTheComparedFileAndResolvesOnlyTheSeenFactoryVersion() {
        val file = write(local)
        val snapshots = mutableListOf<String>()
        val store = store { snapshots.add(file.readText()) }
        notice(store)
        val revision = store.compare(path).getString("revision")
        val input = store.mergeInput(path, revision)
        assertEquals(local, input.local)
        assertEquals(factory, input.factory)
        assertTrue(input.diff.contains("-" + local))
        val merged = "<main>Мои настройки<button>Обновления</button></main>"
        assertEquals(0, store.applyMerged(path, revision, merged).getJSONArray("entries").length())
        assertEquals(listOf(local), snapshots)
        assertEquals(merged, file.readText())
        notice(store())
        assertEquals(0, store().state().getJSONArray("entries").length())
    }

    @Test
    fun malformedScreenDoesNotHideOtherFindings() {
        write(local)
        val broken = write("Моя главная", home)
        val store = store()
        store.observe(
            listOf(
                FactoryObservation(path, hash(factory), hash(factory), true),
                FactoryObservation(home, hash(factory), hash(factory), true),
            ),
            hasLegacyManifest = true,
        )
        assertEquals(2, store.state().getJSONArray("entries").length())
        assertTrue(broken.delete())
        assertTrue(broken.mkdir())
        val entries = store.state().getJSONArray("entries")
        assertEquals(1, entries.length())
        assertEquals(path, entries.getJSONObject(0).getString("path"))
        assertThrows(IllegalArgumentException::class.java) { store.compare(home) }
    }

    @Test
    fun ordinaryFactoryRestorationClearsFindingAndComparisonRejectsOutsidePaths() {
        val file = write(local)
        val store = store()
        notice(store)
        assertThrows(IllegalArgumentException::class.java) { store.compare("../private.txt") }
        file.writeText(factory)
        assertEquals(0, store.state().getJSONArray("entries").length())
    }
}
