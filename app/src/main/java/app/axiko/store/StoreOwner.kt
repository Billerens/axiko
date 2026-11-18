package app.axiko.store

import android.app.AlertDialog
import app.axiko.MainActivity
import app.axiko.core.I18n
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The owner's own word where it overrides the store's verdict: installing a release the review
 * warned about, lifting a ban. It is asked in a dialog of the shell, as secrets are (see Vault.ask):
 * a page, a script or a model can open the question, none of them can answer it. The wording is
 * compiled in and does not pass through the workspace's dictionary, so an edited translation
 * cannot make the question read as something else.
 */
object StoreOwner {
    private const val WAIT_S = 120L
    private val russian: Boolean get() = runCatching { I18n.language == I18n.SOURCE }.getOrDefault(true)
    private fun say(ru: String, en: String) = if (russian) ru else en

    /** `{title, version, sourceTitle, warnings: [{title, path, line}], unknowns}` → the owner's yes or no. */
    fun installDespiteWarnings(summary: JSONObject): Boolean {
        val warnings = summary.optJSONArray("warnings")
        val lines = StringBuilder()
        if (warnings != null) for (index in 0 until minOf(warnings.length(), 8)) {
            val item = warnings.getJSONObject(index)
            lines.append("• ").append(item.optString("title")).append(" — ").append(item.optString("path")).append(':').append(item.optInt("line")).append('\n')
        }
        if (warnings != null && warnings.length() > 8) lines.append(say("…и ещё ${warnings.length() - 8}\n", "…and ${warnings.length() - 8} more\n"))
        val unknowns = summary.optInt("unknowns")
        if (unknowns > 0) lines.append(say("• Не проверено пунктов: $unknowns\n", "• Left unverified: $unknowns\n"))
        val name = "«${summary.optString("title")}» ${summary.optString("version")}"
        return ask(
            say("Установить, несмотря на предупреждения?", "Install despite the warnings?"),
            say("Проверка пакета $name нашла подозрительные места:\n\n", "The review of $name found suspicious places:\n\n") + lines +
                say(
                    "\nПакет получит возможности всего приложения. Решение сохранится вместе с установленным пакетом.",
                    "\nThe package gets the abilities of the whole app. Your decision is kept with the installed package.",
                ),
            say("Установить", "Install"),
        )
    }

    /** A ban record → the owner's yes or no to lifting it. */
    fun liftBan(ban: JSONObject): Boolean {
        val name = "«${ban.optString("title").ifEmpty { ban.optString("packageId") }}» ${ban.optString("version")}"
        val validator = ban.optString("origin") == "validator"
        return ask(
            say("Снять бан?", "Lift the ban?"),
            say("Выпуск $name забанен за нарушение правил магазина:\n\n", "The release $name is banned for breaking the store rules:\n\n") +
                StoreBans.reason(ban) + "\n\n" +
                if (validator) say(
                    "Это нарушение проверяет сам установщик: после снятия бана он отклонит архив снова.",
                    "The installer checks this rule itself: it will refuse the archive again after the ban is lifted.",
                ) else say(
                    "После снятия бана этот выпуск можно будет снова подготовить и проверить.",
                    "After the ban is lifted this release can be prepared and reviewed again.",
                ),
            say("Снять бан", "Lift the ban"),
        )
    }

    /** Waits for the owner off the main thread; no answer in two minutes is a no. */
    private fun ask(title: String, message: String, action: String): Boolean {
        val absent = say(
            "Откройте Axiko на телефоне: это решение подтверждается только на его экране.",
            "Open Axiko on the phone: this decision is confirmed on its screen only.",
        )
        val activity = MainActivity.current ?: throw IllegalStateException(absent)
        val done = CountDownLatch(1)
        var agreed = false
        var dialog: AlertDialog? = null
        activity.runOnUiThread {
            dialog = AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(action) { _, _ -> agreed = true }
                .setNegativeButton(say("Отмена", "Cancel"), null)
                .setOnDismissListener { done.countDown() }
                .show()
        }
        if (!done.await(WAIT_S, TimeUnit.SECONDS)) {
            activity.runOnUiThread { runCatching { dialog?.dismiss() } }
            return false
        }
        return agreed
    }
}
