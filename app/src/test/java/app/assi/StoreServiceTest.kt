package app.assi

import app.assi.store.StoreFormat
import app.assi.store.StoreService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StoreServiceTest {
    @get:Rule val folder = TemporaryFolder()
    private val transport = StoreServiceFixtures.Transport()
    private val workspace get() = File(folder.root, "assi")
    private val state get() = File(folder.root, "store")
    private var snapshots = 0
    private fun service() = StoreService(workspace, state, transport) { snapshots++; "snapshot-$snapshots" }
    private fun add(service: StoreService) = service.sources.add(StoreServiceFixtures.INDEX).getString("id")
    private fun apply(service: StoreService, plan: JSONObject) = service.apply(plan.getString("id"), plan.getString("digest"), true)
    /** The status is no longer the reviewer's to give: a finding decides it. [status] only picks which finding the fixture reports. */
    private fun pass(plan: JSONObject, status: String = "pass") = JSONObject().put("summary", "Read the exact prepared candidate.")
        .put("findings", JSONArray().apply {
            if (status != "pass") put(JSONObject().put("rule", if (status == "reject") "data.exfiltration" else "network.undeclared")
                .put("severity", if (status == "reject") "violation" else "suspicious").put("certainty", "observed")
                .put("evidence", JSONObject().put("path", "code/main.js").put("line", 1))
                .put("explanation", "Fixture finding.").put("resolution", "Fixture resolution."))
        })
        .put("coverage", JSONObject().put("complete", true).put("filesReviewed", JSONArray().put("code/main.js")).put("unreviewedFiles", JSONArray()))
        .put("planDigest", plan.getString("digest")).put("artifactSha256", plan.getString("artifactSha256"))

    @Test fun addCatalogPrepareReadAndInstallUseVerifiedBytesWithoutTouchingData() {
        val pkg = StoreServiceFixtures.bundle(); transport.publish(pkg)
        val data = File(folder.root, "data/demo.counter/save.json").apply { parentFile.mkdirs(); writeText("owner-progress") }
        val service = service(); val source = add(service)
        assertEquals("available", service.catalog().getJSONObject(0).getString("status"))
        val plan = service.prepare(source, "demo.counter")
        assertEquals("ready", plan.getString("status")); assertFalse(File(workspace, "modules/demo.counter").exists())
        assertNotEquals(plan.getString("digest"), plan.getString("installerDigest"))
        val read = service.read(plan.getString("id"), "code/main.js", 0, 15)
        assertEquals(plan.getString("digest"), read.getString("planDigest")); assertEquals(15, read.getString("content").length)
        assertThrows(IllegalArgumentException::class.java) { service.apply(plan.getString("id"), plan.getString("digest"), false) }
        assertEquals(0, snapshots)
        // A plan waits in the list until it is applied; after that it is history, opened by its ID only.
        assertEquals(1, service.plans().length())
        val result = apply(service, plan)
        assertEquals(0, service.plans().length()); assertEquals("applied", service.plan(plan.getString("id")).getString("status"))
        assertTrue(result.getBoolean("dataPreserved")); assertFalse(result.getBoolean("packageCodeStarted")); assertEquals(1, snapshots)
        assertEquals("owner-progress", data.readText())
        assertTrue(File(workspace, "modules/demo.counter/ui/index.html").isFile)
        assertEquals("installed", service.catalog().getJSONObject(0).getString("status"))
        assertEquals(1, service.installed().length())
    }

    @Test fun updateConflictMustBeResolvedAndKeepsLocalEditsAndDataThroughRollback() {
        val v1 = StoreServiceFixtures.bundle(); transport.publish(v1)
        val service = service(); val source = add(service); apply(service, service.prepare(source, "demo.counter"))
        val local = File(workspace, "modules/demo.counter/main.js"); local.writeText("exports.value = function () {\n  return 'owner edit';\n};\n")
        val extra = File(workspace, "modules/demo.counter/custom.txt").apply { writeText("extra owner file") }
        val data = File(folder.root, "data/game-save.db").apply { parentFile.mkdirs(); writeText("never replace") }
        val v2 = StoreServiceFixtures.bundle(version = "1.1.0", main = "exports.value = function () {\n  return 2;\n};\n")
        transport.publish(v2); service.sources.refresh(source)
        assertEquals("update", service.catalog().getJSONObject(0).getString("status"))
        val plan = service.prepare(source, "demo.counter")
        assertEquals("conflicts", plan.getString("status"))
        assertThrows(IllegalArgumentException::class.java) { apply(service, plan) }
        val conflict = plan.getJSONArray("conflicts").getJSONObject(0)
        val resolved = service.resolve(plan.getString("id"), conflict.getString("path"), conflict.getString("localSha"), null, true)
        assertThrows(IllegalArgumentException::class.java) { apply(service, plan) }
        apply(service, resolved)
        assertTrue(local.readText().contains("owner edit")); assertEquals("extra owner file", extra.readText()); assertEquals("never replace", data.readText())
        val installed = service.installed().getJSONObject(0)
        assertEquals("1.1.0", installed.getString("version")); assertTrue(installed.getBoolean("localModified"))
        service.sources.remove(source)
        var rollback = service.prepareRollback(installed.getString("key"))
        val conflicts = rollback.getJSONArray("conflicts")
        for (i in 0 until conflicts.length()) {
            val row = conflicts.getJSONObject(i)
            rollback = service.resolve(rollback.getString("id"), row.getString("path"), row.getString("localSha"), null, true)
        }
        apply(service, rollback)
        assertEquals("1.0.0", service.installed().getJSONObject(0).getString("version"))
        assertTrue(local.readText().contains("owner edit")); assertEquals("never replace", data.readText())
        assertFalse(service.installed().getJSONObject(0).getBoolean("sourceAvailable"))
    }

    @Test fun moduleStandingWithoutTheStoreIsOfferedForTakingOverAndThenUpdatesLikeAnyOther() {
        val v1 = StoreServiceFixtures.bundle(); transport.publish(v1)
        // Copied in by hand before the store existed: the same code as the release but for one older file.
        val own = File(workspace, "modules/demo.counter").apply { mkdirs() }
        File(own, "module.json").writeText(JSONObject().put("id", "demo.counter").put("title", "Counter").put("entry", "ui/index.html").put("autostart", false).toString())
        File(own, "ui").mkdirs(); File(own, "ui/index.html").writeText("<!doctype html>\n<html><body><p>Fixture counter</p></body></html>\n")
        val main = File(own, "main.js").apply { writeText("exports.value = function () {\n  return 0;\n};\n") }
        val data = File(folder.root, "data/demo.counter/save.json").apply { parentFile.mkdirs(); writeText("owner-progress") }
        val service = service(); val source = add(service)
        assertEquals("unmanaged", service.catalog().getJSONObject(0).getString("status")); assertEquals(0, service.installed().length())
        // A plain install never touches it; taking it over is asked for in so many words.
        assertThrows(IllegalArgumentException::class.java) { service.prepare(source, "demo.counter") }
        val plan = service.prepare(source, "demo.counter", adopt = true)
        assertTrue(plan.getBoolean("adoption")); assertEquals("conflicts", plan.getString("status")); assertEquals(2, plan.getInt("matchingFiles"))
        val conflict = plan.getJSONArray("conflicts").getJSONObject(0); assertEquals("main.js", conflict.getString("path"))
        val ready = service.resolve(plan.getString("id"), "main.js", conflict.getString("localSha"), null, false, true)
        apply(service, ready)
        assertTrue(main.readText().contains("return 1")); assertEquals("owner-progress", data.readText()); assertEquals(1, snapshots)
        assertEquals("installed", service.catalog().getJSONObject(0).getString("status"))
        assertFalse(service.installed().getJSONObject(0).getBoolean("localModified"))
        transport.publish(StoreServiceFixtures.bundle(version = "1.0.1", main = "exports.value = function () {\n  return 2;\n};\n")); service.sources.refresh(source)
        assertEquals("update", service.catalog().getJSONObject(0).getString("status"))
        apply(service, service.prepare(source, "demo.counter")); assertTrue(main.readText().contains("return 2"))
    }

    @Test fun catalogIsServedInPortionsWithSearchTagsAndTheCountOfWhatWaits() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 8 8\"><path d=\"M1 1h6v6H1z\"/></svg>".toByteArray()
        val titles = listOf("demo.clock" to "Часы", "demo.notes" to "Заметки", "demo.atlas" to "Atlas", "demo.counter" to "Счётчик", "demo.zoo" to "Зоопарк")
        val packages = titles.map { (id, title) -> StoreServiceFixtures.bundle(id).also { it.entry.put("title", title).put("tags", JSONArray().put("demo").put(if (id == "demo.zoo" || id == "demo.atlas") "game" else "tool")) } }
        packages[3].entry.put("icon", JSONObject().put("path", "counter/store/icon.svg").put("sha256", StoreFormat.sha256(svg)).put("size", svg.size).put("type", "image/svg+xml")).put("updated", "2026-11-17")
        transport.publish(*packages.toTypedArray()); transport.put("https://catalog.example/counter/store/icon.svg", svg)
        val service = service(); val source = add(service)
        fun ids(page: JSONObject) = (0 until page.getJSONArray("items").length()).map { page.getJSONArray("items").getJSONObject(it).getString("id") }

        val first = service.catalogPage(JSONObject().put("limit", 2))
        assertEquals(5, first.getInt("total")); assertEquals(listOf("demo.atlas", "demo.notes"), ids(first))
        assertEquals(listOf("demo.zoo", "demo.counter", "demo.clock"), ids(service.catalogPage(JSONObject().put("offset", 2).put("limit", 10))))
        assertEquals(0, ids(service.catalogPage(JSONObject().put("offset", 99))).size)
        assertEquals(listOf("demo.counter"), ids(service.catalogPage(JSONObject().put("query", "  СЧЁТ "))))
        assertEquals(listOf("demo.atlas", "demo.zoo"), ids(service.catalogPage(JSONObject().put("tag", "game"))))
        assertEquals("demo.counter", ids(service.catalogPage(JSONObject().put("sort", "updated")))[0])
        assertEquals("demo", first.getJSONArray("tags").getJSONObject(0).getString("tag")); assertEquals(5, first.getJSONArray("tags").getJSONObject(0).getInt("count"))
        assertEquals(0, first.getJSONObject("counts").getInt("updates")); assertEquals(5, first.getJSONObject("counts").getInt("all"))
        assertEquals(1, first.getJSONArray("kinds").length())
        first.getJSONArray("kinds").getJSONObject(0).let { assertEquals(listOf("module", 5, "Модули", "Модуль"), listOf(it.getString("kind"), it.getInt("count"), it.getString("title"), it.getString("item"))) }

        // The picture goes to the page as data, taken by the reference of the checked card.
        assertTrue(service.media(source, "demo.counter", "icon").startsWith("data:image/svg+xml;base64,"))
        assertThrows(IllegalArgumentException::class.java) { service.media(source, "demo.clock", "icon") }
        assertThrows(IllegalArgumentException::class.java) { service.media(source, "demo.counter", "../index.json") }
        assertEquals("demo.clock", service.details(source, "demo.clock").getString("id"))

        // What waits for the owner: a module standing without the store and, after an install, a newer release.
        File(workspace, "modules/demo.notes").mkdirs()
        apply(service, service.prepare(source, "demo.clock"))
        val newer = StoreServiceFixtures.bundle("demo.clock", "1.1.0").also { it.entry.put("title", "Часы") }
        transport.publish(*(packages.drop(1) + newer).toTypedArray()); service.sources.refresh(source); service.invalidate()
        val waiting = service.catalogPage(JSONObject().put("only", "updates"))
        assertEquals(listOf("demo.notes", "demo.clock"), ids(waiting)); assertEquals(2, waiting.getJSONObject("counts").getInt("updates"))
        assertEquals(listOf("unmanaged", "update"), (0..1).map { waiting.getJSONArray("items").getJSONObject(it).getString("status") })
    }

    /** A package of a kind some module hosts: the descriptor and the version of the host's rules are the host's to name. */
    private fun hosted(id: String, kind: String, descriptor: String, engine: Pair<String, Int>?): StoreServiceFixtures.Package {
        val files = linkedMapOf(
            "README.md" to "# Theme\n".toByteArray(), "LICENSE" to "MIT\n".toByteArray(),
            "code/$descriptor" to JSONObject().put("id", id).put("title", "Sunset").put("entry", "ui/index.html").toString().toByteArray(),
            "code/ui/index.html" to "<!doctype html>\n<p>Sunset</p>\n".toByteArray(),
        )
        val runtime = JSONObject().put("assiApi", 1).apply { engine?.let { put(it.first, it.second) } }
        val manifest = JSONObject().put("schemaVersion", 1).put("id", id).put("kind", kind).put("version", "1.0.0").put("publisher", "demo").put("license", "MIT")
            .put("sourceFormat", "readable").put("entry", "code/ui/index.html").put("runtime", runtime).put("capabilities", JSONArray().put("storage.own"))
            .put("dependencies", JSONArray()).put("optionalDependencies", JSONArray())
            .put("files", JSONArray().apply { files.forEach { (path, bytes) -> put(JSONObject().put("path", path).put("size", bytes.size).put("sha256", StoreFormat.sha256(bytes))) } })
            .toString().toByteArray()
        val all = linkedMapOf("package.json" to manifest).apply { putAll(files) }
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip -> all.forEach { (path, bytes) -> zip.putNextEntry(java.util.zip.ZipEntry(path).apply { time = 0 }); zip.write(bytes); zip.closeEntry() } }
        val archive = out.toByteArray()
        val entry = StoreServiceFixtures.entry(id).put("kind", kind)
        entry.getJSONObject("compatibility").apply { engine?.let { put(it.first, it.second) } }
        entry.getJSONObject("archive").put("sha256", StoreFormat.sha256(archive)).put("size", archive.size).put("unpackedSize", all.values.sumOf { it.size }).put("fileCount", all.size)
        return StoreServiceFixtures.Package(entry, manifest, archive)
    }
    private fun host(id: String, vararg hosts: JSONObject) = File(workspace, "modules/$id").apply { mkdirs() }
        .let { File(it, "module.json").writeText(JSONObject().put("title", "Host").put("hosts", JSONArray(hosts.toList())).toString()) }

    @Test fun kindsOfPackagesAreWhatInstalledModulesHostAndLiveInsideTheirHost() {
        val theme = hosted("sunset", "theme", "theme.json", "themesEngine" to 2)
        val future = hosted("dawn", "theme", "theme.json", "themesEngine" to 3)
        val unknown = hosted("orbit", "planet", "planet.json", null)
        transport.publish(theme, future, unknown, StoreServiceFixtures.bundle())
        val service = service(); val source = add(service)
        fun status(id: String) = (0 until service.catalog().length()).map { service.catalog().getJSONObject(it) }.first { it.getString("id") == id }
        // Nobody hosts themes yet: the shell itself knows only modules.
        assertEquals("incompatible", status("sunset").getString("status")); assertTrue(status("sunset").getString("compatibilityError").contains("нет модуля-хозяина"))
        assertEquals("available", status("demo.counter").getString("status"))
        assertThrows(IllegalArgumentException::class.java) { service.prepare(source, "sunset") }

        host("themes", JSONObject().put("kind", "theme").put("descriptor", "theme.json").put("engine", "themesEngine").put("version", 2)
            .put("screen", true).put("reserved", JSONArray().put("engine")).put("title", "Темы").put("item", "Тема"))
        assertEquals("available", status("sunset").getString("status"))
        assertTrue(status("dawn").getString("compatibilityError").contains("версии 3"))
        assertEquals("incompatible", status("orbit").getString("status"))
        val kinds = service.catalogPage().getJSONArray("kinds")
        assertEquals(listOf("module|Модули|true", "planet|planet|false", "theme|Темы|true"),
            (0 until kinds.length()).map { kinds.getJSONObject(it) }.map { it.getString("kind") + "|" + it.getString("title") + "|" + it.getBoolean("hosted") })
        val plan = service.prepare(source, "sunset")
        assertEquals("modules/themes/sunset", plan.getString("target")); assertEquals("modules/themes/sunset/ui/index.html", plan.getString("entryPath"))
        apply(service, plan)
        assertTrue(File(workspace, "modules/themes/sunset/theme.json").isFile); assertFalse(File(workspace, "modules/sunset").exists())
        assertEquals("installed", status("sunset").getString("status"))
        // A package cannot take a folder of its host, nor the place of a module that hosts others.
        transport.publish(hosted("engine", "theme", "theme.json", "themesEngine" to 2), StoreServiceFixtures.bundle("themes")); service.sources.refresh(source)
        assertThrows(IllegalArgumentException::class.java) { service.prepare(source, "engine") }
        assertThrows(IllegalArgumentException::class.java) { service.prepare(source, "themes") }
    }

    @Test fun aDeclarationCannotClaimModulesAnotherPlaceOrAKindThatIsTaken() {
        host("alpha", JSONObject().put("kind", "skin").put("descriptor", "skin.json").put("title", "Скины"))
        host("beta", JSONObject().put("kind", "skin").put("descriptor", "other.json"),
            JSONObject().put("kind", "module").put("descriptor", "evil.json"),
            JSONObject().put("kind", "escape").put("descriptor", "../../state/store/installed.json"),
            JSONObject().put("kind", "Bad Kind").put("descriptor", "x.json"),
            JSONObject().put("kind", "versioned").put("descriptor", "v.json").put("engine", "assiApi").put("version", 1))
        val kinds = service().kinds.all()
        assertEquals(setOf("module", "skin"), kinds.keys)
        assertEquals("alpha", kinds.getValue("skin").host); assertEquals("modules/alpha/night", kinds.getValue("skin").target("night"))
        assertEquals("module.json", kinds.getValue("module").descriptor); assertEquals("modules/night", kinds.getValue("module").target("night"))
        // A package may not take the place of a module that hosts others, nor of the store; any other name is free.
        assertEquals(setOf("alpha", "store", "template"), kinds.getValue("module").reserved)
        // A host whose module.json lost its declaration still hosts what the application shipped it with.
        File(workspace, "modules/alpha/module.json").writeText("{\"title\":\"Edited\"}")
        val shipped = app.assi.store.StoreKinds.workspace(workspace) { listOf("alpha" to JSONObject().put("hosts", JSONArray().put(JSONObject().put("kind", "skin").put("descriptor", "skin.json")))) }
        assertEquals("alpha", shipped.find("skin")?.host)
        // A host that is gone hosts nothing: the kind goes to whoever else declares it, or to nobody.
        File(workspace, "modules/alpha").deleteRecursively()
        assertEquals("beta", shipped.find("skin")?.host)
        File(workspace, "modules/beta").deleteRecursively()
        assertNull(shipped.find("skin"))
    }

    @Test fun unchangedUpstreamFileKeepsAgentEditWithoutAConflict() {
        transport.publish(StoreServiceFixtures.bundle())
        val service = service(); val source = add(service); apply(service, service.prepare(source, "demo.counter"))
        val file = File(workspace, "modules/demo.counter/main.js").apply { writeText("exports.owner = true;\n") }
        transport.publish(StoreServiceFixtures.bundle(version = "1.0.1")); service.sources.refresh(source)
        val update = service.prepare(source, "demo.counter")
        assertEquals(0, update.getJSONArray("conflicts").length()); apply(service, update)
        assertEquals("exports.owner = true;\n", file.readText())
    }

    @Test fun requiredDependenciesAreSameSourceAndInvalidateOldPlanReviewsWhenChanged() {
        val helper = StoreServiceFixtures.bundle("demo.helper")
        val dependent = StoreServiceFixtures.bundle(dependency = "demo.helper" to ">=1.0.0")
        transport.publish(helper, dependent)
        val service = service(); val source = add(service)
        val waiting = service.prepare(source, "demo.counter")
        assertEquals("blocked", waiting.getString("status")); assertTrue(waiting.getJSONArray("blockers").length() > 0)
        assertThrows(IllegalArgumentException::class.java) { apply(service, waiting) }
        // Nothing was found in the code; the missing dependency alone keeps the report from being a pass.
        assertEquals("needsAttention", service.submitReview(waiting.getString("id"), waiting.getString("digest"), pass(waiting)).getString("status"))
        apply(service, service.prepare(source, "demo.helper"))
        val ready = service.plan(waiting.getString("id"))
        assertEquals("ready", ready.getString("status")); assertNotEquals(waiting.getString("digest"), ready.getString("digest"))
        assertEquals("pending", service.review(waiting.getString("id")).getString("status"))
        assertThrows(IllegalArgumentException::class.java) { apply(service, waiting) }
        val helperFile = File(workspace, "modules/demo.helper/main.js")
        helperFile.writeText("exports.changed = 1;\n")
        val firstEdit = service.plan(waiting.getString("id"))
        helperFile.writeText("exports.changed = 2;\n")
        val secondEdit = service.plan(waiting.getString("id"))
        assertNotEquals(firstEdit.getString("digest"), secondEdit.getString("digest"))
        apply(service, secondEdit)
    }

    @Test fun removedOrDisabledSourceCannotCommitAnOldDownloadedPlan() {
        transport.publish(StoreServiceFixtures.bundle())
        val service = service(); val source = add(service); val plan = service.prepare(source, "demo.counter")
        service.sources.enable(source, false)
        assertEquals("blocked", service.plan(plan.getString("id")).getString("status"))
        assertThrows(IllegalArgumentException::class.java) { apply(service, plan) }
        service.sources.remove(source); val readded = add(service)
        assertEquals(source, readded)
        assertEquals("blocked", service.plan(plan.getString("id")).getString("status"))
        assertThrows(IllegalArgumentException::class.java) { apply(service, plan) }
        assertFalse(File(workspace, "modules/demo.counter").exists())
    }

    @Test fun archiveSubstitutionAndCatalogChangesDuringDownloadNeverReachWorkspace() {
        val pkg = StoreServiceFixtures.bundle(); transport.publish(pkg)
        val service = service(); val source = add(service)
        val zipUrl = pkg.entry.getJSONObject("archive").getString("url")
        val altered = pkg.zip.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        transport.put(zipUrl, altered)
        assertThrows(IllegalArgumentException::class.java) { service.prepare(source, "demo.counter") }
        assertEquals(0, service.plans().length())
        transport.put(zipUrl, pkg.zip)
        transport.hook = { request -> if (request.url == zipUrl) {
            transport.hook = null
            transport.publish(StoreServiceFixtures.bundle(version = "1.1.0"))
            service.sources.refresh(source)
        } }
        assertThrows(IllegalArgumentException::class.java) { service.prepare(source, "demo.counter") }
        assertEquals(0, service.installed().length()); assertEquals(0, snapshots)
    }

    @Test fun reviewIsBoundToExactDigestAndRejectBlocksDirectApply() {
        transport.publish(StoreServiceFixtures.bundle())
        val service = service(); val source = add(service); val plan = service.prepare(source, "demo.counter")
        assertEquals("pending", service.review(plan.getString("id")).getString("status"))
        val bad = pass(plan).put("artifactSha256", "f".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { service.submitReview(plan.getString("id"), plan.getString("digest"), bad) }
        val rejected = service.submitReview(plan.getString("id"), plan.getString("digest"), pass(plan, "reject"))
        assertEquals("reject", rejected.getString("status"))
        assertEquals(plan.getString("artifactSha256"), rejected.getString("artifactSha256"))
        assertFalse(rejected.getBoolean("capabilitiesEnforced"))
        assertThrows(IllegalArgumentException::class.java) { apply(service, plan) }
        // A reject is final for this archive: a later "pass" cannot be filed over it.
        assertThrows(IllegalArgumentException::class.java) { service.submitReview(plan.getString("id"), plan.getString("digest"), pass(plan)) }
        assertThrows(IllegalArgumentException::class.java) { apply(service, plan) }
        assertEquals(0, service.installed().length())
    }

    @Test fun passReviewOfTheExactDigestLetsThePlanBeApplied() {
        transport.publish(StoreServiceFixtures.bundle())
        val service = service(); val source = add(service); val plan = service.prepare(source, "demo.counter")
        val saved = service.submitReview(plan.getString("id"), plan.getString("digest"), pass(plan).put("status", "reject"))
        assertEquals("pass", saved.getString("status")); assertEquals("reject", saved.getString("claimedStatus"))
        apply(service, plan)
        assertEquals("pass", service.installed().getJSONObject(0).getJSONObject("review").getString("verdict"))
    }

    @Test fun incompatiblePackageStaysDiscoverableButDoesNotDownloadItsArchive() {
        val pkg = StoreServiceFixtures.bundle(); pkg.entry.getJSONObject("compatibility").put("minAssiApi", 9)
        transport.publish(pkg)
        val service = service(); val source = add(service)
        val entry = service.catalog().getJSONObject(0)
        assertFalse(entry.getBoolean("compatible")); assertEquals("incompatible", entry.getString("status"))
        val requests = transport.requests.size
        assertThrows(IllegalArgumentException::class.java) { service.prepare(source, "demo.counter") }
        assertEquals(requests, transport.requests.size)
    }

    @Test fun appliedReviewRemainsHistoricalAfterRestartAndCannotAuthorizeANewPlan() {
        transport.publish(StoreServiceFixtures.bundle())
        val service = service(); val source = add(service); val plan = service.prepare(source, "demo.counter")
        val id = plan.getString("id"); val digest = plan.getString("digest")
        service.submitReview(id, digest, pass(plan))
        apply(service, plan)
        val restarted = service()
        val historical = restarted.review(id)
        assertEquals("pass", historical.getString("status"))
        assertEquals(digest, historical.getString("planDigest"))
        assertTrue(historical.getBoolean("completed")); assertTrue(historical.getBoolean("historicalOnly"))
        assertEquals(digest, restarted.plan(id).getString("appliedFromDigest"))
        assertThrows(IllegalArgumentException::class.java) { apply(restarted, restarted.plan(id)) }
        assertThrows(IllegalArgumentException::class.java) {
            restarted.submitReview(id, restarted.plan(id).getString("digest"), pass(restarted.plan(id)))
        }
        transport.publish(StoreServiceFixtures.bundle(version = "1.1.0")); restarted.sources.refresh(source)
        val update = restarted.prepare(source, "demo.counter")
        assertNotEquals(digest, update.getString("digest"))
        assertEquals("pending", restarted.review(update.getString("id")).getString("status"))
        restarted.sources.remove(source)
        assertEquals("pass", restarted.review(id).getString("status"))
        assertTrue(restarted.review(id).getBoolean("historicalOnly"))
    }
}
