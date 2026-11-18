package app.axiko

import app.axiko.backup.BackupArchive
import app.axiko.backup.BackupException
import app.axiko.backup.BackupFormat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The backup file as a format: what is written can be read back byte for byte, and everything
 * that is not exactly what was written — another password, a changed byte, a missing tail,
 * frames in another order, a path that leads out — is refused with the right words.
 */
class BackupFormatTest {
    private lateinit var base: File
    private val password = "пароль-Zür1ch".toCharArray()

    /** Enough rounds to exercise the code; the real floor is checked on its own. */
    private val rounds = 1_000

    @Before
    fun open() {
        base = Files.createTempDirectory("axiko-backup").toFile()
    }

    @After
    fun close() {
        base.deleteRecursively()
    }

    private fun write(path: String, content: ByteArray): File =
        File(base, path).apply {
            parentFile.mkdirs()
            writeBytes(content)
        }

    private fun random(seed: Long, size: Int): ByteArray = ByteArray(size).also { Random(seed).nextBytes(it) }

    /** A small app: a workspace, data with an empty folder and an empty file, state, no drafts. */
    private fun plant(): File {
        val files = File(base, "files")
        write("files/axiko/ui/home/index.html", "<h1>Дом</h1>".toByteArray())
        write("files/axiko/modules/заметки/main.js", "exports.x = 1;\n".repeat(400).toByteArray())
        write("files/axiko/lib/empty.js", ByteArray(0))
        write("files/data/notes/2026 год.db", random(1, 300_000))
        write("files/data/uploads/photo.jpg", random(2, 70_000))
        File(files, "data/games/empty").mkdirs()
        write("files/data/.webcache/abc.bin", random(3, 5_000))
        write("files/state/chats/axiko/t1.json", """{"messages":[]}""".toByteArray())
        write("files/state/store/install-transaction.json", "{}".toByteArray())
        return files
    }

    private fun about(): JSONObject = JSONObject().put("created", 1_794_830_400_000L)
        .put("app", JSONObject().put("id", "app.axiko").put("version", "0.1"))

    private fun collect(files: File, staging: File, smallLimit: Long = 8L * 1024 * 1024): BackupArchive.Collector =
        BackupArchive.Collector(staging, smallLimit).apply {
            tree("files/axiko", File(files, "axiko"))
            tree("files/data", File(files, "data")) { it == ".webcache" }
            tree("files/state", File(files, "state")) { it == "store/install-transaction.json" }
            tree("files/drafts", File(files, "drafts"))
            bytes(BackupArchive.SETTINGS, """{"wake.on":{"t":"string","v":"on"}}""".toByteArray())
            bytes(BackupArchive.SECRETS, """{"vault":{"mail.password":"s3cret"}}""".toByteArray())
        }

    private fun backup(files: File, target: File, frameBytes: Int = BackupFormat.FRAME_BYTES, iterations: Int = rounds): JSONObject {
        collect(files, File(base, "stage-${target.name}.bin")).use { collector ->
            val manifest = collector.manifest(about())
            target.outputStream().use { raw ->
                val sealed = BackupFormat.seal(raw, password, iterations, frameBytes)
                collector.write(manifest, sealed)
                sealed.finish()
            }
            return manifest
        }
    }

    private fun restore(copy: File, into: File?, with: CharArray = password): JSONObject =
        copy.inputStream().use { BackupArchive.extract(BackupFormat.open(it, with), into) }

