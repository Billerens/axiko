package app.axiko.agent

import app.axiko.App
import app.axiko.core.Workspace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The separate chats of each agent. A chat is a file of its own; a small index keeps the
 * titles and times so that listing them does not read every conversation.
 */
object Threads {
    private val assistantId = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    private val threadId = Regex("[a-zA-Z0-9-]{1,80}")
    private fun dir(agent: String): File = File(Workspace.state, "chats/$agent").apply { mkdirs() }

    private fun indexFile(agent: String): File = File(dir(agent), "index.json")

    private fun index(agent: String): JSONObject =
        runCatching { JSONObject(indexFile(agent).readText()) }.getOrElse { JSONObject() }

    fun file(agent: String, thread: String): File = File(dir(agent), "$thread.json")

    /** The chat the owner has open with this agent; a first one is made when there is none. */
    fun current(agent: String): String =
        App.prefs.getString("chat.current.$agent", null)?.takeIf { it.isNotBlank() } ?: create(agent)

    /** The system panel can start a fresh chat without changing the chat selected in the app. */
    fun create(agent: String, select: Boolean = true): String {
        val id = if (select) System.currentTimeMillis().toString(36) else java.util.UUID.randomUUID().toString()
        if (select) select(agent, id)
        return id
    }

    /** The system panel reserves UUIDs. Chats created by the main app use timestamp IDs. */
    fun isAssistant(thread: String): Boolean = assistantId.matches(thread)

    fun validId(thread: String): Boolean = threadId.matches(thread)

    fun select(agent: String, thread: String) {
        App.prefs.edit().putString("chat.current.$agent", thread).apply()
    }

    /** Notes that the chat was used; its title is the owner's first message. */
    @Synchronized
    fun touch(agent: String, thread: String, title: String) {
        val all = index(agent)
        val entry = all.optJSONObject(thread) ?: JSONObject()
        if (entry.optString("title").isBlank() && title.isNotBlank()) entry.put("title", title.take(80))
        entry.put("time", System.currentTimeMillis())
        all.put(thread, entry)
        indexFile(agent).writeText(all.toString())
    }

    /** `[{id, title, time, current}]`, the most recent first. Empty chats are not listed. */
    @Synchronized
    fun list(agent: String): JSONArray {
        val all = index(agent)
        val open = App.prefs.getString("chat.current.$agent", null)
        val items = all.keys().asSequence().map { id ->
            val entry = all.getJSONObject(id)
            JSONObject().put("id", id).put("title", entry.optString("title")).put("time", entry.optLong("time"))
                .put("current", id == open)
        }.sortedByDescending { it.getLong("time") }.toList()
        return JSONArray(items)
    }

    /** Forgets the chat. If it was the open one, the latest remaining chat opens, or a new one. */
    @Synchronized
    fun delete(agent: String, thread: String): String {
        file(agent, thread).delete()
        val all = index(agent)
        all.remove(thread)
        indexFile(agent).writeText(all.toString())
        if (App.prefs.getString("chat.current.$agent", null) != thread) return current(agent)
        val latest = list(agent).optJSONObject(0)?.getString("id")
        return if (latest != null) latest.also { select(agent, it) } else create(agent)
    }
}
