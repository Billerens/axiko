package app.assi.backup

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
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes

/**
 * Puts the folders of a checked copy in the place of the app's own, and back.
 *
 * It runs at the very start of the process, before anything has opened a file in those folders,
 * and consists of renames only, written down in a journal first: a phone that loses power in
 * the middle starts again with the journal and finishes the same moves. The folders that were
 * replaced are kept beside (`backup/before-<token>`) until the app has started well once, so
 * a copy that turns out to be the wrong one can be taken back.
 *
 * Under [files] (the app's `files` folder): `backup/restore.json` is the journal,
 * `backup/restore-<token>/files/<name>` the unpacked copy, `backup/before-<token>/<name>` what
 * was there before, `backup/undone-<token>/<name>` a restored state that was taken back.
 *
 * Nothing here knows about Android: the tests run it on the JVM.
 */
class BackupSwap(private val files: File) {
    val home = File(files, "backup")
    private val journalFile = File(home, "restore.json")

    class Journal(val token: String, val action: String, val phase: String, val replace: List<String>, val at: Long) {
        fun json(): JSONObject = JSONObject().put("version", 1).put("token", token).put("action", action)
            .put("phase", phase).put("replace", JSONArray(replace)).put("at", at)
    }

    fun stage(token: String) = File(home, "restore-${checked(token)}")

    fun before(token: String) = File(home, "before-${checked(token)}")

    fun undone(token: String) = File(home, "undone-${checked(token)}")

    fun read(): Journal? {
        if (!exists(journalFile)) return null
        require(!Files.isSymbolicLink(journalFile.toPath()) && journalFile.isFile) { "Некорректный журнал восстановления из копии." }
        val value = JSONObject(journalFile.readText())
        require(value.getInt("version") == 1) { "Неизвестный формат журнала восстановления из копии." }
        val names = value.getJSONArray("replace")
        val replace = (0 until names.length()).map { names.getString(it) }
        val action = value.getString("action")
        val phase = value.getString("phase")
        require(replace.isNotEmpty() && replace.all { it in FOLDERS } && replace.toSet().size == replace.size &&
            action in setOf(RESTORE, UNDO) && phase in setOf(DIRS, SETTINGS, CHECK, CLEANUP)) {
            "Некорректный журнал восстановления из копии."
        }
        return Journal(checked(value.getString("token")), action, phase, replace, value.optLong("at"))
    }

    /** The owner agreed: the next start of the app puts the unpacked copy in place. */
    fun request(token: String, replace: List<String>, at: Long): Journal {
        require(read() == null) { "Предыдущее восстановление ещё не завершено." }
        val staged = File(stage(token), "files")
        require(replace.isNotEmpty() && replace.all { it in FOLDERS && File(staged, it).isDirectory }) { "Копия распакована не полностью." }
        return write(Journal(token, RESTORE, DIRS, replace, at))
    }

    /** The owner wants the state from before the restore: the next start puts it back. */
    fun requestUndo(): Journal {
        val journal = read()
        require(journal != null && journal.action == RESTORE && journal.phase == CHECK) { "Нечего возвращать: прежнее состояние уже удалено." }
        return write(Journal(journal.token, UNDO, DIRS, journal.replace, journal.at))
    }

    /**
     * Finishes whatever moves of folders the journal asks for and says where things stand:
     * null when nothing is going on, otherwise the journal — in [SETTINGS] the caller still has
     * to bring the settings and secrets over and call [advance].
     */
    fun recover(): Journal? {
        val journal = read() ?: return null
        if (journal.phase != DIRS) return journal
        if (journal.action == RESTORE) swapIn(journal) else swapBack(journal)
        return write(Journal(journal.token, journal.action, SETTINGS, journal.replace, journal.at))
    }

    fun advance(phase: String): Journal {
        val journal = read() ?: error("Нет журнала восстановления из копии.")
        return write(Journal(journal.token, journal.action, phase, journal.replace, journal.at))
    }

    /** Removes what the finished operation left beside and then the journal itself. */
    fun finish() {
        val journal = read() ?: return
        require(journal.phase == CLEANUP)
        listOf(stage(journal.token), before(journal.token), undone(journal.token)).forEach { if (exists(it)) deleteTree(it) }
        Files.delete(journalFile.toPath())
        syncDirectory(home)
    }

    /** Folders of work that never got as far as the journal: an unpacked copy nobody confirmed, a half-made one. */
    fun leftovers(): List<File> {
        val journal = runCatching { read() }.getOrElse { return emptyList() }
        return home.listFiles().orEmpty().filter { file ->
            (file.name.startsWith("restore-") || file.name.startsWith("work-") || file.name.startsWith("undone-")) &&
                file.isDirectory && (journal == null || !file.name.endsWith("-" + journal.token))
        }
    }