    private fun tree(root: File): Map<String, String> = root.walkTopDown().filter { it != root }.associate {
        it.relativeTo(root).invariantSeparatorsPath to if (it.isDirectory) "dir" else sha256(it.readBytes())
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private inline fun <reified T : BackupException> refused(block: () -> Unit): T {
        try {
            block()
        } catch (error: BackupException) {
            if (error is T) return error
            throw AssertionError("ждали ${T::class.simpleName}, получили ${error::class.simpleName}: ${error.message}", error)
        }
        fail("ждали ${T::class.simpleName}, а копия принята")
        throw IllegalStateException()
    }

    // ---- the round trip --------------------------------------------------------------------

    @Test
    fun `what is backed up comes back byte for byte`() {
        val files = plant()
        val copy = File(base, "copy.axb")
        val manifest = backup(files, copy)
        val out = File(base, "out").apply { mkdirs() }
        val read = restore(copy, out)

        assertEquals(manifest.toString(), read.toString())
        val expected = tree(files).filterKeys { !it.startsWith("data/.webcache") && it != "state/store/install-transaction.json" }
        assertEquals(expected, tree(File(out, "files")))
        assertTrue("an empty folder is restored", File(out, "files/data/games/empty").isDirectory)
        assertEquals(0L, File(out, "files/axiko/lib/empty.js").length())
        assertEquals("""{"vault":{"mail.password":"s3cret"}}""", File(out, BackupArchive.SECRETS).readText())
        assertTrue(File(out, BackupArchive.SETTINGS).isFile)
        assertFalse("a part that was not there is not invented", File(out, "files/drafts").exists())

        val parts = manifest.getJSONArray("parts")
        val ids = (0 until parts.length()).map { parts.getJSONObject(it).getString("id") }
        assertEquals(listOf("workspace", "data", "state", "settings", "secrets"), ids)
        assertEquals(1, parts.getJSONObject(4).getInt("files"))
        assertEquals("the header and whole frames, nothing readable", "AXIKOBAK", String(copy.readBytes(), 0, 8, Charsets.US_ASCII))
    }

    @Test
    fun `the times and the executable bit of files are kept`() {
        val files = plant()
        val script = File(files, "axiko/tools/run.sh").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\n")
            setLastModified(1_700_000_000_000L)
        }
        val runs = script.setExecutable(true, true) && script.canExecute()
        val copy = File(base, "copy.axb")
        backup(files, copy)
        val out = File(base, "out").apply { mkdirs() }
        restore(copy, out)
        val back = File(out, "files/axiko/tools/run.sh")
        assertEquals(1_700_000_000_000L, back.lastModified())
        if (runs) assertTrue(back.canExecute())
    }

    @Test
    fun `a copy can be checked without unpacking it`() {
        val copy = File(base, "copy.axb")
        val manifest = backup(plant(), copy)
        assertEquals(manifest.getJSONArray("files").length(), restore(copy, null).getJSONArray("files").length())
    }

    @Test
    fun `an empty app makes a valid copy`() {
        val files = File(base, "files").apply { File(this, "axiko").mkdirs() }
        val copy = File(base, "copy.axb")
        BackupArchive.Collector(File(base, "stage.bin")).use { collector ->
            collector.tree("files/axiko", File(files, "axiko"))
            copy.outputStream().use { raw ->
                val sealed = BackupFormat.seal(raw, password, rounds)
                collector.write(collector.manifest(about()), sealed)
                sealed.finish()
            }
        }
        val out = File(base, "out").apply { mkdirs() }
        restore(copy, out)
        assertTrue(File(out, "files/axiko").isDirectory)
        assertEquals(emptyList<String>(), File(out, "files/axiko").list()!!.toList())
    }

    // ---- what is refused, and how ------------------------------------------------------------

    @Test
    fun `another password is told apart from a damaged file`() {
        val copy = File(base, "copy.axb")
        backup(plant(), copy)
        refused<BackupException.WrongPassword> { restore(copy, null, "не тот".toCharArray()) }
        // The same letters composed differently are the same password.
        val composed = "й".toCharArray()
        val decomposed = "й".toCharArray()
        assertFalse(composed.contentEquals(decomposed))
        assertArrayEquals(BackupFormat.passwordBytes(composed), BackupFormat.passwordBytes(decomposed))
    }

