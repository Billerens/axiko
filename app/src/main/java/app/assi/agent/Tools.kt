package app.assi.agent

import app.assi.MainActivity
import app.assi.core.Engine
import app.assi.core.Linux
import app.assi.core.Pages
import app.assi.core.Shell
import app.assi.core.Workspace
import app.assi.sense.Notices
import app.assi.sense.Screen
import app.assi.store.StoreAgent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What the agent can do. The built-in tools cover files, the engine and the screen; the rest
 * the agent writes itself as `tools/<name>.js` in the workspace.
 */
object Tools {
    /** [detail] is what the step did, for the owner to look at in the chat: the difference it made to a file (as git shows it), the code that ran. */
    class Result(val text: String, val ok: Boolean = true, val detail: String? = null)

    private const val READ_LIMIT = 60_000
    private const val EVAL_TIMEOUT_S = 60

    private class Param(val name: String, val type: String, val about: String, val required: Boolean = true)

    private fun tool(name: String, about: String, vararg params: Param): JSONObject {
        val properties = JSONObject()
        val required = JSONArray()
        for (param in params) {
            properties.put(param.name, JSONObject().put("type", param.type).put("description", param.about))
            if (param.required) required.put(param.name)
        }
        val schema = JSONObject().put("type", "object").put("properties", properties).put("required", required)
        return function(name, about, schema)
    }

    private fun function(name: String, about: String, parameters: JSONObject): JSONObject =
        JSONObject().put("type", "function").put(
            "function",
            JSONObject().put("name", name).put("description", about).put("parameters", parameters),
        )

    private const val ASK = "ask_owner"
    private const val PLAN = "plan_propose"
    private const val STEP = "plan_step"
    private const val PARTS = "subtasks"
    private const val DRAFT = "draft_begin"

