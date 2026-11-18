package app.axiko.backup

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Log
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.axiko.App
import app.axiko.MainActivity
import app.axiko.core.I18n
import app.axiko.core.Jobs
import app.axiko.core.Notify
import app.axiko.core.Pages
import app.axiko.core.Vault
import app.axiko.core.Workspace
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The owner's backup: everything personal in one file encrypted with a password, saved through
 * the system's "Save file" dialog (phone storage, Google Drive, any cloud app that is installed)
 * and brought back through "Open file". The file itself is [BackupFormat] around [BackupArchive];
 * putting a restored copy in place is [BackupSwap], at the very start of the process.
 *
 * In a copy: the workspace, module data, the shell's state (chats, tasks, the store), drafts,
 * settings, the secrets of the vault (decrypted here, they travel inside the encrypted file),
 * and the history of snapshots when the owner asks for it. Not in a copy: downloaded models,
 * the Linux environment, caches, the WebView's own storage.
 *
 * The password is typed into a system dialog and never passes through a page, the engine or
 * the chat. For copies on a schedule it is kept in the vault, under the phone's keystore.
 */
object Backup {
    /** The job of [Jobs] that wakes the app for a copy on a schedule. */
    const val JOB = "backup.auto"

    /** Under this name the vault keeps the password of scheduled copies; it never goes into a copy. */
    const val SECRET = "backup.password"

    private const val TAG = "AxikoBackup"
    private const val REQUEST_CREATE = 9101
    private const val REQUEST_OPEN = 9102
    private const val REQUEST_TREE = 9103
    private const val SMALL_FILE = 8L * 1024 * 1024
    private const val MIN_PASSWORD = 8
    private const val SPARE = 32L * 1024 * 1024
    private const val HOUR = 3_600_000L
    private const val SCREEN = "ui/settings/index.html?tab=system"
    private val AUTO_NAME = Regex("""axiko-backup-\d{4}-\d{2}-\d{2}-auto( \(\d+\))?\.axb""")

