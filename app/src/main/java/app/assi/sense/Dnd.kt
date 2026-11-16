package app.assi.sense

import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import app.assi.App
import org.json.JSONObject

/**
 * "Do not disturb": what lets through now, and switching it. The notification service may ask
 * for a mode by itself; the app's access to the notification policy is the second way, and
 * the one an alarm channel needs to sound through the silence.
 */
object Dnd {
    private val manager: NotificationManager get() = App.instance.getSystemService(NotificationManager::class.java)

    /** `{filter: 'all'|'priority'|'alarms'|'none', access}`. */
    fun get(): JSONObject = JSONObject()
        .put("filter", name(manager.currentInterruptionFilter))
        .put("access", manager.isNotificationPolicyAccessGranted)

    fun set(filter: String): Boolean {
        val wanted = code(filter) ?: error("Нет такого режима тишины: $filter")
        if (manager.currentInterruptionFilter == wanted) return true
        NoticeService.instance?.let { service ->
            service.requestInterruptionFilter(wanted)
            // The request is answered later, if at all: wait for the mode to change.
            val until = System.currentTimeMillis() + 2000
            while (System.currentTimeMillis() < until) {
                if (manager.currentInterruptionFilter == wanted) return true
                Thread.sleep(100)
            }
        }
        if (!manager.isNotificationPolicyAccessGranted) {
            error("Режим тишины не переключился: включите Assi доступ к режиму «Не беспокоить» (dnd.request) или чтение уведомлений")
        }
        manager.setInterruptionFilter(wanted)
        return true
    }

    /** The system screen where the owner gives the app access to the notification policy. */
    fun request(): Boolean {
        App.instance.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun name(code: Int): String = when (code) {
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "none"
        else -> "all"
    }

    fun code(name: String): Int? = when (name) {
        "all" -> NotificationManager.INTERRUPTION_FILTER_ALL
        "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
        "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
        "none" -> NotificationManager.INTERRUPTION_FILTER_NONE
        else -> null
    }
}
