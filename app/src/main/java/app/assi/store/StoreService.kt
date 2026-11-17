package app.assi.store

import app.assi.core.Workspace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

/** Catalog, download and review coordination. No package code is executed by this service. */
class StoreService(
    workspace: File,
    private val stateDir: File,
    transport: StoreTransport = StoreHttpTransport(),
    /** Which kinds of packages there are; declared by the modules that host them, read anew on every question. */
    val kinds: StoreKinds = StoreKinds.workspace(workspace),
    snapshot: (String) -> String = { "" },
) {
    val sources = StoreSources(stateDir, transport)
    val installer = StoreInstaller(workspace, stateDir, snapshot, kinds = kinds)
    private val envelopes = File(stateDir, "subjects")
    private val reviews = File(stateDir, "reviews")
    private val downloads = File(stateDir, "downloads")
    /** The last reviewer's word on an archive, whatever plan it was said about. */
    private val verdicts = File(stateDir, "verdicts")
    /** What an installed release was installed with: the report and the owner's decision. */
    private val receipts = File(stateDir, "receipts")
    private val operationLock = Any()

    /** Releases banned for breaking the rules; the installer refuses them on its own as well. */
    val bans: StoreBans get() = installer.bans

    /** Called at application startup before editable boot scripts. Constructor recovers the installer. */
    fun recover() = Unit // StoreInstaller's constructor already replays an interrupted transaction.

    fun catalog(): JSONArray {
        val out = JSONArray()
        val installed = objects(installer.installed())
        val sourceList = sources.list()
        val banned = bans.all()
        // Declarations of the hosts are read once for the whole list, not once for every package.
        val known = kinds.all()
        for (index in 0 until sourceList.length()) {
            val source = sourceList.getJSONObject(index)
            if (!source.optBoolean("enabled", true)) continue
            val entries = try { sources.cached(source.getString("id")).getJSONArray("packages") } catch (_: Exception) { continue }
            for (number in 0 until entries.length()) {
                val entry = StoreDisk.copy(entries.getJSONObject(number))
                if (entry.getString("kind") == StoreFormat.MOD) { out.put(modEntry(entry, source, installed, banned)); continue }
                val sameId = installed.firstOrNull { it.getString("id") == entry.getString("id") }
                val own = installed.firstOrNull { it.getString("id") == entry.getString("id") && it.getString("sourceId") == source.getString("id") }
                var error = compatibility(entry.getJSONObject("compatibility"), false, entry.getString("kind"), known)
                var status = "available"
                if (sameId != null && own == null) {
                    error = "Пакет уже установлен из другого источника. Его код и данные не будут заменены."
                    status = "sourceConflict"
                } else if (own != null) {
                    if (own.getString("publisher") != entry.getString("publisher")) {
                        error = "Издатель установленного пакета изменился. Автоматическая замена запрещена."
                        status = "sourceConflict"
                    } else {
                        val compared = StoreFormat.compareVersions(entry.getString("version"), own.getString("version"))
                        status = when { compared > 0 -> "update"; compared < 0 -> "newerInstalled"; else -> "installed" }
                        if (compared == 0 && own.getString("artifactSha256") != entry.getJSONObject("archive").getString("sha256")) {
                            error = "Архив установленной версии изменился без новой версии пакета."
                            status = "changedRelease"
                        }
                    }
                    if (own.optString("localError").isNotEmpty()) {
                        error = own.getString("localError")
                        status = "localError"
                    }
                } else if (installer.standing(entry.getString("kind"), entry.getString("id"), known[entry.getString("kind")])) {
                    // Put there by hand or by an older installer: the store knows nothing of its version
                    // and offers to take it over instead of calling it absent.
                    status = "unmanaged"
                }
                if (error != null && status == "available") status = "incompatible"
                val sha = entry.getJSONObject("archive").getString("sha256")
                banned.firstOrNull { it.getString("artifactSha256") == sha }?.let { ban ->
                    entry.put("ban", StoreBans.brief(ban))
                    // An installed copy stays what it is; the release is simply not offered again.
                    if (own?.optString("artifactSha256") != sha) { status = "banned"; error = StoreBans.refusal(ban) }
                }
                StoreBans.earlier(banned, entry.getString("id"), source.getString("id"), source.getString("repositoryId"), sha)
                    .takeIf { it.length() > 0 }?.let { entry.put("previousBans", it) }
                entry.put("sourceId", source.getString("id")).put("sourceTitle", source.getString("title"))
                    .put("sourceEnabled", true).put("cached", source.optString("error").isNotEmpty())
                    .put("sourceError", source.optString("error")).put("status", status)
                    .put("compatible", error == null).put("compatibilityError", error ?: "")
                    .put("installedVersion", own?.getString("version") ?: "")
                    .put("installationKey", own?.optString("key") ?: "")
                    .put("localModified", own?.optBoolean("localModified", false) ?: false)
                out.put(entry)
            }
        }
        return out
    }

    /**
     * A mod in the catalog. It has no folder of its own: its status is about the package it changes,
     * which must stand under the store's care for the mod to be laid over it.
     */
    private fun modEntry(entry: JSONObject, source: JSONObject, installed: List<JSONObject>, banned: List<JSONObject>): JSONObject {
        val target = installed.firstOrNull { it.getString("id") == entry.getString("extends") }
        val layer = target?.optJSONArray("mods")?.let { list -> (0 until list.length()).map { list.getJSONObject(it) } }
            ?.firstOrNull { it.getString("id") == entry.getString("id") && it.getString("sourceId") == source.getString("id") }
        var error = compatibility(entry.getJSONObject("compatibility"), false, StoreFormat.MOD)
        var status = when {
            target == null -> "targetMissing"
            layer == null -> "available"
            StoreFormat.compareVersions(entry.getString("version"), layer.getString("version")) > 0 -> "update"
            else -> "installed"
        }
        if (target == null && error == null) error = "Сначала установите пакет «${entry.getString("extends")}»: мод ставится поверх него."
        if (error != null && status == "available") status = "incompatible"
        val sha = entry.getJSONObject("archive").getString("sha256")
        banned.firstOrNull { it.getString("artifactSha256") == sha }?.let { ban ->
            entry.put("ban", StoreBans.brief(ban))
            // A mod that already stands stays what it is; the release is simply not offered again.
            if (layer?.optString("artifactSha256") != sha) { status = "banned"; error = StoreBans.refusal(ban) }
        }
        return entry.put("sourceId", source.getString("id")).put("sourceTitle", source.getString("title")).put("sourceEnabled", true)
            .put("cached", source.optString("error").isNotEmpty()).put("sourceError", source.optString("error")).put("status", status)
            .put("compatible", error == null).put("compatibilityError", error ?: "")
            .put("installedVersion", layer?.getString("version") ?: "").put("installationKey", target?.optString("key") ?: "")
            .put("modKey", layer?.optString("key") ?: "").put("targetTitle", target?.optString("title") ?: "")
            .put("targetVersion", target?.optString("version") ?: "").put("localModified", false)
    }

    /** The decorated catalog for the paged screen, kept for a moment: scrolling asks for it many times in a row. */
    @Volatile private var listed: Pair<Long, List<JSONObject>>? = null

    /** Something the statuses depend on has changed: the next page is computed anew. */
    fun invalidate() { listed = null }

    private fun listing(): List<JSONObject> {
        val now = System.currentTimeMillis()
        listed?.takeIf { now - it.first in 0 until LISTING_TTL_MS }?.let { return it.second }
        return objects(catalog()).also { listed = now to it }
    }

    /**
     * One portion of the catalog for a screen that never draws it whole: filtered, sorted and cut
     * here, over the catalog cached on disk, so searching needs no network. `counts`, `tags` and
     * `kinds` are of the whole catalog, whatever the filter.
     */
    fun catalogPage(options: JSONObject = JSONObject()): JSONObject {
        val all = listing()
        val query = options.optString("query").trim().lowercase(Locale.ROOT)
        val kind = options.optString("kind"); val sourceId = options.optString("sourceId")
        val tag = options.optString("tag").trim().lowercase(Locale.ROOT); val only = options.optString("only")
        // The mods of one package: what its card shows under «Доработки».
        val extends = options.optString("extends")
        fun tags(entry: JSONObject) = entry.optJSONArray("tags")?.let { list -> (0 until list.length()).map { list.getString(it) } } ?: emptyList()
        val matched = all.filter { entry ->
            (kind.isEmpty() || kind == "all" || entry.getString("kind") == kind) &&
                (sourceId.isEmpty() || sourceId == "all" || entry.getString("sourceId") == sourceId) &&
                (tag.isEmpty() || tag in tags(entry)) &&
                (extends.isEmpty() || entry.optString("extends") == extends) &&
                (only != "updates" || entry.getString("status") in PENDING) &&
                (query.isEmpty() || (listOf(entry.getString("title"), entry.getString("summary"), entry.getString("id"), entry.getString("publisher")) + tags(entry))
                    .any { it.lowercase(Locale.ROOT).contains(query) })
        }
        val collator = java.text.Collator.getInstance()
        val byTitle = Comparator<JSONObject> { a, b -> collator.compare(a.getString("title"), b.getString("title")) }.thenBy { it.getString("id") }
        val sorted = when (options.optString("sort")) {
            "updated" -> matched.sortedWith(compareByDescending<JSONObject> { it.optString("updated") }.then(byTitle))
            else -> matched.sortedWith(byTitle)
        }
        val offset = options.optInt("offset", 0).coerceIn(0, sorted.size); val limit = options.optInt("limit", 40).coerceIn(1, 200)
        val counted = HashMap<String, Int>()
        all.forEach { entry -> tags(entry).forEach { counted[it] = (counted[it] ?: 0) + 1 } }
        val top = counted.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(30)
        return JSONObject().put("items", JSONArray(sorted.drop(offset).take(limit).map(StoreDisk::copy)))
            .put("total", sorted.size).put("offset", offset).put("limit", limit)
            .put("counts", JSONObject().put("all", all.size).put("updates", all.count { it.getString("status") in PENDING }))
            .put("tags", JSONArray(top.map { JSONObject().put("tag", it.key).put("count", it.value) }))
            .put("kinds", JSONArray(counted(all)))
    }

    /**
     * The kinds the catalog's packages are of, with the words their hosts gave them. What the catalog
     * can be narrowed by is what its packages and the installed hosts say, not a list kept here.
     */
    private fun counted(all: List<JSONObject>): List<JSONObject> {
        val known = kinds.all(); val numbers = sortedMapOf<String, Int>()
        all.forEach { entry -> numbers[entry.getString("kind")] = (numbers[entry.getString("kind")] ?: 0) + 1 }
        return numbers.map { (name, count) ->
            // A mod is the shell's own kind, as a module is: it needs no host.
            val mod = name == StoreFormat.MOD
            JSONObject().put("kind", name).put("count", count).put("title", if (mod) "Доработки" else known[name]?.title ?: name)
                .put("item", if (mod) "Доработка" else known[name]?.item ?: name)
                .put("host", known[name]?.host ?: JSONObject.NULL).put("hosted", mod || known.containsKey(name))
        }
    }

    /** The kinds of packages this installation can take, as the modules that host them declare. */
    fun kindList(): JSONArray = JSONArray(kinds.all().values.map {
        JSONObject().put("kind", it.name).put("host", it.host ?: JSONObject.NULL).put("title", it.title).put("item", it.item)
            .put("descriptor", it.descriptor).put("engine", it.engine ?: JSONObject.NULL).put("version", it.version)
    })

    /** The description, screenshots and changes of one package; fetched when it is opened and kept by hash. */
    fun details(sourceId: String, packageId: String): JSONObject = StoreDisk.copy(sources.details(sourceId, packageId))

    /**
     * A picture of a listing as a `data:` address for the page. Which file it is comes from the
     * checked catalog, never from the caller, and the page does not talk to the source's host itself.
     */
    fun media(sourceId: String, packageId: String, kind: String, index: Int = 0): String {
        val ref = when (kind) {
            "icon" -> sources.packageEntry(sourceId, packageId).optJSONObject("icon")
            "screenshot" -> sources.details(sourceId, packageId).optJSONArray("screenshots")?.optJSONObject(index)
            else -> throw IllegalArgumentException("Неизвестный вид картинки.")
        } ?: throw IllegalArgumentException("У пакета нет такой картинки.")
        return "data:" + ref.getString("type") + ";base64," + java.util.Base64.getEncoder().encodeToString(sources.picture(sourceId, ref))
    }

    fun installed(): JSONArray {
        val result = installer.installed()
        val known = objects(sources.list()).associateBy { it.getString("id") }
        val banned = bans.all()
        for (i in 0 until result.length()) {
            val record = result.getJSONObject(i)
            val source = known[record.getString("sourceId")]
            banned.firstOrNull { it.getString("artifactSha256") == record.optString("artifactSha256") }?.let { record.put("ban", StoreBans.brief(it)) }
            runCatching { receiptFile(record.getString("key"), record.getString("artifactSha256")).takeIf { it.isFile }?.let(StoreDisk::read) }
                .getOrNull()?.let { record.put("review", it) }
            record.put("sourceTitle", source?.optString("title") ?: "Удалённый источник")
                .put("sourceEnabled", source?.optBoolean("enabled", false) ?: false)
                .put("sourceAvailable", source != null)
        }
        return result
    }

    /** [adopt]: the owner asked to take a module that already stands there without the store's record under its care. */
    fun prepare(sourceId: String, packageId: String, adopt: Boolean = false): JSONObject {
        val source = sources.source(sourceId, true)
        val entry = sources.packageEntry(sourceId, packageId)
        bans.find(entry.getJSONObject("archive").getString("sha256"))?.let { throw StoreBanned(it) }
        compatibility(entry.getJSONObject("compatibility"), false, entry.getString("kind"))?.let { throw IllegalArgumentException(it) }
        val existing = objects(installer.installed()).firstOrNull { it.getString("id") == packageId }
        if (existing != null) {
            require(existing.getString("sourceId") == sourceId) { "Этот пакет принадлежит другому источнику. Установленная версия сохранена." }
            require(existing.getString("publisher") == entry.getString("publisher")) { "Издатель пакета изменился. Подготовка остановлена." }
            require(StoreFormat.compareVersions(entry.getString("version"), existing.getString("version")) >= 0) { "Источник предлагает более старую версию. Для возврата используйте явный откат." }
            if (entry.getString("version") == existing.getString("version")) {
                require(entry.getJSONObject("archive").getString("sha256") == existing.getString("artifactSha256")) { "Архив этой версии был подменён. Нужна новая версия публикации." }
            }
        }
        val manifestResponse = sources.fetch(entry.getString("manifestUrl"), StoreSources.MAX_METADATA_BYTES)
        require(manifestResponse.status == 200) { "Манифест не загружен." }
        val manifest = StoreFormat.manifest(manifestResponse.bytes)
        compatibility(manifest.getJSONObject("runtime"), true, manifest.getString("kind"))?.let { throw IllegalArgumentException(it) }
        val archive = entry.getJSONObject("archive")
        val response = sources.fetch(archive.getString("url"), archive.getInt("size"))
        require(response.status == 200) { "Архив не загружен." }
        // What arrived is not what the index names: a damaged download or a careless source. The package
        // itself is not judged by bytes that are not its own, so this is noted with the source, not banned.
        val arrived = when {
            response.bytes.size.toLong() != archive.getLong("size") -> "Размер архива отличается от index.json."
            StoreFormat.sha256(response.bytes) != archive.getString("sha256") -> "SHA-256 архива не совпадает с index.json."
            else -> null
        }
        if (arrived != null) {
            sources.note(sourceId, packageId, entry.getString("version"), arrived)
            throw StoreSourceFault(arrived)
        }
        val token = UUID.randomUUID().toString()
        val directory = File(downloads, token)
        require(directory.mkdirs()) { "Не удалось подготовить загрузку." }
        try {
            val file = File(directory, "package.zip").apply { writeBytes(response.bytes) }
            return synchronized(operationLock) {
                val current = sources.source(sourceId, true)
                require(current.getString("instanceId") == source.getString("instanceId")) { "Источник изменился во время загрузки. Подготовьте пакет снова." }
                val latest = sources.packageEntry(sourceId, packageId)
                require(StoreFormat.canonical(latest) == StoreFormat.canonical(entry)) { "Каталог изменился во время загрузки. Подготовьте новую версию." }
                val result = try {
                    if (entry.getString("kind") == StoreFormat.MOD) installer.prepareMod(sourceId, entry, manifestResponse.bytes, file)
                    else installer.prepare(sourceId, entry, manifestResponse.bytes, file, adopt)
                } catch (violation: StoreViolation) {
                    // The archive is exactly the one the index names, and it breaks a rule no review is needed for.
                    val rule = StoreRules.rule(violation.rule)
                    val reason = violation.message.orEmpty()
                    val ban = bans.add(banRecord(entry.getJSONObject("archive").getString("sha256"), sourceId, source.optString("title"),
                        source.getString("repositoryId"), source.getString("indexUrl"), packageId, entry.getString("title"), entry.getString("version"),
                        entry.getString("publisher"), "validator", reason, JSONArray().put(JSONObject().put("rule", rule.code).put("severity", rule.level)
                            .put("title", rule.title).put("certainty", "observed").put("evidence", JSONObject().put("path", violation.path))
                            .put("explanation", reason).put("resolution", VALIDATOR_RESOLUTION))))
                    throw StoreBanned(ban, "$reason Выпуск забанен: ${rule.title}.")
                } catch (fault: StoreSourceFault) {
                    sources.note(sourceId, packageId, entry.getString("version"), fault.message.orEmpty())
                    throw fault
                }
                sources.settle(sourceId, packageId)
                val subject = JSONObject().put("sourceId", sourceId).put("sourceCreatedAt", source.getLong("createdAt"))
                    .put("sourceInstanceId", source.getString("instanceId"))
                    .put("repositoryId", source.getString("repositoryId")).put("indexUrl", source.getString("indexUrl"))
                    .put("indexSha256", source.getString("indexSha256"))
                    .put("indexEntrySha256", StoreFormat.sha256(StoreFormat.canonical(entry).toByteArray(Charsets.UTF_8)))
                    .put("manifestSha256", result.getString("manifestSha256"))
                    .put("manifestDownloadSha256", StoreFormat.sha256(manifestResponse.bytes))
                    .put("manifestUrl", manifestResponse.finalUrl).put("archiveUrl", response.finalUrl)
                    .put("entry", entry).put("createdAt", System.currentTimeMillis()).put("localRollback", false)
                StoreDisk.write(subjectFile(result.getString("id")), subject)
                decorate(result, subject)
            }
        } finally { directory.deleteRecursively() }
    }

    fun plans(): JSONArray = synchronized(operationLock) {
        val result = JSONArray()
        // Plans that wait for the owner. One that was applied is history: it is opened by its ID when
        // its result is shown, and is not checked again every time the list of plans is asked for.
        objects(installer.plans()).filter { it.optString("status") != "applied" }.forEach { item ->
            if (subjectFile(item.getString("id")).isFile) {
                runCatching { plan(item.getString("id")) }.onSuccess { result.put(it) }
            }
        }
        result
    }

    fun plan(id: String): JSONObject = synchronized(operationLock) {
        decorate(installer.plan(id), subject(id))
    }

    fun read(id: String, path: String, offset: Int = 0, limit: Int = 16000, side: String = "candidate"): JSONObject = synchronized(operationLock) {
        val current = plan(id)
        installer.read(id, path, offset, limit, side).put("planDigest", current.getString("digest"))
    }

    fun resolve(id: String, path: String, expectedHash: String, content: String?, keepLocal: Boolean, takeIncoming: Boolean = false): JSONObject = synchronized(operationLock) {
        subject(id)
        val result = installer.resolve(id, path, expectedHash, content, keepLocal, takeIncoming)
        decorate(result, subject(id))
    }

    fun prepareRollback(key: String): JSONObject = synchronized(operationLock) {
        val result = installer.prepareRollback(key)
        val old = objects(installer.installed()).firstOrNull { it.getString("key") == key }
        val known = runCatching { sources.source(result.getString("sourceId")) }.getOrNull()
        val manifest = result.getJSONObject("manifest")
        val envelope = JSONObject().put("sourceId", result.getString("sourceId"))
            .put("sourceCreatedAt", known?.optLong("createdAt") ?: 0L)
            .put("sourceInstanceId", known?.optString("instanceId") ?: "")
            .put("repositoryId", known?.optString("repositoryId") ?: "")
            .put("indexUrl", known?.optString("indexUrl") ?: "")
            .put("manifestSha256", StoreFormat.sha256(StoreFormat.canonical(manifest).toByteArray(Charsets.UTF_8)))
            .put("localRollback", true).put("createdAt", System.currentTimeMillis())
            .put("previousInstalledVersion", old?.optString("version") ?: "")
        StoreDisk.write(subjectFile(result.getString("id")), envelope)
        decorate(result, envelope)
    }

    /**
     * Prepares taking a mod off its package. Like a rollback it needs no source: what is put back was
     * kept on the device when the mod was laid over.
     */
    fun removeMod(packageKey: String, modKey: String): JSONObject = synchronized(operationLock) {
        val result = installer.prepareModRemoval(packageKey, modKey)
        val known = runCatching { sources.source(result.getString("sourceId")) }.getOrNull()
        val envelope = JSONObject().put("sourceId", result.getString("sourceId"))
            .put("sourceCreatedAt", known?.optLong("createdAt") ?: 0L).put("sourceInstanceId", known?.optString("instanceId") ?: "")
            .put("repositoryId", known?.optString("repositoryId") ?: "").put("indexUrl", known?.optString("indexUrl") ?: "")
            .put("manifestSha256", result.getString("manifestSha256")).put("localRollback", true).put("createdAt", System.currentTimeMillis())
        StoreDisk.write(subjectFile(result.getString("id")), envelope)
        decorate(result, envelope)
    }

    /**
     * The mod that stands on a package, prepared again for an agent to carry it over anew: after the
     * package was updated under it, or when the owner is not content with how it was carried over.
     * Like a removal it needs no source: the mod's release is kept on the device.
     */
    fun againMod(packageKey: String, modKey: String): JSONObject = synchronized(operationLock) {
        val result = installer.prepareModAgain(packageKey, modKey)
        val known = runCatching { sources.source(result.getString("sourceId")) }.getOrNull()
        val envelope = JSONObject().put("sourceId", result.getString("sourceId"))
            .put("sourceCreatedAt", known?.optLong("createdAt") ?: 0L).put("sourceInstanceId", known?.optString("instanceId") ?: "")
            .put("repositoryId", known?.optString("repositoryId") ?: "").put("indexUrl", known?.optString("indexUrl") ?: "")
            .put("manifestSha256", result.getString("manifestSha256")).put("localRollback", true).put("createdAt", System.currentTimeMillis())
        StoreDisk.write(subjectFile(result.getString("id")), envelope)
        decorate(result, envelope)
    }

    /** The owner's own changes to an installed package, written out as the source folder of a mod (see the installer). */
    fun draftMod(packageKey: String, into: File, meta: JSONObject): JSONObject = synchronized(operationLock) { installer.draftMod(packageKey, into, meta) }

    // ---- carrying a mod over: the executor's steps, each on the plan it was started for ----------------

    fun carryTask(id: String, tree: Boolean = false, offset: Int = 0): JSONObject = synchronized(operationLock) { subject(id); installer.carryTask(id, tree, offset) }
    fun carryRead(id: String, side: String, path: String, offset: Int = 0, limit: Int = 12000): JSONObject = synchronized(operationLock) { subject(id); installer.carryRead(id, side, path, offset, limit) }
    fun carryWrite(id: String, path: String, content: String): JSONObject = synchronized(operationLock) { decorate(installer.carryWrite(id, path, content), subject(id)) }
    fun carryEdit(id: String, path: String, find: String, replace: String, all: Boolean = false): JSONObject = synchronized(operationLock) { decorate(installer.carryEdit(id, path, find, replace, all), subject(id)) }
    fun carryCopy(id: String, side: String, source: String, path: String): JSONObject = synchronized(operationLock) { decorate(installer.carryCopy(id, side, source, path), subject(id)) }
    fun carryDelete(id: String, path: String): JSONObject = synchronized(operationLock) { decorate(installer.carryDelete(id, path), subject(id)) }
    fun carryDone(id: String, report: JSONObject): JSONObject = synchronized(operationLock) { decorate(installer.carryDone(id, report), subject(id)) }

    /** A mod carried over by an agent: what stands in its plan is that agent's work, not the author's archive. */
    private fun ported(plan: JSONObject): Boolean = plan.optJSONObject("mod")?.optString("mode") == "ported"

    /** Taking a mod off is always allowed: a ban or a warning about its release is a reason to remove it, not an obstacle. */
    private fun removal(plan: JSONObject): Boolean = plan.optJSONObject("mod")?.optBoolean("removal") == true

    fun apply(id: String, expectedDigest: String, acceptTrustedCode: Boolean): JSONObject = synchronized(operationLock) {
        require(acceptTrustedCode) { "Подтвердите установку кода с доступом Axiko или поручите проверку агенту." }
        commit(id, expectedDigest, null)
    }

    /** [decision] is the owner's recorded word to install what the review warned about; nobody else supplies one. */
    private fun commit(id: String, expectedDigest: String, decision: JSONObject?): JSONObject {
        val current = plan(id)
        require(current.optString("status") != "applied") { "Этот план уже установлен. Для новых изменений подготовьте новый план." }
        val removal = removal(current)
        if (!removal) bans.find(current.getString("artifactSha256"))?.let { throw StoreBanned(it) }
        require(current.getString("digest") == expectedDigest) { "План изменился. Проверьте изменения перед установкой." }
        require(current.getJSONArray("blockers").length() == 0) { current.getJSONArray("blockers").getString(0) }
        val report = review(id)
        require(removal || report.optString("status") != "reject") { "Проверка отклонила этот план. Исправьте пакет и подготовьте новую проверку." }
        val effective = effective(report)
        require(removal || effective?.optString("status") != "needsAttention" || decision != null) {
            "Проверка нашла в этом выпуске подозрительные места. Установить его может только владелец: кнопка «Установить, несмотря на предупреждения» в плане магазина."
        }
        // Persist the reviewed subject before the core transaction. If the process
        // dies after commit, core recovery marks this same immutable plan applied;
        // the receipt still identifies its historical review, not a new approval.
        val envelope = subject(id).put("applyReceipt", JSONObject()
            .put("planDigest", expectedDigest).put("installerDigest", current.getString("installerDigest"))
            .put("artifactSha256", current.getString("artifactSha256")))
        StoreDisk.write(subjectFile(id), envelope)
        val receipt = JSONObject().put("artifactSha256", current.getString("artifactSha256")).put("planId", id)
            .put("version", current.getString("version")).put("decidedAt", System.currentTimeMillis())
            .put("verdict", effective?.optString("status") ?: "none")
        if (effective != null) receipt.put("summary", effective.optString("summary")).put("findings", effective.optJSONArray("findings") ?: JSONArray())
            .put("unknowns", effective.optJSONArray("unknowns") ?: JSONArray()).put("reviewedAt", effective.optLong("createdAt"))
            .put("policyVersion", effective.optInt("policyVersion"))
        if (decision != null) receipt.put("ownerDecision", StoreDisk.copy(decision))
        StoreDisk.write(receiptFile(current.getString("installationKey"), current.getString("artifactSha256")), receipt)
        val result = installer.apply(id, current.getString("installerDigest"))
        invalidate()
        if (decision != null) runCatching {
            val file = reviewFile(id)
            if (file.isFile) StoreDisk.read(file).takeIf { it.optString("planDigest") == expectedDigest }?.let { StoreDisk.write(file, it.put("ownerDecision", StoreDisk.copy(decision))) }
        }
        return result.put("dataPreserved", true).put("runtimeProfile", "trustedLegacy").put("capabilitiesEnforced", false)
            .put("packageCodeStarted", false)
    }

    /** The report that speaks for the plan as it is now: its own, or the last one said about the same archive. */
    private fun effective(report: JSONObject): JSONObject? =
        if (report.optString("status") == "pending") report.optJSONObject("inherited") else report

    private class Warned(val summary: JSONObject, val key: String)

    private fun warned(id: String, expectedDigest: String): Warned {
        val current = plan(id)
        require(current.optString("status") != "applied") { "Этот план уже установлен." }
        bans.find(current.getString("artifactSha256"))?.let { throw StoreBanned(it) }
        require(current.getString("digest") == expectedDigest) { "План изменился. Посмотрите его ещё раз перед решением." }
        require(current.getJSONArray("blockers").length() == 0) { current.getJSONArray("blockers").getString(0) }
        require(current.optString("status") == "ready") { "Сначала разрешите конфликты обновления." }
        val report = effective(review(id))
        require(report?.optString("status") == "needsAttention") { "У этого плана нет предупреждений проверки. Используйте обычную установку или проверку агентом." }
        val findings = report!!.optJSONArray("findings") ?: JSONArray()
        val warnings = JSONArray()
        for (i in 0 until findings.length()) {
            val finding = findings.getJSONObject(i); val evidence = finding.optJSONObject("evidence") ?: JSONObject()
            warnings.put(JSONObject().put("rule", finding.optString("rule")).put("title", finding.optString("title"))
                .put("path", evidence.optString("path")).put("line", evidence.optInt("line")))
        }
        val summary = JSONObject().put("planId", id).put("title", current.optString("title")).put("version", current.optString("version"))
            .put("sourceTitle", current.optString("sourceTitle")).put("warnings", warnings)
            .put("unknowns", report.optJSONArray("unknowns")?.length() ?: 0)
        val key = StoreFormat.sha256(StoreFormat.canonical(JSONObject().put("findings", findings)
            .put("unknowns", report.optJSONArray("unknowns") ?: JSONArray()).put("createdAt", report.optLong("createdAt"))).toByteArray(Charsets.UTF_8))
        return Warned(summary, key)
    }

    /**
     * The owner installs a release the review warned about. [confirm] is the shell's own dialog
     * (see StoreOwner): it is shown what was found and returns the owner's answer. The lock is
     * not held while the owner reads; what was asked about is checked again before installing.
     */
    fun installWithWarnings(id: String, expectedDigest: String, confirm: (JSONObject) -> Boolean): JSONObject {
        val asked = synchronized(operationLock) { warned(id, expectedDigest) }
        if (!confirm(StoreDisk.copy(asked.summary))) return JSONObject().put("ok", false).put("status", "cancelled")
        return synchronized(operationLock) {
            val again = warned(id, expectedDigest)
            require(again.key == asked.key) { "Отчёт проверки изменился, пока вы решали. Посмотрите предупреждения ещё раз." }
            val decision = JSONObject().put("decision", "installDespiteWarnings").put("at", System.currentTimeMillis())
                .put("planId", id).put("planDigest", expectedDigest).put("artifactSha256", plan(id).getString("artifactSha256"))
                .put("warnings", again.summary.getJSONArray("warnings")).put("unknowns", again.summary.getInt("unknowns"))
            commit(id, expectedDigest, decision).put("ownerDecision", decision)
        }
    }

    /** The owner decided not to install, or a plan is of no use any more: its staging goes, nothing installed changes. */
    fun discard(id: String): JSONObject = synchronized(operationLock) {
        safePlanId(id)
        val status = objects(installer.plans()).firstOrNull { it.optString("id") == id }?.optString("status")
        require(status != "applied") { "Этот план уже установлен; убирать нечего." }
        val result = installer.discardPlan(id)
        subjectFile(id).delete(); reviewFile(id).delete()
        result
    }

    /**
     * Lifts a ban: the owner's decision only, taken in [confirm] (the shell's dialog). The release
     * goes back to "not reviewed": the reports that rejected it no longer stand in the way.
     */
    fun unban(sha256: String, confirm: (JSONObject) -> Boolean): JSONObject {
        val ban = bans.find(StoreFormat.hash(sha256)) ?: throw IllegalArgumentException("Этот выпуск не забанен.")
        if (!confirm(StoreDisk.copy(ban))) return JSONObject().put("ok", false).put("status", "cancelled")
        return synchronized(operationLock) {
            bans.lift(sha256)
            verdictFile(sha256).delete()
            objects(installer.plans()).filter { it.optString("artifactSha256") == sha256 && it.optString("status") != "applied" }.forEach { plan ->
                val file = reviewFile(plan.getString("id"))
                if (file.isFile && runCatching { StoreDisk.read(file).optString("status") }.getOrNull() == "reject") file.delete()
            }
            JSONObject().put("ok", true).put("status", "lifted").put("artifactSha256", sha256)
        }
    }

    fun review(id: String): JSONObject = synchronized(operationLock) {
        val current = plan(id)
        val file = reviewFile(id)
        val report = if (file.isFile) StoreDisk.read(file) else null
        val receipt = subject(id).optJSONObject("applyReceipt")
        if (report != null && current.optString("status") == "applied" && receipt != null &&
            receipt.optString("planDigest") == report.optString("planDigest") &&
            receipt.optString("artifactSha256") == current.getString("artifactSha256")) {
            return@synchronized StoreDisk.copy(report).put("completed", true).put("historicalOnly", true)
                .put("appliedAt", current.optLong("appliedAt"))
        }
        if (report != null && report.optString("planDigest") == current.getString("digest")) return@synchronized StoreDisk.copy(report)
        val pending = JSONObject().put("status", "pending").put("planDigest", current.getString("digest"))
        if (report != null) pending.put("stale", true)
        // A new plan of an archive the review already warned about does not start with a clean slate.
        if (current.optString("status") != "applied") runCatching { verdictFile(current.getString("artifactSha256")).takeIf { it.isFile }?.let(StoreDisk::read) }
            .getOrNull()?.takeIf { it.optString("status") == "needsAttention" }?.let { pending.put("inherited", it.put("inherited", true)) }
        pending
    }

    /**
     * Saves a reviewer's report. The form is checked against the rules and the status is derived
     * here: a violation rejects the release and bans it at once, suspicions and unknowns leave the
     * decision to the owner, and only an empty report on fully read sources is a pass. Actual read
     * coverage and the owner's consent are enforced by the dedicated agent tools at the caller.
     */
    fun submitReview(id: String, expectedDigest: String, report: JSONObject): JSONObject = synchronized(operationLock) {
        val current = plan(id)
        require(current.optString("status") != "applied") { "Проверка применённого плана уже завершена. Подготовьте новый план." }
        bans.find(current.getString("artifactSha256"))?.let { throw StoreBanned(it) }
        require(expectedDigest == current.getString("digest")) { "Проверенный план изменился. Повторите проверку." }
        require(report.toString().toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "Отчёт проверки слишком велик." }
        if (report.has("artifactSha256")) require(report.getString("artifactSha256") == current.getString("artifactSha256")) { "Отчёт относится к другому архиву." }
        if (report.has("planDigest")) require(report.getString("planDigest") == expectedDigest) { "Отчёт относится к другому плану." }
        val coverage = report.optJSONObject("coverage")
        require(coverage != null && coverage.opt("complete") is Boolean) { "В отчёте нет сведений о прочитанных исходниках." }
        val reviewFiles = current.getJSONArray("reviewFiles")
        val paths = (0 until reviewFiles.length()).map { reviewFiles.getJSONObject(it).getString("path") }.toSet()
        val counted = HashMap<String, Int>()
        val normalized = StoreRules.normalize(report, paths, coverage.getBoolean("complete")) { path -> counted.getOrPut(path) { lineCount(id, path) } }
        // What the reviewer said about the archive itself; the plan's own unsettled matters are added below.
        val verdict = normalized.getString("status")
        val conflicts = current.optJSONArray("conflicts") ?: JSONArray()
        if ((0 until conflicts.length()).any { !conflicts.getJSONObject(it).optBoolean("resolved") }) normalized.getJSONArray("unknowns").put("Остались неразрешённые конфликты обновления с локальными правками.")
        val blockers = current.getJSONArray("blockers")
        for (i in 0 until blockers.length()) normalized.getJSONArray("unknowns").put("Установке мешает: ${blockers.getString(i)}")
        normalized.put("status", StoreRules.status(normalized.getJSONArray("findings"), normalized.getJSONArray("unknowns")))
        val saved = normalized.put("reviewVersion", 2).put("policyVersion", StoreRules.VERSION)
            .put("planId", id).put("planDigest", expectedDigest).put("createdAt", System.currentTimeMillis())
            .put("artifactSha256", current.getString("artifactSha256"))
            .put("subject", StoreDisk.copy(current.getJSONObject("subject")))
            .put("coverage", StoreDisk.copy(coverage)).put("reviewedFiles", coverage.optJSONArray("filesReviewed") ?: JSONArray())
            .put("runtimeProfile", "trustedLegacy").put("capabilitiesEnforced", false).put("checksExecuted", false)
        // A violation that came with the carry-over is the carry-over's fault: the plan is rejected, the author's release is not banned.
        val ported = ported(current)
        if (saved.getString("status") == "reject" && ported) saved.put("banned", false)
        if (saved.getString("status") == "reject" && !ported) {
            val findings = saved.getJSONArray("findings")
            val violations = JSONArray((0 until findings.length()).map { findings.getJSONObject(it) }.filter { it.getString("severity") == StoreRules.VIOLATION }.map(StoreDisk::copy))
            val subject = current.getJSONObject("subject")
            // At once and before the report is acknowledged to the reviewer: a reject is never left unbanned.
            bans.add(banRecord(current.getString("artifactSha256"), current.getString("sourceId"), current.optString("sourceTitle"),
                subject.optString("repositoryId"), subject.optString("indexUrl"), current.getString("packageId"), current.optString("title"),
                current.getString("version"), current.optString("publisher"), "review", saved.getString("summary"), violations).put("planId", id))
            saved.put("banned", true)
        }
        StoreDisk.write(reviewFile(id), saved)
        // The word about an archive is carried to its next plans; a carried-over candidate is not that archive.
        if (!ported) StoreDisk.write(verdictFile(current.getString("artifactSha256")), StoreDisk.copy(saved).put("status", verdict))
        StoreDisk.copy(saved)
    }

    private fun banRecord(sha256: String, sourceId: String, sourceTitle: String, repositoryId: String, indexUrl: String, packageId: String,
                          title: String, version: String, publisher: String, origin: String, summary: String, violations: JSONArray): JSONObject =
        JSONObject().put("artifactSha256", sha256).put("sourceId", sourceId).put("sourceTitle", sourceTitle).put("repositoryId", repositoryId)
            .put("indexUrl", indexUrl).put("packageId", packageId).put("title", title).put("version", version).put("publisher", publisher)
            .put("origin", origin).put("summary", summary.take(2000)).put("violations", violations).put("bannedAt", System.currentTimeMillis())
            .put("policyVersion", StoreRules.VERSION)

    /** Lines of a file of the candidate, for checking that a finding points at a real place. */
    private fun lineCount(id: String, path: String): Int = runCatching {
        var offset = 0; var lines = 1
        while (true) {
            val part = installer.read(id, path, offset, 64000)
            lines += part.getString("content").count { it == '\n' }
            offset = part.getInt("nextOffset")
            if (part.getBoolean("eof")) break
        }
        lines
    }.getOrDefault(1)

    private fun decorate(raw: JSONObject, envelope: JSONObject): JSONObject {
        val result = StoreDisk.copy(raw)
        val blockers = JSONArray()
        var sourceInstance = "local-rollback"
        if (!envelope.optBoolean("localRollback")) {
            val source = runCatching { sources.source(raw.getString("sourceId"), true) }
            if (source.isFailure) blockers.put(source.exceptionOrNull()?.message ?: "Источник недоступен.")
            else {
                val current = source.getOrThrow()
                sourceInstance = current.getString("instanceId")
                if (current.getString("instanceId") != envelope.getString("sourceInstanceId") || current.getString("repositoryId") != envelope.getString("repositoryId") || current.getString("indexUrl") != envelope.getString("indexUrl")) {
                    blockers.put("Источник был удалён или изменён. Подготовьте пакет снова.")
                }
            }
        }
        val manifest = result.getJSONObject("manifest")
        compatibility(manifest.getJSONObject("runtime"), true, manifest.getString("kind"))?.let { blockers.put(it) }
        // A mod depends on nothing but the package it changes, and that one is checked by the installer.
        val dependencies = if (raw.has("mod")) JSONArray() else dependencyState(result.getString("sourceId"), manifest, blockers)
        val coreDigest = result.getString("digest")
        val scope = JSONObject().put("core", coreDigest).put("dependencies", dependencies).put("sourceInstance", sourceInstance)
            .put("blockers", blockers).put("policy", StoreRules.VERSION)
        val digest = StoreFormat.sha256(StoreFormat.canonical(scope).toByteArray(Charsets.UTF_8))
        result.put("installerDigest", coreDigest).put("digest", digest).put("blockers", blockers)
            .put("dependencies", dependencies).put("runtimeProfile", "trustedLegacy").put("capabilitiesEnforced", false)
            .put("sourceTitle", runCatching { sources.source(raw.getString("sourceId")).getString("title") }.getOrElse { "Удалённый источник" })
        if (blockers.length() > 0 && raw.optString("status") != "applied") result.put("status", "blocked")
        // Outside the digest on purpose: a ban is about the archive, and the report that caused it stays readable.
        val banned = bans.all()
        banned.firstOrNull { it.getString("artifactSha256") == raw.getString("artifactSha256") }?.let { ban ->
            result.put("ban", StoreBans.brief(ban))
            if (raw.optString("status") != "applied" && !removal(raw)) result.put("status", "banned")
        }
        StoreBans.earlier(banned, raw.getString("packageId"), raw.getString("sourceId"), envelope.optString("repositoryId"), raw.getString("artifactSha256"))
            .takeIf { it.length() > 0 }?.let { result.put("previousBans", it) }
        result.put("subject", JSONObject().put("sourceId", result.getString("sourceId"))
            .put("packageId", result.getString("packageId")).put("version", result.getString("version"))
            .put("artifactSha256", result.getString("artifactSha256"))
            .put("manifestSha256", envelope.optString("manifestSha256"))
            .put("indexEntrySha256", envelope.optString("indexEntrySha256"))
            .put("indexSha256", envelope.optString("indexSha256"))
            .put("repositoryId", envelope.optString("repositoryId")).put("indexUrl", envelope.optString("indexUrl"))
            .put("archiveUrl", envelope.optString("archiveUrl")))
        result.put("localRollback", envelope.optBoolean("localRollback"))
        if (raw.optString("status") == "applied") envelope.optJSONObject("applyReceipt")?.let {
            result.put("appliedFromDigest", it.optString("planDigest"))
        }
        return result
    }

    private fun dependencyState(sourceId: String, manifest: JSONObject, blockers: JSONArray): JSONArray {
        val installed = objects(installer.installed())
        val result = JSONArray()
        for (field in listOf("dependencies", "optionalDependencies")) {
            val list = manifest.optJSONArray(field) ?: JSONArray()
            for (i in 0 until list.length()) {
                val dependency = list.getJSONObject(i)
                val own = installed.firstOrNull { it.getString("sourceId") == sourceId && it.getString("id") == dependency.getString("id") }
                val matches = own != null && own.optString("localError").isEmpty() && StoreFormat.versionMatches(own.getString("version"), dependency.getString("version"))
                val item = StoreDisk.copy(dependency).put("optional", field == "optionalDependencies")
                    .put("installedVersion", own?.getString("version") ?: "").put("satisfied", matches)
                    .put("artifactSha256", own?.optString("artifactSha256") ?: "")
                    .put("localModified", own?.optBoolean("localModified", false) ?: false)
                // Including exact live fingerprints prevents a reviewed dependency silently changing under the plan.
                own?.optString("liveDigest")?.takeIf { it.isNotEmpty() }?.let { item.put("liveDigest", it) }
                result.put(item)
                if (!matches && field == "dependencies") blockers.put("Сначала установите ${dependency.getString("id")} ${dependency.getString("version")} из того же источника.")
            }
        }
        return result
    }

    private fun compatibility(value: JSONObject, manifest: Boolean, kind: String, known: Map<String, StoreKind> = kinds.all()): String? {
        val required = value.getInt(if (manifest) "assiApi" else "minAssiApi")
        if (required > ASSI_API) return "Пакету нужен API Axiko $required; установлен $ASSI_API."
        if (kind == StoreFormat.MOD) return null
        return kinds.mismatch(kind, value, known)
    }

    private fun subject(id: String): JSONObject {
        val file = subjectFile(id)
        require(file.isFile) { "У плана нет проверенного источника. Подготовьте пакет заново." }
        return StoreDisk.read(file)
    }
    private fun subjectFile(id: String) = File(envelopes, safePlanId(id) + ".json")
    private fun reviewFile(id: String) = File(reviews, safePlanId(id) + ".json")
    private fun verdictFile(sha256: String) = File(verdicts, StoreFormat.hash(sha256) + ".json")
    private fun receiptFile(key: String, sha256: String) = File(receipts, StoreFormat.hash(key) + "-" + StoreFormat.hash(sha256) + ".json")
    private fun safePlanId(id: String): String {
        require(Regex("[a-zA-Z0-9_-]{1,128}").matches(id)) { "Некорректный план установки." }
        return id
    }
    private fun objects(array: JSONArray) = (0 until array.length()).map { array.getJSONObject(it) }

    companion object {
        const val ASSI_API = 1
        private const val LISTING_TTL_MS = 3000L
        /** What the «Обновления» tab shows: a newer release, or a module to take over. */
        private val PENDING = setOf("update", "unmanaged")
        private const val VALIDATOR_RESOLUTION = "Не устанавливать. Автору нужно исправить пакет и выпустить его с новым номером версии."
    }
}

