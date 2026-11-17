package app.assi.agent

import android.os.Build
import app.assi.App
import app.assi.MainActivity
import app.assi.core.Notify
import app.assi.core.Engine
import app.assi.core.Pages
import app.assi.core.Workspace
import app.assi.voice.Speaker
import app.assi.voice.Tts
import app.assi.store.StoreAgent
import okhttp3.Call
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** One conversation of one agent with the model, and the loop that drives it. */
class Conversation(
    val id: String,
    private val lasting: Boolean,
    private val parent: Conversation?,
    /** Which of the agent's chats this is; empty for a one-off task. */
    val thread: String = "",
    /** For a piece of work handed out by another conversation: what it is called there. */
    private val label: String? = null,
) {
    val depth: Int = (parent?.depth ?: 0) + 1

    @Volatile
    var busy = false
        private set

    @Volatile
    private var stopping = false

    @Volatile
    private var call: Call? = null

    /** Conversations this one handed work to and waits for. */
    private val children = java.util.concurrent.CopyOnWriteArrayList<Conversation>()

    /**
     * A message the owner sent while a turn was running. One that [steer]s goes to the model
     * between two steps of the work in hand; the others wait for the work to end and are taken
     * up in order, each as a request of its own.
     */
    private class Waiting(val id: String, val text: String, val attached: List<Attach>, val where: String?, @Volatile var steer: Boolean)

    private val queue = ArrayList<Waiting>()

    /** The model the owner picked for this chat alone (see [Models.resolve]), or null: the agent's usual one. */
    @Volatile
    private var own: JSONObject? = null

    /** What this conversation runs on: its own choice, or that of the chat that handed it a part of its work. */
    private val chosen: JSONObject? get() = own ?: parent?.takeIf { it.id == id }?.chosen

    /** This turn among the tasks the owner sees on the "Задачи" screen. */
    @Volatile
    private var task: Tasks.Entry? = null

    /** The tool call being carried out: what the work it hands out reports under. */
    @Volatile
    private var running: String? = null

    /** The draft this conversation writes into instead of the app itself, or null. */
    @Volatile
    private var draft: String? = null

    /** Something the model should learn with the owner's next message: what became of a draft. */
    @Volatile
    private var aside: String? = null

    /** The owner's chat this work belongs to, however deep it was handed down. */
    private val home: Conversation get() = parent?.home ?: this

    /** The plan of the work in hand, when the agent drew one up: `{title, state, steps: [{text, status, note}]}`. */
    @Volatile
    private var plan: JSONObject? = null

    /** Tool calls made since the plan's steps were last marked: a model deep in the work forgets to. */
    private var unmarked = 0

    private val cancelled: Boolean get() = stopping || parent?.cancelled == true

    /** For a tool that waits on something long: the owner stopped the work. */
    val halted: Boolean get() = cancelled

    /** The conversation as the API takes it, without the system message. */
    private val messages = ArrayList<JSONObject>()

    /** How each tool call ended, for redrawing the chat: id → {ok, summary}. */
    private val outcomes = JSONObject()

    /**
     * What the chat shows between messages but the model never sees: `{at, type, ...}`, where
     * `at` is the number of messages before it. Today: the files a turn changed.
     */
    private val marks = ArrayList<JSONObject>()

    private val file get() = Threads.file(id, thread)

    /** The answer being written right now, not yet a message: a chat opened in the middle of it shows it too. */
    private val writing = StringBuffer()

    private val worker by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread(null, task, "assi-agent-$id", 16L * 1024 * 1024).apply { isDaemon = true }
        }
    }

    init {
        if (lasting) runCatching {
            val saved = JSONObject(file.readText())
            val list = saved.getJSONArray("messages")
            for (i in 0 until list.length()) messages.add(list.getJSONObject(i))
            saved.optJSONObject("outcomes")?.let { stored -> stored.keys().forEach { outcomes.put(it, stored.get(it)) } }
            saved.optJSONArray("marks")?.let { stored -> for (i in 0 until stored.length()) marks.add(stored.getJSONObject(i)) }
            plan = saved.optJSONObject("plan")
            own = saved.optJSONObject("model")
            draft = saved.optString("draft").takeIf { app.assi.core.Drafts.exists(it) }
        }
    }

    // ---- what the chat calls --------------------------------------------------------------

    /**
     * The owner's message. [attached] goes along with it (a captured screen, pictures, files);
     * [where] is the screen of the app the owner is looking at, so "this screen" has a meaning.
     */
    fun send(text: String, attached: List<Attach> = emptyList(), where: String? = null, steer: Boolean? = null): Boolean {
        if (text.isBlank()) return false
        val waits = synchronized(queue) {
            if (busy) {
                // The agent is at work: the message goes into the work in hand between two of its
                // steps, or waits for it to end — as the owner said, or else as the setting says.
                queue.add(Waiting(java.lang.Long.toString(System.nanoTime(), 36), text.trim(), attached, where, steer ?: steersByDefault))
            } else {
                busy = true
            }
            busy && queue.isNotEmpty()
        }
        if (waits) {
            emitQueue()
            return true
        }
        stopping = false
        Tts.stop()
        worker.execute { turn(text.trim(), attached, where) }
        return true
    }

    fun once(task: String): String {
        busy = true
        parent?.children?.add(this)
        try {
            return turn(task)
        } finally {
            parent?.children?.remove(this)
        }
    }

    fun stop(): Boolean {
        stopping = true
        synchronized(queue) { queue.clear() }
        emitQueue()
        call?.cancel()
        children.forEach { it.stop() }
        if (lasting) Tts.stop()
        return true
    }

    // ---- what waits while the agent works ---------------------------------------------------------

    /** `[{id, text, steer, attached}]` — the owner's messages that have not reached the model yet. */
    fun waiting(): JSONArray = synchronized(queue) {
        JSONArray(queue.map { JSONObject().put("id", it.id).put("text", it.text).put("steer", it.steer).put("attached", it.attached.size) })
    }

    private fun emitQueue() {
        if (lasting) emit(JSONObject().put("type", "queue").put("items", waiting()))
    }

    /** Takes a waiting message back: its text returns to the owner, what was attached — to the inbox. */
    fun unqueue(item: String): JSONObject? {
        val taken = synchronized(queue) { queue.firstOrNull { it.id == item }?.also { queue.remove(it) } } ?: return null
        taken.attached.forEach { Inbox.add(it) }
        emitQueue()
        return JSONObject().put("text", taken.text).put("attached", taken.attached.size)
    }

    /** Says how a waiting message is to reach the model: between steps of the work in hand, or after it. */
    fun requeue(item: String, steer: Boolean): Boolean {
        val found = synchronized(queue) { queue.firstOrNull { it.id == item }?.also { it.steer = steer } } != null
        if (found) emitQueue()
        return found
    }

    /**
     * Between two steps of the work: what the owner said meanwhile goes to the model as their next
     * message, so the work turns where they point without being stopped and started again.
     */
    private fun steered(target: Models.Target) {
        val said = synchronized(queue) { queue.filter { it.steer }.also { queue.removeAll(it.toSet()) } }
        if (said.isEmpty()) return
        for (item in said) {
            append(
                JSONObject().put("role", "user")
                    .put("content", item.text + STEER_NOTE + (item.where?.takeIf { it.isNotBlank() }?.let { "\n\n$WHERE_OPEN$it]" } ?: "")),
            )
            emit(JSONObject().put("type", "user").put("text", item.text).put("steer", true))
            attach(item.attached, target)
        }
        emitQueue()
        save()
    }

    // ---- the model of this chat --------------------------------------------------------------------

    /** Where this conversation's requests go now. */
    fun target(): Models.Target = Models.resolve(Models.roleOf(id), chosen = chosen)

    /** `{own, provider, model, effort, vision, window}` — what the chat runs on, and what of it is its own choice. */
    fun model(): JSONObject {
        val out = JSONObject().put("own", own ?: JSONObject.NULL)
        runCatching { target() }
            .onSuccess { out.put("provider", it.provider).put("model", it.model).put("effort", it.effort).put("vision", it.vision).put("window", it.window) }
            .onFailure { out.put("error", it.message.orEmpty()) }
        return out
    }

    /**
     * The owner's choice for this chat: `{provider, model, vision?, effort?}` for another model,
     * `{effort}` for the usual model thinking harder or less, null for the usual one as it is.
     * Work in hand takes it up with its next request.
     */
    fun setModel(value: JSONObject?): JSONObject {
        own = value?.takeIf { it.length() > 0 }
        save()
        return model().also { emit(JSONObject().put("type", "model").put("model", it)) }
    }

    // ---- questions to the owner -----------------------------------------------------------------

    private class Asked(val id: String, val form: JSONObject, val kind: String = "ask") {
        val done = java.util.concurrent.CountDownLatch(1)

        @Volatile
        var answer: String? = null
    }

    @Volatile
    private var asked: Asked? = null

    /**
     * Shows the owner a form of questions in the chat — choices to tick and a line for an answer
     * of their own — and waits for the answers, however long that takes.
     */
    fun ask(form: JSONObject): String {
        if (!lasting) return "Спросить владельца можно только в его чате, а это поручение другого агента: решите сами или верните вопрос в своём ответе."
        val questions = form.optJSONArray("questions")?.takeIf { it.length() > 0 }
            ?: throw IllegalArgumentException("Нужен хотя бы один вопрос в questions")
        val pending = Asked("ask" + System.nanoTime(), JSONObject().put("title", form.optString("title")).put("questions", questions))
        asked = pending
        emit(JSONObject().put("type", "ask").put("id", pending.id).put("form", pending.form))
        if (!MainActivity.visible) runCatching { Notify.answer(id, thread, Agent.spec(id).title, "Нужен ваш ответ, чтобы продолжить.") }
        while (!pending.done.await(400, java.util.concurrent.TimeUnit.MILLISECONDS)) if (cancelled) break
        asked = null
        emit(JSONObject().put("type", "asked").put("id", pending.id))
        return pending.answer ?: NO_ANSWER
    }

    /** The owner filled the form in. False when the question is no longer waiting. */
    fun answer(ask: String, text: String): Boolean {
        val pending = asked?.takeIf { it.id == ask } ?: return false
        pending.answer = text
        pending.done.countDown()
        return true
    }

    // ---- drafts -----------------------------------------------------------------------------------

    /** From now on this conversation's changes go to a draft: the app stays as it is until the owner takes it. */
    fun draftBegin(args: JSONObject): String {
        draft?.takeIf { app.assi.core.Drafts.exists(it) }?.let { known ->
            args.optString("open").takeIf { it.isNotBlank() }?.let { app.assi.core.Drafts.setOpen(known, it) }
            return "Черновик уже открыт: продолжайте в нём."
        }
        val made = app.assi.core.Drafts.create(args.optString("title").ifBlank { label ?: "Черновик" }, home.id, home.thread, args.optString("open"))
        draft = made
        return "Черновик открыт. Ваши правки файлов теперь идут в него, а приложение у владельца не меняется, пока он не примет результат. " +
            "Работайте как обычно: ui_open и js_eval показывают и проверяют черновую версию. В ответе скажите, что посмотреть, и что принять или отклонить можно в чате."
    }

    /** The owner took the draft or threw it away. */
    fun drafted(id: String, title: String, taken: Boolean) {
        if (draft == id) draft = null
        aside = if (taken) "Владелец принял черновик «$title»: его изменения теперь в приложении." else "Владелец отклонил черновик «$title»: приложение осталось прежним."
        emit(JSONObject().put("type", "drafted").put("id", id).put("taken", taken))
        save()
    }

    // ---- the plan ---------------------------------------------------------------------------------

    /** The plan of the work in hand, or null. */
    fun plan(): JSONObject? = plan

    /** Shows the owner the plan of a big piece of work and waits: a go-ahead, or what to change. */
    fun planPropose(args: JSONObject): String {
        if (!lasting) return "План показывают владельцу в его чате; в поручении просто выполняйте работу."
        val steps = args.optJSONArray("steps")?.takeIf { it.length() > 0 } ?: throw IllegalArgumentException("Нужны шаги плана в steps")
        val drawn = JSONObject().put("title", args.optString("title")).put("state", "proposed").put(
            "steps", JSONArray((0 until steps.length()).map { JSONObject().put("text", steps.optString(it)).put("status", "todo") }),
        )
        plan = drawn
        val pending = Asked("plan" + System.nanoTime(), drawn, "plan")
        asked = pending
        emit(JSONObject().put("type", "plan").put("id", pending.id).put("plan", drawn))
        if (!MainActivity.visible) runCatching { Notify.answer(id, thread, Agent.spec(id).title, "План готов: посмотрите и дайте добро.") }
        while (!pending.done.await(400, java.util.concurrent.TimeUnit.MILLISECONDS)) if (cancelled) break
        asked = null
        val verdict = pending.answer
        drawn.put("state", if (verdict == null) "dropped" else if (verdict.isBlank()) "approved" else "proposed")
        emit(JSONObject().put("type", "asked").put("id", pending.id))
        emit(JSONObject().put("type", "plan").put("plan", drawn))
        return when {
            verdict == null -> NO_ANSWER
            verdict.isBlank() -> "План принят. Выполняйте по шагам и отмечайте каждый: plan_step."
            else -> "Владелец просит изменить план: $verdict\nИсправьте план и покажите его снова (plan_propose)."
        }
    }

    /** Marks a step of the plan: the chat shows how far the work has come. */
    fun planStep(args: JSONObject): String {
        val drawn = plan ?: return "Плана нет: сначала покажите его владельцу (plan_propose)."
        val steps = drawn.getJSONArray("steps")
        val index = args.optInt("step") - 1
        if (index !in 0 until steps.length()) throw IllegalArgumentException("В плане шагов: ${steps.length()}, а отмечается ${index + 1}")
        val status = args.optString("status", "done").let { if (it in setOf("doing", "done", "skipped")) it else "done" }
        steps.getJSONObject(index).put("status", status).put("note", args.optString("note"))
        // The plan keeps up by itself: what came before a marked step is done, and after a finished
        // step the next one is the one in hand — so one mark per step is enough.
        for (i in 0 until index) steps.getJSONObject(i).let { if (it.optString("status") in OPEN) it.put("status", "done") }
        val next = (index + 1 until steps.length()).firstOrNull { steps.getJSONObject(it).optString("status") in OPEN }
        if (status != "doing" && next != null) steps.getJSONObject(next).put("status", "doing")
        unmarked = 0
        emit(JSONObject().put("type", "plan").put("plan", drawn))
        save()
        val left = (0 until steps.length()).count { steps.getJSONObject(it).optString("status") in OPEN }
        return "Шаг ${index + 1} отмечен. Осталось шагов: $left." + if (next != null && status != "doing") " Сейчас в работе шаг ${next + 1}." else ""
    }

    /** A line for the model when it has worked for a long while without marking the plan, or null. */
    private fun planReminder(tool: String): String? {
        val drawn = plan ?: return null
        if (drawn.optString("state") != "approved" || tool == "plan_step" || tool == "plan_propose") return null
        val steps = drawn.getJSONArray("steps")
        if ((0 until steps.length()).none { steps.getJSONObject(it).optString("status") in OPEN }) return null
        if (++unmarked < PLAN_REMIND_EVERY) return null
        unmarked = 0
        return "\n\n(Напоминание: владелец следит за работой по плану, а шаги давно не отмечались. Отметьте сделанное: plan_step.)"
    }

    // ---- work handed out to run side by side --------------------------------------------------------

    /**
     * Hands out several pieces of work at once. Each runs in a conversation of its own — the same
     * agent with a clean head, or another agent — and this one waits for them all.
     */
    fun parts(args: JSONObject): String {
        val list = args.optJSONArray("tasks")?.takeIf { it.length() > 0 } ?: throw IllegalArgumentException("Нужны задачи в tasks")
        if (list.length() > MAX_PARTS) throw IllegalArgumentException("За один раз не больше $MAX_PARTS частей")
        if (!Agent.mayDelegate(this)) return "Эта работа сама кому-то поручена: дальше раздавать её нельзя, сделайте сами."
        val titles = (0 until list.length()).map { list.getJSONObject(it).optString("title").ifBlank { "Часть ${it + 1}" } }
        val pool = Executors.newFixedThreadPool(minOf(list.length(), PARALLEL)) { work ->
            Thread(null, work, "assi-part", 16L * 1024 * 1024).apply { isDaemon = true }
        }
        try {
            val results = (0 until list.length()).map { index ->
                val item = list.getJSONObject(index)
                val who = item.optString("agent").ifBlank { id }
                pool.submit<String> {
                    runCatching {
                        require(Agent.exists(who)) { "Нет агента $who" }
                        Conversation(who, lasting = false, parent = this, label = titles[index]).once(PART_NOTE + item.getString("task"))
                    }.getOrElse { "Не получилось: " + (if (it is LlmError) it.message else Engine.describe(it)) }
                }
            }
            return results.mapIndexed { index, result -> "## ${titles[index]}\n" + result.get().ifBlank { "(пустой ответ)" } }.joinToString("\n\n")
        } finally {
            pool.shutdown()
        }
    }

    /** A piece of handed-out work reports how it goes: the chat shows it under the step that started it. */
    private fun part(name: String, event: JSONObject) {
        val kind = event.optString("type")
        val note = when (kind) {
            "status" -> "шаг ${event.optInt("step")}"
            "tool" -> if (event.optString("status") == "run") event.optString("name") + " " + event.optString("args") else return
            "done" -> "готово"
            "error" -> "ошибка: " + event.optString("message")
            else -> return
        }
        emit(
            JSONObject().put("type", "part").put("call", running).put("name", name).put("note", note.take(120))
                .put("state", if (kind == "done") "ok" else if (kind == "error") "error" else "run"),
        )
    }

    /** The conversation as the chat draws it. */
    fun display(): JSONArray {
        val out = JSONArray()
        synchronized(messages) {
            fun marksAt(index: Int, last: Boolean) {
                for (mark in marks) {
                    val at = mark.optInt("at")
                    if (at == index || (last && at > index)) out.put(mark)
                }
            }
            messages.forEachIndexed { index, message ->
                marksAt(index, false)
                when (message.optString("role")) {
                    "user" -> if (message.opt("content") is String) {
                        val text = message.getString("content")
                        if (text.startsWith(SUMMARY_NOTE)) {
                            out.put(JSONObject().put("type", "note").put("text", "Более ранняя часть разговора сжата в сводку"))
                        } else {
                            out.put(JSONObject().put("type", "user").put("text", spoken(text)))
                        }
                    } else {
                        val note = message.optJSONArray("content")?.optJSONObject(0)?.optString("text").orEmpty()
                        if (note.startsWith(ATTACH_OPEN)) {
                            out.put(JSONObject().put("type", "attach").put("text", note.removePrefix(ATTACH_OPEN).substringBefore(']')))
                        } else if (note.startsWith(SHOT_NOTE)) {
                            // A picture the agent asked for: kept with the conversation, shown folded.
                            val parts = message.getJSONArray("content")
                            for (i in 1 until parts.length()) {
                                parts.optJSONObject(i)?.optJSONObject("image_url")?.optString("url")?.takeIf { it.isNotEmpty() }
                                    ?.let { out.put(JSONObject().put("type", "shot").put("image", it)) }
                            }
                        }
                    }
                    "assistant" -> {
                        val text = if (message.isNull("content")) "" else message.optString("content")
                        message.optString(THOUGHT).takeIf { it.isNotBlank() }?.let { out.put(JSONObject().put("type", "thought").put("text", it)) }
                        if (text.isNotBlank()) out.put(JSONObject().put("type", "assistant").put("text", text))
                        val calls = message.optJSONArray("tool_calls")
                        if (calls != null) {
                            for (i in 0 until calls.length()) {
                                val call = calls.getJSONObject(i)
                                val function = call.getJSONObject("function")
                                val outcome = outcomes.optJSONObject(call.getString("id"))
                                out.put(
                                    JSONObject().put("type", "tool").put("id", call.getString("id"))
                                        .put("name", function.getString("name"))
                                        .put("args", Tools.summary(function.getString("name"), function.optString("arguments")))
                                        .put("status", if (outcome?.optBoolean("ok", true) == false) "error" else "ok")
                                        .put("summary", outcome?.optString("summary").orEmpty())
                                        .put("detail", outcome?.optString("detail").orEmpty()),
                                )
                                // What the owner answered to a form is shown as the owner's own words.
                                if (function.getString("name") == "ask_owner" && outcome?.optBoolean("answered") == true) {
                                    out.put(JSONObject().put("type", "user").put("text", outcome.optString("detail").ifEmpty { outcome.optString("summary") }))
                                }
                            }
                        }
                    }
                }
            }
            marksAt(messages.size, true)
        }
        // An answer in the middle of being written: the chat goes on with it from here.
        writing.toString().takeIf { it.isNotBlank() }?.let { out.put(JSONObject().put("type", "assistant").put("text", it).put("live", true)) }
        // Drafts of this chat that wait for the owner's decision.
        if (lasting) {
            val waiting = app.assi.core.Drafts.list(id, thread)
            for (i in 0 until waiting.length()) out.put(JSONObject().put("type", "draft").put("draft", waiting.getJSONObject(i)))
        }
        // A form the owner has not answered yet is still there when the chat is opened again.
        asked?.let { out.put(JSONObject().put("type", it.kind).put("id", it.id).put(if (it.kind == "plan") "plan" else "form", it.form)) }
        return out
    }

    // ---- the loop -------------------------------------------------------------------------

    /** One request of the owner (or one task of a parent agent); returns the final answer. */
    private fun turn(text: String, attached: List<Attach> = emptyList(), where: String? = null): String {
        val spec = Agent.spec(id)
        val snapshotWall = System.currentTimeMillis()
        val snapshotCpu = android.os.SystemClock.currentThreadTimeMillis()
        val snapshotApp = android.os.Process.getElapsedCpuTime()
        var before: String? = null
        var snapshot: String? = null
        var answer = ""
        var failure: String? = null
        val entry = Tasks.begin(id, thread, label ?: text, parent?.task)
        task = entry
        // The screen note rides inside the message, so the prompt before it stays cacheable.
        val said = text + (aside?.let { "\n\n[$it]" } ?: "")
        aside = null
        append(JSONObject().put("role", "user").put("content", if (where.isNullOrBlank()) said else "$said\n\n$WHERE_OPEN$where]"))
        // The owner sees their message at once: nothing the shell does before asking the model holds it back.
        emit(JSONObject().put("type", "user").put("text", text))
        // On disk from the first moment and after every step: a task the app dies under can be
        // taken up again only if what was asked and what was done by then is still there.
        save()
        try {
            if (lasting) {
                // A snapshot that cannot be taken is told to the owner like any other failure of the turn.
                before = Workspace.signature()
                snapshot = entry.cost("snapshot") { Workspace.snapshot("${spec.title}: $text") }
            }
            var target = target()
            attach(attached, target)
            var lean = lean(spec, target)
            var steps = 0
            while (!cancelled) {
                val limit = if (StoreAgent.isReview(this)) MAX_REVIEW_STEPS else stepLimit
                if (++steps > limit) {
                    answer = limitReached(spec, target, lean, limit).ifBlank { answer }
                    break
                }
                if (steps > 1) {
                    // While the last step ran the owner may have picked another model for the chat
                    // or said something: both are taken up here, between two steps.
                    target = target()
                    lean = lean(spec, target)
                    steered(target)
                }
                Usage.guard()
                val tools = Tools.schemas(StoreAgent.tools(this, spec.tools ?: if (lean) LIGHT_TOOLS else null), Agent.mayDelegate(this) && !lean, target.vision, StoreAgent.isReview(this))
                // With the voice on, the answer is spoken sentence by sentence as it is written.
                val speaker = if (lasting && (Tts.enabled || Tts.voiceTurn)) Speaker() else null
                // What the wait is for: the chat shows it with the step's number.
                emit(JSONObject().put("type", "status").put("stage", "request").put("step", steps).put("local", target.onPhone))
                Tasks.progress(entry, steps, "")
                val pondered = StringBuilder()
                val thought = { piece: String ->
                    pondered.append(piece)
                    emit(JSONObject().put("type", "thought").put("text", piece))
                }
                writing.setLength(0)
                val reply = try {
                    entry.cost("model") {
                        Llm.stream(target, request(spec, target, lean), tools, { cancelled }, { call = it }, thought) { piece ->
                            speaker?.feed(piece)
                            writing.append(piece)
                            emit(JSONObject().put("type", "delta").put("text", piece))
                        }
                    }
                } finally {
                    writing.setLength(0)
                }
                speaker?.finish()
                Usage.record(id, target.model, reply.usage)
                // What the provider counted for this request: the truest measure of how full the window is.
                used = reply.usage?.let { it.optInt("prompt_tokens") + it.optInt("completion_tokens") } ?: 0
                emit(
                    JSONObject().put("type", "usage").put("window", target.window)
                        .put("used", if (used > 0) used else synchronized(messages) { messages.sumOf { weigh(it) } } + request(spec, target, lean).getJSONObject(0).toString().length / 3),
                )
                val assistant = JSONObject().put("role", "assistant")
                    .put("content", if (reply.text.isEmpty()) JSONObject.NULL else reply.text)
                // The thinking stays with the chat for the owner to read; it is not sent back to the model.
                if (pondered.isNotBlank()) assistant.put(THOUGHT, pondered.toString().take(THOUGHT_LIMIT))
                if (reply.calls.isNotEmpty()) {
                    val calls = JSONArray()
                    for (call in reply.calls) {
                        calls.put(
                            JSONObject().put("id", call.id).put("type", "function").put(
                                "function", JSONObject().put("name", call.name).put("arguments", call.arguments),
                            ),
                        )
                    }
                    assistant.put("tool_calls", calls)
                }
                // Models that think expect their thinking back together with the tool results.
                if (reply.reasoning != null && reply.calls.isNotEmpty() && target.openRouter) {
                    assistant.put("reasoning_details", reply.reasoning)
                }
                if (reply.text.isNotEmpty() || reply.calls.isNotEmpty()) append(assistant)
                if (reply.text.isNotEmpty()) answer = reply.text
                emit(JSONObject().put("type", "message"))
                if (reply.calls.isEmpty()) {
                    // The answer is given — unless the owner said something meanwhile: then the work goes on with it.
                    if (synchronized(queue) { queue.none { it.steer } }) break
                    continue
                }

                val images = ArrayList<String>()
                for (call in reply.calls) {
                    emit(
                        JSONObject().put("type", "tool").put("id", call.id).put("name", call.name)
                            .put("args", Tools.summary(call.name, call.arguments)).put("status", "run"),
                    )
                    val offered = (0 until tools.length()).any {
                        tools.getJSONObject(it).getJSONObject("function").getString("name") == call.name
                    }
                    val seen = images.size
                    running = call.id
                    Tasks.progress(entry, steps, call.name + " " + Tools.summary(call.name, call.arguments))
                    val result = when {
                        cancelled -> Tools.Result("Отменено пользователем", ok = false)
                        // A model may ask for a tool it was not given; what it was not given it does not get.
                        !offered -> Tools.Result("Ошибка: инструмент ${call.name} вам недоступен", ok = false)
                        // In a draft the tools see the workspace through the draft's layer.
                        else -> entry.cost("tool:" + call.name) { app.assi.core.Drafts.within(draft) { Tools.run(call.name, call.arguments, this, images) } }
                    }
                    val summary = result.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(140)
                    // What the step shows when opened: what it did to a file or the code it ran — or else what
                    // the model got back from it (a file it read, a search result), so the owner sees the same.
                    val whole = result.detail ?: result.text.takeIf { it.length > summary.length }
                    val detail = whole?.let {
                        if (it.length <= DETAIL_LIMIT) it
                        else it.take(DETAIL_LIMIT) + "\n… показано $DETAIL_LIMIT из ${it.length} символов" +
                            if (result.detail == null) "; модель получила ${minOf(it.length, TOOL_OUTPUT_LIMIT)}" else ""
                    }
                    // The pictures this step took (a screenshot, a look at the phone's screen).
                    val shots = JSONArray(images.subList(seen, images.size).map { "data:image/jpeg;base64,$it" })
                    synchronized(messages) {
                        outcomes.put(
                            call.id,
                            JSONObject().put("ok", result.ok).put("summary", summary).put("detail", detail ?: result.text.takeIf { call.name == "ask_owner" })
                                .put("answered", call.name == "ask_owner" && result.ok && result.text != NO_ANSWER),
                        )
                    }
                    append(JSONObject().put("role", "tool").put("tool_call_id", call.id).put("content", clip(result.text) + planReminder(call.name).orEmpty()))
                    emit(
                        JSONObject().put("type", "tool").put("id", call.id).put("name", call.name)
                            .put("status", if (result.ok) "ok" else "error").put("summary", summary)
                            .put("detail", detail).put("images", shots),
                    )
                }
                for (image in images) {
                    append(
                        JSONObject().put("role", "user").put(
                            "content",
                            JSONArray()
                                .put(JSONObject().put("type", "text").put("text", "$SHOT_NOTE, который вы запросили:"))
                                .put(
                                    JSONObject().put("type", "image_url").put(
                                        "image_url", JSONObject().put("url", "data:image/jpeg;base64,$image"),
                                    ),
                                ),
                        ),
                    )
                }
                save()
            }
            emit(JSONObject().put("type", "done").put("stopped", cancelled))
        } catch (error: Throwable) {
            val message = if (error is LlmError) error.message.orEmpty() else Engine.describe(error)
            failure = message
            emit(JSONObject().put("type", "error").put("message", message))
            if (!lasting) throw error
        } finally {
            val finishWall = System.currentTimeMillis()
            val finishCpu = android.os.SystemClock.currentThreadTimeMillis()
            val finishApp = android.os.Process.getElapsedCpuTime()
            if (snapshot != null) {
                if (Workspace.signature() == before) {
                    Workspace.dropSnapshot(snapshot)
                } else {
                    // What this turn did to the app, shown in the chat with a way to take it back.
                    val mark = JSONObject().put("type", "changes").put("snapshot", snapshot)
                        .put("files", Workspace.changes(snapshot))
                    if (!cancelled && draft == null && !StoreAgent.isReview(this)) runCatching { mark.put("checks", selfChecks(mark.getJSONArray("files"))) }
                    synchronized(messages) { marks.add(mark.put("at", messages.size)) }
                    emit(JSONObject(mark.toString()))
                }
            }
            // A draft that this turn worked in is shown to the owner: look, take or throw away.
            draft?.takeIf { app.assi.core.Drafts.exists(it) }?.let { made ->
                home.emit(JSONObject().put("type", "draft").put("draft", JSONObject(app.assi.core.Drafts.meta(made).toString()).put("id", made).put("files", app.assi.core.Drafts.changes(made))))
            }
            compact()
            save()
            Tts.voiceTurn = false
            // Finished while the owner was elsewhere: the answer goes to the notification shade.
            if (lasting && !MainActivity.visible && answer.isNotBlank()) {
                runCatching { Notify.answer(id, thread, spec.title, Tts.speakable(answer).take(700)) }
            }
            running = null
            task = null
            entry.add("finish", System.currentTimeMillis() - finishWall, android.os.SystemClock.currentThreadTimeMillis() - finishCpu, android.os.Process.getElapsedCpuTime() - finishApp)
            // The whole turn: what it cost beyond the model and the tools is the agent's own housekeeping.
            entry.add("turn", System.currentTimeMillis() - snapshotWall, android.os.SystemClock.currentThreadTimeMillis() - snapshotCpu, android.os.Process.getElapsedCpuTime() - snapshotApp)
            Tasks.end(entry, if (failure != null) Tasks.FAILED else if (cancelled) Tasks.STOPPED else Tasks.DONE, failure ?: answer)
            // A message the owner sent meanwhile is taken up now.
            val next = synchronized(queue) {
                if (stopping || !lasting) queue.clear()
                (if (queue.isEmpty()) null else queue.removeAt(0)).also { if (it == null) busy = false }
            }
            emitQueue()
            emit(JSONObject().put("type", "idle").put("more", next != null))
            if (next != null) {
                stopping = false
                worker.execute { turn(next.text, next.attached, next.where) }
            }
        }
        return answer
    }

    /**
     * The work has taken as many steps in a row as one request may: instead of breaking off in
     * the middle, the model says in a few lines where the work stands (without tools, so this is
     * the last step for certain), and the chat offers the owner to go on.
     */
    private fun limitReached(spec: Agent.Spec, target: Models.Target, lean: Boolean, limit: Int): String {
        append(
            JSONObject().put("role", "user").put(
                "content",
                JSONArray().put(JSONObject().put("type", "text").put("text", "$LIMIT_OPEN$limit$LIMIT_REST")),
            ),
        )
        var said = ""
        runCatching {
            emit(JSONObject().put("type", "status").put("stage", "request").put("step", limit).put("local", target.onPhone))
            writing.setLength(0)
            val reply = Llm.stream(target, request(spec, target, lean), JSONArray(), { cancelled }, { call = it }) { piece ->
                writing.append(piece)
                emit(JSONObject().put("type", "delta").put("text", piece))
            }
            Usage.record(id, target.model, reply.usage)
            said = reply.text
        }
        writing.setLength(0)
        if (said.isNotBlank()) append(JSONObject().put("role", "assistant").put("content", said))
        emit(JSONObject().put("type", "message"))
        val mark = JSONObject().put("type", "limit").put("steps", limit)
        synchronized(messages) { marks.add(mark.put("at", messages.size)) }
        emit(JSONObject(mark.toString()))
        return said
    }

    /** What came along with the owner's message: each attachment follows it as a message of its own. */
    private fun attach(attached: List<Attach>, target: Models.Target) {
        for (attach in attached) {
            val parts = JSONArray().put(JSONObject().put("type", "text").put("text", "$ATTACH_OPEN${attach.label}]\n${attach.note}"))
            if (target.vision) {
                for (image in attach.images) {
                    parts.put(
                        JSONObject().put("type", "image_url")
                            .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$image")),
                    )
                }
            } else if (attach.images.isNotEmpty()) {
                parts.getJSONObject(0).put("text", parts.getJSONObject(0).getString("text") + "\n$BLIND_NOTE")
            }
            append(JSONObject().put("role", "user").put("content", parts))
            emit(JSONObject().put("type", "attach").put("text", attach.label))
        }
    }

    private fun clip(text: String): String =
        if (text.length <= TOOL_OUTPUT_LIMIT) text
        else text.take(TOOL_OUTPUT_LIMIT) + "\n… вывод обрезан (${text.length} символов)"

    private fun append(message: JSONObject) {
        synchronized(messages) { messages.add(message) }
    }

    /**
     * After a turn that changed a module, the module's own checks are run: the owner sees in the
     * chat whether what was just built still works, whatever the agent said about it.
     */
    private fun selfChecks(files: JSONArray): JSONArray {
        val modules = (0 until files.length()).map { files.getJSONObject(it).optString("path") }
            .filter { it.startsWith("modules/") }.mapNotNull { nearestChecks(it) }.distinct()
        val out = JSONArray()
        if (modules.isEmpty()) return out
        // Checks open screens: afterwards the owner is back on the screen they were looking at.
        val was = MainActivity.current?.page?.location.orEmpty()
        for (module in modules) {
            val text = runCatching { Engine.call("__toolRun", "module_check", JSONObject().put("module", module).toString(), timeoutMs = 120_000) as String? }
                .getOrElse { "НЕ ПРОШЛО: проверки не запустились — " + Engine.describe(it) }.orEmpty()
            out.put(JSONObject().put("module", module).put("ok", !text.contains("НЕ ПРОШЛО")).put("text", text))
        }
        if (was.startsWith(Pages.ORIGIN) && MainActivity.current?.page?.location != was) MainActivity.current?.open(was.removePrefix(Pages.ORIGIN + "/"))
        return out
    }

    /**
     * The checks that answer for a changed file: the nearest folder above it, within its module,
     * that has its own checks.js — `games/daily/<day>` for a file of one game, not the whole
     * `games`, whose checks play every installed game and take the phone many minutes.
     */
    private fun nearestChecks(path: String): String? {
        var folder = path.removePrefix("modules/").substringBeforeLast('/', "")
        while (folder.isNotEmpty()) {
            if (File(Workspace.root, "modules/$folder/checks.js").isFile) return folder
            folder = folder.substringBeforeLast('/', "")
        }
        return null
    }

    /** Only a lasting conversation is on screen; a delegated task reports through its tool card. */
    private fun emit(event: JSONObject) {
        if (lasting) Pages.broadcast("agent", event.put("agent", id).put("thread", thread).toString())
        else if (label != null) parent?.part(label, event)
    }

    private fun save() {
        if (!lasting) return
        val saved = synchronized(messages) {
            JSONObject().put("messages", JSONArray(messages)).put("outcomes", outcomes).put("marks", JSONArray(marks))
                .put("plan", plan ?: JSONObject.NULL).put("draft", draft ?: JSONObject.NULL).put("model", own ?: JSONObject.NULL).toString()
        }
        runCatching {
            file.parentFile?.mkdirs()
            // Written beside and moved into place: a death in the middle of writing leaves the old file whole.
            val fresh = File(file.path + ".new")
            fresh.writeText(saved)
            if (!fresh.renameTo(file)) {
                file.writeText(saved)
                fresh.delete()
            }
            val title = synchronized(messages) {
                messages.firstOrNull {
                    it.optString("role") == "user" && it.opt("content") is String && !it.getString("content").startsWith(SUMMARY_NOTE)
                }?.optString("content")
            }
            // A chat nothing was said in yet (only its model was picked) is not among the chats.
            if (title != null) Threads.touch(id, thread, spoken(title))
        }
    }

    // ---- a long conversation is folded, not cut ---------------------------------------------

    private fun isTurnStart(message: JSONObject): Boolean =
        message.optString("role") == "user" && message.opt("content") is String

    /** Tokens the last request took, by the provider's count; 0 when it did not say. */
    private var used = 0

    /** A guess of a message's tokens when nobody counted them: a picture is taken as a fixed lot. */
    private fun weigh(message: JSONObject): Int {
        val content = message.opt("content")
        var letters = message.optJSONArray("tool_calls")?.toString()?.length ?: 0
        var pictures = 0
        if (content is JSONArray) {
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                if (part.optString("type") == "image_url") pictures++ else letters += part.optString("text").length
            }
        } else if (content is String) {
            letters += content.length
        }
        return letters / 3 + pictures * 1500 + 4
    }

    /**
     * When the conversation fills most of the model's window, its earlier turns are replaced by
     * a summary the model writes; the latest turns stay word for word. The window is the owner's
     * setting for the role, so a model on the phone folds early and a large cloud one late.
     */
    fun compact() {
        if (!lasting) return
        val target = runCatching { target() }.getOrNull() ?: return
        val window = target.window
        val cut: Int
        val transcript: String
        synchronized(messages) {
            val weights = messages.map { weigh(it) }
            if (maxOf(used, weights.sum()) <= window * FOLD_AT) return
            val starts = messages.indices.filter { isTurnStart(messages[it]) }
            if (starts.size < 2) return
            // The latest turns stay while they fit in a third of the window; the last one always stays.
            var from = starts.last()
            for (index in starts.indices.reversed().drop(1)) {
                if (starts.size - index > KEEP_TURNS || weights.subList(starts[index], weights.size).sum() > window * KEEP_SHARE) break
                from = starts[index]
            }
            cut = from
            if (cut == 0) return
            transcript = buildString {
                for (message in messages.subList(0, cut)) {
                    val content = message.opt("content")
                    when (message.optString("role")) {
                        "user" -> append("Владелец: ").append(if (content is String) spoken(content).take(1500) else "[вложение]")
                        "assistant" -> {
                            append("Агент: ").append(if (content is String) content.take(1500) else "")
                            message.optJSONArray("tool_calls")?.let { calls ->
                                for (i in 0 until calls.length()) {
                                    val function = calls.getJSONObject(i).getJSONObject("function")
                                    append(" [").append(function.optString("name")).append(' ')
                                        .append(Tools.summary(function.optString("name"), function.optString("arguments"))).append(']')
                                }
                            }
                        }
                        "tool" -> append("  результат: ").append(message.optString("content").take(300))
                    }
                    append('\n')
                }
            // The summary request has to fit the same window, with room for the summary itself.
            }.takeLast(minOf(60_000, (window - 1500).coerceAtLeast(400) * 2))
        }
        used = 0
        val summary = runCatching {
            val request = JSONObject().put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SUMMARY_PROMPT))
                    .put(JSONObject().put("role", "user").put("content", transcript)),
            )
            val response = Llm.complete(target, request)
            Usage.record(id, target.model, response.optJSONObject("usage"))
            response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        }.getOrNull()?.takeIf { it.isNotBlank() }

        synchronized(messages) {
            repeat(cut) { messages.removeAt(0) }
            var shift = cut
            if (summary != null) {
                messages.add(0, JSONObject().put("role", "user").put("content", "$SUMMARY_NOTE\n$summary"))
                shift -= 1
            }
            marks.removeAll { it.optInt("at") <= cut }
            marks.forEach { it.put("at", it.optInt("at") - shift) }
        }
    }

    // ---- what goes to the model -------------------------------------------------------------

    private fun cached(role: String, text: String): JSONObject = JSONObject().put("role", role).put(
        "content",
        JSONArray().put(
            JSONObject().put("type", "text").put("text", text).put("cache_control", JSONObject().put("type", "ephemeral")),
        ),
    )

    /**
     * A model on the phone whose window the whole prompt with every tool would fill by more than
     * half gets the short prompt and a handful of tools: the handbook stays on disk for it to
     * read. With a roomy window it gets everything, like a cloud model — a good prompt matters more
     * than a short one.
     */
    fun lean(spec: Agent.Spec, target: Models.Target): Boolean {
        if (!target.onPhone) return false
        val whole = (systemPrompt(spec).length + Tools.schemas(StoreAgent.tools(this, spec.tools), false, target.vision, StoreAgent.isReview(this)).toString().length) / 3
        return whole > target.window * LEAN_AT
    }

    /** For checks: a conversation put in place of the current one, and a look at what is there. */
    /** For checks: puts back the plan a chat had before a check drew its own. */
    fun planRestore(previous: JSONObject?) {
        plan = previous
        unmarked = 0
        emit(JSONObject().put("type", "plan").put("plan", previous ?: JSONObject.NULL))
        save()
    }

    fun restore(history: JSONArray) = synchronized(messages) {
        messages.clear()
        for (i in 0 until history.length()) messages.add(history.getJSONObject(i))
    }

    fun size(): Int = synchronized(messages) { messages.size }

    fun first(): String = synchronized(messages) { messages.firstOrNull()?.toString().orEmpty() }

    private fun request(spec: Agent.Spec, target: Models.Target, lean: Boolean): JSONArray {
        val out = JSONArray()
        val prompt = systemPrompt(spec, compact = lean)
        // Anthropic models cache the prompt prefix only where a breakpoint marks it: after the
        // system prompt, and at the owner's latest message so earlier turns are not paid for again.
        val anthropic = target.model.startsWith("anthropic/")
        out.put(if (anthropic) cached("system", prompt) else JSONObject().put("role", "system").put("content", prompt))
        synchronized(messages) {
            val imageMessages = messages.indices.filter { messages[it].opt("content") is JSONArray }
            val stale = imageMessages.dropLast(KEEP_IMAGES).toSet()
            val latest = messages.indexOfLast { isTurnStart(it) }
            messages.forEachIndexed { index, message ->
                when {
                    index in stale -> out.put(JSONObject().put("role", "user").put("content", "[старое вложение убрано]"))
                    anthropic && index == latest -> out.put(cached("user", message.getString("content")))
                    else -> out.put(fit(message, target))
                }
            }
        }
        return out
    }

    /**
     * A message as this model can take it. The conversation may have been led by another model
     * before — the owner changes the chat's model when they like — so what only that one took
     * (pictures, its own form of thinking) is left out here, and stays in the conversation.
     */
    private fun fit(message: JSONObject, target: Models.Target): JSONObject {
        val blind = !target.vision && message.opt("content") is JSONArray
        val foreign = !target.openRouter && message.has("reasoning_details")
        if (!message.has(THOUGHT) && !blind && !foreign) return message
        val copy = JSONObject(message.toString())
        copy.remove(THOUGHT)
        if (foreign) copy.remove("reasoning_details")
        if (blind) {
            val parts = copy.getJSONArray("content")
            val kept = JSONArray()
            for (i in 0 until parts.length()) parts.optJSONObject(i)?.takeIf { it.optString("type") != "image_url" }?.let { kept.put(it) }
            if (kept.length() < parts.length()) kept.put(JSONObject().put("type", "text").put("text", BLIND_NOTE))
            copy.put("content", kept)
        }
        return copy
    }

    /** [compact] — for a model on the phone: the handbook and the reference stay on disk, to be read when needed. */
    fun systemPrompt(spec: Agent.Spec, compact: Boolean = false): String {
        // Prompts are templates: {{name}} is what the owner calls the assistant.
        fun part(path: String): String {
            val file = File(Workspace.root, path)
            return (if (file.isFile) file.readText() else Workspace.factoryText(App.instance, path)).orEmpty().trim()
                .replace("{{name}}", Agent.name)
        }

        val home = if (spec.id == Agent.MAIN) "agent" else "agents/${spec.id}"
        val modules = File(Workspace.root, "modules").listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }
            .joinToString("\n") { dir ->
                val meta = runCatching { JSONObject(File(dir, "module.json").readText()) }.getOrNull()
                "- modules/${dir.name}" + (meta?.optString("title")?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "") +
                    (meta?.optString("description")?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
            }.ifEmpty { "(пока нет)" }
        val agents = Agent.list().let { list ->
            (0 until list.length()).map { list.getJSONObject(it) }.filter { it.getString("id") != spec.id }
                .joinToString("\n") { "- ${it.getString("id")} — ${it.getString("title")}: ${it.getString("description")}" }
                .ifEmpty { "(других нет)" }
        }

        return buildString {
            append(part("$home/system.md").ifEmpty { "Вы — агент «${spec.title}» в личном приложении владельца. ${spec.description}" })
            if (spec.api && !compact) append("\n\n").append(part("agent/handbook.md")).append("\n\n").append(part("agent/api.md"))
            append("\n\n# Память\n\n").append(part("$home/memory.md").ifEmpty { "(пусто)" })
            append("\n\n# Сейчас\n\n")
            append("Вы — агент `${spec.id}`; ваша папка — `$home/`.\n")
            append("Модули:\n").append(modules).append('\n')
            append("Другие агенты:\n").append(agents).append('\n')
            append("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}).\n")
            append("Дата: ").append(SimpleDateFormat("yyyy-MM-dd, EEEE", Locale("ru")).format(Date())).append('\n')
            val language = app.assi.core.I18n.language
            append("Язык интерфейса приложения: ").append(Locale.forLanguageTag(language).getDisplayLanguage(Locale("ru"))).append(" (").append(language).append(").\n")
            if (Workspace.safeMode) append("Включён безопасный режим: экраны ui/ показываются из заводской копии.\n")
            if (compact && spec.api) {
                append("Окно вашей модели невелико, поэтому руководства по приложению в этом тексте нет. ")
                append("Прежде чем менять файлы приложения, прочитайте `agent/handbook.md`, а за функциями — `agent/api.md`.\n")
            }
            if (runCatching { target() }.getOrNull()?.vision == false) {
                append("Ваша модель не видит изображения: снимков экрана нет, проверяйте экраны через ui_check (ошибки консоли и код в странице).\n")
            }
            // A store review carries its procedure and rules here, from the shell's code: they outlive a summary of the chat.
            StoreAgent.brief(this@Conversation)?.let { append("\n").append(it).append('\n') }
        }
    }

    companion object {
        /** Opens the note about the screen the owner is looking at, at the end of a message. */
        const val WHERE_OPEN = "[Сейчас у владельца открыт экран: "
        private const val ATTACH_OPEN = "[Вложение: "
        private const val BLIND_NOTE = "(Картинка не показана: модель не видит изображения.)"

        /** Rides at the end of a message that came in the middle of the work, so the model does not take it for a new request. */
        private const val STEER_NOTE = "\n\n[Владелец написал это, пока вы работали. Учтите сказанное и продолжайте работу.]"

        /** What happens to a message sent while the agent works, unless the owner says otherwise: the setting `chat.busy`. */
        private val steersByDefault: Boolean get() = App.prefs.getString("chat.busy", "steer") != "after"
        private const val SUMMARY_NOTE = "[Сводка более ранней части этого разговора]"
        private const val SUMMARY_PROMPT =
            "Ниже — начало разговора владельца приложения с агентом, который меняет это приложение. " +
                "Сожмите его в сводку для самого агента: что владелец просил, что сделано (какие модули, файлы, решения), " +
                "что осталось незавершённым, какие предпочтения владелец высказал. Только факты, без вступлений, до 400 слов."
        private const val MAX_STEPS = 80

        /** Steps in a row one request may take: the owner's setting `agent.steps`. */
        private val stepLimit: Int get() = (App.prefs.getString("agent.steps", null)?.toIntOrNull() ?: MAX_STEPS).coerceIn(20, 400)
        private const val LIMIT_OPEN = "[Вы сделали подряд "
        private const val LIMIT_REST = " шагов — это предел на одну просьбу, и инструменты сейчас недоступны. " +
            "Коротко скажите владельцу: что уже сделано, что осталось и с чего продолжить. Продолжать ли, решит он.]"
        // Reading a large package is bounded, but needs more turns than ordinary tasks.
        // Usage.guard still enforces the owner's spending limit on every model request.
        private const val MAX_REVIEW_STEPS = 240
        private const val TOOL_OUTPUT_LIMIT = 30_000
        /** The share of the window at which the conversation is folded, and the share its latest turns may keep. */
        private const val SHOT_NOTE = "Снимок экрана"
        private const val MAX_PARTS = 6
        private const val PARALLEL = 3
        private const val PART_NOTE = "Это часть большой работы: её вам поручил агент, который ведёт всю работу, и другие части идут в это же время. " +
            "Сделайте только своё, чужих файлов не трогайте, а в конце коротко отчитайтесь: что сделано, какие файлы, что осталось.\n\nЗадача: "
        private const val NO_ANSWER = "Владелец не ответил: работа остановлена."
        private const val THOUGHT = "_thought"
        private const val THOUGHT_LIMIT = 20_000
        private const val DETAIL_LIMIT = 8_000

        /** Steps of a plan that are not finished yet. */
        private val OPEN = setOf("todo", "doing")

        /** After this many tool calls without a mark the model is reminded of its plan. */
        private const val PLAN_REMIND_EVERY = 10
        private const val LEAN_AT = 0.5
        private const val FOLD_AT = 0.7
        private const val KEEP_SHARE = 0.35

        /** What an agent on a phone-sized model works with when it names no tools of its own. */
        private val LIGHT_TOOLS = listOf("ask_owner", "fs_list", "fs_read", "fs_write", "fs_edit", "js_eval", "web_search", "web_read", "memory_save", "memory_search")
        private const val KEEP_TURNS = 3
        private const val KEEP_IMAGES = 3

        /** The owner's words without the screen note the shell added. */
        fun spoken(text: String): String = text.substringBefore("\n\n$WHERE_OPEN").substringBefore(STEER_NOTE)
    }
}
