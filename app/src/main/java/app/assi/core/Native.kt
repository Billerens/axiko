package app.assi.core

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.assi.App
import app.assi.MainActivity
import app.assi.R
import android.util.Base64
import app.assi.agent.Agent
import app.assi.agent.Inbox
import app.assi.agent.Usage
import app.assi.agent.Llm
import app.assi.agent.LocalLlm
import app.assi.agent.Mcp
import app.assi.agent.Models
import app.assi.agent.Tasks
import app.assi.agent.Threads
import app.assi.sense.Bubble
import app.assi.sense.Dnd
import app.assi.sense.Notices
import app.assi.sense.Recorder
import app.assi.sense.Screen
import app.assi.sense.Widgets
import app.assi.voice.Live
import app.assi.voice.LocalTts
import app.assi.voice.Stream
import app.assi.voice.Stt
import app.assi.voice.Tts
import app.assi.voice.Wake
import app.assi.store.StoreAgent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Operations of the shell itself, reachable from pages (`assi.native(...)`) and from scripts
 * (`host.native(...)`). They do not pass through workspace code, so the factory chat and the
 * recovery screens keep working whatever the agent did to the workspace.
 */
object Native {
    /**
     * What this shell can do beyond its first version, by the names modules ask for
     * (`native('shell.features')`, see docs/shell-changes.md): a module checks before it relies on one.
     */
    val FEATURES = listOf(
        "notice.full", "notice.removed", "notice.open", "notify.actions", "dnd", "off", "job.once",
        "vibrate.pattern", "app.visible", "speech.stream", "transcribe.segments", "speech.render",
        "theme.decor", "widget.image", "home.cards", "usage", "store.v1",
    )

