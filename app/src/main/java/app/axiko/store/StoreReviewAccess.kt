package app.axiko.store

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

/** A persisted, plan-bound tool boundary for audit conversations; not a package runtime sandbox. */
class StoreReviewAccess(private val directory: File) {
    companion object {
        val TOOLS = listOf("store_plan", "store_read", "store_resolve", "store_review", "store_apply", "ask_owner")
        fun reviewable(path: String, executable: Boolean): Boolean = executable || path in setOf("package.json", "README.md", "LICENSE") ||
            path.substringAfterLast('.', "").lowercase() in setOf("js", "mjs", "cjs", "html", "htm", "css", "json", "md", "txt", "svg", "xml", "sql")

        // Conversation clips ordinary tool outputs at 30,000 characters. Never record bytes
        // the model cannot receive: keep one copy and bound the serialized, escaped JSON.
        const val READ_CHUNK = 12000
        fun readView(read: JSONObject): JSONObject = JSONObject(read.toString()).also {
            it.remove("text")
            require(it.getString("content").length <= READ_CHUNK) { "Фрагмент слишком велик для полного просмотра; уменьшите limit." }
            // Escaping can expand one source character to six JSON characters.
            // Shrink before recording coverage, and advertise the actual next offset.
            while (it.toString().length >= 28000 && it.getString("content").length > 1) {
                val content = it.getString("content").take(it.getString("content").length / 2)
                val end = it.getInt("offset") + content.length
                it.put("content", content).put("nextOffset", end).put("eof", end == it.getInt("totalLength"))
            }
            require(it.toString().length < 28000) { "Метаданные фрагмента слишком велики для полного просмотра." }
        }

        fun planView(plan: JSONObject, args: JSONObject, coverage: JSONObject? = null): JSONObject {
            val out = JSONObject()
            for (key in listOf("id", "digest", "status", "sourceId", "packageId", "title", "kind", "version", "installationKey", "target", "entryPath", "artifactSha256", "runtimeProfile", "capabilitiesEnforced", "sourceTitle", "localRollback")) {
                if (plan.has(key)) out.put(key, plan.get(key))
            }
            out.put("packageMetadataPath", "package.json").put("readChunkLimit", READ_CHUNK)
            out.put("changeCount", plan.optJSONArray("changes")?.length() ?: 0)
            out.put("dependencyCount", plan.optJSONArray("dependencies")?.length() ?: 0)
            out.put("blockers", plan.optJSONArray("blockers") ?: JSONArray())
            val reviewed = coverage?.optJSONArray("filesReviewed")?.let { list -> (0 until list.length()).map { list.getString(it) }.toSet() }.orEmpty()
            fun page(field: String, offsetKey: String, nextKey: String, size: Int) {
                val all = plan.optJSONArray(field) ?: JSONArray()
                val offset = args.optInt(offsetKey, 0)
                require(offset in 0..all.length()) { "Смещение $offsetKey за пределами списка." }
                val end = minOf(all.length(), offset + size)
                val items = JSONArray()
                for (i in offset until end) {
                    val item = JSONObject(all.getJSONObject(i).toString())
                    if (field == "reviewFiles") item.put("reviewed", item.optString("path") in reviewed)
                    if (field == "conflicts") item.put("readPath", if (item.getString("path").startsWith("assets/")) item.getString("path") else "code/" + item.getString("path"))
                    items.put(item)
                }
                out.put(field, items).put(field + "Total", all.length()).put(nextKey, if (end < all.length()) end else JSONObject.NULL)
            }
            page("reviewFiles", "filesOffset", "nextFilesOffset", args.optInt("filesLimit", 24).coerceIn(1, 24))
            page("conflicts", "conflictsOffset", "nextConflictsOffset", 12)
            page("findings", "findingsOffset", "nextFindingsOffset", 12)
            coverage?.let { out.put("auditCoverage", JSONObject().put("complete", it.getBoolean("complete"))
                .put("filesReviewedCount", reviewed.size).put("unreviewedCount", it.getJSONArray("unreviewedFiles").length())) }
            require(out.toString().length < 28000) { "Страница плана слишком велика; уменьшите filesLimit." }
            return out
        }
    }

    private fun file(agent: String, thread: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest("$agent/$thread".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$key.json")
    }

    private fun write(file: File, data: JSONObject) {
        directory.mkdirs()
        val temp = File(directory, file.name + ".tmp")
        temp.writeText(data.toString())
        try { Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp.toPath(), file.toPath(), REPLACE_EXISTING) }
    }

    @Synchronized fun session(agent: String, thread: String): JSONObject? {
        if (thread.isBlank()) return null
        val file = file(agent, thread)
        return if (file.isFile) JSONObject(file.readText()) else null
    }