/** Trusted shell API; editable UI and the agent use the same installed implementation. */
object Store {
    @Volatile private var current: StoreService? = null
    private var currentRoot: String? = null

    val service: StoreService
        get() = synchronized(this) {
            val root = Workspace.root.canonicalPath
            if (current == null || currentRoot != root) {
                // Kinds of packages are declared by modules; those the application ships with speak for a host
                // whose own module.json was edited and lost its declaration.
                val kinds = StoreKinds.workspace(Workspace.root) {
                    val app = app.assi.App.instance
                    app.assets.list("factory/modules").orEmpty().mapNotNull { id ->
                        Workspace.factoryText(app, "modules/$id/module.json")?.let { text -> runCatching { id to JSONObject(text) }.getOrNull() }
                    }
                }
                current = StoreService(Workspace.root, File(Workspace.state, "store"), StoreHttpTransport(), kinds) { label -> Workspace.snapshot(label) }
                currentRoot = root
            }
            current!!
        }

    fun recover() = service.recover()

    fun draftMod(packageKey: String, into: File, meta: JSONObject): JSONObject = service.draftMod(packageKey, into, meta)

    /** The catalog every install starts with. The owner can switch it off or remove it like any other source. */
    const val FACTORY_SOURCE = "https://raw.githubusercontent.com/Billerens/assi-store/main/index.json"
    @Volatile private var factoryTried = 0L

