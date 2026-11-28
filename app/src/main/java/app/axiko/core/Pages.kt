package app.axiko.core

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import app.axiko.App
import app.axiko.MainActivity
import app.axiko.sense.OnScreenAssistant
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/**
 * Pages are plain HTML served from the workspace under one local origin. Only that origin
 * gets the bridge to the engine; any other site shown in the app is an ordinary web page.
 */
object Pages {
    const val HOST = "axiko.local"
    const val ORIGIN = "https://$HOST"
    const val SUSPICIONS_VIEW = "ui/settings/index.html?tab=system&screenReview=factory#suspicions"
    private const val KIT_CSS = "ui/kit/kit.css"
    private const val KIT_JS = "ui/kit/kit.js"

    private val bridges = CopyOnWriteArrayList<Bridge>()
    private val main = Handler(Looper.getMainLooper())

    private val mimes = mapOf(
        "html" to "text/html", "htm" to "text/html", "js" to "text/javascript",
        "mjs" to "text/javascript", "css" to "text/css", "json" to "application/json",
        "svg" to "image/svg+xml", "png" to "image/png", "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp",
        "ico" to "image/x-icon", "txt" to "text/plain", "md" to "text/plain",
        "woff2" to "font/woff2", "woff" to "font/woff", "ttf" to "font/ttf",
        "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg",
        "mp4" to "video/mp4", "webm" to "video/webm", "pdf" to "application/pdf",
        "wasm" to "application/wasm",
    )

    fun url(path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) path
        else "$ORIGIN/" + path.trimStart('/')

    fun isStockScreenReview(uri: Uri): Boolean =
        uri.scheme == "https" && uri.encodedAuthority == HOST &&
            uri.path == "/ui/settings/index.html" && uri.getQueryParameter("screenReview") == "factory"

    fun register(bridge: Bridge) {
        bridges.add(bridge)
    }

    fun unregister(bridge: Bridge) {
        bridges.remove(bridge)
    }

    /** Session events stay in their own panel, including when the main activity is absent. */
    fun assistantEvent(session: String, event: String, data: JSONObject = JSONObject()) {
        val message = JSONObject().put("event", event).put("data", data).toString()
        main.post { bridges.filter { it.name == "assistant" && it.assistantScope() == session }.forEach { it.push(message) } }
    }

    /** Browsers on the local network get the same events (see Lan). */
    @Volatile
    var onBroadcast: ((String) -> Unit)? = null

    private val STREAMED = listOf("{\"type\":\"delta\"", "{\"type\":\"thought\"")

    fun broadcast(event: String, json: String) {
        val message = "{\"event\":${JSONObject.quote(event)},\"data\":$json}"
        onBroadcast?.invoke(message)
        // An answer and the thinking arrive letter by letter — thousands of events in a long
        // work. Screens nobody sees would only queue them up and choke on them when seen again,
        // so out of sight these are not sent: a chat reads the conversation anew when it is shown.
        if (event == "agent" && !MainActivity.shown && STREAMED.any { json.startsWith(it) }) return
        val appearance = event == "theme" || event == "mascot"
        main.post { bridges.filter { it.name != "assistant" || appearance }.forEach { it.push(message) } }
    }

