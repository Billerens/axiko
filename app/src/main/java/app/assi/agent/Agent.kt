package app.assi.agent

import app.assi.App
import app.assi.core.Icons
import app.assi.core.Workspace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The agents of the app. `assi` is the main one: it rewrites the workspace. Any folder
 * `agents/<id>/` is one more agent with its own prompt, tools and model, which the main agent
 * (or the owner) creates for a particular job. The loop lives in the shell, so no agent can
 * edit itself into silence.
 */
object Agent {
    const val MAIN = "assi"
    private const val MAX_DEPTH = 3

    /** What an agent is: read from the workspace every time, so edits apply at once. */
    class Spec(
        val id: String,
        val title: String,
        val description: String,
        val icon: String,
        /** Tool names it may use (`mail_*` matches a prefix); null means every tool. */
        val tools: List<String>?,
        /** Whether its prompt includes the description of the app's API. */
        val api: Boolean,
    )

    private val chats = HashMap<String, Conversation>()

    fun exists(id: String): Boolean = id == MAIN || File(Workspace.root, "agents/$id").isDirectory

    /** What the owner calls the assistant: in the chat, in its prompt, and aloud as the wake word. */
    val name: String get() = App.prefs.getString("assistant.name", null)?.trim().orEmpty().ifEmpty { "Axiko" }

    fun rename(value: String) = App.prefs.edit().putString("assistant.name", value.trim()).apply()

    fun spec(id: String): Spec {
        if (id == MAIN) return Spec(MAIN, name, "Основной агент: меняет и достраивает приложение", "spark", null, true)
        val meta = runCatching { JSONObject(File(Workspace.root, "agents/$id/agent.json").readText()) }
            .getOrElse { JSONObject() }
        val tools = meta.optJSONArray("tools")?.let { list -> List(list.length()) { list.getString(it) } }
        return Spec(
            id,
            meta.optString("title").ifBlank { id },
            meta.optString("description"),
            meta.optString("icon").ifBlank { "bot" },
            tools,
            meta.optBoolean("api", true),
        )
    }

    /** Every agent with the model it runs on. */
    fun list(): JSONArray {
        val ids = listOf(MAIN) + File(Workspace.root, "agents").listFiles().orEmpty()
            .filter { it.isDirectory }.map { it.name }.sorted()
        val roles = Models.roles()
        val out = JSONArray()
        for (id in ids) {
            val spec = spec(id)
            val role = Models.roleOf(id)
            val entry = JSONObject().put("id", id).put("title", spec.title).put("description", spec.description)
                .put("role", role)
                .put("model", roles.optJSONObject(role)?.optString("model").orEmpty())
                .put("busy", synchronized(chats) { chats.any { it.key.startsWith("$id/") && it.value.busy } })
            val home = File(Workspace.root, if (id == MAIN) "agent" else "agents/$id")
            Icons.describe(entry, "agent", id, home, spec.icon, if (id == MAIN) "spark" else "bot")
            out.put(entry)
        }
        return out
    }

    /** A chat of the agent with the owner; without [thread] it is the one open now. */
    fun chat(id: String, thread: String? = null): Conversation {
        require(exists(id)) { "Нет агента $id" }
        val threadId = thread?.takeIf { it.isNotBlank() } ?: Threads.current(id)
        return synchronized(chats) {
            chats.getOrPut("$id/$threadId") { Conversation(id, lasting = true, parent = null, thread = threadId) }
        }
    }

    /** Forgets a chat; returns the id of the chat that is open afterwards. */
    fun forget(id: String, thread: String): String {
        synchronized(chats) { chats.remove("$id/$thread") }?.stop()
        return Threads.delete(id, thread)
    }

    /** Gives the agent one task in a fresh conversation and waits for its final answer. */
    fun run(id: String, task: String, parent: Conversation? = null): String {
        require(exists(id)) { "Нет агента $id" }
        val depth = (parent?.depth ?: 0) + 1
        require(depth <= MAX_DEPTH) { "Слишком глубокая цепочка агентов" }
        return Conversation(id, lasting = false, parent = parent, label = parent?.let { spec(id).title }).once(task)
    }

    fun mayDelegate(conversation: Conversation): Boolean = conversation.depth < MAX_DEPTH
}
