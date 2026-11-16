package app.assi.core

import android.util.Base64
import app.assi.App
import org.json.JSONObject
import java.io.File

/**
 * The icon of a module or an agent. The agent draws it (`icon.svg` in the folder); the owner
 * may put a kit icon or a picture in its place. The owner's choice lives outside the
 * workspace, so neither a rollback nor a rewrite of the module undoes it.
 */
object Icons {
    private val dir: File get() = File(Workspace.data, ".icons").apply { mkdirs() }

    private fun picture(key: String): File? = dir.listFiles().orEmpty().firstOrNull { it.nameWithoutExtension == key }

    /**
     * How to draw it: `icon` — a kit icon name, `iconSvg` — the drawn SVG, `iconImage` — the
     * address of a picture. [declared] is the name from module.json / agent.json, the fallback.
     */
    fun describe(into: JSONObject, kind: String, id: String, home: File, declared: String, fallback: String) {
        val key = "$kind-$id"
        val chosen = App.prefs.getString("icon.$key", null)
        val own = File(home, "icon.svg")
        into.put("icon", declared.ifBlank { fallback })
        into.put("iconCustom", chosen != null)
        when {
            chosen != null && chosen.startsWith("kit:") -> into.put("icon", chosen.removePrefix("kit:"))
            chosen == "image" && picture(key) != null -> {
                val file = picture(key)!!
                into.put("iconImage", "/data/.icons/${file.name}?v=${file.lastModified()}")
            }
            own.isFile && own.length() < 20_000 -> into.put("iconSvg", clean(own.readText()))
        }
    }

    /** `{icon: name}` — a kit icon; `{image: dataUrl}` — a picture; `{}` — back to the drawn one. */
    fun choose(kind: String, id: String, choice: JSONObject) {
        val key = "$kind-$id"
        val prefs = App.prefs.edit()
        picture(key)?.delete()
        when {
            choice.optString("icon").isNotBlank() -> prefs.putString("icon.$key", "kit:" + choice.getString("icon"))
            choice.optString("image").startsWith("data:") -> {
                val data = choice.getString("image")
                val mime = data.substringAfter("data:").substringBefore(';')
                val extension = when (mime) {
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    "image/svg+xml" -> "svg"
                    "image/gif" -> "gif"
                    else -> "jpg"
                }
                File(dir, "$key.$extension").writeBytes(Base64.decode(data.substringAfter("base64,"), Base64.DEFAULT))
                prefs.putString("icon.$key", "image")
            }
            else -> prefs.remove("icon.$key")
        }
        prefs.apply()
    }

    /** A drawn icon goes into the page as markup: nothing in it may run. */
    private fun clean(svg: String): String = svg
        .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\son\\w+\\s*=\\s*(\"[^\"]*\"|'[^']*')", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<\\?xml[^>]*\\?>"), "")
        .trim()
}
