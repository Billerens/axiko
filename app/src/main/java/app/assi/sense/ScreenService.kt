package app.assi.sense

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.assi.App
import app.assi.core.Engine
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The accessibility service: the app's eyes and hands in other apps. */
class ScreenService : AccessibilityService() {
    private var lastPackage = ""

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    /**
     * What the service is told about. Usually only which app is in front; while the owner shows
     * an action ([Recorder]) — presses and typing too.
     */
    fun watch(everything: Boolean) {
        // The system does not always hand the current settings back: then they are stated anew,
        // the same as in res/xml/screen_service.xml (what the service may do is fixed there).
        val info = serviceInfo ?: android.accessibilityservice.AccessibilityServiceInfo().apply {
            feedbackType = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = android.accessibilityservice.AccessibilityServiceInfo.DEFAULT or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            if (everything) AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED else 0
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (Recorder.active) Recorder.event(event)
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val name = event.packageName?.toString() ?: return
        if (name == lastPackage) return
        lastPackage = name
        // Scripts may follow which app is in front: on('screen.app', ...).
        Engine.fire("screen.app", JSONObject().put("package", name).put("title", event.text.joinToString(" ")))
    }

    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        var instance: ScreenService? = null
            private set
    }
}

/** What the screen service offers: read the screen, act on it, take a picture of it. */
object Screen {
    val on: Boolean get() = ScreenService.instance != null

    /** The nodes of the last reading, by the numbers shown in its outline. */
    @Volatile
    private var numbered: List<AccessibilityNodeInfo> = emptyList()

    private fun service(): ScreenService = ScreenService.instance
        ?: error("Доступ к экрану выключен. Откройте «Настройки» Assi, раздел «Чувства телефона», и включите «Видеть экран».")

    fun appName(packageName: String): String = label(packageName)

