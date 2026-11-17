package app.assi.store

import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A bounded response, including the final HTTPS address after redirects. */
data class StoreFetch(
    val bytes: ByteArray,
    val status: Int,
    val etag: String?,
    val lastModified: String?,
    val finalUrl: String,
)

/** Injection is for isolated tests; the application always constructs [StoreHttpTransport]. */
fun interface StoreTransport {
    fun get(url: String, maxBytes: Int, etag: String?, lastModified: String?): StoreFetch
}

/**
 * A mod that cannot be laid over its target as its author made it: the target is another version, or
 * the files the mod touches are not what the author started from. [reasons] say which, in the owner's words.
 */
class StoreModNeedsCarrying(message: String, val reasons: List<String>) : IllegalArgumentException(message)

/** No credentials, insecure TLS override or cleartext fallback, including on redirects. */
class StoreHttpTransport(client: OkHttpClient = OkHttpClient()) : StoreTransport {
    private val client = client.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).build()

    override fun get(url: String, maxBytes: Int, etag: String?, lastModified: String?): StoreFetch {
        require(maxBytes in 1..MAX_ARCHIVE_BYTES) { "Недопустимый предел загрузки." }
        var current = StoreFormat.https(url)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        val visited = HashSet<String>()
        repeat(5) { hop ->
            require(visited.add(current)) { "Источник перенаправляет запрос по кругу." }
            val request = Request.Builder().url(current).header("User-Agent", "Axiko-Store/1")
                .header("Accept", "application/json, application/zip, application/octet-stream")
            // Conditional validators belong to the original origin; no header is forwarded to a new one.
            if (sameOrigin(url, current)) {
                safeHeader(etag)?.let { request.header("If-None-Match", it) }
                safeHeader(lastModified)?.let { request.header("If-Modified-Since", it) }
            }
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw IOException("Истекло время загрузки источника.")
            val call = client.newCall(request.build())
            call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
            call.execute().use { response ->
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    require(hop < 4) { "Слишком много перенаправлений источника." }
                    val location = response.header("Location") ?: error("Перенаправление без адреса.")
                    current = StoreFormat.https(URI(current).resolve(location).toString())
                } else {
                    if (response.code == 304) return StoreFetch(ByteArray(0), 304,
                        safeHeader(response.header("ETag")), safeHeader(response.header("Last-Modified")), current)
                    if (response.code != 200) throw IOException("Источник ответил HTTP ${response.code}.")
                    val body = response.body ?: throw IOException("Источник вернул пустой ответ.")
                    require(body.contentLength() <= maxBytes) { "Ответ превышает допустимый размер $maxBytes байт." }
                    val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= maxBytes) { "Загрузка превысила допустимый размер $maxBytes байт." }
                            out.write(buffer, 0, count)
                        }
                    }
                    return StoreFetch(out.toByteArray(), 200, safeHeader(response.header("ETag")),
                        safeHeader(response.header("Last-Modified")), current)
                }
            }
        }
        error("Не удалось загрузить источник.")
    }

    private fun sameOrigin(a: String, b: String): Boolean {
        val first = a.toHttpUrl(); val second = b.toHttpUrl()
        return first.scheme == second.scheme && first.host == second.host && first.port == second.port
    }

    companion object {
        const val MAX_ARCHIVE_BYTES = 5 * 1024 * 1024
        private fun safeHeader(value: String?) = value?.takeIf { it.length <= 2048 && '\r' !in it && '\n' !in it }
    }
}

internal object StoreDisk {
    fun copy(value: JSONObject) = JSONObject(value.toString())
    fun read(file: File): JSONObject = JSONObject(file.readText(Charsets.UTF_8))

    fun write(file: File, value: JSONObject) {
        require(file.parentFile.mkdirs() || file.parentFile.isDirectory) { "Не удалось открыть состояние магазина." }
        val temporary = File(file.parentFile, ".${file.name}-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(value.toString().toByteArray(Charsets.UTF_8)); output.fd.sync()
            }
            try { Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING) }
        } finally { temporary.delete() }
    }
}

