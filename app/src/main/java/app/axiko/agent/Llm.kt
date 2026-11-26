package app.axiko.agent

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class LlmError(message: String, val retryable: Boolean = false, val retryAfterMs: Long = 0) : Exception(message)

/** Chat completions over any OpenAI-compatible endpoint. */
object Llm {
    class ToolCall(val id: String, val name: String, val arguments: String)

    /** [reasoning] is the model's thinking in the provider's own form, to be sent back with tool results. */
    class Reply(
        val text: String,
        val calls: List<ToolCall>,
        val reasoning: JSONArray? = null,
        /** Tokens (and money, when the provider tells) this answer cost. */
        val usage: JSONObject? = null,
    )

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = "application/json".toMediaType()

    private fun post(target: Models.Target, path: String, body: JSONObject): Request =
        Request.Builder().url(target.baseUrl + path).apply {
            if (target.key.isNotEmpty()) header("Authorization", "Bearer ${target.key}")
            header("X-Title", "Axiko")
        }.post(body.toString().toRequestBody(json)).build()

    private val THOUGHT_KEYS = listOf("reasoning", "reasoning_content")

    /**
     * One model answer, streamed. The model's thinking, when the provider shows it, goes to [onThought]. Text arrives through [onText] as it is written; tool calls
     * come back whole. When the request is cancelled, what was written so far is the answer.
     * [onCall] receives the running call (and null when it ends) so the caller can cancel it.
     */
    fun stream(
        target: Models.Target,
        messages: JSONArray,
        tools: JSONArray,
        cancelled: () -> Boolean,
        onCall: (Call?) -> Unit,
        onThought: (String) -> Unit = {},
        onText: (String) -> Unit,
    ): Reply {
        if (target.baseUrl.startsWith(LocalLlm.BASE)) {
            // A model on the phone: no network, the same messages and tools.
            if (cancelled()) return Reply("", emptyList())
            val reply = LocalLlm.generate(target.model, messages, tools, target.options.optInt("context", 0), cancelled, onText)
            return if (cancelled()) Reply(reply.text, emptyList(), usage = reply.usage) else reply
        }
        val body = JSONObject().put("model", target.model).put("messages", messages)
        if (tools.length() > 0) body.put("tools", tools)
        effort(target, body)
        return read(target, body, cancelled, onCall, onThought, onText)
    }

