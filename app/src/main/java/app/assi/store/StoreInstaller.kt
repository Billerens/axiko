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
    /** Which kinds of packages there are and where each lives; declared by the modules that host them. */
    private val kinds: StoreKinds = StoreKinds.workspace(workspace),
) {
    private val plansDir = File(stateDir, "plans")
    private val releases = File(stateDir, "releases")
    private val registryFile = File(stateDir, "installed.json")
    private val journal = File(stateDir, "install-transaction.json")
    private val binaries = setOf("apk", "dex", "jar", "aar", "class", "so", "dll", "exe", "wasm", "zip")

    /** Releases that broke the rules; the same file the service writes on a reviewer's reject. */
    val bans = StoreBans(stateDir)

    init {
        require(workspace.isAbsolute && stateDir.isAbsolute) { "Установщику нужны абсолютные рабочие каталоги." }
        require(workspace.isDirectory || workspace.mkdirs())
        require(stateDir.isDirectory || stateDir.mkdirs())
        require(!stateDir.canonicalFile.toPath().startsWith(workspace.canonicalFile.toPath())) { "Реестр магазина должен находиться вне рабочей папки." }
        plansDir.mkdirs(); releases.mkdirs(); recover()
    }

    /**
     * [adopt] takes a folder that stands at the package's place without a record of the store (put there by hand
     * or by an older installer) under the store's care. Nothing is known about what it was made from, so the plan
     * has no base: files equal to the release pass silently, every other one is the owner's to decide.
     */
    @Synchronized
    fun prepare(sourceId: String, entry: JSONObject, manifestBytes: ByteArray, archiveFile: File, adopt: Boolean = false): JSONObject = prepareInternal(sourceId, entry, manifestBytes, archiveFile, false, adopt)

    /** Whether a folder stands where this package would be installed; who put it there is the registry's to say. */
    fun standing(kind: String, id: String, known: StoreKind? = kinds.find(kind)): Boolean = runCatching {
        if (known == null) return@runCatching false
        id !in known.reserved && Files.isDirectory(targetFile(known.target(id)).toPath(), NOFOLLOW_LINKS)
    }.getOrDefault(false)

    private fun prepareInternal(sourceId: String, entry: JSONObject, manifestBytes: ByteArray, archiveFile: File, rollback: Boolean, adopt: Boolean = false): JSONObject {
        require(sourceId.isNotEmpty() && sourceId.length <= 256 && sourceId.none { it.code < 32 }) { "Некорректный источник." }
        StoreFormat.packageEntry(entry)
        // Asked before anything is unpacked: a banned archive is not staged again, by hand or by an agent.
        bans.find(entry.getJSONObject("archive").getString("sha256"))?.let { throw StoreBanned(it) }
        val manifest = StoreFormat.manifest(manifestBytes)
        StoreFormat.compatible(entry, manifest)
        val id = manifest.getString("id"); val kind = manifest.getString("kind")
        // Where a package goes is decided by its kind, and a kind by the module that hosts it: never by the package.
        val known = kinds.require(kind)
        kinds.mismatch(kind, manifest.getJSONObject("runtime"))?.let { throw IllegalArgumentException(it) }
        require(id !in known.reserved && !id.contains("..") && !id.endsWith('.') && !id.endsWith('-')) { "Зарезервированный или неоднозначный ID пакета." }
        val target = known.target(id)
        val live = targetFile(target)
        val key = StoreFormat.sha256((sourceId + "\n" + id).toByteArray())
        val registry = registry(); val old = registry.optJSONObject(key)
        for (name in registry.keys()) require(name == key || registry.getJSONObject(name).getString("target") != target) { "Пакет другого источника уже занимает этот путь." }
        val foreign = old == null && Files.exists(live.toPath(), NOFOLLOW_LINKS)
        require(!foreign || adopt) { "Здесь уже стоит модуль, установленный не через магазин. Его файлы не заменены. Чтобы обновлять его из каталога, примите его под управление магазина." }
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
        var matching = 0
        try {
            val paths = (baseFiles.keys + localFiles.keys + incomingFiles.keys).sorted()
            for (path in paths) {
                val b = baseFiles[path]; val l = localFiles[path]; val n = incomingFiles[path]
                // Without a base a file equal to the release is the release's file, not the owner's edit.
                val same = foreign && l == n
                if (same) matching++
                val locallyChanged = l != b && !same
                val collision = l != b && n != b && l != n
                val chosen = when { collision -> l; l == b -> n; n == b || l == n -> l; else -> l }
                if (chosen != null) {
                    val from = if (chosen == l && Files.exists(File(live, path).toPath(), NOFOLLOW_LINKS)) File(live, path) else File(incoming, path)
                    copyFile(from, File(candidate, path))
                }
                if (!same && (b != n || l != n)) changes.put(JSONObject().put("path", path).put("kind", if (collision) "conflict" else if (chosen == l && locallyChanged) "keep-local" else if (chosen == null) "delete" else if (l == null) "add" else "update").put("localModified", locallyChanged))
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
                .put("adoption", foreign).put("matchingFiles", matching)
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
            require(path in setOf("package.json", "README.md", "LICENSE", "mod/brief.md")) { "Файл вне пакета." }
            File(File(releases, plan.getString("artifactSha256")), "archive/$path")
        } else when (side) {
            "candidate" -> File(candidate(planId), installedPath)
            "incoming" -> File(incomingDir(plan), installedPath)
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
    fun resolve(planId: String, path: String, expectedHash: String, content: String? = null, keepLocal: Boolean = false, takeIncoming: Boolean = false): JSONObject {
        val plan = loadPlan(planId); require(plan.getString("status") != "applied") { "План уже установлен." }; verifyCandidate(plan); verifyLive(plan)
        StoreFormat.path(path)
        val conflicts = plan.getJSONArray("conflicts")
        val conflict = (0 until conflicts.length()).map { conflicts.getJSONObject(it) }.find { it.getString("path") == path } ?: error("Конфликт не найден.")
        require(expectedHash == conflict.getString("localSha")) { "Локальная правка изменилась. Подготовьте новый план." }
        val target = File(candidate(planId), path)
        if (keepLocal) {
            val live = File(targetFile(plan.getString("target")), path)
            if (live.isFile) copyFile(live, target) else if (target.exists()) require(target.delete())
        } else if (takeIncoming) {
            // The release's own file, already checked against its manifest; the owner's copy stays in the snapshot.
            val incoming = File(incomingDir(plan), path)
            if (incoming.isFile) copyFile(incoming, target) else if (target.exists()) require(target.delete())
        } else {
            require(content != null) { "Нужен объединённый текст, версия магазина или сохранение локальной версии." }
            val bytes = content.toByteArray(); require(bytes.size <= StoreFormat.MAX_FILE) { "Объединённый файл слишком велик." }; scanSource(path, bytes, JSONArray())
            requireNotNull(target.parentFile).mkdirs(); target.writeBytes(bytes)
        }
        conflict.put("resolved", true).put("resolution", if (keepLocal) "keep-local" else if (takeIncoming) "incoming" else "merged")
        refreshPlan(plan, candidate(planId)); return publicPlan(plan)
    }

    @Synchronized
    fun apply(planId: String, expectedDigest: String): JSONObject {
        recover()
        val plan = loadPlan(planId)
        // Taking a mod off is never refused for what its release did: that is a reason to take it off.
        if (plan.optJSONObject("mod")?.optBoolean("removal") != true) bans.find(plan.getString("artifactSha256"))?.let { throw StoreBanned(it) }
        require(plan.getString("status") == "ready") { if (plan.getString("status") == "carrying") "Мод ещё не перенесён на вашу версию пакета." else "Сначала разрешите конфликты обновления." }
        require(plan.getString("digest") == expectedDigest) { "План изменился после проверки." }
        verifyCandidate(plan); verifyLive(plan)
        val mod = plan.optJSONObject("mod")
        if (mod == null) checkDependencies(plan)
        val registry = registry(); val key = plan.getString("installationKey"); val old = registry.optJSONObject(key)
        // A mod changes files of a package that stays what it is: what must still work afterwards is that package.
        val target = targetFile(plan.getString("target"))
        val screen = validateRuntime(candidate(planId), if (mod == null) plan.getJSONObject("manifest") else requireNotNull(old) { "Пакет, который меняет мод, больше не установлен." }.getJSONObject("manifest"))
        val tx = UUID.randomUUID().toString(); val stage = transactionDir("stage", tx); val previous = transactionDir("previous", tx)
        copyTree(candidate(planId), stage)
        require(signature(fileHashes(stage)) == plan.getString("candidateSignature")) { "Подготовленная копия изменилась." }
        val entryPath = if (screen.isEmpty()) "" else "${plan.getString("target")}/$screen"
        val record: JSONObject
        val label: String
        if (mod != null) {
            record = layered(plan, mod, old!!, target, stage).put("entryPath", entryPath).put("transactionId", tx)
            label = if (mod.optBoolean("removal")) "Перед снятием мода ${plan.getString("title")}" else "Перед установкой мода ${plan.getString("title")} ${plan.getString("version")}"
        } else {
            record = JSONObject().put("key", key).put("sourceId", plan.getString("sourceId")).put("id", plan.getString("packageId"))
                .put("title", plan.getString("title")).put("kind", plan.getString("kind")).put("version", plan.getString("version"))
                .put("publisher", plan.getString("publisher")).put("target", plan.getString("target")).put("entryPath", entryPath)
                .put("artifactSha256", plan.getString("artifactSha256")).put("manifest", plan.getJSONObject("manifest")).put("catalogEntry", plan.getJSONObject("catalogEntry"))
                .put("runtimeProfile", "trustedLegacy").put("capabilitiesEnforced", false).put("installedAt", System.currentTimeMillis())
                .put("baseFiles", JSONObject(fileHashes(File(File(releases, plan.getString("artifactSha256")), "payload")))).put("transactionId", tx)
            if (plan.optBoolean("adoption")) record.put("adopted", true)
            if (old != null) record.put("previousRelease", JSONObject().put("artifactSha256", old.getString("artifactSha256")).put("catalogEntry", old.getJSONObject("catalogEntry")).put("version", old.getString("version")))
            // The mods of a package stay with it through its updates: their files went through the same merge as any local change.
            old?.optJSONArray("mods")?.let { record.put("mods", it) }
            label = "Перед ${if (plan.optBoolean("rollback")) "откатом" else "установкой"} ${plan.getString("title")} ${plan.getString("version")}"
        }
        try {
            val snapshotId = snapshot(label)
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
            // What the list shows, many times a minute: the files as they are now against what the release
            // recorded. A file that kept its size and time is not read again; nothing is installed by this answer,
            // and every step that changes files hashes them anew.
            val local = fileHashes(targetFile(record.getString("target")), quick = true)
            val base = record.getJSONObject("baseFiles").let { declared -> declared.keys().asSequence().associateWith { declared.getString(it) } }
            val changed = (local.keys + base.keys).filter { local[it] != base[it] }.sorted()
            // Whose difference it is: a file that is exactly what a mod made it belongs to that mod, the rest are the owner's.
            val mods = layers(record)
            val byMods = changed.filter { path -> mods.any { layer -> local[path]?.let { layer.getJSONObject("files").optString(path) == it } ?: jsonStrings(layer.getJSONArray("removed")).contains(path) } }
            val own = changed - byMods.toSet()
            record.put("localModified", own.isNotEmpty()).put("modifiedFiles", JSONArray(own)).put("modFiles", JSONArray(byMods)).put("liveDigest", signature(local)).put("available", local.isNotEmpty())
        } catch (error: Exception) {
            record.put("localModified", true).put("modifiedFiles", JSONArray()).put("liveDigest", "invalid").put("localError", error.message ?: "Не удалось проверить локальные файлы.").put("available", false)
        }
        record.put("rollbackAvailable", record.has("previousRelease"))
        if (record.has("previousRelease")) record.put("previousVersion", record.getJSONObject("previousRelease").getString("version"))
        record
    }.toList() })

    // ---- mods: changes laid over an installed package ------------------------------------------------

    private fun jsonStrings(list: JSONArray): List<String> = (0 until list.length()).map { list.getString(it) }
    private fun layers(record: JSONObject): List<JSONObject> = record.optJSONArray("mods")?.let { list -> (0 until list.length()).map { list.getJSONObject(it) } } ?: emptyList()
    private fun modDir(packageKey: String, modKey: String): File = File(File(File(stateDir, "mods"), StoreFormat.hash(packageKey)), StoreFormat.hash(modKey))
    /**
     * What files were before a mod changed them: kept outside the workspace, by package, by mod and by
     * the laying they belong to. A new version of a mod is a new laying, so the copies of the one that
     * stands are never written over by a plan that may yet fail.
     */
    private fun beforeDir(packageKey: String, modKey: String, laying: String): File = File(File(modDir(packageKey, modKey), token(laying)), "files")
    /** What a plan brings in: the files of a release or, when a mod is taken off, the files as they were before it. */
    private fun incomingDir(plan: JSONObject): File = plan.optJSONObject("mod")?.takeIf { it.optBoolean("removal") }
        ?.let { beforeDir(plan.getString("installationKey"), it.getString("key"), it.getString("laying")) } ?: File(File(releases, StoreFormat.hash(plan.getString("artifactSha256"))), "payload")

    /** Copies kept for layings that no longer stand (a mod taken off, or replaced by its next version) are of no use. */
    private fun sweep(packageKey: String, record: JSONObject) {
        val kept = layers(record).associate { it.getString("key") to it.getString("laying") }
        File(File(stateDir, "mods"), StoreFormat.hash(packageKey)).listFiles().orEmpty().filter { it.isDirectory }.forEach { mod ->
            mod.listFiles().orEmpty().filter { it.isDirectory && kept[mod.name] != it.name }.forEach { runCatching { deleteTree(it) } }
        }
    }

    /**
     * The files of a package as they would be without one of its mods, and where the bytes of each are.
     * A file that is still what the mod made it goes back to what was there before; one changed since
     * stays as it is and is named in [changedSince]. With no layer this is the package as it stands.
     */
    private class Ground(val files: Map<String, String>, val from: Map<String, File>, val changedSince: List<String>)
    private fun ground(packageKey: String, live: File, local: Map<String, String>, layer: JSONObject?): Ground {
        val files = HashMap(local); val from = HashMap(local.keys.associateWith { File(live, it) })
        if (layer == null) return Ground(files, from, emptyList())
        val before = beforeDir(packageKey, layer.getString("key"), layer.getString("laying")); val was = fileHashes(before)
        val wrote = layer.getJSONObject("files"); val changed = ArrayList<String>()
        for (path in wrote.keys().asSequence().toList() + jsonStrings(layer.getJSONArray("removed"))) {
            val made = if (wrote.has(path)) wrote.getString(path) else null
            if (local[path] != made) { if (local[path] != was[path]) changed.add(path); continue }
            val back = was[path]
            if (back == null) { files.remove(path); from.remove(path) } else { files[path] = back; from[path] = File(before, path) }
        }
        return Ground(files, from, changed.sorted())
    }

    /** Files of an installed package that are the owner's own: different from its release and not what a mod made them. */
    private fun ownerEdited(record: JSONObject, local: Map<String, String>): Set<String> {
        val base = record.getJSONObject("baseFiles"); val mods = layers(record)
        return local.filter { (path, hash) -> base.optString(path) != hash && mods.none { it.getJSONObject("files").optString(path) == hash } }.keys
    }

    /** What a plan does to the files that stand; `localModified` marks a file the owner had edited. */
    private fun changesOf(local: Map<String, String>, now: Map<String, String>, owner: Set<String>): JSONArray =
        JSONArray((local.keys + now.keys).filter { local[it] != now[it] }.sorted().map { path ->
            JSONObject().put("path", path).put("kind", if (now[path] == null) "delete" else if (local[path] == null) "add" else "update").put("localModified", path in owner)
        })

    /** The record of a package with a mod's layer added or, for a removal, taken away. Copies of what the mod replaces are kept first. */
    private fun layered(plan: JSONObject, mod: JSONObject, old: JSONObject, target: File, stage: File): JSONObject {
        val record = JSONObject(old.toString()); val modKey = StoreFormat.hash(mod.getString("key"))
        val standing = layers(old).firstOrNull { it.getString("key") == modKey }
        val others = layers(old).filterNot { it.getString("key") == modKey }
        if (mod.optBoolean("removal")) return record.put("mods", JSONArray(others))
        // With an earlier version of this mod standing, "before the mod" is what was there before that version.
        val key = plan.getString("installationKey")
        val ground = ground(key, target, fileHashes(target), standing); val now = fileHashes(stage)
        val touched = (ground.files.keys + now.keys).filter { ground.files[it] != now[it] }.sorted()
        val laying = UUID.randomUUID().toString()
        val before = beforeDir(key, modKey, laying)
        touched.forEach { path -> ground.from[path]?.let { copyFile(it, File(before, path)) } }
        val layer = JSONObject().put("key", modKey).put("id", mod.getString("id")).put("sourceId", plan.getString("sourceId")).put("title", plan.getString("title"))
            .put("version", plan.getString("version")).put("publisher", plan.getString("publisher")).put("mode", mod.getString("mode")).put("laying", laying)
            .put("targetVersion", old.getString("version")).put("artifactSha256", plan.getString("artifactSha256")).put("catalogEntry", plan.getJSONObject("catalogEntry"))
            .put("files", JSONObject(touched.filter { now.containsKey(it) }.associateWith { now.getValue(it) }))
            .put("added", JSONArray(touched.filter { !ground.files.containsKey(it) })).put("removed", JSONArray(touched.filter { !now.containsKey(it) }))
            .put("ownerEdits", mod.optJSONArray("ownerEdits") ?: JSONArray()).put("appliedAt", System.currentTimeMillis())
        mod.optJSONObject("report")?.let { layer.put("report", it) }
        return record.put("mods", JSONArray(others + layer))
    }

    private fun modPlan(planId: String, sourceId: String, modId: String, key: String, record: JSONObject, title: String, version: String, publisher: String,
                        manifest: JSONObject, entry: JSONObject, artifact: String, local: Map<String, String>, incoming: Map<String, String>,
                        changes: JSONArray, conflicts: JSONArray, findings: JSONArray, mod: JSONObject): JSONObject =
        JSONObject().put("id", planId).put("sourceId", sourceId).put("packageId", modId).put("installationKey", key)
            .put("title", title).put("kind", StoreFormat.MOD).put("version", version).put("publisher", publisher)
            .put("target", record.getString("target")).put("entryPath", record.optString("entryPath"))
            .put("manifest", manifest).put("catalogEntry", JSONObject(entry.toString())).put("artifactSha256", artifact)
            .put("manifestSha256", StoreFormat.sha256(StoreFormat.canonical(manifest).toByteArray()))
            .put("runtimeProfile", "trustedLegacy").put("capabilitiesEnforced", false).put("createdAt", System.currentTimeMillis())
            .put("liveSignature", signature(local)).put("incomingSignature", signature(incoming)).put("baseRecordDigest", StoreFormat.sha256(StoreFormat.canonical(record).toByteArray()))
            .put("changes", changes).put("conflicts", conflicts).put("rollback", false).put("adoption", false).put("matchingFiles", 0)
            .put("findings", findings).put("previousRecord", record)
            .put("mod", mod.put("extends", record.getString("id")).put("targetTitle", record.optString("title")).put("targetVersion", record.getString("version")))

    /**
     * A plan to lay a mod over its target. When the target is the very version the sample was made on
     * and every file the mod touches is still what the author started from, the sample is laid over as
     * it is (`mode: direct`) and the plan is ready. In any other case nothing is laid over yet: the plan
     * waits in the status `carrying` for an agent to carry the mod over to what the owner has
     * (`mode: ported`, see the carry functions below), and says why in `mod.carry.reasons`.
     *
     * A version of the same mod that already stands is replaced: the new one is laid over the package
     * as it would be without the old one.
     */
    @Synchronized
    fun prepareMod(sourceId: String, entry: JSONObject, manifestBytes: ByteArray, archiveFile: File): JSONObject {
        require(sourceId.isNotEmpty() && sourceId.length <= 256 && sourceId.none { it.code < 32 }) { "Некорректный источник." }
        StoreFormat.packageEntry(entry)
        bans.find(entry.getJSONObject("archive").getString("sha256"))?.let { throw StoreBanned(it) }
        val manifest = StoreFormat.manifest(manifestBytes)
        StoreFormat.compatible(entry, manifest)
        require(manifest.getString("kind") == StoreFormat.MOD) { "Это не мод." }
        val registry = registry(); val targetId = manifest.getString("extends")
        val key = registry.keys().asSequence().firstOrNull { registry.getJSONObject(it).getString("id") == targetId }
            ?: throw IllegalArgumentException("Мод меняет пакет «$targetId», а он не стоит под управлением магазина. Сначала установите или примите его.")
        val record = registry.getJSONObject(key)
        val modKey = StoreFormat.sha256((sourceId + "\n" + manifest.getString("id")).toByteArray())
        val standing = layers(record).firstOrNull { it.getString("key") == modKey }
        if (standing != null) {
            val compared = StoreFormat.compareVersions(manifest.getString("version"), standing.getString("version"))
            require(compared >= 0) { "У вас стоит более новая версия этого мода." }
            require(compared > 0) { if (standing.getString("artifactSha256") == entry.getJSONObject("archive").getString("sha256")) "Эта версия мода уже стоит." else "Архив той же версии мода изменился. Нужен новый номер выпуска." }
        }
        sweep(key, record)
        val live = targetFile(record.getString("target"))
        val release = verifyRelease(archiveFile, entry, manifestBytes)
        val sample = File(release, "payload")
        val reference = manifest.getJSONObject("reference"); val base = reference.getJSONObject("base")
        val local = fileHashes(live); val incoming = fileHashes(sample)
        val ground = ground(key, live, local, standing)
        val removed = jsonStrings(reference.getJSONArray("removed"))
        // Why it cannot be laid over as it is: each reason is one the owner can read.
        val reasons = ArrayList<String>()
        fun some(paths: List<String>) = paths.take(4).joinToString() + if (paths.size > 4) " и ещё ${paths.size - 4}" else ""
        if (record.getString("version") != reference.getString("version")) reasons.add("образец сделан на версии ${reference.getString("version")}, а у вас стоит ${record.getString("version")}")
        val changed = base.keys().asSequence().filter { ground.files[it] != base.getString(it) }.toList()
        if (changed.isNotEmpty()) reasons.add("у вас другие файлы, чем те, с которыми работал автор: ${some(changed)}")
        val taken = incoming.keys.filter { !base.has(it) && ground.files.containsKey(it) && ground.files[it] != incoming[it] }
        if (taken.isNotEmpty()) reasons.add("файлы, которые мод добавляет, у вас уже есть: ${some(taken)}")
        val unknown = removed.filter { !base.has(it) && ground.files.containsKey(it) }
        if (unknown.isNotEmpty()) reasons.add("мод удаляет файлы, исходного вида которых в нём нет: ${some(unknown)}")
        if (ground.changedSince.isNotEmpty()) reasons.add("файлы прежней версии мода изменены после её установки: ${some(ground.changedSince)}")
        val direct = reasons.isEmpty()
        val planId = UUID.randomUUID().toString()
        val planDir = File(plansDir, planId).apply { mkdirs() }
        val candidate = File(planDir, "candidate")
        try {
            // The package without the standing version of this mod: what the new one is laid over.
            require(candidate.mkdirs())
            ground.from.forEach { (path, file) -> copyFile(file, File(candidate, path)) }
            if (direct) {
                for ((path, hash) in incoming) if (ground.files[path] != hash) copyFile(File(sample, path), File(candidate, path))
                for (path in removed) File(candidate, path).takeIf { it.isFile }?.let { require(it.delete()) }
            }
            val changes = changesOf(local, fileHashes(candidate), ownerEdited(record, local))
            if (direct) {
                require(changes.length() > 0) { "Мод ничего не меняет в установленном пакете." }
                validateRuntime(candidate, record.getJSONObject("manifest"))
            }
            val mod = JSONObject().put("key", modKey).put("id", manifest.getString("id")).put("mode", if (direct) "direct" else "ported").put("removal", false)
            standing?.let { mod.put("replaces", it.getString("version")) }
            if (!direct) mod.put("carry", JSONObject().put("state", "open").put("reasons", JSONArray(reasons)))
            val plan = modPlan(planId, sourceId, manifest.getString("id"), key, record, entry.getString("title"), manifest.getString("version"), manifest.getString("publisher"),
                manifest, entry, entry.getJSONObject("archive").getString("sha256"), local, incoming, changes, JSONArray(),
                readJson(File(release, "analysis.json")).getJSONArray("findings"), mod)
            refreshPlan(plan, candidate)
            return publicPlan(plan)
        } catch (error: Exception) { deleteTree(planDir); throw error }
    }

    /**
     * Taking a mod off: every file it changed goes back to what it was before the mod, which keeps the
     * edits the owner had made earlier. A file changed again since then is the owner's to decide about.
     */
    @Synchronized
    fun prepareModRemoval(packageKey: String, modKey: String): JSONObject {
        val record = registry().optJSONObject(StoreFormat.hash(packageKey)) ?: error("Установленный пакет не найден.")
        val layer = layers(record).firstOrNull { it.getString("key") == StoreFormat.hash(modKey) } ?: error("Такого мода у пакета нет.")
        sweep(packageKey, record)
        val live = targetFile(record.getString("target")); val local = fileHashes(live)
        val before = beforeDir(packageKey, modKey, layer.getString("laying")); val was = fileHashes(before)
        val wrote = layer.getJSONObject("files"); val gone = jsonStrings(layer.getJSONArray("removed"))
        val planId = UUID.randomUUID().toString()
        val planDir = File(plansDir, planId).apply { mkdirs() }
        val candidate = File(planDir, "candidate")
        try {
            copyTree(live, candidate)
            val changes = JSONArray(); val conflicts = JSONArray()
            for (path in (wrote.keys().asSequence().toList() + gone).sorted()) {
                val made = if (wrote.has(path)) wrote.getString(path) else null; val now = local[path]; val back = was[path]
                if (now == back) continue
                if (now == made) {
                    if (back == null) require(File(candidate, path).delete()) else copyFile(File(before, path), File(candidate, path))
                    changes.put(JSONObject().put("path", path).put("kind", if (back == null) "delete" else if (now == null) "add" else "update").put("localModified", false))
                } else {
                    // Changed again after the mod: neither the mod's file nor what was before it. Kept until the owner says.
                    changes.put(JSONObject().put("path", path).put("kind", "conflict").put("localModified", true))
                    conflicts.put(JSONObject().put("path", path).put("baseSha", made ?: "missing").put("localSha", now ?: "missing").put("incomingSha", back ?: "missing")
                        .put("baseHash", made ?: "missing").put("localHash", now ?: "missing").put("incomingHash", back ?: "missing").put("resolved", false))
                }
            }
            val plan = modPlan(planId, layer.getString("sourceId"), layer.getString("id"), StoreFormat.hash(packageKey), record, layer.getString("title"), layer.getString("version"),
                layer.optString("publisher"), record.getJSONObject("manifest"), layer.getJSONObject("catalogEntry"), layer.getString("artifactSha256"), local, was, changes, conflicts,
                JSONArray(), JSONObject().put("key", StoreFormat.hash(modKey)).put("id", layer.getString("id")).put("mode", layer.optString("mode")).put("laying", layer.getString("laying")).put("removal", true))
            refreshPlan(plan, candidate)
            return publicPlan(plan)
        } catch (error: Exception) { deleteTree(planDir); throw error }
    }

    // ---- carrying a mod over to what the owner has ---------------------------------------------------
    //
    // The work of an agent in a conversation of its own (see StoreAgent). It reads the brief, the author's
    // files and what they were before, and the package as it stands; it writes only into the candidate of
    // this one plan. Nothing here runs code, and nothing reaches the package until the owner applies the plan.

    private class Carry(val plan: JSONObject, val record: JSONObject, val release: File, val candidate: File, val live: File)

    private fun carry(planId: String, open: Boolean = true): Carry {
        val plan = loadPlan(planId); val mod = plan.optJSONObject("mod")
        require(mod != null && mod.has("carry")) { "Этот план не переносит мод." }
        require(plan.getString("status") != "applied") { "Этот план уже установлен." }
        require(!open || mod.getJSONObject("carry").getString("state") == "open") { "Перенос этого мода уже сдан. Чтобы переделать его, подготовьте мод заново." }
        verifyCandidate(plan); verifyLive(plan)
        return Carry(plan, plan.getJSONObject("previousRecord"), File(releases, StoreFormat.hash(plan.getString("artifactSha256"))), candidate(planId), targetFile(plan.getString("target")))
    }

    private val carrySides = listOf("reference", "base", "asset", "current", "candidate", "release")
    private fun carrySide(c: Carry, side: String, path: String): File {
        StoreFormat.path(path)
        return when (side) {
            "reference" -> File(File(c.release, "payload"), path)
            "base" -> File(File(c.release, "archive/base"), path)
            "asset" -> File(File(c.release, "archive/assets"), path)
            "current" -> File(c.live, path)
            "candidate" -> File(c.candidate, path)
            "release" -> File(baseDirectory(c.record), path)
            else -> throw IllegalArgumentException("Неизвестная сторона $side. Есть: ${carrySides.joinToString()}.")
        }
    }

    /** The plan after a change of its candidate: the list of changes follows the files, and so does the digest. */
    private fun carried(c: Carry): JSONObject {
        val local = fileHashes(c.live)
        c.plan.put("changes", changesOf(local, fileHashes(c.candidate), ownerEdited(c.record, local)))
        refreshPlan(c.plan, c.candidate)
        return publicPlan(c.plan)
    }

    private fun carryPut(c: Carry, path: String, bytes: ByteArray) {
        StoreFormat.path(path)
        require(bytes.size <= StoreFormat.MAX_FILE) { "Файл слишком велик." }
        // The check every release goes through. Failing it here is a fault of this carry-over, not of the mod's author: nothing is banned.
        try { scanSource(path, bytes, JSONArray()) } catch (violation: StoreViolation) { throw IllegalArgumentException("Так записать нельзя: ${violation.message}") }
        val target = File(c.candidate, path); val parent = requireNotNull(target.parentFile)
        require((parent.mkdirs() || parent.isDirectory) && !target.isDirectory) { "Путь $path занят каталогом." }
        FileOutputStream(target).use { it.write(bytes); it.fd.sync() }
    }

    /**
     * What is to be carried over: the mod, the package, why the sample does not fit as it is, and the
     * files concerned, a page at a time. With [tree] the page lists every file of the package as it stands.
     */
    @Synchronized
    fun carryTask(planId: String, tree: Boolean = false, offset: Int = 0): JSONObject {
        val c = carry(planId, open = false); val plan = c.plan; val mod = plan.getJSONObject("mod")
        val reference = plan.getJSONObject("manifest").getJSONObject("reference")
        val sample = fileHashes(File(c.release, "payload")); val base = fileHashes(File(c.release, "archive/base")); val assets = File(c.release, "archive/assets")
        val local = fileHashes(c.live); val now = fileHashes(c.candidate); val owner = ownerEdited(c.record, local)
        val removed = jsonStrings(reference.getJSONArray("removed"))
        val paths = (if (tree) local.keys else sample.keys + base.keys + removed + (local.keys + now.keys).filter { local[it] != now[it] }).distinct().sorted()
        require(offset in 0..paths.size) { "Смещение за пределами списка." }
        val end = minOf(paths.size, offset + 60)
        val files = JSONArray(paths.subList(offset, end).map { path ->
            JSONObject().put("path", path).put("reference", sample.containsKey(path)).put("base", base.containsKey(path)).put("current", local.containsKey(path))
                .put("removedByMod", path in removed).put("ownerEdited", path in owner)
                // The author's exact change is known only where the mod carries what the file was before.
                .put("withoutBase", sample.containsKey(path) && !base.containsKey(path) && local.containsKey(path))
                .put("sameAsAuthorStart", base[path] != null && base[path] == local[path])
                .put("candidate", when { now[path] == local[path] -> "untouched"; now[path] == null -> "deleted"; local[path] == null -> "added"; else -> "changed" })
        })
        val out = JSONObject().put("planId", planId).put("state", mod.getJSONObject("carry").getString("state"))
            .put("mod", JSONObject().put("id", mod.getString("id")).put("title", plan.getString("title")).put("version", plan.getString("version")).put("replaces", mod.optString("replaces")))
            .put("target", JSONObject().put("id", c.record.getString("id")).put("title", c.record.optString("title")).put("version", c.record.getString("version")))
            .put("referenceVersion", reference.getString("version")).put("reasons", mod.getJSONObject("carry").getJSONArray("reasons"))
            .put("tree", tree).put("files", files).put("filesTotal", paths.size).put("nextOffset", if (end < paths.size) end else JSONObject.NULL)
        if (!tree && offset == 0) out.put("assets", JSONArray(fileHashes(assets).keys.map { JSONObject().put("path", it).put("size", File(assets, it).length()) }))
        return out
    }

    /** A piece of a text file from one side of the carry-over; `brief` is the author's task and needs no path. */
    @Synchronized
    fun carryRead(planId: String, side: String, path: String, offset: Int = 0, limit: Int = 12000): JSONObject {
        require(offset >= 0 && limit in 1..64000) { "Недопустимый диапазон чтения." }
        val c = carry(planId, open = false)
        val file = if (side == "brief") File(c.release, "archive/mod/brief.md") else carrySide(c, side, path)
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) { "Файла ${if (side == "brief") "задания" else path} нет на стороне $side." }
        require(file.length() <= StoreFormat.MAX_FILE) { "Файл слишком велик." }
        val bytes = file.readBytes()
        val text = runCatching { StoreFormat.utf8(bytes) }.getOrNull()?.takeIf { content -> content.none { it == '\u0000' } }
            ?: throw IllegalArgumentException("Это не текст. Такой файл берётся целиком: store_mod_copy.")
        require(offset <= text.length) { "Смещение за концом файла." }
        val end = minOf(text.length, offset + limit)
        return JSONObject().put("path", if (side == "brief") "mod/brief.md" else path).put("side", side).put("content", text.substring(offset, end))
            .put("offset", offset).put("nextOffset", end).put("totalLength", text.length).put("eof", end == text.length).put("sha256", StoreFormat.sha256(bytes))
    }

    @Synchronized
    fun carryWrite(planId: String, path: String, content: String): JSONObject { val c = carry(planId); carryPut(c, path, content.toByteArray()); return carried(c) }

    /** Replaces an exact piece of a candidate's text file; the piece must be there once, or [all] of its places are replaced. */
    @Synchronized
    fun carryEdit(planId: String, path: String, find: String, replace: String, all: Boolean = false): JSONObject {
        val c = carry(planId); val file = carrySide(c, "candidate", path)
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) { "В кандидате нет файла $path." }
        require(find.isNotEmpty()) { "Нужен фрагмент, который заменяется." }
        val text = StoreFormat.utf8(file.readBytes())
        val count = text.split(find).size - 1
        require(count > 0) { "Фрагмент не найден в $path. Прочитайте файл со стороны candidate и возьмите точный текст." }
        require(all || count == 1) { "Фрагмент встречается в $path $count раз. Дайте больше окружающего текста или all=true." }
        carryPut(c, path, text.replace(find, replace).toByteArray())
        return carried(c)
    }

    /** Takes a whole file, text or picture, from one of the sides into the candidate at [path]. */
    @Synchronized
    fun carryCopy(planId: String, side: String, source: String, path: String): JSONObject {
        val c = carry(planId)
        require(side != "candidate") { "Копируется файл с другой стороны: ${carrySides.filter { it != "candidate" }.joinToString()}." }
        val file = carrySide(c, side, source)
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) { "Файла $source нет на стороне $side." }
        carryPut(c, path, file.readBytes())
        return carried(c)
    }

    @Synchronized
    fun carryDelete(planId: String, path: String): JSONObject {
        val c = carry(planId); val file = carrySide(c, "candidate", path)
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) { "В кандидате нет файла $path." }
        require(file.delete()) { "Не удалось удалить $path." }
        return carried(c)
    }

    /**
     * The carry-over is handed in. [report] is `{summary, verbatim: [path], adapted: [{path, note}],
     * skipped: [text], questions: [text]}`: every changed file is named once, as taken from the sample as
     * it is or as done differently and why. What is called verbatim is compared with the sample here.
     * The package must still be one that starts. From now on the plan is an ordinary one, ready for the
     * owner to look at, have reviewed and apply.
     */
    @Synchronized
    fun carryDone(planId: String, report: JSONObject): JSONObject {
        val c = carry(planId); val plan = c.plan; val mod = plan.getJSONObject("mod")
        val local = fileHashes(c.live); val now = fileHashes(c.candidate); val owner = ownerEdited(c.record, local)
        val changes = changesOf(local, now, owner)
        val changed = (0 until changes.length()).map { changes.getJSONObject(it).getString("path") }
        require(changed.isNotEmpty()) { "В кандидате нет ни одного изменения: мод не перенесён. Если переносить нечего, скажите это владельцу, а пустой перенос не сдавайте." }
        try { validateRuntime(c.candidate, c.record.getJSONObject("manifest")) }
        catch (error: IllegalArgumentException) { throw IllegalArgumentException("После такого переноса пакет не запустится: ${error.message}") }
        val summary = report.opt("summary") as? String
        require(!summary.isNullOrBlank() && summary.length <= 4000) { "Нужен итог переноса (summary): что получит владелец, до 4000 символов." }
        fun texts(key: String): JSONArray {
            val list = report.optJSONArray(key) ?: JSONArray(); require(list.length() <= 40) { "Слишком длинный список $key." }
            return JSONArray((0 until list.length()).map { index -> (list.opt(index) as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 1000 } ?: throw IllegalArgumentException("$key[$index] должен быть непустой строкой до 1000 символов.") })
        }
        val sample = fileHashes(File(c.release, "payload")); val base = fileHashes(File(c.release, "archive/base"))
        val removed = jsonStrings(plan.getJSONObject("manifest").getJSONObject("reference").getJSONArray("removed"))
        val named = HashSet<String>()
        val verbatim = JSONArray(); val stated = report.optJSONArray("verbatim") ?: JSONArray()
        for (index in 0 until stated.length()) {
            val path = stated.opt(index) as? String ?: throw IllegalArgumentException("verbatim[$index] должен быть путём файла.")
            require(path in changed && named.add(path)) { "verbatim[$index]: $path не изменён этим переносом или назван дважды." }
            require(if (now[path] == null) path in removed else now[path] == sample[path]) { "$path отличается от образца автора: назовите его в adapted и скажите, что сделано иначе." }
            verbatim.put(path)
        }
        val adapted = JSONArray(); val told = report.optJSONArray("adapted") ?: JSONArray()
        for (index in 0 until told.length()) {
            val item = told.optJSONObject(index) ?: throw IllegalArgumentException("adapted[$index] должен быть объектом {path, note}.")
            val path = item.opt("path") as? String; val note = (item.opt("note") as? String)?.trim()
            require(path != null && path in changed && named.add(path)) { "adapted[$index]: $path не изменён этим переносом или назван дважды." }
            require(!note.isNullOrEmpty() && note.length <= 1000) { "adapted[$index]: нужно пояснение (note), что сделано иначе, чем в образце, и почему." }
            adapted.put(JSONObject().put("path", path).put("note", note))
        }
        (changed - named).takeIf { it.isNotEmpty() }?.let { throw IllegalArgumentException("В отчёте не названы изменённые файлы: ${it.take(8).joinToString()}. Каждый должен быть в verbatim или в adapted.") }
        val saved = JSONObject().put("summary", summary.trim()).put("verbatim", verbatim).put("adapted", adapted)
            .put("skipped", texts("skipped")).put("questions", texts("questions"))
            // Changed with nothing in the mod to say what the author started from: worth a closer look.
            .put("withoutBase", JSONArray(changed.filter { sample.containsKey(it) && !base.containsKey(it) && local.containsKey(it) }))
        mod.put("report", saved).put("ownerEdits", JSONArray(changed.filter { it in owner }))
        mod.getJSONObject("carry").put("state", "done").put("doneAt", System.currentTimeMillis())
        plan.put("changes", changes)
        refreshPlan(plan, c.candidate)
        return publicPlan(plan)
    }

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
            layers(record).forEach { referenced.add(it.getString("artifactSha256")) }
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

    /** Where a file of an archive goes in the installed package; null for what stays in the archive only. */
    private fun placed(path: String, mod: Boolean): String? = if (mod) path.takeIf { it.startsWith("reference/") }?.removePrefix("reference/") else virtualToInstalled(path)

    private fun verifyRelease(archive: File, entry: JSONObject, manifestBytes: ByteArray): File {
        val expected = entry.getJSONObject("archive"); val hash = expected.getString("sha256")
        // A damaged download or a stale index is the source's trouble, never a ban of the package.
        if (!(archive.isFile && archive.length() == expected.getLong("size") && archive.length() <= StoreFormat.MAX_ARCHIVE)) throw StoreSourceFault("Размер скачанного архива не совпадает с индексом.")
        if (StoreFormat.sha256(archive) != hash) throw StoreSourceFault("SHA-256 скачанного архива не совпадает с индексом.")
        val manifest = StoreFormat.manifest(manifestBytes)
        val mod = manifest.getString("kind") == StoreFormat.MOD
        val release = File(releases, hash)
        val fresh = File(releases, ".unpack-${UUID.randomUUID()}").apply { mkdirs() }
        val archiveRoot = File(fresh, "archive").apply { mkdirs() }; val payload = File(fresh, "payload").apply { mkdirs() }
        val files = manifest.getJSONArray("files"); val declared = (0 until files.length()).associate { files.getJSONObject(it).getString("path") to files.getJSONObject(it) }
        val found = ArrayList<String>(); val directoryNames = HashSet<String>(); val findings = JSONArray(); var total = 0L
        try {
            ZipFile(archive).use { zip ->
                // The manifest came by its own address and is pinned by no hash. Only when the archive carries
                // the same one is its list of files the archive's own word, and a mismatch the package's fault.
                val inside = zip.getEntry("package.json") ?: throw StoreSourceFault("В архиве нет package.json.")
                if (inside.isUnixSymlink || inside.generalPurposeBit.usesEncryption() || !zip.canReadEntryData(inside) || inside.size > StoreFormat.MAX_METADATA) throw StoreSourceFault("Манифест в архиве не читается.")
                val insideBytes = zip.getInputStream(inside).use { input -> val out = java.io.ByteArrayOutputStream(); val part = ByteArray(16384); while (true) { val count = input.read(part); if (count < 0) break; if (out.size() + count > StoreFormat.MAX_METADATA) throw StoreSourceFault("Манифест в архиве превышает лимит."); out.write(part, 0, count) }; out.toByteArray() }
                if (runCatching { StoreFormat.canonical(StoreFormat.manifest(insideBytes)) }.getOrNull() != StoreFormat.canonical(manifest)) throw StoreSourceFault("Манифест в ZIP отличается от опубликованного манифеста.")
                val entries = zip.entries
                var entryCount = 0
                while (entries.hasMoreElements()) {
                    require(++entryCount <= 4096) { "Слишком много служебных записей ZIP." }
                    val item = entries.nextElement(); val name = item.name
                    if (item.isUnixSymlink || !(item.unixMode == 0 || item.unixMode and 0xf000 in setOf(0, 0x8000, 0x4000))) throw StoreViolation("archive.unsafe", name.take(240), "Ссылки и специальные файлы в ZIP запрещены: ${name.take(240)}.")
                    if (item.generalPurposeBit.usesEncryption()) throw StoreViolation("archive.unsafe", name.take(240), "В архиве есть зашифрованная запись: ${name.take(240)}.")
                    require(zip.canReadEntryData(item)) { "Неподдерживаемый способ сжатия ZIP." }
                    if (item.isDirectory) {
                        val p = archivePath(name.removeSuffix("/")); require((if (mod) listOf("mod", "reference", "base", "assets") else listOf("code", "assets")).any { p == it || p.startsWith("$it/") })
                        require(directoryNames.add(p.lowercase(Locale.ROOT))) { "Повторяющийся каталог ZIP." }; continue
                    }
                    archivePath(name); if (!(name == "package.json" || declared.containsKey(name))) throw StoreViolation("content.mismatch", name, "Необъявленный файл в архиве: $name.")
                    require(found.size < StoreFormat.MAX_FILES && name !in found) { "Повторяющийся файл или слишком много файлов ZIP." }
                    val ceiling = if (name == "package.json") StoreFormat.MAX_METADATA else StoreFormat.MAX_FILE
                    val buffer = java.io.ByteArrayOutputStream(); val bytes = ByteArray(16384)
                    zip.getInputStream(item).use { input -> while (true) { val count = input.read(bytes); if (count < 0) break; require(buffer.size() + count <= ceiling && total + count <= StoreFormat.MAX_UNPACKED) { "Превышен лимит распаковки." }; buffer.write(bytes, 0, count); total += count } }
                    val content = buffer.toByteArray()
                    if (name == "package.json") require(StoreFormat.canonical(StoreFormat.manifest(content)) == StoreFormat.canonical(manifest)) { "Манифест в ZIP отличается от проверенного." }
                    else { val record = declared.getValue(name); if (!(content.size.toLong() == record.getLong("size") && StoreFormat.sha256(content) == record.getString("sha256"))) throw StoreViolation("content.mismatch", name, "Размер или хеш файла $name не совпали с манифестом.") }
                    scanSource(name, content, findings)
                    File(archiveRoot, name).apply { requireNotNull(parentFile).mkdirs(); writeBytes(content) }; found.add(name)
                }
            }
            StoreFormat.uniquePaths(found)
            (declared.keys - found.toSet()).firstOrNull()?.let { throw StoreViolation("content.mismatch", it, "В архиве нет объявленного в манифесте файла $it.") }
            if (!(found.size == expected.getInt("fileCount") && total == expected.getLong("unpackedSize"))) throw StoreSourceFault("Состав или распакованный размер ZIP не совпал с индексом.")
            val mapped = declared.keys.mapNotNull { placed(it, mod) }; StoreFormat.uniquePaths(mapped)
            for (path in declared.keys) placed(path, mod)?.let { copyFile(File(archiveRoot, path), File(payload, it)) }
            // A mod is not a package that runs: what must work is its target with the mod laid over, checked when the plan is made.
            if (!mod) validateRuntime(payload, manifest)
            if (manifest.getString("kind") == "module" && StoreFormat.parse(File(payload, "module.json").readBytes()).optBoolean("autostart")) {
                findings.put(JSONObject().put("code", "autostart").put("rule", "background.declared").put("path", "code/module.json").put("message", "Модуль просит фоновый запуск при следующем старте Axiko."))
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
        val known = kinds.require(manifest.getString("kind"))
        val file = File(payload, known.descriptor)
        require(file.isFile) { "В пакете отсутствует ${file.name}." }
        val runtime = StoreFormat.parse(file.readBytes())
        require(runtime.optString("id") == manifest.getString("id")) { "ID runtime должен совпадать с ID пакета; существующие данные не переименовываются." }
        val entry = manifest.getString("entry").removePrefix("code/")
        require(File(payload, entry).isFile) { "Нет точки входа пакета." }
        val ownPrefix = known.target(manifest.getString("id")) + "/"
        if (runtime.has("entry")) {
            val screen = runtime.getString("entry").removePrefix(ownPrefix)
            StoreFormat.path(screen)
            require(File(payload, screen).isFile && screen.substringAfterLast('.').lowercase(Locale.ROOT) in setOf("html", "htm")) { "Экран runtime должен быть HTML-файлом своего пакета." }
            return screen
        }
        require(!known.screen) { "В ${known.descriptor} должен быть указан HTML-экран пакета." }
        return when {
            File(payload, "ui/index.html").isFile -> "ui/index.html"
            entry.substringAfterLast('.').lowercase(Locale.ROOT) in setOf("html", "htm") -> entry
            else -> ""
        }
    }

    private fun scanSource(name: String, bytes: ByteArray, findings: JSONArray) {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension in binaries || bytes.size >= 4 && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() && bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()) throw StoreViolation("code.binary", name, "Бинарный исполняемый файл запрещён: $name.")
        if (extension !in setOf("js", "mjs", "css", "html", "htm")) return
        val source = StoreFormat.utf8(bytes)
        if (Regex("(?i)\\.min\\.(js|mjs|css)$").containsMatchIn(name)) throw StoreViolation("code.obfuscated", name, "Минифицированный файл запрещён: $name.")
        if (Regex("eval\\s*\\(\\s*(?:atob|unescape|String\\.fromCharCode)|eval\\s*\\(function\\(p,a,c,k,e", RegexOption.IGNORE_CASE).containsMatchIn(source)) throw StoreViolation("code.obfuscated", name, "Обнаружен упакованный исполняемый код: $name.")
        val codeWithoutData = codeWithoutLiterals(source)
        if (codeWithoutData.lineSequence().any { it.length > 1500 && it.count { c -> c == ';' } > 15 }) throw StoreViolation("code.obfuscated", name, "Исходник похож на минифицированный: $name. Нужна читаемая версия.")
        if (Regex("(?i)<script[^>]+src\\s*=\\s*[\"']?(?:https?:)?//|(?:import\\s+[^;]*?from|import\\s*\\(|importScripts\\s*\\(|new\\s+(?:Worker|SharedWorker)\\s*\\()\\s*[\"'](?:https?:)?//|@import\\s+(?:url\\(\\s*)?[\"']?(?:https?:)?//").containsMatchIn(source)) throw StoreViolation("code.remote", name, "Удалённый исполняемый код запрещён: $name.")
        if (Regex("\\b(?:Packages|java|context)\\b|native\\s*\\(|host\\.").containsMatchIn(codeWithoutData)) findings.put(JSONObject().put("code", "privileged-runtime").put("rule", "privilege.declared").put("path", name).put("message", "Код обращается к возможностям приложения; изоляция прав пока не обеспечивается."))
        if (Regex("https?://|fetch\\s*\\(|XMLHttpRequest|\\.http\\.").containsMatchIn(source)) findings.put(JSONObject().put("code", "network-use").put("rule", "network.declared").put("path", name).put("message", "Нужно проверить сетевые адреса и передаваемые данные."))
        if (Regex("\\beval\\s*\\(|\\bnew\\s+Function\\s*\\(").containsMatchIn(codeWithoutData)) findings.put(JSONObject().put("code", "dynamic-code").put("rule", "code.dynamic").put("path", name).put("message", "Нужно проверить происхождение динамически выполняемого кода."))
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
        val carrying = plan.optJSONObject("mod")?.optJSONObject("carry")?.optString("state") == "open"
        plan.put("candidateSignature", signature(hashes)).put("status", if (carrying) "carrying" else if (unresolved == 0) "ready" else "conflicts")
        val review = JSONArray()
        val manifestFiles = plan.getJSONObject("manifest").getJSONArray("files")
        val virtuals = (0 until manifestFiles.length()).map { manifestFiles.getJSONObject(it).getString("path") }.mapNotNull { name -> virtualToInstalled(name)?.let { it to name } }.toMap()
        hashes.forEach { (path, hash) -> val virtual = virtuals[path] ?: "code/$path"; review.put(JSONObject().put("path", virtual).put("sha256", hash).put("size", File(candidate, path).length()).put("executable", path.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("js", "mjs", "html", "htm", "css") || path in setOf("module.json", "game.json", "config.json"))) }
        for (path in listOf("package.json", "README.md", "LICENSE", "mod/brief.md")) { val file = File(File(releases, plan.getString("artifactSha256")), "archive/$path"); if (!file.isFile) continue; review.put(JSONObject().put("path", path).put("sha256", StoreFormat.sha256(file)).put("size", file.length()).put("executable", path == "package.json")) }
        plan.put("reviewFiles", review).put("files", review)
        plan.put("digest", planDigest(plan)); savePlan(plan)
    }
    private fun planDigest(plan: JSONObject): String = StoreFormat.sha256(StoreFormat.canonical(JSONObject(plan.toString()).apply { remove("digest"); remove("snapshotId"); remove("appliedAt") }).toByteArray())
    private fun verifyCandidate(plan: JSONObject) {
        require(plan.getString("digest") == planDigest(plan)) { "Метаданные плана изменены после подготовки." }
        require(signature(fileHashes(candidate(plan.getString("id")))) == plan.getString("candidateSignature")) { "Подготовленные файлы изменились после проверки." }
        require(signature(fileHashes(incomingDir(plan))) == plan.getString("incomingSignature")) { "Исходный выпуск изменился после подготовки." }
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
    /** A name that climbs out of the package is an attack on the installer, not a slip of formatting. */
    private fun archivePath(name: String): String {
        if (name.startsWith('/') || '\\' in name || name.split('/').any { it == ".." } || Regex("^[A-Za-z]:").containsMatchIn(name)) throw StoreViolation("archive.unsafe", name.take(240), "Путь в архиве выходит за разрешённые каталоги: ${name.take(240)}.")
        return StoreFormat.path(name)
    }
    private fun virtualToInstalled(path: String): String? = when { path.startsWith("code/") -> path.removePrefix("code/"); path.startsWith("assets/") -> path; else -> null }
    private fun token(value: String): String { require(Regex("[a-f0-9-]{36}").matches(value)) { "Некорректный ID операции." }; return value }
    private fun targetFile(path: String): File { require(Regex("modules/(?:[a-z0-9][a-z0-9.-]{0,95}/)?[a-z0-9][a-z0-9.-]{0,95}").matches(path) && !path.contains("..")) { "Недопустимый путь установки." }; val target = File(workspace, path); var parent: File? = target; while (parent != null && parent != workspace.parentFile) { require(!Files.isSymbolicLink(parent.toPath())) { "Ссылки в пути установки запрещены." }; parent = parent.parentFile }; require(target.canonicalFile.toPath().startsWith(workspace.canonicalFile.toPath())) ; return target }
    private fun transactionDir(kind: String, token: String): File = File(workspace.parentFile, ".assi-store-$kind-${token(token)}")
    private fun signature(files: Map<String, String>): String = StoreFormat.sha256(files.toSortedMap().entries.joinToString("\n") { "${it.key}\t${it.value}" }.toByteArray())
    private fun sameFiles(a: Map<String, String>, b: Map<String, String>) = a == b
    /** Hashes of files by path, size and time of change, for [fileHashes] asked to be quick. */
    private val remembered = java.util.concurrent.ConcurrentHashMap<String, Triple<Long, Long, String>>()
    private fun quickHash(file: File): String {
        val length = file.length(); val changed = file.lastModified()
        remembered[file.path]?.takeIf { it.first == length && it.second == changed }?.let { return it.third }
        return StoreFormat.sha256(file).also { remembered[file.path] = Triple(length, changed, it) }
    }
    private fun fileHashes(root: File, quick: Boolean = false): Map<String, String> { if (!Files.exists(root.toPath(), NOFOLLOW_LINKS)) return emptyMap(); require(root.isDirectory && !Files.isSymbolicLink(root.toPath())); val result = sortedMapOf<String, String>(); var total = 0L; root.walkTopDown().forEach { file -> require(!Files.isSymbolicLink(file.toPath())) { "Ссылка в файлах пакета: ${file.name}." }; if (file.isFile) { val path = file.relativeTo(root).invariantSeparatorsPath; StoreFormat.path(path); require(file.length() <= StoreFormat.MAX_FILE && result.size < StoreFormat.MAX_FILES) { "Локальная версия превышает лимит файлов магазина." }; total += file.length(); require(total <= StoreFormat.MAX_UNPACKED) { "Локальная версия превышает лимит размера магазина." }; result[path] = if (quick) quickHash(file) else StoreFormat.sha256(file) } else require(file.isDirectory) }; StoreFormat.uniquePaths(result.keys); return result }
    private fun copyTree(from: File, to: File) { require(!to.exists()); require(to.mkdirs()); fileHashes(from).keys.forEach { copyFile(File(from, it), File(to, it)) } }
    private fun copyFile(from: File, to: File) { require(Files.isRegularFile(from.toPath(), NOFOLLOW_LINKS)) { "Источник не является обычным файлом." }; val parent = requireNotNull(to.parentFile); require(parent.mkdirs() || parent.isDirectory) { "Файл конфликтует с каталогом назначения." }; FileOutputStream(to).use { output -> from.inputStream().use { it.copyTo(output) }; output.fd.sync() } }
    private fun move(from: File, to: File) { try { Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE) } catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(from.toPath(), to.toPath()) } }
    private fun atomicJson(file: File, json: JSONObject) { requireNotNull(file.parentFile).mkdirs(); val temp = File(file.parentFile, file.name + ".tmp"); FileOutputStream(temp).use { it.write(json.toString().toByteArray()); it.fd.sync() }; try { Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) } catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp.toPath(), file.toPath(), REPLACE_EXISTING) } }
    private fun deleteTree(root: File) { if (!Files.exists(root.toPath(), NOFOLLOW_LINKS)) return; if (Files.isSymbolicLink(root.toPath()) || !root.isDirectory) { require(root.delete()); return }; root.listFiles().orEmpty().forEach(::deleteTree); require(root.delete()) }
}