    @Test
    fun `a changed byte anywhere is noticed`() {
        val copy = File(base, "copy.axb")
        backup(plant(), copy, frameBytes = 4096)
        val original = copy.readBytes()
        val places = listOf(BackupFormat.HEADER_BYTES, original.size / 2, original.size - 1, original.size - 17)
        for (place in places) {
            val broken = original.clone().also { it[place] = (it[place].toInt() xor 0x01).toByte() }
            refused<BackupException.Damaged> { BackupArchive.extract(BackupFormat.open(ByteArrayInputStream(broken), password), null) }
        }
        // The header is bound to every frame: the salt cannot be swapped, the check value cannot be forged.
        for (place in listOf(12, 20, 36, 44, 79)) {
            val broken = original.clone().also { it[place] = (it[place].toInt() xor 0x01).toByte() }
            refused<BackupException.Damaged> { BackupArchive.extract(BackupFormat.open(ByteArrayInputStream(broken), password), null) }
        }
    }

    @Test
    fun `a file cut short is noticed wherever it is cut`() {
        val copy = File(base, "copy.axb")
        backup(plant(), copy, frameBytes = 4096)
        val original = copy.readBytes()
        val frame = 4096 + BackupFormat.TAG_BYTES
        val cuts = listOf(
            BackupFormat.HEADER_BYTES,
            BackupFormat.HEADER_BYTES + 5,
            BackupFormat.HEADER_BYTES + frame,
            BackupFormat.HEADER_BYTES + frame * 3,
            BackupFormat.HEADER_BYTES + frame * 3 + 100,
            original.size - 1,
            original.size - BackupFormat.TAG_BYTES,
        )
        assertTrue("the copy must span several frames for this test", original.size > BackupFormat.HEADER_BYTES + frame * 5)
        for (cut in cuts) {
            refused<BackupException.Damaged> { BackupArchive.extract(BackupFormat.open(ByteArrayInputStream(original.copyOf(cut)), password), null) }
        }
        refused<BackupException.Damaged> { BackupFormat.readHeader(ByteArrayInputStream(original.copyOf(40))) }
        // Something added after the end is not a copy either.
        refused<BackupException.Damaged> { BackupArchive.extract(BackupFormat.open(ByteArrayInputStream(original + ByteArray(3)), password), null) }
    }

    @Test
    fun `frames in another order are noticed`() {
        val copy = File(base, "copy.axb")
        backup(plant(), copy, frameBytes = 4096)
        val original = copy.readBytes()
        val frame = 4096 + BackupFormat.TAG_BYTES
        val first = BackupFormat.HEADER_BYTES + frame
        val second = first + frame
        val swapped = original.clone()
        System.arraycopy(original, second, swapped, first, frame)
        System.arraycopy(original, first, swapped, second, frame)
        refused<BackupException.Damaged> { BackupArchive.extract(BackupFormat.open(ByteArrayInputStream(swapped), password), null) }
        // A frame dropped from the middle, a frame repeated.
        val dropped = original.copyOfRange(0, first) + original.copyOfRange(second, original.size)
        refused<BackupException.Damaged> { BackupArchive.extract(BackupFormat.open(ByteArrayInputStream(dropped), password), null) }
        val repeated = original.copyOfRange(0, second) + original.copyOfRange(first, original.size)
        refused<BackupException.Damaged> { BackupArchive.extract(BackupFormat.open(ByteArrayInputStream(repeated), password), null) }
    }

    @Test
    fun `a writer that was only closed leaves no copy`() {
        val out = ByteArrayOutputStream()
        val sealed = BackupFormat.seal(out, password, rounds, frameBytes = 64)
        sealed.write(ByteArray(1000))
        sealed.close()
        refused<BackupException.Damaged> { BackupFormat.open(ByteArrayInputStream(out.toByteArray()), password).readBytes() }
    }

    @Test
    fun `content of every length comes back`() {
        for (size in listOf(0, 1, 63, 64, 65, 128, 129, 1000)) {
            val content = random(size.toLong(), size)
            val out = ByteArrayOutputStream()
            val sealed = BackupFormat.seal(out, password, rounds, frameBytes = 64)
            sealed.write(content)
            sealed.finish()
            val frames = if (size == 0) 1 else (size + 63) / 64
            assertEquals(BackupFormat.HEADER_BYTES + size + frames * BackupFormat.TAG_BYTES, out.size())
            assertArrayEquals(content, BackupFormat.open(ByteArrayInputStream(out.toByteArray()), password).readBytes())
        }
    }