    fun call(method: String, args: JSONArray): Any? = when (method) {
        "shell.features" -> JSONArray(FEATURES)
        "store.sources", "store.source.add", "store.source.refresh", "store.source.remove", "store.source.enable",
        "store.catalog", "store.installed", "store.prepare", "store.plans", "store.plan", "store.read", "store.resolve",
        "store.apply", "store.rollback", "store.review", "store.reviewStart" -> StoreAgent.native(method, args)
        // Agents: the first argument is the agent's id ("assi" is the main one).
        "agent.list" -> Agent.list()
        // A chat is named by (agent, thread); without the thread it is the chat open now.
        // The fourth argument takes along whatever waits in the inbox: a captured screen, pictures, files.
        // The fifth says what becomes of a message sent while the agent works: "steer" puts it into
        // the work in hand, "after" keeps it until the work ends; without it the owner's setting decides.
        "agent.send" -> Agent.chat(args.getString(0), text(args, 2))
            .send(args.getString(1), if (args.optBoolean(3, false)) Inbox.take() else emptyList(), where(), steer(args, 4))
        // Messages that wait while the agent works: the list, taking one back, changing how it is delivered.
        "agent.queue" -> Agent.chat(args.getString(0), text(args, 1)).waiting()
        "agent.unqueue" -> Agent.chat(args.getString(0), text(args, 1)).unqueue(args.getString(2))
        "agent.requeue" -> Agent.chat(args.getString(0), text(args, 1)).requeue(args.getString(2), args.optString(3) != "after")
        // The model of one chat: what it runs on, and the owner's choice for it ({provider, model, effort…} or null).
        "agent.model" -> Agent.chat(args.getString(0), text(args, 1)).model()
        "agent.setModel" -> Agent.chat(args.getString(0), text(args, 1)).setModel(args.optJSONObject(2))
        "agent.stop" -> Agent.chat(args.getString(0), text(args, 1)).stop()
        "agent.history" -> Agent.chat(args.getString(0), text(args, 1)).display()
        // (agent, thread, ask, text) — the owner's answers to a form the agent showed.
        "agent.answer" -> Agent.chat(args.getString(0), text(args, 1)).answer(args.getString(2), args.getString(3))
        "agent.plan" -> Agent.chat(args.getString(0), text(args, 1)).plan()
        // Everything the agents are doing and did lately; a task cut short by the app's death can be taken up again.
        "tasks.list" -> Tasks.list()
        "tasks.forget" -> {
            Tasks.forget(args.optString(0, "*"))
            true
        }
        "tasks.resume" -> {
            Threads.select(args.getString(0), args.getString(1))
            Agent.chat(args.getString(0), args.getString(1)).send("Работа была прервана: приложение закрылось. Посмотрите, что уже сделано, и продолжайте с того места.")
        }
        "agent.busy" -> Agent.chat(args.getString(0), text(args, 1)).busy
        // The agent's separate chats: list, start a new one, switch, forget.
        "agent.threads" -> Threads.list(args.getString(0))
        "agent.thread" -> Threads.current(args.getString(0))
        "agent.newThread" -> Threads.create(args.getString(0))
        "agent.openThread" -> {
            Threads.select(args.getString(0), args.getString(1))
            true
        }
        "agent.deleteThread" -> Agent.forget(args.getString(0), args.getString(1))
        // One task in a fresh conversation; blocks until the agent's final answer.
        "agent.run" -> Agent.run(args.getString(0), args.getString(1))

        // Models: providers are any OpenAI-compatible endpoints, roles say who uses which model.
        "models.providers" -> Models.providers()
        "models.setProviders" -> {
            Models.setProviders(args.getJSONArray(0))
            true
        }
        "models.roles" -> Models.roles()
        "models.setRoles" -> {
            Models.setRoles(args.getJSONObject(0))
            true
        }
        "models.list" -> Models.list(args.getString(0))
        "models.resolve" -> Models.resolve(args.getString(0), args.optBoolean(1, true)).let {
            JSONObject().put("provider", it.provider).put("baseUrl", it.baseUrl).put("key", it.key)
                .put("model", it.model).put("options", it.options)
        }
        // A plain completion for a role: (role, {messages, ...}) → the provider's response.
        "models.complete" -> {
            Usage.guard()
            val target = Models.resolve(args.getString(0))
            Llm.complete(target, args.getJSONObject(1)).also { Usage.record(args.getString(0), target.model, it.optJSONObject("usage")) }
        }

        // Modules: every folder modules/<id>/ with its module.json.
        "modules.list" -> JSONArray().apply {
            File(Workspace.root, "modules").listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.forEach { dir ->
                val meta = runCatching { JSONObject(File(dir, "module.json").readText()) }.getOrElse { JSONObject() }
                val entry = JSONObject().put("id", dir.name)
                    .put("title", meta.optString("title").ifBlank { dir.name })
                    .put("description", meta.optString("description"))
                    .put("entry", "modules/${dir.name}/" + meta.optString("entry").ifBlank { "ui/index.html" })
                    .put("models", meta.optJSONArray("models") ?: JSONArray())
                // A small page of its own on the home screen: a widget of "homeSize" cells (columns x rows),
                // resizable by the owner to one of "homeSizes"; old cards give only "homeHeight".
                meta.optString("home").takeIf { it.isNotBlank() }?.let { home ->
                    entry.put("home", "modules/${dir.name}/" + home.trimStart('/')).put("homeHeight", meta.optInt("homeHeight", 120))
                    meta.optString("homeSize").takeIf { it.isNotBlank() }?.let { entry.put("homeSize", it) }
                    meta.optJSONArray("homeSizes")?.let { entry.put("homeSizes", it) }
                }
                Icons.describe(entry, "module", dir.name, dir, meta.optString("icon"), "box")
                put(entry)
            }
        }

        // (id) — removes the module's code; what it stored in data/ stays.
        "modules.remove" -> {
            val dir = File(Workspace.root, "modules/" + args.getString(0))
            require(dir.isDirectory && dir.parentFile?.name == "modules") { "Нет такого модуля" }
            Workspace.snapshot("Перед удалением модуля ${args.getString(0)}")
            dir.deleteRecursively()
        }
        // ("module" | "agent", id, {icon: name} | {image: dataUrl} | {}) — the owner's choice of icon.
        "icon.set" -> {
            Icons.choose(args.getString(0), args.getString(1), args.optJSONObject(2) ?: JSONObject())
            true
        }

        // Scheduled jobs (see job() in the engine) and shortcuts on the phone's home screen.
        "job.set" -> {
            Jobs.set(args.getString(0), args.getJSONObject(1))
            true
        }
        "job.remove" -> {
            Jobs.remove(args.getString(0))
            true
        }
        "job.list" -> Jobs.list()
        "shortcut.pin" -> {
            val app = App.instance
            val path = args.getString(1)
            val open = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("open", path)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val shortcut = ShortcutInfo.Builder(app, "open:$path")
                .setShortLabel(args.getString(0))
                .setIcon(Icon.createWithResource(app, R.mipmap.ic_launcher))
                .setIntent(open)
                .build()
            app.getSystemService(ShortcutManager::class.java).requestPinShortcut(shortcut, null)
        }

        // Models on the phone: the catalogue, downloading, removal.
        "local.status" -> LocalLlm.status()
        "local.download" -> {
            LocalLlm.download(args.getString(0))
            true
        }
        // (url, embedding?) — any model in the .litertlm format, by the address of its file.
        "local.add" -> LocalLlm.add(args.getString(0), args.optBoolean(1, false)).also { id ->
            Engine.pool.execute { runCatching { LocalLlm.download(id) } }
        }
        "local.remove" -> {
            LocalLlm.remove(args.getString(0))
            true
        }

        // The Linux environment: install it, run a command, keep a program running under a name.
        "linux.status" -> Linux.status()
        "linux.install" -> {
            Linux.install()
            true
        }
        "linux.remove" -> {
            Linux.remove()
            true
        }
        "linux.run" -> Linux.run(args.getString(0), args.optLong(1, 120) * 1000)
            .let { JSONObject().put("code", it.code).put("out", it.out) }
        "linux.spawn" -> {
            Linux.spawn(args.getString(0), args.getString(1))
            true
        }
        "linux.stop" -> {
            Linux.stop(args.getString(0))
            true
        }
        "linux.alive" -> Linux.alive(args.getString(0))
        "linux.log" -> Linux.log(args.getString(0), args.optInt(1, 40))

        // The app in a browser on the same network: {on, running, port, addresses}.
        "lan.status" -> Lan.status()
        "lan.set" -> {
            Lan.set(args.getBoolean(0))
            Lan.status()
        }
        // The owner's own name on the network, key and port: ({name?, code?, port?}) → the new state.
        "lan.setup" -> Lan.setup(args.getJSONObject(0))

        // The command line: (command, privileged?, timeoutSeconds?) → {code, out}. Privileged means
        // the rights of ADB through Shizuku.
        "shell.run" -> Shell.run(args.getString(0), args.optLong(2, 60) * 1000, args.optBoolean(1, false))
            .let { JSONObject().put("code", it.code).put("out", it.out) }
        "shizuku.status" -> Shell.status()
        "shizuku.request" -> Shell.request()
        // The app gives itself every permission and sense it can, with the rights of ADB.
        "shizuku.empower" -> {
            Shell.request()
            Shell.empower()
        }

        // Senses: which are switched on, and the system screens where the owner switches them.
        "sense.status" -> JSONObject().put("screen", Screen.on).put("notices", Notices.on)
            .put("assistant", isAssistant()).put("overlay", Bubble.allowed).put("bubble", Bubble.wanted)
        "sense.open" -> openSystemSettings(args.getString(0))
        "bubble.set" -> {
            Bubble.set(args.getBoolean(0))
            true
        }
        // The screen of whatever app is in front (needs the accessibility service).
        // "Show it once": the screen service watches what the owner does in another app.
        "record.start" -> Recorder.start()
        "record.stop" -> Recorder.stop()
        "record.status" -> Recorder.status()
        "screen.read" -> Screen.read(args.optInt(0, 350))
        "screen.shot" -> Screen.shot()
        "screen.click" -> Screen.click(args.getInt(0))
        "screen.type" -> Screen.type(args.getString(0), if (args.isNull(1)) null else args.getInt(1))
        "screen.scroll" -> Screen.scroll(args.getInt(0), args.optBoolean(1, true))
        "screen.tap" -> Screen.tap(args.getDouble(0).toFloat(), args.getDouble(1).toFloat())
        "screen.swipe" -> Screen.swipe(
            args.getDouble(0).toFloat(), args.getDouble(1).toFloat(), args.getDouble(2).toFloat(),
            args.getDouble(3).toFloat(), args.optLong(4, 300),
        )
        "screen.global" -> Screen.global(args.getString(0))
        "screen.openApp" -> Screen.openApp(args.getString(0))
        // Notifications of all apps (needs notification access).
        "notices.recent" -> Notices.recent(args.optInt(0, 30))
        "notices.active" -> Notices.active()
        "notices.dismiss" -> Notices.dismiss(args.getString(0))
        // (key, text) — answers through the notification's own "Reply" action.
        "notices.reply" -> Notices.reply(args.getString(0), args.getString(1))
        // (key) — what a tap on it opens, a messenger's chat; also after it is gone.
        "notices.open" -> Notices.open(args.getString(0))
        // The app's own notifications (assi.notify): options in, the id out.
        "notify" -> Notify.post(args.optJSONObject(0) ?: JSONObject().put("text", args.optString(0)))
        "notify.cancel" -> {
            Notify.cancel(args.getString(0))
            true
        }
        // "Do not disturb": {filter, access}; switching it; the system screen of access to it.
        "dnd.get" -> Dnd.get()
        "dnd.set" -> Dnd.set(args.getString(0))
        "dnd.request" -> Dnd.request()
        // (module?) — redraws the home-screen widgets of a module, or all of them.
        "widget.refresh" -> {
            Widgets.refresh(text(args, 0))
            true
        }
        // (on) — listening all the time for the name "Асси".
        "wake.set" -> {
            Wake.set(args.getBoolean(0))
            true
        }
        // The name, the way of listening, the small model that keeps watch for the name.
        "wake.status" -> Wake.status()
        "wake.name" -> {
            Wake.rename(args.getString(0))
            Wake.status()
        }
        "wake.mode" -> {
            App.prefs.edit().putString("wake.mode", if (args.getString(0) == "full") "full" else "light").apply()
            Wake.restart()
            Wake.status()
        }
        "wake.download" -> {
            Wake.download()
            true
        }
        // The voice on the phone: its state, downloading, removal, a phrase in one of its voices.
        "tts.local.status" -> LocalTts.status()
        "tts.local.download" -> {
            LocalTts.download()
            true
        }
        "tts.local.remove" -> {
            LocalTts.remove()
            true
        }
        // The language of the interface: dictionaries in ui/i18n/, "auto" follows the phone.
        "lang.status" -> I18n.status()
        "lang.set" -> {
            I18n.set(args.optString(0, "auto"))
            I18n.status()
        }
        // Themes: files in themes/, one of them worn by every screen.
        "theme.list" -> Themes.list()
        "theme.set" -> {
            Themes.set(args.optString(0))
            true
        }
        "theme.save" -> {
            Themes.save(args.getString(0), args.getJSONObject(1))
            true
        }
        "theme.remove" -> {
            Themes.remove(args.getString(0))
            true
        }
        // (css) — a layer over whatever theme is worn (a holiday); '' takes it away.
        "theme.decor" -> {
            Themes.decor(text(args, 0).orEmpty())
            true
        }
        // (on) — the menu of screens: a bar below, a rail at the side when the screen is wide.
        "nav.show" -> {
            App.prefs.edit().putString("nav.on", if (args.getBoolean(0)) "on" else "off").apply()
            MainActivity.current?.rearrange()
            true
        }
        "nav.changed" -> {
            Pages.broadcast("nav", "null")
            true
        }
        // Attachments waiting to go with the next message.
        "inbox.peek" -> Inbox.peek()
        "inbox.drop" -> {
            Inbox.drop()
            true
        }
        // ({name, data}) — a file picked in the chat, as a data: URL.
        "inbox.add" -> {
            val item = args.getJSONObject(0)
            val data = item.getString("data")
            Inbox.addFile(
                item.optString("name", "file"),
                Base64.decode(data.substringAfter("base64,"), Base64.DEFAULT),
                data.substringAfter("data:").substringBefore(';'),
            )
            Inbox.peek()
        }

        // Speech: one utterance from the microphone → text; text → voice.
        "speech.status" -> Stt.status().put("tts", Tts.enabled)
        "speech.download" -> {
            Stt.download()
            true
        }
        "speech.listen" -> Stt.listen(args.optInt(0, 45))
        // (agent, thread) — a live conversation: the microphone stays open and the owner can speak over the answer.
        "live.start" -> Live.start(args.optString(0, Agent.MAIN), text(args, 1))
        "live.stop" -> {
            Live.stop()
            true
        }
        "live.status" -> Live.status()
        "speech.stopListening" -> {
            Stt.stop()
            true
        }
        // (path, {segments: true}?) — the text of a file, or its phrases with their time.
        "speech.transcribe" -> Workspace.resolve(args.getString(0)).path.let { path ->
            if (args.optJSONObject(1)?.optBoolean("segments") == true) Stt.segmentFile(path) else Stt.transcribeFile(path)
        }
        // ({maxMinutes}) — the microphone stays open; every phrase comes as the "speech.phrase" event.
        "speech.stream.start" -> Stream.start(args.optJSONObject(0)?.optDouble("maxMinutes", 10.0) ?: 10.0)
        "speech.stream.stop" -> {
            Stream.stop()
            true
        }
        // (text, path, 'cloud'|'local'?) — the text spoken into an audio file; the path written comes back.
        "speech.render" -> Tts.render(args.getString(0), args.getString(1), text(args, 2))
        "speech.say" -> {
            // (text, "local"?) — the second argument asks for the voice on the phone.
            Tts.say(args.getString(0), args.optString(1) == "local")
            true
        }
        "speech.stop" -> {
            Tts.stop()
            true
        }

        // MCP servers: the owner's list, each one's state, a direct call of a tool.
        "mcp.servers" -> Mcp.servers()
        "mcp.setServers" -> {
            Mcp.setServers(args.getJSONArray(0))
            true
        }
        "mcp.status" -> Mcp.status()
        "mcp.tools" -> Mcp.tools(args.getString(0))
        "mcp.call" -> Mcp.call(args.getString(0), args.getString(1), args.optJSONObject(2) ?: JSONObject())
        // Signing in to a service: (name, {authUrl, tokenUrl, clientId, clientSecret?, scope, extra?}).
        "oauth.connect" -> OAuth.connect(args.getString(0), args.getJSONObject(1))
        "oauth.start" -> OAuth.start(args.getString(0), args.getJSONObject(1))
        "oauth.await" -> OAuth.await(args.getString(0), args.optLong(1, 600))
        "oauth.token" -> OAuth.token(args.getString(0))
        "oauth.names" -> JSONArray(OAuth.names())

        // Secrets the owner typed into the system dialog (see Vault): passwords, API keys.
        "secret.get" -> Vault.get(args.getString(0))
        "secret.set" -> {
            Vault.set(args.getString(0), args.getString(1))
            true
        }
        "secret.remove" -> {
            Vault.remove(args.getString(0))
            true
        }
        "secret.names" -> JSONArray(Vault.names())
        // (name, title, hint) — asks the owner to type it; true when saved.
        "secret.ask" -> Vault.ask(args.getString(0), args.optString(1, "Введите значение"), args.optString(2))
        // (texts) — vectors of meaning from the model of the "embed" role.
        "models.embed" -> Llm.embed(Models.resolve("embed", inherit = false), args.getJSONArray(0))

        // What a turn changed, file by file, against the snapshot taken before it.
        "snap.changes" -> Workspace.changes(args.getString(0))
        "snap.diff" -> Workspace.diff(args.getString(0), args.getString(1))
        // Tokens and money by day and by agent; the daily limit lives in the "budget.daily" setting.
        "usage.summary" -> Usage.summary(args.optInt(0, 7))

        "snap.list" -> Workspace.snapshots()
        "snap.storage" -> Workspace.historyStats()
        "snap.migrate" -> Workspace.migrateHistory()
        "snap.restore" -> {
            Workspace.restore(args.getString(0))
            MainActivity.current?.reloadPages()
            true
        }

        // (agent) — the whole prompt the agent's model gets, as it is assembled right now.
        "agent.prompt" -> {
            val id = args.optString(0, Agent.MAIN)
            val chat = Agent.chat(id)
            val lean = runCatching { chat.lean(Agent.spec(id), chat.target()) }.getOrDefault(false)
            chat.systemPrompt(Agent.spec(id), lean)
        }
        "pref.get" -> App.prefs.getString(args.getString(0), null) ?: args.opt(1)?.takeIf { it != JSONObject.NULL }
        "pref.set" -> {
            val value = args.opt(1)
            App.prefs.edit().apply {
                if (value == null || value == JSONObject.NULL) remove(args.getString(0))
                else putString(args.getString(0), value.toString())
            }.apply()
            true
        }
        "pref.all" -> JSONObject().apply {
            App.prefs.all.forEach { (key, value) -> put(key, value?.toString()) }
        }

        // A draft's screen opens the draft's other screens.
        "ui.open" -> MainActivity.current?.open((Drafts.active?.let { "~$it/" } ?: "") + args.getString(0).trimStart('/')) != null
        // Drafts: reworks that wait for the owner's decision. (agent, thread) lists a chat's; the rest take the draft's id.
        "draft.list" -> Drafts.list(text(args, 0), text(args, 1))
        "draft.diff" -> Drafts.diff(args.getString(0), args.getString(1))
        "draft.open" -> {
            val draft = args.getString(0)
            val path = text(args, 1) ?: Drafts.meta(draft).optString("open").ifBlank {
                val files = Drafts.changes(draft)
                (0 until files.length()).map { files.getJSONObject(it).getString("path") }.firstOrNull { it.endsWith(".html") } ?: "ui/home/index.html"
            }
            MainActivity.current?.open("~$draft/" + path.trimStart('/')) != null
        }
        "draft.accept", "draft.discard" -> {
            val draft = args.getString(0)
            val meta = Drafts.meta(draft)
            val taken = method == "draft.accept"
            if (taken) Drafts.accept(draft) else Drafts.discard(draft)
            // Whoever built it learns what became of it with the owner's next message.
            runCatching { Agent.chat(meta.optString("agent", Agent.MAIN), meta.optString("thread")).drafted(draft, meta.optString("title"), taken) }
            // A screen of the draft that is still shown: the draft is gone, so it becomes the app's own
            // screen at the same place when taken, the home screen when thrown away.
            val shown = where().orEmpty()
            val inDraft = shown.startsWith("~$draft/")
            when {
                taken && inDraft -> MainActivity.current?.open(shown.removePrefix("~$draft/"))
                taken -> MainActivity.current?.reloadPages()
                inDraft -> MainActivity.current?.open(MainActivity.HOME)
            }
            true
        }
        "ui.back" -> MainActivity.current?.goBack() != null
        "ui.reload" -> MainActivity.current?.reloadPages() != null
        // (open, agent?) — shows or hides the chat, optionally switching it to an agent.
        "ui.chat" -> {
            text(args, 1)?.let { Pages.broadcast("chat.agent", JSONObject.quote(it)) }
            MainActivity.current?.showChat(args.optBoolean(0, true)) != null
        }
        // Which screen the owner is looking at: {path, title}.
        "ui.where" -> JSONObject().put("path", where().orEmpty()).put("title", whereTitle())
        // (path, errors) — a broken screen asks the main agent to mend it, in a chat of its own.
        "ui.fix" -> {
            Threads.create(Agent.MAIN)
            Pages.broadcast("chat.agent", JSONObject.quote(Agent.MAIN))
            MainActivity.current?.showChat(true)
            Agent.chat(Agent.MAIN).send(
                "Экран `${args.getString(0)}` работает с ошибкой:\n${args.optString(1).take(3000)}\n\nНайдите причину и исправьте.",
                where = args.getString(0),
            )
        }
        "ui.ready" -> MainActivity.current?.pageReady() != null
        // For checks written in the engine: open a screen and wait for it; run code in it and wait for its promise.
        "ui.openWait" -> {
            val page = (MainActivity.current ?: error("Приложение не открыто")).page
            val mark = page.console.mark()
            // Within a draft it is the draft's screen that is opened, as ui.open does: a module's
            // checks run in a draft must look at the draft.
            val loaded = page.openAndWait((Drafts.active?.let { "~$it/" } ?: "") + args.getString(0).trimStart('/'))
            Thread.sleep(args.optLong(1, 600).coerceIn(0, 10_000))
            JSONObject().put("loaded", loaded).put("errors", JSONArray(page.console.since(mark).filter { it.startsWith("[error]") }))
        }
        "ui.eval" -> app.assi.agent.Tools.pageEval(args.getString(0), args.optLong(1, 400))
        // (on) — the whole display for the current screen: a game, a scene, a presentation.
        "ui.stage" -> MainActivity.current?.setStage(args.optBoolean(0, true)) != null

        "safe.set" -> MainActivity.current?.setSafeMode(args.getBoolean(0)) != null
        "safe.resetUi" -> {
            Workspace.snapshot("Перед сбросом интерфейса")
            Workspace.installFactory(App.instance, reset = args.optString(0, "ui/"))
            MainActivity.current?.reloadPages()
            true
        }

        "sys.info" -> JSONObject()
            .put("safeMode", Workspace.safeMode)
            .put("language", I18n.language)
            .put("engineError", Engine.bootError)
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("version", App.instance.packageManager.getPackageInfo(App.instance.packageName, 0).versionName)

        // Static web files pages loaded (CDN libraries, fonts, images), kept for offline use.
        "webcache.info" -> WebCache.info()
        "webcache.clear" -> {
            WebCache.clear()
            true
        }

        "log.engine" ->JSONArray(Engine.host.console.tail(args.optInt(0, 100)))

        else -> throw IllegalArgumentException("Нет операции $method")
    }

