package app.axiko.update

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import app.axiko.App
import app.axiko.MainActivity
import app.axiko.core.I18n
import java.io.File
import java.util.concurrent.Executors

/** Owner-initiated download followed by the system's confirmation for an in-place update. */
internal object UpdateInstaller {
    const val ACTION = "app.axiko.update.INSTALL_RESULT"
    private const val SESSION = "update.installSession"
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var permissionVersion: String? = null
    private var preparing = false
    private var sessionId: Int? = null
    private var confirmation: Intent? = null

    fun request(activity: MainActivity, version: String): Boolean {
        if (!MainActivity.visible || MainActivity.current !== activity || preparing || sessionId != null || permissionVersion != null) return false
        val state = AppUpdates.get(activity).state()
        if (version.isBlank() || version != state.optString("version") || !state.optBoolean("canInstall")) return false
        if (activity.packageManager.canRequestPackageInstalls()) {
            prepare(activity.applicationContext, version)
        } else {
            AlertDialog.Builder(activity)
                .setTitle(I18n.t("Разрешите Axiko устанавливать обновления"))
                .setMessage(I18n.t("Android просит разрешить установку из этого источника. Затем вернитесь в Axiko."))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(I18n.t("Открыть настройки Android")) { _, _ ->
                    val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", activity.packageName, null))
                    permissionVersion = version
                    runCatching { activity.startActivity(settings) }.onFailure {
                        permissionVersion = null
                        error("Не удалось открыть установщик Android.")
                    }
                }.show()
        }
        return true
    }

    fun resumed(activity: MainActivity) {
        permissionVersion?.let { version ->
            permissionVersion = null
            if (activity.packageManager.canRequestPackageInstalls()) prepare(activity.applicationContext, version)
        }
    }

    private fun prepare(context: Context, version: String) {
        if (preparing || sessionId != null) return
        preparing = true
        AppUpdates.get(context).prepare(version) { result ->
            result.onSuccess { apk ->
                worker.execute {
                    val installer = context.packageManager.packageInstaller
                    val previous = App.prefs.getInt(SESSION, -1)
                    if (previous >= 0) runCatching { installer.abandonSession(previous) }
                    val staged = runCatching { stage(context, installer, apk) }
                    main.post {
                        staged.onSuccess { id ->
                            sessionId = id
                            App.prefs.edit().putInt(SESSION, id).apply()
                            worker.execute {
                                runCatching { commit(context, installer, id) }.onFailure { failure ->
                                    runCatching { installer.abandonSession(id) }
                                    main.post {
                                        clear()
                                        Log.w("AxikoUpdate", "Could not commit the update session", failure)
                                        error("Не удалось открыть установщик Android.")
                                    }
                                }
                            }
                        }.onFailure {
                            clear()
                            Log.w("AxikoUpdate", "Could not stage the verified update", it)
                            error("Не удалось открыть установщик Android.")
                        }
                    }
                }
            }.onFailure {
                main.post {
                    preparing = false
                    error("Не удалось подготовить обновление Axiko. Повторите попытку в настройках.")
                }
            }
        }
    }

    private fun stage(context: Context, installer: PackageInstaller, apk: File): Int {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
            }
            return id
        } catch (failure: Exception) {
            runCatching { installer.abandonSession(id) }
            throw failure
        }
    }

    private fun commit(context: Context, installer: PackageInstaller, id: Int) {
        val result = Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val callback = PendingIntent.getBroadcast(context, id, result, flags)
        installer.openSession(id).use { it.commit(callback.intentSender) }
    }

    fun result(context: Context, intent: Intent) {
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (id < 0 || id != App.prefs.getInt(SESSION, -1)) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                confirmation = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                sessionId = id
                preparing = true
                if (confirmation != null) present()
                else {
                    runCatching { context.packageManager.packageInstaller.abandonSession(id) }
                    clear()
                    error("Не удалось открыть установщик Android.")
                }
            }
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> clear()
            else -> {
                clear()
                Log.w("AxikoUpdate", "Android refused the update, status $status")
                error("Android отклонил обновление Axiko.")
            }
        }
    }

    // Keep the system dialog waiting if Axiko was put in the background during download.
    fun present() {
        val intent = confirmation ?: return
        val activity = MainActivity.current ?: return
        if (!MainActivity.visible || !activity.hasWindowFocus()) return
        confirmation = null
        runCatching { activity.startActivity(intent) }.onFailure {
            sessionId?.let { id -> runCatching { activity.packageManager.packageInstaller.abandonSession(id) } }
            clear()
            error("Не удалось открыть установщик Android.")
        }
    }

    private fun clear() {
        sessionId = null
        preparing = false
        confirmation = null
        App.prefs.edit().remove(SESSION).apply()
    }

    private fun error(text: String) {
        val activity = MainActivity.current ?: return
        if (MainActivity.visible) Toast.makeText(activity, I18n.t(text), Toast.LENGTH_LONG).show()
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == UpdateInstaller.ACTION) UpdateInstaller.result(context, intent)
    }
}
