package app.assi.sense

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.assi.App
import app.assi.core.Engine
import org.json.JSONArray
import org.json.JSONObject

/** Hears every notification the phone shows: mail, messengers, banks, anything. */
class NoticeService : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onNotificationPosted(notification: StatusBarNotification) {
        val item = Notices.describe(notification) ?: return
        Notices.remember(item, notification.notification.contentIntent)
        // Scripts may react at once: on('notification', ...). Every update of a notification comes again.
        Engine.fire("notification", item)
    }

    override fun onNotificationRemoved(notification: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        if (!Notices.worth(notification)) return
        // A messenger takes its notification away once the chat was read there: modules learn it here.
        Engine.fire(
            "notification.removed",
            JSONObject().put("key", notification.key).put("package", notification.packageName)
                .put("time", System.currentTimeMillis()).put("reason", Notices.reason(reason)),
        )
    }

    companion object {
        @Volatile
        var instance: NoticeService? = null
            private set
    }
}

object Notices {
    private const val KEEP = 300
    private val recent = ArrayDeque<JSONObject>()

    /** What a tap on a notification opens, by key: it outlives the notification while the process lives. */
    private val opens = object : LinkedHashMap<String, PendingIntent>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PendingIntent>?) = size > KEEP
    }

    val on: Boolean get() = NoticeService.instance != null

    /** Whether the notification is worth telling about: not the app's own, not a group's summary. */
    fun worth(notification: StatusBarNotification): Boolean =
        notification.packageName != App.instance.packageName &&
            notification.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0

    /** A notification as plain data; null for what is not worth keeping. */
    fun describe(notification: StatusBarNotification): JSONObject? =
        if (worth(notification)) read(notification) else null

    /**
     * Everything a module may need of a notification: the visible title and text, and for a chat
     * the messages themselves (MessagingStyle), whose conversation and whose profile it is.
     */
    fun read(notification: StatusBarNotification): JSONObject? {
        val shown = notification.notification
        val extras = shown.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        val messages = messages(extras)
        if (title.isBlank() && text.isBlank() && messages.length() == 0) return null
        val manager = App.instance.packageManager
        val app = runCatching {
            manager.getApplicationLabel(manager.getApplicationInfo(notification.packageName, 0)).toString()
        }.getOrDefault(notification.packageName)
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val flag = if (extras.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION)) extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) else null
        // Samsung's dual messengers live in a profile of their own (95): the same chat app twice.
        // The number is public only under the old name: UserHandle keeps it to the system.
        @Suppress("DEPRECATION")
        val user = notification.userId
        return JSONObject()
            .put("time", notification.postTime)
            .put("package", notification.packageName)
            .put("app", app)
            .put("title", title)
            .put("text", text)
            .put("ongoing", notification.isOngoing)
            .put("canReply", shown.actions?.any { !it.remoteInputs.isNullOrEmpty() } == true)
            .put("key", notification.key)
            .put("messages", messages)
            .put("conversationTitle", conversation ?: JSONObject.NULL)
            .put("isGroup", messages.length() > 0 && group(flag, conversation))
            .put("shortcutId", shown.shortcutId ?: JSONObject.NULL)
            .put("channel", shown.channelId ?: JSONObject.NULL)
            .put("category", shown.category ?: JSONObject.NULL)
            .put("user", user)
            .put("id", notification.id)
            .put("tag", notification.tag ?: JSONObject.NULL)
            .put("group", shown.group ?: JSONObject.NULL)
    }

    /** The messages of a chat notification, `[{sender, text, time}]`; the owner's own have the sender null. */
    private fun messages(extras: Bundle): JSONArray {
        @Suppress("DEPRECATION")
        val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return JSONArray()
        @Suppress("DEPRECATION")
        val self = extras.getParcelable<Person>(Notification.EXTRA_MESSAGING_PERSON)
        val selfName = self?.name?.toString() ?: extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)?.toString()
        val list = Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles).map { message ->
            val person = message.senderPerson
            @Suppress("DEPRECATION")
            val name = person?.name?.toString() ?: message.sender?.toString()
            JSONObject()
                .put("sender", sender(name, person?.key, selfName, self?.key) ?: JSONObject.NULL)
                .put("text", message.text?.toString().orEmpty())
                .put("time", message.timestamp)
        }
        return JSONArray(list)
    }

    /**
     * Who wrote a message as a module should see it: null for the owner. A chat app marks the
     * owner's own messages with no sender, or with the person the whole conversation is "from";
     * keys tell people apart where the app gives them, names where it does not.
     */
    fun sender(name: String?, key: String?, selfName: String?, selfKey: String?): String? = when {
        name.isNullOrBlank() -> null
        key != null && selfKey != null -> name.takeIf { key != selfKey }
        else -> name.takeIf { it != selfName }
    }

    /** A group chat: as the app says, or — for apps that do not say — a conversation with a title of its own. */
    fun group(flag: Boolean?, conversationTitle: String?): Boolean = flag ?: !conversationTitle.isNullOrBlank()

    /** Why a notification went away, in the words modules use. */
    fun reason(code: Int): String = when (code) {
        NotificationListenerService.REASON_CLICK -> "click"
        // Swiping the whole group away takes its members with it: the owner's doing, too.
        NotificationListenerService.REASON_CANCEL, NotificationListenerService.REASON_CANCEL_ALL,
        NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED -> "cancel"
        NotificationListenerService.REASON_APP_CANCEL, NotificationListenerService.REASON_APP_CANCEL_ALL -> "app"
        else -> "other"
    }

    @Synchronized
    fun remember(item: JSONObject, open: PendingIntent?) {
        // An updated notification replaces its earlier version.
        recent.removeAll { it.optString("key") == item.optString("key") }
        recent.addLast(item)
        while (recent.size > KEEP) recent.removeFirst()
        if (open != null) opens[item.optString("key")] = open
    }

    /** The latest notifications seen since the app started, newest first. */
    @Synchronized
    fun recent(limit: Int): JSONArray = JSONArray(recent.toList().takeLast(limit.coerceIn(1, KEEP)).reversed())

    /** What is in the notification shade right now. */
    fun active(): JSONArray {
        val service = NoticeService.instance
            ?: error("Доступ к уведомлениям выключен. Откройте «Настройки» Axiko, раздел «Чувства телефона», и включите «Читать уведомления».")
        return JSONArray(service.activeNotifications.mapNotNull { describe(it) }.sortedByDescending { it.getLong("time") })
    }

    /**
     * Opens what a tap on the notification would open — a messenger's chat, a bank's screen —
     * also after the notification is gone, as long as the app that made it still honours it.
     */
    fun open(key: String): Boolean {
        val live = NoticeService.instance?.activeNotifications?.firstOrNull { it.key == key }?.notification?.contentIntent
        val pending = live ?: synchronized(this) { opens[key] } ?: return false
        return try {
            // A tap the owner makes in Axiko starts the other app's screen; Android 14 wants that said out loud.
            val options = if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
            } else {
                null
            }
            pending.send(App.instance, 0, null, null, null, null, options)
            true
        } catch (gone: PendingIntent.CanceledException) {
            false
        }
    }

    /**
     * Answers a notification the way its own "Reply" button would: a messenger then sends the
     * text into that chat. Works only while the notification is still in the shade.
     */
    fun reply(key: String, text: String): Boolean {
        val service = NoticeService.instance ?: error("Доступ к уведомлениям выключен")
        val notification = service.activeNotifications.firstOrNull { it.key == key }
            ?: error("Этого уведомления уже нет в шторке")
        val action = notification.notification.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
            ?: error("На это уведомление нельзя ответить текстом")
        val results = android.os.Bundle()
        action.remoteInputs.forEach { results.putCharSequence(it.resultKey, text) }
        val filled = android.content.Intent()
        android.app.RemoteInput.addResultsToIntent(action.remoteInputs, filled, results)
        action.actionIntent.send(service, 0, filled)
        return true
    }

    fun dismiss(key: String): Boolean {
        val service = NoticeService.instance ?: return false
        service.cancelNotification(key)
        return true
    }
}