/** Repository identity and validated last-known catalogs. Reading the catalog performs no network IO. */
class StoreSources(private val stateDir: File, private val transport: StoreTransport) {
    private val lock = Any()
    private val registry = File(stateDir, "sources.json")
    private val catalogs = File(stateDir, "catalogs")
    /** Pictures of listings, named by their hash: one file serves every source and version that shows it. */
    private val pictures = File(stateDir, "media")
    /** Parsed catalogs by source, for as long as the head they were read by is the current one. */
    private val memory = HashMap<String, Pair<String, JSONObject>>()

    fun list(): JSONArray = synchronized(lock) {
        val out = JSONArray()
        records().forEach { out.put(publicRecord(it)) }
        out
    }

    fun source(id: String, requireEnabled: Boolean = false): JSONObject = synchronized(lock) {
        val item = records().firstOrNull { it.getString("id") == id }
            ?: throw IllegalArgumentException("Источник не найден.")
        require(!requireEnabled || item.optBoolean("enabled", true)) { "Источник выключен. Включите его перед установкой." }
        StoreDisk.copy(item)
    }

    /**
     * The validated catalog of a source as one list: every page its head names, read from disk and
     * checked again. The same object is handed out until the catalog changes; callers copy what they change.
     */
    fun cached(id: String): JSONObject = synchronized(lock) {
        val record = source(id)
        val known = record.optString("indexSha256")
        memory[id]?.takeIf { it.first == known }?.let { return@synchronized it.second }
        val file = cacheFile(id)
        require(file.isFile) { "Каталог ещё не загружен. Обновите источник." }
        val bytes = readLimited(file, MAX_METADATA_BYTES)
        require(StoreFormat.sha256(bytes) == known) { "Кэш источника не соответствует реестру. Обновите источник." }
        val head = StoreFormat.index(bytes)
        val packages = JSONArray(); val ids = HashSet<String>()
        val pages = head.getJSONArray("pages")
        for (i in 0 until pages.length()) {
            val page = pages.getJSONObject(i)
            val stored = pageFile(id, page.getString("sha256"))
            require(stored.isFile) { "Страница каталога не загружена. Обновите источник." }
            val raw = readLimited(stored, MAX_METADATA_BYTES)
            require(StoreFormat.sha256(raw) == page.getString("sha256")) { "Страница каталога повреждена. Обновите источник." }
            val list = StoreFormat.page(raw).getJSONArray("packages")
            for (n in 0 until list.length()) {
                val entry = list.getJSONObject(n)
                require(ids.add(entry.getString("id"))) { "Повторяющийся ID пакета в каталоге." }
                packages.put(entry)
            }
        }
        JSONObject().put("schemaVersion", 1).put("repository", head.getJSONObject("repository")).put("packages", packages)
            .also { memory[id] = known to it }
    }

    /** A catalog as it came: the head and the pages that had to be fetched, all checked. Nothing is on disk yet. */
    private class Loaded(val head: JSONObject, val packages: List<JSONObject>, val fresh: Map<String, ByteArray>)

    private fun load(indexUrl: String, headBytes: ByteArray, id: String?): Loaded {
        val head = StoreFormat.index(headBytes)
        val pages = head.getJSONArray("pages")
        val fresh = LinkedHashMap<String, ByteArray>(); val packages = ArrayList<JSONObject>(); val ids = HashSet<String>()
        for (i in 0 until pages.length()) {
            val page = pages.getJSONObject(i); val sha = page.getString("sha256"); val name = page.getString("path")
            // A page that did not change is not downloaded again: its file is named by the hash the head pins.
            val kept = id?.let { pageFile(it, sha) }?.takeIf { it.isFile }?.let { readLimited(it, MAX_METADATA_BYTES) }?.takeIf { StoreFormat.sha256(it) == sha }
            val raw = kept ?: fresh[sha] ?: run {
                val response = fetch(resolve(indexUrl, name), page.getInt("size"))
                require(response.status == 200) { "Страница каталога $name не загружена." }
                require(response.bytes.size == page.getInt("size") && StoreFormat.sha256(response.bytes) == sha) { "Страница каталога $name не совпала с index.json." }
                response.bytes.also { fresh[sha] = it }
            }
            val list = StoreFormat.page(raw).getJSONArray("packages")
            require(list.length() == page.getInt("count")) { "Число пакетов на странице $name не совпало с index.json." }
            for (n in 0 until list.length()) {
                val entry = list.getJSONObject(n)
                require(ids.add(entry.getString("id"))) { "Повторяющийся ID пакета: ${entry.getString("id")}." }
                packages.add(entry)
            }
        }
        return Loaded(head, packages, fresh)
    }