    @Test
    fun `a file that is not a copy and a copy from the future are named as such`() {
        refused<BackupException.NotBackup> { BackupFormat.readHeader(ByteArrayInputStream("PK\u0003\u0004 just a zip".toByteArray())) }
        refused<BackupException.NotBackup> { BackupFormat.readHeader(ByteArrayInputStream(ByteArray(0))) }

        val copy = File(base, "copy.axb")
        backup(plant(), copy)
        val future = copy.readBytes().also { it[9] = 2 }
        val error = refused<BackupException.TooNew> { BackupFormat.readHeader(ByteArrayInputStream(future)) }
        assertEquals(2, error.version)

        // The outer layer is the one we know, the list inside is of a later kind.
        val inner = seal { zip ->
            zip.putNextEntry(ZipEntry(BackupArchive.MANIFEST))
            zip.write(JSONObject().put("format", 2).put("kind", "axiko-backup").put("files", JSONArray()).toString().toByteArray())
            zip.closeEntry()
        }
        refused<BackupException.TooNew> { BackupArchive.extract(BackupFormat.open(ByteArrayInputStream(inner), password), null) }
    }

    // ---- paths that lead out -----------------------------------------------------------------

    private fun seal(fill: (ZipOutputStream) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        val sealed = BackupFormat.seal(out, password, rounds)
        val zip = ZipOutputStream(sealed)
        fill(zip)
        zip.finish()
        sealed.finish()
        return out.toByteArray()
    }

    private fun forged(listed: List<String>, entries: List<Pair<String, ByteArray>>, dirs: List<String> = emptyList()): ByteArray = seal { zip ->
        val files = JSONArray()
        val specs = ArrayList<Triple<String, Long, String>>()
        listed.forEach { path ->
            val content = entries.firstOrNull { it.first == path }?.second ?: ByteArray(0)
            files.put(JSONObject().put("path", path).put("size", content.size).put("sha256", sha256(content)))
            specs.add(Triple(path, content.size.toLong(), sha256(content)))
        }
        val manifest = JSONObject().put("format", 1).put("kind", "axiko-backup").put("files", files).put("dirs", JSONArray(dirs))
        zip.putNextEntry(ZipEntry(BackupArchive.MANIFEST))
        zip.write(manifest.toString().toByteArray())
        zip.closeEntry()
        entries.forEach { (name, content) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(content)
            zip.closeEntry()
        }
    }

    @Test
    fun `a path that leads out of the expected folders is refused`() {
        val evil = listOf(
            "../evil.txt", "files/axiko/../../evil.txt", "/etc/passwd", "files/axiko//x", "files/axiko/./x", "files\\axiko\\x",
            "files/other/x", "files/axiko", "prefs/vault.json", "secrets.json/x", "files/models/big.bin", "C:/x", "files/axiko/x\u0000y", "",
        )
        for (path in evil) refused<BackupException.Damaged> { BackupArchive.part(path) }
        for (path in listOf("files/axiko/..", "files", "prefs", "files/axiko/")) refused<BackupException.Damaged> { BackupArchive.part(path, directory = true) }
        assertEquals("workspace", BackupArchive.part("files/axiko/ui/..hidden/a b.html"))
        assertEquals("data", BackupArchive.part("files/data", directory = true))

        val out = File(base, "out").apply { mkdirs() }
        val content = "x".toByteArray()
        // The manifest itself names the way out.
        refused<BackupException.Damaged> {
            BackupArchive.extract(BackupFormat.open(ByteArrayInputStream(forged(listOf("../evil.txt"), listOf("../evil.txt" to content))), password), out)
        }
        // The manifest is clean, the archive carries more than it lists.
        refused<BackupException.Damaged> {
            BackupArchive.extract(BackupFormat.open(ByteArrayInputStream(forged(listOf("files/axiko/a"), listOf("files/axiko/a" to content, "../evil.txt" to content))), password), File(base, "out2").apply { mkdirs() })
        }
        refused<BackupException.Damaged> {
            BackupArchive.extract(BackupFormat.open(ByteArrayInputStream(forged(emptyList(), emptyList(), dirs = listOf("files/axiko/../../up"))), password), File(base, "out3").apply { mkdirs() })
        }
        assertFalse(File(base, "evil.txt").exists())
        assertFalse(File(base, "up").exists())
        assertFalse(File(base.parentFile, "evil.txt").exists())
    }