    private val builtin: List<JSONObject> = listOf(
        function(
            DRAFT,
            "Начать черновик: с этого момента ваши правки файлов идут в отдельный слой, а приложение у владельца остаётся прежним, " +
                "пока он не посмотрит результат и не примет его. Для больших переделок, смены оформления, всего, что может сломать " +
                "работающее, и когда владелец хочет сравнить варианты (каждый вариант — свой черновик, например в частях subtasks). " +
                "В черновике всё как обычно: fs_*, js_eval, ui_open показывают и проверяют черновую версию. Данные модулей (data/) общие с приложением.",
            JSONObject(
                """{"type":"object","properties":{
                "title":{"type":"string","description":"Что это за переделка, коротко: владелец увидит это название"},
                "open":{"type":"string","description":"Какой экран показать владельцу, когда он захочет посмотреть: путь, например modules/notes/ui/index.html"}},
                "required":["title"]}""",
            ),
        ),
        function(
            PLAN,
            "Показать владельцу план большой работы и дождаться решения: он даёт добро или пишет, что изменить. " +
                "План — перед работой в несколько шагов; после уточняющих вопросов, если они были.",
            JSONObject(
                """{"type":"object","properties":{
                "title":{"type":"string","description":"Что будет сделано, одной строкой"},
                "steps":{"type":"array","items":{"type":"string"},"description":"Шаги по порядку, от трёх до десяти: каждый — законченный кусок работы, понятный владельцу"}},
                "required":["title","steps"]}""",
            ),
        ),
        function(
            STEP,
            "Отметить шаг плана: начат, сделан или пропущен. Владелец видит в чате, как продвигается работа. " +
                "Достаточно отмечать каждый сделанный шаг: следующий сам становится текущим, а предыдущие считаются сделанными.",
            JSONObject(
                """{"type":"object","properties":{
                "step":{"type":"integer","description":"Номер шага, с единицы"},
                "status":{"type":"string","enum":["doing","done","skipped"]},
                "note":{"type":"string","description":"Что стоит знать об этом шаге, коротко (необязательно)"}},
                "required":["step","status"]}""",
            ),
        ),
        function(
            PARTS,
            "Раздать работу: несколько независимых частей выполняются одновременно, каждая — в своём разговоре с чистой головой " +
                "(тот же агент или другой), и вы получаете все отчёты разом. Для частей, которые не мешают друг другу: разные файлы, " +
                "разные экраны, поиск по разным вопросам. В задаче каждой части напишите всё, что ей нужно знать: общего разговора она не видит.",
            JSONObject(
                """{"type":"object","properties":{
                "tasks":{"type":"array","description":"Части, от двух до шести","items":{"type":"object","properties":{
                  "title":{"type":"string","description":"Короткое название части"},
                  "task":{"type":"string","description":"Что сделать, какие файлы её, что вернуть в отчёте"},
                  "agent":{"type":"string","description":"Кому поручить; по умолчанию — себе же"}},
                  "required":["title","task"]}}},
                "required":["tasks"]}""",
            ),
        ),
        function(
            ASK,
            "Спросить владельца: в чате появится форма с вопросами. У каждого вопроса — варианты (один на выбор или несколько) " +
                "и поле для своего ответа; вопрос без вариантов — просто поле (название, адрес, пожелания своими словами). Возвращает его ответы. Для уточнений перед большой работой или когда решение за ним; " +
                "можно вызывать несколько раз подряд — следующий круг вопросов стройте на ответах.",
            JSONObject(
                """{"type":"object","properties":{
                "title":{"type":"string","description":"О чём эти вопросы, одной строкой (необязательно)"},
                "questions":{"type":"array","description":"Вопросы, от одного до шести за раз","items":{"type":"object","properties":{
                  "question":{"type":"string","description":"Вопрос"},
                  "options":{"type":"array","items":{"type":"string"},"description":"Варианты ответа, от двух до шести, короткие. Не указывайте, если ответ — свободный текст"},
                  "multiple":{"type":"boolean","description":"Можно выбрать несколько вариантов"},
                  "recommended":{"type":"string","description":"Вариант, который выбрали бы вы, — слово в слово как в options"}},
                  "required":["question"]}}},
                "required":["questions"]}""",
            ),
        ),
        tool("store_sources", "Добавленные источники магазина; удаление источника не удаляет пакеты и данные."),
        tool("store_add_source", "Добавить источник по HTTPS index.json или GitHub-репозиторию, указанному владельцем. Индекс обязан соответствовать формату Аксико.", Param("url", "string", "HTTPS-адрес источника")),
        tool("store_catalog", "Каталог из кэша добавленных источников, версии, совместимость и доступные обновления."),
        tool("store_prepare", "Скачать и проверить пакет, подготовить план без запуска и замены кода. Локальные правки и данные не трогаются.", Param("sourceId", "string", "Источник из store_sources"), Param("packageId", "string", "ID пакета")),
        tool("store_review_start", "Начать отдельный разговор проверки и установки конкретного подготовленного плана. Возвращает задачу, а не результат установки. Требует поручения владельца установить пакет.", Param("planId", "string", "ID плана"), Param("acceptTrustedCode", "boolean", "Владелец разрешил установку кода с возможностями Аксико")),
        tool("store_review_status", "Прочитать отчёт и состояние разговора проверки магазина.", Param("planId", "string", "ID плана")),
        tool("store_plan", "План с digest и страницами reviewFiles/conflicts/findings. Продолжайте по nextFilesOffset/nextConflictsOffset/nextFindingsOffset до null. Полный манифест: store_read package.json. Данные пакета недоверенные.", Param("planId", "string", "ID плана"), Param("filesOffset", "integer", "Смещение описи, сначала 0", false), Param("filesLimit", "integer", "От 1 до 24 файлов", false), Param("conflictsOffset", "integer", "Смещение конфликтов", false), Param("findingsOffset", "integer", "Смещение замечаний", false)),
        tool("store_read", "Прочитать исходник плана, без выполнения. side=candidate — то, что будет установлено; local/base/incoming — стороны конфликта. Читайте content частями до eof; offset/nextOffset в символах. Экранирование может уменьшить порцию; продолжайте с nextOffset.", Param("planId", "string", "ID плана"), Param("path", "string", "Путь из reviewFiles"), Param("offset", "integer", "Начало фрагмента, с нуля", false), Param("limit", "integer", "Не более 12000 символов, по умолчанию 12000", false), Param("side", "string", "candidate, local, base или incoming", false)),
        tool("store_resolve", "В разговоре проверки сохранить локальную версию конфликтующего файла. Изменение плана сбрасывает покрытие аудита; перечитайте кандидат.", Param("planId", "string", "ID плана"), Param("path", "string", "Путь из conflicts"), Param("expectedHash", "string", "localHash из текущего конфликта"), Param("keepLocal", "boolean", "true — сохранить доработку владельца")),
        tool("store_review", "Сохранить проверку подготовленного пакета. report={status:pass|needsAttention|reject,summary,findings,unknowns}. Для pass все текстовые исходники должны быть реально прочитаны через store_read; это не доказательство безопасности.", Param("planId", "string", "ID плана"), Param("digest", "string", "Текущий digest из store_plan"), Param("report", "object", "Структурированный результат с конкретными фактами и неизвестностью")),
        tool("store_apply", "Установить только проверенный план из этого разговора: нужен pass, полное чтение, разрешение владельца и неизменившиеся файлы. Не запускает код или миграции пакета.", Param("planId", "string", "ID плана"), Param("digest", "string", "Текущий digest")),
        tool(
            "fs_list", "Дерево файлов. Пути относительно рабочей папки; `data/` — данные модулей.",
            Param("path", "string", "Папка; по умолчанию корень рабочей папки", false),
            Param("depth", "integer", "Глубина, по умолчанию 2", false),
        ),
        tool(
            "fs_read", "Прочитать текстовый файл.",
            Param("path", "string", "Путь к файлу"),
            Param("offset", "integer", "С какой строки (с 1)", false),
            Param("limit", "integer", "Сколько строк", false),
        ),
        tool(
            "fs_write", "Создать или целиком заменить файл. Папки создаются сами.",
            Param("path", "string", "Путь к файлу"),
            Param("content", "string", "Полное содержимое"),
        ),
        tool(
            "fs_edit", "Заменить фрагмент файла. `old` должен встречаться ровно один раз, если не указано all.",
            Param("path", "string", "Путь к файлу"),
            Param("old", "string", "Точный текущий фрагмент"),
            Param("new", "string", "Чем заменить"),
            Param("all", "boolean", "Заменить все вхождения", false),
        ),
        tool("fs_delete", "Удалить файл или папку.", Param("path", "string", "Путь")),
        tool(
            "js_eval",
            "Выполнить JavaScript в движке приложения (Rhino): доступны все классы Android, `context`, " +
                "`host`, `require`. Возвращает значение последнего выражения и вывод console.",
            Param("code", "string", "Код"),
            Param("timeout_s", "integer", "Предел времени, по умолчанию $EVAL_TIMEOUT_S с", false),
        ),
        tool(
            "ui_open", "Открыть экран приложения и вернуть ошибки его консоли.",
            Param("path", "string", "Например modules/notes/ui/index.html"),
            Param("wait_ms", "integer", "Сколько ждать после загрузки, по умолчанию 800", false),
        ),
        tool(
            "ui_eval", "Выполнить JavaScript в текущем экране (в странице, не в движке): проверить DOM, нажать кнопку. " +
                "Если код возвращает Promise, его результат дожидаются (до 10 с). После выполнения экрану даётся время перерисоваться, " +
                "так что нажатие и чтение результата можно делать двумя вызовами подряд.",
            Param("code", "string", "Выражение или код; результат вернётся как JSON"),
            Param("wait_ms", "integer", "Сколько ждать перерисовки после выполнения, по умолчанию 400", false),
        ),
        tool(
            "ui_console", "Последние строки консоли текущего экрана.",
            Param("lines", "integer", "Сколько строк, по умолчанию 40", false),
        ),
        tool("ui_screenshot", "Снимок текущего экрана приложения: вы увидите его картинкой."),
        tool("snapshot_list", "Снимки рабочей папки (делаются перед каждым запросом, который что-то изменил)."),
        tool("snapshot_restore", "Вернуть рабочую папку к снимку.", Param("id", "string", "Идентификатор снимка")),
        tool(
            "linux",
            "Команда в Linux-окружении телефона (Ubuntu, bash, права root внутри него): apt, python3, node, git, ffmpeg, curl, компиляторы. " +
                "Папка данных модулей видна там как /assi. Долгую установку пакетов запускайте с большим timeout_s.",
            Param("command", "string", "Команда bash"),
            Param("timeout_s", "integer", "Предел времени, по умолчанию 120 с", false),
        ),
        tool(
            "phone_reply",
            "Ответить на уведомление текстом — так, как это сделала бы кнопка «Ответить» в самом уведомлении (мессенджер отправит сообщение в тот чат). " +
                "Отправляйте только то, о чём владелец просил.",
            Param("key", "string", "Ключ уведомления из phone_notifications"),
            Param("text", "string", "Текст ответа"),
        ),
        tool(
            "shell",
            "Команда в командной строке Android (sh): файлы, процессы, сеть, системные утилиты. " +
                "С privileged=true выполняется с правами ADB (если владелец запустил Shizuku): pm, am, settings, input, appops, cmd.",
            Param("command", "string", "Команда"),
            Param("privileged", "boolean", "С правами ADB через Shizuku", false),
            Param("timeout_s", "integer", "Предел времени, по умолчанию 60 с", false),
        ),
        tool(
            "phone_screen",
            "Прочитать экран телефона: какое приложение сейчас открыто и что на нём — тексты и элементы с номерами. " +
                "Номера действуют до следующего чтения.",
        ),
        tool("phone_look", "Снимок экрана телефона целиком, какое бы приложение ни было открыто: вы увидите его картинкой."),
        tool(
            "phone_act",
            "Действие на экране телефона в любом приложении. Возвращает экран после действия.",
            Param(
                "action", "string",
                "click (номер элемента), type (текст в поле), scroll_down, scroll_up (номер прокручиваемого элемента), " +
                    "tap (x, y), swipe (x, y, x2, y2), back, home, recents, notifications, open_app (название или пакет)",
            ),
            Param("index", "integer", "Номер элемента из phone_screen", false),
            Param("text", "string", "Текст для type или название приложения для open_app", false),
            Param("x", "number", "Координата X в пикселях", false),
            Param("y", "number", "Координата Y в пикселях", false),
            Param("x2", "number", "Конец жеста по X", false),
            Param("y2", "number", "Конец жеста по Y", false),
        ),
        tool(
            "phone_notifications",
            "Уведомления всех приложений: письма, сообщения, банки. Сначала те, что сейчас в шторке, затем недавние.",
            Param("limit", "integer", "Сколько, по умолчанию 30", false),
        ),
        tool(
            DELEGATE,
            "Поручить задачу другому агенту (папка agents/<id>/) и дождаться его итогового ответа. " +
                "Агент начинает с чистого листа: в задаче должно быть всё, что ему нужно знать.",
            Param("agent", "string", "Идентификатор агента"),
            Param("task", "string", "Задача целиком"),
        ),
    )

