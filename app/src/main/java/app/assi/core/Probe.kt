package app.assi.core

import android.app.Presentation
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import app.assi.App
import app.assi.MainActivity
import app.assi.R
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * The agent's own screen. Checking a screen used to mean opening it in front of the owner: the
 * owner's place was lost, and with the app out of sight there was no screen to check at all, so
 * work in the background stopped at its first look. Here a page lives in a window on a display
 * of the app's own that nobody sees: it loads, draws and runs its timers whether the app is
 * shown or not, at any width the agent asks for, and the owner's screen stays where it was.
 *
 * It is built at the first use and let go after a while without one.
 */
object Probe {
    private const val IDLE_MS = 120_000L
    private const val RETRY_MS = 300_000L
    private const val WIDTH = "probe.width"
    private const val HEIGHT = "probe.height"

    private class Screen(
        val display: VirtualDisplay,
        val reader: ImageReader,
        val window: Presentation,
        val bridge: Bridge,
        val width: Int,
    )

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var screen: Screen? = null

    /** What it showed last: a screen built anew starts there. */
    @Volatile
    private var last = ""

    /**
     * When the phone would not give a display: the owner's screen serves instead, as it did
     * before, and after a while the display is asked for again.
     */
    @Volatile
    private var refusedAt = 0L

    private val refused: Boolean get() = refusedAt != 0L && System.currentTimeMillis() - refusedAt < RETRY_MS

    /** A screen is alive: the timers of pages must run though the app may be out of sight. */
    val active: Boolean get() = screen != null

    /** The width its pages are laid out at now, in CSS points; 0 when there is no screen. */
    val width: Int get() = screen?.width ?: 0

    private val idle = Runnable { drop() }

    /**
     * The screen to check on, built if there is none; [width] in CSS points, 0 for the width
     * the owner's screen has. A screen that has just been built shows what the last one did,
     * or what the owner looks at. Call off the main thread.
     */
    fun bridge(width: Int = 0, load: Boolean = true): Bridge {
        val have = screen
        if (have != null && (width == 0 || width == have.width)) {
            touch()
            return have.bridge
        }
        if (refused) return theirs()
        val size = size(width)
        val built = runCatching {
            onMain {
                drop()
                build(size.first, size.second).also { screen = it }
            }
        }.getOrElse {
            refusedAt = System.currentTimeMillis()
            android.util.Log.w("Probe", "no screen of its own", it)
            return theirs()
        }
        touch()
        if (load) built.bridge.openAndWait(last.ifBlank { theirPath() ?: MainActivity.HOME })
        return built.bridge
    }

    /**
     * The screen for opening a page on: at [width], or at the width of the owner's screen when
     * none is given — a check at another width does not stay with the screens opened after it.
     */
    fun at(width: Int = 0): Bridge = bridge(if (width > 0) width else size(0).first, load = false)

    /** Opens a page on a screen got from [at] and waits for it; [path] is within the workspace (a draft's prefix included). */
    fun open(bridge: Bridge, path: String): Boolean {
        if (bridge.name == "probe") last = path
        return bridge.openAndWait(path)
    }

    /** A picture of a screen (the agent's own unless another is given), or of one part of it found by a CSS selector. */
    fun shot(bridge: Bridge = bridge(), selector: String? = null): String? {
        val clip = selector?.takeIf { it.isNotBlank() }?.let { part(bridge, it) }
        return bridge.shot(Themes.color("bg", App.instance.getColor(R.color.bg)), clip = clip)
    }

    /** Where the part is, in the view's pixels. The part is scrolled into sight first. */
    private fun part(bridge: Bridge, selector: String): Rect {
        val found = bridge.eval(
            "(function(){var e=document.querySelector(" + JSONObject.quote(selector) + ");if(!e)return null;" +
                "e.scrollIntoView({block:'center',inline:'center'});var r=e.getBoundingClientRect();" +
                "return JSON.stringify({x:r.left,y:r.top,w:r.width,h:r.height,view:window.innerWidth});})()",
        )
        val box = runCatching { JSONObject(org.json.JSONTokener(found.orEmpty()).nextValue() as String) }.getOrNull()
            ?: throw IllegalArgumentException("На экране нет элемента `$selector`")
        // The scroll takes a frame to show.
        Thread.sleep(120)
        val scale = bridge.web.width / box.getDouble("view").coerceAtLeast(1.0)
        val margin = 8 * scale
        return Rect(
            (box.getDouble("x") * scale - margin).toInt(),
            (box.getDouble("y") * scale - margin).toInt(),
            ((box.getDouble("x") + box.getDouble("w")) * scale + margin).toInt(),
            ((box.getDouble("y") + box.getDouble("h")) * scale + margin).toInt(),
        )
    }

