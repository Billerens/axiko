package app.axiko.agent

import app.axiko.core.Pages
import app.axiko.core.Workspace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What the agents are doing and what they did lately. A turn of a chat is a task; so is every
 * piece of work a turn hands out to run beside it. The owner sees them on the "Задачи" screen:
 * what goes on now, at which step, and what came of the recent ones — without opening each chat.
 *
 * A task that was running when the app was killed is remembered as interrupted, so it can be
 * taken up again instead of being silently lost.
 */
object Tasks {
    class Entry(
        val key: String,
        val agent: String,
        val thread: String,
        val title: String,
        /** The task this one is a part of, for work handed out by another. */
        val parent: String?,
        val started: Long = System.currentTimeMillis(),
    ) {
        @Volatile var step = 0
        @Volatile var note = ""
        @Volatile var state = RUNNING
        @Volatile var ended = 0L
        @Volatile var result = ""

        /**
         * Where the task's time went, by kind ("model", "tool:<name>", "snapshot", "finish"):
         * {n, wall, cpu, app} in milliseconds — wall time, the processor of the agent's own thread,
         * and the processor of the whole app meanwhile (tools that run code do it on other threads).
         */
        val costs = JSONObject()

        /** Measures [work] and adds it to [kind]. */
        fun <T> cost(kind: String, work: () -> T): T {
            val wall = System.currentTimeMillis()
            val cpu = android.os.SystemClock.currentThreadTimeMillis()
            val app = android.os.Process.getElapsedCpuTime()
            try {
                return work()
            } finally {
                add(kind, System.currentTimeMillis() - wall, android.os.SystemClock.currentThreadTimeMillis() - cpu, android.os.Process.getElapsedCpuTime() - app)
            }
        }

        fun add(kind: String, wall: Long, cpu: Long, app: Long) = synchronized(costs) {
            val row = costs.optJSONObject(kind) ?: JSONObject().also { costs.put(kind, it) }
            row.put("n", row.optLong("n") + 1).put("wall", row.optLong("wall") + wall)
                .put("cpu", row.optLong("cpu") + cpu).put("app", row.optLong("app") + app)
        }

        fun json(): JSONObject = JSONObject()
            .put("key", key).put("agent", agent).put("thread", thread).put("title", title).put("parent", parent ?: JSONObject.NULL)
            .put("started", started).put("ended", ended).put("step", step).put("note", note).put("state", state).put("result", result)
            .put("costs", synchronized(costs) { JSONObject(costs.toString()) })
    }

    const val RUNNING = "run"
    const val DONE = "done"
    const val STOPPED = "stopped"
    const val FAILED = "error"
    const val INTERRUPTED = "interrupted"
    private const val KEEP = 40

    private val live = LinkedHashMap<String, Entry>()
    private val recent = ArrayList<Entry>()
    private var loaded = false

    private val file: File get() = File(Workspace.state, "tasks.json")

    private fun entry(saved: JSONObject): Entry = Entry(
        saved.getString("key"), saved.getString("agent"), saved.optString("thread"), saved.optString("title"),
        saved.optString("parent").takeIf { it.isNotEmpty() && it != "null" }, saved.optLong("started"),
    ).apply {
        step = saved.optInt("step")
        note = saved.optString("note")
        state = saved.optString("state", DONE)
        ended = saved.optLong("ended")
        result = saved.optString("result")
        saved.optJSONObject("costs")?.let { stored -> stored.keys().forEach { costs.put(it, stored.get(it)) } }
    }

    /** Reads what was saved. Whatever was still running then did not finish: the app was killed under it. */
    private fun load() {
        if (loaded) return
        loaded = true
        val saved = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        val all = saved.optJSONArray("tasks") ?: return
        for (i in 0 until all.length()) {
            val task = entry(all.getJSONObject(i))
            if (task.state == RUNNING) {
                task.state = INTERRUPTED
                task.ended = saved.optLong("saved", task.started)
            }
            // Parts of an interrupted task are of no use on their own.
            if (task.state == INTERRUPTED && task.parent != null) continue
            recent.add(task)
        }
    }

    private fun save() {
        val all = JSONArray()
        (live.values + recent).forEach { all.put(it.json()) }
        runCatching { file.writeText(JSONObject().put("saved", System.currentTimeMillis()).put("tasks", all).toString()) }
    }

    private fun changed() = Pages.broadcast("tasks", "null")

    @Synchronized
    fun begin(agent: String, thread: String, title: String, parent: Entry? = null): Entry {
        load()
        val task = Entry("t" + System.nanoTime(), agent, thread, title.lineSequence().firstOrNull().orEmpty().take(120), parent?.key)
        // A chat taken up again is no longer an interrupted one.
        if (parent == null) recent.removeAll { it.state == INTERRUPTED && it.agent == agent && it.thread == thread }
        live[task.key] = task
        save()
        changed()
        return task
    }

    fun progress(task: Entry, step: Int, note: String) {
        task.step = step
        task.note = note.take(140)
        changed()
    }

    @Synchronized
    fun end(task: Entry, state: String, result: String) {
        task.state = state
        task.result = result.take(400)
        task.ended = System.currentTimeMillis()
        live.remove(task.key)
        // The parts of a task are kept only while it runs: the task's own result says the rest.
        if (task.parent == null) {
            recent.add(0, task)
            while (recent.size > KEEP) recent.removeAt(recent.size - 1)
        }
        save()
        changed()
    }

    /** Running first (parts right after their task), then the interrupted and the recent ones. */
    @Synchronized
    fun list(): JSONArray {
        load()
        val out = JSONArray()
        live.values.filter { it.parent == null }.forEach { task ->
            out.put(task.json())
            live.values.filter { it.parent == task.key }.forEach { out.put(it.json()) }
        }
        recent.forEach { out.put(it.json()) }
        return out
    }

    @Synchronized
    fun interrupted(): List<Entry> {
        load()
        return recent.filter { it.state == INTERRUPTED }
    }

    @Synchronized
    fun forget(key: String) {
        load()
        if (recent.removeAll { it.key == key || key == "*" && it.state != INTERRUPTED }) save()
        changed()
    }
}
