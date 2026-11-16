package app.assi

import app.assi.store.StoreFetch
import app.assi.store.StoreFormat
import app.assi.store.StoreTransport
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object StoreServiceFixtures {
    const val INDEX = "https://catalog.example/index.json"

    fun entry(id: String = "demo.counter", version: String = "1.0.0"): JSONObject = JSONObject()
        .put("id", id).put("kind", "module").put("title", "Counter").put("summary", "Readable fixture")
        .put("version", version).put("publisher", "demo").put("license", "MIT")
        .put("manifestUrl", "https://catalog.example/$id/$version/package.json")
        .put("archive", JSONObject().put("url", "https://catalog.example/$id/$version/package.zip")
            .put("sha256", "0".repeat(64)).put("size", 256).put("unpackedSize", 1024).put("fileCount", 6))
        .put("compatibility", JSONObject().put("minAssiApi", 1))

    fun index(vararg entries: JSONObject, id: String = "demo.catalog"): ByteArray = JSONObject()
        .put("schemaVersion", 1).put("repository", JSONObject().put("id", id).put("title", "Demo catalog").put("homepage", "https://catalog.example/"))
        .put("packages", JSONArray().apply { entries.forEach { put(it) } }).toString().toByteArray()

    data class Package(val entry: JSONObject, val manifest: ByteArray, val zip: ByteArray)

    fun bundle(id: String = "demo.counter", version: String = "1.0.0", main: String = "exports.value = function () {\n  return 1;\n};\n", dependency: Pair<String, String>? = null): Package {
        val files = linkedMapOf(
            "README.md" to "# Counter\nA readable fixture, no personal data.\n".toByteArray(),
            "LICENSE" to "MIT test fixture\n".toByteArray(),
            "code/module.json" to JSONObject().put("id", id).put("title", "Counter").put("entry", "ui/index.html").put("autostart", false).toString().toByteArray(),
            "code/main.js" to main.toByteArray(),
            "code/ui/index.html" to "<!doctype html>\n<html><body><p>Fixture counter</p></body></html>\n".toByteArray(),
        )
        val manifest = JSONObject().put("schemaVersion", 1).put("id", id).put("kind", "module").put("version", version)
            .put("publisher", "demo").put("license", "MIT").put("sourceFormat", "readable").put("entry", "code/ui/index.html")
            .put("runtime", JSONObject().put("assiApi", 1)).put("capabilities", JSONArray().put("storage.own"))
            .put("dependencies", JSONArray().apply { dependency?.let { put(JSONObject().put("id", it.first).put("version", it.second)) } })
            .put("optionalDependencies", JSONArray()).put("files", JSONArray().apply {
                files.forEach { (path, bytes) -> put(JSONObject().put("path", path).put("size", bytes.size).put("sha256", StoreFormat.sha256(bytes))) }
            }).toString().toByteArray()
        val all = linkedMapOf("package.json" to manifest).apply { putAll(files) }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> all.forEach { (path, bytes) ->
            zip.putNextEntry(ZipEntry(path).apply { time = 0 }); zip.write(bytes); zip.closeEntry()
        } }
        val archive = out.toByteArray()
        val entry = entry(id, version)
        entry.getJSONObject("archive").put("sha256", StoreFormat.sha256(archive)).put("size", archive.size)
            .put("unpackedSize", all.values.sumOf { it.size }).put("fileCount", all.size)
        return Package(entry, manifest, archive)
    }

    class Transport : StoreTransport {
        data class Request(val url: String, val limit: Int, val etag: String?, val lastModified: String?)
        val responses = LinkedHashMap<String, StoreFetch>()
        val requests = ArrayList<Request>()
        var conditional = false
        var hook: ((Request) -> Unit)? = null
        override fun get(url: String, maxBytes: Int, etag: String?, lastModified: String?): StoreFetch {
            val request = Request(url, maxBytes, etag, lastModified); requests += request; hook?.invoke(request)
            val response = responses[url] ?: throw IOException("Fixture offline")
            return if (conditional && etag != null && response.etag == etag) StoreFetch(ByteArray(0), 304, etag, lastModified, url) else response.copy(bytes = response.bytes.copyOf())
        }
        fun put(url: String, bytes: ByteArray, etag: String? = null) { responses[url] = StoreFetch(bytes, 200, etag, null, url) }
        fun publish(vararg packages: Package) {
            put(INDEX, index(*packages.map { it.entry }.toTypedArray()), "\"index-${packages.joinToString { it.entry.getString("version") }}\"")
            packages.forEach { p -> put(p.entry.getString("manifestUrl"), p.manifest); put(p.entry.getJSONObject("archive").getString("url"), p.zip) }
        }
    }
}
