package app.assi.agent

import app.assi.App
import app.assi.core.Pages
import app.assi.core.Vault
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.tool
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Language models that run on the phone itself (Google's LiteRT-LM runtime): free, private,
 * working without a network. To the rest of the app this is one more provider, "На устройстве":
 * its models are assigned to roles in Settings like any others, and they take tools the same
 * way cloud models do — how well a small model uses them is another matter.
 *
 * The catalogue is a starting point, not a boundary: any model in the `.litertlm` format can be
 * added by its address, because models come out faster than app updates.
 */
object LocalLlm {
    const val PROVIDER = "local"
    const val BASE = "local:"
    private const val CUSTOM = "local.custom"

    class Entry(
        val id: String,
        val title: String,
        val about: String,
        val url: String,
        val megabytes: Int,
        /** Gives vectors of meaning rather than text: for the role "embed". */
        val embedding: Boolean = false,
    ) {
        val file: String get() = url.substringAfterLast('/').substringBefore('?')
    }

    private fun hub(repo: String, file: String) = "https://huggingface.co/litert-community/$repo/resolve/main/$file"

    /** Checked against the hub on 2026-11-11: all download without registration. */
    private val builtin = listOf(
        Entry(
            "gemma-4-e2b", "Gemma 4 · E2B", "Google, март 2026. Самая скачиваемая модель для телефонов",
            hub("gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it.litertlm"), 2468,
        ),
        Entry(
            "gemma-4-e4b", "Gemma 4 · E4B", "Старшая версия: способнее, но просит больше памяти",
            hub("gemma-4-E4B-it-litert-lm", "gemma-4-E4B-it.litertlm"), 3490,
        ),
        Entry(
            "qwen3.5-2b", "Qwen 3.5 · 2B", "Alibaba, август 2026",
            hub("Qwen3.5-2B", "Qwen3.5-2B_int8.litertlm"), 2022,
        ),
        Entry(
            "qwen3.5-4b", "Qwen 3.5 · 4B", "Alibaba, август 2026. Самая крупная в списке",
            hub("Qwen3.5-4B", "Qwen3.5-4B_mixed_int4.litertlm"), 2627,
        ),
        Entry(
            "lfm3-1.7b", "LFM3 · 1,7B", "Liquid AI, октябрь 2026. Среди языков заявлен русский; лицензия своя",
            hub("LFM3-1.7B-LiteRT", "LFM3-1.7B_mixed_int4_ekv4096.litertlm"), 1375,
        ),
        Entry(
            "qwen3.5-0.8b", "Qwen 3.5 · 0,8B", "Самая лёгкая из свежих: быстрые простые задачи",
            hub("Qwen3.5-0.8B", "Qwen3.5-0.8B_int8.litertlm"), 920,
        ),
        Entry(
            "qwen3-0.6b", "Qwen 3 · 0,6B", "Совсем маленькая: для слабого телефона и проверки, что всё работает",
            hub("Qwen3-0.6B-int4", "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm"), 331,
        ),
        Entry(
            "embeddinggemma-2", "EmbeddingGemma 2", "Не отвечает, а сравнивает тексты по смыслу: для поиска в памяти без сети",
            hub("embeddinggemma-2-text-270m-litert-lm", "embeddinggemma-2-text-270m.litertlm"), 157, embedding = true,
        ),
    )

    /** Models the owner added by address. */
    private fun custom(): List<Entry> = runCatching {
        val saved = JSONArray(App.prefs.getString(CUSTOM, "[]"))
        (0 until saved.length()).map { saved.getJSONObject(it) }.map {
            Entry(it.getString("id"), it.getString("title"), "Добавлена по ссылке", it.getString("url"), 0, it.optBoolean("embedding"))
        }
    }.getOrDefault(emptyList())

    private fun catalog(): List<Entry> = builtin + custom()

    private val dir: File get() = File(App.instance.filesDir, "models/llm").apply { mkdirs() }
    private val downloading = HashSet<String>()

    private fun entry(id: String): Entry = catalog().firstOrNull { it.id == id } ?: throw LlmError("Нет локальной модели «$id»")
    private fun file(entry: Entry): File = File(dir, entry.file)

    /** `[{id, title, about, megabytes, installed, downloading, embedding, custom}]` */
    fun status(): JSONArray = JSONArray(
        catalog().map {
            JSONObject().put("id", it.id).put("title", it.title).put("about", it.about).put("megabytes", it.megabytes)
                .put("installed", file(it).isFile).put("downloading", synchronized(downloading) { it.id in downloading })
                .put("embedding", it.embedding).put("custom", builtin.none { known -> known.id == it.id })
                .put("context", if (it.embedding) 0 else limit(it.id))
        },
    )

    /** Adds a model by the address of its `.litertlm` file and starts downloading it. */
    fun add(url: String, embedding: Boolean): String {
        val address = url.trim().replace("/blob/", "/resolve/")
        if (!address.substringBefore('?').endsWith(".litertlm")) throw LlmError("Нужна ссылка на файл модели в формате .litertlm")
        val name = address.substringAfterLast('/').substringBefore('?').removeSuffix(".litertlm")
        val id = name.lowercase().replace(Regex("[^a-z0-9.]+"), "-").trim('-')
        if (catalog().none { it.id == id }) {
            val saved = runCatching { JSONArray(App.prefs.getString(CUSTOM, "[]")) }.getOrElse { JSONArray() }
            saved.put(JSONObject().put("id", id).put("title", name).put("url", address).put("embedding", embedding))
            App.prefs.edit().putString(CUSTOM, saved.toString()).apply()
        }
        return id
    }

    // ---- downloading --------------------------------------------------------------------------

    private fun emit(data: JSONObject) = Pages.broadcast("local.download", data.toString())

    fun download(id: String) {
        val entry = entry(id)
        if (file(entry).isFile || !synchronized(downloading) { downloading.add(id) }) return
        try {
            val request = Request.Builder().url(entry.url).apply {
                Vault.get("hf.token")?.let { header("Authorization", "Bearer $it") }
            }.build()
            val partial = File(dir, entry.file + ".part")
            Llm.client.newCall(request).execute().use { response ->
                val body = response.body
                if (response.code == 401 || response.code == 403) {
                    throw LlmError("Эта модель выдаётся только по токену Hugging Face: примите её лицензию на сайте и сохраните токен в секрете hf.token.")
                }
                if (!response.isSuccessful || body == null) throw LlmError("Не удалось скачать модель: HTTP ${response.code}")
                val total = body.contentLength()
                if (total > 0 && dir.usableSpace < total + 200L * 1024 * 1024) {
                    throw LlmError("Не хватает места: модели нужно ${total / 1048576} МБ, свободно ${dir.usableSpace / 1048576} МБ.")
                }
                var done = 0L
                var reported = 0L
                partial.outputStream().use { out ->
                    val buffer = ByteArray(512 * 1024)
                    val input = body.byteStream()
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        if (System.currentTimeMillis() - reported > 500) {
                            reported = System.currentTimeMillis()
                            emit(JSONObject().put("id", id).put("done", done).put("total", total))
                        }
                    }
                }
            }
            if (!partial.renameTo(file(entry))) throw LlmError("Не удалось сохранить модель")
            provide()
            emit(JSONObject().put("id", id).put("ready", true))
        } catch (error: Exception) {
            File(dir, entry.file + ".part").delete()
            emit(JSONObject().put("id", id).put("error", error.message ?: error.toString()))
            throw error
        } finally {
            synchronized(downloading) { downloading.remove(id) }
        }
    }

    fun remove(id: String) {
        unload()
        file(entry(id)).delete()
        if (builtin.none { it.id == id }) {
            val saved = runCatching { JSONArray(App.prefs.getString(CUSTOM, "[]")) }.getOrElse { JSONArray() }
            val kept = JSONArray((0 until saved.length()).map { saved.getJSONObject(it) }.filter { it.getString("id") != id })
            App.prefs.edit().putString(CUSTOM, kept.toString()).apply()
        }
    }

    /** Makes sure the provider "На устройстве" is in the list, so its models can be assigned. */
    private fun provide() {
        val providers = Models.providers()
        for (i in 0 until providers.length()) if (providers.getJSONObject(i).optString("id") == PROVIDER) return
        providers.put(JSONObject().put("id", PROVIDER).put("name", "На устройстве").put("baseUrl", BASE).put("key", ""))
        Models.setProviders(providers)
    }

    // ---- engines ------------------------------------------------------------------------------

    private var loaded: Pair<String, Engine>? = null
    private var embedder: Pair<String, EmbeddingEngine>? = null
    private var lastUse = 0L

    /** How many tokens the loaded model holds at once: the prompt, the conversation and the answer together. */
    @Volatile
    var window = DEFAULT_WINDOW
        private set

    /** The runtime's own window: what a model gets when it refuses a wider one. */
    const val DEFAULT_WINDOW = 4096

    /**
     * The window a model gets when the owner set none. Model files name a modest one (4096 for
     * Gemma 4 and Qwen 3.5), but the runtime takes much wider — tried on a phone up to 32768 —
     * at the price of memory and of a longer start. This is the middle: room for a real
     * conversation, a start within seconds.
     */
    const val ROOMY_WINDOW = 16384

    /** The window for the model: the owner's [context] when set, else the roomy one or what the file names, whichever is wider. */
    fun windowFor(model: String, context: Int): Int = context.takeIf { it > 0 } ?: maxOf(limit(model), ROOMY_WINDOW)

    private val limits = HashMap<String, Int>()

    /** The most tokens the model allows at once, as its file says; 0 when it does not say or is not downloaded. */
    fun limit(model: String): Int {
        val entry = catalog().firstOrNull { it.id == model } ?: return 0
        val path = file(entry).takeIf { it.isFile }?.path ?: return 0
        return synchronized(limits) {
            limits.getOrPut(path) { runCatching { ModelInfo.from(path).use { it.maxContextTokens() } }.getOrDefault(0) }
        }
    }

    /** Left free for the answer. */
    private const val ANSWER_ROOM = 1024

    /** A cautious guess of the tokens in a text: Russian takes more tokens per letter than English. */
    private fun tokens(text: String): Int = text.length / 2 + 4

    private fun present(entry: Entry): String {
        if (!file(entry).isFile) throw LlmError("Локальная модель «${entry.title}» не скачана. Откройте «Настройки», раздел «Модели на устройстве».")
        return file(entry).path
    }

    /** [context] — the window the owner wants for this model, in tokens; 0 for all the model allows. */
    @Synchronized
    private fun engine(entry: Entry, context: Int): Engine {
        val path = present(entry)
        val wanted = windowFor(entry.id, context)
        val backend = App.prefs.getString("local.backend", "cpu").orEmpty()
        val key = "${entry.id}/$wanted/$backend"
        loaded?.let { (made, engine) ->
            if (made == key) return engine
            drop()
            engine.close()
            loaded = null
        }
        fun open(tokens: Int?): Engine =
            Engine(
                EngineConfig(
                    modelPath = path, backend = if (backend == "gpu") Backend.GPU() else Backend.CPU(),
                    maxNumTokens = tokens, cacheDir = App.instance.cacheDir.path,
                ),
            ).also { it.initialize() }
        // Loading takes from seconds to minutes: the chat says what the wait is for.
        Pages.broadcast("local.state", JSONObject().put("loading", true).put("model", entry.title).put("window", wanted).toString())
        val engine = try {
            open(wanted).also { window = wanted }
        } catch (failure: Exception) {
            // The model would not start with this window: it starts with the runtime's own, and says so.
            runCatching { open(null) }.getOrElse {
                Pages.broadcast("local.state", JSONObject().put("loading", false).toString())
                throw LlmError("Модель «${entry.title}» не запустилась: ${failure.message}")
            }
                .also {
                    window = DEFAULT_WINDOW
                    Pages.broadcast("local.note", JSONObject.quote("Модель «${entry.title}» не приняла окно в $wanted токенов и запущена с окном $DEFAULT_WINDOW."))
                }
        }
        Pages.broadcast("local.state", JSONObject().put("loading", false).put("model", entry.title).put("window", window).toString())
        loaded = key to engine
        idleWatch()
        return engine
    }

    /** A model holds a lot of memory: it is let go after a few idle minutes. */
    private fun idleWatch() {
        Thread({
            while (true) {
                Thread.sleep(60_000)
                synchronized(this) {
                    if (loaded == null && embedder == null) return@Thread
                    if (System.currentTimeMillis() - lastUse > 5 * 60_000) {
                        unload()
                        return@Thread
                    }
                }
            }
        }, "assi-local-idle").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun unload() {
        drop()
        runCatching { loaded?.second?.close() }
        runCatching { embedder?.second?.close() }
        loaded = null
        embedder = null
    }

    // ---- answering ----------------------------------------------------------------------------

    private fun plain(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { plain(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { plain(value.get(it)) }
        JSONObject.NULL -> null
        else -> value
    }

    /** A message's content for the model: its text, and its pictures as pictures. */
    private fun contents(message: JSONObject): Contents {
        val content = message.opt("content")
        if (content is JSONArray) {
            val parts = ArrayList<Content>()
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                when (part.optString("type")) {
                    "text" -> parts.add(Content.Text(part.optString("text")))
                    "image_url" -> {
                        val data = part.optJSONObject("image_url")?.optString("url").orEmpty()
                        if (data.startsWith("data:")) {
                            parts.add(Content.ImageBytes(android.util.Base64.decode(data.substringAfter("base64,"), android.util.Base64.DEFAULT)))
                        }
                    }
                }
            }
            return Contents.of(parts)
        }
        return Contents.of(if (content is String) content else "")
    }

    /**
     * The conversation the model is in the middle of. Reading a prompt is the slow part on a
     * phone, so a request that only continues the previous one — the agent's next step with the
     * results of its tools, or the owner's next message — is added to the conversation already
     * in the model's memory instead of being read from the start.
     */
    private class Live(val key: String, val conversation: com.google.ai.edge.litertlm.Conversation, val prints: List<Int>)

    private var live: Live? = null

    private fun drop() {
        runCatching { live?.conversation?.close() }
        live = null
    }

    /**
     * Answers the conversation, which arrives in the same shape a cloud provider gets: the
     * messages so far and the tools on offer. Text comes through [onText] as it is written;
     * tool calls come back for the app's own loop to carry out. [context] is the window the
     * owner set for this model, 0 for all it allows.
     */
    @Synchronized
    fun generate(
        model: String, messages: JSONArray, tools: JSONArray, context: Int, cancelled: () -> Boolean, onText: (String) -> Unit,
    ): Llm.Reply {
        val entry = entry(model)
        if (entry.embedding) throw LlmError("«${entry.title}» сравнивает тексты, а не отвечает: назначьте её роли «Поиск по смыслу в памяти».")
        val before = loaded?.second
        val engine = engine(entry, context)
        if (engine !== before) live = null
        lastUse = System.currentTimeMillis()

        val all = (0 until messages.length()).map { messages.getJSONObject(it) }
        val system = all.filter { it.optString("role") == "system" }
            .joinToString("\n") { message -> contents(message).contents.filterIsInstance<Content.Text>().joinToString("\n") { it.text } }.trim()
        val wire = all.filter { it.optString("role") != "system" }
        if (wire.isEmpty()) throw LlmError("Пустой запрос к локальной модели")
        val prints = wire.map { it.toString().hashCode() }

        // Which tool each result answers: the model is told by name.
        val callNames = HashMap<String, String>()
        for (message in wire) {
            val calls = message.optJSONArray("tool_calls") ?: continue
            for (j in 0 until calls.length()) {
                callNames[calls.getJSONObject(j).optString("id")] = calls.getJSONObject(j).getJSONObject("function").getString("name")
            }
        }
        fun result(message: JSONObject) = Content.ToolResponse(callNames[message.optString("tool_call_id")] ?: "tool", message.optString("content"))
        fun turn(message: JSONObject): Message = when (message.optString("role")) {
            "assistant" -> {
                val calls = ArrayList<ToolCall>()
                message.optJSONArray("tool_calls")?.let { list ->
                    for (j in 0 until list.length()) {
                        val function = list.getJSONObject(j).getJSONObject("function")
                        @Suppress("UNCHECKED_CAST")
                        val arguments = runCatching { plain(JSONObject(function.optString("arguments", "{}"))) as Map<String, Any?> }
                            .getOrDefault(emptyMap())
                        calls.add(ToolCall(function.getString("name"), arguments))
                    }
                }
                Message.model(contents = contents(message), toolCalls = calls)
            }
            "tool" -> Message.tool(Contents.of(listOf(result(message))))
            else -> Message.user(contents(message))
        }
        fun size(message: JSONObject): Int = tokens(message.opt("content")?.toString().orEmpty() + message.optJSONArray("tool_calls")?.toString().orEmpty())

        val key = "${loaded?.first}|${system.hashCode()}|${tools.toString().hashCode()}"
        var conversation: com.google.ai.edge.litertlm.Conversation? = null
        var next: Message? = null

        // Does this request only continue the one before it? Then: what the model has not seen yet.
        live?.let { held ->
            val seen = held.prints.size
            val continues = held.key == key && wire.size >= seen + 2 && prints.subList(0, seen) == held.prints &&
                wire[seen].optString("role") == "assistant"
            if (!continues) return@let
            val fresh = wire.drop(seen + 1)
            val message = when {
                fresh.all { it.optString("role") == "tool" } -> Message.tool(Contents.of(fresh.map { result(it) }))
                fresh.size == 1 && fresh[0].optString("role") == "user" -> Message.user(contents(fresh[0]))
                else -> null
            }
            val taken = runCatching { held.conversation.getTokenCount() }.getOrDefault(0)
            if (message != null && taken > 0 && taken + fresh.sumOf { size(it) } < window - ANSWER_ROOM) {
                conversation = held.conversation
                next = message
            }
        }

        if (conversation == null) {
            drop()
            // The instruction and the tools come first, then as much of the conversation as fits,
            // the latest of it; the rest is left out, oldest first. (The agent folds its
            // conversation into a summary long before this; here is the last line of defence.)
            val fixed = tokens(system) + tokens(tools.toString())
            val room = window - ANSWER_ROOM - fixed
            if (room < size(wire.last())) {
                throw LlmError(
                    "Запрос не помещается в окно модели «${entry.title}» ($window токенов): инструкция агента и инструменты занимают около $fixed. " +
                        "Увеличьте окно этой модели в настройках («Кто на какой модели»), сократите инструкцию агента или дайте ему меньше инструментов.",
                )
            }
            val sizes = wire.map { size(it) }
            var spent = sizes.sum()
            var skip = 0
            while (spent > room && skip < wire.size - 1) {
                spent -= sizes[skip]
                skip++
            }
            // A conversation cannot open with the result of a tool whose call was left out.
            while (skip < wire.size - 1 && wire[skip].optString("role") != "user") skip++
            val kept = wire.drop(skip).map { turn(it) }

            val offered = (0 until tools.length()).map { index ->
                val description = tools.getJSONObject(index).getJSONObject("function").toString()
                tool(
                    object : OpenApiTool {
                        override fun getToolDescriptionJsonString(): String = description

                        // Never called: the app's own loop carries tools out, so that they are shown and can be refused.
                        override fun execute(paramsJsonString: String): String = ""
                    },
                )
            }
            conversation = engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(system),
                    initialMessages = kept.dropLast(1),
                    tools = offered,
                    automaticToolCalling = false,
                ),
            )
            next = kept.last()
        }

        val talk = conversation!!
        var finished = false
        try {
            val text = StringBuilder()
            val calls = ArrayList<Llm.ToolCall>()
            val done = CountDownLatch(1)
            var failure: Throwable? = null
            talk.sendMessageAsync(
                next!!,
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        val piece = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                        if (piece.isNotEmpty()) {
                            text.append(piece)
                            onText(piece)
                        }
                        for (call in message.toolCalls) {
                            calls.add(Llm.ToolCall("local_${System.nanoTime()}_${calls.size}", call.name, JSONObject(call.arguments).toString()))
                        }
                    }

                    override fun onDone() = done.countDown()

                    override fun onError(throwable: Throwable) {
                        failure = throwable
                        done.countDown()
                    }
                },
            )
            var stopped = false
            while (!done.await(200, TimeUnit.MILLISECONDS)) {
                if (cancelled()) {
                    stopped = true
                    talk.cancelProcess()
                    done.await(5, TimeUnit.SECONDS)
                    break
                }
            }
            lastUse = System.currentTimeMillis()
            failure?.let { if (!stopped) throw LlmError("Локальная модель не справилась: ${it.message}") }
            // Only an answer given in full leaves the model where the next request can pick up.
            finished = !stopped && failure == null && (text.isNotBlank() || calls.isNotEmpty())
            // How full the window is, by the model's own count.
            val taken = runCatching { talk.getTokenCount() }.getOrDefault(0)
            val usage = if (taken > 0) JSONObject().put("prompt_tokens", taken).put("completion_tokens", 0) else null
            return Llm.Reply(text.toString().trim(), calls, null, usage)
        } finally {
            if (finished) {
                live = Live(key, talk, prints)
            } else {
                runCatching { talk.close() }
                live = null
            }
        }
    }

    /** Vectors of meaning for the texts, from an embedding model on the phone. */
    @Synchronized
    fun embed(model: String, texts: JSONArray): JSONArray {
        val entry = entry(model)
        val engine = embedder?.takeIf { it.first == entry.id }?.second ?: run {
            runCatching { embedder?.second?.close() }
            EmbeddingEngine(EmbeddingEngineConfig(present(entry), Backend.CPU())).also {
                it.initialize()
                embedder = entry.id to it
                idleWatch()
            }
        }
        lastUse = System.currentTimeMillis()
        val out = JSONArray()
        for (i in 0 until texts.length()) {
            val vector = engine.computeEmbedding(listOf(InputData.Text(texts.getString(i)))).embedding
            out.put(JSONArray(vector.map { it.toDouble() }))
        }
        return out
    }
}
