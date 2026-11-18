package app.axiko.backup

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.zip.CRC32
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What can be wrong with a file offered as a copy; each has its own words for the owner. */
sealed class BackupException(message: String) : IOException(message) {
    class NotBackup : BackupException("Это не резервная копия Axiko.")
    class TooNew(val version: Int) : BackupException("Копия сделана более новой версией Axiko (формат $version). Обновите приложение и повторите.")
    class WrongPassword : BackupException("Пароль не подошёл.")
    class Damaged(detail: String = "Файл копии повреждён или обрезан.") : BackupException(detail)
    class Changed(val path: String) : BackupException("Файл менялся во время копирования: $path")
    class Cancelled : BackupException("Отменено.")
}

/**
 * The outer layer of a backup file (`.axb`): a fixed header and the content encrypted with the
 * owner's password in frames, so a file of any size is written and read with a megabyte of
 * memory, and a changed byte, a missing tail or frames in another order are all noticed.
 * The layout is described in docs/backup.md; scripts/backup/decrypt.py reads it without the app.
 *
 * Nothing here knows about Android: the tests run it on the JVM.
 */
object BackupFormat {
    const val VERSION = 1
    const val HEADER_BYTES = 80
    const val FRAME_BYTES = 1 shl 20
    const val TAG_BYTES = 16

    /** OWASP's floor for PBKDF2-HMAC-SHA256 (2023); a faster phone gets more, see [calibrate]. */
    const val MIN_ITERATIONS = 600_000
    private const val MAX_ITERATIONS_READ = 50_000_000
    private const val MAX_FRAME_READ = 16 shl 20
    private const val KDF_PBKDF2_SHA256 = 1
    private const val CIPHER_AES_GCM_FRAMES = 1
    private val MAGIC = "AXIKOBAK".toByteArray(Charsets.US_ASCII)
    private val LABEL_ENCRYPT = "axiko-backup/v1/encrypt".toByteArray(Charsets.US_ASCII)
    private val LABEL_CHECK = "axiko-backup/v1/check".toByteArray(Charsets.US_ASCII)

    class Keys(val encrypt: ByteArray, val check: ByteArray)

    class Header(
        val iterations: Int,
        val frameBytes: Int,
        val salt: ByteArray,
        val noncePrefix: ByteArray,
        val check: ByteArray,
        /** The header as it stands in the file: every frame is bound to it. */
        val bytes: ByteArray,
    ) {
        fun keys(password: CharArray): Keys = keys(password, salt, iterations)

        fun accepts(keys: Keys): Boolean = MessageDigest.isEqual(keys.check, check)
    }

    /** The password as bytes: composed form (NFC) in UTF-8, the same on every platform. */
    fun passwordBytes(password: CharArray): ByteArray =
        Normalizer.normalize(String(password), Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)

