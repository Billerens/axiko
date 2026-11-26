package app.axiko.sense

import android.content.Context
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.axiko.MainActivity
import app.axiko.R
import app.axiko.core.Bridge
import app.axiko.core.Engine
import app.axiko.core.I18n
import app.axiko.core.Pages
import app.axiko.core.Probe
import app.axiko.core.Themes
import app.axiko.core.Workspace
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

/** Owns the window geometry, safe controls and recovery UI for the system assistant session. */
internal class AssistantPanel(private val context: Context, private val dismiss: () -> Unit) {
    private data class HitRect(val left: Float, val top: Float, val right: Float, val bottom: Float, val radius: Float)
    private data class HitLayout(val width: Float, val height: Float, val rects: List<HitRect>)

    private val main = Handler(Looper.getMainLooper())
    private val card = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val content = FrameLayout(context)
    private val chrome = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val status = TextView(context).apply {
        textSize = 13F
        maxLines = 2
    }
    private val mute = control("Выключить микрофон") {
        call("assistant.stopVoice")
    }
    private val close = control("Закрыть", dismiss)
    val view = object : FrameLayout(context) {
        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            super.onSizeChanged(width, height, oldWidth, oldHeight)
            arrange()
        }
    }
    private var insets = Insets.NONE
    private var session: String? = null
    private var bridge: Bridge? = null
    private var pageReady = false
    private var hitLayout: HitLayout? = null
    private var fallbackTranscript: TextView? = null
    private var side = false
    private var epoch = 0
    private var pendingLoad: Runnable? = null

    init {
        view.setBackgroundColor(Color.TRANSPARENT)
        view.isFocusableInTouchMode = true
        view.addView(card, FrameLayout.LayoutParams(1, 1, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        card.visibility = View.GONE
        card.addView(content, LinearLayout.LayoutParams(-1, 0, 1F))
        chrome.setPadding(dp(12), 0, dp(4), 0)
        chrome.addView(status, LinearLayout.LayoutParams(0, -2, 1F))
        chrome.addView(mute, LinearLayout.LayoutParams(0, -1, 2F))
        chrome.addView(close, LinearLayout.LayoutParams(0, -1, 1F))
        card.addView(chrome, LinearLayout.LayoutParams(-1, dp(48)))
        view.setOnApplyWindowInsetsListener { _, window ->
            val system = window.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val keyboard = window.getInsets(WindowInsets.Type.ime())
            insets = Insets.of(system.left, system.top, system.right, max(system.bottom, keyboard.bottom))
            arrange()
            window
        }
    }

    fun show(id: String, profile: JSONObject) {
        side = profile.optString("layout") == "side"
        arrange()
        card.visibility = View.VISIBLE
        view.requestApplyInsets()
        if (session == id && (bridge != null || fallbackTranscript != null)) return
        clear()
        session = id
        card.visibility = View.VISIBLE
        chrome.visibility = View.VISIBLE
        mute.visibility = View.VISIBLE
        status.text = "Axiko"
        view.requestFocus()
        load(id, Workspace.safeMode)
    }

    fun showUnavailable(reason: String?) {
        clear()
        card.visibility = View.VISIBLE
        chrome.visibility = View.VISIBLE
        mute.visibility = View.GONE
        status.text = "Axiko"
        recoverySurface(true)
        content.addView(label(reason?.take(400) ?: "Ассистент недоступен"), FrameLayout.LayoutParams(-1, -1))
    }

    fun clear(pauseIdleTimers: Boolean = false) {
        epoch++
        session = null
        main.removeCallbacksAndMessages(null)
        pendingLoad = null
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
        removePage(pauseIdleTimers)
        fallbackTranscript = null
        card.visibility = View.GONE
    }

    fun touchableRegion(region: Region) {
        region.setEmpty()
        if (card.visibility != View.VISIBLE || card.width == 0 || card.height == 0) return
        val layout = hitLayout.takeIf { pageReady }
        val web = bridge?.web
        if (layout != null && web != null && web.width > 0 && web.height > 0) {
            setWebRegion(region, web, layout)
            if (!region.isEmpty) return
        }
        // The loading/fallback controls and old layouts remain reachable before the page opts in.
        val location = IntArray(2)
        card.getLocationInWindow(location)
        region.set(location[0], location[1], location[0] + card.width, location[1] + card.height)
    }

    private fun setWebRegion(region: Region, web: WebView, layout: HitLayout) {
        val location = IntArray(2)
        web.getLocationInWindow(location)
        val (x, y) = location
        val scaleX = web.width / layout.width
        val scaleY = web.height / layout.height
        val clip = Region(x, y, x + web.width, y + web.height)
        val shape = Path()
        val part = Region()
        for (bounds in layout.rects) {
            val rect = RectF(x + bounds.left * scaleX, y + bounds.top * scaleY, x + bounds.right * scaleX, y + bounds.bottom * scaleY)
            shape.rewind()
            shape.addRoundRect(rect, bounds.radius * scaleX, bounds.radius * scaleY, Path.Direction.CW)
            part.setPath(shape, clip)
            region.op(part, Region.Op.UNION)
        }
    }

    /** A viewport rectangle comes from the trusted panel document in CSS pixels. */
    private fun setHitAreas(document: JSONObject): Boolean {
        val width = document.optDouble("width").toFloat()
        val height = document.optDouble("height").toFloat()
        if (!width.isFinite() || !height.isFinite() || width < 1 || height < 1) return false
        val raw = document.optJSONArray("rects") ?: return false
        val rects = buildList {
            for (i in 0 until raw.length().coerceAtMost(32)) {
                val rect = raw.optJSONObject(i) ?: continue
                val left = rect.optDouble("left").toFloat().coerceIn(0F, width)
                val top = rect.optDouble("top").toFloat().coerceIn(0F, height)
                val right = rect.optDouble("right").toFloat().coerceIn(0F, width)
                val bottom = rect.optDouble("bottom").toFloat().coerceIn(0F, height)
                if (!(right > left && bottom > top)) continue
                val radius = rect.optDouble("radius", 0.0).toFloat().takeIf { it.isFinite() } ?: 0F
                add(HitRect(left, top, right, bottom, radius.coerceIn(0F, minOf(right - left, bottom - top) / 2)))
            }
        }
        if (rects.isEmpty()) return false
        val layout = HitLayout(width, height, rects)
        if (layout != hitLayout) {
            hitLayout = layout
            arrange()
            // ViewRoot recomputes the window region during its next traversal even for the same size.
            view.requestLayout()
        }
        return true
    }

    private fun load(id: String, factory: Boolean) {
        if (session != id) return
        removePage()
        chrome.visibility = View.VISIBLE
        val greeting = label("Открываю панель…")
        recoverySurface(true)
        content.addView(greeting, FrameLayout.LayoutParams(-1, -1))
        var attempted: Bridge? = null
        var createdWeb: WebView? = null
        try {
            val web = WebView(context)
            createdWeb = web
            val page = Bridge(web, "assistant")
            attempted = page
            bridge = page
            page.assistantScope = { session?.takeIf { it == id && bridge === page } }
            page.assistantHitAreas = { layout -> session == id && bridge === page && setHitAreas(layout) }
            page.assistantFactory = factory
            val timeout = Runnable { recoverPage(id, page, factory) }
            pendingLoad = timeout
            page.onHello = {
                main.post {
                    if (session == id && bridge === page) {
                        main.removeCallbacks(timeout)
                        pendingLoad = null
                        content.removeView(greeting)
                        pageReady = true
                        recoverySurface(false)
                        chrome.visibility = View.GONE
                        arrange()
                    }
                }
            }
            page.onGone = { main.post { recoverPage(id, page, factory) } }
            content.addView(web, 0, FrameLayout.LayoutParams(-1, -1))
            web.onResume()
            web.resumeTimers()
            main.postDelayed(timeout, 3_500)
            web.loadUrl(Pages.url("ui/assistant/index.html"))
        } catch (failure: Exception) {
            if (attempted == null) createdWeb?.destroy()
            android.util.Log.w("AxikoAssistant", "Unable to create the assistant panel", failure)
            recoverPage(id, attempted, factory)
        }
    }

    private fun recoverPage(id: String, page: Bridge?, factory: Boolean) {
        if (session != id || bridge !== page) return
        if (!factory) load(id, true) else showFallback(id)
    }

    private fun removePage(pauseIdleTimers: Boolean = false) {
        pendingLoad?.let(main::removeCallbacks)
        pendingLoad = null
        val prior = bridge
        bridge = null
        pageReady = false
        hitLayout = null
        arrange()
        content.removeAllViews()
        prior?.onHello = null
        prior?.onGone = null
        prior?.assistantHitAreas = null
        prior?.web?.onPause()
        if (pauseIdleTimers && !MainActivity.shown && !Probe.active && !OnScreenAssistant.hasVisiblePanel()) {
            prior?.web?.pauseTimers()
        }
        prior?.destroy()
    }

    /** Retains basic conversation and the system controls if even the bundled page fails. */
    private fun showFallback(id: String) {
        removePage()
        chrome.visibility = View.VISIBLE
        recoverySurface(true)
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val transcript = label("Панель недоступна. Можно продолжить текстом.")
        val scroll = ScrollView(context).apply { addView(transcript) }
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1F))
        val input = EditText(context).apply {
            hint = I18n.t("Сообщение")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            setTextColor(Themes.color("ink", context.getColor(R.color.ink)))
            setHintTextColor(Themes.color("ink-2", Color.LTGRAY))
        }
        val composer = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, -1, 1F))
            addView(control("Отправить") { sendFallback(input) }, LinearLayout.LayoutParams(-2, -1))
        }
        body.addView(composer, LinearLayout.LayoutParams(-1, dp(48)))
        content.addView(body, FrameLayout.LayoutParams(-1, -1))
        fallbackTranscript = transcript
        val ticket = epoch
        val refresh = object : Runnable {
            override fun run() {
                if (session != id || ticket != epoch || fallbackTranscript !== transcript) return
                call("assistant.status") { result ->
                    val model = result as? JSONObject
                    if (model != null) renderFallback(model, transcript, scroll)
                    main.postDelayed(this, 700)
                }
            }
        }
        main.post(refresh)
    }

    private fun sendFallback(input: EditText) {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.isEnabled = false
        call("assistant.send", JSONArray().put(text)) { result ->
            input.isEnabled = true
            if (result == true || result is JSONObject) input.text.clear()
        }
    }

    private fun renderFallback(model: JSONObject, transcript: TextView, scroll: ScrollView) {
        val lines = mutableListOf<String>()
        val messages = model.optJSONArray("messages") ?: JSONArray()
        for (i in max(0, messages.length() - 12) until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            val author = if (message.optString("role") == "user") I18n.t("Вы") else "Axiko"
            lines.add("$author: ${message.optString("text").takeLast(3_000)}")
        }
        for (key in listOf("reply", "work", "error")) {
            model.optString(key).takeIf { it.isNotBlank() }?.let { lines.add(it.takeLast(3_000)) }
        }
        val text = lines.joinToString("\n\n").ifBlank { I18n.t("Панель недоступна. Можно продолжить текстом.") }
        if (transcript.text.toString() == text) return
        transcript.text = text
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun call(method: String, args: JSONArray = JSONArray(), then: (Any?) -> Unit = {}) {
        val id = session ?: return
        val ticket = epoch
        Engine.pool.execute {
            val outcome = runCatching { OnScreenAssistant.call(method, args, id) }
            main.post {
                if (session != id || ticket != epoch) return@post
                outcome.onFailure { status.text = it.message?.take(160) ?: I18n.t("Операция не удалась") }
                then(outcome.getOrNull())
            }
        }
    }

    private fun arrange() {
        if (view.width <= 0 || view.height <= 0) return
        val edge = dp(12)
        val availableWidth = (view.width - insets.left - insets.right - 2 * edge).coerceAtLeast(1)
        val availableHeight = (view.height - insets.top - insets.bottom - 2 * edge).coerceAtLeast(1)
        val wideSide = side && availableWidth >= dp(680)
        val expanded = pageReady && hitLayout != null
        val width = if (expanded && !wideSide) availableWidth else availableWidth.coerceAtMost(dp(if (wideSide) 448 else 640))
        val preferredHeight = if (wideSide) dp(560) else (availableHeight * 0.58F).roundToInt().coerceIn(dp(260), dp(460))
        val height = if (expanded) availableHeight else preferredHeight.coerceAtMost(availableHeight)
        val unusedWidth = availableWidth - width
        val left = insets.left + edge + if (wideSide) unusedWidth else unusedWidth / 2
        val gravity = Gravity.BOTTOM or Gravity.LEFT
        val layout = card.layoutParams as FrameLayout.LayoutParams
        if (layout.width == width && layout.height == height && layout.gravity == gravity &&
            layout.leftMargin == left && layout.rightMargin == insets.right + edge &&
            layout.bottomMargin == insets.bottom + edge) return
        layout.width = width
        layout.height = height
        layout.gravity = gravity
        layout.setMargins(left, 0, insets.right + edge, insets.bottom + edge)
        card.layoutParams = layout
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private fun recoverySurface(visible: Boolean) {
        card.background = if (visible) surface(0xEB) else null
        card.clipToOutline = visible
        if (visible) {
            val ink = Themes.color("ink", context.getColor(R.color.ink))
            status.setTextColor(ink)
            mute.setTextColor(ink)
            close.setTextColor(ink)
        }
    }

    private fun surface(alpha: Int, radius: Float = dp(18).toFloat()) = GradientDrawable().apply {
        cornerRadius = radius
        val color = Themes.color("surface", context.getColor(R.color.surface))
        setColor((color and 0x00FFFFFF) or (alpha shl 24))
    }

    private fun label(text: String) = TextView(context).apply {
        this.text = I18n.t(text)
        textSize = 15F
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setTextColor(Themes.color("ink", context.getColor(R.color.ink)))
    }

    private fun control(text: String, onClick: () -> Unit) = Button(context).apply {
        this.text = I18n.t(text)
        contentDescription = this.text
        isAllCaps = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        minHeight = 0
        minimumHeight = 0
        minWidth = dp(48)
        minimumWidth = dp(48)
        textSize = 12F
        setPadding(dp(8), 0, dp(8), 0)
        setTextColor(Themes.color("ink", context.getColor(R.color.ink)))
        val ripple = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
            setBackgroundResource(ripple.resourceId)
        }
        setOnClickListener { onClick() }
    }
}
