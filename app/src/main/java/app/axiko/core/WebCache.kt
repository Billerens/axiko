package app.axiko.core

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import app.axiko.App
import app.axiko.agent.Llm
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Static things pages take from the web — libraries from CDNs, fonts, images, styles — are
 * kept on disk after the first load and served from there, so a module that imports
 * `https://esm.sh/...` opens instantly and works without a network. An entry never expires:
 * the same address keeps giving the same file until the cache is cleared.
 */
object WebCache {
    private const val MAX_BYTES = 40L * 1024 * 1024

    /** Hosts that serve nothing but versioned packages and fonts. */
    private val cdns = listOf(
        "esm.sh", "esm.run", "cdn.jsdelivr.net", "unpkg.com", "cdnjs.cloudflare.com", "ga.jspm.io",
        "jspm.dev", "cdn.skypack.dev", "fonts.googleapis.com", "fonts.gstatic.com",
    )

    private val staticTypes = setOf(
        "js", "mjs", "css", "woff", "woff2", "ttf", "otf", "png", "jpg", "jpeg", "gif", "webp", "svg",
        "ico", "wasm", "map",
    )

    private val dir: File get() = File(Workspace.data, ".webcache").apply { mkdirs() }

    val enabled: Boolean get() = App.prefs.getString("webcache", "on") != "off"

    /** A plain download of a static file or of anything from a package CDN. */
    fun wants(request: WebResourceRequest): Boolean {
        val url = request.url
        if (!enabled || request.method != "GET" || (url.scheme != "https" && url.scheme != "http")) return false
        val host = url.host ?: return false
        if (cdns.any { host == it || host.endsWith(".$it") }) return true
        return url.path.orEmpty().substringAfterLast('.', "").lowercase() in staticTypes
    }

    /** The cached file, fetched first if it is not there yet; null when it cannot be had. */
    fun serve(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        val key = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val body = File(dir, "$key.bin")
        val meta = File(dir, "$key.json")
        if (!body.isFile || !meta.isFile) {
            if (!fetch(request, url, body, meta)) return null
        }
        val info = runCatching { JSONObject(meta.readText()) }.getOrNull() ?: return null
        val headers = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=31536000, immutable")
        return WebResourceResponse(
            info.optString("mime", "application/octet-stream"),
            info.optString("charset").ifEmpty { null },
            200, "OK", headers, body.inputStream(),
        )
    }

    private fun fetch(request: WebResourceRequest, url: String, body: File, meta: File): Boolean = try {
        val builder = Request.Builder().url(url)
        // CDNs choose the build by the browser: pass on who is asking and what it accepts.
        for (name in listOf("User-Agent", "Accept", "Accept-Language")) {
            request.requestHeaders.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.let { builder.header(name, it.value) }
        }
        Llm.client.newCall(builder.build()).execute().use { response ->
            val source = response.body
            if (!response.isSuccessful || source == null || source.contentLength() > MAX_BYTES) {
                false
            } else {
                val partial = File(body.path + ".part")
                partial.outputStream().use { out -> source.byteStream().copyTo(out) }
                val type = source.contentType()
                meta.writeText(
                    JSONObject().put("url", url)
                        .put("mime", type?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream")
                        .put("charset", type?.charset()?.name().orEmpty())
                        .put("time", System.currentTimeMillis()).toString(),
                )
                partial.renameTo(body)
            }
        }
    } catch (error: Exception) {
        false
    }

    fun info(): JSONObject {
        val files = dir.listFiles().orEmpty().filter { it.name.endsWith(".bin") }
        return JSONObject().put("files", files.size).put("bytes", files.sumOf { it.length() }).put("enabled", enabled)
    }

    fun clear() {
        dir.listFiles().orEmpty().forEach { it.delete() }
    }
}