    /**
     * Connects the factory source once, off the caller's thread: the store opens at once and the catalog
     * arrives when the index has been fetched and checked ([connected] is then called). Without a network
     * nothing is recorded; the next look at the store tries again, at most once a minute.
     */
    fun factory(connected: () -> Unit) {
        val service = service
        val now = System.currentTimeMillis()
        if (service.sources.factorySettled() || now - factoryTried < 60_000) return
        factoryTried = now
        Thread({ runCatching { service.sources.ensureFactory(FACTORY_SOURCE) }.getOrNull()?.let { service.invalidate(); connected() } }, "store-factory-source").start()
    }

    /** Calls after which the statuses of the catalog may be different. */
    private val CHANGING = setOf("store.source.add", "store.source.refresh", "store.source.remove", "store.source.enable",
        "store.apply", "store.acceptWarnings", "store.unban", "store.submitReview")

    fun call(method: String, args: JSONArray): Any? {
        val service = service
        if (method in CHANGING) service.invalidate()
        return try { dispatch(service, method, args) } finally { if (method in CHANGING) service.invalidate() }
    }

    private fun dispatch(service: StoreService, method: String, args: JSONArray): Any? {
        return when (method) {
            "store.sources" -> service.sources.list()
            "store.source.add" -> service.sources.add(args.getString(0))
            "store.source.refresh" -> service.sources.refresh(args.getString(0))
            "store.source.remove" -> service.sources.remove(args.getString(0))
            "store.source.enable" -> service.sources.enable(args.getString(0), args.getBoolean(1))
            "store.catalog" -> service.catalogPage(args.optJSONObject(0) ?: JSONObject())
            "store.kinds" -> service.kindList()
            "store.mod.remove" -> service.removeMod(args.getString(0), args.getString(1))
            "store.mod.again" -> service.againMod(args.getString(0), args.getString(1))
            // The executor's steps. Not among the operations a page may call: only the carry conversation reaches them.
            "store.mod.carry.task" -> service.carryTask(args.getString(0), args.optBoolean(1, false), args.optInt(2, 0))
            "store.mod.carry.read" -> service.carryRead(args.getString(0), args.getString(1), args.optString(2), args.optInt(3, 0), args.optInt(4, 12000))
            "store.mod.carry.write" -> service.carryWrite(args.getString(0), args.getString(1), args.getString(2))
            "store.mod.carry.edit" -> service.carryEdit(args.getString(0), args.getString(1), args.getString(2), args.getString(3), args.optBoolean(4, false))
            "store.mod.carry.copy" -> service.carryCopy(args.getString(0), args.getString(1), args.getString(2), args.getString(3))
            "store.mod.carry.delete" -> service.carryDelete(args.getString(0), args.getString(1))
            "store.mod.carry.done" -> service.carryDone(args.getString(0), args.getJSONObject(1))
            "store.details" -> service.details(args.getString(0), args.getString(1))
            "store.media" -> service.media(args.getString(0), args.getString(1), args.getString(2), args.optInt(3, 0))
            "store.installed" -> service.installed()
            "store.prepare" -> service.prepare(args.getString(0), args.getString(1), args.optBoolean(2, false))
            "store.plans" -> service.plans()
            "store.plan" -> service.plan(args.getString(0))
            "store.read" -> service.read(args.getString(0), args.getString(1), args.optInt(2, 0), args.optInt(3, 16000), args.optString(4, "candidate"))
            "store.resolve" -> service.resolve(args.getString(0), args.getString(1), args.getString(2), if (args.isNull(3)) null else args.getString(3), args.optBoolean(4, false), args.optBoolean(5, false))
            "store.apply" -> service.apply(args.getString(0), args.getString(1), args.optBoolean(2, false))
            "store.rollback" -> service.prepareRollback(args.getString(0))
            "store.review" -> service.review(args.getString(0))
            "store.submitReview" -> service.submitReview(args.getString(0), args.getString(1), args.getJSONObject(2))
            "store.rules" -> StoreRules.json()
            "store.bans" -> service.bans.list()
            "store.discard" -> service.discard(args.getString(0))
            // The owner's own decisions: each is answered in a dialog of the shell, not by the caller.
            "store.acceptWarnings" -> service.installWithWarnings(args.getString(0), args.getString(1), StoreOwner::installDespiteWarnings)
            "store.unban" -> service.unban(args.getString(0), StoreOwner::liftBan)
            else -> throw IllegalArgumentException("Неизвестная операция магазина: $method")
        }
    }
}