    private fun swapIn(journal: Journal) {
        val staged = File(stage(journal.token), "files")
        val before = before(journal.token)
        require(before.mkdirs() || before.isDirectory) { "Не удалось создать папку для прежнего состояния." }
        for (name in journal.replace) {
            val live = File(files, name)
            val fresh = File(staged, name)
            val kept = File(before, name)
            if (!exists(fresh)) {
                // Moved already, before the interruption.
                require(exists(live)) { "Не найдена ни прежняя, ни восстановленная папка «$name». Данные сохранены в ${home.name}." }
                continue
            }
            if (exists(live)) {
                require(!exists(kept)) { "Неоднозначное состояние восстановления: папка «$name». Данные сохранены в ${home.name}." }
                move(live, kept)
            } else if (!exists(kept)) {
                // There was no such folder: an empty one stands for it, so taking the restore back removes the new one.
                require(kept.mkdirs()) { "Не удалось создать папку для прежнего состояния." }
            }
            move(fresh, live)
        }
    }

    private fun swapBack(journal: Journal) {
        val before = before(journal.token)
        val undone = undone(journal.token)
        require(undone.mkdirs() || undone.isDirectory) { "Не удалось создать папку для отменённого состояния." }
        for (name in journal.replace) {
            val live = File(files, name)
            val kept = File(before, name)
            val gone = File(undone, name)
            if (!exists(kept)) {
                require(exists(live)) { "Не найдена ни прежняя, ни восстановленная папка «$name». Данные сохранены в ${home.name}." }
                continue
            }
            if (exists(live)) {
                require(!exists(gone)) { "Неоднозначное состояние восстановления: папка «$name». Данные сохранены в ${home.name}." }
                move(live, gone)
            }
            move(kept, live)
        }
    }

    private fun write(journal: Journal): Journal {
        require(home.mkdirs() || home.isDirectory) { "Не удалось создать папку резервных копий." }
        val temporary = File(home, ".restore.json.tmp")
        try {
            FileOutputStream(temporary).use {
                it.write(journal.json().toString().toByteArray())
                it.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), journalFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), journalFile.toPath(), REPLACE_EXISTING)
            }
            syncDirectory(home)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
        return journal
    }

    private fun move(source: File, destination: File) {
        require(!exists(destination)) { "Папка назначения уже существует: ${destination.name}" }
        try {
            Files.move(source.toPath(), destination.toPath(), ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
        syncDirectory(destination.parentFile)
        syncDirectory(source.parentFile)
    }

    /** Deletes one of this class's own folders; nothing outside `backup/` and no link is followed. */
    fun deleteTree(directory: File) {
        require(directory.parentFile == home && PREFIXES.any { directory.name.startsWith(it) } &&
            !Files.isSymbolicLink(directory.toPath())) { "Не та папка для удаления: ${directory.name}" }
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
    }

    companion object {
        const val RESTORE = "restore"
        const val UNDO = "undo"

        /** Folders are to be moved. */
        const val DIRS = "dirs"

        /** Folders are in place; settings and secrets are next (the Android side does that). */
        const val SETTINGS = "settings"

        /** Restored; what was there before waits beside until the app has started well. */
        const val CHECK = "check"

        /** Only removing what is no longer needed is left. */
        const val CLEANUP = "cleanup"

        /** The folders of `files/` a copy may replace. */
        val FOLDERS = listOf("assi", "data", "state", "drafts", "history")
        private val PREFIXES = listOf("restore-", "before-", "undone-", "work-")
        private val TOKEN = Regex("[a-f0-9]{32}")

        fun checked(token: String): String = token.also { require(TOKEN.matches(it)) { "Некорректный журнал восстановления из копии." } }

        /** Unknown filesystem state must not be mistaken for "not there". */
        private fun exists(file: File): Boolean {
            val path = file.toPath()
            if (Files.exists(path, NOFOLLOW_LINKS)) return true
            if (Files.notExists(path, NOFOLLOW_LINKS)) return false
            throw IOException("Не удалось проверить папку: ${file.name}")
        }

        fun syncDirectory(directory: File) {
            try {
                FileChannel.open(directory.toPath(), READ).use { it.force(true) }
            } catch (error: IOException) {
                // Windows (where the tests run) cannot open a folder this way. Android must persist the rename.
                if (File.separatorChar != '\\') throw error
            }
        }

        /** Makes sure every unpacked file has reached the disk before a rename depends on it. */
        fun syncTree(directory: File, cancelled: () -> Boolean = { false }) {
            Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (cancelled()) throw BackupException.Cancelled()
                    FileChannel.open(file, WRITE).use { it.force(true) }
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    syncDirectory(dir.toFile())
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }
}
