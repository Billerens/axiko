package app.assi.store

import org.apache.commons.compress.archivers.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Locale
import java.util.UUID

/** Downloaded code is inspected and staged, never evaluated. Module data is outside every destination. */
class StoreInstaller(
    private val workspace: File,
    private val stateDir: File,
    private val snapshot: (String) -> String = { "" },
    private val checkpoint: (String) -> Unit = {},
) {
    private val plansDir = File(stateDir, "plans")
    private val releases = File(stateDir, "releases")
    private val registryFile = File(stateDir, "installed.json")
    private val journal = File(stateDir, "install-transaction.json")
    private val reserved = setOf("games", "store", "engine", "daily", "links", "template")
    private val binaries = setOf("apk", "dex", "jar", "aar", "class", "so", "dll", "exe", "wasm", "zip")

    init {
        require(workspace.isAbsolute && stateDir.isAbsolute) { "Установщику нужны абсолютные рабочие каталоги." }
        require(workspace.isDirectory || workspace.mkdirs())
        require(stateDir.isDirectory || stateDir.mkdirs())
        require(!stateDir.canonicalFile.toPath().startsWith(workspace.canonicalFile.toPath())) { "Реестр магазина должен находиться вне рабочей папки." }
        plansDir.mkdirs(); releases.mkdirs(); recover()
    }

    @Synchronized
    fun prepare(sourceId: String, entry: JSONObject, manifestBytes: ByteArray, archiveFile: File): JSONObject = prepareInternal(sourceId, entry, manifestBytes, archiveFile, false)

    private fun prepareInternal(sourceId: String, entry: JSONObject, manifestBytes: ByteArray, archiveFile: File, rollback: Boolean): JSONObject {
        require(sourceId.isNotEmpty() && sourceId.length <= 256 && sourceId.none { it.code < 32 }) { "Некорректный источник." }
        StoreFormat.packageEntry(entry)
        val manifest = StoreFormat.manifest(manifestBytes)
        StoreFormat.compatible(entry, manifest)
        val id = manifest.getString("id"); val kind = manifest.getString("kind")
        require(id !in reserved && !id.contains("..") && !id.endsWith('.') && !id.endsWith('-')) { "Зарезервированный или неоднозначный ID пакета." }
        val target = if (kind == "game") "modules/games/$id" else "modules/$id"
        val live = targetFile(target)
        val key = StoreFormat.sha256((sourceId + "\n" + id).toByteArray())
        val registry = registry(); val old = registry.optJSONObject(key)
        for (name in registry.keys()) require(name == key || registry.getJSONObject(name).getString("target") != target) { "Пакет другого источника уже занимает этот путь." }
        require(old != null || !Files.exists(live.toPath(), NOFOLLOW_LINKS)) { "Здесь уже есть локальный модуль без исходной версии магазина. Его файлы не будут заменены." }
        if (old != null) {
            require(old.getString("publisher") == manifest.getString("publisher")) { "Издатель установленного пакета изменился." }
            val compared = StoreFormat.compareVersions(manifest.getString("version"), old.getString("version"))
            require(rollback || compared >= 0) { "Источник предлагает более старую версию. Для отката используйте сохранённый выпуск." }
            require(rollback || compared != 0 || entry.getJSONObject("archive").getString("sha256") == old.getString("artifactSha256")) { "Архив той же версии изменился. Нужен новый номер выпуска." }
        }
        val hash = entry.getJSONObject("archive").getString("sha256")
        val release = verifyRelease(archiveFile, entry, manifestBytes)
        val incoming = File(release, "payload")
        val base = old?.let { baseDirectory(it) }
        val localFiles = fileHashes(live)
        val baseFiles = if (base != null) fileHashes(base) else emptyMap()
        val incomingFiles = fileHashes(incoming)
        val planId = UUID.randomUUID().toString()
        val planDir = File(plansDir, planId).apply { mkdirs() }
        val candidate = File(planDir, "candidate").apply { mkdirs() }
        val changes = JSONArray(); val conflicts = JSONArray()
        try {
            val paths = (baseFiles.keys + localFiles.keys + incomingFiles.keys).sorted()
            for (path in paths) {
                val b = baseFiles[path]; val l = localFiles[path]; val n = incomingFiles[path]
                val locallyChanged = l != b
                val collision = l != b && n != b && l != n
                val chosen = when { collision -> l; l == b -> n; n == b || l == n -> l; else -> l }
                if (chosen != null) {
                    val from = if (chosen == l && Files.exists(File(live, path).toPath(), NOFOLLOW_LINKS)) File(live, path) else File(incoming, path)
                    copyFile(from, File(candidate, path))
                }
                if (b != n || l != n) changes.put(JSONObject().put("path", path).put("kind", if (collision) "conflict" else if (chosen == l && locallyChanged) "keep-local" else if (chosen == null) "delete" else if (l == null) "add" else "update").put("localModified", locallyChanged))
                if (collision) conflicts.put(JSONObject().put("path", path).put("baseSha", b ?: "missing").put("localSha", l ?: "missing").put("incomingSha", n ?: "missing")
                    .put("baseHash", b ?: "missing").put("localHash", l ?: "missing").put("incomingHash", n ?: "missing").put("resolved", false))
            }
            fileHashes(candidate) // Includes local extras: no links, path collisions or oversized files.
            val screen = runCatching { validateRuntime(candidate, manifest) }.getOrDefault("")
            val plan = JSONObject().put("id", planId).put("sourceId", sourceId).put("packageId", id).put("installationKey", key)
                .put("title", entry.getString("title")).put("kind", kind).put("version", manifest.getString("version"))
                .put("publisher", manifest.getString("publisher")).put("target", target).put("entryPath", if (screen.isEmpty()) "" else "$target/$screen")
                .put("manifest", manifest).put("catalogEntry", JSONObject(entry.toString())).put("artifactSha256", hash)
                .put("manifestSha256", StoreFormat.sha256(StoreFormat.canonical(manifest).toByteArray()))
                .put("runtimeProfile", "trustedLegacy").put("capabilitiesEnforced", false).put("createdAt", System.currentTimeMillis())
                .put("liveSignature", signature(localFiles)).put("incomingSignature", signature(incomingFiles)).put("baseRecordDigest", old?.let { StoreFormat.sha256(StoreFormat.canonical(it).toByteArray()) } ?: "missing")
                .put("changes", changes).put("conflicts", conflicts).put("rollback", rollback)
                .put("findings", readJson(File(release, "analysis.json")).getJSONArray("findings"))
            if (old != null) plan.put("previousRecord", old)
            refreshPlan(plan, candidate)
            return publicPlan(plan)
        } catch (error: Exception) { deleteTree(planDir); throw error }
    }

    @Synchronized fun plan(planId: String): JSONObject = publicPlan(loadPlan(planId).also(::verifyCandidate))
    @Synchronized fun plans(): JSONArray = JSONArray(plansDir.listFiles().orEmpty().filter { it.isDirectory && File(it, "plan.json").isFile }.mapNotNull { runCatching { publicPlan(readJson(File(it, "plan.json"))) }.getOrNull() }.sortedByDescending { it.optLong("createdAt") })

    @Synchronized
    fun read(planId: String, path: String, offset: Int = 0, limit: Int = 16000, side: String = "candidate"): JSONObject {
        require(offset >= 0 && limit in 1..64000) { "Недопустимый диапазон чтения." }
        val plan = loadPlan(planId); verifyCandidate(plan)
        StoreFormat.path(path)
        require(side in setOf("candidate", "incoming", "base", "local")) { "Неизвестная сторона сравнения." }
        val installedPath = virtualToInstalled(path)
        val file = if (installedPath == null) {
            require(side == "candidate" || side == "incoming") { "Эти метаданные есть только в новом пакете." }
            require(path in setOf("package.json", "README.md", "LICENSE")) { "Файл вне пакета." }
            File(File(releases, plan.getString("artifactSha256")), "archive/$path")
        } else when (side) {
            "candidate" -> File(candidate(planId), installedPath)
            "incoming" -> File(File(releases, plan.getString("artifactSha256")), "payload/$installedPath")
            "base" -> File(baseDirectory(plan.optJSONObject("previousRecord") ?: error("Предыдущего пакета нет.")), installedPath)
            else -> File(targetFile(plan.getString("target")), installedPath)
        }
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) { "Файла нет на стороне $side." }
        require(file.length() <= StoreFormat.MAX_FILE) { "Файл слишком велик." }
        val bytes = file.readBytes(); val text = StoreFormat.utf8(bytes)
        if (side == "candidate") {
            val records = plan.getJSONArray("reviewFiles")
            val expected = (0 until records.length()).map { records.getJSONObject(it) }.find { it.getString("path") == path }
            require(expected != null && StoreFormat.sha256(bytes) == expected.getString("sha256")) { "Файл проверки изменился после подготовки." }
        }
        require(text.none { it == '\u0000' }) { "Бинарный файл нельзя читать как текст." }
        require(offset <= text.length) { "Смещение за концом файла." }
        val end = minOf(text.length, offset + limit)
        return JSONObject().put("path", path).put("side", side).put("content", text.substring(offset, end)).put("text", text.substring(offset, end))
            .put("offset", offset).put("nextOffset", end).put("totalLength", text.length).put("eof", end == text.length).put("sha256", StoreFormat.sha256(bytes))
    }

    @Synchronized
    fun resolve(planId: String, path: String, expectedHash: String, content: String? = null, keepLocal: Boolean = false): JSONObject {
        val plan = loadPlan(planId); require(plan.getString("status") != "applied") { "План уже установлен." }; verifyCandidate(plan); verifyLive(plan)
        StoreFormat.path(path)
        val conflicts = plan.getJSONArray("conflicts")
        val conflict = (0 until conflicts.length()).map { conflicts.getJSONObject(it) }.find { it.getString("path") == path } ?: error("Конфликт не найден.")
        require(expectedHash == conflict.getString("localSha")) { "Локальная правка изменилась. Подготовьте новый план." }
        val target = File(candidate(planId), path)
        if (keepLocal) {
            val live = File(targetFile(plan.getString("target")), path)
            if (live.isFile) copyFile(live, target) else if (target.exists()) require(target.delete())
        } else {
            require(content != null) { "Нужен объединённый текст или сохранение локальной версии." }
            val bytes = content.toByteArray(); require(bytes.size <= StoreFormat.MAX_FILE) { "Объединённый файл слишком велик." }; scanSource(path, bytes, JSONArray())
            requireNotNull(target.parentFile).mkdirs(); target.writeBytes(bytes)
        }
        conflict.put("resolved", true).put("resolution", if (keepLocal) "keep-local" else "merged")
        refreshPlan(plan, candidate(planId)); return publicPlan(plan)
    }

    @Synchronized
    fun apply(planId: String, expectedDigest: String): JSONObject {
        recover()
        val plan = loadPlan(planId)
        require(plan.getString("status") == "ready") { "Сначала разрешите конфликты обновления." }
        require(plan.getString("digest") == expectedDigest) { "План изменился после проверки." }
        verifyCandidate(plan); verifyLive(plan); checkDependencies(plan)
        val target = targetFile(plan.getString("target")); val screen = validateRuntime(candidate(planId), plan.getJSONObject("manifest"))
        val tx = UUID.randomUUID().toString(); val stage = transactionDir("stage", tx); val previous = transactionDir("previous", tx)
        copyTree(candidate(planId), stage)
        require(signature(fileHashes(stage)) == plan.getString("candidateSignature")) { "Подготовленная копия изменилась." }
        val registry = registry(); val key = plan.getString("installationKey"); val old = registry.optJSONObject(key)
        val record = JSONObject().put("key", key).put("sourceId", plan.getString("sourceId")).put("id", plan.getString("packageId"))
            .put("title", plan.getString("title")).put("kind", plan.getString("kind")).put("version", plan.getString("version"))
            .put("publisher", plan.getString("publisher")).put("target", plan.getString("target")).put("entryPath", if (screen.isEmpty()) "" else "${plan.getString("target")}/$screen")
            .put("artifactSha256", plan.getString("artifactSha256")).put("manifest", plan.getJSONObject("manifest")).put("catalogEntry", plan.getJSONObject("catalogEntry"))
            .put("runtimeProfile", "trustedLegacy").put("capabilitiesEnforced", false).put("installedAt", System.currentTimeMillis())
            .put("baseFiles", JSONObject(fileHashes(File(File(releases, plan.getString("artifactSha256")), "payload")))).put("transactionId", tx)
        if (old != null) record.put("previousRelease", JSONObject().put("artifactSha256", old.getString("artifactSha256")).put("catalogEntry", old.getJSONObject("catalogEntry")).put("version", old.getString("version")))
        try {
            val snapshotId = snapshot("Перед ${if (plan.optBoolean("rollback")) "откатом" else "установкой"} ${plan.getString("title")} ${plan.getString("version")}")
            verifyLive(plan); verifyCandidate(plan)
            val data = JSONObject().put("tx", tx).put("planId", planId).put("target", plan.getString("target")).put("key", key).put("phase", "prepared").put("newRecord", record).put("oldRecord", old ?: JSONObject.NULL).put("oldSignature", plan.getString("liveSignature"))
            atomicJson(journal, data)
            checkpoint("prepared")
            requireNotNull(target.parentFile).mkdirs()
            if (Files.exists(target.toPath(), NOFOLLOW_LINKS)) {
                move(target, previous)
                require(signature(fileHashes(previous)) == plan.getString("liveSignature")) { "Локальный файл изменился в момент установки. Изменение сохранено, операция отменена." }
            }
            checkpoint("previous-moved")
            move(stage, target)
            data.put("phase", "swapped"); atomicJson(journal, data)
            checkpoint("swapped")
            registry.put(key, record); atomicJson(registryFile, registry)
            checkpoint("registry-written")
            data.put("phase", "committed"); atomicJson(journal, data)
            checkpoint("committed")
            deleteTree(previous); journal.delete()
            plan.put("status", "applied").put("snapshotId", snapshotId).put("appliedAt", System.currentTimeMillis()); plan.put("digest", planDigest(plan)); savePlan(plan)
            return JSONObject().put("ok", true).put("status", "applied").put("id", planId).put("installationKey", key).put("snapshotId", snapshotId)
                .put("entryPath", record.getString("entryPath")).put("dataPreserved", true).put("localModified", !sameFiles(fileHashes(target), fileHashes(baseDirectory(record))))
                .put("runtimeProfile", "trustedLegacy").put("restartRequired", true)
        } catch (error: Exception) {
            if (journal.exists()) runCatching { recover() }.exceptionOrNull()?.let(error::addSuppressed) else deleteTree(stage)
            throw error
        }
    }

    @Synchronized fun installed(): JSONArray = JSONArray(registry().let { registry -> registry.keys().asSequence().map { key ->
        val record = JSONObject(registry.getJSONObject(key).toString())
        try {
            val local = fileHashes(targetFile(record.getString("target"))); val base = fileHashes(baseDirectory(record))
            val changed = (local.keys + base.keys).filter { local[it] != base[it] }.sorted()
            record.put("localModified", changed.isNotEmpty()).put("modifiedFiles", JSONArray(changed)).put("liveDigest", signature(local)).put("available", local.isNotEmpty())
        } catch (error: Exception) {
            record.put("localModified", true).put("modifiedFiles", JSONArray()).put("liveDigest", "invalid").put("localError", error.message ?: "Не удалось проверить локальные файлы.").put("available", false)
        }
        record.put("rollbackAvailable", record.has("previousRelease"))
        if (record.has("previousRelease")) record.put("previousVersion", record.getJSONObject("previousRelease").getString("version"))
        record
    }.toList() })

    @Synchronized fun prepareRollback(packageKey: String): JSONObject {
        StoreFormat.hash(packageKey)
        val record = registry().optJSONObject(packageKey) ?: error("Установленный пакет не найден.")
        val previous = record.optJSONObject("previousRelease") ?: error("Предыдущего выпуска для отката нет.")
        val release = File(releases, StoreFormat.hash(previous.getString("artifactSha256")))
        return prepareInternal(record.getString("sourceId"), previous.getJSONObject("catalogEntry"), File(release, "archive/package.json").readBytes(), File(release, "package.zip"), true)
    }

    /** Discards staging only. Installed code, its rollback base, user data and review reports are separate. */
    @Synchronized fun discardPlan(planId: String): JSONObject {
        val plan = loadPlan(planId)
        deleteTree(File(plansDir, token(planId)))
        val referenced = HashSet<String>()
        val installed = registry()
        for (key in installed.keys()) {
            val record = installed.getJSONObject(key); referenced.add(record.getString("artifactSha256"))
            record.optJSONObject("previousRelease")?.let { referenced.add(it.getString("artifactSha256")) }
        }
        plansDir.listFiles().orEmpty().filter { it.isDirectory && File(it, "plan.json").isFile }.forEach { directory ->
            referenced.add(readJson(File(directory, "plan.json")).getString("artifactSha256"))
        }
        val hash = plan.getString("artifactSha256")
        if (hash !in referenced) deleteTree(File(releases, StoreFormat.hash(hash)))
        return JSONObject().put("ok", true).put("id", planId).put("dataPreserved", true)
    }

    @Synchronized fun recover() {
        if (!journal.exists()) return
        val data = readJson(journal); val tx = token(data.getString("tx")); val target = targetFile(data.getString("target"))
        val stage = transactionDir("stage", tx); val previous = transactionDir("previous", tx); val key = StoreFormat.hash(data.getString("key"))
        val registry = registry()
        if (data.getString("phase") == "committed") {
            require(target.isDirectory) { "Не удалось восстановить завершённую установку магазина." }
            registry.put(key, data.getJSONObject("newRecord")); atomicJson(registryFile, registry)
            if (data.has("planId")) {
                val completed = loadPlan(data.getString("planId"))
                completed.put("status", "applied").put("appliedAt", System.currentTimeMillis()).put("recovered", true)
                completed.put("digest", planDigest(completed)); savePlan(completed)
            }
        } else {
            if (previous.exists()) { deleteTree(target); move(previous, target) }
            else if (data.isNull("oldRecord") && !stage.exists()) deleteTree(target)
            else if (!data.isNull("oldRecord")) require(signature(fileHashes(target)) == data.getString("oldSignature")) { "Нельзя подтвердить прежнюю версию после сбоя; файлы оставлены для восстановления." }
            if (data.isNull("oldRecord")) registry.remove(key) else registry.put(key, data.getJSONObject("oldRecord"))
            atomicJson(registryFile, registry)
        }
        deleteTree(stage); deleteTree(previous); require(journal.delete()) { "Не удалось очистить журнал установки." }
    }

    private fun verifyRelease(archive: File, entry: JSONObject, manifestBytes: ByteArray): File {
        val expected = entry.getJSONObject("archive"); val hash = expected.getString("sha256")
        require(archive.isFile && archive.length() == expected.getLong("size") && archive.length() <= StoreFormat.MAX_ARCHIVE) { "Размер скачанного архива не совпадает с индексом." }
        require(StoreFormat.sha256(archive) == hash) { "SHA-256 скачанного архива не совпадает с индексом." }
        val manifest = StoreFormat.manifest(manifestBytes)
        val release = File(releases, hash)
        val fresh = File(releases, ".unpack-${UUID.randomUUID()}").apply { mkdirs() }
        val archiveRoot = File(fresh, "archive").apply { mkdirs() }; val payload = File(fresh, "payload").apply { mkdirs() }
        val files = manifest.getJSONArray("files"); val declared = (0 until files.length()).associate { files.getJSONObject(it).getString("path") to files.getJSONObject(it) }
        val found = ArrayList<String>(); val directoryNames = HashSet<String>(); val findings = JSONArray(); var total = 0L
        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries
                var entryCount = 0
                while (entries.hasMoreElements()) {
                    require(++entryCount <= 4096) { "Слишком много служебных записей ZIP." }
                    val item = entries.nextElement(); val name = item.name
                    require(!item.isUnixSymlink && (item.unixMode == 0 || item.unixMode and 0xf000 in setOf(0, 0x8000, 0x4000))) { "Ссылки и специальные файлы в ZIP запрещены." }
                    require(!item.generalPurposeBit.usesEncryption() && zip.canReadEntryData(item)) { "Зашифрованный или неподдерживаемый ZIP." }
                    if (item.isDirectory) {
                        val p = StoreFormat.path(name.removeSuffix("/")); require(p == "code" || p == "assets" || p.startsWith("code/") || p.startsWith("assets/"))
                        require(directoryNames.add(p.lowercase(Locale.ROOT))) { "Повторяющийся каталог ZIP." }; continue
                    }
                    StoreFormat.path(name); require(name == "package.json" || declared.containsKey(name)) { "Необъявленный файл в архиве: $name." }
                    require(found.size < StoreFormat.MAX_FILES && name !in found) { "Повторяющийся файл или слишком много файлов ZIP." }
                    val ceiling = if (name == "package.json") StoreFormat.MAX_METADATA else StoreFormat.MAX_FILE
                    val buffer = java.io.ByteArrayOutputStream(); val bytes = ByteArray(16384)
                    zip.getInputStream(item).use { input -> while (true) { val count = input.read(bytes); if (count < 0) break; require(buffer.size() + count <= ceiling && total + count <= StoreFormat.MAX_UNPACKED) { "Превышен лимит распаковки." }; buffer.write(bytes, 0, count); total += count } }
                    val content = buffer.toByteArray()
                    if (name == "package.json") require(StoreFormat.canonical(StoreFormat.manifest(content)) == StoreFormat.canonical(manifest)) { "Манифест в ZIP отличается от проверенного." }
                    else { val record = declared.getValue(name); require(content.size.toLong() == record.getLong("size") && StoreFormat.sha256(content) == record.getString("sha256")) { "Не совпали размер или хеш файла $name." } }
                    scanSource(name, content, findings)
                    File(archiveRoot, name).apply { requireNotNull(parentFile).mkdirs(); writeBytes(content) }; found.add(name)
                }
            }
            StoreFormat.uniquePaths(found)
            require(found.toSet() == declared.keys + "package.json" && found.size == expected.getInt("fileCount") && total == expected.getLong("unpackedSize")) { "Состав или распакованный размер ZIP не совпал с индексом." }
            val mapped = declared.keys.mapNotNull(::virtualToInstalled); StoreFormat.uniquePaths(mapped)
            for (path in declared.keys) virtualToInstalled(path)?.let { copyFile(File(archiveRoot, path), File(payload, it)) }
            validateRuntime(payload, manifest)
            if (manifest.getString("kind") == "module" && StoreFormat.parse(File(payload, "module.json").readBytes()).optBoolean("autostart")) {
                findings.put(JSONObject().put("code", "autostart").put("path", "code/module.json").put("message", "Модуль просит фоновый запуск при следующем старте Assi."))
            }
            atomicJson(File(fresh, "analysis.json"), JSONObject().put("findings", findings))
            copyFile(archive, File(fresh, "package.zip"))
            if (release.exists()) {
                // A cached base is not trusted merely because its directory is named by a digest.
                require(fileHashes(File(release, "payload")) == fileHashes(payload) && fileHashes(File(release, "archive")) == fileHashes(archiveRoot) && StoreFormat.sha256(File(release, "package.zip")) == hash) { "Сохранённый исходный выпуск повреждён." }
                atomicJson(File(release, "analysis.json"), JSONObject().put("findings", findings))
                deleteTree(fresh)
            } else move(fresh, release)
            return release
        } catch (error: Exception) { deleteTree(fresh); throw error }
    }

    private fun validateRuntime(payload: File, manifest: JSONObject): String {
        val game = manifest.getString("kind") == "game"
        val file = File(payload, if (game) "game.json" else "module.json")
        require(file.isFile) { "В пакете отсутствует ${file.name}." }
        val runtime = StoreFormat.parse(file.readBytes())
        require(runtime.optString("id") == manifest.getString("id")) { "ID runtime должен совпадать с ID пакета; существующие данные не переименовываются." }
        val entry = manifest.getString("entry").removePrefix("code/")
        require(File(payload, entry).isFile) { "Нет точки входа пакета." }
        val ownPrefix = "modules/${if (game) "games/" else ""}${manifest.getString("id")}/"
        if (runtime.has("entry")) {
            val screen = runtime.getString("entry").removePrefix(ownPrefix)
            StoreFormat.path(screen)
            require(File(payload, screen).isFile && screen.substringAfterLast('.').lowercase(Locale.ROOT) in setOf("html", "htm")) { "Экран runtime должен быть HTML-файлом своего пакета." }
            return screen
        }
        require(!game) { "В game.json должен быть указан HTML-экран игры." }
        return when {
            File(payload, "ui/index.html").isFile -> "ui/index.html"
            entry.substringAfterLast('.').lowercase(Locale.ROOT) in setOf("html", "htm") -> entry
            else -> ""
        }
    }

    private fun scanSource(name: String, bytes: ByteArray, findings: JSONArray) {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(extension !in binaries && !(bytes.size >= 4 && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() && bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte())) { "Бинарный исполняемый файл запрещён: $name." }
        if (extension !in setOf("js", "mjs", "css", "html", "htm")) return
        val source = StoreFormat.utf8(bytes)
        require(!Regex("(?i)\\.min\\.(js|mjs|css)$").containsMatchIn(name)) { "Минифицированный файл запрещён: $name." }
        require(!Regex("eval\\s*\\(\\s*(?:atob|unescape|String\\.fromCharCode)|eval\\s*\\(function\\(p,a,c,k,e", RegexOption.IGNORE_CASE).containsMatchIn(source)) { "Обнаружен упакованный исполняемый код: $name." }
        val codeWithoutData = codeWithoutLiterals(source)
        require(codeWithoutData.lineSequence().none { it.length > 1500 && it.count { c -> c == ';' } > 15 }) { "Исходник похож на минифицированный: $name. Поставьте читаемую версию." }
        require(!Regex("(?i)<script[^>]+src\\s*=\\s*[\"']?(?:https?:)?//|(?:import\\s+[^;]*?from|import\\s*\\(|importScripts\\s*\\(|new\\s+(?:Worker|SharedWorker)\\s*\\()\\s*[\"'](?:https?:)?//|@import\\s+(?:url\\(\\s*)?[\"']?(?:https?:)?//").containsMatchIn(source)) { "Удалённый исполняемый код запрещён: $name." }
        if (Regex("\\b(?:Packages|java|context)\\b|native\\s*\\(|host\\.").containsMatchIn(codeWithoutData)) findings.put(JSONObject().put("code", "privileged-runtime").put("path", name).put("message", "Код обращается к возможностям приложения; изоляция прав пока не обеспечивается."))
        if (Regex("https?://|fetch\\s*\\(|XMLHttpRequest|\\.http\\.").containsMatchIn(source)) findings.put(JSONObject().put("code", "network-use").put("path", name).put("message", "Нужно проверить сетевые адреса и передаваемые данные."))
        if (Regex("\\beval\\s*\\(|\\bnew\\s+Function\\s*\\(").containsMatchIn(codeWithoutData)) findings.put(JSONObject().put("code", "dynamic-code").put("path", name).put("message", "Нужно проверить происхождение динамически выполняемого кода."))
    }

    /** Lexical masking for readability heuristics only, not a JavaScript parser or security boundary.
     * Iteration avoids JVM regex recursion on large readable strings such as inline SVG or templates.
     * Unterminated strings/comments remain visible, matching the former complete-literal rule.
     */
    private fun codeWithoutLiterals(source: String): String {
        val result = StringBuilder(source.length)
        var position = 0
        while (position < source.length) {
            val start = position
            val current = source[position]
            if (current == '/' && position + 1 < source.length && source[position + 1] == '/') {
                position += 2
                while (position < source.length && source[position] != '\r' && source[position] != '\n') position++
                result.append(' ')
            } else if (current == '/' && position + 1 < source.length && source[position + 1] == '*') {
                val end = source.indexOf("*/", position + 2)
                if (end < 0) { result.append(source, start, source.length); break }
                position = end + 2
                result.append(' ')
            } else if (current == '\"' || current == '\'' || current == '`') {
                position++
                var closed = false
                while (position < source.length) {
                    val next = source[position++]
                    if (next == '\\') {
                        if (position < source.length) position++
                    } else if (next == current) {
                        closed = true
                        break
                    }
                }
                if (!closed) { result.append(source, start, source.length); break }
                result.append(' ')
            } else {
                result.append(current)
                position++
            }
        }
        return result.toString()
    }

    private fun refreshPlan(plan: JSONObject, candidate: File) {
        val hashes = fileHashes(candidate); val conflicts = plan.getJSONArray("conflicts")
        val unresolved = (0 until conflicts.length()).count { !conflicts.getJSONObject(it).optBoolean("resolved") }
        plan.put("candidateSignature", signature(hashes)).put("status", if (unresolved == 0) "ready" else "conflicts")
        val review = JSONArray()
        val manifestFiles = plan.getJSONObject("manifest").getJSONArray("files")
        val virtuals = (0 until manifestFiles.length()).map { manifestFiles.getJSONObject(it).getString("path") }.mapNotNull { name -> virtualToInstalled(name)?.let { it to name } }.toMap()
        hashes.forEach { (path, hash) -> val virtual = virtuals[path] ?: "code/$path"; review.put(JSONObject().put("path", virtual).put("sha256", hash).put("size", File(candidate, path).length()).put("executable", path.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("js", "mjs", "html", "htm", "css") || path in setOf("module.json", "game.json", "config.json"))) }
        for (path in listOf("package.json", "README.md", "LICENSE")) { val file = File(File(releases, plan.getString("artifactSha256")), "archive/$path"); review.put(JSONObject().put("path", path).put("sha256", StoreFormat.sha256(file)).put("size", file.length()).put("executable", path == "package.json")) }
        plan.put("reviewFiles", review).put("files", review)
        plan.put("digest", planDigest(plan)); savePlan(plan)
    }
    private fun planDigest(plan: JSONObject): String = StoreFormat.sha256(StoreFormat.canonical(JSONObject(plan.toString()).apply { remove("digest"); remove("snapshotId"); remove("appliedAt") }).toByteArray())
    private fun verifyCandidate(plan: JSONObject) {
        require(plan.getString("digest") == planDigest(plan)) { "Метаданные плана изменены после подготовки." }
        require(signature(fileHashes(candidate(plan.getString("id")))) == plan.getString("candidateSignature")) { "Подготовленные файлы изменились после проверки." }
        require(signature(fileHashes(File(File(releases, plan.getString("artifactSha256")), "payload"))) == plan.getString("incomingSignature")) { "Исходный выпуск изменился после подготовки." }
    }
    private fun verifyLive(plan: JSONObject) { val old = registry().optJSONObject(plan.getString("installationKey")); require((old?.let { StoreFormat.sha256(StoreFormat.canonical(it).toByteArray()) } ?: "missing") == plan.getString("baseRecordDigest")) { "Установленная версия изменилась. Подготовьте новый план." }; require(signature(fileHashes(targetFile(plan.getString("target")))) == plan.getString("liveSignature")) { "Локальные файлы изменились после подготовки. Новые правки сохранены; подготовьте план заново." } }
    private fun checkDependencies(plan: JSONObject) { val registry = registry(); val dependencies = plan.getJSONObject("manifest").getJSONArray("dependencies"); for (i in 0 until dependencies.length()) { val d = dependencies.getJSONObject(i); val key = StoreFormat.sha256((plan.getString("sourceId") + "\n" + d.getString("id")).toByteArray()); val installed = registry.optJSONObject(key); require(installed != null && StoreFormat.versionMatches(installed.getString("version"), d.getString("version"))) { "Не установлена совместимая зависимость ${d.getString("id")}." } } }
    private fun publicPlan(plan: JSONObject): JSONObject = JSONObject(plan.toString()).apply { remove("previousRecord") }
    private fun candidate(id: String): File = File(File(plansDir, token(id)), "candidate")
    private fun loadPlan(id: String): JSONObject = readJson(File(File(plansDir, token(id)), "plan.json"))
    private fun savePlan(plan: JSONObject) = atomicJson(File(File(plansDir, token(plan.getString("id"))), "plan.json"), plan)
    private fun registry(): JSONObject = if (registryFile.exists()) readJson(registryFile) else JSONObject()
    private fun readJson(file: File): JSONObject = StoreFormat.parse(file.readBytes(), 8 * StoreFormat.MAX_METADATA)
    private fun baseDirectory(record: JSONObject): File { val directory = File(File(releases, StoreFormat.hash(record.getString("artifactSha256"))), "payload"); val declared = record.getJSONObject("baseFiles"); require(fileHashes(directory) == declared.keys().asSequence().associateWith { declared.getString(it) }) { "Исходная версия установленного пакета повреждена." }; return directory }
    private fun virtualToInstalled(path: String): String? = when { path.startsWith("code/") -> path.removePrefix("code/"); path.startsWith("assets/") -> path; else -> null }
    private fun token(value: String): String { require(Regex("[a-f0-9-]{36}").matches(value)) { "Некорректный ID операции." }; return value }
    private fun targetFile(path: String): File { require(Regex("modules/(?:games/)?[a-z0-9][a-z0-9.-]{0,95}").matches(path) && !path.contains("..")) { "Недопустимый путь установки." }; val target = File(workspace, path); var parent: File? = target; while (parent != null && parent != workspace.parentFile) { require(!Files.isSymbolicLink(parent.toPath())) { "Ссылки в пути установки запрещены." }; parent = parent.parentFile }; require(target.canonicalFile.toPath().startsWith(workspace.canonicalFile.toPath())) ; return target }
    private fun transactionDir(kind: String, token: String): File = File(workspace.parentFile, ".assi-store-$kind-${token(token)}")
    private fun signature(files: Map<String, String>): String = StoreFormat.sha256(files.toSortedMap().entries.joinToString("\n") { "${it.key}\t${it.value}" }.toByteArray())
    private fun sameFiles(a: Map<String, String>, b: Map<String, String>) = a == b
    private fun fileHashes(root: File): Map<String, String> { if (!Files.exists(root.toPath(), NOFOLLOW_LINKS)) return emptyMap(); require(root.isDirectory && !Files.isSymbolicLink(root.toPath())); val result = sortedMapOf<String, String>(); var total = 0L; root.walkTopDown().forEach { file -> require(!Files.isSymbolicLink(file.toPath())) { "Ссылка в файлах пакета: ${file.name}." }; if (file.isFile) { val path = file.relativeTo(root).invariantSeparatorsPath; StoreFormat.path(path); require(file.length() <= StoreFormat.MAX_FILE && result.size < StoreFormat.MAX_FILES) { "Локальная версия превышает лимит файлов магазина." }; total += file.length(); require(total <= StoreFormat.MAX_UNPACKED) { "Локальная версия превышает лимит размера магазина." }; result[path] = StoreFormat.sha256(file) } else require(file.isDirectory) }; StoreFormat.uniquePaths(result.keys); return result }
    private fun copyTree(from: File, to: File) { require(!to.exists()); require(to.mkdirs()); fileHashes(from).keys.forEach { copyFile(File(from, it), File(to, it)) } }
    private fun copyFile(from: File, to: File) { require(Files.isRegularFile(from.toPath(), NOFOLLOW_LINKS)) { "Источник не является обычным файлом." }; val parent = requireNotNull(to.parentFile); require(parent.mkdirs() || parent.isDirectory) { "Файл конфликтует с каталогом назначения." }; FileOutputStream(to).use { output -> from.inputStream().use { it.copyTo(output) }; output.fd.sync() } }
    private fun move(from: File, to: File) { try { Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE) } catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(from.toPath(), to.toPath()) } }
    private fun atomicJson(file: File, json: JSONObject) { requireNotNull(file.parentFile).mkdirs(); val temp = File(file.parentFile, file.name + ".tmp"); FileOutputStream(temp).use { it.write(json.toString().toByteArray()); it.fd.sync() }; try { Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) } catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp.toPath(), file.toPath(), REPLACE_EXISTING) } }
    private fun deleteTree(root: File) { if (!Files.exists(root.toPath(), NOFOLLOW_LINKS)) return; if (Files.isSymbolicLink(root.toPath()) || !root.isDirectory) { require(root.delete()); return }; root.listFiles().orEmpty().forEach(::deleteTree); require(root.delete()) }
}