    private fun label(packageName: String): String = runCatching {
        val manager = App.instance.packageManager
        manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    /**
     * What is on the screen now: the app and an outline of everything with text or that can be
     * pressed. Each line starts with a number to use in [click] and [type].
     */
    fun read(maxNodes: Int = 350): JSONObject {
        val root = service().rootInActiveWindow ?: error("На экране нет окна, которое можно прочитать")
        val nodes = ArrayList<AccessibilityNodeInfo>()
        val outline = StringBuilder()
        val bounds = Rect()

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (nodes.size >= maxNodes) return
            val text = node.text?.toString()?.trim().orEmpty()
            val description = node.contentDescription?.toString()?.trim().orEmpty()
            val shown = node.isVisibleToUser &&
                (text.isNotEmpty() || description.isNotEmpty() || node.isClickable || node.isEditable || node.isScrollable || node.isCheckable)
            if (shown) {
                node.getBoundsInScreen(bounds)
                outline.append("  ".repeat(depth.coerceAtMost(8))).append('[').append(nodes.size).append("] ")
                outline.append(node.className?.toString()?.substringAfterLast('.') ?: "View")
                if (text.isNotEmpty()) outline.append(" \"").append(text.take(200)).append('"')
                if (description.isNotEmpty() && description != text) outline.append(" (").append(description.take(120)).append(')')
                // The element's own name in its app: steadier than its text, which may be translated or change.
                node.viewIdResourceName?.substringAfter("id/")?.takeIf { it.isNotEmpty() }?.let { outline.append(" #").append(it) }
                val marks = buildList {
                    if (node.isClickable) add("нажимается")
                    if (node.isEditable) add("поле ввода")
                    if (node.isScrollable) add("прокручивается")
                    if (node.isCheckable) add(if (node.isChecked) "включено" else "выключено")
                    if (node.isFocused) add("в фокусе")
                }
                if (marks.isNotEmpty()) outline.append(" {").append(marks.joinToString(", ")).append('}')
                outline.append(" @").append(bounds.centerX()).append(',').append(bounds.centerY()).append('\n')
                nodes.add(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it, if (shown) depth + 1 else depth) }
            }
        }
        walk(root, 0)
        numbered = nodes
        val name = root.packageName?.toString().orEmpty()
        return JSONObject().put("package", name).put("app", label(name)).put("outline", outline.toString())
    }

    private fun node(index: Int): AccessibilityNodeInfo =
        numbered.getOrNull(index) ?: error("Нет элемента с номером $index: прочитайте экран заново")

    /** Presses the element with that number: the element itself, or what contains it. */
    fun click(index: Int): Boolean {
        var target: AccessibilityNodeInfo? = node(index)
        while (target != null && !target.isClickable) target = target.parent
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val bounds = Rect()
        node(index).getBoundsInScreen(bounds)
        return tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    /** Puts text into the field with that number, or into the focused one. */
    fun type(text: String, index: Int? = null): Boolean {
        val field = if (index != null) node(index) else service().findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: error("Нет поля ввода в фокусе: нажмите на поле или укажите его номер")
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    fun scroll(index: Int, forward: Boolean): Boolean {
        var target: AccessibilityNodeInfo? = node(index)
        while (target != null && !target.isScrollable) target = target.parent
        return target?.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
        ) ?: false
    }

    fun tap(x: Float, y: Float): Boolean = gesture(Path().apply { moveTo(x, y) }, 60)

    fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, ms: Long = 300): Boolean =
        gesture(Path().apply { moveTo(fromX, fromY); lineTo(toX, toY) }, ms)

    private fun gesture(path: Path, ms: Long): Boolean {
        val done = CountDownLatch(1)
        var ok = false
        val description = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, ms.coerceAtLeast(1))).build()
        val sent = service().dispatchGesture(
            description,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok = true
                    done.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) = done.countDown()
            },
            null,
        )
        if (!sent) return false
        done.await(ms + 3000, TimeUnit.MILLISECONDS)
        return ok
    }

    /** back, home, recents, notifications, quick_settings, lock. */
    fun global(action: String): Boolean = service().performGlobalAction(
        when (action) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            else -> error("Нет действия $action")
        },
    )

    /** Starts an app by its package or by (a part of) its name. */
    fun openApp(name: String): String {
        val manager = App.instance.packageManager
        val launchable = manager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        )
        val wanted = name.trim().lowercase()
        val match = launchable.firstOrNull { it.activityInfo.packageName == name }
            ?: launchable.firstOrNull { it.loadLabel(manager).toString().lowercase() == wanted }
            ?: launchable.firstOrNull { it.loadLabel(manager).toString().lowercase().contains(wanted) }
            ?: error("Не нашлось приложения «$name»")
        val intent = manager.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: error("Приложение «$name» нельзя открыть")
        App.instance.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return match.loadLabel(manager).toString()
    }

    /** The whole screen as a JPEG in base64, whatever app is on it. */
    fun shot(maxWidth: Int = 720): String {
        val done = CountDownLatch(1)
        var encoded: String? = null
        var failure: String? = null
        service().takeScreenshot(
            Display.DEFAULT_DISPLAY,
            Engine.pool,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    try {
                        val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val bitmap = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                        result.hardwareBuffer.close()
                        if (bitmap == null) {
                            failure = "Система не отдала снимок"
                        } else {
                            val scale = minOf(1f, maxWidth.toFloat() / bitmap.width)
                            val scaled = if (scale < 1f) {
                                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                            } else {
                                bitmap
                            }
                            val bytes = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, 80, bytes)
                            encoded = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                        }
                    } catch (error: Exception) {
                        failure = error.message
                    } finally {
                        done.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    failure = if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        "Снимки экрана можно делать не чаще раза в секунду"
                    } else {
                        "Не удалось снять экран (код $errorCode)"
                    }
                    done.countDown()
                }
            },
        )
        done.await(6, TimeUnit.SECONDS)
        return encoded ?: error(failure ?: "Не удалось снять экран")
    }
}