    private const val DELEGATE = "agent_run"

    /** Tools whose answer is a picture: of no use to a model that cannot see. */
    private val PICTURES = setOf("ui_screenshot", "phone_look")

    private val builtinNames = builtin.map { it.getJSONObject("function").getString("name") }.toSet()

    /** Tools that need the owner at the chat, or hand work on: not for a caller from outside. */
    private val INSIDE = setOf(ASK, PLAN, STEP, PARTS, DELEGATE, DRAFT)

    /**
     * What the phone offers to agents elsewhere (see Lan: the phone as an MCP server): its own
     * tools, without the ones that talk to the owner in the chat and without tools that are
     * themselves borrowed from other servers.
     */
    fun offered(): List<JSONObject> {
        val all = schemas(null, delegate = false, vision = true)
        return (0 until all.length()).map { all.getJSONObject(it).getJSONObject("function") }
            .filter { it.getString("name") !in INSIDE && !it.getString("name").startsWith("mcp_") }
    }

    private fun allowed(name: String, filter: List<String>?): Boolean =
        filter == null || filter.any { if (it.endsWith("*")) name.startsWith(it.dropLast(1)) else it == name }

    /**
     * Built-in tools plus the ones the workspace defines, narrowed to what the agent may use.
     * [delegate] is false at the bottom of a chain of agents; [vision] says whether the model takes pictures.
     */
    fun schemas(filter: List<String>?, delegate: Boolean, vision: Boolean = true, builtinOnly: Boolean = false): JSONArray {
        val all = JSONArray()
        builtin.forEach {
            val name = it.getJSONObject("function").getString("name")
            // A model that cannot see gets no screenshots: the picture would only break the request.
            if (allowed(name, filter) && (delegate || (name != DELEGATE && name != PARTS)) && (vision || name !in PICTURES)) all.put(it)
        }
        if (builtinOnly) return all
        runCatching {
            val own = JSONArray(Engine.call("__tools") as String)
            for (i in 0 until own.length()) {
                val item = own.getJSONObject(i)
                if (item.getString("name") in builtinNames || !allowed(item.getString("name"), filter)) continue
                all.put(
                    function(
                        item.getString("name"),
                        item.optString("description"),
                        item.optJSONObject("parameters") ?: JSONObject().put("type", "object").put("properties", JSONObject()),
                    ),
                )
            }
        }.onFailure { Engine.host.log("error", "tools/: " + Engine.describe(it)) }
        // Tools of the MCP servers the owner connected.
        for (tool in Mcp.offered()) {
            if (!allowed(tool.getString("name"), filter)) continue
            all.put(function(tool.getString("name"), tool.getString("description"), tool.getJSONObject("parameters")))
        }
        return all
    }

