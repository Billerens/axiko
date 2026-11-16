package app.assi.core

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

/**
 * The part of the app the agent rewrites: pages, modules, library, tools, prompt.
 * A factory copy ships in the APK; the working copy lives in app storage and is
 * snapshotted before every agent turn so any change can be rolled back.
 */
object Workspace {
    /** Code and pages the agent edits. Snapshotted. */
    lateinit var root: File
        private set

    /** What modules store (notes, databases, downloads). Never rolled back. */
    lateinit var data: File
        private set

    /** The shell's own state: chat, factory manifest. */
    lateinit var state: File
        private set

    lateinit var history: File
        private set

    /** Pages come from the factory copy and boot scripts are skipped. */
    @Volatile
    var safeMode = false

    private const val FACTORY = "factory"
    private const val KEEP_SNAPSHOTS = 60
    private const val MAX_DIFF_FILE = 5L * 1024 * 1024
    private lateinit var historyStore: SnapshotStore

    fun init(ctx: Context) {
        open(ctx.filesDir)
        installFactory(ctx)
    }

    /** Places the folders under [base], without the factory copy (tests start from here). */
    @Synchronized
    fun open(base: File) {
        root = File(base, "assi")
        data = File(base, "data").apply { mkdirs() }
        state = File(base, "state").apply { mkdirs() }
        history = File(base, "history").apply { mkdirs() }
        // Recover before mkdirs/factory installation can obscure an interrupted directory swap.
        recoverRestore()
        require(root.mkdirs() || root.isDirectory) { "Не удалось открыть рабочую папку." }
        historyStore = SnapshotStore(history)
    }

    /**
     * A workspace-relative path, `data/...` for module data, or an absolute path as is.
     * Code working in a draft (see [Drafts]) gets the draft's copy of a workspace file when it
     * has one; [write] says the file is about to be changed, so the draft gets a copy of its own.
     */
    fun resolve(path: String, write: Boolean = false): File {
        val file = File(path)
        if (file.isAbsolute) return file
        val clean = path.trimStart('/').removePrefix("./")
        if (clean == "data" || clean.startsWith("data/")) return File(data, clean.removePrefix("data").trimStart('/'))
        val draft = Drafts.active
        return if (draft != null && clean.isNotEmpty()) Drafts.locate(draft, clean, write) else File(root, clean)
    }

    /** Removes a file or a folder; in a draft the app's own file stays until the draft is taken. */
    fun remove(path: String): Boolean {
        val draft = Drafts.active
        val clean = path.trimStart('/').removePrefix("./")
        if (draft != null && !File(path).isAbsolute && clean != "data" && !clean.startsWith("data/")) return Drafts.remove(draft, clean)
        val file = resolve(path)
        return file.exists() && file.deleteRecursively()
    }

    /** How a file is named to the agent: relative when it is inside the workspace. */
    fun display(file: File): String {
        val path = file.path
        Drafts.relative(file)?.let { return it }
        return when {
            path.startsWith(root.path + File.separator) -> path.substring(root.path.length + 1)
            path.startsWith(data.path + File.separator) -> "data/" + path.substring(data.path.length + 1)
            else -> path
        }
    }

    // ---- factory copy -------------------------------------------------------------------

