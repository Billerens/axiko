package app.assi.core

import android.content.pm.PackageManager
import android.os.Environment
import app.assi.App
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The command line of the phone. Plain commands run as the app. With Shizuku running, commands
 * can run with the rights of ADB instead: grant permissions, change system settings, press
 * keys, install apps — everything `adb shell` can do, with no computer attached.
 */
object Shell {
    class Result(val code: Int, val out: String)

    private const val OUTPUT_LIMIT = 200_000

    // ---- Shizuku ---------------------------------------------------------------------------

    /** The Shizuku service is running and answers. */
    val alive: Boolean get() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** The owner let this app use it. */
    val granted: Boolean
        get() = alive && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    private val installed: Boolean
        get() = runCatching { App.instance.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0) }.isSuccess

    fun status(): JSONObject = JSONObject().put("installed", installed).put("running", alive).put("granted", granted)

    /** Shows Shizuku's own "allow?" dialog and waits for the answer. */
    fun request(): Boolean {
        if (!alive) error("Shizuku не запущен. Откройте приложение Shizuku и запустите его службу.")
        if (granted) return true
        val done = CountDownLatch(1)
        val listener = Shizuku.OnRequestPermissionResultListener { _, _ -> done.countDown() }
        Shizuku.addRequestPermissionResultListener(listener)
        try {
            Shizuku.requestPermission(41)
            done.await(60, TimeUnit.SECONDS)
        } finally {
            Shizuku.removeRequestPermissionResultListener(listener)
        }
        return granted
    }

    // ---- commands --------------------------------------------------------------------------

    /**
     * Runs a shell command and returns its exit code and output (stdout and stderr together).
     * [privileged] runs it with the rights of ADB through Shizuku.
     */
    fun run(command: String, timeoutMs: Long = 60_000, privileged: Boolean = false): Result {
        val process: Process = if (privileged) {
            if (!granted) error("Нет прав ADB: Shizuku не запущен или не разрешён. Откройте «Настройки» Axiko, раздел «Чувства телефона».")
            // The API keeps this method private, but it is how a one-off command is run.
            val start = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
            )
            start.isAccessible = true
            start.invoke(null, arrayOf("sh", "-c", "$command 2>&1"), null, null) as Process
        } else {
            ProcessBuilder("sh", "-c", command).redirectErrorStream(true).directory(Workspace.data).start()
        }
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { stream ->
                    val buffer = CharArray(8192)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        if (output.length < OUTPUT_LIMIT) output.append(buffer, 0, read)
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        // The output ends when the command does. Waiting on the output, not on the process,
        // works for both kinds: a Shizuku process cannot be waited on with a time limit.
        reader.join(timeoutMs)
        if (reader.isAlive) {
            process.destroy()
            reader.join(500)
            return Result(-1, output.toString() + "\n… команда остановлена: не уложилась в ${timeoutMs / 1000} с")
        }
        return Result(process.waitFor(), output.toString())
    }

    /**
     * With the rights of ADB the app gives itself everything the owner would otherwise switch
     * on by hand, screen after screen. Returns what was done, line by line.
     */
    fun empower(): String {
        val name = App.instance.packageName
        val dangerous = App.instance.packageManager
            .getPackageInfo(name, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
            .filter { it.startsWith("android.permission.") }
        val steps = ArrayList<Pair<String, String>>()
        steps.add("Системные настройки" to "pm grant $name android.permission.WRITE_SECURE_SETTINGS")
        steps.add("Чтение журнала" to "pm grant $name android.permission.READ_LOGS")
        steps.add("Статистика приложений" to "appops set $name GET_USAGE_STATS allow")
        steps.add("Показ поверх других приложений" to "appops set $name SYSTEM_ALERT_WINDOW allow")
        steps.add("Работа в фоне без ограничений" to "cmd deviceidle whitelist +$name")
        steps.add("Чтение уведомлений" to "cmd notification allow_listener $name/$name.sense.NoticeService")
        steps.add(
            "Чтение экрана" to
                "settings put secure enabled_accessibility_services $name/$name.sense.ScreenService && settings put secure accessibility_enabled 1",
        )
        steps.add("Системный ассистент" to "cmd role add-role-holder android.app.role.ASSISTANT $name")
        val report = StringBuilder()
        for ((title, command) in steps) {
            val result = run(command, 20_000, privileged = true)
            report.append(if (result.code == 0) "сделано: " else "не вышло: ").append(title)
            if (result.code != 0) report.append(" (").append(result.out.trim().lines().lastOrNull().orEmpty().take(120)).append(')')
            report.append('\n')
        }
        var grantedCount = 0
        for (permission in dangerous) {
            if (run("pm grant $name $permission", 10_000, privileged = true).code == 0) grantedCount++
        }
        report.append("разрешений выдано: ").append(grantedCount).append('\n')
        // Access to all files comes last: the system restarts an app whose storage access
        // changes, so the report is kept for the settings screen to show after the restart.
        if (!Environment.isExternalStorageManager()) {
            report.append("сделано: Доступ ко всем файлам (приложение перезапустилось)\n")
            App.prefs.edit().putString("empower.report", report.toString()).commit()
            run("appops set $name MANAGE_EXTERNAL_STORAGE allow", 20_000, privileged = true)
        }
        return report.toString()
    }
}