    /** What the chat shows next to the tool's name. */
    fun summary(name: String, argsJson: String): String {
        val args = runCatching { JSONObject(argsJson) }.getOrNull() ?: return ""
        val value = when (name) {
            "js_eval", "ui_eval" -> args.optString("code").lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            DELEGATE -> args.optString("agent") + ": " + args.optString("task")
            PLAN -> args.optString("title")
            STEP -> "${args.optInt("step")}: " + app.assi.core.I18n.t(
                when (args.optString("status")) {
                    "doing" -> "начат"
                    "skipped" -> "пропущен"
                    else -> "сделан"
                },
            )
            PARTS -> args.optJSONArray("tasks")?.let { list -> (0 until list.length()).joinToString(", ") { list.optJSONObject(it)?.optString("title").orEmpty() } }.orEmpty()
            ASK -> args.optString("title").ifBlank { args.optJSONArray("questions")?.optJSONObject(0)?.optString("question").orEmpty() }
            else -> args.optString("path").ifEmpty { args.optString("id") }
                .ifEmpty { args.keys().asSequence().firstOrNull()?.let { args.optString(it) }.orEmpty() }
        }
        return value.trim().take(80)
    }

    fun run(name: String, argsJson: String, conversation: Conversation, images: MutableList<String>): Result = try {
        val args = JSONObject(argsJson.ifBlank { "{}" })
        StoreAgent.enforce(conversation, name)
        // A file about to be written is read first, so the chat can show what the step changed in it.
        val file = if (name == "fs_write" || name == "fs_edit") runCatching { Workspace.resolve(args.getString("path"), write = true) }.getOrNull() else null
        val before = file?.takeIf { it.isFile && it.length() < 400_000 }?.readText()
        val text = if (name.startsWith("store_")) StoreAgent.run(name, args, conversation)
        else if (name == DELEGATE) Agent.run(args.getString("agent"), args.getString("task"), conversation).ifBlank { "(агент ничего не ответил)" }
        else if (name == ASK) conversation.ask(args)
        else if (name == PLAN) conversation.planPropose(args)
        else if (name == STEP) conversation.planStep(args)
        else if (name == PARTS) conversation.parts(args)
        else if (name == DRAFT) conversation.draftBegin(args)
        else if (name in builtinNames) builtin(name, args, images)
        else if (name.startsWith("mcp_")) Mcp.run(name, args)
        else Engine.call("__toolRun", name, args.toString(), timeoutMs = 300_000) as String? ?: "готово"
        val detail = when {
            file != null && file.isFile && file.length() < 400_000 -> Workspace.unified(before, file.readText())
            // Code that ran is shown with what it gave: the owner sees the step as the model did.
            name == "js_eval" || name == "ui_eval" -> args.optString("code") + "\n\n→ " + text
            name == "shell" || name == "linux" -> "$ " + args.optString("command") + "\n\n" + text
            else -> null
        }
        Result(text, true, detail?.take(12_000))
    } catch (error: Throwable) {
        Result("Ошибка: " + Engine.describe(error), ok = false)
    }

