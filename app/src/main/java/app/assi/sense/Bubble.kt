package app.assi.sense

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.WindowInsets
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import app.assi.App
import app.assi.R
import app.assi.core.Engine
import kotlin.math.abs

/**
 * A small button floating over other apps. A tap captures the screen under it and opens the
 * chat with that capture attached: "what is this?", "answer him", "add it to my notes".
 */
object Bubble {
    private const val PREF = "bubble.on"
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    // Started activities are still visible behind dialogs or in split-screen, even if paused.
    // Keep each instance so an old Fold activity stopping cannot unhide the bubble over its replacement.
    private val visibleActivities = mutableSetOf<Activity>()

    val allowed: Boolean get() = Settings.canDrawOverlays(App.instance)
    val wanted: Boolean get() = App.prefs.getString(PREF, "off") == "on"
    val shown: Boolean get() = view != null

    fun set(on: Boolean) {
        App.prefs.edit().putString(PREF, if (on) "on" else "off").apply()
        if (on) show() else hide()
    }

    /** The app already has its own chat button; the system overlay belongs outside it. */
    fun activityStarted(activity: Activity) {
        visibleActivities.add(activity)
        hide()
    }

    fun activityStopped(activity: Activity) {
        if (visibleActivities.remove(activity)) restore()
    }

    /** Puts the button back after the app restarts, if the owner had it on. */
    fun restore() {
        if (wanted) show()
    }

    fun hide() = main.post {
        view?.let { runCatching { windows().removeView(it) } }
        view = null
        callbacks?.let { App.instance.unregisterComponentCallbacks(it) }
        callbacks = null
    }

    private fun windows(): WindowManager = App.instance.getSystemService(WindowManager::class.java)

    private var params: WindowManager.LayoutParams? = null
    private var callbacks: ComponentCallbacks? = null

    /** The screen the button is on now (the Fold's outer or inner one) without the system bars. */
    private fun area(): Rect {
        val metrics = windows().currentWindowMetrics
        val bars = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val b = metrics.bounds
        return Rect(bars.left, bars.top, b.width() - bars.right, b.height() - bars.bottom)
    }

    /**
     * Where the button stands is kept as a side (left or right) and a share of the height, not as
     * pixels: after folding or unfolding the phone it stays at the same edge, inside the screen.
     */
    private fun place(size: Int, margin: Int) {
        val p = params ?: return
        val a = area()
        val prefs = App.prefs
        val right = prefs.getString("bubble.side", "right") != "left"
        val share = prefs.getFloat("bubble.share", 0.6f).coerceIn(0f, 1f)
        p.x = if (right) a.right - size - margin else a.left + margin
        p.y = (a.top + share * (a.height() - size)).toInt().coerceIn(a.top + margin, maxOf(a.top + margin, a.bottom - size - margin))
        view?.let { runCatching { windows().updateViewLayout(it, p) } }
    }

    /** After a drag: sticks to the nearer edge and remembers the side and the height share. */
    private fun settle(size: Int, margin: Int) {
        val p = params ?: return
        val a = area()
        val right = p.x + size / 2 > a.centerX()
        val share = if (a.height() > size) (p.y - a.top).toFloat() / (a.height() - size) else 0.5f
        App.prefs.edit().putString("bubble.side", if (right) "right" else "left").putFloat("bubble.share", share.coerceIn(0f, 1f)).apply()
        place(size, margin)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() = main.post {
        // Recheck when the posted operation runs: the foreground activity or preference may
        // have changed since EngineService.restore() or set(true) requested the bubble.
        if (view != null || !wanted || !allowed || visibleActivities.isNotEmpty()) return@post
        val context: Context = App.instance
        val density = context.resources.displayMetrics.density
        val size = (46 * density).toInt()
        val button = ImageView(context).apply {
            setImageResource(R.drawable.ic_spark)
            setColorFilter(context.getColor(R.color.on_accent))
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(context.getColor(R.color.accent))
            }
            alpha = 0.92f
            contentDescription = "Спросить Assi об этом экране"
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        val margin = (8 * density).toInt()
        this.params = params
        place(size, margin)

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragged = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > 8 * density || abs(dy) > 8 * density) dragged = true
                    if (dragged) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { windows().updateViewLayout(button, params) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        settle(size, margin)
                    } else {
                        ask(button)
                    }
                }
            }
            true
        }
        runCatching {
            windows().addView(button, params)
            view = button
        }
        // Folding or unfolding changes the screen: put the button back inside the new one.
        callbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                main.postDelayed({ place(size, margin) }, 250)
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = Unit
        }.also { App.instance.registerComponentCallbacks(it) }
    }

    /** Captures what is on the screen (without the button itself) and opens the chat. */
    private fun ask(button: View) {
        button.visibility = View.INVISIBLE
        Engine.pool.execute {
            Thread.sleep(120)
            Assist.captureNow()
            main.post { button.visibility = View.VISIBLE }
            Assist.openChat(App.instance)
        }
    }
}