    /** The file behind a local URL: the workspace, module data, or the factory copy in safe mode. */
    fun serve(rawPath: String, factoryUi: Boolean = false): WebResourceResponse {
        // A screen of a draft: `/~<id>/…` is the same path seen through the draft's layer.
        // The WebView asks for it already "within" the draft, so the prefix goes either way.
        Drafts.of(rawPath)?.let { draft ->
            return Drafts.within(draft) { serve(Uri.decode(rawPath).trimStart('/').substringAfter('/', ""), factoryUi) }
        }
        var path = Uri.decode(rawPath).trimStart('/')
        if (path.isEmpty() || path.endsWith("/")) path += "index.html"
        val stream: InputStream? = runCatching {
            when {
                path.startsWith("data/") ->
                    File(Workspace.data, path.removePrefix("data/")).takeIf { it.isFile }?.inputStream()
                (factoryUi || Workspace.safeMode) && path.startsWith("ui/") ->
                    App.instance.assets.open("factory/$path")
                else -> Workspace.resolve(path).takeIf { it.isFile }?.inputStream()
            }
        }.getOrNull()
        val headers = mapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "*")
        if (stream == null) {
            val body = "<!doctype html><meta charset=utf-8><body style='font:16px sans-serif;padding:24px;background:#111;color:#bbb'>" +
                "Нет файла <b>$path</b></body>"
            return WebResourceResponse(
                "text/html", "utf-8", 404, "Not Found", headers, ByteArrayInputStream(body.toByteArray()),
            )
        }
        val mime = mimes[path.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
        val text = mime.startsWith("text/") || mime == "application/json" || mime == "image/svg+xml"
        // The chosen theme is the tail of the kit's stylesheet: every screen that links the kit wears it.
        // The dictionary of the interface's language is the tail of the kit's script in the same way.
        val body = when (path) {
            KIT_CSS -> java.io.SequenceInputStream(stream, ByteArrayInputStream(Themes.css().toByteArray()))
            KIT_JS -> java.io.SequenceInputStream(stream, ByteArrayInputStream(I18n.script().toByteArray()))
            else -> stream
        }
        return WebResourceResponse(mime, if (text) "utf-8" else null, 200, "OK", headers, body)
    }
}

/** One WebView with its link to the engine, its console and its load state. */
@SuppressLint("SetJavaScriptEnabled")
class Bridge(val web: WebView, val name: String) : WebViewCompat.WebMessageListener {
    val console = RingLog(300)

    /** Called when the page reports itself alive. */
    var onHello: (() -> Unit)? = null

    /** Called when the main document and its scripts have loaded. */
    var onLoaded: (() -> Unit)? = null

    /** Called when the page's process is gone, in place of building the app's screens anew. */
    var onGone: (() -> Unit)? = null

    /** The native assistant window binds every request to the session it is showing. */
    var assistantScope: () -> String? = { null }
    var assistantHitAreas: ((JSONObject) -> Boolean)? = null
    var assistantFactory = false

    /** The address of the page it shows now. */
    @Volatile
    var location = ""
        private set

    /**
     * Where events go: the page, and the local pages framed in it (the home screen's cards).
     * A new page starts the list over; a page's frames are kept to the latest few.
     */
    private val proxies = CopyOnWriteArrayList<JavaScriptReplyProxy>()

    @Volatile
    private var loading: CountDownLatch? = null

    private var scalePercent = 100

    /** The scale its pages are shown at, per cent. */
    val scaled: Int get() = scalePercent
    private var scaleScript: androidx.webkit.ScriptHandler? = null

    /**
     * The owner's scale of the interface for the pages of this view (see [UiScale]); they take
     * it at their next load. True when it differs from the one before. Main thread only.
     */
    fun scale(percent: Int): Boolean {
        if (percent == scalePercent && scaleScript != null) return false
        val changed = percent != scalePercent
        scalePercent = percent
        scaleScript?.remove()
        scaleScript = WebViewCompat.addDocumentStartJavaScript(web, UiScale.script(percent), setOf(Pages.ORIGIN))
        return changed
    }

    init {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = name != "assistant"
            allowContentAccess = name != "assistant"
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = if (name == "assistant") WebSettings.MIXED_CONTENT_NEVER_ALLOW else WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            setGeolocationEnabled(name != "assistant")
        }
        web.setBackgroundColor(0)
        web.overScrollMode = WebView.OVER_SCROLL_NEVER
        WebView.setWebContentsDebuggingEnabled(true)

