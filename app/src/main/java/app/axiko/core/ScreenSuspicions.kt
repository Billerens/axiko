package app.axiko.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID

internal object ScreenSuspicions {
    internal data class FactoryObservation(
        val path: String,
        val previous: String?,
        val incoming: String,
        val preservedLocalEdit: Boolean,
    )

    internal data class MergeInput(val local: String, val factory: String, val diff: String)

    // Only these entry points can be compared or restored through this feature.
    internal val titles = linkedMapOf(
        "ui/settings/index.html" to "Настройки",
        "ui/home/index.html" to "Главная",
        "ui/chat/index.html" to "Чат",
        "ui/nav/index.html" to "Навигация",
        "ui/assistant/index.html" to "Экран ассистента",
    )

    internal fun workingFile(root: File, path: String): File {
        require(path in titles) { "Неизвестный системный экран." }
        val base = File(root.absoluteFile.parentFile!!.canonicalFile, root.name)
        val file = File(base, path)
        require(root.canonicalFile == base && file.canonicalFile == file &&
            (!Files.exists(file.toPath(), NOFOLLOW_LINKS) || Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))) {
            "Экран должен быть файлом в рабочей папке."
        }
        return file
    }

    private fun store(context: Context) = ScreenSuspicionStore(
        Workspace.root,
        File(Workspace.state, "screen-suspicions.json"),
        { path -> Workspace.factoryBytes(context, path) },
        { title -> Workspace.snapshot("Перед восстановлением экрана «$title»") },
    )

    fun observe(context: Context, observations: List<FactoryObservation>, hasLegacyManifest: Boolean) =
        synchronized(Workspace) { store(context).observe(observations, hasLegacyManifest) }

    fun state(context: Context): JSONObject =
        synchronized(Workspace) { store(context).state() }

    fun compare(context: Context, path: String): JSONObject =
        synchronized(Workspace) { store(context).compare(path) }

    fun dismiss(context: Context, path: String, revision: String): JSONObject =
        synchronized(Workspace) { store(context).dismiss(path, revision) }

    fun restore(context: Context, path: String, revision: String): JSONObject =
        synchronized(Workspace) { store(context).restore(path, revision) }

    fun mergeInput(context: Context, path: String, revision: String): MergeInput =
        synchronized(Workspace) { store(context).mergeInput(path, revision) }

    fun applyMerged(context: Context, path: String, revision: String, merged: String): JSONObject =
        synchronized(Workspace) { store(context).applyMerged(path, revision, merged) }
}