    /** Puts a loaded catalog in place of the old one: new pages first, the head last, pages nobody names any more removed. */
    private fun store(id: String, headBytes: ByteArray, loaded: Loaded) {
        loaded.fresh.forEach { (sha, raw) -> writeAtomic(pageFile(id, sha), raw) }
        saveCache(id, headBytes)
        memory.remove(id)
        val named = (0 until loaded.head.getJSONArray("pages").length()).map { loaded.head.getJSONArray("pages").getJSONObject(it).getString("sha256") + ".json" }.toSet()
        pageDir(id).listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json") && it.name !in named }.forEach { it.delete() }
    }

    /** A file published next to the index. A path cannot leave the folder of the index: it has no `..`, and no host of its own. */
    private fun resolve(indexUrl: String, path: String): String {
        val base = indexUrl.toHttpUrl(); val builder = base.newBuilder().query(null)
        builder.removePathSegment(base.pathSize - 1)
        StoreFormat.path(path).split('/').forEach { builder.addPathSegment(it) }
        return builder.build().toString()
    }

    /**
     * What is read when the owner opens a package: fetched on demand and kept by its hash, so an
     * unchanged description is not downloaded twice. A package without details gives just its id and version.
     */
    fun details(sourceId: String, packageId: String): JSONObject {
        val source = source(sourceId)
        val entry = packageEntry(sourceId, packageId)
        val ref = entry.optJSONObject("details")
            ?: return JSONObject().put("schemaVersion", 1).put("id", packageId).put("version", entry.getString("version"))
        val sha = ref.getString("sha256")
        val stored = File(File(pageDir(sourceId), "details"), "$sha.json")
        val kept = stored.takeIf { it.isFile }?.let { readLimited(it, StoreFormat.MAX_DETAILS) }?.takeIf { StoreFormat.sha256(it) == sha }
        val raw = kept ?: run {
            val response = fetch(resolve(source.getString("indexUrl"), ref.getString("path")), ref.getInt("size"))
            require(response.status == 200) { "Описание пакета не загружено." }
            require(response.bytes.size == ref.getInt("size") && StoreFormat.sha256(response.bytes) == sha) { "Описание пакета не совпало с каталогом." }
            response.bytes
        }
        val details = StoreFormat.details(raw)
        require(details.getString("id") == packageId && details.getString("version") == entry.getString("version")) { "Описание относится к другому пакету или версии." }
        if (kept == null) writeAtomic(stored, raw)
        return details
    }

    /**
     * A picture of a listing by the reference a checked card or details file gave: never more bytes
     * than declared, the declared hash, and the declared type by what the bytes really are.
     */
    fun picture(sourceId: String, ref: JSONObject): ByteArray {
        val source = source(sourceId)
        val sha = StoreFormat.hash(ref.getString("sha256")); val size = ref.getInt("size")
        val stored = File(pictures, sha)
        stored.takeIf { it.isFile && it.length() == size.toLong() }?.let { readLimited(it, size) }?.takeIf { StoreFormat.sha256(it) == sha }?.let { return it }
        val response = fetch(resolve(source.getString("indexUrl"), ref.getString("path")), size)
        require(response.status == 200) { "Картинка не загружена." }
        require(response.bytes.size == size && StoreFormat.sha256(response.bytes) == sha) { "Картинка не совпала с каталогом." }
        require(StoreFormat.pictureType(response.bytes) == ref.getString("type")) { "Файл не является картинкой заявленного типа." }
        writeAtomic(stored, response.bytes)
        return response.bytes
    }

    fun packageEntry(sourceId: String, packageId: String): JSONObject {
        source(sourceId, true)
        val packages = cached(sourceId).getJSONArray("packages")
        for (index in 0 until packages.length()) {
            val item = packages.getJSONObject(index)
            if (item.getString("id") == packageId) return StoreDisk.copy(item)
        }
        throw IllegalArgumentException("Пакет отсутствует в index.json этого источника.")
    }

