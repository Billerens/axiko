package app.axiko.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import app.axiko.core.Workspace
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** Something that goes along with the owner's message: a captured screen, a picture, a file. */
class Attach(
    /** What the chat shows: "Экран: Почта", "photo.jpg". */
    val label: String,
    /** What the model is told about it. */
    val note: String,
    /** JPEG pictures in base64; sent only to a model that sees. */
    val images: List<String> = emptyList(),
)

/**
 * Attachments waiting for the next message: picked in the chat, shared from another app, or
 * captured by the assistant gesture. The next message to an agent takes them all.
 */
object Inbox {
    private const val MAX_SIDE = 1568
    private val pending = ArrayList<Attach>()

    @Synchronized
    fun add(attach: Attach) {
        pending.add(attach)
    }

    /** `{labels: [...]}` for the chat, or null when nothing waits. */
    @Synchronized
    fun peek(): JSONObject? =
        if (pending.isEmpty()) null else JSONObject().put("labels", JSONArray(pending.map { it.label }))

    @Synchronized
    fun take(): List<Attach> = pending.toList().also { pending.clear() }

    @Synchronized
    fun drop() = pending.clear()

    /**
     * Takes in a file: it is kept in `data/uploads/` where tools can read it, and a picture also
     * goes to the model as a picture.
     */
    fun addFile(name: String, bytes: ByteArray, mime: String) {
        val uploads = File(Workspace.data, "uploads").apply { mkdirs() }
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }
        var target = File(uploads, safe)
        var copy = 1
        while (target.exists()) {
            target = File(uploads, safe.substringBeforeLast('.') + "-${copy++}" + safe.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" })
        }
        target.writeBytes(bytes)
        val path = "data/uploads/${target.name}"
        val picture = if (mime.startsWith("image/")) encode(bytes) else null
        add(
            if (picture != null) Attach(target.name, "Изображение от владельца, сохранено как $path.", listOf(picture))
            else Attach(target.name, "Файл от владельца сохранён как $path (${bytes.size} байт). Прочитайте его инструментами, если нужно."),
        )
    }

    /** Takes in what another app shared. */
    fun addUri(context: Context, uri: Uri) {
        val resolver = context.contentResolver
        var name = uri.lastPathSegment ?: "file"
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.let { name = it }
            }
        }
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return
        addFile(name, bytes, resolver.getType(uri).orEmpty())
    }

    /** A picture scaled to what models take, as JPEG in base64; null when it is not a picture. */
    private fun encode(bytes: ByteArray): String? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
