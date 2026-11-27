package app.axiko

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.drawable.GradientDrawable
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewOutlineProvider
import android.view.inputmethod.InputMethodManager
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import app.axiko.core.Bridge
import app.axiko.core.Engine
import app.axiko.core.I18n
import app.axiko.core.EngineService
import app.axiko.core.Pages
import app.axiko.core.ScreenSuspicions
import app.axiko.core.Themes
import app.axiko.core.Workspace
import app.axiko.agent.Inbox
import app.axiko.agent.Threads
import app.axiko.voice.Wake
import app.axiko.sense.Bubble
import app.axiko.update.AppUpdates
import app.axiko.update.UpdateInstaller
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The shell: one WebView for the current screen, one for the chat that slides over it, one for
 * the menu of screens (a bar below, a rail at the side when wide), and a native button that
 * the workspace cannot break. The button's look may come from a page too (ui/button/: the
 * mascot peeking over the edge) — but only its look: the press and the recovery menu stay native.
 */
class MainActivity : Activity() {
    lateinit var page: Bridge
        private set
    lateinit var chat: Bridge
        private set
    private lateinit var nav: Bridge

    /** The page that draws the chat button's look; it never takes a touch. */
    private lateinit var face: Bridge

    /** That page has drawn something to show in place of the round button. */
    private var faceOn = false
    private var edgeBar: Boolean? = null

    private lateinit var root: FrameLayout
    private lateinit var scrim: View
    private lateinit var panel: FrameLayout
    private lateinit var fab: ImageView
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var chatOpen = false
    private var chatLoaded = false
    private var pendingChatAgent: String? = null
    private var alive = false
    private var typing = false
    private var appUpdateDialog: AlertDialog? = null
    private var screenNotice: JSONObject? = null
    private var screenNoticeDialog: AlertDialog? = null

    /** The address of the screen that was given the whole display, or null. */
    private var staged: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this

        // Two starts in a row that never reached a live page: show the factory interface.
        val failures = App.prefs.getInt(BOOT_FAILURES, 0)
        if (failures >= 2) Workspace.safeMode = true
        App.prefs.edit().putInt(BOOT_FAILURES, failures + 1).commit()

