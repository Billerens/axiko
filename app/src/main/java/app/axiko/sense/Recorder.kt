package app.axiko.sense

import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.Toast
import app.axiko.App
import app.axiko.MainActivity
import app.axiko.R
import app.axiko.agent.Attach
import app.axiko.agent.Inbox
import app.axiko.core.I18n
import app.axiko.core.Pages
import org.json.JSONObject

/**
 * "Show it once": the owner does something in another app while the screen service watches —
 * which app, what was pressed, what was typed — and the steps go to the agent with the next
 * message, to be turned into something that can be repeated.
 *
 * Watching ends when the owner comes back to Axiko, presses the floating stop button, or after
 * a few minutes. What is typed into password fields is not recorded.
 */
object Recorder {
    private const val MAX_MS = 4 * 60_000L
    private const val MAX_STEPS = 80
    private val main = Handler(Looper.getMainLooper())
    private val steps = ArrayList<JSONObject>()
    private var stopButton: View? = null
    private var started = 0L
    private var lastPackage = ""

    @Volatile
    var active = false
        private set

    fun status(): JSONObject = JSONObject().put("recording", active).put("steps", synchronized(steps) { steps.size }).put("screen", Screen.on)

    fun start(): JSONObject {
        val service = ScreenService.instance
            ?: error("Доступ к экрану выключен. Откройте «Настройки» Axiko, раздел «Чувства телефона», и включите «Видеть экран».")
        synchronized(steps) { steps.clear() }
        lastPackage = App.instance.packageName
        started = System.currentTimeMillis()
        active = true
        service.watch(true)
        val begun = started
        main.post {
            showStop()
            Toast.makeText(App.instance, I18n.t("Покажите действие и вернитесь в Axiko — запись остановится сама."), Toast.LENGTH_LONG).show()
            MainActivity.current?.moveTaskToBack(true)
        }
        main.postDelayed({ if (active && started == begun) stop() }, MAX_MS)
        return status()
    }

    private fun words(event: AccessibilityEvent): String =
        (event.text.joinToString(" ").ifBlank { event.contentDescription?.toString().orEmpty() }).trim().take(160)

    /** What the screen service sees while the owner shows the action. */
    fun event(event: AccessibilityEvent) {
        if (!active) return
        val name = event.packageName?.toString().orEmpty()
        // The keyboard is how things are typed, not what is done.
        if (name.contains("inputmethod") || name.contains("keyboard") || name.contains("honeyboard")) return
        val source = runCatching { event.source }.getOrNull()
        val id = source?.viewIdResourceName?.substringAfter("id/").orEmpty()
        val kind = event.className?.toString()?.substringAfterLast('.').orEmpty()
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (name.isEmpty() || name == lastPackage) return
                val mine = name == App.instance.packageName
                // Back in Axiko: the showing is over.
                if (mine && synchronized(steps) { steps.isNotEmpty() }) {
                    stop()
                    return
                }
                lastPackage = name
                if (!mine) add(JSONObject().put("do", "app").put("package", name).put("app", Screen.appName(name)))
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                if (name == App.instance.packageName) return
                add(
                    JSONObject().put("do", if (event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) "hold" else "press")
                        .put("text", words(event)).put("id", id).put("kind", kind),
                )
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (name == App.instance.packageName) return
                val typed = JSONObject().put("do", "type").put("id", id).put("kind", kind)
                    .put("text", if (event.isPassword) "" else event.text.joinToString("").take(300)).put("secret", event.isPassword)
                synchronized(steps) {
                    // Letter by letter is one typing: the last state of the field is what was typed.
                    val last = steps.lastOrNull()
                    if (last != null && last.optString("do") == "type" && last.optString("id") == id) steps[steps.size - 1] = typed else steps.add(typed)
                }
            }
        }
    }

    private fun add(step: JSONObject) {
        synchronized(steps) { if (steps.size < MAX_STEPS) steps.add(step) }
    }

    private fun line(index: Int, step: JSONObject): String {
        val where = buildList {
            step.optString("id").takeIf { it.isNotEmpty() }?.let { add("id $it") }
            step.optString("kind").takeIf { it.isNotEmpty() }?.let { add(it) }
        }.joinToString(", ").let { if (it.isEmpty()) "" else " [$it]" }
        val text = step.optString("text")
        return "${index + 1}. " + when (step.optString("do")) {
            "app" -> "Открыл приложение «${step.optString("app")}» (${step.optString("package")})"
            "hold" -> "Долго нажал: \"$text\"$where"
            "type" -> if (step.optBoolean("secret")) "Ввёл пароль в поле$where (значение не записано)" else "Ввёл в поле$where: \"$text\""
            else -> "Нажал: \"$text\"$where"
        }
    }

    /** Ends the showing: the steps wait in the chat for the owner's words. */
    fun stop(): JSONObject {
        if (!active) return status()
        active = false
        ScreenService.instance?.watch(false)
        main.post { hideStop() }
        val shown = synchronized(steps) { steps.toList() }
        if (shown.isNotEmpty()) {
            val note = "Владелец показал действие на телефоне. Шаги по порядку:\n" +
                shown.mapIndexed { index, step -> line(index, step) }.joinToString("\n") +
                "\n\nЭто запись одного показа: тексты и поля — как были в этот раз. Чтобы действие можно было повторять, " +
                "сделайте из него сценарий (см. руководство, «Показанное действие») и выясните у владельца, что в нём меняется от раза к разу."
            Inbox.add(Attach(I18n.t("Показанное действие, шагов: {}").replace("{}", shown.size.toString()), note))
        }
        Pages.broadcast("record.done", JSONObject().put("steps", shown.size).toString())
        Assist.openChat(App.instance)
        return status()
    }

    // ---- the stop button over other apps -------------------------------------------------------------

    private fun showStop() {
        if (stopButton != null || !Bubble.allowed) return
        val context = App.instance
        val density = context.resources.displayMetrics.density
        val size = (52 * density).toInt()
        val button = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(context.getColor(R.color.danger))
            }
            contentDescription = I18n.t("Закончить показ")
            // A square: the sign for "stop".
            addView(
                View(context).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = 3 * density
                        setColor(context.getColor(R.color.bg))
                    }
                },
                FrameLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt(), Gravity.CENTER),
            )
            setOnClickListener { stop() }
        }
        val params = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (12 * density).toInt()
            y = (context.resources.displayMetrics.heightPixels * 0.45f).toInt()
        }
        runCatching {
            context.getSystemService(WindowManager::class.java).addView(button, params)
            stopButton = button
        }
    }

    private fun hideStop() {
        stopButton?.let { runCatching { App.instance.getSystemService(WindowManager::class.java).removeView(it) } }
        stopButton = null
    }
}
