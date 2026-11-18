package app.axiko.sense

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.util.Base64
import app.axiko.App
import app.axiko.R
import app.axiko.core.Themes
import app.axiko.core.Workspace
import com.caverock.androidsvg.SVG
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * How the button over other apps looks. It is not the chat button inside the app — that one is
 * a page (ui/button/) and wears the mascot's setting. This one is a window of the system, where
 * no page runs, so its look is a picture and a few numbers, kept in a place of its own:
 *
 *     ui/bubble/bubble.json
 *     { "look": "spark",
 *       "looks": { "<id>": { "title", "image", "background", "ink", "size", "pad", "shape", "opacity" } } }
 *
 * `image` is a file beside it (SVG, PNG, WebP, JPEG); without one the shell's own spark is drawn.
 * `background` and `ink` are a colour (`#RRGGBB`), a token of the theme (`accent`, `surface`, …)
 * or `none`; `ink` repaints the picture in one colour. `size` and `pad` are in dp, `shape` is
 * `round` or `square`. The owner picks a look in the settings; the agent adds one by adding an
 * entry and its picture.
 */
object BubbleLook {
    const val DIR = "ui/bubble"
    private const val FILE = "$DIR/bubble.json"
    private const val PICTURE_PX = 144

    class Look(
        val id: String,
        val title: String,
        val image: File?,
        val background: Int?,
        val ink: Int?,
        val size: Int,
        val pad: Int,
        val round: Boolean,
        val opacity: Float,
    )

    /** What the button was before it had looks, and what it is when the file says nothing usable. */
    private fun spark() = Look("spark", "Искра", null, color("accent"), color("accent-ink"), 46, 12, true, 0.92f)

    private fun config(): JSONObject? = runCatching { JSONObject(Workspace.resolve(FILE).readText()) }.getOrNull()

    fun all(): List<Look> {
        val looks = config()?.optJSONObject("looks") ?: return listOf(spark())
        return looks.keys().asSequence().mapNotNull { id -> looks.optJSONObject(id)?.let { read(id, it) } }.toList().ifEmpty { listOf(spark()) }
    }

    /** The look that is on. */
    fun now(): Look {
        val all = all()
        val id = config()?.optString("look").orEmpty()
        return all.firstOrNull { it.id == id } ?: all.first()
    }

    /** Puts a look on: only the one field of the file is touched. */
    fun choose(id: String) {
        require(all().any { it.id == id }) { "Нет такого вида кнопки: $id" }
        val config = config() ?: JSONObject()
        config.put("look", id)
        val file = Workspace.resolve(FILE, write = true)
        file.parentFile?.mkdirs()
        file.writeText(config.toString(1))
    }

    /** For a screen that lets the owner choose: `{now, looks: [{id, title, on, image}]}`, each with the button as it will float. */
    fun list(): JSONObject {
        val now = now().id
        return JSONObject().put("now", now).put(
            "looks",
            JSONArray(all().map { look -> JSONObject().put("id", look.id).put("title", look.title).put("on", look.id == now).put("image", picture(look)) }),
        )
    }

    /** The button of [px] pixels: its background with the picture inside. A look that cannot be drawn gives the spark. */
    fun drawable(look: Look, px: Int): Drawable = runCatching { draw(look, px) }.getOrElse { draw(spark(), px) }

    // ---- inside -----------------------------------------------------------------------------

    private fun read(id: String, entry: JSONObject): Look {
        val image = entry.optString("image").takeIf { it.isNotBlank() }
            ?.let { Workspace.resolve(File(DIR, it).normalize().path.replace('\\', '/')) }?.takeIf { it.isFile }
        val size = entry.optInt("size", 46).coerceIn(32, 96)
        return Look(
            id = id,
            title = entry.optString("title").ifBlank { id },
            image = image,
            background = color(entry.optString("background", "accent")),
            ink = color(entry.optString("ink", if (image == null) "accent-ink" else "none")),
            size = size,
            pad = entry.optInt("pad", if (image == null) 12 else 0).coerceIn(0, size / 3),
            round = entry.optString("shape", "round") != "square",
            opacity = entry.optDouble("opacity", 0.92).toFloat().coerceIn(0.3f, 1f),
        )
    }

