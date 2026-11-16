package app.assi.core

import app.assi.App
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * The language of the interface. The screens are written in Russian; another language is a
 * dictionary in the workspace, `ui/i18n/<code>.json`, from the Russian text to that language:
 * `{"_name": "English", "Настройки": "Settings", "Удалить тему «{}»?": "Delete the theme “{}”?"}`.
 * `{}` in a key stands for whatever is put into the text when it is shown; the translation
 * takes the same pieces in order (or by number: `{2}`).
 *
 * The dictionary rides on `ui/kit/kit.js` as it is served, and the kit translates what it draws.
 * Being a workspace file, a dictionary can be written by the agent: a new language is one file.
 */
object I18n {
    const val SOURCE = "ru"
    private const val PREF = "ui.lang"
    private const val AUTO = "auto"

    /** What the owner chose: a language code, or "auto" for the phone's language. */
    val setting: String get() = App.prefs.getString(PREF, AUTO).orEmpty().ifEmpty { AUTO }

    private fun codes(): List<String> {
        val names = if (Workspace.safeMode) App.instance.assets.list("factory/ui/i18n").orEmpty().toList()
        else File(Workspace.root, "ui/i18n").list().orEmpty().toList()
        return names.filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.filter { Regex("[a-z]{2,3}(-[A-Za-z]{2,4})?").matches(it) }.sorted()
    }

    /** The language the interface is in now. */
    val language: String
        get() {
            val known = codes()
            val chosen = setting
            if (chosen != AUTO) return if (chosen == SOURCE || chosen in known) chosen else SOURCE
            val phone = Locale.getDefault().language
            return when {
                phone == SOURCE || phone in known -> phone
                "en" in known -> "en"
                else -> SOURCE
            }
        }

    private fun read(code: String): JSONObject? = runCatching {
        val text = if (Workspace.safeMode) App.instance.assets.open("factory/ui/i18n/$code.json").use { it.readBytes().decodeToString() }
        else File(Workspace.root, "ui/i18n/$code.json").readText()
        JSONObject(text).also { if (!Workspace.safeMode) addModules(it, code) }
    }.getOrNull()

    /**
     * Modules and games bring their own words: `modules/<id>/i18n-<code>.json` and
     * `modules/games/<id>/i18n-<code>.json`, flat like the main dictionary. The main one wins
     * where both have a text, so a module cannot change how the app itself reads.
     */
    private fun addModules(into: JSONObject, code: String) {
        val modules = File(Workspace.root, "modules")
        val folders = modules.listFiles().orEmpty().filter { it.isDirectory } +
            File(modules, "games").listFiles().orEmpty().filter { it.isDirectory }
        for (folder in folders) {
            val file = File(folder, "i18n-$code.json")
            if (!file.isFile) continue
            val words = runCatching { JSONObject(file.readText()) }.getOrNull() ?: continue
            for (key in words.keys()) if (!into.has(key)) into.put(key, words.opt(key))
        }
    }

    private fun title(code: String): String =
        read(code)?.optString("_name")?.takeIf { it.isNotBlank() }
            ?: Locale.forLanguageTag(code).let { it.getDisplayLanguage(it).replaceFirstChar { first -> first.uppercase() } }

    /** `{setting, language, languages: [{code, name}]}` */
    fun status(): JSONObject = JSONObject()
        .put("setting", setting).put("language", language)
        .put(
            "languages",
            JSONArray((listOf(SOURCE) + codes().filter { it != SOURCE }).map { JSONObject().put("code", it).put("name", if (it == SOURCE) "Русский" else title(it)) }),
        )

    fun set(code: String) {
        App.prefs.edit().putString(PREF, code.ifBlank { AUTO }).apply()
        cached = null
        Pages.broadcast("lang", JSONObject.quote(language))
    }

    /** What is added to the kit's script: the language and its dictionary. */
    fun script(): String {
        val code = language
        val words = if (code == SOURCE) null else read(code)
        return "\n;window.__lang=${JSONObject.quote(code)};window.__i18n=${words?.toString() ?: "null"};\n"
    }

    // ---- the shell's own words (toasts, the recovery menu) -----------------------------------------

    private class Pattern(val regex: Regex, val into: String)

    private class Words(val code: String, val exact: JSONObject, val patterns: List<Pattern>)

    private var cached: Words? = null

    // Every character is escaped inside the class too: Android's engine is stricter than the JVM's.
    private val META = Regex("[\\.\\*\\+\\?\\^\\$\\{\\}\\(\\)\\|\\[\\]\\\\]")

    private fun words(): Words? {
        val code = language
        if (code == SOURCE) return null
        cached?.takeIf { it.code == code }?.let { return it }
        val exact = read(code) ?: return null
        val patterns = exact.keys().asSequence().filter { "{}" in it }.sortedByDescending { it.length }.map { key ->
            // Escaped by hand: Android's regex engine does not take an empty \Q\E.
            val source = key.split("{}").joinToString("(.+?)") { part -> part.replace(META) { "\\" + it.value } }
            Pattern(Regex(source, RegexOption.DOT_MATCHES_ALL), exact.optString(key))
        }.toList()
        return Words(code, exact, patterns).also { cached = it }
    }

    /** The text in the interface's language, or the text itself when there is no translation. */
    fun t(text: String): String {
        val words = words() ?: return text
        words.exact.optString(text).takeIf { it.isNotEmpty() }?.let { return it }
        for (pattern in words.patterns) {
            val match = pattern.regex.matchEntire(text) ?: continue
            var next = 0
            return Regex("\\{(\\d*)\\}").replace(pattern.into) { slot ->
                val index = slot.groupValues[1].toIntOrNull() ?: ++next
                match.groupValues.getOrElse(index) { "" }
            }
        }
        return text
    }
}