        buildViews()
        EngineService.start(this)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        page.onHello = { handler.postDelayed({ pageReady() }, 2500) }
        page.web.loadUrl(Pages.url(HOME))
        chat.web.loadUrl(Pages.url(CHAT))
        nav.web.loadUrl(Pages.url(NAV))
        face.web.loadUrl(Pages.url(BUTTON))
        handler.postDelayed({
            if (!alive) toast("Экран не отвечает. Удерживайте круглую кнопку — там восстановление.")
        }, 9000)
        handle(intent)
        Engine.pool.execute {
            runCatching { ScreenSuspicions.state(this) }.onSuccess { state ->
                handler.post {
                    if (isDestroyed) return@post
                    screenNotice = state
                    offerScreenReview()
                }
            }.onFailure { Log.w("Axiko", "Could not read important screen notices", it) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /**
     * What a launch can ask for: `open` — a screen (shortcuts use it), `eval` — code for the
     * engine, with the result in logcat under AxikoEval (development over adb; `evalTimeout`
     * gives it more than two minutes, in seconds).
     */
    private fun handle(intent: Intent?) {
        intent?.getStringExtra("open")?.let { open(it) }
        // From an answer's notification: straight to that chat.
        intent?.getStringExtra("chat")?.let { agent ->
            navigateChat(agent, intent.getStringExtra("thread")?.takeIf { it.isNotBlank() })
            intent.removeExtra("chat")
        }
        if (intent?.getBooleanExtra("assist", false) == true || intent?.action == Intent.ACTION_ASSIST) {
            // Called over another app: straight to the chat, with that app's screen attached if captured.
            showChat(true)
            Pages.broadcast("inbox", (Inbox.peek() ?: org.json.JSONObject()).toString())
        }
        if (intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE) share(intent)
        // Health Connect asks the app to say what the owner's health data is for (see the manifest).
        if (intent?.action == Intent.ACTION_VIEW_PERMISSION_USAGE) open(HEALTH_RATIONALE)
        intent?.getStringExtra("eval")?.let { code ->
            val limit = intent.getIntExtra("evalTimeout", 120) * 1000L
            Engine.pool.execute {
                val result = runCatching { Engine.evalShown(code, limit) }.getOrElse { "Ошибка: " + Engine.describe(it) }
                result.chunked(3000).forEach { Log.i("AxikoEval", it) }
                Log.i("AxikoEval", "<<end>>")
            }
        }
    }

    fun displaysChat(agent: String, thread: String): Boolean {
        if (!visible || !chatOpen || !::chat.isInitialized || Threads.current(agent) != thread) return false
        val selected = runCatching {
            org.json.JSONTokener(chat.eval("localStorage.getItem('chat.agent') || 'axiko'").orEmpty()).nextValue()
        }.getOrNull()
        return visible && chatOpen && selected == agent && Threads.current(agent) == thread
    }

    fun navigateChat(agent: String, thread: String?) = runOnUiThread {
        thread?.let { Threads.select(agent, it) }
        showChat(true)
        pendingChatAgent = agent
        pushChatAgent()
    }

    private fun pushChatAgent() {
        val agent = pendingChatAgent?.takeIf { chatLoaded } ?: return
        pendingChatAgent = null
        chat.push(org.json.JSONObject().put("event", "chat.agent").put("data", agent).toString())
    }

    override fun onResume() {
        super.onResume()
        visible = true
        Wake.start()
        UpdateInstaller.resumed(this)
        AppUpdates.get(this).check(manual = false) { runOnUiThread { offerAppUpdate() } }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            UpdateInstaller.present()
            offerAppUpdate()
            offerScreenReview()
        }
    }

    private fun offerAppUpdate() {
        if (!visible || current !== this || isFinishing || !hasWindowFocus() || screenNoticeDialog?.isShowing == true) return
        if (page.location.substringBefore('?').endsWith("/ui/settings/index.html")) return
        val update = AppUpdates.get(this).state()
        val version = update.optString("version")
        if (update.optString("status") != "available" || !update.optBoolean("canInstall") ||
            version.isBlank() || App.prefs.getString("update.offered", null) == version
        ) return
        showAppUpdate(update)
    }

    private fun offerScreenReview() {
        val state = screenNotice ?: return
        if (!visible || current !== this || isFinishing || !hasWindowFocus() || appUpdateDialog?.isShowing == true) return
        screenNotice = null
        val generation = state.optString("generation")
        if (generation.isEmpty() || (state.optJSONArray("entries")?.length() ?: 0) == 0 ||
            App.prefs.getString("suspicions.offered", null) == generation
        ) return
        App.prefs.edit().putString("suspicions.offered", generation).apply()
        screenNoticeDialog = AlertDialog.Builder(this)
            .setTitle(I18n.t("Важные экраны изменены"))
            .setMessage(I18n.t("После обновления некоторые действия могут отсутствовать на локально изменённых экранах."))
            .setNegativeButton(I18n.t("Позже"), null)
            .setPositiveButton(I18n.t("Посмотреть подозрения")) { _, _ -> open(Pages.SUSPICIONS_VIEW) }
            .show()
    }

    private fun checkAppUpdate() {
        toast("Проверяем обновления…")
        AppUpdates.get(this).check(manual = true) { update ->
            runOnUiThread {
                if (!visible || current !== this || isFinishing) return@runOnUiThread
                when {
                    update.optBoolean("canInstall") -> showAppUpdate(update)
                    update.optString("status") == "current" -> toast("У вас актуальная версия.")
                    update.optString("status") == "downloading" -> toast("Загружаем обновление…")
                    update.optString("status") == "unavailable" -> toast("Для этой сборки Axiko обновление недоступно.")
                    else -> toast("Не удалось проверить обновления.")
                }
            }
        }
    }

    private fun showAppUpdate(update: JSONObject) {
        if (appUpdateDialog?.isShowing == true) return
        val version = update.getString("version")
        val raw = update.optString("notes")
        val headings = Regex("(?im)^[ \\t]*##[ \\t]+(English|Русский|Russian)[ \\t]*#*[ \\t]*\\r?$")
        val sections = headings.findAll(raw).toList()
        val notes = sections.mapIndexed { index, heading ->
            val language = if (heading.groupValues[1].equals("English", ignoreCase = true)) "en" else "ru"
            language to raw.substring(heading.range.last + 1, sections.getOrNull(index + 1)?.range?.first ?: raw.length).trim()
        }.toMap().filterValues(String::isNotBlank)
        val localized = (notes[I18n.language.substringBefore('-')] ?: notes["en"] ?: notes["ru"] ?: raw)
            .trim().take(12_000).replace(Regex("(?m)^[ \\t]*[-*][ \\t]+"), "• ")
        val message = listOf(I18n.t("Версия $version"), localized).filter(String::isNotBlank).joinToString("\n\n")
        App.prefs.edit().putString("update.offered", version).apply()
        appUpdateDialog = AlertDialog.Builder(this)
            .setTitle(I18n.t("Доступно обновление Axiko"))
            .setMessage(message)
            .setNegativeButton(I18n.t("Позже"), null)
            .setNeutralButton(I18n.t("Настройки")) { _, _ -> open("ui/settings/index.html?tab=system") }
            .setPositiveButton(I18n.t(if (update.optBoolean("downloaded")) "Установить" else "Скачать и установить")) { _, _ ->
                if (!UpdateInstaller.request(this, version)) toast("Не удалось начать установку. Повторите попытку.")
            }.show()
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        Bubble.activityStarted(this)
        seen(true)
    }

    override fun onStop() {
        seen(false)
        Bubble.activityStopped(this)
        super.onStop()
    }

    /**
     * The app went out of sight (folded away, the screen went dark) or came back. The WebViews
     * rest while unseen, so a page's `document.visibilityState` tells the truth and a game stops
     * its loop; pages and scripts hear it as "app.visible". A dialog over the app does not count.
     */
    private fun seen(on: Boolean) {
        if (on) startedActivities.add(this) else startedActivities.remove(this)
        if (startedActivities.isNotEmpty()) page.web.resumeTimers()
        for (bridge in listOf(page, chat, nav, face)) if (on) bridge.web.onResume() else bridge.web.onPause()
        val event = org.json.JSONObject().put("on", startedActivities.isNotEmpty())
        Pages.broadcast("app.visible", event.toString())
        Engine.fire("app.visible", event)
        // onPause hides a WebView but still runs JS timers (observed at 1 Hz in the background).
        // Timer suspension is process-wide; keep them running while another Activity is visible.
        // Background agents/jobs run in Rhino and are unaffected.
        // The agent's own screen (Probe) lives in the same process: while it is at work the timers stay.
        if (startedActivities.isEmpty() && !app.axiko.core.Probe.active && !app.axiko.sense.OnScreenAssistant.hasVisiblePanel()) page.web.pauseTimers()
    }

    override fun onDestroy() {
        startedActivities.remove(this)
        Bubble.activityStopped(this)
        if (current === this) current = null
        handler.removeCallbacksAndMessages(null)
        page.destroy()
        chat.destroy()
        nav.destroy()
        face.destroy()
        super.onDestroy()
    }

    /** Another app shared text, pictures or files: they wait in the chat for the owner's words. */
    private fun share(intent: Intent) {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        @Suppress("DEPRECATION")
        val streams = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        } else {
            listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        }
        // Consumed: a rotation or a return to the app must not take the same things in again.
        intent.action = Intent.ACTION_MAIN
        showChat(true)
        Engine.pool.execute {
            for (uri in streams) runCatching { Inbox.addUri(this, uri) }
            Pages.broadcast("inbox", (Inbox.peek() ?: org.json.JSONObject()).toString())
            if (!text.isNullOrBlank()) Pages.broadcast("share.text", org.json.JSONObject.quote(text))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            chatOpen && !wide -> showChat(false)
            staged != null -> {
                setStage(false)
                if (page.web.canGoBack()) page.web.goBack()
            }
            page.web.canGoBack() -> page.web.goBack()
            chatOpen -> showChat(false)
            else -> moveTaskToBack(true)
        }
    }

