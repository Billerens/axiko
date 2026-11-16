package app.assi.core

import app.assi.App
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A draft is a set of changes to the workspace that is not the app yet: the agent builds a big
 * rework in it, the owner looks at the result and takes it or throws it away — and until then
 * the app works as it did.
 *
 * It is kept as a layer over the workspace: only the files the draft changed, and a list of the
 * ones it removed. Code that runs "within" a draft sees the workspace through that layer — the
 * agent's file tools, the engine's `require`, the pages served under `/~<id>/…`. Module data
 * (`data/`) has no layer: a draft works with the owner's real data.
 */
object Drafts {
    private val now = ThreadLocal<String?>()

    private val base: File get() = File(App.instance.filesDir, "drafts")

    /** The draft the code on this thread works in, or null for the app itself. */
    val active: String? get() = now.get()

    fun <T> within(id: String?, block: () -> T): T {
        val was = now.get()
        now.set(id?.takeIf { exists(it) })
        try {
            return block()
        } finally {
            now.set(was)
        }
    }

    /** The draft a page address belongs to: `/~d123/modules/x/ui/index.html` → `d123`. */
    fun of(path: String?): String? =
        path?.trimStart('/')?.takeIf { it.startsWith("~") }?.substring(1)?.substringBefore('/')?.takeIf { exists(it) }

    fun files(id: String): File = File(base, "$id/files")

    private fun metaFile(id: String): File = File(base, "$id/draft.json")

    fun exists(id: String): Boolean = Regex("[a-z0-9]+").matches(id) && metaFile(id).isFile

    fun meta(id: String): JSONObject = runCatching { JSONObject(metaFile(id).readText()) }.getOrElse { JSONObject() }

    private fun save(id: String, meta: JSONObject) = metaFile(id).writeText(meta.toString())

    fun create(title: String, agent: String, thread: String, open: String): String {
        val id = "d" + java.lang.Long.toString(System.currentTimeMillis(), 36)
        files(id).mkdirs()
        save(id, JSONObject().put("title", title).put("agent", agent).put("thread", thread).put("open", open).put("created", System.currentTimeMillis()).put("removed", JSONArray()))
        return id
    }

    fun setOpen(id: String, open: String) = save(id, meta(id).put("open", open))

    private fun removed(id: String): MutableList<String> {
        val list = meta(id).optJSONArray("removed") ?: JSONArray()
        return MutableList(list.length()) { list.getString(it) }
    }

    private fun gone(removed: List<String>, rel: String): Boolean = removed.any { rel == it || rel.startsWith("$it/") }

    /** Where a workspace path is in the draft: the draft's own copy when it has one (made now, for writing). */
    fun locate(id: String, rel: String, write: Boolean): File {
        val layer = File(files(id), rel)
        val live = File(Workspace.root, rel)
        val removed = removed(id)
        if (write) {
            if (!layer.exists()) {
                layer.parentFile?.mkdirs()
                if (live.isFile && !gone(removed, rel)) live.copyTo(layer)
            }
            if (removed.removeAll { it == rel || rel.startsWith("$it/") }) save(id, meta(id).put("removed", JSONArray(removed)))
            return layer
        }
        return when {
            layer.isFile -> layer
            gone(removed, rel) -> layer
            live.exists() -> live
            else -> layer
        }
    }

    /** Removes a path in the draft: the app's own file stays until the draft is taken. */
    fun remove(id: String, rel: String): Boolean {
        val layer = File(files(id), rel)
        val existed = locate(id, rel, false).exists()
        layer.deleteRecursively()
        if (File(Workspace.root, rel).exists()) {
            val removed = removed(id)
            if (!gone(removed, rel)) save(id, meta(id).put("removed", JSONArray(removed + rel)))
        }
        return existed
    }

    /** The workspace path of a file inside some draft's layer, or null. */
    fun relative(file: File): String? {
        // Without the app around (unit tests) there are no drafts to speak of.
        val prefix = (runCatching { base.path }.getOrNull() ?: return null) + File.separator
        if (!file.path.startsWith(prefix)) return null
        return file.path.substring(prefix.length).substringAfter(File.separator + "files" + File.separator, "").replace(File.separatorChar, '/').ifEmpty { null }
    }

    /** What the draft changes in the app: `[{path, kind}]`, kind — added, changed or removed. */
    fun changes(id: String): JSONArray {
        val out = JSONArray()
        val root = files(id)
        root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path.replace(File.separatorChar, '/') }.sorted().forEach { rel ->
            val live = File(Workspace.root, rel)
            if (!live.isFile) out.put(JSONObject().put("path", rel).put("kind", "added"))
            else if (!live.readBytes().contentEquals(File(root, rel).readBytes())) out.put(JSONObject().put("path", rel).put("kind", "changed"))
        }
        removed(id).filter { File(Workspace.root, it).exists() }.forEach { out.put(JSONObject().put("path", it).put("kind", "removed")) }
        return out
    }

    /** One file of the draft against the app's, the way git shows it. */
    fun diff(id: String, rel: String): String {
        fun text(file: File): String? = if (file.isFile) file.readText() else null
        val live = text(File(Workspace.root, rel))
        return Workspace.unified(live, if (gone(removed(id), rel)) null else text(File(files(id), rel)) ?: live)
    }

    /** Drafts waiting for the owner's decision, the newest first; [thread] narrows them to one chat. */
    fun list(agent: String? = null, thread: String? = null): JSONArray {
        val all = base.listFiles().orEmpty().filter { it.isDirectory && exists(it.name) }.map { it.name to meta(it.name) }
            .filter { (_, meta) -> (agent == null || meta.optString("agent") == agent) && (thread == null || meta.optString("thread") == thread) }
            .sortedByDescending { it.second.optLong("created") }
        return JSONArray(all.map { (id, meta) -> JSONObject(meta.toString()).put("id", id).put("files", changes(id)) })
    }

    /** The draft becomes the app. What the app was is kept in a snapshot. */
    fun accept(id: String) {
        if (!exists(id)) error("Нет такого черновика")
        Workspace.snapshot("Перед принятием черновика «${meta(id).optString("title")}»")
        val root = files(id)
        removed(id).forEach { File(Workspace.root, it).deleteRecursively() }
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            val target = File(Workspace.root, file.relativeTo(root).path)
            target.parentFile?.mkdirs()
            file.copyTo(target, overwrite = true)
        }
        discard(id)
    }

    fun discard(id: String) {
        if (Regex("[a-z0-9]+").matches(id)) File(base, id).deleteRecursively()
    }
}
