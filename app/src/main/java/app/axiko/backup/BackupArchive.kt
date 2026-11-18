package app.axiko.backup

import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The inner layer of a backup: a ZIP whose first entry, `manifest.json`, lists every other
 * entry with its size and SHA-256. Reading is one pass: each file is checked against the
 * manifest as it comes out, and a copy with a file missing, added or altered is refused.
 *
 * Nothing here knows about Android: the tests run it on the JVM.
 */
object BackupArchive {
    const val FORMAT = 1
    const val MANIFEST = "manifest.json"
    const val SETTINGS = "prefs/axiko.json"
    const val SECRETS = "secrets.json"

    /** Where each folder of the app's storage lies in the archive, and the part it is counted in. */
    val TREES: Map<String, String> = linkedMapOf(
        "files/axiko" to "workspace",
        "files/data" to "data",
        "files/state" to "state",
        "files/drafts" to "drafts",
        "files/history" to "history",
    )

    private const val MAX_MANIFEST = 256 * 1024 * 1024
    private const val BUFFER = 64 * 1024

    /** Formats that are compressed already: deflating them again only costs time. */
    private val PACKED = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "heic", "avif", "mp3", "m4a", "aac", "ogg", "opus", "flac", "mp4", "mkv",
        "webm", "mov", "zip", "gz", "bz2", "xz", "zst", "7z", "apk", "jar", "pdf", "onnx", "litertlm", "bin", "woff2",
    )

    /**
     * The part an archive path belongs to; anything outside the expected places is refused,
     * whatever the archive says: no `..`, no absolute paths, no backslashes, only known roots.
     */
    fun part(path: String, directory: Boolean = false): String {
        val sane = path.isNotEmpty() && path.length <= 4096 && !path.startsWith("/") && !path.endsWith("/") &&
            path.none { it == '\\' || it.code < 32 || it.code == 127 } &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }
        if (sane) {
            if (!directory && path == SETTINGS) return "settings"
            if (!directory && path == SECRETS) return "secrets"
            for ((root, id) in TREES) if (path.startsWith("$root/") || (directory && path == root)) return id
        }
        throw BackupException.Damaged("В копии есть путь, которого там быть не должно: ${path.take(120)}")
    }

    // ---- collecting ------------------------------------------------------------------------

    class Item internal constructor(
        val path: String,
        val part: String,
        val size: Long,
        val sha256: String,
        val modified: Long,
        val executable: Boolean,
        /** Exactly one of the three: bytes in memory, a place in the staging file, the file itself. */
        internal val bytes: ByteArray?,
        internal val staged: Long,
        internal val live: File?,
    )

    /**
     * Reads everything that goes into a copy once, before a byte of the copy is written: sizes and
     * hashes for the manifest. A file is read whole between two looks at its size and time, and
     * read again if they moved. Small files are kept aside in one staging file, so what is
     * written later is what was hashed even if the app goes on changing them; a large file is
     * read again from its place and the copy is refused ([BackupException.Changed]) if it differs.
     */
    class Collector(
        private val staging: File,
        private val smallLimit: Long = 8L * 1024 * 1024,
        private val cancelled: () -> Boolean = { false },
        private val seen: (files: Int, bytes: Long) -> Unit = { _, _ -> },
    ) : AutoCloseable {
        val items = ArrayList<Item>()
        val dirs = ArrayList<String>()

        /** Links, sockets and names an archive cannot carry: left out, and listed in the manifest. */
        val skipped = ArrayList<String>()
        var bytes = 0L
            private set
        private val blob = RandomAccessFile(staging, "rw").apply { setLength(0) }
        private var blobEnd = 0L

        /** Adds the folder [dir] under the archive path [root]; [skip] gets paths relative to [dir]. */
        fun tree(root: String, dir: File, skip: (String) -> Boolean = { false }) {
            val id = TREES[root] ?: error("Неизвестная часть копии: $root")
            val base = dir.toPath()
            if (!Files.isDirectory(base, NOFOLLOW_LINKS)) return
            Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
                private fun relative(path: Path) = base.relativize(path).toString().replace(File.separatorChar, '/')

                override fun preVisitDirectory(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val inner = relative(path)
                    if (inner.isNotEmpty() && skip(inner)) return FileVisitResult.SKIP_SUBTREE
                    val name = if (inner.isEmpty()) root else "$root/$inner"
                    if (runCatching { part(name, directory = true) }.isFailure) {
                        skipped.add(name)
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    dirs.add(name)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (cancelled()) throw BackupException.Cancelled()
                    val inner = relative(path)
                    if (skip(inner)) return FileVisitResult.CONTINUE
                    val name = "$root/$inner"
                    if (!attrs.isRegularFile || runCatching { part(name) }.isFailure) skipped.add(name) else add(name, id, path)
                    return FileVisitResult.CONTINUE
                }

                // A file deleted while the folder was being walked is simply not in the copy.
                override fun visitFileFailed(path: Path, error: IOException): FileVisitResult =
                    if (error is NoSuchFileException) FileVisitResult.CONTINUE else throw error
            })
        }

        /** Adds content made for the copy itself (settings, secrets); it never touches the disk. */
        fun bytes(path: String, content: ByteArray) {
            items.add(Item(path, part(path), content.size.toLong(), hex(sha256().digest(content)), System.currentTimeMillis(), false, content, -1, null))
            bytes += content.size
        }

        private fun add(name: String, id: String, path: Path) {
            val buffer = ByteArray(BUFFER)
            for (attempt in 1..ATTEMPTS) {
                val before = attributes(path) ?: return
                if (!before.isRegularFile) {
                    skipped.add(name)
                    return
                }
                val small = before.size() <= smallLimit
                val digest = sha256()
                var size = 0L
                if (small) blob.seek(blobEnd)
                try {
                    Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { input ->
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (cancelled()) throw BackupException.Cancelled()
                            digest.update(buffer, 0, count)
                            if (small) blob.write(buffer, 0, count)
                            size += count
                        }
                    }
                } catch (_: NoSuchFileException) {
                    return
                } catch (_: FileNotFoundException) {
                    return
                }
                val after = attributes(path)
                val still = after != null && after.isRegularFile && size == before.size() && size == after.size() &&
                    before.lastModifiedTime() == after.lastModifiedTime() && before.fileKey() == after.fileKey()
                // A small file that will not hold still (a log being appended) goes in as it was
                // read: the staged bytes and their hash agree, which is what a copy needs.
                if (still || (small && attempt == ATTEMPTS)) {
                    val file = path.toFile()
                    items.add(Item(name, id, size, hex(digest.digest()), before.lastModifiedTime().toMillis(), file.canExecute(),
                        null, if (small) blobEnd else -1, if (small) null else file))
                    if (small) blobEnd += size
                    bytes += size
                    seen(items.size, bytes)
                    return
                }
                if (attempt == ATTEMPTS) throw BackupException.Changed(name)
                Thread.sleep(40L * attempt)
            }
        }

        private fun attributes(path: Path): BasicFileAttributes? = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            null
        }

        /**
         * The manifest: [about] (when, by which app, on what device) plus what was collected.
         * Call when everything is added; the lists are put in order here.
         */
        fun manifest(about: JSONObject): JSONObject {
            items.sortBy { it.path }
            dirs.sort()
            skipped.sort()
            val out = JSONObject(about.toString()).put("format", FORMAT).put("kind", "axiko-backup")
            val parts = JSONArray()
            for (id in TREES.values + listOf("settings", "secrets")) {
                val root = TREES.entries.firstOrNull { it.value == id }?.key
                val files = items.filter { it.part == id }
                if (files.isEmpty() && (root == null || root !in dirs)) continue
                parts.put(JSONObject().put("id", id).put("root", root ?: files.first().path)
                    .put("files", files.size).put("bytes", files.sumOf { it.size })
                    .put("sha256", partDigest(files.map { Triple(it.path, it.size, it.sha256) })))
            }
            out.put("parts", parts).put("dirs", JSONArray(dirs))
            out.put("files", JSONArray().apply {
                items.forEach { item ->
                    put(JSONObject().put("path", item.path).put("size", item.size).put("sha256", item.sha256)
                        .put("mtime", item.modified).apply { if (item.executable) put("x", true) })
                }
            })
            if (skipped.isNotEmpty()) out.put("skipped", JSONArray(skipped))
            return out
        }

        /**
         * Writes the archive to [out] (the encrypted stream) and leaves [out] open: the caller
         * finishes it. [progress] gets the bytes of content written so far.
         */
        fun write(manifest: JSONObject, out: OutputStream, progress: (Long) -> Unit = {}) {
            val zip = ZipOutputStream(out)
            zip.setLevel(6)
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            dirs.forEach {
                zip.putNextEntry(ZipEntry("$it/"))
                zip.closeEntry()
            }
            val buffer = ByteArray(BUFFER)
            var done = 0L
            for (item in items) {
                if (cancelled()) throw BackupException.Cancelled()
                zip.setLevel(if (item.path.substringAfterLast('.', "").lowercase() in PACKED) 0 else 1)
                zip.putNextEntry(ZipEntry(item.path).apply { if (item.modified > 315_619_200_000L) time = item.modified })
                val digest = sha256()
                var size = 0L
                fun take(count: Int) {
                    digest.update(buffer, 0, count)
                    zip.write(buffer, 0, count)
                    size += count
                    done += count
                    progress(done)
                }
                when {
                    item.bytes != null -> {
                        zip.write(item.bytes)
                        digest.update(item.bytes)
                        size = item.bytes.size.toLong()
                        done += size
                    }
                    item.live != null -> try {
                        Files.newInputStream(item.live.toPath(), READ, NOFOLLOW_LINKS).use { input ->
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (cancelled()) throw BackupException.Cancelled()
                                take(count)
                            }
                        }
                    } catch (_: NoSuchFileException) {
                        throw BackupException.Changed(item.path)
                    } catch (_: FileNotFoundException) {
                        throw BackupException.Changed(item.path)
                    }
                    else -> {
                        blob.seek(item.staged)
                        var left = item.size
                        while (left > 0) {
                            val count = blob.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                            if (count < 0) break
                            take(count)
                            left -= count
                        }
                    }
                }
                zip.closeEntry()
                // What the manifest promised must be what went in: a large file may have changed since.
                if (size != item.size || hex(digest.digest()) != item.sha256) throw BackupException.Changed(item.path)
            }
            zip.finish()
        }

        override fun close() {
            blob.close()
            staging.delete()
        }

        private companion object {
            const val ATTEMPTS = 4
        }
    }

    // ---- reading ---------------------------------------------------------------------------

    /**
     * Reads a decrypted archive to its end and checks all of it against its manifest; with
     * [target] the files are also unpacked there (a fresh, empty folder). Returns the manifest.
     * [inspect] gets the manifest before the first file is written — the place to refuse a copy
     * there is no room for. [progress] gets the bytes of content read so far.
     */
    fun extract(
        input: InputStream,
        target: File?,
        inspect: (JSONObject) -> Unit = {},
        progress: (Long) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): JSONObject {
        try {
            val zip = ZipInputStream(input)
            val first = zip.nextEntry
            if (first == null || first.name != MANIFEST) throw BackupException.Damaged("В копии нет описи содержимого.")
            val manifest = runCatching { JSONObject(String(readLimited(zip, MAX_MANIFEST), Charsets.UTF_8)) }
                .getOrElse { if (it is BackupException) throw it else throw BackupException.Damaged("Опись содержимого копии не читается.") }
            val format = manifest.optInt("format", 0)
            if (format > FORMAT) throw BackupException.TooNew(format)
            if (format < 1 || manifest.optString("kind") != "axiko-backup") throw BackupException.Damaged("Опись содержимого копии не читается.")

            class Spec(val size: Long, val sha256: String, val modified: Long, val executable: Boolean)
            val expected = LinkedHashMap<String, Spec>()
            val files = manifest.optJSONArray("files") ?: throw BackupException.Damaged("Опись содержимого копии не читается.")
            for (index in 0 until files.length()) {
                val entry = files.getJSONObject(index)
                val path = entry.getString("path")
                part(path)
                val spec = Spec(entry.getLong("size"), entry.getString("sha256"), entry.optLong("mtime"), entry.optBoolean("x"))
                if (spec.size < 0 || expected.put(path, spec) != null) throw BackupException.Damaged("Опись содержимого копии не читается.")
            }
            val dirs = LinkedHashSet<String>()
            manifest.optJSONArray("dirs")?.let { list ->
                for (index in 0 until list.length()) dirs.add(list.getString(index).also { part(it, directory = true) })
            }
            // A file where the manifest also wants a folder cannot be unpacked.
            if (dirs.any { it in expected }) throw BackupException.Damaged("Опись содержимого копии не читается.")
            val parts = manifest.optJSONArray("parts") ?: JSONArray()
            for (index in 0 until parts.length()) {
                val entry = parts.getJSONObject(index)
                val id = entry.getString("id")
                val own = expected.filter { part(it.key) == id }.map { Triple(it.key, it.value.size, it.value.sha256) }
                if (entry.optString("sha256") != partDigest(own)) throw BackupException.Damaged("Опись содержимого копии не сходится сама с собой.")
            }
            inspect(manifest)

            val root = target?.canonicalFile
            if (root != null) {
                require(root.isDirectory && root.list().isNullOrEmpty()) { "Папка для распаковки должна быть пустой." }
                dirs.forEach { inside(root, it).mkdirs() }
                File(root, MANIFEST).writeText(manifest.toString())
            }
            val seen = HashSet<String>()
            val buffer = ByteArray(BUFFER)
            var done = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                if (cancelled()) throw BackupException.Cancelled()
                val name = entry.name
                if (name.endsWith("/")) {
                    if (name.dropLast(1) !in dirs) throw BackupException.Damaged("В копии есть папка, которой нет в описи.")
                    continue
                }
                val spec = expected[name] ?: throw BackupException.Damaged("В копии есть файл, которого нет в описи.")
                if (!seen.add(name)) throw BackupException.Damaged("Файл встречается в копии дважды.")
                val file = root?.let { inside(it, name) }
                file?.parentFile?.mkdirs()
                val digest = sha256()
                var size = 0L
                (file?.let { FileOutputStream(it) } ?: Nowhere).use { out ->
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        if (cancelled()) throw BackupException.Cancelled()
                        size += count
                        // More than the manifest says is not written out: the size is known in advance.
                        if (size > spec.size) throw BackupException.Damaged("Файл в копии не совпадает с описью: $name")
                        digest.update(buffer, 0, count)
                        out.write(buffer, 0, count)
                        done += count
                        progress(done)
                    }
                }
                if (size != spec.size || hex(digest.digest()) != spec.sha256) throw BackupException.Damaged("Файл в копии не совпадает с описью: $name")
                if (file != null) {
                    if (spec.executable) file.setExecutable(true, true)
                    if (spec.modified > 0) file.setLastModified(spec.modified)
                }
            }
            if (seen.size != expected.size) throw BackupException.Damaged("В копии не хватает файлов, перечисленных в описи.")
            // The rest of the stream (the ZIP's own index) is read too: the last frame closes the file.
            while (input.read(buffer) >= 0) { /* nothing to keep */ }
            return manifest
        } catch (error: ZipException) {
            throw BackupException.Damaged()
        } catch (error: EOFException) {
            throw BackupException.Damaged()
        } catch (error: org.json.JSONException) {
            throw BackupException.Damaged("Опись содержимого копии не читается.")
        }
    }

    /** Checking without unpacking: the bytes are hashed and dropped. */
    private object Nowhere : OutputStream() {
        override fun write(value: Int) = Unit

        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
    }

    /** `root/path` for a path that [part] has accepted, and never anything outside [root]. */
    private fun inside(root: File, path: String): File {
        val file = File(root, path).canonicalFile
        if (!file.path.startsWith(root.path + File.separator)) throw BackupException.Damaged("В копии есть путь, которого там быть не должно.")
        return file
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (out.size() + count > limit) throw BackupException.Damaged("Опись содержимого копии слишком велика.")
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    /**
     * One hash for a part: SHA-256 over `path \n size \n sha256 \n` of its files, in the order
     * the manifest lists them.
     */
    fun partDigest(files: List<Triple<String, Long, String>>): String {
        val digest = sha256()
        files.forEach { (path, size, hash) -> digest.update("$path\n$size\n$hash\n".toByteArray(Charsets.UTF_8)) }
        return hex(digest.digest())
    }

    private fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