    /**
     * Runs code in the current screen. A promise the code returns is waited for, and afterwards
     * the screen gets a moment to redraw: handlers of buttons are usually asynchronous, and a
     * check made at once would see the screen as it was.
     */
    fun pageEval(code: String, settle: Long): String {
        val activity = activity()
        val slot = "__assiEval" + System.nanoTime()
        val keep = "function(o){try{window['$slot']=JSON.stringify(o);}catch(e){window['$slot']=JSON.stringify({value:String(o.value)});}}"
        activity.evalInPage(
            "(function(){var keep=$keep;window['$slot']=null;" +
                "try{Promise.resolve((0,eval)(${JSONObject.quote(code)})).then(function(v){keep({value:v===undefined?null:v});}," +
                "function(e){keep({error:String(e&&e.message||e)});});}catch(e){keep({error:String(e&&e.message||e)});}})()",
        )
        var answer: JSONObject? = null
        val deadline = System.currentTimeMillis() + 10_000
        while (answer == null && System.currentTimeMillis() < deadline) {
            // What comes back is the JSON of a string that is itself JSON, or of null while the code runs.
            val held = runCatching { org.json.JSONTokener(activity.evalInPage("window['$slot']").orEmpty()).nextValue() }.getOrNull()
            if (held is String) answer = runCatching { JSONObject(held) }.getOrNull() else Thread.sleep(100)
        }
        activity.evalInPage("delete window['$slot']")
        if (answer == null) return "Код не завершился за 10 с: возможно, его Promise так и не выполнился."
        Thread.sleep(settle.coerceIn(0, 5000))
        if (answer.has("error")) return "Ошибка: " + answer.getString("error")
        val value = answer.opt("value")
        return when (value) {
            null, JSONObject.NULL -> "null"
            is String -> JSONObject.quote(value)
            else -> value.toString()
        }
    }

