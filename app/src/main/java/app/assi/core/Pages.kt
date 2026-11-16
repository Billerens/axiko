package app.assi.core

import android.annotation.SuppressLint
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
import app.assi.App
import app.assi.MainActivity
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
    const val HOST = "assi.local"
    const val ORIGIN = "https://$HOST"
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

    fun register(bridge: Bridge) {
        bridges.add(bridge)
    }

    fun unregister(bridge: Bridge) {
        bridges.remove(bridge)
    }

    /** Browsers on the local network get the same events (see Lan). */
    @Volatile
    var onBroadcast: ((String) -> Unit)? = null

    fun broadcast(event: String, json: String) {
        val message = "{\"event\":${JSONObject.quote(event)},\"data\":$json}"
        onBroadcast?.invoke(message)
        main.post { bridges.forEach { it.push(message) } }
    }

    /** The file behind a local URL: the workspace, module data, or the factory copy in safe mode. */
    fun serve(rawPath: String): WebResourceResponse {
        // A screen of a draft: `/~<id>/…` is the same path seen through the draft's layer.
        // The WebView asks for it already "within" the draft, so the prefix goes either way.
        Drafts.of(rawPath)?.let { draft ->
            return Drafts.within(draft) { serve(Uri.decode(rawPath).trimStart('/').substringAfter('/', "")) }
        }
        var path = Uri.decode(rawPath).trimStart('/')
        if (path.isEmpty() || path.endsWith("/")) path += "index.html"
        val stream: InputStream? = runCatching {
            when {
                path.startsWith("data/") ->
                    File(Workspace.data, path.removePrefix("data/")).takeIf { it.isFile }?.inputStream()
                Workspace.safeMode && path.startsWith("ui/") ->
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

    init {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            setGeolocationEnabled(true)
        }
        web.setBackgroundColor(0)
        web.overScrollMode = WebView.OVER_SCROLL_NEVER
        WebView.setWebContentsDebuggingEnabled(true)

        val origins = setOf(Pages.ORIGIN)
        WebViewCompat.addWebMessageListener(web, "__assi", origins, this)
        val client = App.instance.assets.open("engine/page.js").use { it.readBytes().decodeToString() }
        WebViewCompat.addDocumentStartJavaScript(web, client, origins)

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = when {
                request.url.host == Pages.HOST -> {
                    // What a draft's screen asks for by an absolute address (/ui/kit/…, /modules/…) is the draft's too.
                    val path = request.url.path.orEmpty()
                    val from = Drafts.of(path) ?: Drafts.of(request.requestHeaders["Referer"]?.let { Uri.parse(it).path })
                    Drafts.within(from) { Pages.serve(path) }
                }
                WebCache.wants(request) -> WebCache.serve(request)
                else -> null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
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
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // The system may kill the renderer when memory is short. A WebView without its
                // renderer never draws again: the shell builds its screens anew.
                console.add("[error] Процесс страницы завершился")
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

            override fun onPermissionRequest(request: PermissionRequest) = request.grant(request.resources)

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean = MainActivity.current?.pickFiles(params, callback) ?: false

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
    }

    /** Opens the page and waits until it has loaded (call off the main thread). */
    fun openAndWait(path: String, timeoutMs: Long = 10_000): Boolean {
        val latch = CountDownLatch(1)
        loading = latch
        web.post { web.loadUrl(Pages.url(path)) }
        return latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
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
            "native" -> Engine.pool.execute {
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
