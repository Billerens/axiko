package app.assi.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.provider.Settings
import app.assi.App
import app.assi.MainActivity
import app.assi.R
import app.assi.agent.Agent
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The app's own notifications. When an agent finishes while the app is out of sight, its answer
 * comes as a notification the owner can reply to right there, without opening the app. Scripts
 * post theirs through [post] (`assi.notify`): with buttons that call a module's function, a
 * field for a reply, a clock, a progress bar.
 */
object Notify {
    private const val CHANNEL = "answers"
    private const val KEY_REPLY = "reply"

    /** Buttons a notification can carry: what the shade has room for. */
    private const val MAX_ACTIONS = 3

    /**
     * The ongoing notifications scripts posted, by id, with how many times each was posted: how an
     * answered one is put back without its spinner when the function did not post it anew.
     */
    private val posted = ConcurrentHashMap<String, Pair<Int, Notification>>()

    fun answer(agent: String, thread: String, title: String, text: String) {
        val app = App.instance
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Ответы агентов", NotificationManager.IMPORTANCE_DEFAULT))
        val id = (agent + thread).hashCode()
        val open = PendingIntent.getActivity(
            app, id,
            Intent(app, MainActivity::class.java).putExtra("chat", agent).putExtra("thread", thread),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // A reply intent carries the typed text, so it has to stay mutable.
        val reply = PendingIntent.getBroadcast(
            app, id,
            Intent(app, ReplyReceiver::class.java).putExtra("agent", agent).putExtra("thread", thread).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = Notification.Action.Builder(null, "Ответить", reply)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Сообщение").build())
            .build()
        manager.notify(
            id,
            Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_spark)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .addAction(action)
                .setAutoCancel(true)
                .build(),
        )
    }

    /**
     * A script's notification (`assi.notify(options)`); returns its id. The same id posted again
     * replaces it. Options: title, text, open, id, actions [{title, call, fn, args, reply}],
     * ongoing, when, chronometer, countdown, silent, progress {max, value}, channel.
     */
    fun post(options: JSONObject): String {
        val app = App.instance
        val id = options.opt("id")?.takeIf { it != JSONObject.NULL }?.toString()?.ifBlank { null }
            ?: UUID.randomUUID().toString().take(12)
        val silent = options.optBoolean("silent")
        val kind = options.optString("channel").ifBlank { if (silent) "quiet" else "normal" }
        val ongoing = options.optBoolean("ongoing")
        val text = options.optString("text")
        val open = Intent(app, MainActivity::class.java)
        options.optString("open").takeIf { it.isNotBlank() }?.let { open.putExtra("open", it) }
        val builder = Notification.Builder(app, channel(kind))
            .setSmallIcon(R.drawable.ic_spark)
            .setContentTitle(options.optString("title").ifBlank { "Аксико" })
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(PendingIntent.getActivity(app, id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            // A timer updated every minute must not ring every minute.
            .setOnlyAlertOnce(ongoing)
        if (kind == "alarm") builder.setCategory(Notification.CATEGORY_ALARM)
        if (options.has("when")) builder.setWhen(options.getLong("when")).setShowWhen(true)
        val countdown = options.optBoolean("countdown")
        if (options.optBoolean("chronometer") || countdown) builder.setUsesChronometer(true).setChronometerCountDown(countdown)
        options.optJSONObject("progress")?.let { progress ->
            val max = progress.optInt("max")
            builder.setProgress(max, progress.optInt("value"), max <= 0)
        }
        val actions = options.optJSONArray("actions") ?: JSONArray()
        for (index in 0 until minOf(actions.length(), MAX_ACTIONS)) builder.addAction(action(id, index, actions.getJSONObject(index), ongoing))
        val shown = builder.build()
        if (ongoing) posted[id] = ((posted[id]?.first ?: 0) + 1) to shown else posted.remove(id)
        app.getSystemService(NotificationManager::class.java).notify(id, 0, shown)
        return id
    }

    fun cancel(id: String) {
        posted.remove(id)
        App.instance.getSystemService(NotificationManager::class.java).cancel(id, 0)
    }

    /** The channel of a kind, made when first needed: "quiet", "normal" or "alarm". */
    private fun channel(kind: String): String {
        val manager = App.instance.getSystemService(NotificationManager::class.java)
        // The first two keep the ids lib/assi.js gave them, so the owner's settings for them stay.
        val channel = when (kind) {
            "quiet" -> NotificationChannel("assi-quiet", "Аксико: тихие", NotificationManager.IMPORTANCE_LOW)
            "normal" -> NotificationChannel("assi", "Аксико", NotificationManager.IMPORTANCE_DEFAULT)
            // Sounds through "do not disturb": the system honours that only with access to the
            // notification policy, and takes it up when the channel is made again after access is given.
            "alarm" -> NotificationChannel("assi-alarm", "Аксико: будильники", NotificationManager.IMPORTANCE_HIGH).apply {
                setBypassDnd(true)
                setSound(
                    Settings.System.DEFAULT_NOTIFICATION_URI,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
            }
            else -> error("Нет такого канала уведомлений: $kind")
        }
        manager.createNotificationChannel(channel)
        return channel.id
    }

    /** A button that calls a module's function in the engine, with the typed text when it asks for one. */
    private fun action(id: String, index: Int, spec: JSONObject, ongoing: Boolean): Notification.Action {
        val call = spec.optString("call")
        val fn = spec.optString("fn")
        if (call.isBlank() || fn.isBlank()) error("У кнопки уведомления нужны call и fn")
        val reply = spec.optBoolean("reply")
        val intent = Intent(App.instance, ActionReceiver::class.java)
            .putExtra("id", id).putExtra("call", call).putExtra("fn", fn).putExtra("reply", reply).putExtra("ongoing", ongoing)
            // Any JSON value: wrapped in an array to travel as text.
            .putExtra("args", JSONArray().put(spec.opt("args") ?: JSONObject.NULL).toString())
        val pending = PendingIntent.getBroadcast(
            App.instance, "$id#$index".hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (reply) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE,
        )
        val button = Notification.Action.Builder(null, spec.optString("title"), pending)
        if (reply) button.addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel(spec.optString("title")).build())
        return button.build()
    }

    /** A button of a script's notification was pressed: its function runs, the app stays closed. */
    private fun pressed(intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val ongoing = intent.getBooleanExtra("ongoing", false)
        val reply = intent.getBooleanExtra("reply", false)
        // Done with, as a tap would make it — before the function, which may post it anew.
        if (!ongoing) cancel(id)
        val version = posted[id]?.first
        val args = JSONArray(intent.getStringExtra("args") ?: "[null]")
        if (reply) args.put(RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString().orEmpty())
        try {
            Engine.rpc(intent.getStringExtra("call").orEmpty(), intent.getStringExtra("fn").orEmpty(), args.toString())
        } catch (error: Throwable) {
            Engine.host.log("error", "Кнопка уведомления $id: " + Engine.describe(error))
        }
        // An answered notification shows a spinner until it is posted again; the function may have done it.
        val left = posted[id]
        if (ongoing && reply && left != null && left.first == version) {
            App.instance.getSystemService(NotificationManager::class.java).notify(id, 0, left.second)
        }
    }

    class ActionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            EngineService.start(context)
            Engine.pool.execute { pressed(intent) }
        }
    }

    class ReplyReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString().orEmpty()
            val agent = intent.getStringExtra("agent") ?: return
            EngineService.start(context)
            if (text.isNotBlank()) Agent.chat(agent, intent.getStringExtra("thread")).send(text)
            // The answer to this reply will come as a new notification.
            context.getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra("id", 0))
        }
    }
}
