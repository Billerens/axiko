package app.assi.agent

import app.assi.App
import app.assi.core.Workspace
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the models cost: tokens and money per day and per who asked (an agent or a role).
 * Agents that work in the background spend unseen, so there is a daily limit to stop at.
 */
object Usage {
    private const val KEEP_DAYS = 60
    private val file: File get() = File(Workspace.state, "usage.json")

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun load(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }

    /** The daily limit in dollars; 0 means none. */
    val budget: Double get() = App.prefs.getString("budget.daily", "0")?.replace(',', '.')?.toDoubleOrNull() ?: 0.0

    /**
     * Adds one answer's usage as the provider reported it. Money is known only when the
     * provider says it (OpenRouter does); tokens always.
     */
    @Synchronized
    fun record(who: String, model: String, usage: JSONObject?) {
        usage ?: return
        val all = load()
        val day = all.optJSONObject(today()) ?: JSONObject().also { all.put(today(), it) }
        val entry = day.optJSONObject(who) ?: JSONObject().also { day.put(who, it) }
        val cached = usage.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens") ?: 0
        entry.put("calls", entry.optInt("calls") + 1)
        entry.put("input", entry.optLong("input") + usage.optLong("prompt_tokens"))
        entry.put("output", entry.optLong("output") + usage.optLong("completion_tokens"))
        entry.put("cached", entry.optLong("cached") + cached)
        entry.put("cost", entry.optDouble("cost", 0.0) + usage.optDouble("cost", 0.0))
        entry.put("model", model)
        val days = all.keys().asSequence().toList().sorted()
        days.dropLast(KEEP_DAYS).forEach { all.remove(it) }
        runCatching { file.writeText(all.toString()) }
    }

    @Synchronized
    fun spentToday(): Double {
        val day = load().optJSONObject(today()) ?: return 0.0
        return day.keys().asSequence().sumOf { day.getJSONObject(it).optDouble("cost", 0.0) }
    }

    /** Refuses a request once today's spending has reached the limit. */
    fun guard() {
        val limit = budget
        if (limit > 0 && spentToday() >= limit) {
            throw LlmError(
                "Дневной лимит трат исчерпан: потрачено \$${"%.2f".format(Locale.US, spentToday())} из \$${"%.2f".format(Locale.US, limit)}. " +
                    "Лимит меняется в разделе «Расход» в «Настройки».",
            )
        }
    }

    /** `{budget, days: [{day, cost, input, output, cached, who: {...}}]}` for the last [days], newest first. */
    @Synchronized
    fun summary(days: Int): JSONObject {
        val all = load()
        val list = org.json.JSONArray()
        for (day in all.keys().asSequence().toList().sortedDescending().take(days)) {
            val who = all.getJSONObject(day)
            val total = JSONObject().put("day", day).put("who", who)
            for (field in listOf("input", "output", "cached", "calls")) {
                total.put(field, who.keys().asSequence().sumOf { who.getJSONObject(it).optLong(field) })
            }
            total.put("cost", who.keys().asSequence().sumOf { who.getJSONObject(it).optDouble("cost", 0.0) })
            list.put(total)
        }
        return JSONObject().put("budget", budget).put("days", list)
    }
}