    /**
     * Sends [body] as a streamed request and gathers the answer. Streaming is also what keeps a
     * long answer alive: proxies in front of providers (Cloudflare among them) drop a connection
     * that stays silent for about a hundred seconds, and a stream is never silent that long.
     */
    private fun read(
        target: Models.Target,
        body: JSONObject,
        cancelled: () -> Boolean,
        onCall: (Call?) -> Unit,
        onThought: (String) -> Unit,
        onText: (String) -> Unit,
    ): Reply {
        body.put("stream", true).put("stream_options", JSONObject().put("include_usage", true))
        // OpenRouter adds the price of the answer when asked.
        if (target.openRouter) body.put("usage", JSONObject().put("include", true))

        val text = StringBuilder()
        val ids = HashMap<Int, String>()
        val names = HashMap<Int, StringBuilder>()
        val arguments = HashMap<Int, StringBuilder>()
        val reasoning = ArrayList<JSONObject>()
        var usage: JSONObject? = null
        var finishReason = ""
        var done = false

        val call = client.newCall(post(target, "/chat/completions", body))
        onCall(call)
        try {
            if (cancelled()) {
                call.cancel()
                return Reply("", emptyList())
            }
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw LlmError(
                        "Модель ответила ошибкой ${response.code}: " + errorText(runCatching { response.body?.string() }.getOrNull()),
                        transientStatus(response.code), retryAfter(response.header("Retry-After")),
                    )
                }
                // A provider that does not stream answers with the whole message at once.
                if (response.header("Content-Type").orEmpty().contains("application/json")) {
                    val reply = whole(response.body?.string())
                    if (cancelled()) return Reply("", emptyList())
                    if (reply.text.isNotEmpty()) onText(reply.text)
                    return reply
                }
                val source = response.body?.source() ?: throw LlmError("Пустой ответ модели", retryable = true)
                while (!cancelled() && !source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload.isEmpty()) continue
                    if (payload == "[DONE]") {
                        done = true
                        break
                    }
                    val chunk = runCatching { JSONObject(payload) }.getOrNull()
                        ?: throw LlmError("Поток ответа модели повреждён", retryable = true)
                    chunk.optJSONObject("error")?.let {
                        throw LlmError("Ошибка модели: " + it.optString("message", it.toString()), transientStatus(it.optInt("code")))
                    }
                    // The last chunk carries the usage and no choices.
                    chunk.optJSONObject("usage")?.let { usage = it }
                    val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: continue
                    if (!choice.isNull("finish_reason")) finishReason = choice.optString("finish_reason")
                    val delta = choice.optJSONObject("delta") ?: continue
                    if (!delta.isNull("content")) {
                        val piece = delta.optString("content")
                        if (piece.isNotEmpty()) {
                            text.append(piece)
                            onText(piece)
                        }
                    }
                    delta.optJSONArray("reasoning_details")?.let { collect(reasoning, it) }
                    // Thinking as plain text: "reasoning" at OpenRouter, "reasoning_content" at others.
                    for (key in THOUGHT_KEYS) {
                        if (delta.isNull(key)) continue
                        val thought = delta.optString(key)
                        if (thought.isNotEmpty()) {
                            onThought(thought)
                            break
                        }
                    }
                    val calls = delta.optJSONArray("tool_calls") ?: continue
                    for (i in 0 until calls.length()) {
                        val part = calls.optJSONObject(i) ?: throw incompleteToolCall()
                        val index = part.optInt("index", i)
                        if (!part.isNull("id") && part.optString("id").isNotEmpty()) ids[index] = part.getString("id")
                        val function = part.optJSONObject("function") ?: continue
                        if (!function.isNull("name")) {
                            names.getOrPut(index) { StringBuilder() }.append(function.optString("name"))
                        }
                        if (!function.isNull("arguments")) {
                            arguments.getOrPut(index) { StringBuilder() }.append(function.optString("arguments"))
                        }
                    }
                }
            }
        } catch (error: IOException) {
            if (!cancelled() && !done && finishReason.isBlank()) throw LlmError(
                "Нет связи с моделью: ${error.message}",
                retryable = error !is SSLHandshakeException && error !is SSLPeerUnverifiedException,
            )
        } finally {
            onCall(null)
        }

        if (cancelled()) return Reply(text.toString(), emptyList(), null, usage)
        if (!done && finishReason.isBlank()) throw LlmError("Ответ модели прервался", retryable = true)
        val indexes = ids.keys + names.keys + arguments.keys
        if (indexes.isEmpty() && finishReason == "tool_calls") throw incompleteToolCall()
        if (indexes.isNotEmpty() && finishReason in setOf("length", "content_filter")) {
            throw LlmError("Модель остановилась, не закончив вызов инструмента")
        }
        val calls = indexes.sorted().map { index ->
            toolCall(index, ids[index].orEmpty(), names[index]?.toString().orEmpty(), arguments[index]?.toString().orEmpty())
        }
        return Reply(text.toString(), uniqueCalls(calls), if (reasoning.isEmpty()) null else JSONArray(reasoning), usage)
    }

    /** A plain (not streamed) response read into a [Reply]. */
    private fun whole(body: String?): Reply {
        val parsed = runCatching { JSONObject(body.orEmpty()) }.getOrNull() ?: throw LlmError("Непонятный ответ модели", retryable = true)
        parsed.optJSONObject("error")?.let {
            throw LlmError("Ошибка модели: " + it.optString("message", it.toString()), transientStatus(it.optInt("code")))
        }
        val choice = parsed.optJSONArray("choices")?.optJSONObject(0) ?: throw LlmError("Пустой ответ модели", retryable = true)
        val message = choice.optJSONObject("message") ?: throw LlmError("Непонятный ответ модели")
        val listed = message.optJSONArray("tool_calls") ?: JSONArray()
        if (listed.length() == 0 && choice.optString("finish_reason") == "tool_calls") throw incompleteToolCall()
        if (listed.length() > 0 && choice.optString("finish_reason") in setOf("length", "content_filter")) {
            throw LlmError("Модель остановилась, не закончив вызов инструмента")
        }
        val calls = (0 until listed.length()).map { i ->
            val part = listed.optJSONObject(i) ?: throw incompleteToolCall()
            val function = part.optJSONObject("function") ?: throw incompleteToolCall()
            toolCall(i, part.optString("id"), function.optString("name"), function.optString("arguments"))
        }
        val text = if (message.isNull("content")) "" else message.optString("content")
        return Reply(text, uniqueCalls(calls), message.optJSONArray("reasoning_details"), parsed.optJSONObject("usage"))
    }

    private fun incompleteToolCall() = LlmError("Ответ модели содержит незавершённый вызов инструмента", retryable = true)

    private fun uniqueCalls(calls: List<ToolCall>): List<ToolCall> {
        if (calls.distinctBy { it.id }.size != calls.size) throw incompleteToolCall()
        return calls
    }

    private fun toolCall(index: Int, id: String, name: String, arguments: String): ToolCall {
        val complete = runCatching {
            val json = JSONTokener(arguments)
            json.nextValue() is JSONObject && json.nextClean() == '\u0000'
        }.getOrDefault(false)
        if (name.isBlank() || !complete) throw incompleteToolCall()
        return ToolCall(id.ifBlank { "call_${System.nanoTime()}_$index" }, name, arguments)
    }

    private fun transientStatus(code: Int): Boolean =
        code == 408 || code == 429 || code == 500 || code in 502..504 || code in 520..524 || code == 529

    private fun retryAfter(header: String?): Long {
        val value = header?.trim().orEmpty()
        if (value.isEmpty()) return 0
        value.toLongOrNull()?.let { return TimeUnit.SECONDS.toMillis(it.coerceAtLeast(0)) }
        return runCatching {
            (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0)
        }.getOrDefault(0)
    }

    /** Asks for the thinking effort of the role, in the dialect of the provider. */
    private fun effort(target: Models.Target, body: JSONObject) {
        if (target.effort.isEmpty()) return
        if (target.openRouter) body.put("reasoning", JSONObject().put("effort", target.effort))
        else body.put("reasoning_effort", target.effort)
    }

    /**
     * Thinking arrives in fragments: pieces with the same index are one block whose text
     * grows, while its other fields (type, signature, format) are simply set.
     */
    private fun collect(blocks: ArrayList<JSONObject>, fragments: JSONArray) {
        for (i in 0 until fragments.length()) {
            val fragment = fragments.optJSONObject(i) ?: continue
            val index = fragment.optInt("index", blocks.size.coerceAtLeast(1) - 1)
            val block = blocks.firstOrNull { it.optInt("index", -1) == index && it.optString("type") == fragment.optString("type") }
                ?: JSONObject().put("index", index).also { blocks.add(it) }
            for (key in fragment.keys()) {
                val value = fragment.get(key)
                if (value == JSONObject.NULL) continue
                if (value is String && key in GROWING) block.put(key, block.optString(key) + value) else block.put(key, value)
            }
        }
    }

    private val GROWING = setOf("text", "summary", "data")

    /**
     * One whole answer in the shape of a plain (not streamed) response: what a module's AI
     * function needs. It is read as a stream all the same, so an answer that takes minutes is
     * not cut off on the way; `"stream": false` in the request asks for the plain call instead.
     */
    fun complete(target: Models.Target, request: JSONObject): JSONObject {
        if (target.baseUrl.startsWith(LocalLlm.BASE)) {
            val text = LocalLlm.generate(target.model, request.optJSONArray("messages") ?: JSONArray(), JSONArray(), target.options.optInt("context", 0), { false }) {}.text
            return JSONObject().put(
                "choices",
                JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("content", text))),
            )
        }
        val body = JSONObject(request.toString()).put("model", target.model)
        if (!body.has("reasoning") && !body.has("reasoning_effort")) effort(target, body)
        if (body.optBoolean("stream", true)) {
            body.remove("stream")
            val thought = StringBuilder()
            val reply = read(target, body, { false }, {}, { thought.append(it) }, {})
            val message = JSONObject().put("role", "assistant").put("content", reply.text)
            if (thought.isNotEmpty()) message.put("reasoning_content", thought.toString())
            if (reply.calls.isNotEmpty()) {
                message.put("tool_calls", JSONArray(reply.calls.map { call ->
                    JSONObject().put("id", call.id).put("type", "function")
                        .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments))
                }))
            }
            return JSONObject().put("model", target.model)
                .put("choices", JSONArray().put(JSONObject().put("index", 0).put("message", message)))
                .apply { reply.usage?.let { put("usage", it) } }
        }
        if (target.openRouter) body.put("usage", JSONObject().put("include", true))
        try {
            client.newCall(post(target, "/chat/completions", body)).execute().use { response ->
                val text = response.body?.string()
                if (!response.isSuccessful) throw LlmError("Модель ответила ошибкой ${response.code}: " + errorText(text))
                val parsed = runCatching { JSONObject(text.orEmpty()) }.getOrNull() ?: throw LlmError("Непонятный ответ модели")
                parsed.optJSONObject("error")?.let { throw LlmError("Ошибка модели: " + it.optString("message", it.toString())) }
                return parsed
            }
        } catch (error: IOException) {
            throw LlmError("Нет связи с моделью: ${error.message}")
        }
    }

    /** Vectors of meaning for the texts (`POST /embeddings`), one per text. */
    fun embed(target: Models.Target, texts: JSONArray): JSONArray {
        if (target.baseUrl.startsWith(LocalLlm.BASE)) return LocalLlm.embed(target.model, texts)
        val body = JSONObject().put("model", target.model).put("input", texts)
        try {
            client.newCall(post(target, "/embeddings", body)).execute().use { response ->
                val text = response.body?.string()
                if (!response.isSuccessful) throw LlmError("Модель эмбеддингов ответила ошибкой ${response.code}: " + errorText(text))
                val data = JSONObject(text.orEmpty()).getJSONArray("data")
                val out = JSONArray()
                for (i in 0 until data.length()) out.put(data.getJSONObject(i).getJSONArray("embedding"))
                return out
            }
        } catch (error: IOException) {
            throw LlmError("Нет связи с моделью: ${error.message}")
        }
    }

    private fun errorText(body: String?): String {
        if (body.isNullOrBlank()) return "без описания"
        val parsed = runCatching { JSONObject(body) }.getOrNull() ?: return body.take(500)
        val error = parsed.optJSONObject("error")
        return (error?.optString("message") ?: parsed.optString("message")).ifBlank { body.take(500) }
    }
}