    /**
     * Installs the factory tree. A file the agent has changed is kept; a file still equal to
     * the factory version it came from follows the new factory version.
     * With [reset], every factory file under that prefix is put back.
     */
    fun installFactory(ctx: Context, reset: String? = null) {
        val manifestFile = File(state, "factory.json")
        val installed = runCatching { JSONObject(manifestFile.readText()) }.getOrElse { JSONObject() }
        val next = JSONObject()
        for (path in assetTree(ctx.assets, FACTORY)) {
            val bytes = ctx.assets.open("$FACTORY/$path").use { it.readBytes() }
            val hash = sha1(bytes)
            next.put(path, hash)
            val target = File(root, path)
            val forced = reset != null && path.startsWith(reset)
            val untouched = !target.exists() ||
                (installed.has(path) && sha1(target.readBytes()) == installed.getString(path))
            if (forced || untouched) {
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
        }
        manifestFile.writeText(next.toString())
    }

    fun factoryText(ctx: Context, path: String): String? =
        runCatching { ctx.assets.open("$FACTORY/$path").use { it.readBytes().decodeToString() } }.getOrNull()

    private fun assetTree(assets: AssetManager, dir: String, prefix: String = ""): List<String> {
        val out = ArrayList<String>()
        for (name in assets.list(dir).orEmpty()) {
            val children = assets.list("$dir/$name").orEmpty()
            if (children.isEmpty()) out.add(prefix + name)
            else out.addAll(assetTree(assets, "$dir/$name", "$prefix$name/"))
        }
        return out
    }

    // ---- snapshots ----------------------------------------------------------------------

    /** What the tree looks like: equal signatures mean nothing was added, removed or edited. */
    fun signature(): String {
        val digest = MessageDigest.getInstance("SHA-1")
        root.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach {
            digest.update("${it.path}|${it.length()}|${it.lastModified()}\n".toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Records a complete file manifest; unchanged bytes are shared by every snapshot. */
    @Synchronized
    fun snapshot(label: String): String {
        val id = historyStore.create(root, label)
        maintainHistory()
        return id
    }

    @Synchronized
    fun dropSnapshot(id: String) = historyStore.drop(id)

    @Synchronized
    fun snapshots(): JSONArray = historyStore.snapshots()

    /** Run on a worker: old IDs and metadata are preserved, with no retention pruning. */
    @Synchronized
    fun migrateHistory(): JSONObject = historyStore.migrate().json()

    /** Startup migration skips the full integrity/GC pass once all old directory copies are gone. */
    @Synchronized
    fun migrateHistoryIfNeeded(): JSONObject? = if (historyStore.needsMigration()) historyStore.migrate().json() else null

    @Synchronized
    fun historyStats(): JSONObject = historyStore.stats()

    /** Puts the workspace back as it was in that snapshot; the current state is kept as a new one. */
    @Synchronized
    fun restore(id: String) {
        recoverRestore()
        val token = UUID.randomUUID().toString()
        val stage = File(root.parentFile, ".assi-restore-$token")
        val previous = File(root.parentFile, ".assi-before-$token")
        val journal = File(state, "restore-transaction.json")
        try {
            // Validate and copy the target before making an undo snapshot (which may prune it).
            val snapshot = historyStore.materialize(id, stage)
            snapshot.legacyMaxFileBytes?.let { limit ->
                // The old format omitted these files: their historical bytes cannot be recovered.
                // Keep today's copy instead of silently deleting data the old snapshot never held.
                liveFiles().filter { (path, file) -> path !in snapshot.files && file.length() > limit }
                    .forEach { (path, file) ->
                        val target = File(stage, path)
                        require(!restorePathExists(target) && (target.parentFile.mkdirs() || target.parentFile.isDirectory)) {
                            "Старый снимок конфликтует с несохранённым большим файлом: $path"
                        }
                        copyPreservedFile(file, target)
                        if (file.canExecute()) target.setExecutable(true, true)
                    }
            }
            syncTree(stage)
            historyStore.create(root, "Перед откатом к $id")
            writeRestoreJournal(journal, JSONObject().put("version", 1)
                .put("stage", stage.name).put("previous", previous.name))
            moveDirectory(root, previous)
            moveDirectory(stage, root)
        } catch (error: Exception) {
            if (restorePathExists(journal)) {
                runCatching { recoverRestore() }.exceptionOrNull()?.let(error::addSuppressed)
            } else if (restorePathExists(stage)) {
                runCatching { deleteRestoreTree(stage) }.exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        }
        // The new workspace is committed. Cleanup/retention trouble must not report a failed restore.
        runCatching { recoverRestore() }.onFailure { maintenanceError(it) }
        maintainHistory()
    }

    /** What differs between that snapshot and the workspace now: `[{path, kind}]`, kind is added, changed or removed. */
    @Synchronized
    fun changes(id: String): JSONArray {
        val snapshot = historyStore.read(id)
        val old = snapshot.files
        val now = liveFiles()
        val out = JSONArray()
        for (path in (old.keys + now.keys).sorted()) {
            if (path !in old && snapshot.legacyMaxFileBytes != null && now.getValue(path).length() > snapshot.legacyMaxFileBytes) continue
            val kind = when {
                path !in old -> "added"
                path !in now -> "removed"
                old.getValue(path).size != now.getValue(path).length() ||
                    old.getValue(path).hash != sha256(now.getValue(path)) -> "changed"
                else -> continue
            }
            out.put(JSONObject().put("path", path).put("kind", kind))
        }
        return out
    }

    /** How one file differs from its state in the snapshot, the way git shows it. */
    @Synchronized
    fun diff(id: String, path: String): String {
        SnapshotStore.validatePath(path)
        val snapshot = historyStore.read(id)
        val old = snapshot.files[path]?.let { historyStore.content(snapshot, path) }
        val now = liveFiles()[path]
        if (old == null && now != null && snapshot.legacyMaxFileBytes != null && now.length() > snapshot.legacyMaxFileBytes) {
            return "Большой файл не входил в старый снимок; его текущая версия сохраняется при откате."
        }
        if ((old?.length() ?: 0) > MAX_DIFF_FILE || (now?.length() ?: 0) > MAX_DIFF_FILE) {
            return "Файл слишком большой для построчного сравнения: было ${old?.length() ?: 0}, стало ${now?.length() ?: 0} байт."
        }
        fun text(file: File?): String? = file?.readText()
        val before = text(old)
        val after = text(now)
        if (before?.contains('\u0000') == true || after?.contains('\u0000') == true) {
            return if (old != null && now != null && sha256(old) == sha256(now)) "Без изменений."
                else "Двоичный файл изменён: было ${old?.length() ?: 0}, стало ${now?.length() ?: 0} байт."
        }
        return unified(before, after)
    }

    /**
     * The difference between two texts the way git shows it: hunks headed by `@@ -a,b +c,d @@`,
     * lines marked with a space, `-` or `+`, three unchanged lines around every change.
     * A null text is a file that is not there (new or deleted).
     */
    fun unified(before: String?, after: String?, context: Int = 3): String {
        if (before == after) return "Без изменений."
        val old = before?.lines() ?: emptyList()
        val now = after?.lines() ?: emptyList()
        // What the two share at the start and at the end is set aside; the middle is compared line by line.
        var head = 0
        while (head < old.size && head < now.size && old[head] == now[head]) head++
        var tail = 0
        while (tail < old.size - head && tail < now.size - head && old[old.size - 1 - tail] == now[now.size - 1 - tail]) tail++
        val a = old.subList(head, old.size - tail)
        val b = now.subList(head, now.size - tail)
        if (a.size.toLong() * b.size > 4_000_000) {
            return "Файл изменён слишком сильно, чтобы показать разницу построчно: строк было ${old.size}, стало ${now.size}."
        }
        val common = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                common[i][j] = if (a[i] == b[j]) common[i + 1][j + 1] + 1 else maxOf(common[i + 1][j], common[i][j + 1])
            }
        }
        // Every line of the stretch that is shown: its mark and its text.
        val lead = minOf(head, context)
        val marks = ArrayList<Char>()
        val texts = ArrayList<String>()
        fun put(mark: Char, line: String) {
            marks.add(mark)
            texts.add(line)
        }
        for (k in head - lead until head) put(' ', old[k])
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> { put(' ', a[i]); i++; j++ }
                i < a.size && (j == b.size || common[i + 1][j] >= common[i][j + 1]) -> { put('-', a[i]); i++ }
                else -> { put('+', b[j]); j++ }
            }
        }
        for (k in 0 until minOf(tail, context)) put(' ', now[now.size - tail + k])

        // Changes that are close together share a hunk.
        val out = StringBuilder()
        var written = 0
        var at = 0
        while (at < marks.size) {
            if (marks[at] == ' ') {
                at++
                continue
            }
            val from = maxOf(0, at - context)
            var last = at
            var next = at + 1
            while (next < marks.size) {
                if (marks[next] != ' ') last = next else if (next - last > context * 2) break
                next++
            }
            val to = minOf(marks.size, last + context + 1)
            val oldBefore = (0 until from).count { marks[it] != '+' }
            val newBefore = (0 until from).count { marks[it] != '-' }
            val oldCount = (from until to).count { marks[it] != '+' }
            val newCount = (from until to).count { marks[it] != '-' }
            val oldStart = if (oldCount == 0) 0 else head - lead + oldBefore + 1
            val newStart = if (newCount == 0) 0 else head - lead + newBefore + 1
            out.append("@@ -").append(oldStart).append(',').append(oldCount).append(" +").append(newStart).append(',').append(newCount).append(" @@\n")
            for (k in from until to) {
                if (written++ >= DIFF_LINES) {
                    out.append("… разница длиннее: показаны первые $DIFF_LINES строк\n")
                    return out.toString().trimEnd()
                }
                out.append(marks[k]).append(texts[k]).append('\n')
            }
            at = to
        }
        return out.toString().trimEnd()
    }

    private const val DIFF_LINES = 400

    private fun maintainHistory() {
        runCatching { historyStore.prune(KEEP_SNAPSHOTS) }.onFailure { maintenanceError(it) }
    }

    private fun maintenanceError(error: Throwable) {
        runCatching { Engine.host.log("error", "История: ${error.message}") }
    }

    private fun liveFiles(): Map<String, File> {
        val base = root.toPath()
        require(!Files.isSymbolicLink(base)) { "Рабочая папка не должна быть ссылкой." }
        val files = linkedMapOf<String, File>()
        Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(attrs.isRegularFile && !attrs.isSymbolicLink) { "Ссылка или специальный файл в рабочей папке: ${file.fileName}" }
                files[SnapshotStore.validatePath(base.relativize(file).toString().replace(File.separatorChar, '/'))] = file.toFile()
                return FileVisitResult.CONTINUE
            }
        })
        return files
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file.toPath(), READ, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Large files omitted by legacy snapshots need the same stable-copy checks as captured files. */
    private fun copyPreservedFile(source: File, destination: File) {
        val before = Files.readAttributes(source.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(before.isRegularFile && !before.isSymbolicLink) { "Нельзя сохранить ссылку вместо большого файла." }
        var size = 0L
        Files.newInputStream(source.toPath(), READ, NOFOLLOW_LINKS).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    size += count
                }
                output.fd.sync()
            }
        }
        val after = Files.readAttributes(source.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(after.isRegularFile && !after.isSymbolicLink && size == before.size() && size == after.size() &&
            before.lastModifiedTime() == after.lastModifiedTime() && before.fileKey() == after.fileKey()) {
            "Большой файл изменился во время подготовки отката: ${source.name}"
        }
    }

    /** Unknown filesystem state must not be mistaken for an absent recovery journal or tree. */
    private fun restorePathExists(file: File): Boolean {
        val path = file.toPath()
        if (Files.exists(path, NOFOLLOW_LINKS)) return true
        if (Files.notExists(path, NOFOLLOW_LINKS)) return false
        throw IOException("Не удалось проверить данные восстановления: ${file.name}")
    }

    private fun writeRestoreJournal(file: File, value: JSONObject) {
        require(!Files.isSymbolicLink(file.toPath())) { "Журнал восстановления не должен быть ссылкой." }
        val temporary = File(state, ".restore-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { it.write(value.toString().toByteArray()); it.fd.sync() }
            try {
                Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING)
            }
            syncDirectory(state)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    /** The journal names only generated sibling directories; never accept arbitrary deletion paths. */
    private fun restoreDirectory(name: String, prefix: String): File {
        require(name.startsWith(prefix) && name.removePrefix(prefix).matches(
            Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))) {
            "Некорректный журнал восстановления."
        }
        val file = File(root.parentFile, name)
        require(!Files.isSymbolicLink(file.toPath()) && file.canonicalFile.parentFile == root.parentFile.canonicalFile) {
            "Некорректный каталог восстановления."
        }
        return file
    }

    private fun recoverRestore() {
        val journal = File(state, "restore-transaction.json")
        if (!restorePathExists(journal)) return
        require(!Files.isSymbolicLink(journal.toPath()) && journal.isFile) { "Некорректный журнал восстановления." }
        val value = JSONObject(journal.readText())
        require(value.getInt("version") == 1) { "Неизвестный формат журнала восстановления." }
        val stage = restoreDirectory(value.getString("stage"), ".assi-restore-")
        val previous = restoreDirectory(value.getString("previous"), ".assi-before-")
        require(!Files.isSymbolicLink(root.toPath())) { "Рабочая папка не должна быть ссылкой." }
        if (!restorePathExists(root)) {
            require(previous.isDirectory) { "Не найдена рабочая папка до прерванного отката. Данные восстановления сохранены." }
            moveDirectory(previous, root)
        } else {
            require(root.isDirectory) { "Рабочая папка повреждена." }
            require(!(restorePathExists(stage) && restorePathExists(previous))) { "Неоднозначное состояние отката. Данные восстановления сохранены." }
        }
        // A process may have stopped after stage -> root but before the rename was synced.
        // Persist the surviving root entry before deleting either recoverable tree.
        syncDirectory(root.parentFile)
        if (restorePathExists(stage)) deleteRestoreTree(stage)
        if (restorePathExists(previous)) deleteRestoreTree(previous)
        Files.delete(journal.toPath())
        syncDirectory(state)
    }

    private fun moveDirectory(source: File, destination: File) {
        require(!restorePathExists(destination)) { "Каталог назначения уже существует." }
        try {
            Files.move(source.toPath(), destination.toPath(), ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
        syncDirectory(destination.parentFile)
    }

    private fun syncDirectory(directory: File) {
        try {
            FileChannel.open(directory.toPath(), READ).use { it.force(true) }
        } catch (error: IOException) {
            // Windows tests cannot open directories with FileChannel. Android must persist the rename.
            if (File.separatorChar != '\\') throw error
        }
    }

    private fun syncTree(directory: File) {
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(attrs.isRegularFile && !attrs.isSymbolicLink)
                FileChannel.open(file, java.nio.file.StandardOpenOption.WRITE).use { it.force(true) }
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                syncDirectory(dir.toFile())
                return FileVisitResult.CONTINUE
            }
        })
        syncDirectory(directory.parentFile)
    }

    private fun deleteRestoreTree(directory: File) {
        val prefix = if (directory.name.startsWith(".assi-restore-")) ".assi-restore-" else ".assi-before-"
        require(restoreDirectory(directory.name, prefix) == directory)
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
        syncDirectory(directory.parentFile)
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
}