    @Synchronized fun create(agent: String, thread: String, plan: JSONObject, consent: Boolean): JSONObject {
        require(consent) { "Для установки нужно разрешение на запуск кода с возможностями Axiko." }
        val state = JSONObject().put("agent", agent).put("thread", thread).put("planId", plan.getString("id"))
            .put("digest", plan.getString("digest")).put("artifactSha256", plan.getString("artifactSha256"))
            .put("consent", true).put("createdAt", System.currentTimeMillis()).put("coverage", JSONObject())
        write(file(agent, thread), state)
        return state
    }

    @Synchronized fun forPlan(planId: String): JSONObject? = directory.listFiles().orEmpty()
        .filter { it.isFile && it.extension == "json" }.mapNotNull { runCatching { JSONObject(it.readText()) }.getOrNull() }
        .filter { it.optString("planId") == planId }.maxByOrNull { it.optLong("createdAt") }

    @Synchronized fun bind(agent: String, thread: String, plan: JSONObject): JSONObject {
        val state = requireNotNull(session(agent, thread)) { "Проверка магазина не связана с этим разговором." }
        require(state.getString("planId") == plan.getString("id")) { "В этом разговоре разрешён только выбранный пакет." }
        require(state.getString("artifactSha256") == plan.getString("artifactSha256")) { "Архив изменился после начала проверки." }
        if (state.getString("digest") != plan.getString("digest")) {
            state.put("digest", plan.getString("digest")).put("coverage", JSONObject())
            write(file(agent, thread), state)
        }
        return state
    }

    @Synchronized fun record(agent: String, thread: String, plan: JSONObject, read: JSONObject) {
        val state = bind(agent, thread, plan)
        if (read.optString("side", "candidate") != "candidate") return
        val path = read.getString("path")
        val start = read.getInt("offset"); val end = read.getInt("nextOffset"); val length = read.getInt("totalLength")
        require(start >= 0 && end >= start && end <= length) { "Некорректная граница прочитанного фрагмента." }
        val hash = read.getString("sha256")
        val coverage = state.getJSONObject("coverage")
        val old = coverage.optJSONObject(path)?.takeIf { it.optString("sha256") == hash && it.optInt("length") == length }
        val intervals = mutableListOf<Pair<Int, Int>>()
        old?.optJSONArray("ranges")?.let { list -> for (i in 0 until list.length()) intervals.add(list.getJSONArray(i).let { it.getInt(0) to it.getInt(1) }) }
        intervals.add(start to end)
        val merged = mutableListOf<Pair<Int, Int>>()
        intervals.sortedBy { it.first }.forEach { range ->
            val previous = merged.lastOrNull()
            if (previous != null && range.first <= previous.second) merged[merged.lastIndex] = previous.first to maxOf(previous.second, range.second)
            else merged.add(range)
        }
        coverage.put(path, JSONObject().put("sha256", hash).put("length", length)
            .put("ranges", JSONArray(merged.map { JSONArray(listOf(it.first, it.second)) })))
        write(file(agent, thread), state)
    }

    @Synchronized fun coverage(agent: String, thread: String, plan: JSONObject): JSONObject {
        val state = bind(agent, thread, plan)
        val coverage = state.getJSONObject("coverage")
        val files = plan.optJSONArray("reviewFiles") ?: error("В плане отсутствует опись файлов для проверки.")
        val read = JSONArray(); val missing = JSONArray()
        for (i in 0 until files.length()) {
            val item = files.getJSONObject(i); val path = item.getString("path")
            if (!reviewable(path, item.optBoolean("executable"))) continue
            val record = coverage.optJSONObject(path); val ranges = record?.optJSONArray("ranges")
            val full = record != null && record.optString("sha256") == item.getString("sha256") && ranges?.length() == 1 &&
                ranges.getJSONArray(0).getInt(0) == 0 && ranges.getJSONArray(0).getInt(1) == record.getInt("length")
            if (full) read.put(path) else missing.put(path)
        }
        return JSONObject().put("filesReviewed", read).put("unreviewedFiles", missing).put("complete", missing.length() == 0)
    }

    @Synchronized fun requireApply(agent: String, thread: String, plan: JSONObject, review: JSONObject) {
        val state = bind(agent, thread, plan)
        require(state.optBoolean("consent")) { "Установка не разрешена владельцем." }
        require(plan.getString("status") == "ready") { "Сначала разрешите конфликты обновления." }
        require(coverage(agent, thread, plan).getBoolean("complete")) { "Прочитаны не все исходники подготовленного пакета." }
        require(review.optString("status") == "pass" && review.optString("planDigest", review.optString("digest")) == plan.getString("digest")) {
            "Нужен завершённый отчёт pass для текущей версии плана."
        }
    }
}