    /** Always replaced by a restore; the history only when the copy carries one. */
    private val ALWAYS = listOf("axiko", "data", "state", "drafts")

    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "axiko-backup").apply { isDaemon = true } }

    private val app: App get() = App.instance
    private val prefs: SharedPreferences get() = App.prefs
    private val swap: BackupSwap get() = BackupSwap(app.filesDir)

    /** What is being done right now, for the settings screen: one thing at a time. */
    private class Run(val kind: String) {
        @Volatile var stage = "ask"
        @Volatile var done = 0L
        @Volatile var total = 0L
        @Volatile var cancelled = false
        private var sent = 0L

        fun json(): JSONObject = JSONObject().put("kind", kind).put("stage", stage).put("done", done).put("total", total)

        fun enter(next: String, size: Long = 0) {
            if (cancelled) throw BackupException.Cancelled()
            stage = next
            done = 0
            total = size
            sent = 0
            tell()
        }

        fun progress(bytes: Long) {
            done = bytes
            val now = System.currentTimeMillis()
            if (now - sent < 250) return
            sent = now
            tell()
        }

        fun tell() = Pages.broadcast("backup", JSONObject().put("busy", json()).toString())
    }

    /** A copy that was opened, checked and unpacked, and waits for the owner's word. */
    private class Pending(val token: String, val summary: JSONObject, val replace: List<String>)

    @Volatile private var run: Run? = null
    @Volatile private var pending: Pending? = null

    /** A restore was put in place; the folders from before it wait for the first good start. */
    @Volatile private var awaiting = false

    /** What the start of this process did ("restored", "undone", or what went wrong), said to the owner once a screen is up. */
    @Volatile private var greeting: String? = null

    @Volatile private var picking: Pick? = null

    private class Pick(val request: Int) {
        val answered = CountDownLatch(1)
        @Volatile var data: Intent? = null
        @Volatile var failure: String? = null
    }

    // ---- the start of the process ------------------------------------------------------------

    /**
     * Called first thing in [App.onCreate], before the workspace, the engine or a page has
     * opened a file: finishes a restore (or the taking back of one) that the owner confirmed
     * before the app was restarted.
     */
    fun startup(app: App) {
        val swap = BackupSwap(app.filesDir)
        try {
            var journal = swap.recover()
            if (journal != null && journal.phase == BackupSwap.SETTINGS) {
                if (journal.action == BackupSwap.RESTORE) {
                    settingsIn(app, swap, journal)
                    journal = swap.advance(BackupSwap.CHECK)
                    greeting = "restored"
                } else {
                    settingsBack(app, swap, journal)
                    journal = swap.advance(BackupSwap.CLEANUP)
                    greeting = "undone"
                }
            }
            awaiting = journal?.action == BackupSwap.RESTORE && journal.phase == BackupSwap.CHECK
        } catch (error: Throwable) {
            // The journal stays: the next start tries again, and nothing was deleted.
            Log.e(TAG, "Восстановление из копии не завершено", error)
            greeting = error.message ?: error.toString()
        }
        worker.execute {
            runCatching {
                val journal = swap.read()
                if (journal?.phase == BackupSwap.CLEANUP) swap.finish()
                // The unpacked copy has done its work once the folders are in place.
                if (journal?.phase == BackupSwap.CHECK) swap.stage(journal.token).takeIf { it.exists() }?.let(swap::deleteTree)
                swap.leftovers().forEach(swap::deleteTree)
                arm()
            }.onFailure { Log.e(TAG, "Уборка после резервной копии", it) }
        }
    }

    /**
     * A screen came up and answered. Outside safe mode that is the good start a restore was
     * waiting for: the folders kept from before it are no longer needed.
     */
    fun started() {
        greeting?.let { kind ->
            greeting = null
            val text = when (kind) {
                "restored" -> I18n.t("Восстановлено из резервной копии")
                "undone" -> I18n.t("Возвращено состояние до восстановления из копии")
                else -> I18n.t("Восстановление из копии не завершено") + ": " + kind
            }
            MainActivity.current?.let { activity -> activity.runOnUiThread { Toast.makeText(activity, text, Toast.LENGTH_LONG).show() } }
        }
        if (!awaiting || Workspace.safeMode) return
        awaiting = false
        worker.execute {
            runCatching {
                val swap = swap
                val journal = swap.read()
                if (journal != null && journal.action == BackupSwap.RESTORE && journal.phase == BackupSwap.CHECK) {
                    swap.advance(BackupSwap.CLEANUP)
                    swap.finish()
                }
            }.onFailure { Log.e(TAG, "Уборка после восстановления", it) }
        }
    }

    /** The state from before a restore can still be brought back (the recovery menu asks). */
    val undoable: Boolean get() = awaiting

    /** Brings back what was there before the restore: the app restarts and swaps the folders again. */
    fun undo(): Boolean {
        check(run == null) { "Дождитесь окончания работы с копией." }
        swap.requestUndo()
        awaiting = false
        restart()
        return true
    }

    private fun settingsIn(app: App, swap: BackupSwap, journal: BackupSwap.Journal) {
        val stage = swap.stage(journal.token)
        val before = swap.before(journal.token)
        val settings = app.getSharedPreferences("axiko", Context.MODE_PRIVATE)
        val vault = app.getSharedPreferences("vault", Context.MODE_PRIVATE)
        // What is replaced is written down first, once: a second run after an interruption must
        // not take the half-restored settings for the old ones.
        val keptSettings = File(before, "prefs.json")
        val keptVault = File(before, "vault.json")
        if (!keptSettings.isFile) keep(keptSettings, dump(settings.all) { true })
        if (!keptVault.isFile) keep(keptVault, JSONObject().apply { vault.all.forEach { (name, value) -> if (value is String) put(name, value) } })
        val old = JSONObject(keptSettings.readText())

        File(stage, BackupArchive.SETTINGS).takeIf { it.isFile }?.let { file ->
            val edit = settings.edit().clear()
            load(edit, JSONObject(file.readText())) { !local(it) }
            // What belongs to this phone stays: widgets on its home screen, where its copies go.
            load(edit, old) { local(it) }
            edit.putInt("boot_failures", 0).commit()
        }
        File(stage, BackupArchive.SECRETS).takeIf { it.isFile }?.let { file ->
            val fresh = JSONObject(file.readText()).optJSONObject("vault") ?: JSONObject()
            val own = JSONObject(keptVault.readText()).optString(SECRET).takeIf { it.isNotEmpty() }
            vault.edit().clear().apply { own?.let { putString(SECRET, it) } }.commit()
            var lost = 0
            // Sealed anew with this phone's key: the copy carried them in the clear, inside its own encryption.
            for (name in fresh.keys()) {
                if (name == SECRET) continue
                runCatching { Vault.set(name, fresh.getString(name)) }.onFailure { lost++ }
            }
            vault.edit().commit()
            if (lost > 0) Log.e(TAG, "Не удалось сохранить секретов: $lost")
            file.delete()
        }
        // Pages keep a few things in the WebView's storage (a game's spare copy of its save, with a
        // counter that would outvote the restored one). It belongs to the state that was replaced.
        val storage = webStorage(app)
        val keptStorage = File(before, "webview-local-storage")
        if (storage.isDirectory && !keptStorage.exists()) storage.renameTo(keptStorage)
    }

    private fun settingsBack(app: App, swap: BackupSwap, journal: BackupSwap.Journal) {
        val before = swap.before(journal.token)
        File(before, "prefs.json").takeIf { it.isFile }?.let { file ->
            val edit = app.getSharedPreferences("axiko", Context.MODE_PRIVATE).edit().clear()
            load(edit, JSONObject(file.readText())) { true }
            edit.putInt("boot_failures", 0).commit()
        }
        File(before, "vault.json").takeIf { it.isFile }?.let { file ->
            val kept = JSONObject(file.readText())
            val edit = app.getSharedPreferences("vault", Context.MODE_PRIVATE).edit().clear()
            kept.keys().forEach { edit.putString(it, kept.getString(it)) }
            edit.commit()
        }
        val keptStorage = File(before, "webview-local-storage")
        if (keptStorage.isDirectory) {
            val storage = webStorage(app)
            storage.deleteRecursively()
            keptStorage.renameTo(storage)
        }
    }

    private fun webStorage(app: App) = File(app.dataDir, "app_webview/Default/Local Storage")

    private fun keep(file: File, value: JSONObject) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temporary).use {
            it.write(value.toString().toByteArray())
            it.fd.sync()
        }
        if (!temporary.renameTo(file)) error("Не удалось сохранить прежние настройки.")
    }

    // ---- settings as they travel in a copy ---------------------------------------------------

    /** Settings that describe this phone, not the owner: they are not copied and not replaced. */
    private fun local(key: String): Boolean = key == "boot_failures" || key.startsWith("widget.") || key.startsWith("backup.")

    /** `{key: {t: type, v: value}}`: SharedPreferences keeps types, and a restore must too. */
    private fun dump(all: Map<String, *>, wanted: (String) -> Boolean): JSONObject = JSONObject().apply {
        for ((key, value) in all.toSortedMap()) {
            if (!wanted(key)) continue
            val entry = when (value) {
                is String -> JSONObject().put("t", "string").put("v", value)
                is Boolean -> JSONObject().put("t", "bool").put("v", value)
                is Int -> JSONObject().put("t", "int").put("v", value)
                is Long -> JSONObject().put("t", "long").put("v", value)
                is Float -> JSONObject().put("t", "float").put("v", value.toDouble())
                is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(value.map { it.toString() }.sorted()))
                else -> continue
            }
            put(key, entry)
        }
    }

    private fun load(edit: SharedPreferences.Editor, values: JSONObject, wanted: (String) -> Boolean) {
        for (key in values.keys()) {
            if (!wanted(key)) continue
            val entry = values.optJSONObject(key) ?: continue
            when (entry.optString("t")) {
                "string" -> edit.putString(key, entry.getString("v"))
                "bool" -> edit.putBoolean(key, entry.getBoolean("v"))
                "int" -> edit.putInt(key, entry.getInt("v"))
                "long" -> edit.putLong(key, entry.getLong("v"))
                "float" -> edit.putFloat(key, entry.getDouble("v").toFloat())
                "set" -> edit.putStringSet(key, entry.getJSONArray("v").let { list -> (0 until list.length()).map { list.getString(it) }.toSet() })
            }
        }
    }

    // ---- what the settings screen asks for ---------------------------------------------------

    fun native(method: String, args: JSONArray): Any? = when (method) {
        "backup.status" -> status()
        "backup.save" -> save()
        "backup.cancel" -> {
            run?.cancelled = true
            true
        }
        "backup.open" -> open()
        "backup.apply" -> apply()
        "backup.discard" -> discard()
        "backup.undo" -> undo()
        "backup.folder" -> chooseFolder()
        "backup.password" -> choosePassword()
        "backup.set" -> set(args.getJSONObject(0))
        "backup.now" -> now()
        else -> throw IllegalArgumentException("Нет операции $method")
    }

    private val mode: String get() = prefs.getString("backup.auto", "off").takeIf { it == "daily" || it == "weekly" } ?: "off"
    private val keepCount: Int get() = (prefs.getString("backup.keep", null)?.toIntOrNull() ?: 3).coerceIn(1, 50)
    private val quietTime: String get() = prefs.getString("backup.time", null)?.takeIf { TIME.matches(it) } ?: "03:30"
    private val withHistory: Boolean get() = prefs.getString("backup.history", "off") == "on"
    private val folder: Uri? get() = prefs.getString("backup.folder", null)?.let(Uri::parse)
    private val TIME = Regex("""([01]\d|2[0-3]):[0-5]\d""")

    private fun status(): JSONObject {
        val out = JSONObject()
        run?.let { out.put("busy", it.json()) }
        prefs.getString("backup.last", null)?.let { saved -> runCatching { out.put("last", JSONObject(saved)) } }
        prefs.getString("backup.error", null)?.let { saved -> runCatching { out.put("error", JSONObject(saved)) } }
        val tree = folder
        out.put("history", withHistory)
        out.put("auto", JSONObject().put("mode", mode).put("keep", keepCount).put("time", quietTime)
            .put("folder", tree?.let { folderName(it) } ?: JSONObject.NULL).put("folderOk", tree != null && writable(tree))
            .put("password", runCatching { SECRET in Vault.names() }.getOrDefault(false))
            .put("next", Jobs.list().optJSONObject(JOB)?.optLong("next") ?: 0))
        pending?.let { out.put("pending", it.summary) }
        // Outside safe mode the kept folders live a few seconds more: offering them would only flicker.
        out.put("undo", awaiting && Workspace.safeMode)
        return out
    }

    /** ({history?, auto?, keep?, time?}) — the owner's choices; the schedule follows them. */
    private fun set(values: JSONObject): JSONObject {
        val edit = prefs.edit()
        if (values.has("history")) edit.putString("backup.history", if (values.optBoolean("history")) "on" else "off")
        if (values.has("auto")) edit.putString("backup.auto", values.optString("auto").takeIf { it == "daily" || it == "weekly" } ?: "off")
        if (values.has("keep")) edit.putString("backup.keep", values.optInt("keep", 3).coerceIn(1, 50).toString())
        if (values.has("time")) {
            val time = values.optString("time")
            if (!TIME.matches(time)) error("Время нужно в виде ЧЧ:ММ, например 03:30.")
            edit.putString("backup.time", time)
        }
        edit.commit()
        arm()
        return status()
    }

    private fun arm() {
        if (mode == "off") Jobs.remove(JOB) else Jobs.set(JOB, JSONObject().put("at", quietTime))
    }

    private fun begin(kind: String): Run = synchronized(this) {
        if (run != null) error("Работа с копией уже идёт. Дождитесь её окончания.")
        Run(kind).also { run = it }
    }

    private fun end(mine: Run, event: JSONObject) {
        synchronized(this) { if (run === mine) run = null }
        Pages.broadcast("backup", event.put("kind", mine.kind).toString())
    }

    private fun cancelled(mine: Run): JSONObject {
        end(mine, JSONObject().put("cancelled", true))
        return JSONObject().put("cancelled", true)
    }

    /** What went wrong, in words for the owner. */
    private fun words(error: Throwable): String = when (error) {
        is BackupException, is IllegalStateException, is IllegalArgumentException -> error.message ?: error.toString()
        is SecurityException -> "Нет доступа к выбранному месту. Выберите его заново."
        is IOException -> "Не удалось прочитать или записать файл: " + (error.message ?: error.javaClass.simpleName)
        else -> error.message ?: error.toString()
    }

    // ---- making a copy -----------------------------------------------------------------------

    /** "Save a copy…": the password twice, the system's "Save file" dialog, then the work in the background. */
    private fun save(): JSONObject {
        val mine = begin("save")
        var handed = false
        try {
            val password = askPassword(
                I18n.t("Пароль резервной копии"),
                I18n.t("Копия шифруется этим паролем. Без него её не открыть, и восстановить пароль нельзя: запишите его там, где не потеряете."),
                twice = true,
            ) ?: return cancelled(mine)
            val picked = pick(
                REQUEST_CREATE,
                Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_TITLE, fileName(auto = false)),
            )?.data
            if (picked == null) {
                password.fill('\u0000')
                return cancelled(mine)
            }
            val history = withHistory
            handed = true
            worker.execute {
                produce(mine, password, history, auto = false) { made ->
                    try {
                        deliver(mine, made, picked)
                    } catch (error: Throwable) {
                        // A half-written file must not lie there looking like a copy.
                        runCatching { DocumentsContract.deleteDocument(app.contentResolver, picked) }
                        throw error
                    }
                    describe(picked).first ?: fileName(auto = false)
                }
            }
            return JSONObject().put("started", true)
        } catch (error: Throwable) {
            if (!handed) end(mine, JSONObject())
            throw IllegalStateException(words(error), error)
        }
    }

    /** "Make a copy into the folder now": the scheduled copy without waiting for its hour. */
    private fun now(): JSONObject {
        val mine = begin("auto")
        worker.execute { auto(mine, notify = false) }
        return JSONObject().put("started", true)
    }

    /** The alarm of [JOB] rang (see JobReceiver): a copy is made when one is due. */
    fun due() {
        worker.execute {
            val period = when (mode) {
                "daily" -> 24 * HOUR
                "weekly" -> 7 * 24 * HOUR
                else -> return@execute
            }
            // Not more often than asked: the alarm is daily, a weekly copy lets six of them pass.
            if (System.currentTimeMillis() - prefs.getLong("backup.auto.at", 0) < period - 4 * HOUR) return@execute
            val mine = runCatching { begin("auto") }.getOrNull() ?: return@execute
            auto(mine, notify = true)
        }
    }

    private fun auto(mine: Run, notify: Boolean) {
        var password: CharArray? = null
        try {
            val tree = folder ?: error("Не выбрана папка для автокопии.")
            if (!writable(tree)) error("Папка для автокопии больше недоступна. Выберите её заново в настройках.")
            password = runCatching { Vault.get(SECRET) }.getOrNull()?.toCharArray()
                ?: error("Не задан пароль автокопии. Задайте его в настройках.")
            val name = fileName(auto = true)
            produce(mine, password, withHistory, auto = true, quiet = notify) { made ->
                val resolver = app.contentResolver
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val document = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)
                    ?: error("Не удалось создать файл в папке автокопии.")
                try {
                    deliver(mine, made, document)
                } catch (error: Throwable) {
                    runCatching { DocumentsContract.deleteDocument(resolver, document) }
                    throw error
                }
                runCatching { prune(tree, keepCount) }.onFailure { Log.e(TAG, "Удаление старых копий", it) }
                prefs.edit().putLong("backup.auto.at", System.currentTimeMillis()).apply()
                describe(document).first ?: name
            }
        } catch (error: Throwable) {
            // produce() reports its own failures; this is what stopped the copy before it began.
            if (run === mine) failed(mine, error, auto = true, quiet = notify)
        }
    }

    /**
     * Makes the encrypted file in the app's own storage, reads it back whole to be sure it can be
     * restored, and only then hands it to [place] (which says under what name it was put).
     */
    private fun produce(mine: Run, password: CharArray, history: Boolean, auto: Boolean, quiet: Boolean = false, place: (File) -> String) {
        val work = File(swap.home, "work-" + token()).apply { mkdirs() }
        val lock = app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "axiko:backup")
        try {
            lock.acquire(30 * 60_000L)
            val started = System.currentTimeMillis()
            val made = make(mine, password, history, work)
            val name = place(made.file)
            val last = JSONObject().put("at", System.currentTimeMillis()).put("name", name).put("bytes", made.file.length())
                .put("auto", auto).put("history", history).put("files", made.manifest.getJSONArray("files").length())
                .put("parts", made.manifest.getJSONArray("parts").let { parts ->
                    JSONArray().apply {
                        for (index in 0 until parts.length()) {
                            val part = parts.getJSONObject(index)
                            put(JSONObject().put("id", part.getString("id")).put("files", part.getInt("files")).put("bytes", part.getLong("bytes")))
                        }
                    }
                })
                .put("skipped", made.manifest.optJSONArray("skipped")?.length() ?: 0)
                .put("iterations", made.iterations).put("keyMs", made.keyMs).put("tookMs", System.currentTimeMillis() - started)
            prefs.edit().putString("backup.last", last.toString()).remove("backup.error").commit()
            end(mine, JSONObject().put("done", true).put("last", last))
        } catch (error: Throwable) {
            failed(mine, error, auto, quiet)
        } finally {
            password.fill('\u0000')
            runCatching { if (lock.isHeld) lock.release() }
            work.deleteRecursively()
        }
    }

    private fun failed(mine: Run, error: Throwable, auto: Boolean, quiet: Boolean) {
        if (error is BackupException.Cancelled) {
            end(mine, JSONObject().put("cancelled", true))
            return
        }
        Log.e(TAG, "Резервная копия не сделана", error)
        val text = words(error)
        if (auto) prefs.edit().putString("backup.error", JSONObject().put("at", System.currentTimeMillis()).put("text", text).toString()).apply()
        end(mine, JSONObject().put("error", text))
        // A scheduled copy that failed is the one thing the owner must hear about without looking.
        if (quiet) {
            runCatching {
                Notify.post(JSONObject().put("id", "backup").put("title", I18n.t("Резервная копия не сделана")).put("text", I18n.t(text)).put("open", SCREEN))
            }
        }
    }

    private class Made(val file: File, val manifest: JSONObject, val iterations: Int, val keyMs: Long)

    private fun make(mine: Run, password: CharArray, history: Boolean, work: File): Made {
        val target = File(work, "copy.axb")
        var changed: BackupException.Changed? = null
        repeat(3) {
            mine.enter("collect")
            try {
                BackupArchive.Collector(File(work, "stage.bin"), SMALL_FILE, { mine.cancelled }) { _, bytes -> mine.progress(bytes) }.use { collector ->
                    busyStore()
                    // Snapshots and rollbacks of the workspace wait while it is read (the same lock they take).
                    synchronized(Workspace) {
                        collector.tree("files/axiko", Workspace.root)
                        if (history) collector.tree("files/history", Workspace.history)
                    }
                    collector.tree("files/data", Workspace.data) { it == ".webcache" }
                    collector.tree("files/state", Workspace.state) { transient(it) }
                    collector.tree("files/drafts", File(app.filesDir, "drafts"))
                    collector.bytes(BackupArchive.SETTINGS, dump(prefs.all) { !local(it) }.toString().toByteArray())
                    val secrets = secrets()
                    collector.bytes(BackupArchive.SECRETS, JSONObject().put("vault", secrets).toString().toByteArray())
                    busyStore()
                    val manifest = collector.manifest(about(history, secrets.length()))
                    room(work, collector.bytes)

                    mine.enter("write", collector.bytes)
                    val iterations = BackupFormat.calibrate()
                    val keyStarted = System.currentTimeMillis()
                    var keyMs = 0L
                    val keys = FileOutputStream(target).use { raw ->
                        val out = BufferedOutputStream(raw, 1 shl 16)
                        val sealed = BackupFormat.seal(out, password, iterations)
                        keyMs = System.currentTimeMillis() - keyStarted
                        collector.write(manifest, sealed) { mine.progress(it) }
                        sealed.finish()
                        out.flush()
                        raw.fd.sync()
                        sealed.keys
                    }
                    // A copy nobody has read back is a hope, not a copy.
                    mine.enter("verify", collector.bytes)
                    target.inputStream().buffered(1 shl 16).use { input ->
                        val header = BackupFormat.readHeader(input)
                        BackupArchive.extract(BackupFormat.open(input, header, keys), null, progress = { mine.progress(it) }, cancelled = { mine.cancelled })
                    }
                    return Made(target, manifest, iterations, keyMs)
                }
            } catch (error: BackupException.Changed) {
                changed = error
                target.delete()
                Thread.sleep(1500)
            }
        }
        error("Файл всё время меняется, копию сделать не удалось: ${changed?.path}. Повторите, когда приложение ничем не занято.")
    }

    /** State that is only true for a moment: journals of unfinished operations, downloads in flight. */
    private fun transient(path: String): Boolean = path == "restore-transaction.json" || path == "store/install-transaction.json" ||
        path == "store/downloads" || path.endsWith(".tmp")

    /** A store install moves module folders about; a copy read across it would hold half of one. */
    private fun busyStore() {
        if (File(Workspace.state, "store/install-transaction.json").exists()) error("Идёт установка из магазина. Повторите через минуту.")
    }

    private fun secrets(): JSONObject = JSONObject().apply {
        for (name in Vault.names()) {
            if (name == SECRET) continue
            runCatching { Vault.get(name) }.getOrNull()?.let { put(name, it) }
        }
    }

    private fun about(history: Boolean, secrets: Int): JSONObject {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        val now = System.currentTimeMillis()
        return JSONObject().put("created", now)
            .put("createdText", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(now)))
            .put("app", JSONObject().put("id", app.packageName).put("version", info.versionName).put("versionCode", info.longVersionCode))
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("android", Build.VERSION.RELEASE)
            .put("history", history).put("secrets", secrets)
    }

    private fun room(where: File, bytes: Long) {
        val free = where.usableSpace
        if (free < bytes + SPARE) error("Не хватает места для копии: нужно около ${(bytes + SPARE) / 1048576} МБ, свободно ${free / 1048576} МБ.")
    }

    private fun deliver(mine: Run, made: File, target: Uri) {
        mine.enter("deliver", made.length())
        val out = app.contentResolver.openOutputStream(target, "w") ?: error("Не удалось открыть выбранный файл для записи.")
        out.use {
            made.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (mine.cancelled) throw BackupException.Cancelled()
                    out.write(buffer, 0, count)
                    done += count
                    mine.progress(done)
                }
            }
            out.flush()
        }
    }

    private fun fileName(auto: Boolean): String =
        "axiko-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + (if (auto) "-auto" else "") + ".axb"

    /** Removes the scheduled copies beyond the newest [keep]; files the owner saved by hand are not touched. */
    private fun prune(tree: Uri, keep: Int) {
        val resolver = app.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val found = ArrayList<Triple<String, String, Long>>()
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null, null, null,
        )?.use { rows ->
            while (rows.moveToNext()) {
                val name = rows.getString(1) ?: continue
                if (AUTO_NAME.matches(name)) found.add(Triple(rows.getString(0), name, if (rows.isNull(2)) 0 else rows.getLong(2)))
            }
        }
        found.sortedWith(compareByDescending<Triple<String, String, Long>> { it.third }.thenByDescending { it.second }).drop(keep).forEach {
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, it.first)) }
        }
    }

    // ---- the folder and the password of scheduled copies -------------------------------------

    private fun chooseFolder(): JSONObject {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val picked = pick(
            REQUEST_TREE,
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(flags or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),
        )?.data ?: return status()
        val resolver = app.contentResolver
        resolver.takePersistableUriPermission(picked, flags)
        folder?.takeIf { it != picked }?.let { old -> runCatching { resolver.releasePersistableUriPermission(old, flags) } }
        prefs.edit().putString("backup.folder", picked.toString()).remove("backup.error").commit()
        return status()
    }

    private fun writable(tree: Uri): Boolean =
        app.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission && it.isReadPermission }

    /** "Documents/Axiko" for a folder of the phone's storage, the provider's own name otherwise. */
    private fun folderName(tree: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrDefault("")
        if (tree.authority == "com.android.externalstorage.documents" && ':' in id) {
            return id.substringAfter(':').ifEmpty { I18n.t("Память телефона") }
        }
        val named = runCatching { describe(DocumentsContract.buildDocumentUriUsingTree(tree, id)).first }.getOrNull()
        return named ?: id.substringAfterLast('/').substringAfterLast(':').ifEmpty { tree.toString() }
    }

    private fun choosePassword(): JSONObject {
        val password = askPassword(
            I18n.t("Пароль автокопии"),
            I18n.t("Им шифруются копии по расписанию. Он хранится на этом телефоне в защищённом хранилище. Без него копию не открыть, и восстановить пароль нельзя: запишите его."),
            twice = true,
        ) ?: return status()
        Vault.set(SECRET, String(password))
        password.fill('\u0000')
        App.instance.getSharedPreferences("vault", Context.MODE_PRIVATE).edit().commit()
        return status()
    }

    // ---- restoring ---------------------------------------------------------------------------

    /**
     * "Restore from a copy…": the system's "Open file" dialog, the password, then the whole file
     * is decrypted, checked and unpacked beside the app's folders. Nothing is replaced yet:
     * the screen shows what is inside and asks.
     */
    private fun open(): JSONObject {
        val mine = begin("open")
        var handed = false
        try {
            discard()
            val picked = pick(REQUEST_OPEN, Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"))?.data
                ?: return cancelled(mine)
            mine.enter("read")
            val (name, size) = describe(picked)
            val input = app.contentResolver.openInputStream(picked) ?: error("Не удалось открыть выбранный файл.")
            try {
                val header = BackupFormat.readHeader(input)
                var keys: BackupFormat.Keys? = null
                var note = I18n.t("Введите пароль, которым зашифрована эта копия.")
                while (keys == null) {
                    val password = askPassword(I18n.t("Пароль резервной копии"), note, twice = false) ?: return cancelled(mine).also { input.close() }
                    val tried = header.keys(password)
                    password.fill('\u0000')
                    if (header.accepts(tried)) keys = tried else note = I18n.t("Пароль не подошёл. Попробуйте ещё раз.")
                }
                handed = true
                val ready = keys
                worker.execute { unpack(mine, input, header, ready, name, size) }
                return JSONObject().put("started", true)
            } catch (error: Throwable) {
                if (!handed) runCatching { input.close() }
                throw error
            }
        } catch (error: Throwable) {
            if (!handed) end(mine, JSONObject())
            throw IllegalStateException(words(error), error)
        }
    }

    private fun unpack(mine: Run, input: java.io.InputStream, header: BackupFormat.Header, keys: BackupFormat.Keys, name: String?, size: Long?) {
        val swap = swap
        val token = token()
        val stage = swap.stage(token)
        try {
            stage.mkdirs()
            val manifest = input.buffered(1 shl 16).use { source ->
                BackupArchive.extract(
                    BackupFormat.open(source, header, keys), stage,
                    inspect = { listed ->
                        val bytes = total(listed)
                        val free = stage.usableSpace
                        if (free < bytes + SPARE) error("Не хватает места, чтобы распаковать копию: нужно около ${(bytes + SPARE) / 1048576} МБ, свободно ${free / 1048576} МБ.")
                        mine.enter("unpack", bytes)
                    },
                    progress = { mine.progress(it) },
                    cancelled = { mine.cancelled },
                )
            }
            val parts = manifest.getJSONArray("parts")
            val ids = (0 until parts.length()).map { parts.getJSONObject(it).getString("id") }
            if ("workspace" !in ids) throw BackupException.Damaged("В копии нет рабочей папки приложения.")
            val replace = ALWAYS + if ("history" in ids) listOf("history") else emptyList()
            val made = manifest.optJSONObject("app") ?: JSONObject()
            val summary = JSONObject().put("name", name ?: JSONObject.NULL).put("fileBytes", size ?: 0)
                .put("created", manifest.optLong("created")).put("app", made).put("device", manifest.optString("device"))
                .put("sameApp", made.optString("id") == app.packageName)
                .put("files", manifest.getJSONArray("files").length()).put("bytes", total(manifest))
                .put("secrets", manifest.optInt("secrets")).put("history", "history" in ids)
                .put("parts", JSONArray().apply {
                    for (index in 0 until parts.length()) {
                        val part = parts.getJSONObject(index)
                        put(JSONObject().put("id", part.getString("id")).put("files", part.getInt("files")).put("bytes", part.getLong("bytes")))
                    }
                })
            pending = Pending(token, summary, replace)
            end(mine, JSONObject().put("pending", summary))
        } catch (error: Throwable) {
            runCatching { if (stage.exists()) swap.deleteTree(stage) }
            if (error is BackupException.Cancelled) {
                end(mine, JSONObject().put("cancelled", true))
            } else {
                Log.e(TAG, "Копия не открыта", error)
                end(mine, JSONObject().put("error", words(error)))
            }
        }
    }

    private fun total(manifest: JSONObject): Long {
        val parts = manifest.optJSONArray("parts") ?: return 0
        return (0 until parts.length()).sumOf { parts.getJSONObject(it).optLong("bytes") }
    }

    /** The owner looked at what is in the copy and said no. */
    private fun discard(): Boolean {
        val waiting = pending ?: return false
        pending = null
        val swap = swap
        runCatching { swap.stage(waiting.token).takeIf { it.exists() }?.let(swap::deleteTree) }
        return true
    }

    /**
     * The owner said yes: the unpacked copy is made durable, the journal is written and the app
     * restarts. The folders change places in [startup], before anything has them open.
     */
    private fun apply(): JSONObject {
        val waiting = pending ?: error("Сначала выберите файл копии.")
        busyStore()
        val mine = begin("apply")
        worker.execute {
            try {
                val swap = swap
                val stage = swap.stage(waiting.token)
                // What the app looks like now stays in the history, when the history itself stays.
                runCatching { Workspace.snapshot("Перед восстановлением из копии") }
                waiting.replace.forEach { File(stage, "files/$it").mkdirs() }
                mine.enter("sync")
                BackupSwap.syncTree(stage)
                // No rollback of the workspace may be half-way when the process is stopped.
                synchronized(Workspace) {
                    busyStore()
                    swap.request(waiting.token, waiting.replace, System.currentTimeMillis())
                }
                pending = null
                mine.enter("restart")
                restart()
            } catch (error: Throwable) {
                Log.e(TAG, "Восстановление не начато", error)
                end(mine, JSONObject().put("error", words(error)))
            }
        }
        return JSONObject().put("started", true)
    }

    /**
     * Stops the process and starts the app again. Everything written through SharedPreferences
     * is flushed first; the alarm is a second way back in case the system does not bring the
     * screen up by itself.
     */
    private fun restart() {
        val app = app
        runCatching { prefs.edit().commit() }
        val component = app.packageManager.getLaunchIntentForPackage(app.packageName)?.component ?: ComponentName(app, MainActivity::class.java)
        val again = Intent.makeRestartActivityTask(component)
        runCatching {
            val later = PendingIntent.getActivity(app, 9104, again, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            app.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, System.currentTimeMillis() + 1500, later)
        }
        runCatching { app.startActivity(again) }
        Runtime.getRuntime().exit(0)
    }

    // ---- system dialogs ----------------------------------------------------------------------

    /** MainActivity hands over the results of the pickers started here; true when it was one of them. */
    fun onActivityResult(request: Int, result: Int, data: Intent?): Boolean {
        if (request != REQUEST_CREATE && request != REQUEST_OPEN && request != REQUEST_TREE) return false
        val waiting = picking
        if (waiting != null && waiting.request == request) {
            if (result == Activity.RESULT_OK) waiting.data = data
            waiting.answered.countDown()
        }
        return true
    }

    /** Shows a system picker and waits for the owner; null when it was dismissed. Call off the main thread. */
    private fun pick(request: Int, intent: Intent): Intent? {
        val activity = MainActivity.current ?: error("Откройте Axiko на экране телефона и повторите.")
        val waiting = Pick(request)
        picking = waiting
        activity.runOnUiThread {
            try {
                @Suppress("DEPRECATION")
                activity.startActivityForResult(intent, request)
            } catch (error: Exception) {
                waiting.failure = error.message ?: error.toString()
                waiting.answered.countDown()
            }
        }
        waiting.answered.await(30, TimeUnit.MINUTES)
        picking = null
        waiting.failure?.let { error("На этом телефоне нет системного выбора файлов: $it") }
        return waiting.data
    }

    private fun describe(uri: Uri): Pair<String?, Long?> = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { row ->
            if (!row.moveToFirst()) return@use null
            Pair(if (row.isNull(0)) null else row.getString(0), if (row.isNull(1)) null else row.getLong(1))
        }
    }.getOrNull() ?: Pair(null, null)

    /**
     * Asks for the password in a dialog of the shell itself (as [Vault.ask] does for secrets):
     * no page, script or model sees it. With [twice] it is a new password, typed two times.
     * Call off the main thread; null when the owner cancelled.
     */
    private fun askPassword(title: String, message: String, twice: Boolean): CharArray? {
        // [title] and [message] come translated: their words stand at the call, where the dictionary's script finds them.
        val activity = MainActivity.current ?: error("Откройте Axiko на экране телефона и повторите.")
        val done = CountDownLatch(1)
        var answer: CharArray? = null
        activity.runOnUiThread {
            fun field(hint: String) = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                this.hint = hint
            }
            val first = field(I18n.t("Пароль"))
            val second = if (twice) field(I18n.t("Пароль ещё раз")) else null
            val trouble = TextView(activity).apply { setTextColor(0xFFE5484D.toInt()) }
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            val holder = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
                addView(first)
                second?.let { addView(it) }
                addView(trouble)
            }
            val dialog = AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setView(holder)
                .setPositiveButton(I18n.t("Продолжить"), null)
                .setNegativeButton(I18n.t("Отмена"), null)
                .setOnDismissListener { done.countDown() }
                .create()
            dialog.setCanceledOnTouchOutside(false)
            // Neither a screenshot nor the list of recent apps shows the dialog.
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val typed = CharArray(first.length()).also { first.text.getChars(0, it.size, it, 0) }
                val again = second?.let { field -> CharArray(field.length()).also { field.text.getChars(0, it.size, it, 0) } }
                val problem = when {
                    typed.isEmpty() -> I18n.t("Введите пароль.")
                    twice && typed.size < MIN_PASSWORD -> I18n.t("Пароль короче $MIN_PASSWORD знаков подобрать слишком легко.")
                    again != null && !typed.contentEquals(again) -> I18n.t("Пароли не совпадают.")
                    else -> null
                }
                again?.fill('\u0000')
                if (problem != null) {
                    typed.fill('\u0000')
                    trouble.text = problem
                } else {
                    answer = typed
                    first.text.clear()
                    second?.text?.clear()
                    dialog.dismiss()
                }
            }
        }
        done.await()
        return answer
    }

    private fun token(): String = UUID.randomUUID().toString().replace("-", "")
}
