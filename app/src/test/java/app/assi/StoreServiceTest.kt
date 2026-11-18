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

    @Test fun theListingSpeaksTheInterfaceLanguageAndATagStaysWhatIsCountedAndFilteredBy() {
        fun locale(title: String?, summary: String?, vararg tags: String) = JSONObject().apply {
            if (title != null) put("title", title); if (summary != null) put("summary", summary); if (tags.isNotEmpty()) put("tags", JSONArray(tags.toList()))
        }
        val details = JSONObject().put("schemaVersion", 1).put("id", "demo.clock").put("version", "1.0.0").put("description", "Shows the **time**.")
            .put("screenshots", JSONArray().put(JSONObject().put("path", "clock/store/1.png").put("sha256", "1".repeat(64)).put("size", 10).put("type", "image/png").put("width", 8).put("height", 8).put("caption", "Night")))
            .put("changes", JSONArray().put(JSONObject().put("version", "1.0.0").put("notes", "First release.")).put(JSONObject().put("version", "0.9.0").put("notes", "A preview.")))
            .put("locales", JSONObject().put("ru", JSONObject().put("description", "Показывает **время**.").put("changes", JSONObject().put("1.0.0", "Первый выпуск.")).put("captions", JSONArray().put("Ночь"))))
            .toString().toByteArray()
        val clock = StoreServiceFixtures.bundle("demo.clock").also {
            it.entry.put("title", "Clock").put("summary", "A clock for the home screen.").put("tags", JSONArray().put("time").put("clock"))
                .put("locales", JSONObject().put("ru", locale("Часы", "Часы для главного экрана.", "время", "часы")).put("de", locale("Uhr", null)))
                .put("details", JSONObject().put("path", "catalog/details/demo.clock.json").put("sha256", StoreFormat.sha256(details)).put("size", details.size))
        }
        val notes = StoreServiceFixtures.bundle("demo.notes").also { it.entry.put("title", "Notes").put("summary", "Plain notes.").put("tags", JSONArray().put("time").put("text")) }
        val plain = StoreServiceFixtures.bundle("demo.plain").also { it.entry.put("title", "Atlas").put("summary", "An atlas.") }
        transport.publish(clock, notes, plain); transport.put("https://catalog.example/catalog/details/demo.clock.json", details)
        val service = service(); val source = add(service)
        fun items(page: JSONObject) = (0 until page.getJSONArray("items").length()).associate { page.getJSONArray("items").getJSONObject(it).let { item -> item.getString("id") to item } }
        fun tagRows(page: JSONObject) = (0 until page.getJSONArray("tags").length()).associate { page.getJSONArray("tags").getJSONObject(it).let { row -> row.getString("tag") to (row.getString("label") to row.getInt("count")) } }

        // English, or no language at all: the base text, and no locales left over for the page to pick from.
        for (language in listOf("", "en", "fr")) {
            service.language = { language }
            val page = service.catalogPage(); val shown = items(page).getValue("demo.clock")
            assertEquals(listOf("Clock", "A clock for the home screen.", "time,clock"), listOf(shown.getString("title"), shown.getString("summary"), shown.getJSONArray("tags").join(",").replace("\"", "")))
            assertFalse(shown.has("locales"))
            assertEquals(mapOf("time" to ("time" to 2), "clock" to ("clock" to 1), "text" to ("text" to 1)), tagRows(page))
        }
        // Russian: the locale's words, tags by position; what the locale does not give stays English.
        service.language = { "ru" }
        val russian = service.catalogPage(); val shown = items(russian).getValue("demo.clock")
        assertEquals(listOf("Часы", "Часы для главного экрана.", "время,часы"), listOf(shown.getString("title"), shown.getString("summary"), shown.getJSONArray("tags").join(",").replace("\"", "")))
        assertEquals("Notes", items(russian).getValue("demo.notes").getString("title"))
        // Tags are counted by what they are, and shown by the words of the language, English where it has none.
        assertEquals(mapOf("time" to ("время" to 2), "clock" to ("часы" to 1), "text" to ("text" to 1)), tagRows(russian))
        // Filtered by the tag itself, and by the word shown for it; found by either language.
        assertEquals(setOf("demo.clock", "demo.notes"), items(service.catalogPage(JSONObject().put("tag", "time"))).keys)
        assertEquals(setOf("demo.clock"), items(service.catalogPage(JSONObject().put("tag", "часы"))).keys)
        assertEquals(setOf("demo.clock"), items(service.catalogPage(JSONObject().put("query", "ЧАСЫ"))).keys)
        assertEquals(setOf("demo.clock"), items(service.catalogPage(JSONObject().put("query", "clock for the"))).keys)
        // The order is the order of the titles as shown: Atlas, Notes, Часы here, and Atlas, Clock, Notes in English.
        fun order(page: JSONObject) = (0 until page.getJSONArray("items").length()).map { page.getJSONArray("items").getJSONObject(it).getString("id") }
        assertEquals(listOf("demo.plain", "demo.notes", "demo.clock"), order(russian))
        service.language = { "en" }; assertEquals(listOf("demo.plain", "demo.clock", "demo.notes"), order(service.catalogPage())); service.language = { "ru" }
        // Another language of the package; a region falls back to the language, a language to a variant of it.
        service.language = { "de" }; assertEquals("Uhr", items(service.catalogPage()).getValue("demo.clock").getString("title"))
        assertEquals("A clock for the home screen.", items(service.catalogPage()).getValue("demo.clock").getString("summary"))
        service.language = { "ru-RU" }; assertEquals("Часы", items(service.catalogPage()).getValue("demo.clock").getString("title"))

        // The details: description, notes of the versions and captions, where the language has them.
        service.language = { "ru" }
        val ru = service.details(source, "demo.clock")
        assertEquals("Показывает **время**.", ru.getString("description")); assertFalse(ru.has("locales"))
        assertEquals(listOf("Первый выпуск.", "A preview."), (0 until 2).map { ru.getJSONArray("changes").getJSONObject(it).getString("notes") })
        assertEquals("Ночь", ru.getJSONArray("screenshots").getJSONObject(0).getString("caption"))
        service.language = { "en" }
        val en = service.details(source, "demo.clock")
        assertEquals(listOf("Shows the **time**.", "First release.", "Night"), listOf(en.getString("description"), en.getJSONArray("changes").getJSONObject(0).getString("notes"), en.getJSONArray("screenshots").getJSONObject(0).getString("caption")))

        // An installed package is called by the title it has in the interface's language, from the card it was installed from.
        apply(service, service.prepare(source, "demo.clock"))
        service.language = { "ru" }
        val record = service.installed().getJSONObject(0)
        assertEquals(listOf("Часы", "Часы"), listOf(record.getString("title"), record.getJSONObject("catalogEntry").getString("title")))
        service.language = { "en" }
        assertEquals("Clock", service.installed().getJSONObject(0).getString("title"))
        // What the installer keeps, and what a plan is decided by, is the card as the source published it.
        assertEquals("Clock", service.installer.installed().getJSONObject(0).getString("title"))
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

    private val counterMain = "exports.value = function () {\n  return 1;\n};\n"
    private val nightMain = "exports.value = function () {\n  return 'night';\n};\n"

    /** A mod of the fixture counter: a changed main.js and a new stylesheet, with what main.js was before. */
    private fun mod(id: String = "demo.night", target: String = "demo.counter", version: String = "1.0.0", madeOn: String = "1.0.0",
                    sample: Map<String, String> = mapOf("main.js" to nightMain, "ui/night.css" to "body { background: #000; }\n"),
                    before: Map<String, String> = mapOf("main.js" to counterMain), removed: List<String> = emptyList()): StoreServiceFixtures.Package {
        val files = linkedMapOf("README.md" to "# Night\n".toByteArray(), "LICENSE" to "MIT\n".toByteArray(), "mod/brief.md" to "Make the counter dark at night.\n".toByteArray())
        sample.forEach { (path, text) -> files["reference/$path"] = text.toByteArray() }
        before.forEach { (path, text) -> files["base/$path"] = text.toByteArray() }
        val reference = JSONObject().put("version", madeOn).put("base", JSONObject(before.mapValues { StoreFormat.sha256(it.value.toByteArray()) }))
            .put("added", JSONArray(sample.keys - before.keys)).put("removed", JSONArray(removed))
        val manifest = JSONObject().put("schemaVersion", 1).put("id", id).put("kind", "mod").put("version", version).put("publisher", "demo").put("license", "MIT")
            .put("sourceFormat", "readable").put("runtime", JSONObject().put("assiApi", 1)).put("capabilities", JSONArray().put("storage.own"))
            .put("dependencies", JSONArray()).put("optionalDependencies", JSONArray()).put("extends", target).put("reference", reference)
            .put("files", JSONArray().apply { files.forEach { (path, bytes) -> put(JSONObject().put("path", path).put("size", bytes.size).put("sha256", StoreFormat.sha256(bytes))) } })
            .toString().toByteArray()
        val all = linkedMapOf("package.json" to manifest).apply { putAll(files) }
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip -> all.forEach { (path, bytes) -> zip.putNextEntry(java.util.zip.ZipEntry(path).apply { time = 0 }); zip.write(bytes); zip.closeEntry() } }
        val archive = out.toByteArray()
        val entry = StoreServiceFixtures.entry(id, version).put("kind", "mod").put("extends", target).put("title", "Night counter")
        entry.getJSONObject("archive").put("sha256", StoreFormat.sha256(archive)).put("size", archive.size).put("unpackedSize", all.values.sumOf { it.size }).put("fileCount", all.size)
        return StoreServiceFixtures.Package(entry, manifest, archive)
    }
    private fun card(service: StoreService, id: String) = (0 until service.catalog().length()).map { service.catalog().getJSONObject(it) }.first { it.getString("id") == id }
    private fun strings(list: JSONArray) = (0 until list.length()).map { list.getString(it) }

    @Test fun modIsLaidOverItsTargetAsItsAuthorMadeItAndTakenOffToWhatWasBefore() {
        transport.publish(StoreServiceFixtures.bundle(), mod())
        val service = service(); val source = add(service)
        val folder = File(workspace, "modules/demo.counter")
        // A mod stands on a package: without it there is nothing to change.
        assertEquals("targetMissing", card(service, "demo.night").getString("status")); assertFalse(card(service, "demo.night").getBoolean("compatible"))
        assertThrows(IllegalArgumentException::class.java) { service.prepare(source, "demo.night") }
        apply(service, service.prepare(source, "demo.counter"))
        assertEquals("available", card(service, "demo.night").getString("status")); assertEquals("Counter", card(service, "demo.night").getString("targetTitle"))
        service.invalidate()
        val mods = service.catalogPage(JSONObject().put("extends", "demo.counter"))
        assertEquals(1, mods.getInt("total")); assertEquals("demo.night", mods.getJSONArray("items").getJSONObject(0).getString("id"))

        val plan = service.prepare(source, "demo.night")
        assertEquals("ready", plan.getString("status")); assertEquals("modules/demo.counter", plan.getString("target"))
        assertEquals("direct", plan.getJSONObject("mod").getString("mode")); assertEquals("demo.counter", plan.getJSONObject("mod").getString("extends"))
        val changes = plan.getJSONArray("changes")
        assertEquals(listOf("main.js update", "ui/night.css add"), (0 until changes.length()).map { changes.getJSONObject(it) }.map { it.getString("path") + " " + it.getString("kind") })
        assertTrue(service.read(plan.getString("id"), "mod/brief.md").getString("content").contains("dark at night"))
        assertEquals(counterMain, File(folder, "main.js").readText())
        apply(service, plan)
        assertEquals(nightMain, File(folder, "main.js").readText()); assertTrue(File(folder, "ui/night.css").isFile)

        // The package is still itself, at its own version, with a layer that says whose its differences are.
        val record = service.installed().getJSONObject(0)
        assertEquals("1.0.0", record.getString("version")); assertFalse(record.getBoolean("localModified"))
        assertEquals(listOf("main.js", "ui/night.css"), strings(record.getJSONArray("modFiles")))
        val layer = record.getJSONArray("mods").getJSONObject(0)
        assertEquals(listOf("demo.night", "1.0.0", "direct", "1.0.0"), listOf(layer.getString("id"), layer.getString("version"), layer.getString("mode"), layer.getString("targetVersion")))
        assertEquals(listOf("ui/night.css"), strings(layer.getJSONArray("added")))
        assertEquals("installed", card(service, "demo.night").getString("status")); assertEquals("installed", card(service, "demo.counter").getString("status"))
        assertThrows(IllegalArgumentException::class.java) { service.prepare(source, "demo.night") }
        File(folder, "notes.txt").writeText("the owner's own file")
        assertEquals(listOf("notes.txt"), strings(service.installed().getJSONObject(0).getJSONArray("modifiedFiles")))

        // A release of the package goes under the mod: what the mod changed stays, the layer stays with the package.
        transport.publish(StoreServiceFixtures.bundle(version = "1.0.1", extra = mapOf("code/extra.js" to "exports.extra = true;\n".toByteArray())), mod()); service.sources.refresh(source)
        apply(service, service.prepare(source, "demo.counter"))
        assertEquals(nightMain, File(folder, "main.js").readText()); assertTrue(File(folder, "extra.js").isFile)
        assertEquals(1, service.installed().getJSONObject(0).getJSONArray("mods").length()); assertEquals("installed", card(service, "demo.night").getString("status"))

        // Taken off, every file is what it was before the mod; the owner's own file is not touched.
        val removal = service.removeMod(record.getString("key"), layer.getString("key"))
        assertEquals("ready", removal.getString("status")); assertTrue(removal.getJSONObject("mod").getBoolean("removal"))
        apply(service, removal)
        assertEquals(counterMain, File(folder, "main.js").readText()); assertFalse(File(folder, "ui/night.css").exists())
        assertEquals("the owner's own file", File(folder, "notes.txt").readText()); assertTrue(File(folder, "extra.js").isFile)
        assertEquals(0, service.installed().getJSONObject(0).optJSONArray("mods")?.length() ?: 0)
        assertEquals("1.0.1", service.installed().getJSONObject(0).getString("version"))
        // What was laid over 1.0.0 as it was does not fit 1.0.1 by itself: that is a carry-over, not a copy.
        assertEquals("available", card(service, "demo.night").getString("status"))
        assertEquals("carrying", service.prepare(source, "demo.night").getString("status"))
    }

    @Test fun modThatDoesNotFitIsNotLaidOverAndSaysWhy() {
        transport.publish(StoreServiceFixtures.bundle(), mod(madeOn = "0.9.0"), mod("demo.dusk"), mod("demo.clean", sample = mapOf("ui/index.html" to "<p>new</p>\n"), before = emptyMap()),
            mod("demo.drop", sample = emptyMap(), before = emptyMap(), removed = listOf("main.js")))
        val service = service(); val source = add(service)
        apply(service, service.prepare(source, "demo.counter"))
        val main = File(workspace, "modules/demo.counter/main.js")
        // Such a mod gets a plan that waits to be carried over: nothing is laid over, and it cannot be applied as it is.
        fun why(id: String): String {
            val plan = service.prepare(source, id)
            assertEquals("carrying", plan.getString("status")); assertEquals("ported", plan.getJSONObject("mod").getString("mode"))
            assertEquals(0, plan.getJSONArray("changes").length())
            assertThrows(IllegalArgumentException::class.java) { apply(service, plan) }
            return strings(plan.getJSONObject("mod").getJSONObject("carry").getJSONArray("reasons")).joinToString("|").also { service.discard(plan.getString("id")) }
        }
        assertTrue(why("demo.night").contains("образец сделан на версии 0.9.0, а у вас стоит 1.0.0"))
        // A file the mod adds is already there with other contents; a file it removes without knowing what it was.
        assertTrue(why("demo.clean").contains("ui/index.html")); assertTrue(why("demo.drop").contains("main.js"))
        // The owner's edit in a file the author worked on: the sample is no longer a copy to lay over.
        main.writeText("exports.value = function () {\n  return 'mine';\n};\n")
        assertTrue(why("demo.dusk").contains("main.js"))
        assertTrue(main.readText().contains("mine")); assertEquals(0, service.plans().length())
        assertEquals(0, service.installed().getJSONObject(0).optJSONArray("mods")?.length() ?: 0)
    }

    private val steppedMain = counterMain + "exports.step = 2;\n"
    private val ownerLine = "// tuned by the owner\n"

    /** The counter at 1.0.1 with the owner's line in main.js, and the fixture mod made on 1.0.0, prepared: a plan to carry over. */
    private fun carrying(service: StoreService, source: String): JSONObject {
        apply(service, service.prepare(source, "demo.counter"))
        File(workspace, "modules/demo.counter/main.js").appendText(ownerLine)
        return service.prepare(source, "demo.night")
    }

    @Test fun modIsCarriedOverToAnotherVersionAndOverTheOwnersEditsThroughAnOrdinaryPlan() {
        transport.publish(StoreServiceFixtures.bundle(version = "1.0.1", main = steppedMain), mod())
        val service = service(); val source = add(service)
        val plan = carrying(service, source); val id = plan.getString("id")
        val folder = File(workspace, "modules/demo.counter")
        assertEquals("carrying", plan.getString("status"))
        assertEquals(2, plan.getJSONObject("mod").getJSONObject("carry").getJSONArray("reasons").length())

        // What the executor is given: the task, each side of each file, and whose the differences are.
        val task = service.carryTask(id)
        assertEquals("1.0.0", task.getString("referenceVersion")); assertEquals("1.0.1", task.getJSONObject("target").getString("version"))
        val rows = (0 until task.getJSONArray("files").length()).map { task.getJSONArray("files").getJSONObject(it) }.associateBy { it.getString("path") }
        assertEquals(setOf("main.js", "ui/night.css"), rows.keys)
        val main = rows.getValue("main.js")
        assertTrue(main.getBoolean("reference") && main.getBoolean("base") && main.getBoolean("current") && main.getBoolean("ownerEdited"))
        assertFalse(main.getBoolean("sameAsAuthorStart")); assertEquals("untouched", main.getString("candidate"))
        assertFalse(rows.getValue("ui/night.css").getBoolean("current"))
        assertTrue(service.carryRead(id, "brief", "").getString("content").contains("dark at night"))
        assertEquals(counterMain, service.carryRead(id, "base", "main.js").getString("content"))
        assertEquals(nightMain, service.carryRead(id, "reference", "main.js").getString("content"))
        assertEquals(steppedMain, service.carryRead(id, "release", "main.js").getString("content"))
        assertEquals(steppedMain + ownerLine, service.carryRead(id, "current", "main.js").getString("content"))
        assertTrue(service.carryTask(id, tree = true).getJSONArray("files").length() >= 3)

        // It writes only into the candidate, and only what a release would be allowed to hold.
        service.carryCopy(id, "reference", "ui/night.css", "ui/night.css")
        val edited = service.carryEdit(id, "main.js", "return 1;", "return 'night';")
        assertEquals("carrying", edited.getString("status")); assertEquals(2, edited.getJSONArray("changes").length())
        assertThrows(IllegalArgumentException::class.java) { service.carryEdit(id, "main.js", "no such text", "x") }
        assertThrows(IllegalArgumentException::class.java) { service.carryWrite(id, "ui/remote.html", "<script src=\"https://cdn.example/x.js\"></script>\n") }
        assertThrows(IllegalArgumentException::class.java) { service.carryWrite(id, "../outside.js", "x") }
        assertEquals(0, service.bans.list().length())
        service.carryWrite(id, "notes.md", "scratch\n"); service.carryDelete(id, "notes.md")
        assertEquals(steppedMain + ownerLine, File(folder, "main.js").readText()); assertFalse(File(folder, "ui/night.css").exists())
        assertThrows(IllegalArgumentException::class.java) { apply(service, service.plan(id)) }

        // The report names every changed file once, and what it calls verbatim is compared with the sample.
        fun report(verbatim: List<String>, adapted: List<String>) = JSONObject().put("summary", "The counter is dark at night; the step of 1.0.1 and the owner's line are kept.")
            .put("verbatim", JSONArray(verbatim)).put("adapted", JSONArray(adapted.map { JSONObject().put("path", it).put("note", "Applied to the file of 1.0.1 with the owner's line kept.") }))
            .put("skipped", JSONArray()).put("questions", JSONArray().put("Asked nothing."))
        assertThrows(IllegalArgumentException::class.java) { service.carryDone(id, report(listOf("ui/night.css"), emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { service.carryDone(id, report(listOf("ui/night.css", "main.js"), emptyList())) }
        val done = service.carryDone(id, report(listOf("ui/night.css"), listOf("main.js")))
        assertEquals("ready", done.getString("status")); assertEquals("ported", done.getJSONObject("mod").getString("mode"))
        assertEquals(listOf("main.js"), strings(done.getJSONObject("mod").getJSONArray("ownerEdits")))
        assertTrue(done.getJSONObject("mod").getJSONObject("report").getString("summary").contains("dark at night"))
        val changes = (0 until done.getJSONArray("changes").length()).map { done.getJSONArray("changes").getJSONObject(it) }
        assertEquals(listOf("main.js update true", "ui/night.css add false"), changes.map { "${it.getString("path")} ${it.getString("kind")} ${it.getBoolean("localModified")}" })
        assertThrows(IllegalArgumentException::class.java) { service.carryWrite(id, "main.js", "late") }

        // From here it is an ordinary plan: read, applied by the owner, recorded as a layer that knows how it was laid.
        assertTrue(service.read(id, "code/main.js").getString("content").contains("'night'"))
        apply(service, done)
        val carried = File(folder, "main.js").readText()
        assertTrue(carried.contains("return 'night';") && carried.contains("exports.step = 2;") && carried.endsWith(ownerLine))
        val record = service.installed().getJSONObject(0); val layer = record.getJSONArray("mods").getJSONObject(0)
        assertEquals(listOf("ported", "1.0.1", "1.0.0"), listOf(layer.getString("mode"), layer.getString("targetVersion"), layer.getString("version")))
        assertEquals(listOf("main.js"), strings(layer.getJSONArray("ownerEdits"))); assertTrue(layer.getJSONObject("report").has("summary"))
        assertEquals(listOf("main.js", "ui/night.css"), strings(record.getJSONArray("modFiles"))); assertFalse(record.getBoolean("localModified"))

        // Taken off, the file is what it was before the mod: the release's file with the owner's line.
        apply(service, service.removeMod(record.getString("key"), layer.getString("key")))
        assertEquals(steppedMain + ownerLine, File(folder, "main.js").readText()); assertFalse(File(folder, "ui/night.css").exists())
    }

    @Test fun aViolationBroughtInByACarryOverRejectsThePlanWithoutBanningTheAuthorsRelease() {
        transport.publish(StoreServiceFixtures.bundle(version = "1.0.1", main = steppedMain), mod())
        val service = service(); val source = add(service)
        val id = carrying(service, source).getString("id")
        service.carryEdit(id, "main.js", "return 1;", "return 'night';")
        val done = service.carryDone(id, JSONObject().put("summary", "Carried over.").put("adapted", JSONArray().put(JSONObject().put("path", "main.js").put("note", "Edited in place."))))
        val rejected = service.submitReview(id, done.getString("digest"), pass(done, "reject"))
        assertEquals("reject", rejected.getString("status")); assertFalse(rejected.getBoolean("banned"))
        assertEquals(0, service.bans.list().length())
        assertThrows(IllegalArgumentException::class.java) { apply(service, service.plan(id)) }
        // The mod itself is still offered: it can be prepared and carried over again.
        service.discard(id)
        assertEquals("available", card(service, "demo.night").getString("status"))
        assertEquals("carrying", service.prepare(source, "demo.night").getString("status"))
    }

    @Test fun modTakenOffAfterItsPackageWasUpdatedLeavesThePackagesNewFileAndCanBeCarriedOverAnew() {
        transport.publish(StoreServiceFixtures.bundle(), mod())
        val service = service(); val source = add(service)
        apply(service, service.prepare(source, "demo.counter")); apply(service, service.prepare(source, "demo.night"))
        val folder = File(workspace, "modules/demo.counter")
        // The package moves on in the very file the mod changed; the owner keeps the mod's file.
        transport.publish(StoreServiceFixtures.bundle(version = "1.0.1", main = steppedMain), mod()); service.sources.refresh(source)
        val update = service.prepare(source, "demo.counter")
        assertEquals("conflicts", update.getString("status"))
        apply(service, service.resolve(update.getString("id"), "main.js", update.getJSONArray("conflicts").getJSONObject(0).getString("localSha"), null, true))
        assertEquals(nightMain, File(folder, "main.js").readText())
        var record = service.installed().getJSONObject(0); var layer = record.getJSONArray("mods").getJSONObject(0)
        assertEquals("1.0.1", record.getString("version")); assertEquals(listOf("main.js", "ui/night.css"), strings(layer.getJSONArray("pristine")))

        // Carried over anew, by the owner's wish: the candidate starts from the package as 1.0.1 has it, without the mod.
        val again = service.againMod(record.getString("key"), layer.getString("key")); val id = again.getString("id")
        assertEquals("carrying", again.getString("status"))
        assertTrue(strings(again.getJSONObject("mod").getJSONObject("carry").getJSONArray("reasons")).first().contains("заново"))
        assertEquals(steppedMain, service.carryRead(id, "candidate", "main.js").getString("content"))
        assertEquals(nightMain, service.carryRead(id, "current", "main.js").getString("content"))
        service.carryEdit(id, "main.js", "return 1;", "return 'night';"); service.carryCopy(id, "reference", "ui/night.css", "ui/night.css")
        val done = service.carryDone(id, JSONObject().put("summary", "The night counter, on 1.0.1.").put("adapted", JSONArray().put(JSONObject().put("path", "main.js").put("note", "Kept the step of 1.0.1."))))
        apply(service, done)
        assertTrue(File(folder, "main.js").readText().let { it.contains("'night'") && it.contains("exports.step = 2;") })
        record = service.installed().getJSONObject(0); layer = record.getJSONArray("mods").getJSONObject(0)
        assertEquals(1, record.getJSONArray("mods").length()); assertEquals(listOf("ported", "1.0.1"), listOf(layer.getString("mode"), layer.getString("targetVersion")))

        // Taken off, the file is the one 1.0.1 has: the release that went under the mod is not undone.
        apply(service, service.removeMod(record.getString("key"), layer.getString("key")))
        assertEquals(steppedMain, File(folder, "main.js").readText()); assertFalse(File(folder, "ui/night.css").exists())
        assertFalse(service.installed().getJSONObject(0).getBoolean("localModified"))
    }

    @Test fun theOwnersOwnChangesAreWrittenOutAsTheDraftOfAMod() {
        transport.publish(StoreServiceFixtures.bundle(extra = mapOf("code/old.js" to "exports.old = true;\n".toByteArray())), mod())
        val service = service(); val source = add(service)
        apply(service, service.prepare(source, "demo.counter"))
        val folder = File(workspace, "modules/demo.counter"); val key = service.installed().getJSONObject(0).getString("key")
        val draft = File(this.folder.root, "drafts/demo.mine")
        fun meta(id: String = "demo.mine") = JSONObject().put("id", id).put("title", "My counter").put("summary", "Counts with my own line.")
        // Nothing of the owner's yet: nothing to share.
        assertThrows(IllegalArgumentException::class.java) { service.draftMod(key, draft, meta()) }
        File(folder, "main.js").appendText(ownerLine); File(folder, "ui/extra.css").writeText("p { color: teal; }\n"); assertTrue(File(folder, "old.js").delete())
        // What a mod stands on the package with is the mod's, not the owner's change.
        apply(service, service.prepare(source, "demo.night").let { plan ->
            assertEquals("carrying", plan.getString("status")); service.carryCopy(plan.getString("id"), "reference", "ui/night.css", "ui/night.css")
            service.carryDone(plan.getString("id"), JSONObject().put("summary", "Only the stylesheet.").put("verbatim", JSONArray().put("ui/night.css")))
        })
        assertThrows(IllegalArgumentException::class.java) { service.draftMod(key, draft, meta("demo.counter")) }
        assertThrows(IllegalArgumentException::class.java) { service.draftMod(key, draft, meta("Bad ID")) }
        assertThrows(IllegalArgumentException::class.java) { service.draftMod(key, File(workspace, "modules/demo.mine"), meta()) }

        val made = service.draftMod(key, draft, meta())
        assertEquals(listOf("main.js"), strings(made.getJSONArray("changed"))); assertEquals(listOf("ui/extra.css"), strings(made.getJSONArray("added")))
        assertEquals(listOf("old.js"), strings(made.getJSONArray("removed")))
        val manifest = JSONObject(File(draft, "store.json").readText())
        assertEquals(listOf("demo.mine", "mod", "1.0.0", "demo.counter", "1.0.0"), listOf(manifest.getString("id"), manifest.getString("kind"), manifest.getString("version"),
            manifest.getString("extends"), manifest.getJSONObject("reference").getString("version")))
        assertEquals(listOf("old.js"), strings(manifest.getJSONArray("removed")))
        assertEquals(counterMain + ownerLine, File(draft, "reference/main.js").readText()); assertEquals(counterMain, File(draft, "base/main.js").readText())
        assertTrue(File(draft, "reference/ui/extra.css").isFile); assertFalse(File(draft, "base/ui/extra.css").exists()); assertTrue(File(draft, "base/old.js").isFile)
        assertFalse(File(draft, "reference/ui/night.css").exists())
        assertTrue(File(draft, "mod/brief.md").readText().contains("`main.js` — изменён")); assertTrue(File(draft, "README.md").readText().startsWith("# My counter"))
        // A file no release may hold is not shared either, and no half-made draft is left behind.
        File(folder, "ui/remote.html").writeText("<script src=\"https://cdn.example/x.js\"></script>\n")
        assertThrows(IllegalArgumentException::class.java) { service.draftMod(key, draft, meta()) }
        assertFalse(draft.exists())
    }

    @Test fun aNewVersionOfAModReplacesTheOneThatStands() {
        val dusk = "exports.value = function () {\n  return 'dusk';\n};\n"
        transport.publish(StoreServiceFixtures.bundle(), mod())
        val service = service(); val source = add(service)
        apply(service, service.prepare(source, "demo.counter")); apply(service, service.prepare(source, "demo.night"))
        val folder = File(workspace, "modules/demo.counter")
        transport.publish(StoreServiceFixtures.bundle(), mod(version = "1.1.0", sample = mapOf("main.js" to dusk, "ui/dusk.css" to "body { background: #223; }\n")))
        service.sources.refresh(source)
        assertEquals("update", card(service, "demo.night").getString("status"))

        // Laid over the package as it would be without the old version: the author's start is the release, not the old mod.
        val plan = service.prepare(source, "demo.night")
        assertEquals("ready", plan.getString("status")); assertEquals("direct", plan.getJSONObject("mod").getString("mode"))
        assertEquals("1.0.0", plan.getJSONObject("mod").getString("replaces"))
        val changes = (0 until plan.getJSONArray("changes").length()).map { plan.getJSONArray("changes").getJSONObject(it) }
        assertEquals(listOf("main.js update", "ui/dusk.css add", "ui/night.css delete"), changes.map { it.getString("path") + " " + it.getString("kind") })
        apply(service, plan)
        assertEquals(dusk, File(folder, "main.js").readText()); assertTrue(File(folder, "ui/dusk.css").isFile); assertFalse(File(folder, "ui/night.css").exists())
        val record = service.installed().getJSONObject(0)
        assertEquals(1, record.getJSONArray("mods").length()); assertEquals("1.1.0", record.getJSONArray("mods").getJSONObject(0).getString("version"))
        assertEquals("installed", card(service, "demo.night").getString("status")); assertFalse(record.getBoolean("localModified"))

        // Taken off, the package is what it was before the first version of the mod.
        apply(service, service.removeMod(record.getString("key"), record.getJSONArray("mods").getJSONObject(0).getString("key")))
        assertEquals(counterMain, File(folder, "main.js").readText()); assertFalse(File(folder, "ui/dusk.css").exists()); assertFalse(File(folder, "ui/night.css").exists())
    }

    @Test fun fileChangedAfterAModIsTheOwnersToDecideWhenTheModIsTakenOff() {
        transport.publish(StoreServiceFixtures.bundle(), mod())
        val service = service(); val source = add(service)
        apply(service, service.prepare(source, "demo.counter")); apply(service, service.prepare(source, "demo.night"))
        val folder = File(workspace, "modules/demo.counter")
        File(folder, "main.js").writeText("exports.value = function () {\n  return 'night, tuned by me';\n};\n")
        val record = service.installed().getJSONObject(0)
        assertEquals(listOf("main.js"), strings(record.getJSONArray("modifiedFiles"))); assertEquals(listOf("ui/night.css"), strings(record.getJSONArray("modFiles")))
        val removal = service.removeMod(record.getString("key"), record.getJSONArray("mods").getJSONObject(0).getString("key"))
        assertEquals("conflicts", removal.getString("status"))
        val conflict = removal.getJSONArray("conflicts").getJSONObject(0); assertEquals("main.js", conflict.getString("path"))
        assertThrows(IllegalArgumentException::class.java) { apply(service, removal) }
        // What the removal would bring back can be read before deciding, and then taken.
        assertEquals(counterMain, service.read(removal.getString("id"), "code/main.js", 0, 16000, "incoming").getString("content"))
        val back = service.resolve(removal.getString("id"), "main.js", conflict.getString("localSha"), null, false, true)
        apply(service, back)
        assertEquals(counterMain, File(folder, "main.js").readText()); assertFalse(File(folder, "ui/night.css").exists())
        assertFalse(service.installed().getJSONObject(0).getBoolean("localModified"))
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
