package app.assi.core

import android.graphics.Color
import app.assi.App
import app.assi.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Themes are files in the workspace, `themes/<id>.json`:
 * `{ "name": "Изумруд", "tokens": { "accent": "#3DDC97", … }, "css": "…" }`. The tokens are the
 * custom properties of `ui/kit/kit.css` without the dashes; `css` is anything more a theme wants.
 * The chosen theme is appended to the kit's stylesheet as it is served, so every screen that
 * links the kit wears it — the agent's own screens too. Being workspace files, themes are
 * written and edited by the agent like everything else, and are covered by snapshots.
 */
object Themes {
    private const val PREF = "ui.theme"

    private val dir: File get() = File(Workspace.root, "themes")

    val active: String get() = App.prefs.getString(PREF, "").orEmpty()

    private fun valid(id: String) = Regex("[a-z0-9][a-z0-9-]{0,40}").matches(id)

    private fun read(id: String): JSONObject? {
        if (!valid(id)) return null
        return runCatching { JSONObject(File(dir, "$id.json").readText()) }.getOrNull()
    }

    /** `[{id, name, tokens, css, active}]`, by name. */
    fun list(): JSONArray {
        val current = active
        val themes = dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json") }.mapNotNull { file ->
            val id = file.name.removeSuffix(".json")
            read(id)?.let {
                JSONObject().put("id", id).put("name", it.optString("name", id))
                    .put("tokens", it.optJSONObject("tokens") ?: JSONObject()).put("css", it.optString("css"))
                    .put("active", id == current)
            }
        }
        return JSONArray(themes.sortedBy { it.getString("name").lowercase() })
    }

    /** Puts a theme on; an empty id returns the kit's own look. */
    fun set(id: String) {
        if (id.isNotEmpty() && read(id) == null) error("Нет темы «$id»")
        App.prefs.edit().putString(PREF, id).apply()
        changed()
    }

    fun save(id: String, theme: JSONObject) {
        if (!valid(id)) error("Имя файла темы — латинские буквы, цифры и дефис")
        dir.mkdirs()
        File(dir, "$id.json").writeText(theme.toString(2))
        if (id == active) changed()
    }

    fun remove(id: String) {
        if (!valid(id)) return
        File(dir, "$id.json").delete()
        if (id == active) set("")
    }

    /** Screens reload their stylesheet, the shell recolours its own parts. */
    fun changed() {
        Pages.broadcast("theme", JSONObject.quote(active))
        MainActivity.current?.applyTheme()
    }

    private fun tokens(): JSONObject? =
        if (Workspace.safeMode) null else read(active)?.optJSONObject("tokens")

    /**
     * A layer over whatever theme is worn: a holiday's snow or garland. A module writes it without
     * touching the owner's choice of theme; an empty text takes it away.
     */
    private val decorFile: File get() = File(dir, "_decor.css")

    fun decor(css: String) {
        if (css.isBlank()) {
            decorFile.delete()
        } else {
            dir.mkdirs()
            decorFile.writeText(css)
        }
        changed()
    }

    /** What is added to the kit's stylesheet: the chosen theme, then the decor over it. */
    fun css(): String {
        if (Workspace.safeMode) return ""
        val decor = decorFile.takeIf { it.isFile }?.readText()?.let { "\n/* decor */\n" + it.replace("</", "<\\/") + "\n" }.orEmpty()
        return theme() + decor
    }

    private fun theme(): String {
        val theme = read(active) ?: return ""
        val out = StringBuilder("\n/* theme: ").append(active).append(" */\n:root {\n")
        theme.optJSONObject("tokens")?.let { tokens ->
            for (key in tokens.keys()) {
                if (!Regex("[a-zA-Z0-9-]+").matches(key)) continue
                val value = tokens.optString(key).replace(Regex("[;{}<>]"), "").trim()
                if (value.isNotEmpty()) out.append("  --").append(key).append(": ").append(value).append(";\n")
            }
        }
        out.append("}\n")
        theme.optString("css").takeIf { it.isNotBlank() }?.let { out.append(it.replace("</", "<\\/")).append('\n') }
        return out.toString()
    }

    /** A theme colour for the shell's own parts (the round button, the edges), or [fallback]. */
    fun color(token: String, fallback: Int): Int =
        runCatching { Color.parseColor(tokens()!!.getString(token).trim()) }.getOrDefault(fallback)
}