    // ---- views ----------------------------------------------------------------------------

    /** The owner's scale of the interface for the screen as it is now (see UiScale): 1 for "as designed". */
    private var scale = 1f

    /** The screen as the phone reports it, before the owner's scale: which of the two scales applies. */
    private val rawWide: Boolean get() = resources.configuration.screenWidthDp >= WIDE_DP

    /** The shell's own sizes grow and shrink with the pages they stand beside. */
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density * scale).toInt()

    /**
     * Takes the scale for the current screen and gives it to every page view. With [reload] the
     * pages are loaded anew when it changed — a page learns its scale as it loads.
     */
    private fun takeScale(reload: Boolean) {
        val percent = app.axiko.core.UiScale.percent(rawWide)
        scale = percent / 100f
        val changed = listOf(page, chat, nav, face).map { it.scale(percent) }.any { it }
        // The system's display size or font size: the view keeps its pixels, but a page in it
        // is laid out for another width in CSS pixels, and only a page loaded anew learns it.
        val metrics = resources.configuration.let { it.densityDpi to it.fontScale }
        val resized = laidOut != null && laidOut != metrics
        laidOut = metrics
        arrange()
        if ((changed || resized) && reload) reloadPages()
    }

    /** The system's display density and font scale the pages were laid out for. */
    private var laidOut: Pair<Int, Float>? = null

    fun scaleInfo(): org.json.JSONObject = app.axiko.core.UiScale.info(rawWide)

    fun setScale(percent: Int): org.json.JSONObject {
        app.axiko.core.UiScale.set(rawWide, percent)
        runOnUiThread { takeScale(reload = true) }
        return scaleInfo()
    }

    private fun buildViews() {
        root = FrameLayout(this).apply {
            fitsSystemWindows = true
            // The keyboard takes the place of the menu bar while it is up.
            setOnApplyWindowInsetsListener { view, insets ->
                val keyboard = insets.isVisible(android.view.WindowInsets.Type.ime())
                if (keyboard != typing) {
                    typing = keyboard
                    post { arrange() }
                }
                view.onApplyWindowInsets(insets)
            }
        }

        page = Bridge(WebView(this), "page")
        root.addView(page.web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        nav = Bridge(WebView(this), "nav")
        root.addView(nav.web, FrameLayout.LayoutParams(MATCH_PARENT, dp(BAR_DP), Gravity.BOTTOM))

        // Over the screen and the menu, under the chat: the button's look. Touches fall through
        // it to whatever lies beneath; the native button above it is what gets pressed.
        face = Bridge(object : WebView(this) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean = false
        }, "button")
        face.web.isFocusable = false
        face.web.visibility = View.GONE
        root.addView(face.web, FrameLayout.LayoutParams(dp(FACE_DP), dp(FACE_DP), Gravity.BOTTOM or Gravity.END))

        scrim = View(this).apply {
            setBackgroundColor(getColor(R.color.scrim))
            visibility = View.GONE
            setOnClickListener { showChat(false) }
        }
        root.addView(scrim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val radius = dp(22).toFloat()
        panel = FrameLayout(this).apply {
            outlineProvider = object : ViewOutlineProvider() {
                // Rounded top corners of the sheet; the docked panel does not clip.
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height + radius.toInt(), radius)
                }
            }
            clipToOutline = true
            visibility = View.GONE
        }
        chat = Bridge(WebView(this), "chat")
        chatLoaded = false
        chat.onLoaded = {
            chatLoaded = true
            pushChatAgent()
        }
        panel.addView(chat.web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        fab = ImageView(this).apply {
            setImageResource(R.drawable.ic_spark)
            setColorFilter(getColor(R.color.on_accent))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(15), dp(15), dp(15), dp(15))
            contentDescription = "Чат с Axiko"
            setOnClickListener { showChat(true) }
            setOnLongClickListener { recoveryMenu(); true }
        }
        root.addView(
            fab,
            FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(18)
                bottomMargin = dp(22)
            },
        )
        setContentView(root)
        takeScale(reload = false)
        applyTheme()
        if (wide && App.prefs.getBoolean(CHAT_DOCKED, false)) showChat(true)
    }

    private fun tone(token: String, fallback: Int): Int = Themes.color(token, getColor(fallback))

    /** The shell's own parts take the colours of the chosen theme. */
    fun applyTheme() = runOnUiThread {
        val background = tone("bg", R.color.bg)
        root.setBackgroundColor(background)
        window.statusBarColor = background
        window.navigationBarColor = background
        fab.setColorFilter(tone("accent-ink", R.color.on_accent))
        fab.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(tone("accent", R.color.accent))
        }
        arrange()
    }

    /** The button wears the look its page drew — never in safe mode, where nothing of the workspace is trusted. */
    private val faced: Boolean get() = faceOn && !Workspace.safeMode

    /** The menu of screens is a bar along the bottom right now: the edge the mascot holds on to. */
    private val barShown: Boolean get() = menu && !wide && !typing && staged == null

    /**
     * The button's page says whether it has a look to show (`button.face`). Answers with what
     * the page needs to draw it: whether the edge under the button is the menu bar.
     */
    fun setFace(on: Boolean): org.json.JSONObject {
        runOnUiThread {
            if (faceOn != on) {
                faceOn = on
                arrange()
            }
        }
        return org.json.JSONObject().put("bar", barShown)
    }

    /** The menu of screens is shown: the owner may switch it off. */
    private val menu: Boolean get() = App.prefs.getString("nav.on", "on") == "on"

    fun rearrange() = runOnUiThread { arrange() }

    /** A tablet or an unfolded phone: the chat stands beside the screen instead of covering it. */
    private val wide: Boolean get() = resources.configuration.screenWidthDp / scale >= WIDE_DP

    private fun dockWidth(): Int = dp((resources.configuration.screenWidthDp / scale * 0.42f).toInt().coerceIn(340, 460))

    /**
     * Places everything for the current width. Wide: the menu is a rail on the left, the chat a
     * panel on the right. Narrow: the menu is a bar below, the chat a sheet from below.
     */
    private fun arrange() {
        val panelParams = panel.layoutParams as FrameLayout.LayoutParams
        val pageParams = page.web.layoutParams as FrameLayout.LayoutParams
        val navParams = nav.web.layoutParams as FrameLayout.LayoutParams
        val fabParams = fab.layoutParams as FrameLayout.LayoutParams
        val faceParams = face.web.layoutParams as FrameLayout.LayoutParams
        val radius = dp(22).toFloat()
        val rail = menu && wide && staged == null
        val bar = barShown
        nav.web.visibility = if (rail || bar) View.VISIBLE else View.GONE
        navParams.width = if (rail) dp(RAIL_DP) else MATCH_PARENT
        navParams.height = if (rail) MATCH_PARENT else dp(BAR_DP)
        navParams.gravity = if (rail) Gravity.START else Gravity.BOTTOM
        nav.web.layoutParams = navParams
        pageParams.leftMargin = if (rail) dp(RAIL_DP) else 0
        pageParams.bottomMargin = if (bar) dp(BAR_DP) else 0
        // With a look of its own the native button is only the place to press: unseen, and as
        // large as what the page draws — the mascot's head and paws at the edge.
        fab.alpha = if (faced) 0f else 1f
        fabParams.width = dp(if (faced) 104 else 56)
        fabParams.height = dp(if (faced) 78 else 56)
        fabParams.rightMargin = dp(if (faced) 8 else 18)
        fabParams.bottomMargin = (if (faced) 0 else dp(22)) + if (bar) dp(BAR_DP) else 0
        fab.layoutParams = fabParams
        val inset = dp(15)
        fab.setPadding(inset, inset, inset, inset)
        faceParams.width = dp(FACE_DP)
        faceParams.height = dp(FACE_DP)
        // The look reaches a little below the edge, so the paws can lie over the menu bar.
        faceParams.bottomMargin = if (bar) dp(BAR_DP) - dp(FACE_OVERLAP_DP) else 0
        face.web.layoutParams = faceParams
        face.web.visibility = if (faced && staged == null && !(wide && chatOpen)) View.VISIBLE else View.GONE
        if (edgeBar != bar) {
            edgeBar = bar
            Pages.broadcast("button.edge", org.json.JSONObject().put("bar", bar).toString())
        }
        if (wide) {
            panelParams.width = dockWidth()
            panelParams.topMargin = 0
            panelParams.gravity = Gravity.END
            pageParams.rightMargin = if (chatOpen) dockWidth() else 0
            // A hairline on the left tells the panel from the screen.
            panel.background = GradientDrawable().apply { setColor(tone("line", R.color.line)) }
            panel.setPadding(dp(1), 0, 0, 0)
            panel.clipToOutline = false
            scrim.visibility = View.GONE
        } else {
            panelParams.width = MATCH_PARENT
            panelParams.topMargin = dp(44)
            panelParams.gravity = Gravity.BOTTOM
            pageParams.rightMargin = 0
            panel.background = GradientDrawable().apply {
                setColor(tone("surface", R.color.surface))
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            panel.setPadding(0, 0, 0, 0)
            panel.clipToOutline = true
            scrim.alpha = 1f
            scrim.visibility = if (chatOpen) View.VISIBLE else View.GONE
        }
        panel.animate().cancel()
        panel.translationX = 0f
        panel.translationY = 0f
        panel.visibility = if (chatOpen) View.VISIBLE else View.GONE
        panel.layoutParams = panelParams
        page.web.layoutParams = pageParams
        fab.scaleX = 1f
        fab.scaleY = 1f
        fab.visibility = if (chatOpen || staged != null) View.GONE else View.VISIBLE
    }

    /**
     * Gives the current screen the whole display — no chat button, no menu, no system bars —
     * or takes it back. It ends by itself when the owner goes back or another screen opens, so
     * a screen cannot keep the owner from the rest of the app.
     */
    fun setStage(on: Boolean) = runOnUiThread {
        if (on == (staged != null)) return@runOnUiThread
        staged = if (on) page.location else null
        window.insetsController?.let { bars ->
            if (on) {
                bars.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                bars.hide(android.view.WindowInsets.Type.systemBars())
            } else {
                bars.show(android.view.WindowInsets.Type.systemBars())
            }
        }
        arrange()
    }

    /** The screen changed: one that had the whole display has it no more. */
    fun screenChanged(url: String) = runOnUiThread {
        val held = staged ?: return@runOnUiThread
        if (url.substringBefore('#') != held.substringBefore('#')) setStage(false)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Folding, unfolding and rotation arrive here: the activity is not recreated. The other
        // screen of a folding phone may have a scale of its own.
        takeScale(reload = true)
    }

    fun showChat(open: Boolean) = runOnUiThread {
        if (open == chatOpen) return@runOnUiThread
        chatOpen = open
        if (wide) {
            App.prefs.edit().putBoolean(CHAT_DOCKED, open).apply()
            if (!open) getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(panel.windowToken, 0)
            arrange()
            if (open) {
                panel.translationX = dockWidth().toFloat()
                panel.animate().translationX(0f).setDuration(180).start()
            }
            Pages.broadcast("chat.visible", open.toString())
            return@runOnUiThread
        }
        val distance = (panel.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels).toFloat()
        if (open) {
            scrim.alpha = 0f
            scrim.visibility = View.VISIBLE
            scrim.animate().alpha(1f).setDuration(180).start()
            panel.translationY = distance
            panel.visibility = View.VISIBLE
            panel.animate().translationY(0f).setDuration(220).start()
            fab.animate().scaleX(0f).scaleY(0f).setDuration(120).withEndAction { fab.visibility = View.GONE }.start()
            chat.web.requestFocus()
        } else {
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(panel.windowToken, 0)
            scrim.animate().alpha(0f).setDuration(160).withEndAction { scrim.visibility = View.GONE }.start()
            panel.animate().translationY(distance).setDuration(200)
                .withEndAction { panel.visibility = View.GONE }.start()
            fab.visibility = if (staged != null) View.GONE else View.VISIBLE
            fab.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            page.web.requestFocus()
        }
        Pages.broadcast("chat.visible", open.toString())
    }

    private fun recoveryMenu() {
        val safe = Workspace.safeMode
        val items = arrayOf(
            "Домой",
            "Перезагрузить экраны",
            if (safe) "Выйти из безопасного режима" else "Безопасный режим",
            "Откатить последнее изменение",
            "Сбросить интерфейс к заводскому",
            "Проверить обновления",
            "Подозрения",
            // Only while the folders from before a restore are still kept (until the first good start).
        ) + if (app.axiko.backup.Backup.undoable) arrayOf("Вернуть как было до восстановления из копии") else emptyArray<String>()
        AlertDialog.Builder(this).setTitle("Axiko").setItems(items.map { I18n.t(it) }.toTypedArray()) { _, which ->
            when (which) {
                0 -> open(HOME)
                1 -> reloadPages()
                2 -> setSafeMode(!safe)
                3 -> rollbackLast()
                4 -> {
                    Workspace.snapshot("Перед сбросом интерфейса")
                    Workspace.installFactory(this, reset = "ui/")
                    reloadPages()
                    toast("Интерфейс сброшен")
                }
                5 -> checkAppUpdate()
                6 -> open(Pages.SUSPICIONS_VIEW)
                7 -> runCatching { app.axiko.backup.Backup.undo() }.onFailure { toast(it.message ?: it.toString()) }
            }
        }.show()
    }

    private fun rollbackLast() {
        val latest = Workspace.snapshots().optJSONObject(0)
        if (latest == null) {
            toast("Снимков пока нет")
            return
        }
        Workspace.restore(latest.getString("id"))
        reloadPages()
        toast("Откат: " + latest.optString("label"))
    }

    private fun toast(text: String) = Toast.makeText(this, I18n.t(text), Toast.LENGTH_LONG).show()

    // ---- <input type="file"> ----------------------------------------------------------------

    private var filePick: ValueCallback<Array<Uri>>? = null

    /** Shows the system picker for a page's file input. */
    fun pickFiles(params: WebChromeClient.FileChooserParams, callback: ValueCallback<Array<Uri>>): Boolean {
        filePick?.onReceiveValue(null)
        filePick = callback
        return try {
            @Suppress("DEPRECATION")
            startActivityForResult(params.createIntent(), PICK_FILES)
            true
        } catch (error: Exception) {
            filePick = null
            false
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == PICK_FILES) {
            filePick?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            filePick = null
            return
        }
        // "Save file", "Open file" and "Choose folder" of the backup.
        if (app.axiko.backup.Backup.onActivityResult(requestCode, resultCode, data)) return
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    // ---- what the shell offers to pages and tools --------------------------------------------

    fun open(path: String) = runOnUiThread { page.web.loadUrl(Pages.url(path)) }

    fun goBack() = runOnUiThread { if (page.web.canGoBack()) page.web.goBack() }

    /**
     * Builds the screens anew after their renderer died. Three deaths within a minute mean
     * rebuilding does not help: then the owner is told instead of looping.
     */
    fun rebuild() = runOnUiThread {
        val now = System.currentTimeMillis()
        rebuilds.removeAll { now - it > 60_000 }
        rebuilds.add(now)
        if (rebuilds.size > 3) {
            toast("Экран не удаётся показать: системе не хватает памяти. Закройте другие приложения и откройте Axiko снова.")
            return@runOnUiThread
        }
        // Not the workspace's fault: this start must not count towards safe mode.
        App.prefs.edit().putInt(BOOT_FAILURES, 0).commit()
        if (!isFinishing && !isDestroyed) recreate()
    }

    fun reloadPages() = runOnUiThread {
        page.web.reload()
        chat.web.reload()
        nav.web.reload()
        // The button is itself again until its page says otherwise.
        faceOn = false
        face.web.reload()
        applyTheme()
    }

    /** A page answered: this start is good, the failure counter starts over. */
    fun pageReady() {
        alive = true
        if (!Workspace.safeMode) App.prefs.edit().putInt(BOOT_FAILURES, 0).apply()
        // After a restore from a backup this is the good start the kept folders were waiting for.
        app.axiko.backup.Backup.started()
    }

    fun setSafeMode(on: Boolean) = runOnUiThread {
        Workspace.safeMode = on
        App.prefs.edit().putInt(BOOT_FAILURES, if (on) 2 else 0).apply()
        open(HOME)
        chat.web.reload()
        nav.web.reload()
        faceOn = false
        face.web.reload()
        applyTheme()
        if (!on) Engine.pool.execute { Engine.runBoot() }
        toast(if (on) "Безопасный режим: заводской интерфейс" else "Обычный режим")
    }

    /** The current screen as a JPEG in base64 (call off the main thread). */
    fun screenshot(maxWidth: Int = 720): String? = page.shot(tone("bg", R.color.bg), maxWidth)

    /** Runs code in the current screen and returns its value as JSON text (off the main thread). */
    fun evalInPage(code: String, timeoutMs: Long = 10_000): String? = page.eval(code, timeoutMs)

    companion object {
        private val startedActivities = HashSet<MainActivity>()

        /** Whether the app is in sight: an activity is started (a dialog over it does not count). */
        val shown: Boolean get() = startedActivities.isNotEmpty()
        const val HOME = "ui/home/index.html"
        const val CHAT = "ui/chat/index.html"
        const val NAV = "ui/nav/index.html"
        const val BUTTON = "ui/button/index.html"
        private const val FACE_DP = 132
        private const val FACE_OVERLAP_DP = 14
        const val HEALTH_RATIONALE = "ui/health-rationale/index.html"
        private const val BAR_DP = 62
        private const val RAIL_DP = 76
        private const val BOOT_FAILURES = "boot_failures"
        private const val PICK_FILES = 9001
        private const val CHAT_DOCKED = "chat_docked"
        // The unfolded Galaxy Fold is about 670 dp wide; its cover screen about 320.
        private const val WIDE_DP = 600

        /** When the screens were last rebuilt; outlives the activity that is being replaced. */
        private val rebuilds = ArrayList<Long>()

        @Volatile
        var current: MainActivity? = null
            private set

        /** The app is on screen right now. */
        @Volatile
        var visible = false
            private set
    }
}