    /** `#RRGGBB`, a token of the theme, or null for "none" and for what is neither. */
    private fun color(spec: String): Int? {
        val value = spec.trim()
        if (value.isEmpty() || value == "none") return null
        if (value.startsWith("#")) return runCatching { Color.parseColor(value) }.getOrNull()
        val context = App.instance
        val own = when (value) {
            "accent" -> context.getColor(R.color.accent)
            "accent-ink" -> context.getColor(R.color.on_accent)
            "bg" -> context.getColor(R.color.bg)
            "surface" -> context.getColor(R.color.surface)
            "line" -> context.getColor(R.color.line)
            "ink" -> context.getColor(R.color.ink)
            else -> NOTHING
        }
        return Themes.color(value, own).takeIf { it != NOTHING }
    }

    private fun draw(look: Look, px: Int): Drawable {
        val context = App.instance
        val pad = px * look.pad / look.size
        val art: Drawable = look.image?.let { BitmapDrawable(context.resources, art(it, px - 2 * pad)) }
            ?: context.getDrawable(R.drawable.ic_spark)!!.mutate()
        look.ink?.let { art.setTint(it) }
        val layers = ArrayList<Drawable>()
        look.background?.let { color ->
            layers.add(
                GradientDrawable().apply {
                    shape = if (look.round) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
                    if (!look.round) cornerRadius = px * 0.28f
                    setColor(color)
                },
            )
        }
        layers.add(InsetDrawable(art, pad))
        return LayerDrawable(layers.toTypedArray())
    }

    /** The picture fitted whole into a square of [px], its proportions kept. */
    private fun art(file: File, px: Int): Bitmap {
        val square = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(square)
        if (file.extension.lowercase() == "svg") {
            val svg = SVG.getFromString(plain(file.readText()))
            if (svg.documentViewBox == null && svg.documentWidth > 0 && svg.documentHeight > 0) {
                svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
            }
            svg.setDocumentWidth(px.toFloat())
            svg.setDocumentHeight(px.toFloat())
            svg.renderToCanvas(canvas)
        } else {
            val source = BitmapFactory.decodeFile(file.path) ?: error("Не картинка: ${file.name}")
            val scale = minOf(px.toFloat() / source.width, px.toFloat() / source.height)
            val wide = source.width * scale
            val tall = source.height * scale
            canvas.drawBitmap(source, null, RectF((px - wide) / 2, (px - tall) / 2, (px + wide) / 2, (px + tall) / 2), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        return square
    }

    /**
     * An SVG made for a page takes its colours from the page's style: `var(--mascot-body, #C9B6FD)`.
     * There is no page here, so each is put in as a plain colour: the theme's when the theme
     * names one, the fallback written in the picture otherwise.
     */
    internal fun plain(svg: String): String = VAR.replace(svg) { match ->
        val fallback = match.groupValues[2].trim()
        val own = runCatching { Color.parseColor(fallback) }.getOrNull()
        val themed = Themes.color(match.groupValues[1], own ?: NOTHING)
        if (themed == NOTHING) fallback.ifEmpty { "#000000" } else String.format("#%06X", themed and 0xFFFFFF)
    }

    private fun picture(look: Look): String = runCatching {
        val bitmap = Bitmap.createBitmap(PICTURE_PX, PICTURE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.saveLayerAlpha(0f, 0f, PICTURE_PX.toFloat(), PICTURE_PX.toFloat(), (look.opacity * 255).toInt())
        drawable(look, PICTURE_PX).apply { setBounds(0, 0, PICTURE_PX, PICTURE_PX) }.draw(canvas)
        canvas.restore()
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrDefault("")

    private val VAR = Regex("""var\(\s*--([a-zA-Z0-9-]+)\s*(?:,\s*([^()]*?)\s*)?\)""")

    /** A colour no look asks for: stands for "the theme has no such token". */
    private const val NOTHING = 0x00010203
}