    /** The menu and the theme are files: an edit shows on the screen at once. */
    private fun shown(file: java.io.File) {
        // In a draft nothing is on the owner's screen yet.
        if (app.assi.core.Drafts.active != null) return
        val path = Workspace.display(file)
        if (path.startsWith("ui/nav")) Pages.broadcast("nav", "null")
        if (path.startsWith("themes/")) app.assi.core.Themes.changed()
        // The mascot and the chat button's look are files too: screens draw them anew.
        if (path.startsWith("ui/mascot/") || path.startsWith("ui/button/")) Pages.broadcast("mascot", "null")
    }

    private fun builtin(name: String, args: JSONObject, images: MutableList<String>): String = when (name) {
        "fs_list" -> list(Workspace.resolve(args.optString("path", "")), args.optInt("depth", 2)) + (
            app.assi.core.Drafts.active?.let { draft ->
                val changed = app.assi.core.Drafts.changes(draft)
                if (changed.length() == 0) "" else "\nВ черновике: " + (0 until changed.length()).joinToString(", ") {
                    changed.getJSONObject(it).let { item -> item.getString("path") + " (" + item.getString("kind") + ")" }
                }
            } ?: ""
            )
        "fs_read" -> read(args)
        "fs_write" -> {
            val file = Workspace.resolve(args.getString("path"), write = true)
            file.parentFile?.mkdirs()
            file.writeText(args.getString("content"))
            shown(file)
            "Записано: ${Workspace.display(file)} (${file.length()} байт)"
        }
        "fs_edit" -> edit(args).also { shown(Workspace.resolve(args.getString("path"))) }
        "fs_delete" -> {
            val file = Workspace.resolve(args.getString("path"))
            if (!Workspace.remove(args.getString("path"))) throw IllegalArgumentException("Нет такого файла: ${args.getString("path")}")
            shown(file)
            "Удалено: ${Workspace.display(file)}"
        }
        "js_eval" -> {
            val console = Engine.host.console
            val mark = console.mark()
            val timeout = args.optInt("timeout_s", EVAL_TIMEOUT_S) * 1000L
            val outcome = runCatching { Engine.evalShown(args.getString("code"), timeout) }
            val printed = console.since(mark).joinToString("\n")
            val value = outcome.getOrElse { "Ошибка: " + Engine.describe(it) }
            if (printed.isEmpty()) value else "$value\n--- console ---\n$printed"
        }
        "ui_open" -> {
            val activity = activity()
            val mark = activity.page.console.mark()
            // In a draft the screen shown is the draft's.
            val loaded = activity.page.openAndWait((app.assi.core.Drafts.active?.let { "~$it/" } ?: "") + args.getString("path").trimStart('/'))
            // A browser on the local network follows to the same screen.
            Pages.broadcast("ui.opened", JSONObject.quote(args.getString("path")))
            Thread.sleep(args.optLong("wait_ms", 800).coerceIn(0, 15_000))
            val printed = activity.page.console.since(mark)
            val problems = printed.filter { it.startsWith("[error]") || it.startsWith("[warning]") }
            buildString {
                append(if (loaded) "Экран открыт." else "Экран не успел загрузиться за 10 с.")
                append(if (problems.isEmpty()) " Ошибок в консоли нет." else "\nКонсоль:\n" + problems.joinToString("\n"))
            }
        }
        "ui_eval" -> pageEval(args.getString("code"), args.optLong("wait_ms", 400))
        "ui_console" -> activity().page.console.tail(args.optInt("lines", 40)).joinToString("\n").ifEmpty { "Консоль пуста" }
        "ui_screenshot" -> {
            val shot = activity().screenshot() ?: throw IllegalStateException("Не удалось снять экран")
            images.add(shot)
            "Снимок приложен следующим сообщением."
        }
        "shell" -> {
            val result = Shell.run(args.getString("command"), args.optLong("timeout_s", 60) * 1000, args.optBoolean("privileged", false))
            (if (result.code == 0) "" else "Код выхода ${result.code}\n") + result.out.ifBlank { "(без вывода)" }
        }
        "linux" -> {
            val result = Linux.run(args.getString("command"), args.optLong("timeout_s", 120) * 1000)
            (if (result.code == 0) "" else "Код выхода ${result.code}\n") + result.out.ifBlank { "(без вывода)" }
        }
        "phone_reply" -> {
            Notices.reply(args.getString("key"), args.getString("text"))
            "Ответ отправлен."
        }
        "phone_screen" -> describeScreen()
        "phone_look" -> {
            images.add(Screen.shot())
            "Снимок приложен следующим сообщением."
        }
        "phone_act" -> {
            val done = when (val action = args.getString("action")) {
                "click" -> Screen.click(args.getInt("index"))
                "type" -> Screen.type(args.getString("text"), if (args.has("index")) args.getInt("index") else null)
                "scroll_down" -> Screen.scroll(args.getInt("index"), true)
                "scroll_up" -> Screen.scroll(args.getInt("index"), false)
                "tap" -> Screen.tap(args.getDouble("x").toFloat(), args.getDouble("y").toFloat())
                "swipe" -> Screen.swipe(
                    args.getDouble("x").toFloat(), args.getDouble("y").toFloat(),
                    args.getDouble("x2").toFloat(), args.getDouble("y2").toFloat(),
                )
                "open_app" -> {
                    Screen.openApp(args.getString("text"))
                    true
                }
                else -> Screen.global(action)
            }
            // Give the app a moment to react, then show what the screen became.
            Thread.sleep(900)
            (if (done) "Сделано." else "Действие не сработало.") + " Экран теперь:\n" + describeScreen()
        }
        "phone_notifications" -> {
            val limit = args.optInt("limit", 30)
            val active = runCatching { Notices.active() }.getOrElse { throw it }
            val seen = HashSet<String>()
            val lines = ArrayList<String>()
            for (list in listOf(active, Notices.recent(limit))) {
                for (i in 0 until list.length()) {
                    val item = list.getJSONObject(i)
                    if (!seen.add(item.optString("key")) || lines.size >= limit) continue
                    val time = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(java.util.Date(item.optLong("time")))
                    lines.add(
                        "$time · ${item.optString("app")} · ${item.optString("title")}: ${item.optString("text").take(400)}" +
                            if (item.optBoolean("canReply")) "\n   можно ответить, ключ: ${item.optString("key")}" else "",
                    )
                }
            }
            lines.joinToString("\n").ifEmpty { "Уведомлений нет." }
        }
        "snapshot_list" -> Workspace.snapshots().toString(1)
        "snapshot_restore" -> {
            Workspace.restore(args.getString("id"))
            MainActivity.current?.reloadPages()
            "Рабочая папка возвращена к снимку ${args.getString("id")}"
        }
        else -> throw IllegalArgumentException("Нет инструмента $name")
    }

