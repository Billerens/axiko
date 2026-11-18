package app.axiko.core

import app.axiko.agent.Llm
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/**
 * Model packs that come as `.tar.bz2` archives (speech models do): downloaded and unpacked in
 * one pass, so the archive itself never takes space on the phone.
 */
object Archive {
    /**
     * Unpacks the archive at [url] into [target], without the archive's own top folder.
     * [progress] gets the bytes downloaded so far and the size of the archive.
     */
    fun fetch(url: String, target: File, progress: (Long, Long) -> Unit) {
        val partial = File(target.parentFile, target.name + ".part")
        partial.deleteRecursively()
        partial.mkdirs()
        try {
            Llm.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) error("Не удалось скачать: HTTP ${response.code}")
                val total = body.contentLength()
                if (total > 0 && partial.usableSpace < total * 2) {
                    error("Не хватает места: нужно около ${total * 2 / 1048576} МБ, свободно ${partial.usableSpace / 1048576} МБ.")
                }
                var done = 0L
                var reported = 0L
                val counted = object : FilterInputStream(body.byteStream()) {
                    private fun count(read: Int) {
                        if (read <= 0) return
                        done += read
                        val now = System.currentTimeMillis()
                        if (now - reported > 400) {
                            reported = now
                            progress(done, total)
                        }
                    }

                    override fun read(): Int = super.read().also { if (it >= 0) count(1) }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also { count(it) }
                }
                unpack(counted.buffered(256 * 1024), partial)
            }
            target.deleteRecursively()
            if (!partial.renameTo(target)) error("Не удалось сохранить скачанное")
        } catch (failure: Exception) {
            partial.deleteRecursively()
            throw failure
        }
    }

    private fun unpack(input: InputStream, into: File) {
        val root = into.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(input)).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                // "pack-name/model.onnx" → "model.onnx": the top folder is the archive's own name.
                val name = entry.name.trimStart('.', '/').substringAfter('/', "")
                if (name.isEmpty()) continue
                val file = File(into, name)
                if (!file.canonicalPath.startsWith(root)) continue
                if (entry.isDirectory) {
                    file.mkdirs()
                } else if (entry.isFile) {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { tar.copyTo(it, 256 * 1024) }
                }
            }
        }
    }
}