/** Stored independently of the editable workspace and its code-only history. */
internal class ScreenSuspicionStore(
    private val root: File,
    private val metadata: File,
    private val factory: (String) -> ByteArray,
    private val beforeWrite: (String) -> Unit,
) {
    private data class Records(
        val findings: MutableMap<String, String> = linkedMapOf(),
        val dismissed: MutableMap<String, String> = linkedMapOf(),
        var generation: String = "",
    )

    private data class Screen(
        val path: String,
        val title: String,
        val file: File,
        val localSha: String?,
        val factoryBytes: ByteArray,
        val factoryVersion: String,
    ) {
        val revision: String get() = "$factoryVersion:" + (localSha ?: "missing")
        val matchesFactory: Boolean get() = localSha == digest(factoryBytes, "SHA-256")

        fun json() = JSONObject().put("path", path).put("title", title).put("revision", revision)

        fun texts(): Pair<String?, String> {
            val localBytes = if (localSha == null) null else {
                require(file.length() <= DeskFiles.MAX_BYTES) { "Файл слишком большой для сравнения." }
                file.inputStream().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= DeskFiles.MAX_BYTES) { "Файл слишком большой для сравнения." }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }.also { require(digest(it, "SHA-256") == localSha) { STALE } }
            }
            return localBytes?.decodeToString() to factoryBytes.decodeToString()
        }
    }

    fun observe(observations: List<ScreenSuspicions.FactoryObservation>, hasLegacyManifest: Boolean) {
        val stored = read()
        val previous = stored ?: Records()
        val next = Records(previous.findings.toMutableMap(), previous.dismissed.toMutableMap(), previous.generation)
        val firstAdoption = stored == null && hasLegacyManifest
        var added = false
        for (observation in observations) {
            val (path, oldFactory, newFactory, differs) = observation
            require(path in ScreenSuspicions.titles)
            if (next.dismissed[path] != newFactory) next.dismissed.remove(path)
            val factoryChanged = oldFactory != null && oldFactory != newFactory
            if (!differs || next.dismissed[path] == newFactory) {
                next.findings.remove(path)
            } else if (firstAdoption || factoryChanged) {
                if (next.findings.put(path, newFactory) != newFactory) added = true
            }
        }
        if (added) next.generation = UUID.randomUUID().toString()
        if (next != stored) write(next)
    }

    fun state(): JSONObject {
        val records = read() ?: Records()
        val entries = JSONArray()
        var resolved = false
        for (path in ScreenSuspicions.titles.keys) {
            val known = records.findings[path] ?: continue
            val screen = runCatching { screen(path) }.getOrNull() ?: continue
            if (screen.matchesFactory || screen.factoryVersion != known) {
                records.findings.remove(path)
                resolved = true
            } else entries.put(screen.json())
        }
        if (resolved) write(records)
        return JSONObject().put("entries", entries).put("generation", records.generation)
    }

    fun compare(path: String): JSONObject {
        val screen = pending(path)
        val diff = if (screen.file.length() > DeskFiles.MAX_BYTES) "Файл слишком большой для сравнения."
        else screen.texts().let { (local, factory) -> Workspace.unified(local, factory) }
        return screen.json().put("diff", diff)
    }

    fun dismiss(path: String, revision: String): JSONObject {
        val records = read() ?: Records()
        val screen = pending(path, records).also { require(it.revision == revision) { STALE } }
        resolve(records, screen)
        return state()
    }

    fun restore(path: String, revision: String): JSONObject = replace(path, revision) { it.factoryBytes }

    fun mergeInput(path: String, revision: String): ScreenSuspicions.MergeInput {
        val screen = pending(path).also { require(it.revision == revision) { STALE } }
        val (local, factory) = screen.texts()
        return ScreenSuspicions.MergeInput(local.orEmpty(), factory, Workspace.unified(local, factory))
    }

    fun applyMerged(path: String, revision: String, merged: String): JSONObject {
        val bytes = merged.toByteArray(Charsets.UTF_8)
        require(merged.isNotBlank() && '\u0000' !in merged && bytes.size <= DeskFiles.MAX_BYTES) {
            "Проверьте размер и текст восстановленного экрана."
        }
        return replace(path, revision) { bytes }
    }

    private fun replace(path: String, revision: String, replacement: (Screen) -> ByteArray): JSONObject {
        val records = read() ?: Records()
        val screen = pending(path, records).also { require(it.revision == revision) { STALE } }
        beforeWrite(screen.title)
        writeAtomically(screen.file, replacement(screen)) {
            require(screen(path).revision == revision) { STALE }
        }
        resolve(records, screen)
        return state()
    }

    private fun resolve(records: Records, screen: Screen) {
        records.findings.remove(screen.path)
        records.dismissed[screen.path] = screen.factoryVersion
        write(records)
    }

    private fun pending(path: String, records: Records = read() ?: Records()): Screen {
        val screen = screen(path)
        require(records.findings[path] == screen.factoryVersion && !screen.matchesFactory) {
            "Подозрение больше не актуально."
        }
        return screen
    }

    private fun screen(path: String): Screen {
        val title = requireNotNull(ScreenSuspicions.titles[path]) { "Неизвестный системный экран." }
        val local = ScreenSuspicions.workingFile(root, path)
        val localSha = local.takeIf { it.isFile }?.let { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65_536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            hex(digest.digest())
        }
        val incoming = factory(path)
        require(incoming.size <= DeskFiles.MAX_BYTES) { "Заводской экран слишком большой для сравнения." }
        return Screen(path, title, local, localSha, incoming, digest(incoming, "SHA-1"))
    }

    private fun read(): Records? {
        if (!metadata.isFile) return null
        val json = runCatching { JSONObject(metadata.readText()) }.getOrNull() ?: return null
        fun map(key: String): MutableMap<String, String> {
            val values = json.optJSONObject(key) ?: return linkedMapOf()
            return ScreenSuspicions.titles.keys.mapNotNull { path ->
                values.optString(path).takeIf { it.isNotBlank() }?.let { path to it }
            }.toMap(linkedMapOf())
        }
        return Records(map("findings"), map("dismissed"), json.optString("generation"))
    }

    private fun write(records: Records) = writeAtomically(
        metadata,
        JSONObject().put("findings", JSONObject(records.findings))
            .put("dismissed", JSONObject(records.dismissed))
            .put("generation", records.generation).toString().toByteArray(Charsets.UTF_8),
    )

    private fun writeAtomically(target: File, bytes: ByteArray, beforeMove: () -> Unit = {}) {
        val parent = target.absoluteFile.parentFile!!
        require(parent.mkdirs() || parent.isDirectory) { "Не удалось подготовить папку экрана." }
        val temporary = File.createTempFile(".axiko-screen-", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            beforeMove()
            try {
                Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (unsupported: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    companion object {
        private const val STALE = "Экран изменился после сравнения. Сравните версии заново."

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
        private fun digest(bytes: ByteArray, algorithm: String): String =
            hex(MessageDigest.getInstance(algorithm).digest(bytes))
    }
}
