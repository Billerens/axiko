package app.axiko.core

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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Immutable file contents shared by snapshots. Manifests are the commit records. */
class SnapshotStore(
    private val history: File,
    private val afterManifestCommitted: ((String) -> Unit)? = null,
) {
    data class Entry(val hash: String, val size: Long, val executable: Boolean = false)

    data class Snapshot(
        val id: String,
        val meta: JSONObject,
        val files: Map<String, Entry>,
        val directories: List<String>,
        val legacyMaxFileBytes: Long? = null,
    )

    data class MigrationReport(
        val migrated: Int,
        val remainingLegacy: Int,
        val snapshots: Int,
        val objects: Int,
        val objectBytes: Long,
        val logicalBytes: Long,
    ) {
        fun json(): JSONObject = JSONObject().put("migrated", migrated).put("remainingLegacy", remainingLegacy)
            .put("snapshots", snapshots).put("objects", objects).put("objectBytes", objectBytes)
            .put("logicalBytes", logicalBytes).put("format", FORMAT)
    }

    private data class Tree(val files: Map<String, File>, val directories: List<String>)
    private data class Digest(val hash: String, val size: Long)
    private val objectRoot get() = child(history, ".objects", internal = true)

    /**
     * What each committed manifest refers to, by snapshot: `(size, time of change) -> hashes`. A manifest
     * is never rewritten once published, so it is read once; one that differs from what was read is read anew.
     */
    private val referenced = HashMap<String, Triple<Long, Long, Set<String>>>()

    /**
     * A file as it was when its bytes were last read: its size, time of change and identity, what it hashed to
     * and when the reading began. A file that is the same in all three is not read again, the way the tree's
     * signature and git's index trust them. One changed close to the reading is not trusted: a later write
     * within the same tick of the clock would leave no trace, so it is read anew until its time is well behind.
     */
    private class Seen(val size: Long, val modified: Long, val key: Any?, val entry: Entry, val readAt: Long)
    private val seen = HashMap<String, Seen>()

    companion object {
        const val FORMAT = 2
        const val LEGACY_MAX_FILE_BYTES = 5L * 1024 * 1024
        private val HASH = Regex("[a-f0-9]{64}")
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        /** How long before a reading a file must have been changed for the reading to be trusted later. */
        private const val SETTLED_MS = 2000L

        fun validateId(id: String): String {
            require(ID.matches(id) && id != "." && id != "..") { "Некорректный идентификатор снимка." }
            return id
        }

        fun validatePath(path: String): String {
            require(path.isNotEmpty() && !path.startsWith('/') && !path.contains('\\') && !path.contains(':') &&
                path.none { it.code < 32 } && path.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
                "Некорректный путь файла снимка."
            }
            return path
        }

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }

    /** New snapshots capture every regular file, including files larger than the old 5 MiB limit. */
    @Synchronized
    fun create(root: File, label: String): String {
        ensureDirectory(history)
        val newest = snapshotDirectories().maxOfOrNull { metadata(it).getLong("time") } ?: 0L
        require(newest < Long.MAX_VALUE) { "Некорректное время снимка." }
        val time = maxOf(System.currentTimeMillis(), newest + 1)
        val id = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(time)) + "-" + UUID.randomUUID().toString().take(8)
        val pending = child(history, ".pending-" + id, internal = true)
        require(!exists(pending)) { "Каталог нового снимка уже существует." }
        ensureDirectory(pending)
        var published = false
        try {
            val tree = scan(root)
            val verified = HashSet<String>()
            val objects = objectRoot
            // Each entry is checked as it is made: its object is there and of its size, read in full when new or changed.
            val entries = tree.files.mapValues { (_, file) -> storeFile(file, verified, objects) }
            seen.keys.retainAll(tree.files.values.mapTo(HashSet()) { it.path })
            val meta = JSONObject().put("id", id).put("time", time).put("label", label.take(200))
            val snapshot = Snapshot(id, meta, entries, tree.directories)
            writeAtomic(child(pending, "manifest.json"), manifest(snapshot).toString().toByteArray())
            writeAtomic(child(pending, "meta.json"), meta.toString().toByteArray())
            move(pending, snapshotDirectory(id), replace = false)
            published = true
            remember(snapshotDirectory(id), entries.values.mapTo(HashSet()) { it.hash })
            afterManifestCommitted?.invoke(id)
            return id
        } finally {
            if (!published && exists(pending)) deleteTree(pending)
        }
    }

    @Synchronized
    fun snapshots(): JSONArray = JSONArray().also { result ->
        orderedDirectories().forEach { result.put(metadata(it)) }
    }

    /** A legacy read publishes a manifest, but leaves its old files available until migration verifies them. */
    @Synchronized
    fun read(id: String): Snapshot = readInternal(validateId(id), HashSet())

    @Synchronized
    fun content(snapshot: Snapshot, path: String): File {
        val entry = snapshot.files[validatePath(path)] ?: throw IllegalArgumentException("Нет файла в снимке: $path")
        return verifyEntry(entry, HashSet())
    }

    /** All references are checked before any destination file is made. The caller stages this outside its live workspace. */
    @Synchronized
    fun materialize(id: String, destination: File): Snapshot {
        val snapshot = readInternal(validateId(id), HashSet())
        verify(snapshot, HashSet())
        require(!Files.isSymbolicLink(destination.toPath())) { "Каталог восстановления не должен быть ссылкой." }
        require(!exists(destination) || (destination.isDirectory && children(destination).isEmpty())) {
            "Каталог восстановления должен быть пустым."
        }
        val target = destination.canonicalFile
        val source = history.canonicalFile
        require(target != source && !target.toPath().startsWith(source.toPath())) {
            "Нельзя восстанавливать файлы внутрь истории."
        }
        ensureDirectory(destination)
        snapshot.directories.sortedBy { it.count { ch -> ch == '/' } }.forEach { ensureDirectory(relative(destination, it)) }
        snapshot.files.forEach { (path, entry) ->
            val file = relative(destination, path)
            ensureDirectory(file.parentFile)
            val copied = copyAndDigest(blob(entry.hash), file)
            require(copied.hash == entry.hash && copied.size == entry.size) { "Файл истории изменился во время восстановления: $path" }
            if (entry.executable) require(file.setExecutable(true, true) || file.canExecute()) { "Не удалось восстановить права: $path" }
        }
        snapshot.directories.sortedByDescending { it.count { ch -> ch == '/' } }
            .forEach { syncDirectory(relative(destination, it)) }
        syncDirectory(destination)
        return snapshot
    }

    /** Startup probe: inspect only snapshot metadata and legacy directory names, never blob contents. */
    @Synchronized
    fun needsMigration(): Boolean = snapshotDirectories().any { directory ->
        listOf("files", ".migrated-files").any { name ->
            val legacy = child(directory, name, internal = name.startsWith('.'))
            if (exists(legacy)) {
                requireDirectory(legacy)
                true
            } else false
        }
    }

    /** Imports old snapshots in place, preserving IDs, timestamps and labels. Does not prune snapshots. */
    @Synchronized
    fun migrate(): MigrationReport {
        ensureDirectory(history)
        var migrated = 0
        val verified = HashSet<String>()
        for (directory in orderedDirectories().reversed()) {
            val legacy = child(directory, "files")
            val retired = child(directory, ".migrated-files", internal = true)
            if (exists(retired)) {
                // Renaming the fully checked old tree is the cleanup commit. A process death
                // during its recursive deletion must not turn a good snapshot into a mismatch.
                require(!exists(legacy)) { "Два старых дерева в снимке ${directory.name}." }
                verify(parse(directory), verified)
                syncDirectory(directory)
                deleteTree(retired)
                syncDirectory(directory)
                migrated++
                continue
            }
            if (!exists(legacy)) continue
            val snapshot = readInternal(directory.name, verified)
            // A crash after publishing a manifest may leave both representations. Never remove
            // the old representation until every path and object still agrees with it.
            verify(snapshot, verified)
            val old = scan(legacy)
            require(old.files.keys == snapshot.files.keys && old.directories.toSet() == snapshot.directories.toSet()) {
                "Старые файлы не совпадают с манифестом снимка ${snapshot.id}."
            }
            for ((path, file) in old.files) {
                val digest = digestStable(file)
                val entry = snapshot.files.getValue(path)
                require(digest.hash == entry.hash && digest.size == entry.size) { "Старый файл снимка изменился: ${snapshot.id}/$path" }
            }
            move(legacy, retired, replace = false)
            deleteTree(retired)
            syncDirectory(directory)
            migrated++
        }
        discardUnreferenced(referencesExcept(emptySet(), contents = true))
        val summary = stats()
        return MigrationReport(migrated, summary.getInt("legacySnapshots"), summary.getInt("snapshots"),
            summary.getInt("objects"), summary.getLong("objectBytes"), summary.getLong("logicalBytes"))
    }

    @Synchronized
    fun drop(id: String) {
        val directory = snapshotDirectory(validateId(id))
        require(exists(directory)) { "Нет снимка $id" }
        metadata(directory)
        removeSnapshots(setOf(id))
    }

    @Synchronized
    fun prune(keep: Int, pinned: Set<String> = emptySet()) {
        require(keep >= 0)
        pinned.forEach(::validateId)
        val remove = orderedDirectories().drop(keep).map { it.name }.filterNot { it in pinned }.toSet()
        if (remove.isNotEmpty()) removeSnapshots(remove) else collectGarbage()
    }

    /** Corrupt or unknown committed manifests prevent reclamation; their objects are never guessed away. */
    @Synchronized
    fun collectGarbage() {
        if (!exists(history)) return
        val references = referencesExcept(emptySet())
        discardUnreferenced(references)
    }

    @Synchronized
    fun stats(): JSONObject {
        val directories = snapshotDirectories()
        var logical = 0L
        var legacy = 0
        for (directory in directories) {
            val manifest = child(directory, "manifest.json")
            if (exists(manifest)) logical += parse(directory).files.values.sumOf { it.size }
            else logical += scan(child(directory, "files")).files.values.sumOf { it.length() }
            if (exists(child(directory, "files"))) legacy++
        }
        val objects = objects()
        return JSONObject().put("format", FORMAT).put("snapshots", directories.size)
            .put("legacySnapshots", legacy).put("remainingLegacy", legacy)
            .put("objects", objects.size).put("objectBytes", objects.sumOf { it.length() })
            .put("logicalBytes", logical).put("physicalBytes", if (exists(history)) scan(history).files.values.sumOf { it.length() } else 0L)
    }

    private fun readInternal(id: String, verified: MutableSet<String>): Snapshot {
        val directory = snapshotDirectory(id)
        val meta = metadata(directory)
        val manifestFile = child(directory, "manifest.json")
        if (exists(manifestFile)) return parse(directory)
        val legacy = child(directory, "files")
        require(exists(legacy)) { "В снимке $id нет файлов или манифеста." }
        val tree = scan(legacy)
        val snapshot = Snapshot(id, meta, tree.files.mapValues { (_, file) -> storeFile(file, verified) },
            tree.directories, LEGACY_MAX_FILE_BYTES)
        writeAtomic(manifestFile, manifest(snapshot).toString().toByteArray())
        afterManifestCommitted?.invoke(id)
        return snapshot
    }

    private fun manifest(snapshot: Snapshot): JSONObject {
        val files = JSONObject()
        snapshot.files.toSortedMap().forEach { (path, file) ->
            files.put(path, JSONObject().put("sha256", file.hash).put("size", file.size).put("executable", file.executable))
        }
        return JSONObject().put("format", FORMAT).put("files", files).put("directories", JSONArray(snapshot.directories.sorted()))
            .also { snapshot.legacyMaxFileBytes?.let { size -> it.put("legacyMaxFileBytes", size) } }
    }

    private fun parse(directory: File): Snapshot {
        val meta = metadata(directory)
        val source = child(directory, "manifest.json")
        requireRegular(source)
        val json = JSONObject(source.readText())
        require(json.getInt("format") == FORMAT) { "Неизвестный формат снимка ${directory.name}." }
        val files = LinkedHashMap<String, Entry>()
        val fileJson = json.getJSONObject("files")
        for (path in fileJson.keys()) {
            validatePath(path)
            val entry = fileJson.getJSONObject(path)
            val hash = entry.getString("sha256")
            val size = entry.getLong("size")
            require(HASH.matches(hash) && size >= 0) { "Некорректная запись файла в снимке ${directory.name}." }
            files[path] = Entry(hash, size, entry.optBoolean("executable", false))
        }
        val dirJson = json.getJSONArray("directories")
        val directories = List(dirJson.length()) { validatePath(dirJson.getString(it)) }
        require(directories.distinct().size == directories.size) { "Повторяющиеся каталоги в снимке." }
        val directorySet = directories.toSet()
        require(files.keys.none { it in directorySet }) { "Файл и каталог имеют один путь в снимке." }
        for (path in files.keys + directories) {
            var parent = path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                require(parent !in files && parent in directorySet) { "Некорректное дерево путей в снимке." }
                parent = parent.substringBeforeLast('/', "")
            }
        }
        val legacyLimit = if (json.has("legacyMaxFileBytes")) json.getLong("legacyMaxFileBytes") else null
        require(legacyLimit == null || legacyLimit == LEGACY_MAX_FILE_BYTES) { "Неизвестное ограничение старого снимка." }
        return Snapshot(directory.name, meta, files, directories, legacyLimit)
    }

    private fun metadata(directory: File): JSONObject {
        requireDirectory(directory)
        val file = child(directory, "meta.json")
        requireRegular(file)
        val meta = JSONObject(file.readText())
        require(meta.getString("id") == directory.name && meta.getLong("time") >= 0 && meta.get("label") is String) {
            "Повреждены сведения о снимке ${directory.name}."
        }
        return meta
    }

    private fun snapshotDirectories(): List<File> {
        if (!exists(history)) return emptyList()
        requireDirectory(history)
        return children(history).filter { file ->
            if (file.name.startsWith('.')) return@filter false
            require(!Files.isSymbolicLink(file.toPath())) { "Ссылка вместо каталога истории." }
            if (!file.isDirectory) return@filter false
            validateId(file.name)
            val meta = child(file, "meta.json")
            if (!exists(meta)) {
                require(!exists(child(file, "manifest.json"))) { "Манифест снимка без метаданных: ${file.name}" }
                return@filter false // An interrupted old capture never published a snapshot.
            }
            metadata(file)
            true
        }
    }

    private fun orderedDirectories(): List<File> = snapshotDirectories().map { it to metadata(it).getLong("time") }
        .sortedWith(compareByDescending<Pair<File, Long>> { it.second }.thenByDescending { it.first.name }).map { it.first }

    private fun verify(snapshot: Snapshot, verified: MutableSet<String>) {
        snapshot.files.values.forEach { verifyEntry(it, verified) }
    }

    private fun verifyEntry(entry: Entry, verified: MutableSet<String>): File {
        val file = blob(entry.hash)
        requireRegular(file)
        require(file.length() == entry.size) { "Неверный размер объекта истории ${entry.hash}." }
        if (verified.add(entry.hash)) {
            val value = digestStable(file)
            require(value.hash == entry.hash && value.size == entry.size) { "Повреждён объект истории ${entry.hash}." }
        }
        return file
    }

    private fun storeFile(source: File, verified: MutableSet<String>, objects: File? = null): Entry {
        val readAt = System.currentTimeMillis()
        val stamp = attributes(source)
        if (objects != null) seen[source.path]?.let { known ->
            val modified = stamp.lastModifiedTime().toMillis()
            val unchanged = stamp.isRegularFile && known.size == stamp.size() && known.modified == modified && known.key == stamp.fileKey()
            if (unchanged && modified < known.readAt - SETTLED_MS) {
                // Its object may have gone with the last snapshot that held it: then the file is stored again below.
                val kept = runCatching { attributes(File(File(objects, known.entry.hash.take(2)), known.entry.hash.drop(2))) }.getOrNull()
                if (kept != null && kept.isRegularFile && kept.size() == known.entry.size) {
                    verified.add(known.entry.hash)
                    return known.entry.copy(executable = source.canExecute())
                }
            }
        }
        return storeRead(source, verified).also { entry ->
            val after = attributes(source)
            if (objects != null && sameFile(stamp, after)) seen[source.path] = Seen(after.size(), after.lastModifiedTime().toMillis(), after.fileKey(), entry, readAt)
        }
    }

    private fun storeRead(source: File, verified: MutableSet<String>): Entry {
        repeat(3) { attempt ->
            val digest = digestStable(source)
            val entry = Entry(digest.hash, digest.size, source.canExecute())
            val target = blob(entry.hash)
            if (exists(target)) {
                // An object is written once, under the name of its own hash, and never opened for writing again:
                // here it is enough that it is there and of the right size. Its bytes are checked when they are
                // needed, before a restore writes anything.
                requireRegular(target)
                require(target.length() == entry.size) { "Неверный размер объекта истории ${entry.hash}." }
                verified.add(entry.hash)
                return entry
            }
            ensureDirectory(target.parentFile)
            val temporary = child(objectRoot, ".blob-" + UUID.randomUUID() + ".tmp", internal = true)
            try {
                val copied = copyAndDigest(source, temporary)
                if (copied != digest) {
                    if (attempt < 2) return@repeat
                    error("Файл меняется во время создания снимка: ${source.name}")
                }
                move(temporary, target, replace = false)
                verified.add(entry.hash)
                return entry
            } finally { if (exists(temporary)) Files.delete(temporary.toPath()) }
        }
        error("Не удалось сохранить файл истории.")
    }

    private fun digestStable(file: File): Digest {
        repeat(3) {
            val before = attributes(file)
            require(before.isRegularFile) { "В истории поддерживаются только обычные файлы: ${file.name}" }
            val hash = MessageDigest.getInstance("SHA-256")
            var size = 0L
            Files.newInputStream(file.toPath(), READ, NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    hash.update(buffer, 0, read); size += read
                }
            }
            val after = attributes(file)
            if (sameFile(before, after) && size == after.size()) return Digest(hex(hash.digest()), size)
        }
        error("Файл меняется во время создания снимка: ${file.name}")
    }

    private fun copyAndDigest(source: File, target: File): Digest {
        val before = attributes(source)
        require(before.isRegularFile) { "Нельзя копировать ссылку или специальный файл в историю." }
        require(!exists(target)) { "Файл назначения уже существует." }
        val hash = MessageDigest.getInstance("SHA-256")
        var size = 0L
        Files.newInputStream(source.toPath(), READ, NOFOLLOW_LINKS).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read); hash.update(buffer, 0, read); size += read
                }
                output.fd.sync()
            }
        }
        val after = attributes(source)
        require(sameFile(before, after) && size == after.size()) { "Файл изменился во время копирования: ${source.name}" }
        return Digest(hex(hash.digest()), size)
    }

    private fun sameFile(before: BasicFileAttributes, after: BasicFileAttributes): Boolean =
        after.isRegularFile && before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime() && before.fileKey() == after.fileKey()

    /**
     * The objects every snapshot but the [excluded] ones refers to. Every committed manifest must be readable,
     * or nothing is reclaimed. With [contents] each object is also read and compared with its hash: that is
     * for a migration; taking and dropping a snapshot many times a day only need to know what is referred to.
     */
    private fun referencesExcept(excluded: Set<String>, contents: Boolean = false): Set<String> {
        val references = HashSet<String>()
        val verified = HashSet<String>()
        val present = HashSet<String>()
        for (directory in snapshotDirectories()) {
            present.add(directory.name)
            if (directory.name in excluded) continue
            val manifest = child(directory, "manifest.json")
            if (!exists(manifest)) {
                scan(child(directory, "files")) // Valid legacy snapshots own their old files, not objects.
                continue
            }
            val attrs = attributes(manifest)
            val known = referenced[directory.name]?.takeIf { !contents && it.first == attrs.size() && it.second == attrs.lastModifiedTime().toMillis() }
            if (known != null) { references.addAll(known.third); continue }
            val snapshot = parse(directory)
            if (contents) verify(snapshot, verified)
            references.addAll(remember(directory, snapshot.files.values.mapTo(HashSet()) { it.hash }))
        }
        referenced.keys.retainAll(present)
        return references
    }

    private fun remember(directory: File, hashes: Set<String>): Set<String> {
        val attrs = attributes(child(directory, "manifest.json"))
        // The same few thousand hashes repeat in every manifest: one string for each, however many snapshots there are.
        val shared = hashes.mapTo(HashSet()) { it.intern() }
        referenced[directory.name] = Triple(attrs.size(), attrs.lastModifiedTime().toMillis(), shared)
        return shared
    }

    private fun removeSnapshots(ids: Set<String>) {
        val references = referencesExcept(ids)
        for (id in ids) {
            val directory = snapshotDirectory(id)
            val deleting = child(history, ".deleting-" + id + "-" + UUID.randomUUID(), internal = true)
            move(directory, deleting, replace = false)
            deleteTree(deleting)
            referenced.remove(id)
        }
        discardUnreferenced(references)
    }

    private fun objects(): List<File> {
        if (!exists(objectRoot)) return emptyList()
        requireDirectory(objectRoot)
        return children(objectRoot).filter { Regex("[a-f0-9]{2}").matches(it.name) }.flatMap { directory ->
            requireDirectory(directory)
            children(directory).filter { HASH.matches(directory.name + it.name) }.onEach(::requireRegular)
        }
    }

    private fun discardUnreferenced(references: Set<String>) {
        for (file in objects()) {
            if (file.parentFile.name + file.name !in references) Files.delete(file.toPath())
        }
        if (exists(objectRoot)) {
            children(objectRoot).forEach { file ->
                if (Regex("\\.blob-[a-f0-9-]+\\.tmp").matches(file.name)) {
                    requireRegular(file); Files.delete(file.toPath())
                } else if (Regex("[a-f0-9]{2}").matches(file.name) && children(file).isEmpty()) {
                    requireDirectory(file); Files.delete(file.toPath())
                }
            }
        }
        children(history).filter { Regex("\\.(pending|deleting)-[A-Za-z0-9._-]+").matches(it.name) }.forEach(::deleteTree)
        syncDirectory(history)
    }

    private fun scan(root: File): Tree {
        requireDirectory(root)
        val base = root.toPath().toAbsolutePath().normalize()
        val files = linkedMapOf<String, File>()
        val directories = arrayListOf<String>()
        Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(!attrs.isSymbolicLink) { "Ссылки на каталоги не поддерживаются в снимках." }
                if (dir != base) directories.add(validatePath(base.relativize(dir).toString().replace(File.separatorChar, '/')))
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(attrs.isRegularFile && !attrs.isSymbolicLink) { "Ссылка или специальный файл не может войти в снимок: ${file.fileName}" }
                files[validatePath(base.relativize(file).toString().replace(File.separatorChar, '/'))] = file.toFile()
                return FileVisitResult.CONTINUE
            }
        })
        return Tree(files.toSortedMap(), directories.sorted())
    }

    private fun snapshotDirectory(id: String): File = child(history, validateId(id))

    private fun blob(hash: String): File {
        require(HASH.matches(hash)) { "Некорректный хеш объекта истории." }
        return relative(objectRoot, hash.take(2) + "/" + hash.drop(2))
    }

    private fun relative(base: File, path: String): File {
        validatePath(path)
        var file = base
        require(!Files.isSymbolicLink(base.toPath())) { "Каталог хранения не должен быть ссылкой." }
        for (part in path.split('/')) file = child(file, part)
        return file
    }

    private fun child(base: File, name: String, internal: Boolean = false): File {
        require(name.isNotEmpty() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\') && !name.contains(':'))
        if (!internal) require(name.none { it.code < 32 })
        require(!Files.isSymbolicLink(base.toPath())) { "Каталог хранения не должен быть ссылкой." }
        val result = File(base, name)
        require(!Files.isSymbolicLink(result.toPath())) { "Ссылка вместо файла или каталога истории." }
        return result
    }

    private fun exists(file: File): Boolean {
        if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) return true
        if (Files.notExists(file.toPath(), NOFOLLOW_LINKS)) return false
        throw IOException("Не удалось проверить файл истории: ${file.name}")
    }
    private fun children(directory: File): Array<File> {
        requireDirectory(directory)
        return directory.listFiles() ?: throw IOException("Не удалось прочитать каталог истории: ${directory.name}")
    }
    private fun attributes(file: File): BasicFileAttributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    private fun requireRegular(file: File) {
        require(exists(file) && attributes(file).isRegularFile && !Files.isSymbolicLink(file.toPath())) { "Нет обычного файла истории: ${file.name}" }
    }
    private fun requireDirectory(file: File) {
        require(exists(file) && attributes(file).isDirectory && !Files.isSymbolicLink(file.toPath())) { "Нет каталога истории: ${file.name}" }
    }
    private fun ensureDirectory(file: File) {
        val parent = file.absoluteFile.parentFile
        if (!exists(file)) {
            if (parent != null) ensureDirectory(parent)
            Files.createDirectory(file.toPath())
        }
        requireDirectory(file)
        // Also completes a directory creation interrupted before its parent was synced.
        if (parent != null) syncDirectory(parent)
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        ensureDirectory(file.parentFile)
        val temporary = child(file.parentFile, ".write-" + UUID.randomUUID() + ".tmp", internal = true)
        try {
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            move(temporary, file, replace = true)
        } finally { if (exists(temporary)) Files.delete(temporary.toPath()) }
    }

    private fun move(source: File, destination: File, replace: Boolean) {
        try {
            if (replace) Files.move(source.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            else Files.move(source.toPath(), destination.toPath(), ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            if (replace) Files.move(source.toPath(), destination.toPath(), REPLACE_EXISTING)
            else Files.move(source.toPath(), destination.toPath())
        }
        syncDirectory(destination.parentFile)
        if (source.parentFile.absoluteFile != destination.parentFile.absoluteFile) syncDirectory(source.parentFile)
    }

    private fun syncDirectory(directory: File) {
        // Windows cannot open directories as FileChannels. Android/Linux must persist directory
        // entries too: a synced blob is useless if its newly created prefix directory is lost.
        try {
            FileChannel.open(directory.toPath(), READ).use { it.force(true) }
        } catch (error: IOException) {
            if (!System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) throw error
        }
    }

    private fun deleteTree(directory: File) {
        requireDirectory(directory)
        val target = directory.canonicalFile.toPath()
        val base = history.canonicalFile.toPath()
        require(target != base && target.startsWith(base)) { "Удаление допускается только внутри истории." }
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir); return FileVisitResult.CONTINUE
            }
        })
    }
}
