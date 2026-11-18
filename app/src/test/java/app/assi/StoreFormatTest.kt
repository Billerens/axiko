package app.assi

import app.assi.store.StoreFormat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StoreFormatTest {
    private fun example(name: String): ByteArray = listOf(File("../docs/marketplace/examples/$name.json"), File("docs/marketplace/examples/$name.json")).first { it.isFile }.readBytes()
    private fun reject(action: () -> Unit) { assertThrows(RuntimeException::class.java, action) }

    @Test fun aCatalogThatSaysMoreThanThisVersionReadsStillOpensButAPackageMayHoldNothingUnknown() {
        fun head() = JSONObject(String(example("index")))
        fun page() = JSONObject(String(example("page")))
        fun details() = JSONObject(String(example("details")))
        // The head, a page reference, a page, a card and its parts, a listing: each may carry what a later format adds.
        StoreFormat.index(head().put("mirrors", JSONArray().put("https://example.org")).apply {
            getJSONObject("repository").put("languages", JSONArray().put("ru")); getJSONArray("pages").getJSONObject(0).put("from", "a")
        }.toString().toByteArray())
        val card = page().getJSONArray("packages").getJSONObject(0).put("rating", 4.5).put("badges", JSONArray().put("new"))
        card.getJSONObject("archive").put("mirror", "https://example.org/a.zip")
        card.optJSONObject("icon")?.put("dominant", "#102030"); card.optJSONObject("details")?.put("language", "en")
        StoreFormat.packageEntry(card)
        StoreFormat.page(page().put("generatedAt", "2026-11-18").apply { getJSONArray("packages").put(0, card) }.toString().toByteArray())
        StoreFormat.details(details().put("video", JSONObject().put("path", "v.webm")).apply {
            optJSONArray("screenshots")?.optJSONObject(0)?.put("device", "phone"); optJSONArray("changes")?.optJSONObject(0)?.put("breaking", false)
        }.toString().toByteArray())
        // What this version does read is checked as before, whatever stands beside it.
        reject { StoreFormat.packageEntry(JSONObject(card.toString()).put("version", "one")) }
        reject { StoreFormat.packageEntry(JSONObject(card.toString()).apply { remove("archive") }) }
        reject { StoreFormat.packageEntry(JSONObject(card.toString()).apply { getJSONObject("archive").put("sha256", "short") }) }
        reject { StoreFormat.index(head().put("extra", true).put("schemaVersion", 2).toString().toByteArray()) }
        // A package is another matter: its manifest names everything it is made of, and nothing unknown passes.
        reject { StoreFormat.manifest(JSONObject(String(example("package"))).put("postInstall", "run.js").toString().toByteArray()) }
        reject { StoreFormat.manifest(JSONObject(String(example("package"))).apply { getJSONArray("files").getJSONObject(0).put("mode", "755") }.toString().toByteArray()) }
    }

    @Test fun publishedExamplesAndSchemaVersions() {
        assertEquals(1, StoreFormat.index(example("index")).getInt("schemaVersion"))
        assertEquals("readable", StoreFormat.manifest(example("package")).getString("sourceFormat"))
        reject { StoreFormat.index(JSONObject(String(example("index"))).put("schemaVersion", 2).toString().toByteArray()) }
        // A field this version does not know is left alone: a newer catalog still opens in an older app.
        assertEquals(1, StoreFormat.index(JSONObject(String(example("index"))).put("extra", true).toString().toByteArray()).getInt("schemaVersion"))
        assertEquals("example.counter", StoreFormat.page(example("page")).getJSONArray("packages").getJSONObject(0).getString("id"))
        assertEquals(1, StoreFormat.details(example("details")).getJSONArray("screenshots").length())
    }

    @Test fun headPagesCardsAndDetailsAreCheckedFieldByField() {
        fun head() = JSONObject(String(example("index")))
        // The pages must account for every package, each once, and stay inside the folder of the index.
        reject { StoreFormat.index(head().put("packageCount", 2).toString().toByteArray()) }
        reject { StoreFormat.index(head().apply { getJSONArray("pages").put(JSONObject(getJSONArray("pages").getJSONObject(0).toString())) }.put("packageCount", 2).toString().toByteArray()) }
        for (path in listOf("../p.json", "https://evil.example/p.json", "catalog/p-0.txt", "/catalog/p-0.json")) reject { StoreFormat.index(head().apply { getJSONArray("pages").getJSONObject(0).put("path", path) }.toString().toByteArray()) }
        assertEquals(0, StoreFormat.index(head().put("packageCount", 0).put("pages", JSONArray()).toString().toByteArray()).getJSONArray("pages").length())

        fun card() = JSONObject(String(example("page"))).getJSONArray("packages").getJSONObject(0)
        StoreFormat.packageEntry(card())
        StoreFormat.packageEntry(card().put("screenshots", JSONArray()))
        reject { StoreFormat.packageEntry(card().put("tags", JSONArray().put("Big"))) }
        reject { StoreFormat.packageEntry(card().put("updated", "17.11.2026")) }
        reject { StoreFormat.packageEntry(card().apply { getJSONObject("icon").put("type", "text/html") }) }
        reject { StoreFormat.packageEntry(card().apply { getJSONObject("icon").put("size", StoreFormat.MAX_ICON + 1) }) }
        reject { StoreFormat.packageEntry(card().apply { getJSONObject("icon").put("path", "../../icon.svg") }) }
        reject { StoreFormat.packageEntry(card().apply { getJSONObject("details").put("size", StoreFormat.MAX_DETAILS + 1) }) }
        val twice = JSONObject(String(example("page"))).apply { getJSONArray("packages").put(card()) }
        reject { StoreFormat.page(twice.toString().toByteArray()) }

        fun details() = JSONObject(String(example("details")))
        // A field this version does not know is neither refused nor shown: it is simply not read.
        StoreFormat.details(details().put("html", "<b>").toString().toByteArray())
        reject { StoreFormat.details(details().apply { getJSONArray("screenshots").getJSONObject(0).put("type", "image/svg+xml") }.toString().toByteArray()) }
        reject { StoreFormat.details(details().apply { getJSONObject("links").put("homepage", "http://example.org/") }.toString().toByteArray()) }
        reject { StoreFormat.details(details().apply { getJSONArray("changes").put(JSONObject(getJSONArray("changes").getJSONObject(0).toString())) }.toString().toByteArray()) }
        reject { StoreFormat.details(details().put("description", "x".repeat(20_001)).toString().toByteArray()) }
    }

    @Test fun cardsAndDetailsMayGiveTheirTextsInOtherLanguagesAndEachIsChecked() {
        fun card() = JSONObject(String(example("page"))).getJSONArray("packages").getJSONObject(0)
        fun details() = JSONObject(String(example("details")))
        assertEquals(listOf("ru"), card().getJSONObject("locales").keys().asSequence().toList())
        assertEquals(listOf("ru"), StoreFormat.details(example("details")).getJSONObject("locales").keys().asSequence().toList())
        // A card: only title, summary and tags; the tags are the words for the card's own, one each, in the same order.
        StoreFormat.packageEntry(card().apply { put("locales", JSONObject().put("pt-BR", JSONObject().put("title", "Contador")).put("zh-Hans", JSONObject().put("summary", "计数器"))) })
        reject { StoreFormat.packageEntry(card().apply { getJSONObject("locales").put("en", JSONObject().put("title", "Counter")) }) }
        for (code in listOf("RU", "russian", "r", "ru_RU", "ru-")) reject { StoreFormat.packageEntry(card().apply { put("locales", JSONObject().put(code, JSONObject().put("title", "x"))) }) }
        reject { StoreFormat.packageEntry(card().apply { getJSONObject("locales").put("ru", JSONObject()) }) }
        StoreFormat.packageEntry(card().apply { getJSONObject("locales").getJSONObject("ru").put("description", "x") })
        reject { StoreFormat.packageEntry(card().apply { getJSONObject("locales").getJSONObject("ru").put("title", "x".repeat(121)) }) }
        reject { StoreFormat.packageEntry(card().apply { getJSONObject("locales").getJSONObject("ru").put("summary", "x".repeat(601)) }) }
        reject { StoreFormat.packageEntry(card().apply { getJSONObject("locales").getJSONObject("ru").put("tags", JSONArray().put("пример")) }) }
        reject { StoreFormat.packageEntry(card().apply { getJSONObject("locales").getJSONObject("ru").put("tags", JSONArray().put("пример").put("пример")) }) }
        reject { StoreFormat.packageEntry(card().apply { getJSONObject("locales").getJSONObject("ru").put("tags", JSONArray().put("пример").put(" счётчик")) }) }
        reject { StoreFormat.packageEntry(card().apply { remove("tags") }) }
        reject { StoreFormat.packageEntry(card().apply { put("locales", JSONObject((0..16).associate { "l%02d".format(it) to JSONObject().put("title", "x") })) }) }
        StoreFormat.packageEntry(card().apply { getJSONObject("locales").getJSONObject("ru").put("tags", JSONArray().put("Пример").put("Счётчик")) })
        // Details: a translation of what the details have in English.
        StoreFormat.details(details().apply { getJSONObject("locales").getJSONObject("ru").put("captions", JSONArray().put(JSONObject.NULL)).put("changes", JSONObject()) }.toString().toByteArray())
        reject { StoreFormat.details(details().apply { getJSONObject("locales").getJSONObject("ru").getJSONObject("changes").put("2.0.0", "Нет такой версии.") }.toString().toByteArray()) }
        reject { StoreFormat.details(details().apply { getJSONObject("locales").getJSONObject("ru").getJSONObject("changes").put("first", "Не версия.") }.toString().toByteArray()) }
        reject { StoreFormat.details(details().apply { getJSONObject("locales").getJSONObject("ru").put("captions", JSONArray().put("Один").put("Два")) }.toString().toByteArray()) }
        reject { StoreFormat.details(details().apply { getJSONArray("screenshots").getJSONObject(0).remove("caption") }.toString().toByteArray()) }
        reject { StoreFormat.details(details().apply { remove("description") }.toString().toByteArray()) }
        reject { StoreFormat.details(details().apply { getJSONObject("locales").getJSONObject("ru").put("description", "x".repeat(20_001)) }.toString().toByteArray()) }
        StoreFormat.details(details().apply { getJSONObject("locales").getJSONObject("ru").put("links", JSONObject()) }.toString().toByteArray())
        reject { StoreFormat.details(details().apply { getJSONObject("locales").put("en", JSONObject().put("description", "x")) }.toString().toByteArray()) }
        reject { StoreFormat.details(details().apply { getJSONObject("locales").put("ru", JSONObject()) }.toString().toByteArray()) }
    }

    @Test fun modNamesItsTargetAndItsReferenceMustBeWhatItsFilesAre() {
        fun file(path: String) = JSONObject().put("path", path).put("sha256", "1".repeat(64)).put("size", 1)
        fun mod() = JSONObject().put("schemaVersion", 1).put("id", "demo.night").put("kind", "mod").put("version", "1.0.0").put("publisher", "demo").put("license", "MIT")
            .put("sourceFormat", "readable").put("runtime", JSONObject().put("assiApi", 1)).put("capabilities", JSONArray().put("storage.own"))
            .put("dependencies", JSONArray()).put("optionalDependencies", JSONArray()).put("extends", "demo.counter")
            .put("reference", JSONObject().put("version", "1.0.0").put("base", JSONObject().put("main.js", "2".repeat(64))).put("added", JSONArray().put("ui/night.css")).put("removed", JSONArray()))
            .put("files", JSONArray(listOf("README.md", "LICENSE", "mod/brief.md", "reference/main.js", "reference/ui/night.css", "base/main.js").map(::file)))
        fun check(value: JSONObject) = StoreFormat.manifest(value.toString().toByteArray())
        assertEquals("demo.counter", check(mod()).getString("extends"))
        reject { check(mod().put("entry", "code/main.js")) }
        reject { check(mod().put("extends", "demo.night")) }
        reject { check(mod().apply { remove("reference") }) }
        reject { check(mod().apply { getJSONArray("files").put(file("code/main.js")) }) }
        reject { check(mod().apply { getJSONArray("files").remove(2) }) }
        reject { check(mod().apply { getJSONObject("reference").put("added", JSONArray()) }) }
        reject { check(mod().apply { getJSONObject("reference").getJSONObject("base").put("other.js", "3".repeat(64)) }) }
        reject { check(mod().apply { getJSONObject("reference").put("removed", JSONArray().put("main.js")) }) }
        reject { check(mod().apply { getJSONObject("reference").put("version", "latest") }) }
        reject { check(mod().apply { getJSONObject("reference").put("removed", JSONArray().put("../escape.js")) }) }
        // Nothing but a mod may name what it extends, in a manifest or in a card; a card of a mod must.
        val plain = JSONObject(String(example("package")))
        reject { check(plain.put("extends", "demo.counter")) }
        val card = JSONObject(String(example("page"))).getJSONArray("packages").getJSONObject(0)
        reject { StoreFormat.packageEntry(JSONObject(card.toString()).put("extends", "demo.counter")) }
        reject { StoreFormat.packageEntry(JSONObject(card.toString()).put("kind", "mod")) }
        StoreFormat.packageEntry(JSONObject(card.toString()).put("kind", "mod").put("extends", "demo.counter"))
        reject { StoreFormat.packageEntry(JSONObject(card.toString()).put("kind", "mod").put("extends", card.getString("id"))) }
    }

    @Test fun pictureIsWhatItsBytesAreAndAnSvgIsOnlyADrawing() {
        assertEquals("image/png", StoreFormat.pictureType(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0)))
        assertEquals("image/jpeg", StoreFormat.pictureType(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte())))
        assertEquals("image/webp", StoreFormat.pictureType("RIFF".toByteArray() + byteArrayOf(1, 0, 0, 0) + "WEBPVP8 ".toByteArray()))
        assertEquals("image/svg+xml", StoreFormat.pictureType("<?xml version=\"1.0\"?>\n<!-- icon -->\n<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><use href=\"#a\"/><path d=\"M1 1h2\"/></svg>".toByteArray()))
        for (bad in listOf("<html><svg></svg></html>", "<svg><script>alert(1)</script></svg>", "<svg onload=\"x()\"></svg>", "<svg><a href=\"javascript:x()\">a</a></svg>",
            "<svg><foreignObject><iframe/></foreignObject></svg>", "<svg><use href=\"https://evil.example/a.svg#x\"/></svg>", "<!DOCTYPE svg [<!ENTITY a \"b\">]><svg/>", "plain text", "")) assertNull(bad, StoreFormat.pictureType(bad.toByteArray()))
    }

    @Test fun strictJsonRejectsDuplicateKeysLooseSyntaxAndInvalidUnicode() {
        for (bad in listOf("{\"a\":1,\"a\":2}", "{a:1}", "{\"a\":1,}", "{\"a\":NaN}", "{\"a\":1e999}", "{\"a\":\"\\uD800\"}", "{\"a\":01}", "{\"a\":true} false")) reject { StoreFormat.parse(bad.toByteArray()) }
        reject { StoreFormat.parse(byteArrayOf(0xc3.toByte(), 0x28)) }
        reject { StoreFormat.parse(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "{}".toByteArray()) }
        reject { StoreFormat.parse("[{}]".toByteArray()) }
        assertEquals("😀", StoreFormat.parse("{\"a\":\"\\uD83D\\uDE00\"}".toByteArray()).getString("a"))
    }

    @Test fun limitsAndIdentitiesCannotBeOverriddenByIndex() {
        val page = StoreFormat.page(example("page"))
        val entry = page.getJSONArray("packages").getJSONObject(0)
        page.getJSONArray("packages").put(JSONObject(entry.toString()))
        reject { StoreFormat.page(page.toString().toByteArray()) }
        entry.getJSONObject("archive").put("size", StoreFormat.MAX_ARCHIVE + 1)
        reject { StoreFormat.packageEntry(entry) }
        reject { StoreFormat.index(ByteArray(StoreFormat.MAX_METADATA + 1) { 32 }) }
    }

    @Test fun canonicalPathsRejectEscapeAndAmbiguousNames() {
        for (path in listOf("/abs", "../file", "code/../x", "code//x", "code\\x", "code/.hidden", "code/C:", "code/CON.txt", "code/a.", "code/a ", "code/e\u0301.js")) reject { StoreFormat.path(path) }
        for (paths in listOf(listOf("code/a", "code/a/b.js"), listOf("code/A.js", "code/a.js"), listOf("assets/Art/a", "assets/art/b"), listOf("code/Straße.js", "code/STRASSE.js"))) reject { StoreFormat.uniquePaths(paths) }
        StoreFormat.uniquePaths(listOf("code/main.js", "code/ui/index.html", "assets/звук.ogg"))
    }

    @Test fun httpsRequiresExplicitSafeAuthority() {
        for (url in listOf("http://example.org/a", "https://user:pass@example.org/a", "https://example.org/#a", "https://example.org:99999/a", "https://example.org\\@evil.test/a", "https://example.org/a\nb")) reject { StoreFormat.https(url) }
        assertEquals("https://example.org/a?revision=1", StoreFormat.https("https://example.org/a?revision=1"))
    }

    @Test fun semverIncludesPrereleaseOrderingAndExactDependencies() {
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "2.0.0")
        ordered.zipWithNext().forEach { (a, b) -> assertTrue(StoreFormat.compareVersions(a, b) < 0) }
        assertEquals(0, StoreFormat.compareVersions("1.0.0+build.1", "1.0.0+build.2"))
        assertTrue(StoreFormat.versionMatches("2.0.0", ">=1.0.0")); assertFalse(StoreFormat.versionMatches("2.0.0", "1.0.0"))
        for (bad in listOf("1.0", "01.0.0", "1.0.0-01", "latest", "^1.0.0")) reject { StoreFormat.version(bad) }
    }

    @Test fun manifestRequiresInventoryAndDisjointDependencies() {
        val manifest = StoreFormat.manifest(example("package"))
        manifest.put("entry", "code/missing.js")
        reject { StoreFormat.manifest(manifest.toString().toByteArray()) }
        manifest.put("entry", "code/main.js").put("dependencies", JSONArray().put(JSONObject().put("id", "example.counter").put("version", "1.0.0")))
        reject { StoreFormat.manifest(manifest.toString().toByteArray()) }
    }
}
