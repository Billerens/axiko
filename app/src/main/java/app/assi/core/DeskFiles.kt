package app.assi.core

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Text editing for the web desk: a write applies only to the revision the editor read. */
class DeskFiles(
    private val root: File,
    private val locate: (String, Boolean) -> File = { path, _ -> File(root, path) },
    private val allowedRoots: () -> List<File> = { listOf(root) },
    private val beforeWrite: (String) -> Unit = {},
) {
    data class Entry(val path: String, val text: String, val revision: String?, val size: Int, val exists: Boolean) {
        fun json(): JSONObject = JSONObject().put("path", path).put("text", text)
            .put("revision", revision ?: JSONObject.NULL).put("size", size).put("exists", exists)
    }

    class Conflict(val actualRevision: String?) : IllegalStateException(
        "Файл изменился после открытия. Сравните версии или перечитайте файл перед сохранением.",
    )

    class Missing : IllegalArgumentException("Файл не найден.")

    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024

        fun path(raw: String): String {
            require(raw.isNotBlank() && raw.length <= 1024) { "Укажите путь файла в рабочей папке." }
            require(!raw.startsWith('/') && !raw.contains('\\') && !raw.contains(':') && raw.none { it.code < 32 }) {
                "Нужен относительный путь в рабочей папке."
            }
            require(raw.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Недопустимый путь файла." }
            require(raw != "data" && !raw.startsWith("data/")) {
                "Редактор изменяет код рабочей папки. Данные модулей открывайте через сам модуль."
            }
            return raw
        }

        private fun revision(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

        private fun decode(bytes: ByteArray): String {
            require(bytes.none { it == 0.toByte() }) { "Этот файл не является текстом UTF-8." }
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (error: java.nio.charset.CharacterCodingException) {
                throw IllegalArgumentException("Этот файл не является текстом UTF-8.", error)
            }
        }
    }

    private fun file(path: String, write: Boolean): File {
        val target = locate(path, write).canonicalFile
        require(allowedRoots().any { base ->
            val prefix = base.canonicalFile.path + File.separator
            target.path.startsWith(prefix)
        }) { "Файл находится вне рабочей папки." }
        require(!target.isDirectory) { "Выберите файл, а не папку." }
        return target
    }

    private fun readFile(path: String): Entry {
        val target = file(path, false)
        if (!target.exists()) return Entry(path, "", null, 0, false)
        require(target.length() <= MAX_BYTES) { "Файл больше 2 МБ: откройте его другим инструментом." }
        val bytes = target.readBytes()
        require(bytes.size <= MAX_BYTES) { "Файл больше 2 МБ: откройте его другим инструментом." }
        return Entry(path, decode(bytes), revision(bytes), bytes.size, true)
    }

    @Synchronized
    fun read(raw: String): Entry = readFile(path(raw)).also { if (!it.exists) throw Missing() }

    @Synchronized
    fun write(raw: String, text: String, expectedRevision: String?): Entry {
        val path = path(raw)
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Файл больше 2 МБ: уменьшите его перед сохранением." }
        decode(bytes)
        val previous = readFile(path)
        if (previous.revision != expectedRevision) throw Conflict(previous.revision)
        if (previous.exists && previous.text == text) return previous
        beforeWrite(path)
        // Taking a snapshot can take time; do not overwrite an agent's edit made meanwhile.
        val latest = readFile(path)
        if (latest.revision != expectedRevision) throw Conflict(latest.revision)
        val target = file(path, true)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "." + target.name + ".desk-" + UUID.randomUUID() + ".tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (unsupported: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
        return Entry(path, text, revision(bytes), bytes.size, true)
    }
}

/** A single factory bundle seeds the editable desk and remains its recovery version. */
internal object DeskAssets {
    const val PREFIX = "ui/desk/"
    private val SHARED = listOf("ui/kit/", "ui/vendor/")

    fun path(requestPath: String, factoryOnly: Boolean = false): String? = when (requestPath) {
        "/", "/ui/desk", "/ui/desk/" -> PREFIX + "index.html"
        "/__assi/client.js" -> PREFIX + "client.js"
        else -> requestPath.trimStart('/').takeIf { relative ->
            relative.startsWith(PREFIX) || (factoryOnly && SHARED.any { relative.startsWith(it) })
        }?.let { DeskFiles.path(it) }
    }

    fun read(
        relative: String,
        factoryOnly: Boolean,
        working: (String) -> File,
        factory: (String) -> ByteArray?,
    ): ByteArray? {
        require(relative.startsWith(PREFIX) || (factoryOnly && SHARED.any { relative.startsWith(it) }))
        DeskFiles.path(relative)
        if (!factoryOnly) {
            val custom = runCatching { working(relative).takeIf { it.isFile }?.readBytes() }.getOrNull()
            if (custom != null) return custom
        }
        return factory(relative)
    }
}
