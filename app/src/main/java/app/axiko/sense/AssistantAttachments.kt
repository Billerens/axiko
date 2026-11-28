package app.axiko.sense

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import app.axiko.agent.Inbox
import app.axiko.core.Engine
import app.axiko.core.I18n
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

internal class AssistantCameraProvider : FileProvider()

internal class AssistantAttachmentRequest(val session: String, val kind: String) {
    val token: String = UUID.randomUUID().toString()

    fun intent(context: Context): Intent = Intent(context, AssistantAttachmentActivity::class.java)
        .putExtra(TOKEN, token)

    companion object { const val TOKEN = "axiko.assistant.attachment" }
}

/** Kept outside the working files until the owner sends a message from this panel. */
internal class AssistantAttachment private constructor(
    val id: String,
    val name: String,
    private val file: File,
    private val image: String?,
) {
    fun json() = JSONObject().put("id", id).put("name", name).put("kind", if (image != null) "image" else "file")
    fun upload(): Inbox.SavedUpload = Inbox.storeFile(name, file, image)
    fun discard() { file.delete() }

    companion object {
        private const val MAX_BYTES = 25 * 1024 * 1024L

        fun read(context: Context, uri: Uri): AssistantAttachment {
            require(uri.scheme == "content") { "Не удалось прочитать вложение." }
            val resolver = context.contentResolver
            val name = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull().orEmpty().ifBlank { "file" }.take(120)
            val mime = resolver.getType(uri).orEmpty().ifBlank { MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()).orEmpty() }
            val input = resolver.openInputStream(uri) ?: error("Не удалось прочитать вложение.")
            return input.use { stage(context, name, mime.startsWith("image/"), it) }
        }

        fun photo(context: Context, file: File): AssistantAttachment = file.inputStream().use {
            stage(context, "photo.jpg", true, it)
        }

        private fun stage(context: Context, name: String, isImage: Boolean, input: InputStream): AssistantAttachment {
            val directory = File(context.cacheDir, "assistant-attachments").apply { mkdirs() }
            val staged = File.createTempFile("attachment-", ".tmp", directory)
            try {
                var total = 0L
                staged.outputStream().use { output ->
                    val buffer = ByteArray(65_536)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_BYTES) { "Размер вложения превышает 25 МБ." }
                        output.write(buffer, 0, count)
                    }
                }
                val picture = if (isImage) Inbox.encodeImage(staged) else null
                return AssistantAttachment(UUID.randomUUID().toString(), name, staged, picture)
            } catch (error: Exception) {
                staged.delete()
                throw error
            }
        }
    }
}

/** Hosts Android's chooser above a live voice session without opening the main app task. */
internal class AssistantAttachmentActivity : Activity() {
    private var request: AssistantAttachmentRequest? = null
    private var completed = false
    private var cameraUri: Uri? = null
    private val cameraFile: File? get() = request?.takeIf { it.kind == "camera" }?.let {
        File(File(cacheDir, "assistant-camera"), "${it.token}.jpg")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pending = intent.getStringExtra(AssistantAttachmentRequest.TOKEN)?.let(OnScreenAssistant::attachmentRequest)
        if (pending == null) { finish(); return }
        request = pending
        if (savedInstanceState != null) return
        if (pending.kind == "camera" && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
        } else launchPicker(pending.kind)
    }

    @Deprecated("Android's Activity callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_PERMISSION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) launchPicker("camera")
        else finishPicker(error = "Разрешите доступ к камере, чтобы сделать фото.")
    }

    @Suppress("DEPRECATION")
    private fun launchPicker(kind: String) {
        try {
            val target = if (kind == "camera") {
                val file = requireNotNull(cameraFile)
                file.parentFile?.mkdirs()
                val uri = FileProvider.getUriForFile(this, "$packageName.assistant-files", file)
                cameraUri = uri
                Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri).apply {
                    clipData = ClipData.newRawUri("photo", uri)
                    addFlags(CAMERA_GRANTS)
                }
            } else Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(if (kind == "photo") "image/*" else "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivityForResult(target, RESULT)
        } catch (_: Exception) {
            finishPicker(error = if (kind == "camera") "Не удалось открыть камеру." else "Не удалось открыть выбор файлов.")
        }
    }

    @Deprecated("Android's Activity callback")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RESULT) return
        if (resultCode != RESULT_OK) { finishPicker(); return }
        val file = cameraFile
        val uri = data?.data
        val thumbnail = if (file != null && file.length() == 0L) data?.extras?.get("data") as? Bitmap else null
        Engine.pool.execute {
            val outcome = runCatching {
                if (file != null) {
                    if (thumbnail != null) file.outputStream().use { thumbnail.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    require(file.length() > 0L) { "Не удалось прочитать фото." }
                    AssistantAttachment.photo(this, file)
                } else AssistantAttachment.read(this, requireNotNull(uri) { "Не удалось прочитать вложение." })
            }
            runOnUiThread {
                val error = outcome.exceptionOrNull()?.message.takeIf { it == "Размер вложения превышает 25 МБ." }
                finishPicker(outcome.getOrNull(), if (outcome.isSuccess) null else error ?: "Не удалось прочитать вложение.")
            }
        }
    }

    private fun finishPicker(attachment: AssistantAttachment? = null, error: String? = null) {
        if (completed) { attachment?.discard(); return }
        completed = true
        cleanup()
        finish()
        request?.let { OnScreenAssistant.attachmentResult(it.token, attachment, error?.let { message -> I18n.t(message) }) }
    }

    private fun cleanup() {
        cameraUri?.let { revokeUriPermission(it, CAMERA_GRANTS) }
        cameraFile?.delete()
    }

    override fun onDestroy() {
        if (isFinishing && !completed) {
            completed = true
            cleanup()
            request?.let { OnScreenAssistant.attachmentResult(it.token, null, null) }
        }
        super.onDestroy()
    }

    private companion object {
        const val CAMERA_PERMISSION = 1
        const val RESULT = 2
        const val CAMERA_GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
