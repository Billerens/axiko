package app.assi.agent

import app.assi.App
import app.assi.core.Vault
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * A client of MCP servers (Model Context Protocol, the Streamable HTTP transport): the owner
 * adds a server's address, and its tools appear among the agents' tools as
 * `mcp_<server>_<tool>`. A server's access token is kept in the vault.
 */
object Mcp {
    private const val PREF = "mcp.servers"
    private const val VERSION = "2025-06-18"
    private const val FRESH_MS = 10 * 60_000L
    private val json = "application/json".toMediaType()
    private val ids = AtomicInteger(1)

    private class Session(var id: String? = null, var version: String = VERSION, var ready: Boolean = false)

    private val sessions = HashMap<String, Session>()
    private val listed = HashMap<String, Pair<Long, JSONArray>>()
    private val failures = HashMap<String, String>()

    /** `[{id, name, url}]` */
    fun servers(): JSONArray = runCatching { JSONArray(App.prefs.getString(PREF, "[]")) }.getOrElse { JSONArray() }

    /** Saves the list; a `token` given with a server goes to the vault, not to the settings. */
    @Synchronized
    fun setServers(list: JSONArray) {
        val clean = JSONArray()
        val kept = HashSet<String>()
        for (i in 0 until list.length()) {
            val server = list.getJSONObject(i)
            val id = server.getString("id")
            kept.add(id)
            if (server.has("token")) {
                val token = server.optString("token")
                if (token.isBlank()) Vault.remove("mcp.$id") else Vault.set("mcp.$id", token)
            }
            clean.put(JSONObject().put("id", id).put("name", server.optString("name")).put("url", server.optString("url").trim()))
        }
        Vault.names().filter { it.startsWith("mcp.") && it.removePrefix("mcp.") !in kept }.forEach { Vault.remove(it) }
        App.prefs.edit().putString(PREF, clean.toString()).apply()
        sessions.clear()
        listed.clear()
        failures.clear()
    }

    private fun server(id: String): JSONObject {
        val all = servers()
        for (i in 0 until all.length()) if (all.getJSONObject(i).getString("id") == id) return all.getJSONObject(i)
        throw IllegalArgumentException("Нет сервера инструментов «$id»")
    }

    // ---- the protocol --------------------------------------------------------------------------

    /** Sends one JSON-RPC message; returns the `result` of a request, null for a notification. */
    private fun post(server: JSONObject, session: Session, method: String, params: JSONObject?, notify: Boolean = false): JSONObject? {
        val id = ids.getAndIncrement()
        val message = JSONObject().put("jsonrpc", "2.0").put("method", method)
        if (!notify) message.put("id", id)
        if (params != null) message.put("params", params)
        val request = Request.Builder().url(server.getString("url")).apply {
            header("Accept", "application/json, text/event-stream")
            Vault.get("mcp." + server.getString("id"))?.let { header("Authorization", "Bearer $it") }
            session.id?.let { header("Mcp-Session-Id", it) }
            if (session.ready) header("MCP-Protocol-Version", session.version)
        }.post(message.toString().toRequestBody(json)).build()

        Llm.client.newCall(request).execute().use { response ->
            response.header("Mcp-Session-Id")?.let { session.id = it }
            if (response.code == 404 && session.ready) throw SessionLost()
            if (!response.isSuccessful) {
                throw LlmError("Сервер инструментов «${server.optString("name")}» ответил ошибкой ${response.code}: ${response.body?.string().orEmpty().take(300)}")
            }
            if (notify) return null
            val body = response.body ?: throw LlmError("Пустой ответ сервера инструментов")
            val answer = if (response.header("Content-Type").orEmpty().contains("text/event-stream")) {
                // The answer comes as events; other messages may precede the one with our id.
                var found: JSONObject? = null
                val source = body.source()
                while (found == null && !source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val event = runCatching { JSONObject(line.substring(5).trim()) }.getOrNull() ?: continue
                    if (event.optInt("id", -1) == id) found = event
                }
                found ?: throw LlmError("Сервер инструментов не ответил на запрос")
            } else {
                JSONObject(body.string())
            }
            answer.optJSONObject("error")?.let { throw LlmError("Сервер инструментов: " + it.optString("message", it.toString())) }
            return answer.optJSONObject("result") ?: JSONObject()
        }
    }

    private class SessionLost : Exception()

    private fun session(server: JSONObject): Session {
        val id = server.getString("id")
        val session = synchronized(sessions) { sessions.getOrPut(id) { Session() } }
        synchronized(session) {
            if (session.ready) return session
            val hello = post(
                server, session, "initialize",
                JSONObject().put("protocolVersion", VERSION).put("capabilities", JSONObject())
                    .put("clientInfo", JSONObject().put("name", "Axiko").put("version", "0.1")),
            )
            session.version = hello?.optString("protocolVersion").orEmpty().ifBlank { VERSION }
            session.ready = true
            post(server, session, "notifications/initialized", null, notify = true)
        }
        return session
    }