    fun add(input: String): JSONObject {
        val indexUrl = resolveIndex(input)
        val response = fetch(indexUrl, MAX_METADATA_BYTES)
        require(response.status == 200) { "Новый источник не вернул index.json." }
        val known = synchronized(lock) { records().firstOrNull { it.getString("indexUrl") == indexUrl }?.getString("id") }
        val loaded = load(indexUrl, response.bytes, known)
        val repository = loaded.head.getJSONObject("repository")
        return synchronized(lock) {
            val all = records()
            val existing = all.firstOrNull { it.getString("indexUrl") == indexUrl }
            if (existing != null) {
                require(existing.getString("repositoryId") == repository.getString("id")) { "По этому адресу изменился идентификатор репозитория. Старое доверие не перенесено." }
                validateContinuity(existing, loaded.packages)
                val updated = updated(existing, loaded.head, response)
                store(updated.getString("id"), response.bytes, loaded)
                save(all.map { if (it.getString("id") == updated.getString("id")) updated else it })
                publicRecord(updated)
            } else {
                require(all.size < 64) { "Можно добавить не более 64 источников." }
                val id = StoreFormat.sha256((indexUrl + "\n" + repository.getString("id")).toByteArray()).take(32)
                val created = JSONObject().put("id", id).put("indexUrl", indexUrl)
                    .put("repositoryId", repository.getString("id")).put("enabled", true)
                    .put("createdAt", System.currentTimeMillis()).put("instanceId", UUID.randomUUID().toString())
                val item = updated(created, loaded.head, response)
                store(id, response.bytes, loaded); save(all + item)
                publicRecord(item)
            }
        }
    }

    private val factoryMarker = File(stateDir, "factory-source.json")

    /** Whether the factory source was already offered: connected once, or connected and then removed by the owner. */
    fun factorySettled(): Boolean = factoryMarker.isFile

    /**
     * Connects the source every install starts with, once. It is fetched and checked like a source added by
     * hand, so without a network nothing is recorded and the attempt is made again later. After the first
     * success it is the owner's: removed, it stays removed. Returns the source when it has just been connected.
     */
    fun ensureFactory(url: String): JSONObject? {
        if (factorySettled()) return null
        val added = add(url)
        synchronized(lock) {
            StoreDisk.write(factoryMarker, JSONObject().put("indexUrl", added.getString("indexUrl")).put("sourceId", added.getString("id")).put("at", System.currentTimeMillis()))
        }
        return added
    }

    fun refresh(id: String): JSONObject {
        val before = source(id)
        try {
            // A cache that cannot be read (damaged, or written by an older format) is not vouched for by a 304.
            val readable = runCatching { cached(id) }.isSuccess
            val response = fetch(before.getString("indexUrl"), MAX_METADATA_BYTES,
                before.optString("etag").ifBlank { null }.takeIf { readable }, before.optString("lastModified").ifBlank { null }.takeIf { readable })
            val loaded = if (response.status == 304) null else load(before.getString("indexUrl"), response.bytes, id)
            return synchronized(lock) {
                val current = source(id)
                require(current.getString("instanceId") == before.getString("instanceId")) { "Источник был изменён во время загрузки." }
                val result: JSONObject
                if (loaded == null) {
                    cached(id) // A 304 cannot create an empty catalog.
                    result = current.put("lastChecked", System.currentTimeMillis()).put("lastSuccess", System.currentTimeMillis()).put("error", "")
                } else {
                    require(loaded.head.getJSONObject("repository").getString("id") == current.getString("repositoryId")) { "Источник изменил идентификатор. Кэш и установленный код сохранены." }
                    validateContinuity(current, loaded.packages)
                    result = updated(current, loaded.head, response)
                    store(id, response.bytes, loaded)
                }
                save(records().map { if (it.getString("id") == id) result else it })
                publicRecord(result)
            }
        } catch (error: Exception) {
            synchronized(lock) {
                val all = records()
                val current = all.firstOrNull { it.getString("id") == id && it.getString("instanceId") == before.getString("instanceId") }
                if (current != null) {
                    current.put("lastChecked", System.currentTimeMillis()).put("error", (error.message ?: "Источник недоступен.").take(1200))
                    save(all)
                }
            }
            throw error
        }
    }

    fun enable(id: String, enabled: Boolean): JSONObject = synchronized(lock) {
        val all = records(); val item = all.firstOrNull { it.getString("id") == id }
            ?: throw IllegalArgumentException("Источник не найден.")
        item.put("enabled", enabled); save(all); publicRecord(item)
    }