    private fun describeScreen(): String {
        val screen = Screen.read()
        return "Приложение: ${screen.optString("app")} (${screen.optString("package")})\n" +
            screen.optString("outline").ifBlank { "(на экране нет читаемых элементов)" }
    }

    private fun activity(): MainActivity =
        MainActivity.current ?: throw IllegalStateException("Приложение свёрнуто: экрана сейчас нет")

    private fun list(dir: File, depth: Int): String {
        if (!dir.exists()) throw IllegalArgumentException("Нет такой папки")
        if (dir.isFile) return "${Workspace.display(dir)} (${dir.length()} байт)"
        val out = StringBuilder()
        fun walk(current: File, indent: String, level: Int) {
            val children = current.listFiles().orEmpty().sortedWith(compareBy({ it.isFile }, { it.name }))
            for (child in children) {
                if (child.isDirectory) {
                    out.append(indent).append(child.name).append("/\n")
                    if (level < depth) walk(child, "$indent  ", level + 1)
                } else {
                    out.append(indent).append(child.name).append("  ").append(child.length()).append('\n')
                }
                if (out.length > 20_000) return
            }
        }
        walk(dir, "", 1)
        return out.toString().ifEmpty { "(пусто)" }
    }

    private fun read(args: JSONObject): String {
        val file = Workspace.resolve(args.getString("path"))
        if (!file.isFile) throw IllegalArgumentException("Нет такого файла: ${args.getString("path")}")
        val text = file.readText()
        if (!args.has("offset") && !args.has("limit")) {
            return if (text.length <= READ_LIMIT) text
            else text.take(READ_LIMIT) + "\n… файл обрезан (${text.length} символов); читайте частями через offset и limit"
        }
        val lines = text.lines()
        val from = (args.optInt("offset", 1) - 1).coerceIn(0, lines.size)
        val to = (from + args.optInt("limit", 400)).coerceAtMost(lines.size)
        return lines.subList(from, to).joinToString("\n").take(READ_LIMIT) + "\n(строки ${from + 1}–$to из ${lines.size})"
    }

    private fun edit(args: JSONObject): String {
        val file = Workspace.resolve(args.getString("path"), write = true)
        if (!file.isFile) throw IllegalArgumentException("Нет такого файла: ${args.getString("path")}")
        val old = args.getString("old")
        val text = file.readText()
        val count = text.split(old).size - 1
        if (old.isEmpty() || count == 0) throw IllegalArgumentException("Фрагмент `old` не найден в файле")
        val all = args.optBoolean("all", false)
        if (count > 1 && !all) {
            throw IllegalArgumentException("Фрагмент `old` встречается $count раз: уточните его или укажите all")
        }
        val replacement = args.getString("new")
        file.writeText(if (all) text.replace(old, replacement) else text.replaceFirst(old, replacement))
        return "Изменено: ${Workspace.display(file)}" + if (all && count > 1) " ($count замен)" else ""
    }
}