    @Test
    fun `an archive that does not match its own list is refused`() {
        val a = "files/axiko/a" to "one".toByteArray()
        val b = "files/axiko/b" to "two".toByteArray()
        fun check(bytes: ByteArray) = BackupArchive.extract(BackupFormat.open(ByteArrayInputStream(bytes), password), null)
        check(forged(listOf(a.first, b.first), listOf(a, b)))
        // A file missing, a file with other content, no list at all.
        refused<BackupException.Damaged> { check(forged(listOf(a.first, b.first), listOf(a))) }
        val other = seal { zip ->
            val manifest = JSONObject().put("format", 1).put("kind", "axiko-backup").put("files", JSONArray().put(
                JSONObject().put("path", a.first).put("size", 3).put("sha256", sha256("one".toByteArray()))))
            zip.putNextEntry(ZipEntry(BackupArchive.MANIFEST))
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(a.first))
            zip.write("eno".toByteArray())
            zip.closeEntry()
        }
        refused<BackupException.Damaged> { check(other) }
        // No manifest first.
        refused<BackupException.Damaged> {
            check(seal { zip ->
                zip.putNextEntry(ZipEntry(a.first))
                zip.write(a.second)
                zip.closeEntry()
            })
        }
    }

    // ---- files that change while the copy is made ----------------------------------------------

    @Test
    fun `a small file changed after it was read goes in as it was read`() {
        val files = plant()
        val chat = File(files, "state/chats/axiko/t1.json")
        val copy = File(base, "copy.axb")
        collect(files, File(base, "stage.bin")).use { collector ->
            val manifest = collector.manifest(about())
            chat.writeText("""{"messages":["написано позже"]}""")
            copy.outputStream().use { raw ->
                val sealed = BackupFormat.seal(raw, password, rounds)
                collector.write(manifest, sealed)
                sealed.finish()
            }
        }
        val out = File(base, "out").apply { mkdirs() }
        restore(copy, out)
        assertEquals("""{"messages":[]}""", File(out, "files/state/chats/axiko/t1.json").readText())
    }

    @Test
    fun `a large file changed after it was read stops the copy`() {
        val files = plant()
        val big = File(files, "data/notes/2026 год.db")
        collect(files, File(base, "stage.bin"), smallLimit = 1024).use { collector ->
            val manifest = collector.manifest(about())
            big.writeBytes(random(9, 300_000))
            val error = refused<BackupException.Changed> {
                val sealed = BackupFormat.seal(ByteArrayOutputStream(), password, rounds)
                collector.write(manifest, sealed)
            }
            assertEquals("files/data/notes/2026 год.db", error.path)
        }
        assertFalse("the staging file is removed", File(base, "stage.bin").exists())
    }

    // ---- size ------------------------------------------------------------------------------

    @Test
    fun `a file of tens of megabytes passes through without being held in memory`() {
        val megabytes = 48
        val files = File(base, "files")
        val big = File(files, "data/recording.bin").apply { parentFile.mkdirs() }
        val source = Random(7)
        val chunk = ByteArray(1 shl 20)
        big.outputStream().use { out ->
            repeat(megabytes) {
                source.nextBytes(chunk)
                out.write(chunk)
            }
        }
        val expected = MessageDigest.getInstance("SHA-256").let { digest ->
            big.inputStream().use { input ->
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    digest.update(chunk, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

        val runtime = Runtime.getRuntime()
        fun used(): Long {
            repeat(3) { System.gc() }
            return runtime.totalMemory() - runtime.freeMemory()
        }
        val baseline = used()
        var peak = 0L
        var sampled = 0L
        fun sample(done: Long) {
            if (done - sampled < 6L * 1024 * 1024) return
            sampled = done
            peak = maxOf(peak, used() - baseline)
        }

        val copy = File(base, "copy.axb")
        // Once through the staging file (the limit is above the size), once straight from its place.
        for (limit in listOf(64L * 1024 * 1024, 1024L)) {
            sampled = 0
            BackupArchive.Collector(File(base, "stage.bin"), limit).use { collector ->
                collector.tree("files/data", File(files, "data"))
                val manifest = collector.manifest(about())
                copy.outputStream().buffered().use { raw ->
                    val sealed = BackupFormat.seal(raw, password, rounds)
                    collector.write(manifest, sealed) { sample(it) }
                    sealed.finish()
                }
            }
            assertTrue(copy.length() > megabytes * 1024L * 1024)
        }
        sampled = 0
        val out = File(base, "out").apply { mkdirs() }
        copy.inputStream().buffered().use { BackupArchive.extract(BackupFormat.open(it, password), out, progress = { done -> sample(done) }) }
        val back = File(out, "files/data/recording.bin")
        assertEquals(big.length(), back.length())
        val actual = MessageDigest.getInstance("SHA-256").let { digest ->
            back.inputStream().use { input ->
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    digest.update(chunk, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        assertEquals(expected, actual)
        assertTrue("memory grew by ${peak / 1048576} MB while a $megabytes MB file passed through", peak < 16L * 1024 * 1024)
    }

    // ---- the key ---------------------------------------------------------------------------

    @Test
    fun `the key derivation is the standard one`() {
        val salt = random(5, 16)
        for (word in listOf("password", "пароль с пробелом", "pässwörd-€")) {
            val reference = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(word.toCharArray(), salt, 2_500, 256)).encoded
            assertArrayEquals(word, reference, BackupFormat.pbkdf2(BackupFormat.passwordBytes(word.toCharArray()), salt, 2_500))
        }
        // RFC 7914, section 11: PBKDF2-HMAC-SHA-256 ("passwd", "salt", 1 round), the first 32 bytes.
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc",
            BackupFormat.pbkdf2("passwd".toByteArray(), "salt".toByteArray(), 1).joinToString("") { "%02x".format(it) },
        )
        val chosen = BackupFormat.calibrate(targetMs = 50)
        assertTrue("never below the floor: $chosen", chosen >= BackupFormat.MIN_ITERATIONS)
    }

    /**
     * Leaves a copy made with the real number of rounds in build/backup-sample/, with the tree
     * it was made from beside it: scripts/backup/decrypt.py is checked against it by hand
     * (see docs/backup.md).
     */
    @Test
    fun `a sample for the standalone decryptor`() {
        val sample = File("build/backup-sample").apply {
            deleteRecursively()
            mkdirs()
        }
        val files = plant()
        write("files/data/big.bin", random(11, 3 * 1024 * 1024 + 17))
        val copy = File(sample, "sample.axb")
        backup(files, copy, iterations = BackupFormat.MIN_ITERATIONS)
        File(sample, "password.txt").writeText(String(password))
        val out = File(sample, "expected").apply { mkdirs() }
        restore(copy, out)
        assertEquals(tree(files).keys.filter { !it.startsWith("data/.webcache") && it != "state/store/install-transaction.json" }.toSet(), tree(File(out, "files")).keys)
        // What a third-party tool sees after decryption is an ordinary ZIP.
        val plain = copy.inputStream().use { BackupFormat.open(it, password).readBytes() }
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(plain)).use { zip ->
            while (true) names.add((zip.nextEntry ?: break).name)
        }
        assertEquals(BackupArchive.MANIFEST, names.first())
        assertTrue(names.contains("files/data/games/empty/"))
    }
}