    /**
     * What went wrong on the source's side with one package: an archive that does not match the
     * index, a manifest that disagrees with it. Shown with the source; the package is not banned for it.
     */
    fun note(id: String, packageId: String, version: String, message: String) = synchronized(lock) {
        val all = records(); val item = all.firstOrNull { it.getString("id") == id } ?: return@synchronized
        val kept = (item.optJSONArray("problems") ?: JSONArray()).let { list -> (0 until list.length()).map { list.getJSONObject(it) } }
            .filterNot { it.optString("packageId") == packageId }
        val problem = JSONObject().put("packageId", packageId).put("version", version).put("message", message.take(600)).put("at", System.currentTimeMillis())
        item.put("problems", JSONArray((listOf(problem) + kept).take(10))); save(all)
    }

    /** The package was fetched and verified again: an earlier complaint about it is over. */
    fun settle(id: String, packageId: String) = synchronized(lock) {
        val all = records(); val item = all.firstOrNull { it.getString("id") == id } ?: return@synchronized
        val list = item.optJSONArray("problems") ?: return@synchronized
        val kept = (0 until list.length()).map { list.getJSONObject(it) }.filterNot { it.optString("packageId") == packageId }
        if (kept.size != list.length()) { item.put("problems", JSONArray(kept)); save(all) }
    }

    fun remove(id: String): JSONObject = synchronized(lock) {
        val all = records(); require(all.any { it.getString("id") == id }) { "Источник не найден." }
        save(all.filterNot { it.getString("id") == id })
        // An installed package never depends on the continued existence of this cached index.
        cacheFile(id).delete(); pageDir(id).deleteRecursively(); memory.remove(id)
        JSONObject().put("removed", true).put("id", id).put("installedKept", true)
    }

    fun fetch(url: String, limit: Int, etag: String? = null, lastModified: String? = null): StoreFetch {
        val safe = StoreFormat.https(url)
        val result = transport.get(safe, limit, etag, lastModified)
        StoreFormat.https(result.finalUrl)
        require(result.status == 200 || result.status == 304) { "Источник ответил HTTP ${result.status}." }
        require(result.bytes.size <= limit) { "Ответ превышает допустимый размер." }
        require(result.status != 304 || result.bytes.isEmpty()) { "Некорректный ответ HTTP 304." }
        return result
    }

    private fun resolveIndex(input: String): String {
        val safe = StoreFormat.https(input.trim())
        val uri = safe.toHttpUrl()
        if (uri.host.equals("github.com", true)) {
            require(uri.encodedQuery == null) { "Для GitHub укажите адрес репозитория без параметров." }
            val parts = uri.encodedPath.trim('/').split('/')
            if (parts.size == 2 && parts.all { Regex("[A-Za-z0-9_.-]+").matches(it) }) {
                val owner = parts[0]; val repo = parts[1].removeSuffix(".git")
                require(owner.isNotBlank() && repo.isNotBlank()) { "Некорректный адрес GitHub-репозитория." }
                val metaResponse = fetch("https://api.github.com/repos/$owner/$repo", MAX_METADATA_BYTES)
                require(metaResponse.status == 200) { "Не удалось определить ветку GitHub-репозитория." }
                val meta = JSONObject(metaResponse.bytes.toString(Charsets.UTF_8))
                val branch = meta.getString("default_branch")
                require(branch.length in 1..240 && !branch.contains('\\') && branch.split('/').none { it.isEmpty() || it == "." || it == ".." } && branch.none { it.code <= 32 || it in "?#%" }) { "Некорректная ветка GitHub-репозитория." }
                return normalizeUrl("https://raw.githubusercontent.com/$owner/$repo/$branch/index.json")
            }
            if (parts.size >= 5 && parts[2] == "blob" && parts.last() == "index.json") {
                require(parts.take(2).all { Regex("[A-Za-z0-9_.-]+").matches(it) }) { "Некорректный адрес GitHub." }
                return normalizeUrl("https://raw.githubusercontent.com/" + parts.filterIndexed { i, _ -> i != 2 }.joinToString("/"))
            }
            throw IllegalArgumentException("Укажите GitHub-репозиторий или прямой HTTPS-адрес index.json.")
        }
        require(uri.pathSegments.lastOrNull() == "index.json") { "Адрес источника должен указывать на index.json." }
        return normalizeUrl(safe)
    }