    /** Lets the screen go. A page left open would draw and count for nobody. */
    fun release() {
        main.post { drop() }
    }

    // ---- inside -----------------------------------------------------------------------------

    private fun touch() {
        main.removeCallbacks(idle)
        main.postDelayed(idle, IDLE_MS)
    }

    private fun theirs(): Bridge =
        MainActivity.current?.page ?: throw IllegalStateException("Приложение свёрнуто: экрана сейчас нет")

    private fun theirPath(): String? =
        MainActivity.current?.page?.location?.takeIf { it.startsWith(Pages.ORIGIN + "/") }?.removePrefix(Pages.ORIGIN + "/")?.takeIf { it.isNotBlank() }

    /**
     * Width and height in CSS points: as the owner's screen lays its pages out, so a check sees
     * what the owner will; remembered for the time the app is out of sight.
     */
    private fun size(width: Int): Pair<Int, Int> {
        val prefs = App.prefs
        var usual = prefs.getInt(WIDTH, 380) to prefs.getInt(HEIGHT, 820)
        MainActivity.current?.page?.let { page ->
            val web = page.web
            if (web.width > 0 && web.height > 0) {
                val point = web.resources.displayMetrics.density * page.scaled / 100f
                usual = (web.width / point).roundToInt() to (web.height / point).roundToInt()
                prefs.edit().putInt(WIDTH, usual.first).putInt(HEIGHT, usual.second).apply()
            }
        }
        return (if (width > 0) width.coerceIn(240, 1600) else usual.first) to usual.second.coerceIn(480, 1400)
    }

    /** Main thread. */
    private fun build(width: Int, height: Int): Screen {
        val context = App.instance
        val metrics = context.resources.displayMetrics
        val wide = (width * metrics.density).roundToInt()
        val tall = (height * metrics.density).roundToInt()
        val reader = ImageReader.newInstance(wide, tall, PixelFormat.RGBA_8888, 2)
        // Nobody looks at what the display shows, but frames nobody takes would stop it drawing.
        reader.setOnImageAvailableListener({ runCatching { it.acquireLatestImage()?.close() } }, main)
        val display = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            "axiko-probe", wide, tall, metrics.densityDpi, reader.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
        )
        try {
            val window = Presentation(context, display.display)
            window.window?.setBackgroundDrawable(ColorDrawable(Themes.color("bg", context.getColor(R.color.bg))))
            val web = WebView(window.context)
            val bridge = Bridge(web, "probe")
            // A page can tell it is looked at by the agent, not the owner: it keeps what is meant for the owner (a tour).
            WebViewCompat.addDocumentStartJavaScript(web, "window.assiProbe = true;", setOf(Pages.ORIGIN))
            bridge.onGone = { release() }
            window.setContentView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            window.setOnDismissListener { if (screen?.window === window) drop() }
            window.show()
            // The app may be out of sight, its timers stopped: this screen needs them.
            web.resumeTimers()
            return Screen(display, reader, window, bridge, width)
        } catch (error: Throwable) {
            display.release()
            reader.close()
            throw error
        }
    }

    /** Main thread. */
    private fun drop() {
        main.removeCallbacks(idle)
        val old = screen ?: return
        screen = null
        runCatching { if (!MainActivity.shown) old.bridge.web.pauseTimers() }
        runCatching { old.window.setOnDismissListener(null) }
        runCatching { old.window.dismiss() }
        runCatching { old.bridge.destroy() }
        runCatching { old.display.release() }
        runCatching { old.reader.close() }
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == main.looper) return block()
        val done = CountDownLatch(1)
        var value: Result<T>? = null
        main.post {
            value = runCatching(block)
            done.countDown()
        }
        if (!done.await(15, TimeUnit.SECONDS)) throw IllegalStateException("Экран для проверок не построился за 15 с")
        return value!!.getOrThrow()
    }
}