    /** A request within a session; a session the server has forgotten is opened anew once. */
    private fun ask(id: String, method: String, params: JSONObject): JSONObject {
        val server = server(id)
        return try {
            post(server, session(server), method, params)!!
        } catch (lost: SessionLost) {
            synchronized(sessions) { sessions.remove(id) }
            post(server, session(server), method, params)!!
        }
    }

    // ---- what the app uses ---------------------------------------------------------------------

    /** The server's tools: `[{name, description, inputSchema}]`. Remembered for ten minutes. */
    fun tools(id: String, refresh: Boolean = false): JSONArray {
        synchronized(listed) {
            listed[id]?.takeIf { !refresh && System.currentTimeMillis() - it.first < FRESH_MS }?.let { return it.second }
        }
        val all = JSONArray()
        var cursor: String? = null
        do {
            val page = ask(id, "tools/list", JSONObject().apply { if (cursor != null) put("cursor", cursor) })
            val tools = page.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until tools.length()) all.put(tools.getJSONObject(i))
            cursor = page.optString("nextCursor").takeIf { it.isNotBlank() }
        } while (cursor != null)
        synchronized(listed) { listed[id] = System.currentTimeMillis() to all }
        failures.remove(id)
        return all
    }

    /** Calls a tool and returns what it answered as text. */
    fun call(id: String, tool: String, arguments: JSONObject): String {
        val result = ask(id, "tools/call", JSONObject().put("name", tool).put("arguments", arguments))
        val content = result.optJSONArray("content") ?: JSONArray()
        val text = StringBuilder()
        for (i in 0 until content.length()) {
            val part = content.getJSONObject(i)
            when (part.optString("type")) {
                "text" -> text.append(part.optString("text")).append('\n')
                "resource" -> text.append(part.optJSONObject("resource")?.optString("text").orEmpty()).append('\n')
                else -> text.append("[${part.optString("type")}]\n")
            }
        }
        result.optJSONObject("structuredContent")?.let { if (text.isBlank()) text.append(it.toString(1)) }
        val answer = text.toString().trim().ifEmpty { "готово" }
        if (result.optBoolean("isError")) throw LlmError(answer)
        return answer
    }

    private fun slug(text: String): String = text.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')

    /** Every server's tools as the model sees them: name, description, parameters, and where it leads. */
    fun offered(): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val all = servers()
        for (i in 0 until all.length()) {
            val server = all.getJSONObject(i)
            val id = server.getString("id")
            // A server that is down must not take the agent down with it; it is tried again later.
            val tools = try {
                tools(id)
            } catch (error: Exception) {
                failures[id] = error.message ?: error.toString()
                synchronized(listed) { listed[id] = System.currentTimeMillis() - FRESH_MS + 60_000 to JSONArray() }
                continue
            }
            for (j in 0 until tools.length()) {
                val tool = tools.getJSONObject(j)
                out.put(server, tool, id)
            }
        }
        return out
    }

    private fun ArrayList<JSONObject>.put(server: JSONObject, tool: JSONObject, id: String) {
        val name = ("mcp_" + slug(server.optString("name").ifBlank { id }) + "_" + slug(tool.getString("name"))).take(64)
        add(
            JSONObject().put("name", name)
                .put("description", ("[${server.optString("name")}] " + tool.optString("description")).take(1000))
                .put("parameters", tool.optJSONObject("inputSchema") ?: JSONObject().put("type", "object").put("properties", JSONObject()))
                .put("server", id).put("tool", tool.getString("name")),
        )
    }

    /** Runs a tool by the name the model was given. */
    fun run(offeredName: String, arguments: JSONObject): String {
        val match = offered().firstOrNull { it.getString("name") == offeredName }
            ?: throw IllegalArgumentException("Нет инструмента $offeredName")
        return call(match.getString("server"), match.getString("tool"), arguments)
    }

    /** For the settings screen: each server with the number of its tools or the reason it failed. */
    fun status(): JSONArray {
        val out = JSONArray()
        val all = servers()
        for (i in 0 until all.length()) {
            val server = all.getJSONObject(i)
            val id = server.getString("id")
            val entry = JSONObject(server.toString()).put("token", Vault.get("mcp.$id") != null)
            try {
                entry.put("tools", tools(id, refresh = true).length())
            } catch (error: Exception) {
                entry.put("error", error.message ?: error.toString())
            }
            out.put(entry)
        }
        return out
    }
}
