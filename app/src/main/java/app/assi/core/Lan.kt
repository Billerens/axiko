package app.assi.core

import app.assi.App
import app.assi.agent.Agent
import app.assi.agent.Conversation
import app.assi.agent.Inbox
import app.assi.agent.Tools
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The app in a browser on the same network: the phone serves its own screens and chat over
 * HTTP, so the owner's modules are at hand on a computer too. The page there talks to the
 * same engine and the same agents.
 *
 * The phone is found by a name the owner chose — `http://assi.local:8765` (see [Mdns]) — and
 * lets in whoever knows the key, also the owner's own; the browser is asked for it once.
 */
object Lan {
    private const val COOKIE = "assi"
    private const val FACTORY_COOKIE = "assi_factory"
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "assi-lan").apply { isDaemon = true } }
    private val streams = CopyOnWriteArrayList<OutputStream>()

    @Volatile
    private var server: ServerSocket? = null

    val wanted: Boolean get() = App.prefs.getString("lan.on", "off") == "on"
    val port: Int get() = App.prefs.getString("lan.port", null)?.toIntOrNull() ?: 8765

    /** The phone's name on the network, without ".local". */
    val name: String get() = App.prefs.getString("lan.name", null)?.takeIf { it.isNotBlank() } ?: "assi"

    /** What a browser that was let in keeps instead of the key itself. */
    private val pass: String
        get() = java.security.MessageDigest.getInstance("SHA-256").digest("assi:$code".toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }

    /**
     * The owner's own name, key and port: `{name?, code?, port?}`; an empty key asks for a new
     * random one. What is given is checked, and a running server takes it up at once.
     */
    fun setup(values: JSONObject): JSONObject {
        val edit = App.prefs.edit()
        val was = port
        if (values.has("name")) {
            val wanted = values.optString("name").trim().lowercase().removeSuffix(".local")
            require(Regex("[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?").matches(wanted)) { I18n.t("Имя в сети — латинские буквы, цифры и дефис, не длиннее 32 знаков") }
            edit.putString("lan.name", wanted)
        }
        if (values.has("code")) {
            val wanted = values.optString("code").trim()
            require(wanted.isEmpty() || (wanted.length >= 4 && wanted.all { it.code in 33..126 })) {
                I18n.t("Ключ — не короче 4 знаков: латинские буквы, цифры и знаки без пробелов")
            }
            if (wanted.isEmpty()) edit.remove("lan.code") else edit.putString("lan.code", wanted)
        }
        if (values.has("port")) {
            val wanted = values.optInt("port")
            require(wanted in 1024..65535) { I18n.t("Порт — число от 1024 до 65535") }
            edit.putString("lan.port", wanted.toString())
        }
        edit.commit()
        if (server != null) {
            if (port != was) {
                stop()
                start()
            } else if (Mdns.name != name) {
                Mdns.start(name)
            }
        }
        return status()
    }

    /** The key that lets a browser in: the owner's own, or a random one made once and kept. */
    val code: String
        get() = App.prefs.getString("lan.code", null) ?: run {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            val random = SecureRandom()
            (1..8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("").also {
                App.prefs.edit().putString("lan.code", it).apply()
            }
        }

    fun set(on: Boolean) {
        App.prefs.edit().putString("lan.on", if (on) "on" else "off").apply()
        if (on) start() else stop()
    }

    /**
     * `{on, running, port, name, code, named, addresses}` — the addresses to open in a browser:
     * the one by name first (when the name is announced), then the ones by number.
     */
    fun status(): JSONObject {
        val addresses = JSONArray()
        runCatching {
            for (network in NetworkInterface.getNetworkInterfaces()) {
                if (!network.isUp || network.isLoopback) continue
                for (address in network.inetAddresses) {
                    if (address is Inet4Address && address.isSiteLocalAddress) {
                        if (addresses.length() == 0 && Mdns.running) addresses.put("http://$name.local:$port")
                        addresses.put("http://${address.hostAddress}:$port")
                    }
                }
            }
        }
        return JSONObject().put("on", wanted).put("running", server != null).put("port", port).put("name", name)
            .put("named", Mdns.running).put("addresses", addresses).put("code", code)
    }

    @Synchronized
    fun start() {
        if (server != null || !wanted) return
        val socket = runCatching { ServerSocket(port) }.getOrElse {
            Engine.host.log("error", "Доступ с компьютера не запустился: порт $port занят")
            return
        }
        server = socket
        Mdns.start(name)
        Pages.onBroadcast = { message -> push(message) }
        Thread({
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                workers.execute { runCatching { serve(client) } }
            }
        }, "assi-lan-accept").apply { isDaemon = true }.start()
        // Dead event streams are noticed only on a write: a comment line every so often does it.
        Thread({
            while (server === socket) {
                Thread.sleep(20_000)
                push(null)
            }
        }, "assi-lan-ping").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        Mdns.stop()
        Pages.onBroadcast = null
        streams.forEach { runCatching { it.close() } }
        streams.clear()
    }

    /** Sends an event to every browser that listens; null is a keep-alive. */
    private fun push(message: String?) {
        val bytes = (if (message == null) ": ping\n\n" else "data: $message\n\n").toByteArray()
        for (stream in streams) {
            try {
                synchronized(stream) {
                    stream.write(bytes)
                    stream.flush()
                }
            } catch (gone: Exception) {
                streams.remove(stream)
            }
        }
    }

    // ---- HTTP ---------------------------------------------------------------------------------

    private class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: ByteArray)

    private fun read(input: InputStream): Request? {
        val head = StringBuilder()
        var matched = 0
        val end = "\r\n\r\n"
        while (matched < 4) {
            val byte = input.read()
            if (byte < 0 || head.length > 32_768) return null
            head.append(byte.toChar())
            matched = if (byte.toChar() == end[matched]) matched + 1 else if (byte.toChar() == '\r') 1 else 0
        }
        val lines = head.toString().trim().split("\r\n")
        val first = lines[0].split(' ')
        if (first.size < 2) return null
        val headers = lines.drop(1).filter { it.contains(':') }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > 64 * 1024 * 1024) return null
        val body = ByteArray(length)
        var got = 0
        while (got < length) {
            val read = input.read(body, got, length - got)
            if (read < 0) return null
            got += read
        }
        val target = first[1]
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        return Request(first[0], URLDecoder.decode(target.substringBefore('?'), "UTF-8"), query, headers, body)
    }

    private fun send(out: OutputStream, status: String, type: String, body: ByteArray, extra: String = "") {
        out.write(
            ("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n$extra\r\n").toByteArray(),
        )
        out.write(body)
        out.flush()
    }

    private val deskFiles by lazy {
        DeskFiles(
            Workspace.root,
            locate = { path, write -> Workspace.resolve(path, write) },
            allowedRoots = { listOfNotNull(Workspace.root, Drafts.active?.let { Drafts.files(it) }) },
            beforeWrite = { path ->
                if (Drafts.active == null) Workspace.snapshot("Перед сохранением из web: " + path)
            },
        )
    }

    private fun factoryHeader(value: String?): String = when (value) {
        "1" -> "Set-Cookie: " + FACTORY_COOKIE + "=1; Path=/; HttpOnly; SameSite=Lax\r\n"
        "0" -> "Set-Cookie: " + FACTORY_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax\r\n"
        else -> ""
    }

    private fun factoryMode(request: Request): Boolean {
        if (Workspace.safeMode) return true
        if (request.query["factory"] == "1") return true
        if (request.query["factory"] == "0") return false
        return request.headers["cookie"].orEmpty().split(';').any { it.trim() == FACTORY_COOKIE + "=1" }
    }

    private fun refererPath(request: Request): String? =
        runCatching { URI(request.headers["referer"].orEmpty()).path }.getOrNull()

    /** Match the phone's draft resource handling, including absolute CSS/JS URLs in a preview. */
    private fun servePage(out: OutputStream, request: Request, factory: Boolean) {
        val draft = Drafts.of(request.path) ?: Drafts.of(refererPath(request))
        Drafts.within(draft) {
            val path = if (Drafts.of(request.path) != null) "/" + request.path.trimStart('/').substringAfter('/') else request.path
            val deskPath = DeskAssets.path(path, factory)
            val extra = factoryHeader(request.query["factory"])
            if (deskPath != null) {
                // Recovery also uses the factory kit and bundled libraries. Do not append the
                // owner's theme/decor or runtime translations to these known-working assets.
                val bytes = DeskAssets.read(deskPath, factory, { Workspace.resolve(it) }) { relative ->
                    runCatching { App.instance.assets.open("factory/" + relative).use { it.readBytes() } }.getOrNull()
                }
                if (bytes == null) {
                    send(out, "404 Not Found", "text/plain; charset=utf-8", "Файл web-интерфейса не найден.".toByteArray(), extra)
                } else {
                    val type = when (deskPath.substringAfterLast('.')) {
                        "html" -> "text/html; charset=utf-8"
                        "css" -> "text/css; charset=utf-8"
                        "js", "mjs" -> "text/javascript; charset=utf-8"
                        "json" -> "application/json; charset=utf-8"
                        "svg" -> "image/svg+xml"
                        else -> "application/octet-stream"
                    }
                    send(out, "200 OK", type, if (type.startsWith("text/html")) injectClient(bytes) else bytes, extra)
                }
            } else {
                val response = Pages.serve(path)
                val bytes = response.data.use { it.readBytes() }
                val type = response.mimeType + (response.encoding?.let { "; charset=" + it } ?: "")
                send(out, response.statusCode.toString() + " " + response.reasonPhrase, type,
                    if (response.mimeType == "text/html") injectClient(bytes) else bytes, extra)
            }
        }
    }

    private fun injectClient(bytes: ByteArray): ByteArray {
        val html = String(bytes, Charsets.UTF_8)
        if (Regex("<script\\b[^>]*\\bsrc\\s*=\\s*[\"']/__assi/client\\.js(?:[?\"'])", RegexOption.IGNORE_CASE).containsMatchIn(html)) return bytes
        val tag = "<script src=\"/__assi/client.js\"></script>"
        val doctype = Regex("^\\s*<!doctype[^>]*>", RegexOption.IGNORE_CASE).find(html)
        return (if (doctype != null) html.replaceRange(doctype.range, doctype.value + tag) else tag + html).toByteArray()
    }

    private fun serve(socket: Socket) {
        socket.use {
            socket.soTimeout = 30_000
            val out = socket.getOutputStream()
            val request = read(BufferedInputStream(socket.getInputStream())) ?: return
            val cookie = request.headers["cookie"].orEmpty().split(';').map { it.trim() }
                .firstOrNull { it.startsWith("$COOKIE=") }?.substringAfter('=')
            val known = cookie == pass
            val factory = factoryMode(request)

            // Agents on other machines come in by the MCP door, with the code as a bearer token.
            if (request.path == "/mcp") {
                val token = request.headers["authorization"]?.removePrefix("Bearer ")?.trim() ?: request.query["k"]
                if (token != code && !known) {
                    // A key the owner chose may be short: guessing it is made slow.
                    if (token != null) Thread.sleep(WRONG_KEY_PAUSE)
                    send(out, "401 Unauthorized", "application/json", "{\"error\":\"unauthorized\"}".toByteArray(), "WWW-Authenticate: Bearer\r\n")
                } else if (request.method != "POST") {
                    send(out, "405 Method Not Allowed", "text/plain", ByteArray(0), "Allow: POST\r\n")
                } else {
                    socket.soTimeout = 0
                    val answer = runCatching { mcp(JSONObject(String(request.body))) }.getOrElse {
                        JSONObject().put("jsonrpc", "2.0").put("id", JSONObject.NULL)
                            .put("error", JSONObject().put("code", -32700).put("message", Engine.describe(it)))
                    }
                    if (answer == null) send(out, "202 Accepted", "text/plain", ByteArray(0))
                    else send(out, "200 OK", "application/json; charset=utf-8", answer.toString().toByteArray())
                }
                return
            }

            // The key lets the browser in — typed into the form or riding in the address — and is then dropped from view.
            val offered = request.query["k"]
            if (offered == code) {
                val recovery = request.query["factory"]?.takeIf { it == "1" || it == "0" }
                val destination = if (recovery == null) "/" else "/?factory=" + recovery
                send(out, "302 Found", "text/plain", ByteArray(0),
                    "Set-Cookie: $COOKIE=$pass; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax\r\n" +
                        factoryHeader(recovery) + "Location: " + destination + "\r\n")
                return
            }
            if (!known) {
                if (offered != null) Thread.sleep(WRONG_KEY_PAUSE)
                val page = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'><title>" + Agent.name + "</title>" +
                    "<body style='font:17px system-ui;background:#101010;color:#ededed;padding:40px'>" +
                    "<h2>" + Agent.name + "</h2><p>" + I18n.t("Введите ключ из настроек приложения: вкладка «Телефон», раздел «Доступ с компьютера».") + "</p>" +
                    (if (offered != null) "<p style='color:#ff8a8a'>" + I18n.t("Ключ не подошёл.") + "</p>" else "") +
                    "<form>" + (request.query["factory"]?.takeIf { it == "1" || it == "0" }
                        ?.let { "<input type=hidden name=factory value='" + it + "'>" } ?: "") +
                    "<input name=k type=password autofocus autocomplete=current-password style='font:inherit;padding:10px;border-radius:10px;border:1px solid #2e2e2e;background:#1a1a1a;color:inherit'>" +
                    " <button style='font:inherit;padding:10px 18px;border-radius:999px;border:0;background:#8b7cff;color:#0f0e17'>" + I18n.t("Войти") + "</button></form></body>"
                send(out, "401 Unauthorized", "text/html; charset=utf-8", page.toByteArray())
                return
            }

            when {
                request.path == "/__assi/events" -> {
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-store\r\nConnection: keep-alive\r\n\r\n".toByteArray())
                    out.flush()
                    socket.soTimeout = 0
                    streams.add(out)
                    // Held open until the browser leaves: reading notices that.
                    runCatching { while (socket.getInputStream().read() >= 0) Unit }
                    streams.remove(out)
                }
                request.path == "/__assi/rpc" && request.method == "POST" -> {
                    val answer = JSONObject()
                    try {
                        val payload = JSONObject(String(request.body))
                        val result = rpc(payload, factory, refererPath(request))
                        answer.put("ok", true)
                        if (result != null) answer.put("result", result)
                    } catch (error: Throwable) {
                        answer.put("error", Engine.describe(error))
                        if (error is DeskFiles.Conflict) {
                            answer.put("code", "conflict").put("revision", error.actualRevision ?: JSONObject.NULL)
                        }
                        if (error is DeskFiles.Missing) answer.put("code", "not_found")
                    }
                    send(out, "200 OK", "application/json; charset=utf-8", answer.toString().toByteArray())
                }
                else -> servePage(out, request, factory)
            }
        }
    }

    // ---- the phone as an MCP server ---------------------------------------------------------------

    private const val MCP_VERSION = "2025-06-18"
    private const val WRONG_KEY_PAUSE = 700L

    /**
     * One JSON-RPC message of the Model Context Protocol (Streamable HTTP, without sessions:
     * every request stands alone). Agents on the owner's other machines get the phone's tools —
     * its notifications, its screen, its files and modules, its shell — and can give the phone's
     * own agent a task. Returns null for a notification, which takes no answer.
     */
    private fun mcp(message: JSONObject): JSONObject? {
        if (!message.has("id")) return null
        val reply = JSONObject().put("jsonrpc", "2.0").put("id", message.get("id"))
        fun fail(code: Int, text: String) = reply.put("error", JSONObject().put("code", code).put("message", text))
        val params = message.optJSONObject("params") ?: JSONObject()
        return when (message.optString("method")) {
            "initialize" -> reply.put(
                "result",
                JSONObject().put("protocolVersion", params.optString("protocolVersion").ifBlank { MCP_VERSION })
                    .put("capabilities", JSONObject().put("tools", JSONObject()))
                    .put("serverInfo", JSONObject().put("name", "assi-phone").put("title", "Axiko на телефоне").put("version", "1"))
                    .put(
                        "instructions",
                        "Телефон владельца и приложение Axiko на нём. Инструменты phone_* читают экран и уведомления и действуют в приложениях, " +
                            "fs_* и js_eval работают с рабочей папкой Axiko (модули, данные), shell выполняет команды на телефоне, " +
                            "base_find отвечает по личным данным владельца, assi_ask поручает задачу агенту на телефоне.",
                    ),
            )
            "ping" -> reply.put("result", JSONObject())
            "tools/list" -> {
                val tools = JSONArray()
                tools.put(
                    JSONObject().put("name", "assi_ask")
                        .put("description", "Поручить задачу агенту Axiko на телефоне и получить его ответ. Он знает приложение, его модули и данные владельца и сам пользуется инструментами телефона.")
                        .put(
                            "inputSchema",
                            JSONObject("""{"type":"object","properties":{"task":{"type":"string","description":"Что сделать или узнать"},"agent":{"type":"string","description":"Кому; по умолчанию основной агент"}},"required":["task"]}"""),
                        ),
                )
                for (tool in Tools.offered()) {
                    tools.put(JSONObject().put("name", tool.getString("name")).put("description", tool.optString("description")).put("inputSchema", tool.getJSONObject("parameters")))
                }
                reply.put("result", JSONObject().put("tools", tools))
            }
            "tools/call" -> {
                val name = params.optString("name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                val content = JSONArray()
                var failed = false
                if (name == "assi_ask") {
                    val answer = runCatching { Agent.run(args.optString("agent").ifBlank { Agent.MAIN }, args.getString("task")) }
                    failed = answer.isFailure
                    content.put(JSONObject().put("type", "text").put("text", answer.getOrElse { Engine.describe(it) }.ifBlank { "(пустой ответ)" }))
                } else if (Tools.offered().none { it.getString("name") == name }) {
                    return fail(-32602, "Нет инструмента $name")
                } else {
                    val images = ArrayList<String>()
                    val result = Tools.run(name, args.toString(), Conversation(Agent.MAIN, lasting = false, parent = null), images)
                    failed = !result.ok
                    content.put(JSONObject().put("type", "text").put("text", result.text))
                    images.forEach { content.put(JSONObject().put("type", "image").put("data", it).put("mimeType", "image/jpeg")) }
                }
                reply.put("result", JSONObject().put("content", content).put("isError", failed))
            }
            else -> fail(-32601, "Метод не поддерживается: ${message.optString("method")}")
        }
    }

    private fun draftFrom(path: String?): String? {
        val raw = path.orEmpty().trimStart('/')
        if (!raw.startsWith('~')) return null
        return raw.substring(1).substringBefore('/').substringBefore('?').takeIf { it.isNotEmpty() }
    }

    /** A preview's calls use the same draft as its HTML and absolute CSS/JS resources. */
    private fun rpc(request: JSONObject, factory: Boolean, referer: String?): String? {
        val draft = if (request.has("draft")) {
            request.opt("draft")?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }
        } else {
            draftFrom(request.optString("where")) ?: draftFrom(referer)
        }
        require(draft == null || Drafts.exists(draft)) { "Черновик уже недоступен. Откройте рабочую версию." }
        return Drafts.within(draft) { rpcInWorkspace(request, factory) }
    }

    /** The phone's ordinary bridge plus the revision-aware web editor operations. */
    private fun rpcInWorkspace(request: JSONObject, factory: Boolean): String? = when (request.optString("op")) {
        "desk.read" -> deskFiles.read(request.getString("path")).json().toString()
        "desk.write" -> {
            require(request.has("expectedRevision")) { "Не указана версия открытого файла. Перечитайте его перед сохранением." }
            val expected = request.opt("expectedRevision")?.takeIf { it != JSONObject.NULL }?.toString()
            val entry = deskFiles.write(request.getString("path"), request.getString("text"), expected)
            val changed = JSONObject().put("path", entry.path).put("revision", entry.revision)
                .put("draft", Drafts.active ?: JSONObject.NULL)
            Pages.broadcast("workspace.changed", changed.toString())
            if (Drafts.active == null) {
                if (entry.path.startsWith("ui/nav")) Pages.broadcast("nav", "null")
                if (entry.path.startsWith("themes/")) Themes.changed()
                if (entry.path.startsWith("ui/mascot/") || entry.path.startsWith("ui/button/")) Pages.broadcast("mascot", "null")
            }
            entry.json().toString()
        }
        "desk.status" -> JSONObject().put("name", Agent.name).put("host", name).put("protocol", 1)
            .put("version", runCatching { App.instance.packageManager.getPackageInfo(App.instance.packageName, 0).versionName }.getOrDefault(""))
            .put("factory", factory).put("device", android.os.Build.MODEL)
            .put("capabilities", JSONArray(listOf("editable.hub", "files.revisions", "draft.rpc", "factory.recovery"))).toString()
        "native" -> {
            val method = request.getString("method")
            val args = JSONArray(request.optString("args", "[]"))
            if (method == "agent.send") {
                // "This screen" is the one open in the browser, not the one on the phone.
                val thread = if (args.isNull(2)) null else args.optString(2).takeIf { it.isNotBlank() }
                Native.encode(
                    Agent.chat(args.getString(0), thread).send(
                        args.getString(1),
                        if (args.optBoolean(3, false)) Inbox.take() else emptyList(),
                        request.optString("where").takeIf { it.isNotBlank() && !it.startsWith("ui/home/") },
                        Native.steer(args, 4),
                    ),
                )
            } else {
                Native.encode(Native.call(method, args))
            }
        }
        "call" -> Engine.rpc(request.getString("path"), request.getString("fn"), request.optString("args", "[]"))
        "eval" -> Engine.call("__evalJson", request.getString("code")) as String?
        else -> throw IllegalArgumentException("Неизвестный запрос")
    }
}