    /** The workspace path of the screen in front of the owner; null on the home screen. */
    private fun where(): String? =
        MainActivity.current?.page?.location?.removePrefix(Pages.ORIGIN + "/")?.substringBefore('?')?.substringBefore('#')
            ?.takeIf { it.isNotBlank() && !it.startsWith("ui/home/") }

    private fun whereTitle(): String {
        val path = where() ?: return ""
        if (!path.startsWith("modules/")) return ""
        val id = path.removePrefix("modules/").substringBefore('/')
        return runCatching { JSONObject(File(Workspace.root, "modules/$id/module.json").readText()).optString("title") }
            .getOrDefault("").ifBlank { id }
    }

    private fun isAssistant(): Boolean {
        val resolver = App.instance.contentResolver
        return listOf("assistant", "voice_interaction_service").any {
            Settings.Secure.getString(resolver, it).orEmpty().contains(App.instance.packageName)
        }
    }

    /** Opens the system screen where a sense is granted: the app cannot grant these to itself. */
    private fun openSystemSettings(which: String): Boolean {
        val intent = when (which) {
            "screen" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "notices" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "assistant" -> Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + App.instance.packageName))
            "app" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + App.instance.packageName))
            else -> throw IllegalArgumentException("Нет такого раздела: $which")
        }
        App.instance.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** An optional text argument: absent, null and empty all mean "not given". */
    private fun text(args: JSONArray, index: Int): String? =
        if (args.isNull(index)) null else args.optString(index).takeIf { it.isNotBlank() }

    /** How a message sent while the agent works is to reach it: "steer", "after", or null for the owner's setting. */
    fun steer(args: JSONArray, index: Int): Boolean? = when (text(args, index)) {
        "steer" -> true
        "after" -> false
        else -> null
    }

    /** The result as the JSON text a page parses; null for "nothing". */
    fun encode(value: Any?): String? = when (value) {
        null, Unit, JSONObject.NULL -> null
        is String -> JSONObject.quote(value)
        is Number, is Boolean, is JSONObject, is JSONArray -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}
