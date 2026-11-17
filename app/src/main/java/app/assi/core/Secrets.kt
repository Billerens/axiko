package app.assi.core

import app.assi.agent.Llm
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * The rules about secrets that need no keystore to state: whose a name is, in what forms a value may
 * show up in text, and how it is taken out of what a model reads. The values themselves are in [Vault].
 *
 * A secret is used, not shown. The shell's own secrets (the keys of model providers, the tokens of MCP
 * servers and sign-ins, the backup password) are used by the shell alone: no operation gives them to a
 * script. A module's secret is put into its request by the shell ([request]). And whatever a tool
 * returns is scrubbed of every stored value before a model sees it ([scrub]).
 *
 * This is not isolation. Scripts run with the whole of Java in reach, and code written on purpose to dig
 * a value out and disguise it is stopped by the review of what gets installed, not by this file.
 */
object Secrets {
    private val SHELL_PREFIXES = listOf("provider.", "mcp.", "oauth.")
    private val SHELL_NAMES = setOf("hf.token", "backup.password")

    /** A secret of the shell itself: scripts do not read it, list it, write it or remove it. */
    fun shell(name: String): Boolean = name in SHELL_NAMES || SHELL_PREFIXES.any { name.startsWith(it) }

    /** Shorter than this a value is not looked for: it would be found in ordinary words. */
    const val MIN = 8

    /**
     * The forms in which a value may appear in text: as it is, as it stands inside JSON, as it stands in
     * an address. A value that is JSON itself (the tokens of a sign-in) is also looked for by its long strings.
     */
    fun forms(value: String): List<String> {
        val out = LinkedHashSet<String>()
        fun add(text: String) {
            if (text.length < MIN) return
            out.add(text)
            JSONObject.quote(text).let { quoted -> out.add(quoted.substring(1, quoted.length - 1)) }
            runCatching { URLEncoder.encode(text, "UTF-8") }.getOrNull()?.let(out::add)
        }
        add(value.trim())
        runCatching { JSONObject(value) }.getOrNull()?.let { json ->
            json.keys().forEach { key -> (json.opt(key) as? String)?.takeIf { it.length >= 16 }?.let(::add) }
        }
        return out.sortedByDescending { it.length }
    }

    /** A value cut short (a clipped line, a shortened preview) is still found by a beginning this long. */
    const val HEAD = 12

    /**
     * [text] with every one of the [known] values (name to value) replaced by a mark that names the secret.
     * A value is found whole or by its beginning: where text was cut in the middle of a secret, what is
     * left of it goes too.
     */
    fun scrub(text: String, known: List<Pair<String, String>>): String {
        if (text.length < MIN) return text
        var out = text
        for ((name, value) in known) {
            // The forms of one value begin alike: at each place the one that matches furthest is the one that stands there.
            val forms = forms(value); val heads = forms.map { it.take(HEAD) }.distinct(); val mark = mark(name)
            var at = 0
            while (true) {
                val from = heads.map { out.indexOf(it, at) }.filter { it >= 0 }.minOrNull() ?: break
                val length = forms.maxOf { form -> var same = 0; while (same < form.length && from + same < out.length && out[from + same] == form[same]) same++; same }
                out = out.substring(0, from) + mark + out.substring(from + length)
                at = from + mark.length
            }
        }
        return out
    }

    fun mark(name: String): String = "[секрет «$name» скрыт]"

    private val PLACE = Regex("\\{\\{secret:([A-Za-z0-9_.-]{1,80})\\}\\}")

    /** [text] with each `{{secret:name}}` replaced by what [value] gives for the name. */
    fun fill(text: String, value: (String) -> String): String = PLACE.replace(text) { value(it.groupValues[1]) }

    /**
     * A request of a script that names its secrets instead of holding them: `{url, method?, headers?, json?, body?}`
     * with `{{secret:name}}` in the address, a header or the body. The shell puts the values in, sends the request
     * over HTTPS and takes the values out of the answer: `{status, ok, headers, text}`.
     */
    fun request(options: JSONObject): JSONObject {
        val used = LinkedHashMap<String, String>()
        fun secret(name: String): String {
            require(!shell(name)) { "Секрет «$name» принадлежит оболочке и в запросы скриптов не подставляется." }
            return used.getOrPut(name) { Vault.get(name) ?: throw IllegalArgumentException("Секрет «$name» не задан. Попросите владельца ввести его: assi.secret.ask.") }
        }
        val url = fill(options.getString("url")) { URLEncoder.encode(secret(it), "UTF-8") }
        require(url.startsWith("https://")) { "Секреты подставляются только в запросы по HTTPS." }
        val builder = Request.Builder().url(url)
        var type: String? = null
        options.optJSONObject("headers")?.let { headers ->
            headers.keys().forEach { name ->
                val value = fill(headers.get(name).toString()) { secret(it) }
                if (name.equals("Content-Type", ignoreCase = true)) type = value
                builder.header(name, value)
            }
        }
        val body = when {
            options.has("json") && !options.isNull("json") ->
                fill(options.get("json").toString()) { JSONObject.quote(secret(it)).let { quoted -> quoted.substring(1, quoted.length - 1) } }
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
            options.has("body") && !options.isNull("body") ->
                fill(options.get("body").toString()) { secret(it) }.toRequestBody((type ?: "text/plain; charset=utf-8").toMediaType())
            else -> null
        }
        val method = options.optString("method").ifBlank { if (body != null) "POST" else "GET" }.uppercase()
        builder.method(method, body ?: if (method != "GET" && method != "HEAD") ByteArray(0).toRequestBody(null) else null)
        Llm.client.newCall(builder.build()).execute().use { response ->
            val known = used.map { it.key to it.value }
            val headers = JSONObject()
            response.headers.names().forEach { headers.put(it.lowercase(), scrub(response.header(it).orEmpty(), known)) }
            return JSONObject().put("status", response.code).put("ok", response.isSuccessful).put("headers", headers)
                .put("text", scrub(response.body?.string().orEmpty(), known))
        }
    }

    /**
     * Takes the [known] values out of what was already written to disk under [root] (chats, the list of tasks):
     * once, when secrets that used to lie in the settings are moved to the vault. Returns how many files changed.
     */
    fun scrubFiles(root: File, known: List<Pair<String, String>>, skip: (File) -> Boolean = { false }): Int {
        if (known.isEmpty() || !root.isDirectory) return 0
        var changed = 0
        root.walkTopDown().onEnter { !skip(it) }.filter { it.isFile && it.length() in MIN..MAX_FILE }.forEach { file ->
            runCatching {
                val text = file.readText()
                val clean = scrub(text, known)
                if (clean != text) { file.writeText(clean); changed++ }
            }
        }
        return changed
    }

    private const val MAX_FILE = 64L * 1024 * 1024
}
