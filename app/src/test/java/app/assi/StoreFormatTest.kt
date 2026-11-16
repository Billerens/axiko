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

    @Test fun publishedExamplesAndSchemaVersions() {
        assertEquals(1, StoreFormat.index(example("index")).getInt("schemaVersion"))
        assertEquals("readable", StoreFormat.manifest(example("package")).getString("sourceFormat"))
        reject { StoreFormat.index(JSONObject(String(example("index"))).put("schemaVersion", 2).toString().toByteArray()) }
        reject { StoreFormat.index(JSONObject(String(example("index"))).put("extra", true).toString().toByteArray()) }
    }

    @Test fun strictJsonRejectsDuplicateKeysLooseSyntaxAndInvalidUnicode() {
        for (bad in listOf("{\"a\":1,\"a\":2}", "{a:1}", "{\"a\":1,}", "{\"a\":NaN}", "{\"a\":1e999}", "{\"a\":\"\\uD800\"}", "{\"a\":01}", "{\"a\":true} false")) reject { StoreFormat.parse(bad.toByteArray()) }
        reject { StoreFormat.parse(byteArrayOf(0xc3.toByte(), 0x28)) }
        reject { StoreFormat.parse(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "{}".toByteArray()) }
        reject { StoreFormat.parse("[{}]".toByteArray()) }
        assertEquals("😀", StoreFormat.parse("{\"a\":\"\\uD83D\\uDE00\"}".toByteArray()).getString("a"))
    }

    @Test fun limitsAndIdentitiesCannotBeOverriddenByIndex() {
        val index = StoreFormat.index(example("index"))
        val entry = index.getJSONArray("packages").getJSONObject(0)
        index.getJSONArray("packages").put(JSONObject(entry.toString()))
        reject { StoreFormat.index(index.toString().toByteArray()) }
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