    private fun normalizeUrl(value: String): String {
        return StoreFormat.https(value).toHttpUrl().toString()
    }

    private fun validateContinuity(source: JSONObject, next: List<JSONObject>) {
        // No readable catalog to continue from: a first load, or a cache written by an older format.
        val old = runCatching { cached(source.getString("id")).getJSONArray("packages") }.getOrNull() ?: return
        val previous = HashMap<String, JSONObject>()
        for (i in 0 until old.length()) previous[old.getJSONObject(i).getString("id")] = old.getJSONObject(i)
        for (entry in next) {
            val was = previous[entry.getString("id")] ?: continue
            require(was.getString("publisher") == entry.getString("publisher")) { "У пакета ${entry.getString("id")} изменился издатель. Обновление источника остановлено." }
            if (was.getString("version") == entry.getString("version")) {
                require(was.getJSONObject("archive").getString("sha256") == entry.getJSONObject("archive").getString("sha256")) { "Архив уже опубликованной версии ${entry.getString("id")} изменился. Старый каталог сохранён." }
            }
        }
    }

    private fun updated(source: JSONObject, head: JSONObject, response: StoreFetch): JSONObject {
        val repository = head.getJSONObject("repository")
        return StoreDisk.copy(source).put("title", repository.getString("title")).put("homepage", repository.getString("homepage"))
            .put("description", repository.optString("description"))
            .put("lastChecked", System.currentTimeMillis()).put("lastSuccess", System.currentTimeMillis()).put("error", "")
            .put("packageCount", head.getInt("packageCount")).put("indexSha256", StoreFormat.sha256(response.bytes))
            .put("finalUrl", response.finalUrl).put("etag", response.etag ?: "").put("lastModified", response.lastModified ?: "")
    }

    private fun publicRecord(source: JSONObject) = StoreDisk.copy(source).apply { remove("etag"); remove("lastModified") }
    private fun cacheFile(id: String): File {
        require(Regex("[a-f0-9]{32}").matches(id)) { "Некорректный идентификатор источника." }
        return File(catalogs, "$id.json")
    }

    private fun pageDir(id: String): File {
        require(Regex("[a-f0-9]{32}").matches(id)) { "Некорректный идентификатор источника." }
        return File(catalogs, id)
    }
    private fun pageFile(id: String, sha256: String) = File(pageDir(id), StoreFormat.hash(sha256) + ".json")

    private fun writeAtomic(file: File, bytes: ByteArray) {
        require(file.parentFile.mkdirs() || file.parentFile.isDirectory) { "Не удалось открыть кэш каталога." }
        val temporary = File(file.parentFile, ".${file.name}-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            try { Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING) }
        } finally { temporary.delete() }
    }

    private fun records(): List<JSONObject> {
        if (!registry.exists()) return emptyList()
        val root = try { StoreDisk.read(registry) } catch (error: Exception) { throw IllegalStateException("Реестр источников повреждён; данные не сброшены.", error) }
        require(root.optInt("version") == 1) { "Неподдерживаемый реестр источников." }
        val rows = root.getJSONArray("sources")
        return (0 until rows.length()).map { StoreDisk.copy(rows.getJSONObject(it)) }
    }

    private fun save(rows: List<JSONObject>) = StoreDisk.write(registry,
        JSONObject().put("version", 1).put("sources", JSONArray().apply { rows.forEach { put(it) } }))

    private fun saveCache(id: String, bytes: ByteArray) {
        val index = StoreFormat.index(bytes)
        // Persist the exact response bytes: the source's digest remains meaningful in audit reports.
        val file = cacheFile(id); file.parentFile.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            try { Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING) }
        } finally { temporary.delete() }
        check(index.has("repository"))
    }

    companion object {
        const val MAX_METADATA_BYTES = 1024 * 1024
        private fun readLimited(file: File, limit: Int): ByteArray = file.inputStream().use { input ->
            val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; require(out.size() + count <= limit) { "Кэш каталога превышает лимит." }; out.write(buffer, 0, count) }
            out.toByteArray()
        }
    }
}