    /** PBKDF2-HMAC-SHA256 with a 32-byte result (one block). */
    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        require(password.isNotEmpty()) { "Пароль не может быть пустым." }
        require(iterations >= 1)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        val block = mac.doFinal()
        val out = block.clone()
        for (round in 1 until iterations) {
            mac.update(block)
            mac.doFinal(block, 0)
            for (i in out.indices) out[i] = (out[i].toInt() xor block[i].toInt()).toByte()
        }
        return out
    }

    fun keys(password: CharArray, salt: ByteArray, iterations: Int): Keys {
        val bytes = passwordBytes(password)
        val master = pbkdf2(bytes, salt, iterations)
        bytes.fill(0)
        fun derive(label: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(master, "HmacSHA256")) }.doFinal(label)
        return Keys(derive(LABEL_ENCRYPT), derive(LABEL_CHECK)).also { master.fill(0) }
    }

    /**
     * How many rounds this device does in about [targetMs]: never below [MIN_ITERATIONS], so a slow
     * phone waits longer rather than writing a weaker copy.
     */
    fun calibrate(targetMs: Long = 1000, ceiling: Int = 4_000_000): Int {
        val probe = 40_000
        val salt = ByteArray(16)
        pbkdf2(byteArrayOf(1), salt, 2_000)
        val started = System.nanoTime()
        pbkdf2(byteArrayOf(1), salt, probe)
        val spent = (System.nanoTime() - started).coerceAtLeast(1)
        val fits = probe.toDouble() * targetMs * 1_000_000 / spent
        return fits.coerceIn(MIN_ITERATIONS.toDouble(), ceiling.toDouble()).toInt() / 10_000 * 10_000
    }

    // ---- writing ---------------------------------------------------------------------------

    /**
     * Writes the header to [out] and returns the stream whose content goes out encrypted.
     * [Sealer.finish] must be called when everything is written: a stream that was only closed
     * (an error, a cancel) has no final frame and is refused by every reader.
     */
    fun seal(
        out: OutputStream,
        password: CharArray,
        iterations: Int,
        frameBytes: Int = FRAME_BYTES,
        random: SecureRandom = SecureRandom(),
    ): Sealer {
        require(frameBytes in 16..MAX_FRAME_READ)
        val salt = ByteArray(16).also(random::nextBytes)
        val prefix = ByteArray(7).also(random::nextBytes)
        val keys = keys(password, salt, iterations)
        val header = ByteBuffer.allocate(HEADER_BYTES)
            .put(MAGIC).putShort(VERSION.toShort())
            .put(KDF_PBKDF2_SHA256.toByte()).put(CIPHER_AES_GCM_FRAMES.toByte())
            .putInt(iterations).putInt(frameBytes)
            .put(salt).put(prefix).put(0).put(keys.check)
        header.putInt(crc(header.array(), HEADER_BYTES - 4))
        out.write(header.array())
        return Sealer(out, Header(iterations, frameBytes, salt, prefix, keys.check, header.array()), keys)
    }

    class Sealer internal constructor(private val out: OutputStream, val header: Header, val keys: Keys) : OutputStream() {
        private val key = SecretKeySpec(keys.encrypt, "AES")
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val plain = ByteArray(header.frameBytes)
        private val sealed = ByteArray(header.frameBytes + TAG_BYTES)
        private var filled = 0
        private var counter = 0L
        private var finished = false

        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(!finished) { "Копия уже завершена." }
            var from = offset
            var left = length
            while (left > 0) {
                // A full buffer goes out only when more follows: the last frame is marked as such.
                if (filled == plain.size) frame(last = false)
                val take = minOf(left, plain.size - filled)
                System.arraycopy(bytes, from, plain, filled, take)
                filled += take
                from += take
                left -= take
            }
        }

        private fun frame(last: Boolean) {
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce(header.noncePrefix, counter, last)))
            cipher.updateAAD(header.bytes)
            val size = cipher.doFinal(plain, 0, filled, sealed, 0)
            out.write(sealed, 0, size)
            filled = 0
            counter++
        }

        /** Writes the last frame. Without it the file is not a copy. */
        fun finish() {
            check(!finished) { "Копия уже завершена." }
            frame(last = true)
            finished = true
            plain.fill(0)
            out.flush()
        }

        override fun flush() = Unit

        override fun close() = out.close()
    }

    // ---- reading ---------------------------------------------------------------------------

    /** Reads and checks the header; the stream is left at the first frame. */
    fun readHeader(input: InputStream): Header {
        val bytes = ByteArray(HEADER_BYTES)
        val got = readFully(input, bytes, 0, bytes.size)
        if (got < MAGIC.size + 2 || !bytes.copyOf(MAGIC.size).contentEquals(MAGIC)) throw BackupException.NotBackup()
        val buffer = ByteBuffer.wrap(bytes)
        buffer.position(MAGIC.size)
        val version = buffer.short.toInt() and 0xffff
        // A later format may lay the rest of the header out differently: say so before anything else.
        if (version > VERSION) throw BackupException.TooNew(version)
        if (version < 1 || got < HEADER_BYTES) throw BackupException.Damaged()
        if (ByteBuffer.wrap(bytes, HEADER_BYTES - 4, 4).int != crc(bytes, HEADER_BYTES - 4)) {
            throw BackupException.Damaged("Заголовок копии повреждён.")
        }
        val kdf = buffer.get().toInt()
        val cipher = buffer.get().toInt()
        val iterations = buffer.int
        val frameBytes = buffer.int
        if (kdf != KDF_PBKDF2_SHA256 || cipher != CIPHER_AES_GCM_FRAMES ||
            iterations !in 1..MAX_ITERATIONS_READ || frameBytes !in 16..MAX_FRAME_READ
        ) throw BackupException.Damaged("Заголовок копии повреждён.")
        val salt = ByteArray(16).also { buffer.get(it) }
        val prefix = ByteArray(7).also { buffer.get(it) }
        buffer.get()
        val check = ByteArray(32).also { buffer.get(it) }
        return Header(iterations, frameBytes, salt, prefix, check, bytes)
    }

    /** The content of a copy whose header was already read: [keys] must have passed [Header.accepts]. */
    fun open(input: InputStream, header: Header, keys: Keys): InputStream {
        if (!header.accepts(keys)) throw BackupException.WrongPassword()
        return Opened(input, header, keys)
    }

    /** Header, password, content: the whole of it for a caller that has the password at hand. */
    fun open(input: InputStream, password: CharArray): InputStream {
        val header = readHeader(input)
        return open(input, header, header.keys(password))
    }

    private class Opened(private val input: InputStream, private val header: Header, keys: Keys) : InputStream() {
        private val key = SecretKeySpec(keys.encrypt, "AES")
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val full = header.frameBytes + TAG_BYTES

        /** A frame and one byte after it: whether anything follows says if the frame is the last. */
        private val sealed = ByteArray(full + 1)
        private val plain = ByteArray(header.frameBytes)
        private var carried = 0
        private var size = 0
        private var at = 0
        private var counter = 0L
        private var ended = false

        private fun next(): Boolean {
            if (ended) return false
            val have = carried + readFully(input, sealed, carried, sealed.size - carried)
            val last = have < sealed.size
            val length = if (last) have else full
            if (length < TAG_BYTES) throw BackupException.Damaged()
            try {
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce(header.noncePrefix, counter, last)))
                cipher.updateAAD(header.bytes)
                size = cipher.doFinal(sealed, 0, length, plain, 0)
            } catch (_: AEADBadTagException) {
                throw BackupException.Damaged()
            }
            at = 0
            counter++
            if (last) {
                ended = true
            } else {
                sealed[0] = sealed[full]
                carried = 1
            }
            return true
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            while (at == size) if (!next()) return -1
            val take = minOf(length, size - at)
            System.arraycopy(plain, at, bytes, offset, take)
            at += take
            return take
        }

        override fun close() = input.close()
    }

    /** prefix (7) ‖ frame number (4, big-endian) ‖ 1 for the last frame, 0 otherwise. */
    private fun nonce(prefix: ByteArray, counter: Long, last: Boolean): ByteArray {
        if (counter > 0xffffffffL) throw IOException("Копия слишком велика для этого формата.")
        return ByteBuffer.allocate(12).put(prefix).putInt(counter.toInt()).put(if (last) 1 else 0).array()
    }

    private fun crc(bytes: ByteArray, length: Int): Int = CRC32().apply { update(bytes, 0, length) }.value.toInt()

    /** Reads until [length] bytes are there or the stream ends; says how many were read. */
    internal fun readFully(input: InputStream, into: ByteArray, offset: Int, length: Int): Int {
        var done = 0
        while (done < length) {
            val count = input.read(into, offset + done, length - done)
            if (count < 0) break
            done += count
        }
        return done
    }
}