        val origins = setOf(Pages.ORIGIN)
        WebViewCompat.addWebMessageListener(web, "__axiko", origins, this)
        val client = App.instance.assets.open("engine/page.js").use { it.readBytes().decodeToString() }
        WebViewCompat.addDocumentStartJavaScript(web, client, origins)

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = when {
                name == "assistant" -> if (assistantResource(request.url, request.isForMainFrame)) {
                    Pages.serve(request.url.path.orEmpty(), assistantFactory)
                } else WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                request.url.host == Pages.HOST -> {
                    // What a draft's screen asks for by an absolute address (/ui/kit/…, /modules/…) is the draft's too.
                    val path = request.url.path.orEmpty()
                    val from = Drafts.of(path) ?: Drafts.of(request.requestHeaders["Referer"]?.let { Uri.parse(it).path })
                    val stock = name == "page" && (Pages.isStockScreenReview(request.url) ||
                        request.requestHeaders["Referer"]?.let { Pages.isStockScreenReview(Uri.parse(it)) } == true)
                    Drafts.within(from) { Pages.serve(path, stock) }
                }
                WebCache.wants(request) -> WebCache.serve(request)
                else -> null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (name == "assistant") return !assistantResource(request.url, request.isForMainFrame)
                if (request.url.host == Pages.HOST || !request.isForMainFrame) return false
                // Another site: the system browser shows it, the bridge stays with local pages.
                runCatching {
                    view.context.startActivity(
                        Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                return true
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                location = url
                // The menu marks the screen the owner is on.
                if (name == "page") MainActivity.current?.screenChanged(url)
                if (name == "page") Pages.broadcast("where", JSONObject.quote(url.removePrefix(Pages.ORIGIN + "/").substringBefore('?')))
            }

            override fun onPageFinished(view: WebView, url: String) {
                loading?.countDown()
                onLoaded?.invoke()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // The system may kill the renderer when memory is short. A WebView without its
                // renderer never draws again: the shell builds its screens anew.
                console.add("[error] Процесс страницы завершился")
                onGone?.let {
                    it()
                    return true
                }
                MainActivity.current?.rebuild()
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                val level = message.messageLevel().name.lowercase()
                val where = message.sourceId().removePrefix(Pages.ORIGIN + "/")
                console.add("[$level] ${message.message()} ($where:${message.lineNumber()})")
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                if (name == "assistant") request.deny() else request.grant(request.resources)
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean = name != "assistant" && MainActivity.current?.pickFiles(params, callback) == true

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
                callback.invoke(origin, true, true)
        }
        Pages.register(this)
    }

    fun destroy() {
        Pages.unregister(this)
        web.destroy()
    }

    private companion object {
        /** Framed pages that hear events: enough for the cards of a home screen. */
        const val FRAMES = 24

        /** What a page asks of the owner's screen; on the agent's own screen it is that screen's business. */
        val OWN = setOf("ui.open", "ui.back", "ui.chat", "ui.stage")
        val SCREEN_REVIEW_ACTIONS = setOf("suspicions.dismiss", "suspicions.restore", "suspicions.merge", "suspicions.applyMerge")

        val main = Handler(Looper.getMainLooper())
    }

    /** Opens the page and waits until it has loaded (call off the main thread). */
    fun openAndWait(path: String, timeoutMs: Long = 10_000): Boolean {
        val latch = CountDownLatch(1)
        loading = latch
        main.post { web.loadUrl(Pages.url(path)) }
        return latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** Runs code in the page and returns its value as JSON text (call off the main thread). */
    fun eval(code: String, timeoutMs: Long = 10_000): String? {
        val done = CountDownLatch(1)
        var value: String? = null
        main.post {
            web.evaluateJavascript(code) {
                value = it
                done.countDown()
            }
        }
        done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return value
    }

    /** The page as a JPEG in base64, or the part of it within [clip] (call off the main thread). */
    fun shot(background: Int, maxWidth: Int = 720, clip: android.graphics.Rect? = null): String? {
        val done = CountDownLatch(1)
        var encoded: String? = null
        main.post {
            try {
                encoded = Shots.take(web, background, maxWidth, clip)
            } finally {
                done.countDown()
            }
        }
        done.await(8, java.util.concurrent.TimeUnit.SECONDS)
        return encoded
    }

    fun push(message: String) {
        proxies.forEach { runCatching { it.postMessage(message) } }
    }

    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        val request = runCatching { JSONObject(message.data ?: return) }.getOrNull() ?: return
        val id = request.optLong("id")
        if (name == "assistant") {
            val scope = assistantScope()
            if (scope == null || !isMainFrame || sourceOrigin.toString().trimEnd('/') != Pages.ORIGIN ||
                !assistantResource(Uri.parse(view.url ?: ""), true)) {
                reply(replyProxy, id, null, "Панель ассистента закрыта")
                return
            }
            when (request.optString("op")) {
                "hello" -> {
                    proxies.clear()
                    proxies.add(replyProxy)
                    reply(replyProxy, id, null, null)
                }
                "native" -> {
                    val method = request.optString("method")
                    if (method == "assistant.hitAreas") {
                        // WebMessageListener runs on the UI thread, where the window geometry lives.
                        answer(replyProxy, id) {
                            val layout = JSONArray(request.optString("args", "[]")).getJSONObject(0)
                            Native.encode(assistantHitAreas?.invoke(layout) == true)
                        }
                        return
                    }
                    Engine.pool.execute {
                        answer(replyProxy, id) {
                            val result = OnScreenAssistant.call(method, JSONArray(request.optString("args", "[]")), scope)
                            if (method == "assistant.ready") main.post { if (assistantScope() == scope) onHello?.invoke() }
                            Native.encode(result)
                        }
                    }
                }
                else -> reply(replyProxy, id, null, "Эта панель использует API assistant.*")
            }
            return
        }
        // Only the foreground Settings document can ask Android to install a verified release.
        if (request.optString("op") == "native" && request.optString("method") == "update.install") {
            val activity = MainActivity.current
            val url = Uri.parse(view.url.orEmpty())
            answer(replyProxy, id) {
                check(activity != null && activity.page === this && isMainFrame &&
                    sourceOrigin.toString().trimEnd('/') == Pages.ORIGIN &&
                    url.path == "/ui/settings/index.html"
                ) { I18n.t("Откройте настройки Axiko на телефоне.") }
                val version = JSONArray(request.optString("args", "[]")).getString(0)
                Native.encode(app.axiko.update.UpdateInstaller.request(activity, version))
            }
            return
        }
        if (request.optString("op") == "native" && request.optString("method") in SCREEN_REVIEW_ACTIONS) {
            val activity = MainActivity.current
            if (activity == null || activity.page !== this || !isMainFrame || !MainActivity.visible ||
                sourceOrigin.toString().trimEnd('/') != Pages.ORIGIN ||
                Uri.parse(view.url.orEmpty()).path != "/ui/settings/index.html"
            ) {
                reply(replyProxy, id, null, I18n.t("Откройте настройки Axiko на телефоне."))
                return
            }
            val args = runCatching { JSONArray(request.optString("args", "[]")) }.getOrElse {
                reply(replyProxy, id, null, Engine.describe(it))
                return
            }
            screenReview(activity, replyProxy, id, request.getString("method"), args)
            return
        }
        // A draft's screen calls the draft's code.
        val draft = Drafts.of(runCatching { Uri.parse(view.url).path }.getOrNull())
        when (request.optString("op")) {
            "hello" -> {
                if (isMainFrame) {
                    proxies.clear()
                    proxies.add(replyProxy)
                } else {
                    proxies.add(replyProxy)
                    while (proxies.size > FRAMES + 1) proxies.removeAt(1)
                }
                onHello?.invoke()
                reply(replyProxy, id, null, null)
            }
            // Off the main thread: some operations wait for a model or for the screen.
            // The agent's own screen moves itself and leaves the owner's alone: a game it checks
            // must not take the owner's whole display, a link must not lead the owner away.
            "native" -> if (name == "probe" && request.optString("method") in OWN) {
                val args = runCatching { JSONArray(request.optString("args", "[]")) }.getOrDefault(JSONArray())
                when (request.optString("method")) {
                    "ui.open" -> web.loadUrl(Pages.url((draft?.let { "~$it/" } ?: "") + args.optString(0).trimStart('/')))
                    "ui.back" -> if (web.canGoBack()) web.goBack()
                }
                reply(replyProxy, id, "true", null)
            } else Engine.pool.execute {
                answer(replyProxy, id) {
                    Drafts.within(draft) { Native.encode(Native.call(request.getString("method"), JSONArray(request.optString("args", "[]")))) }
                }
            }
            "call" -> Engine.pool.execute {
                answer(replyProxy, id) {
                    Drafts.within(draft) { Engine.rpc(request.getString("path"), request.getString("fn"), request.optString("args", "[]")) }
                }
            }
            "eval" -> Engine.pool.execute {
                answer(replyProxy, id) { Drafts.within(draft) { Engine.call("__evalJson", request.getString("code")) as String? } }
            }
        }
    }

    private fun screenReview(activity: MainActivity, proxy: JavaScriptReplyProxy, id: Long, method: String, args: JSONArray) {
        val installScreen = method == "suspicions.restore" || method == "suspicions.applyMerge"
        fun finish(approved: Boolean) {
            Engine.pool.execute {
                var changed = false
                answer(proxy, id) {
                    val result = if (!approved) {
                        if (method == "suspicions.merge") null else ScreenSuspicions.state(activity)
                    } else {
                        val path = args.getString(0)
                        val revision = args.getString(1)
                        when (method) {
                            "suspicions.dismiss" -> ScreenSuspicions.dismiss(activity, path, revision)
                            "suspicions.merge" -> ScreenMerge.start(activity, path, revision)
                            "suspicions.applyMerge" -> ScreenMerge.apply(activity, path, revision, args.getString(2))
                            else -> ScreenSuspicions.restore(activity, path, revision)
                        }.also {
                            if (method != "suspicions.merge") Pages.broadcast("suspicions.changed", it.toString())
                            changed = installScreen
                        }
                    }
                    Native.encode(result)
                }
                if (changed) activity.reloadPages()
            }
        }
        val dialog = when (method) {
            "suspicions.dismiss" -> Triple("Снять подозрение?",
                "Axiko скроет отметку для текущей заводской версии экрана. Рабочая версия сохранится.",
                "Снять подозрение")
            "suspicions.merge" -> Triple("Объединить с помощью агента?",
                "Axiko передаст сравнение выбранного экрана настроенной модели. Результат можно будет просмотреть перед применением.",
                "Подготовить объединение")
            "suspicions.applyMerge" -> Triple("Применить объединение?",
                "Axiko сохранит текущую версию экрана в истории правок.", "Применить")
            else -> Triple("Восстановить заводской экран?",
                "Рабочая версия этого экрана будет сохранена в истории правок.", "Восстановить")
        }
        var approved = false
        AlertDialog.Builder(activity)
            .setTitle(I18n.t(dialog.first))
            .setMessage(I18n.t(dialog.second))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(I18n.t(dialog.third)) { _, _ -> approved = true }
            .setOnDismissListener { finish(approved) }
            .show()
    }

    private fun assistantResource(uri: Uri, mainFrame: Boolean): Boolean {
        if (uri.scheme != "https" || uri.encodedAuthority != Pages.HOST) return false
        val path = uri.path.orEmpty()
        if ('%' in path || '\\' in path || path.split('/').any { it == "." || it == ".." }) return false
        if (path.startsWith("/ui/assistant/")) return true
        return !mainFrame && listOf("/ui/kit/", "/ui/mascot/", "/ui/vendor/").any(path::startsWith)
    }

    private inline fun answer(proxy: JavaScriptReplyProxy, id: Long, block: () -> String?) {
        try {
            reply(proxy, id, block(), null)
        } catch (error: Throwable) {
            reply(proxy, id, null, Engine.describe(error))
        }
    }

    private fun reply(proxy: JavaScriptReplyProxy, id: Long, result: String?, error: String?) {
        val message = JSONObject().put("id", id)
        if (error != null) message.put("error", error) else message.put("ok", true)
        if (result != null) message.put("result", result)
        web.post { runCatching { proxy.postMessage(message.toString()) } }
    }
}
