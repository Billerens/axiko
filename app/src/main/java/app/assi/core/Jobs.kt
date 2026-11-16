package app.assi.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.assi.App
import org.json.JSONObject
import java.util.Calendar

/**
 * Work on a schedule that happens whether or not the app is open. A script registers a job
 * (`job('digest', { at: '08:30' }, fn)`); the system alarm wakes the app at the time, and the
 * script's function runs in the engine.
 */
object Jobs {
    private const val PREF = "jobs"
    const val ACTION = "app.assi.JOB"

    private fun all(): JSONObject = runCatching { JSONObject(App.prefs.getString(PREF, "{}")!!) }.getOrElse { JSONObject() }

    private fun save(jobs: JSONObject) = App.prefs.edit().putString(PREF, jobs.toString()).apply()

    /** What is scheduled: a one-off job that has run is no longer. */
    fun list(): JSONObject = all().apply {
        keys().asSequence().toList().filter { optJSONObject(it)?.optBoolean("done") == true }.forEach { remove(it) }
    }

    /**
     * `{every: minutes}`, `{at: "HH:mm"}` (daily) or `{once: ms}` (one moment, then the job is
     * over). Registering the same schedule again, as a script does on every start, keeps the time
     * already set — and a moment that has already run does not run again.
     */
    @Synchronized
    fun set(key: String, spec: JSONObject) {
        val jobs = all()
        val known = jobs.optJSONObject(key)
        val once = spec.optLong("once")
        if (once > 0) {
            if (known != null && known.optLong("once") == once) return
            jobs.put(key, JSONObject().put("once", once).put("next", once))
            save(jobs)
            // A moment already past rings at once.
            arm(key, once, spec)
            return
        }
        val same = known != null && known.optInt("every") == spec.optInt("every") && known.optString("at") == spec.optString("at") &&
            known.optBoolean("exact") == spec.optBoolean("exact")
        val next = if (same && known!!.optLong("next") > System.currentTimeMillis()) known.optLong("next") else next(spec)
        jobs.put(key, JSONObject().put("every", spec.optInt("every")).put("at", spec.optString("at"))
            .put("exact", spec.optBoolean("exact")).put("next", next))
        save(jobs)
        arm(key, next, spec)
    }

    @Synchronized
    fun remove(key: String) {
        val jobs = all()
        jobs.remove(key)
        save(jobs)
        App.instance.getSystemService(AlarmManager::class.java).cancel(pending(key))
    }

    /** After a restart of the phone the system has forgotten the alarms: set them again. */
    @Synchronized
    fun rearm() {
        val jobs = all()
        for (key in jobs.keys()) {
            val job = jobs.getJSONObject(key)
            if (job.has("once")) {
                // Missed while the phone was off: it runs now, late rather than never.
                if (!job.optBoolean("done")) arm(key, job.getLong("once"), job)
                continue
            }
            val next = job.optLong("next").takeIf { it > System.currentTimeMillis() } ?: next(job)
            job.put("next", next)
            arm(key, next, job)
        }
        save(jobs)
    }

    /** The alarm rang: run the script's function and set the next time. */
    @Synchronized
    fun fired(key: String) {
        val jobs = all()
        val job = jobs.optJSONObject(key) ?: return
        if (job.has("once")) {
            // Kept, marked as run: a script registering the same moment again on its next start is not run twice.
            if (job.optBoolean("done")) return
            job.put("done", true)
            save(jobs)
            Engine.fire("job", JSONObject().put("key", key).put("once", true))
            return
        }
        val next = next(job)
        job.put("next", next)
        save(jobs)
        arm(key, next, job)
        Engine.fire("job", JSONObject().put("key", key))
    }

    private fun next(spec: JSONObject): Long {
        val every = spec.optInt("every")
        if (every > 0) return System.currentTimeMillis() + every * 60_000L
        val parts = spec.optString("at").split(':')
        val time = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, parts.getOrNull(0)?.toIntOrNull() ?: 9)
            set(Calendar.MINUTE, parts.getOrNull(1)?.toIntOrNull() ?: 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (time.timeInMillis <= System.currentTimeMillis()) time.add(Calendar.DAY_OF_YEAR, 1)
        return time.timeInMillis
    }

    private fun pending(key: String): PendingIntent = PendingIntent.getBroadcast(
        App.instance, key.hashCode(),
        Intent(App.instance, JobReceiver::class.java).setAction(ACTION).putExtra("key", key),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Periodic maintenance may be batched/deferred in Doze; reminders retain their precise time. */
    internal fun needsExact(spec: JSONObject): Boolean = spec.optInt("every") <= 0 || spec.optBoolean("exact")

    internal fun batchWindow(spec: JSONObject): Long = (spec.optInt("every").toLong() * 60_000 / 3)
        .coerceIn(60_000, 15 * 60_000)

    private fun arm(key: String, time: Long, spec: JSONObject) {
        val alarms = App.instance.getSystemService(AlarmManager::class.java)
        if (!needsExact(spec)) {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, time, batchWindow(spec), pending(key))
            return
        }
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending(key))
        } catch (denied: SecurityException) {
            // Exact alarms were switched off for the app: an approximate time is better than none.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending(key))
        }
    }
}

/** Wakes the app for a job and brings it back after the phone restarts. */
class JobReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        EngineService.start(context)
        when (intent.action) {
            Jobs.ACTION -> intent.getStringExtra("key")?.let {
                Jobs.fired(it)
                // The scheduled backup is the shell's own job: it must not depend on workspace scripts.
                if (it == app.assi.backup.Backup.JOB) app.assi.backup.Backup.due()
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Jobs.rearm()
        }
    }
}
