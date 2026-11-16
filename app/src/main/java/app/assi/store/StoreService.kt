package app.assi.store

import app.assi.core.Workspace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Catalog, download and review coordination. No package code is executed by this service. */
class StoreService(
    workspace: File,
    private val stateDir: File,
    transport: StoreTransport = StoreHttpTransport(),
    snapshot: (String) -> String = { "" },
) {
    val sources = StoreSources(stateDir, transport)
    val installer = StoreInstaller(workspace, stateDir, snapshot)
    private val envelopes = File(stateDir, "subjects")
    private val reviews = File(stateDir, "reviews")
    private val downloads = File(stateDir, "downloads")
    private val operationLock = Any()

    /** Called at application startup before editable boot scripts. Constructor recovers the installer. */
    fun recover() = Unit // StoreInstaller's constructor already replays an interrupted transaction.

    fun catalog(): JSONArray {
        val out = JSONArray()
        val installed = objects(installer.installed())
        val sourceList = sources.list()
        for (index in 0 until sourceList.length()) {
            val source = sourceList.getJSONObject(index)
            if (!source.optBoolean("enabled", true)) continue
            val entries = try { sources.cached(source.getString("id")).getJSONArray("packages") } catch (_: Exception) { continue }
            for (number in 0 until entries.length()) {
                val entry = StoreDisk.copy(entries.getJSONObject(number))
                val sameId = installed.firstOrNull { it.getString("id") == entry.getString("id") }
                val own = installed.firstOrNull { it.getString("id") == entry.getString("id") && it.getString("sourceId") == source.getString("id") }
                var error = compatibility(entry.getJSONObject("compatibility"), false, entry.getString("kind"))
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
                }
                if (error != null && status == "available") status = "incompatible"
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

    fun installed(): JSONArray {
        val result = installer.installed()
        val known = objects(sources.list()).associateBy { it.getString("id") }
        for (i in 0 until result.length()) {
            val record = result.getJSONObject(i)
            val source = known[record.getString("sourceId")]
            record.put("sourceTitle", source?.optString("title") ?: "Удалённый источник")
                .put("sourceEnabled", source?.optBoolean("enabled", false) ?: false)
                .put("sourceAvailable", source != null)
        }
        return result
    }

    fun prepare(sourceId: String, packageId: String): JSONObject {
        val source = sources.source(sourceId, true)
        val entry = sources.packageEntry(sourceId, packageId)
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
        require(response.bytes.size.toLong() == archive.getLong("size")) { "Размер архива отличается от index.json." }
        require(StoreFormat.sha256(response.bytes) == archive.getString("sha256")) { "SHA-256 архива не совпадает с index.json." }
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
                val result = installer.prepare(sourceId, entry, manifestResponse.bytes, file)
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
        objects(installer.plans()).forEach { item ->
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

    fun resolve(id: String, path: String, expectedHash: String, content: String?, keepLocal: Boolean): JSONObject = synchronized(operationLock) {
        subject(id)
        val result = installer.resolve(id, path, expectedHash, content, keepLocal)
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

    fun apply(id: String, expectedDigest: String, acceptTrustedCode: Boolean): JSONObject = synchronized(operationLock) {
        require(acceptTrustedCode) { "Подтвердите установку кода с доступом Аксико или поручите проверку агенту." }
        val current = plan(id)
        require(current.optString("status") != "applied") { "Этот план уже установлен. Для новых изменений подготовьте новый план." }
        require(current.getString("digest") == expectedDigest) { "План изменился. Проверьте изменения перед установкой." }
        require(current.getJSONArray("blockers").length() == 0) { current.getJSONArray("blockers").getString(0) }
        val report = review(id)
        require(report.optString("status") != "reject") { "Проверка отклонила этот план. Исправьте пакет и подготовьте новую проверку." }
        // Persist the reviewed subject before the core transaction. If the process
        // dies after commit, core recovery marks this same immutable plan applied;
        // the receipt still identifies its historical review, not a new approval.
        val envelope = subject(id).put("applyReceipt", JSONObject()
            .put("planDigest", expectedDigest).put("installerDigest", current.getString("installerDigest"))
            .put("artifactSha256", current.getString("artifactSha256")))
        StoreDisk.write(subjectFile(id), envelope)
        val result = installer.apply(id, current.getString("installerDigest"))
        result.put("dataPreserved", true).put("runtimeProfile", "trustedLegacy").put("capabilitiesEnforced", false)
            .put("packageCodeStarted", false)
    }

    fun review(id: String): JSONObject = synchronized(operationLock) {
        val current = plan(id)
        val file = reviewFile(id)
        if (!file.isFile) return@synchronized JSONObject().put("status", "pending").put("planDigest", current.getString("digest"))
        val report = StoreDisk.read(file)
        val receipt = subject(id).optJSONObject("applyReceipt")
        if (current.optString("status") == "applied" && receipt != null &&
            receipt.optString("planDigest") == report.optString("planDigest") &&
            receipt.optString("artifactSha256") == current.getString("artifactSha256")) {
            return@synchronized StoreDisk.copy(report).put("completed", true).put("historicalOnly", true)
                .put("appliedAt", current.optLong("appliedAt"))
        }
        if (report.optString("planDigest") != current.getString("digest")) {
            return@synchronized JSONObject().put("status", "pending").put("stale", true).put("planDigest", current.getString("digest"))
        }
        StoreDisk.copy(report)
    }

    /** Actual read coverage / owner consent are enforced by the dedicated agent tools at the caller. */
    fun submitReview(id: String, expectedDigest: String, report: JSONObject): JSONObject = synchronized(operationLock) {
        val current = plan(id)
        require(current.optString("status") != "applied") { "Проверка применённого плана уже завершена. Подготовьте новый план." }
        require(expectedDigest == current.getString("digest")) { "Проверенный план изменился. Повторите проверку." }
        require(report.optString("status") in setOf("pass", "needsAttention", "reject")) { "Некорректный статус проверки." }
        require(report.has("summary") && report.get("summary") is String && report.getString("summary").isNotBlank() && report.getString("summary").length <= 8000) { "Нужен содержательный итог проверки." }
        require(report.optJSONArray("findings") != null && report.getJSONArray("findings").length() <= 100) { "Некорректный список замечаний." }
        require(report.optJSONArray("reviewedFiles") != null && report.getJSONArray("reviewedFiles").length() <= 512) { "Укажите исследованные файлы." }
        require(report.toString().toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "Отчёт проверки слишком велик." }
        if (report.has("artifactSha256")) require(report.getString("artifactSha256") == current.getString("artifactSha256")) { "Отчёт относится к другому архиву." }
        if (report.has("planDigest")) require(report.getString("planDigest") == expectedDigest) { "Отчёт относится к другому плану." }
        val saved = StoreDisk.copy(report).put("reviewVersion", 1).put("policyVersion", 1)
            .put("planId", id).put("planDigest", expectedDigest).put("createdAt", System.currentTimeMillis())
            .put("artifactSha256", current.getString("artifactSha256"))
            .put("subject", StoreDisk.copy(current.getJSONObject("subject")))
            .put("runtimeProfile", "trustedLegacy").put("capabilitiesEnforced", false)
        StoreDisk.write(reviewFile(id), saved)
        StoreDisk.copy(saved)
    }

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
        val dependencies = dependencyState(result.getString("sourceId"), manifest, blockers)
        val coreDigest = result.getString("digest")
        val scope = JSONObject().put("core", coreDigest).put("dependencies", dependencies).put("sourceInstance", sourceInstance)
            .put("blockers", blockers).put("policy", 1)
        val digest = StoreFormat.sha256(StoreFormat.canonical(scope).toByteArray(Charsets.UTF_8))
        result.put("installerDigest", coreDigest).put("digest", digest).put("blockers", blockers)
            .put("dependencies", dependencies).put("runtimeProfile", "trustedLegacy").put("capabilitiesEnforced", false)
            .put("sourceTitle", runCatching { sources.source(raw.getString("sourceId")).getString("title") }.getOrElse { "Удалённый источник" })
        if (blockers.length() > 0 && raw.optString("status") != "applied") result.put("status", "blocked")
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

    private fun compatibility(value: JSONObject, manifest: Boolean, kind: String): String? {
        val required = value.getInt(if (manifest) "assiApi" else "minAssiApi")
        if (required > ASSI_API) return "Пакету нужен API Аксико $required; установлен $ASSI_API."
        if (kind == "game" && value.getInt("gamesEngine") != GAMES_ENGINE) return "Нужен игровой движок ${value.getInt("gamesEngine")}; установлен $GAMES_ENGINE."
        return null
    }

    private fun subject(id: String): JSONObject {
        val file = subjectFile(id)
        require(file.isFile) { "У плана нет проверенного источника. Подготовьте пакет заново." }
        return StoreDisk.read(file)
    }
    private fun subjectFile(id: String) = File(envelopes, safePlanId(id) + ".json")
    private fun reviewFile(id: String) = File(reviews, safePlanId(id) + ".json")
    private fun safePlanId(id: String): String {
        require(Regex("[a-zA-Z0-9_-]{1,128}").matches(id)) { "Некорректный план установки." }
        return id
    }
    private fun objects(array: JSONArray) = (0 until array.length()).map { array.getJSONObject(it) }

    companion object {
        const val ASSI_API = 1
        const val GAMES_ENGINE = 1
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
                current = StoreService(Workspace.root, File(Workspace.state, "store"), StoreHttpTransport()) { label -> Workspace.snapshot(label) }
                currentRoot = root
            }
            current!!
        }

    fun recover() = service.recover()

    fun call(method: String, args: JSONArray): Any? {
        val service = service
        return when (method) {
            "store.sources" -> service.sources.list()
            "store.source.add" -> service.sources.add(args.getString(0))
            "store.source.refresh" -> service.sources.refresh(args.getString(0))
            "store.source.remove" -> service.sources.remove(args.getString(0))
            "store.source.enable" -> service.sources.enable(args.getString(0), args.getBoolean(1))
            "store.catalog" -> service.catalog()
            "store.installed" -> service.installed()
            "store.prepare" -> service.prepare(args.getString(0), args.getString(1))
            "store.plans" -> service.plans()
            "store.plan" -> service.plan(args.getString(0))
            "store.read" -> service.read(args.getString(0), args.getString(1), args.optInt(2, 0), args.optInt(3, 16000), args.optString(4, "candidate"))
            "store.resolve" -> service.resolve(args.getString(0), args.getString(1), args.getString(2), if (args.isNull(3)) null else args.getString(3), args.optBoolean(4, false))
            "store.apply" -> service.apply(args.getString(0), args.getString(1), args.optBoolean(2, false))
            "store.rollback" -> service.prepareRollback(args.getString(0))
            "store.review" -> service.review(args.getString(0))
            "store.submitReview" -> service.submitReview(args.getString(0), args.getString(1), args.getJSONObject(2))
            else -> throw IllegalArgumentException("Неизвестная операция магазина: $method")
        }
    }
}
