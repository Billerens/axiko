package app.axiko.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import app.axiko.core.Workspace
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

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

    internal class SavedUpload(val attachment: Attach, val file: File)

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
        val picture = if (mime.startsWith("image/")) encode(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) else null
        add(writeUpload(name, bytes.size.toLong(), picture) { it.writeBytes(bytes) }.attachment)
    }

    /** Shares the normal uploads location without consuming or populating the global chat inbox. */
    internal fun storeFile(name: String, file: File, picture: String?): SavedUpload =
        writeUpload(name, file.length(), picture) { target ->
            file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        }

    internal fun encodeImage(file: File): String? = encode(ImageDecoder.createSource(file))

    @Synchronized
    private fun writeUpload(name: String, size: Long, picture: String?, write: (File) -> Unit): SavedUpload {
        val uploads = File(Workspace.data, "uploads").apply { mkdirs() }
        val safe = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(80)
            .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "file"
        var target = File(uploads, safe)
        var copy = 1
        while (!target.createNewFile()) {
            target = File(uploads, safe.substringBeforeLast('.') + "-${copy++}" + safe.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" })
        }
        try {
            write(target)
        } catch (error: Exception) {
            target.delete()
            throw error
        }
        val path = "data/uploads/${target.name}"
        val attachment = if (picture != null) Attach(target.name, "Изображение от владельца, сохранено как $path.", listOf(picture))
            else Attach(target.name, "Файл от владельца сохранён как $path ($size байт). Прочитайте его инструментами, если нужно.")
        return SavedUpload(attachment, target)
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
    private fun encode(source: ImageDecoder.Source): String? = runCatching {
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val size = info.size
            val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(size.width, size.height))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            if (scale < 1f) decoder.setTargetSize((size.width * scale).toInt().coerceAtLeast(1),
                (size.height * scale).toInt().coerceAtLeast(1))
        }
        try {
            val out = ByteArrayOutputStream()
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)) Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) else null
        } finally { bitmap.recycle() }
    }.getOrNull()
}
