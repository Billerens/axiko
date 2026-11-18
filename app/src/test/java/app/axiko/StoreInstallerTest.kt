package app.axiko

import app.axiko.store.StoreFormat
import app.axiko.store.StoreInstaller
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StoreInstallerTest {
    @get:Rule val temp = TemporaryFolder()
    private val workspace get() = File(temp.root, "axiko").apply { mkdirs() }
    private val storeState get() = File(temp.root, "state/store")
    private fun installer(snapshot: (String) -> String = { "snapshot-test" }, checkpoint: (String) -> Unit = {}) = StoreInstaller(workspace, storeState, snapshot, checkpoint)
    private data class Package(val entry: JSONObject, val manifest: ByteArray, val zip: File)

    private fun pkg(version: String = "1.0.0", main: String = "exports.value = 1;\n", extra: Map<String, ByteArray> = emptyMap(), remove: Set<String> = emptySet(), mutateManifest: (JSONObject) -> Unit = {}, zipExtra: Map<String, ByteArray> = emptyMap(), symlink: String? = null): Package {
        val files = linkedMapOf(
            "README.md" to "Readable test package\n".toByteArray(), "LICENSE" to "MIT\n".toByteArray(),
            "code/module.json" to "{\"id\":\"example.counter\",\"title\":\"Counter\",\"entry\":\"ui/index.html\"}".toByteArray(),
            "code/main.js" to main.toByteArray(), "code/ui/index.html" to "<!doctype html>\n<title>Counter</title>\n<p>Test package</p>\n".toByteArray(),
        ).apply { putAll(extra); remove.forEach(::remove) }
        val manifest = JSONObject().put("schemaVersion", 1).put("id", "example.counter").put("kind", "module").put("version", version).put("publisher", "example").put("license", "MIT").put("sourceFormat", "readable")
            .put("entry", "code/ui/index.html").put("runtime", JSONObject().put("axikoApi", 1)).put("dependencies", JSONArray()).put("optionalDependencies", JSONArray()).put("capabilities", JSONArray().put("storage.own"))
            .put("files", JSONArray(files.map { (path, bytes) -> JSONObject().put("path", path).put("sha256", StoreFormat.sha256(bytes)).put("size", bytes.size) }))
        mutateManifest(manifest)
        val manifestBytes = manifest.toString().toByteArray()
        val archived = linkedMapOf("package.json" to manifestBytes).apply { putAll(files); putAll(zipExtra) }
        val zip = temp.newFile("${version}-${System.nanoTime()}.zip")
        ZipArchiveOutputStream(zip).use { output -> archived.forEach { (name, content) ->
            val entry = ZipArchiveEntry(name); if (name == symlink) entry.unixMode = 0xa1ff
            output.putArchiveEntry(entry); output.write(content); output.closeArchiveEntry()
        } }
        val entry = JSONObject().put("id", "example.counter").put("kind", "module").put("title", "Counter").put("summary", "A test counter").put("version", version).put("publisher", "example").put("license", "MIT")
            .put("manifestUrl", "https://example.org/$version/package.json").put("compatibility", JSONObject().put("minAxikoApi", 1))
            .put("archive", JSONObject().put("url", "https://example.org/$version/package.zip").put("sha256", StoreFormat.sha256(zip)).put("size", zip.length()).put("unpackedSize", archived.values.sumOf { it.size }).put("fileCount", archived.size))
        return Package(entry, manifestBytes, zip)
    }
    private fun prepare(store: StoreInstaller, pkg: Package) = store.prepare("source-one", pkg.entry, pkg.manifest, pkg.zip)
    private fun apply(store: StoreInstaller, plan: JSONObject) = store.apply(plan.getString("id"), plan.getString("digest"))
    private fun text(path: String) = File(workspace, "modules/example.counter/$path").readText()
    private fun write(path: String, text: String) = File(workspace, "modules/example.counter/$path").apply { parentFile.mkdirs(); writeText(text) }
    private fun reject(action: () -> Unit) { assertThrows(RuntimeException::class.java, action) }

    @Test fun realInstallKeepsAllDataAndDoesNotExecuteCode() {
        val data = File(temp.root, "data/games.db").apply { parentFile.mkdirs(); writeText("owner-save-bytes") }
        val store = installer(); val p = pkg(main = "throw new Error('must never execute installer code');\n")
        val plan = prepare(store, p); assertFalse(File(workspace, "modules/example.counter").exists())
        assertEquals("ready", plan.getString("status")); assertEquals("trustedLegacy", plan.getString("runtimeProfile"))
        assertTrue(apply(store, plan).getBoolean("dataPreserved"))
        assertEquals("owner-save-bytes", data.readText()); assertTrue(text("main.js").contains("must never"))
        assertEquals("applied", store.plan(plan.getString("id")).getString("status"))
        assertFalse(store.installed().getJSONObject(0).getBoolean("localModified"))
    }

    @Test fun updatePreservesUnknownFilesAndUnchangedPublisherFilesWithLocalChanges() {
        val store = installer(); apply(store, prepare(store, pkg()))
        write("main.js", "exports.value = 'local';\n"); write("notes.txt", "my local addition")
        val next = pkg("1.1.0", extra = mapOf("code/new.js" to "exports.newValue = true;\n".toByteArray()))
        val plan = prepare(store, next); assertEquals(0, plan.getJSONArray("conflicts").length()); apply(store, plan)
        assertEquals("exports.value = 'local';\n", text("main.js")); assertEquals("my local addition", text("notes.txt")); assertTrue(text("new.js").contains("true"))
        val installed = store.installed().getJSONObject(0); assertTrue(installed.getBoolean("localModified")); assertTrue(installed.getBoolean("rollbackAvailable")); assertEquals("1.0.0", installed.getString("previousVersion"))
    }

    @Test fun conflictingPublisherUpdateCannotSilentlyReplaceLocalEditAndMergedPlanIsReviewable() {
        val store = installer(); apply(store, prepare(store, pkg())); write("main.js", "exports.local = 7;\n")
        val plan = prepare(store, pkg("1.1.0", "exports.upstream = 2;\n")); assertEquals("conflicts", plan.getString("status"))
        reject { apply(store, plan) }; assertEquals("exports.local = 7;\n", text("main.js"))
        val id = plan.getString("id"); val conflict = plan.getJSONArray("conflicts").getJSONObject(0)
        assertEquals("exports.value = 1;\n", store.read(id, "code/main.js", side = "base").getString("content"))
        assertEquals("exports.upstream = 2;\n", store.read(id, "code/main.js", side = "incoming").getString("content"))
        val merged = store.resolve(id, conflict.getString("path"), conflict.getString("localSha"), "exports.local = 7;\nexports.upstream = 2;\n")
        assertNotEquals(plan.getString("digest"), merged.getString("digest")); reject { apply(store, plan) }; apply(store, merged)
        assertEquals("exports.local = 7;\nexports.upstream = 2;\n", text("main.js"))
    }

    @Test fun explicitKeepLocalPreservesIntentionalDeletionAndLaterRollbackAlsoProtectsEdits() {
        val store = installer(); apply(store, prepare(store, pkg())); File(workspace, "modules/example.counter/main.js").delete()
        val p = prepare(store, pkg("1.1.0", "exports.newValue = 9;\n")); val c = p.getJSONArray("conflicts").getJSONObject(0)
        val kept = store.resolve(p.getString("id"), c.getString("path"), "missing", keepLocal = true); apply(store, kept)
        assertFalse(File(workspace, "modules/example.counter/main.js").exists())
        write("notes.txt", "persist through code rollback")
        val rollback = store.prepareRollback(store.installed().getJSONObject(0).getString("key"));
        if (rollback.getString("status") == "conflicts") {
            var current = rollback
            for (i in 0 until current.getJSONArray("conflicts").length()) { val item = current.getJSONArray("conflicts").getJSONObject(i); current = store.resolve(current.getString("id"), item.getString("path"), item.getString("localSha"), keepLocal = true) }
            apply(store, current)
        } else apply(store, rollback)
        assertFalse(File(workspace, "modules/example.counter/main.js").exists()); assertEquals("persist through code rollback", text("notes.txt"))
    }

    @Test fun changedLiveFileOrCandidateInvalidatesPreparedOperation() {
        val store = installer(); apply(store, prepare(store, pkg())); val update = prepare(store, pkg("1.1.0", "exports.value = 2;\n"))
        write("main.js", "new local edit"); reject { apply(store, update) }; assertEquals("new local edit", text("main.js"))
        val retry = prepare(store, pkg("1.1.1")); File(storeState, "plans/${retry.getString("id")}/candidate/main.js").writeText("tampered staging")
        reject { store.read(retry.getString("id"), "code/main.js") }; reject { apply(store, retry) }
    }

    @Test fun modifiedReleaseBaseCannotBecomeAnUnverifiedFutureBaseline() {
        val store = installer(); val pack = pkg(); val p = prepare(store, pack)
        File(storeState, "releases/${p.getString("artifactSha256")}/payload/main.js").writeText("tampered source")
        reject { apply(store, p) }; reject { prepare(store, pack) }; assertFalse(File(workspace, "modules/example.counter").exists())
    }

    @Test fun discardOnlyRemovesStagingAndUnreferencedDownloads() {
        val store = installer(); val fresh = prepare(store, pkg()); val released = File(storeState, "releases/${fresh.getString("artifactSha256")}")
        store.discardPlan(fresh.getString("id")); assertFalse(released.exists()); assertEquals(0, store.plans().length())
        val installed = prepare(store, pkg()); apply(store, installed); val current = File(storeState, "releases/${installed.getString("artifactSha256")}")
        store.discardPlan(installed.getString("id")); assertTrue(current.exists()); assertEquals("exports.value = 1;\n", text("main.js")); assertEquals(1, store.installed().length())
    }

    @Test fun oversizedLocalAdditionIsReportedWithoutHidingTheInstalledCatalog() {
        val store = installer(); apply(store, prepare(store, pkg())); write("notes.txt", "x".repeat(StoreFormat.MAX_FILE + 1))
        val record = store.installed().getJSONObject(0)
        assertTrue(record.getBoolean("localModified")); assertTrue(record.has("localError")); assertFalse(record.getBoolean("available")); assertEquals("invalid", record.getString("liveDigest"))
        reject { prepare(store, pkg("1.1.0")) }; assertTrue(File(workspace, "modules/example.counter/notes.txt").exists())
    }

    @Test fun backendEntryDoesNotOpenJavascriptAsAnApplicationScreen() {
        val store = installer()
        val withUi = pkg(mutateManifest = { it.put("entry", "code/main.js") })
        val plan = prepare(store, withUi); apply(store, plan)
        assertEquals("modules/example.counter/ui/index.html", store.installed().getJSONObject(0).getString("entryPath"))
        val backend = pkg("1.1.0", extra = mapOf("code/module.json" to "{\"id\":\"example.counter\",\"title\":\"Background counter\"}".toByteArray()), remove = setOf("code/ui/index.html"), mutateManifest = { it.put("entry", "code/main.js") })
        apply(store, prepare(store, backend)); assertEquals("", store.installed().getJSONObject(0).getString("entryPath"))
    }

    @Test fun sourceOwnershipUnmanagedFoldersAndSameVersionReplacementAreBlocked() {
        write("main.js", "unmanaged user code"); val store = installer(); reject { prepare(store, pkg()) }; assertEquals("unmanaged user code", text("main.js"))
        File(workspace, "modules/example.counter").deleteRecursively()
        apply(store, prepare(store, pkg())); val p = pkg("1.1.0")
        reject { store.prepare("other-source", p.entry, p.manifest, p.zip) }
        reject { prepare(store, pkg("1.0.0", "replacement same version")) }
        reject { prepare(store, pkg("0.9.0")) }
    }

    private fun adopt(store: StoreInstaller, pkg: Package) = store.prepare("source-one", pkg.entry, pkg.manifest, pkg.zip, adopt = true)

    @Test fun adoptingAFolderEqualToTheReleaseRegistersItWithoutChangingAFile() {
        val p = pkg(); val store = installer()
        write("module.json", "{\"id\":\"example.counter\",\"title\":\"Counter\",\"entry\":\"ui/index.html\"}"); write("main.js", "exports.value = 1;\n")
        write("ui/index.html", "<!doctype html>\n<title>Counter</title>\n<p>Test package</p>\n"); write("notes.txt", "the owner's own file")
        val plan = adopt(store, p)
        assertTrue(plan.getBoolean("adoption")); assertEquals("ready", plan.getString("status")); assertEquals(3, plan.getInt("matchingFiles"))
        assertEquals(0, plan.getJSONArray("conflicts").length())
        // Only the owner's extra file is a change of the plan: it stays and counts as a local edit.
        assertEquals(1, plan.getJSONArray("changes").length()); assertEquals("keep-local", plan.getJSONArray("changes").getJSONObject(0).getString("kind"))
        apply(store, plan)
        val record = store.installed().getJSONObject(0)
        assertEquals("1.0.0", record.getString("version")); assertTrue(record.getBoolean("adopted")); assertTrue(record.getBoolean("localModified"))
        assertEquals(listOf("notes.txt"), (0 until record.getJSONArray("modifiedFiles").length()).map { record.getJSONArray("modifiedFiles").getString(it) })
        assertEquals("the owner's own file", text("notes.txt"))
        // From here on it is an installed package like any other: the next release updates it.
        apply(store, prepare(store, pkg("1.1.0", "exports.value = 2;\n")))
        assertEquals("exports.value = 2;\n", text("main.js")); assertEquals("the owner's own file", text("notes.txt"))
    }

    @Test fun adoptingAFolderThatDiffersLeavesEveryDifferingFileToTheOwner() {
        val store = installer()
        write("module.json", "{\"id\":\"example.counter\",\"title\":\"Counter\",\"entry\":\"ui/index.html\"}"); write("main.js", "exports.value = 'older or edited';\n")
        write("ui/index.html", "<!doctype html>\n<title>Mine</title>\n")
        val plan = adopt(store, pkg())
        assertEquals("conflicts", plan.getString("status")); assertEquals(1, plan.getInt("matchingFiles"))
        val conflicts = plan.getJSONArray("conflicts"); assertEquals(setOf("main.js", "ui/index.html"), (0 until conflicts.length()).map { conflicts.getJSONObject(it).getString("path") }.toSet())
        reject { apply(store, plan) }; assertEquals("exports.value = 'older or edited';\n", text("main.js")); assertEquals(0, store.installed().length())
        val id = plan.getString("id")
        fun conflict(path: String) = (0 until conflicts.length()).map { conflicts.getJSONObject(it) }.first { it.getString("path") == path }
        reject { store.resolve(id, "main.js", "0".repeat(64), takeIncoming = true) }
        store.resolve(id, "main.js", conflict("main.js").getString("localSha"), takeIncoming = true)
        val ready = store.resolve(id, "ui/index.html", conflict("ui/index.html").getString("localSha"), keepLocal = true)
        assertEquals("ready", ready.getString("status")); apply(store, ready)
        assertEquals("exports.value = 1;\n", text("main.js")); assertEquals("<!doctype html>\n<title>Mine</title>\n", text("ui/index.html"))
        val record = store.installed().getJSONObject(0)
        assertEquals(listOf("ui/index.html"), (0 until record.getJSONArray("modifiedFiles").length()).map { record.getJSONArray("modifiedFiles").getString(it) })
    }

    @Test fun adoptionInterruptedAtAnyCheckpointLeavesTheOwnersFolderAsItWas() {
        for (point in listOf("prepared", "previous-moved", "swapped", "registry-written")) {
            val scenario = File(temp.root, "adopt-$point").apply { mkdirs() }; val root = File(scenario, "axiko"); val state = File(scenario, "state")
            val own = File(root, "modules/example.counter/main.js").apply { parentFile.mkdirs(); writeText("the owner's code") }
            File(root, "modules/example.counter/module.json").writeText("{\"id\":\"example.counter\",\"title\":\"Counter\",\"entry\":\"ui/index.html\"}")
            val crashing = StoreInstaller(root, state, checkpoint = { if (it == point) throw AssertionError("process death") })
            val p = pkg(); var plan = crashing.prepare("source-one", p.entry, p.manifest, p.zip, adopt = true)
            for (i in 0 until plan.getJSONArray("conflicts").length()) { val item = plan.getJSONArray("conflicts").getJSONObject(i); plan = crashing.resolve(plan.getString("id"), item.getString("path"), item.getString("localSha"), takeIncoming = true) }
            val ready = plan
            assertThrows(AssertionError::class.java) { crashing.apply(ready.getString("id"), ready.getString("digest")) }
            val recovered = StoreInstaller(root, state)
            assertEquals(0, recovered.installed().length()); assertEquals("the owner's code", own.readText())
            assertFalse(File(root, "modules/example.counter/ui/index.html").exists()); assertFalse(File(state, "install-transaction.json").exists())
        }
    }

    @Test fun snapshotRacePreservesNewerLocalContent() {
        var change = false
        val store = installer(snapshot = { if (change) write("main.js", "changed during snapshot"); "snapshot" })
        apply(store, prepare(store, pkg())); val p = prepare(store, pkg("1.1.0")); change = true
        reject { apply(store, p) }; assertEquals("changed during snapshot", text("main.js"))
    }

    @Test fun everyJournalCheckpointRecoversConsistentlyWithoutTouchingData() {
        for (point in listOf("prepared", "previous-moved", "swapped", "registry-written", "committed")) {
            val scenario = File(temp.root, point).apply { mkdirs() }; val root = File(scenario, "axiko"); val state = File(scenario, "state")
            val data = File(scenario, "data.json").apply { writeText("untouched") }
            val initial = StoreInstaller(root, state); val v1 = pkg(); apply(initial, prepare(initial, v1))
            val crashing = StoreInstaller(root, state, checkpoint = { if (it == point) throw AssertionError("process death") })
            val p = prepare(crashing, pkg("1.1.0", "exports.value = 2;\n"))
            assertThrows(AssertionError::class.java) { apply(crashing, p) }
            val recovered = StoreInstaller(root, state)
            assertEquals(if (point == "committed") "1.1.0" else "1.0.0", recovered.installed().getJSONObject(0).getString("version"))
            assertEquals(if (point == "committed") "exports.value = 2;\n" else "exports.value = 1;\n", File(root, "modules/example.counter/main.js").readText())
            assertEquals("untouched", data.readText()); assertFalse(File(state, "install-transaction.json").exists())
        }
    }

    @Test fun archiveIntegrityInventoryTraversalLinksAndMappedCollisionsAreChecked() {
        val store = installer()
        val wrong = pkg(); wrong.entry.getJSONObject("archive").put("sha256", "0".repeat(64)); reject { prepare(store, wrong) }
        reject { prepare(store, pkg(zipExtra = mapOf("../escape" to "bad".toByteArray()))) }
        reject { prepare(store, pkg(zipExtra = mapOf("code/extra.js" to "bad".toByteArray()))) }
        reject { prepare(store, pkg(symlink = "code/main.js")) }
        reject { prepare(store, pkg(extra = mapOf("assets/same.txt" to "a".toByteArray(), "code/assets/same.txt" to "b".toByteArray()))) }
        reject { prepare(store, pkg(mutateManifest = { it.getJSONArray("files").getJSONObject(0).put("sha256", "0".repeat(64)) })) }
        val wrongManifest = pkg(); val manifest = JSONObject(String(wrongManifest.manifest)).put("license", "Other")
        reject { store.prepare("source-one", wrongManifest.entry, manifest.toString().toByteArray(), wrongManifest.zip) }
        assertFalse(File(temp.root, "escape").exists()); assertEquals(0, store.installed().length())
    }

    @Test fun zipStreamingLimitDoesNotTrustDeclaredSmallSize() {
        val big = ByteArray(StoreFormat.MAX_FILE + 1) { 65 }
        val p = pkg(extra = mapOf("assets/large.txt" to big), mutateManifest = { m -> val files=m.getJSONArray("files"); for(i in 0 until files.length()) if(files.getJSONObject(i).getString("path")=="assets/large.txt")files.getJSONObject(i).put("size",1) })
        reject { prepare(installer(), p) }
    }

    @Test fun executableBinaryPackedSourcesAndRemoteScriptsAreRefused() {
        for (p in listOf(pkg(extra = mapOf("code/library.min.js" to "a();b();".toByteArray())), pkg(main = "eval(atob('ZXhwb3J0cy5leGVjKCk='));"), pkg(extra = mapOf("assets/native.so" to byteArrayOf(1,2,3))), pkg(extra = mapOf("code/ui/remote.html" to "<script src=\"https://example.org/payload.js\"></script>".toByteArray())))) reject { prepare(installer(), p) }
    }

    @Test fun largeReadableLiteralsDoNotOverflowTheScannerOrHideFollowingCode() {
        val literal = "text; \\\"quoted\\\" ".repeat(20000)
        val source = "const template = \"$literal\";\n" +
            "/* ${"readable comment ".repeat(20000)} */\n" +
            "const markup = `${"<p>Readable content</p>".repeat(15000)}`;\n" +
            "exports.value = context.getPackageName();\n"
        val store = installer()
        val plan = prepare(store, pkg(main = source))
        assertEquals("ready", plan.getString("status"))
        assertTrue(plan.getJSONArray("findings").toString().contains("privileged-runtime"))
        reject { prepare(store, pkg(main = source + "exports.value = 1;".repeat(200))) }
        reject { prepare(store, pkg(main = source + "\nimport('https://example.org/remote.js');\n")) }
        reject { prepare(store, pkg(main = source + "\neval(atob('YQ=='));\n")) }
    }
}
