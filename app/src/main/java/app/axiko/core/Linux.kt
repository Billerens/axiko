package app.axiko.core

import android.os.Build
import android.system.Os
import app.axiko.App
import app.axiko.agent.Llm
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * A real Linux on the phone: an Ubuntu file tree in the app's storage, entered through PRoot
 * (no root needed). It gives the agents what Android itself lacks — a package manager, Python,
 * Node, git, ffmpeg, compilers — and room for long-running programs such as a local model.
 *
 * Android does not let an app execute files it has downloaded, so PRoot itself ships inside
 * the APK as native libraries, and it loads the programs of the Linux tree on their behalf.
 */
object Linux {
    private const val RELEASES = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/"

    private val base: File get() = File(App.instance.filesDir, "linux")
    val rootfs: File get() = File(base, "rootfs")
    private val marker: File get() = File(base, "installed")
    private val logs: File get() = File(base, "logs").apply { mkdirs() }

    /** arm64 on phones, amd64 on the emulator. */
    private val arch: String get() = if (Build.SUPPORTED_ABIS.first().startsWith("arm64")) "arm64" else "amd64"

    val installed: Boolean get() = marker.isFile

    @Volatile
    var installing = false
        private set

    private val running = ConcurrentHashMap<String, Process>()

    /**
     * The bundled PRoot is built for 4 KB memory pages, which is what phones use today. A system
     * with 16 KB pages (some new devices, the newest emulator images) refuses to load it.
     */
    val supported: Boolean get() = Os.sysconf(android.system.OsConstants._SC_PAGESIZE) <= 4096

    fun status(): JSONObject = JSONObject().put("installed", installed).put("installing", installing).put("arch", arch)
        .put("supported", supported)
        .put("running", org.json.JSONArray(running.filterValues { it.isAlive }.keys.sorted()))

    private fun emit(data: JSONObject) = Pages.broadcast("linux.install", data.toString())

    // ---- installing ---------------------------------------------------------------------------

    /** Downloads the Ubuntu base image and unpacks it; progress goes to pages as "linux.install". */
    fun install() {
        if (installed || installing) return
        installing = true
        try {
            base.mkdirs()
            // Point releases come and go: take the latest image that is there.
            val listing = Llm.client.newCall(Request.Builder().url(RELEASES).build()).execute().use { it.body?.string().orEmpty() }
            val image = Regex("ubuntu-base-24\\.04(\\.\\d+)?-base-$arch\\.tar\\.gz").findAll(listing).map { it.value }.distinct()
                .maxByOrNull { it.substringAfter("24.04").substringBefore("-base").trim('.').toIntOrNull() ?: 0 }
                ?: error("Не нашёлся образ Ubuntu для $arch")
            val archive = File(base, "image.tar.gz")
            Llm.client.newCall(Request.Builder().url(RELEASES + image).build()).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) error("Не удалось скачать образ: HTTP ${response.code}")
                val total = body.contentLength()
                var done = 0L
                var reported = 0L
                archive.outputStream().use { out ->
                    val buffer = ByteArray(256 * 1024)
                    val input = body.byteStream()
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        if (System.currentTimeMillis() - reported > 400) {
                            reported = System.currentTimeMillis()
                            emit(JSONObject().put("stage", "download").put("done", done).put("total", total))
                        }
                    }
                }
            }
            emit(JSONObject().put("stage", "unpack"))
            rootfs.deleteRecursively()
            rootfs.mkdirs()
            GZIPInputStream(archive.inputStream().buffered(1 shl 16)).use { untar(it, rootfs) }
            archive.delete()
            configure()
            marker.writeText(image)
            emit(JSONObject().put("stage", "done"))
        } catch (error: Exception) {
            emit(JSONObject().put("stage", "error").put("message", error.message ?: error.toString()))
            throw error
        } finally {
            installing = false
        }
    }

    fun remove() {
        running.values.forEach { it.destroy() }
        running.clear()
        marker.delete()
        // Read-only directories of the image would stop a plain delete.
        runCatching { ProcessBuilder("sh", "-c", "chmod -R u+rwx '${base.path}'; rm -rf '${base.path}'").start().waitFor() }
        base.deleteRecursively()
    }

    /** What a fresh image needs to be usable from inside an Android app. */
    private fun configure() {
        fun file(path: String, text: String) {
            val target = File(rootfs, path)
            target.parentFile?.mkdirs()
            target.delete()
            target.writeText(text)
        }
        file("etc/resolv.conf", "nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        file("etc/hosts", "127.0.0.1 localhost\n::1 localhost\n")
        // apt drops privileges to a user that does not exist here, and asks questions nobody answers.
        file("etc/apt/apt.conf.d/99axiko", "APT::Sandbox::User \"root\";\nDpkg::Use-Pty \"0\";\nAcquire::Languages \"none\";\nAPT::Install-Recommends \"false\";\n")
        // Android's own group ids, so that tools do not complain about nameless groups.
        File(rootfs, "etc/group").appendText(
            "aid_inet:x:3003:root\naid_net_raw:x:3004:root\naid_everybody:x:9997:root\n" +
                "aid_app:x:${android.os.Process.myUid()}:root\n",
        )
        File(rootfs, "root").mkdirs()
        File(rootfs, "tmp").mkdirs()
    }

    // ---- unpacking ----------------------------------------------------------------------------

    /** Unpacks a tar stream: files, directories, symbolic and hard links, with their modes. */
    private fun untar(input: InputStream, into: File) {
        val header = ByteArray(512)
        var longName: String? = null
        var longLink: String? = null

        fun readFully(buffer: ByteArray, length: Int): Boolean {
            var got = 0
            while (got < length) {
                val read = input.read(buffer, got, length - got)
                if (read < 0) return false
                got += read
            }
            return true
        }

        fun text(from: Int, length: Int): String {
            var end = from
            while (end < from + length && header[end].toInt() != 0) end++
            return String(header, from, end - from)
        }

        fun skip(bytes: Long) {
            var left = bytes
            val scratch = ByteArray(8192)
            while (left > 0) {
                val read = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
                if (read < 0) return
                left -= read
            }
        }

        while (readFully(header, 512)) {
            if (header.all { it.toInt() == 0 }) break
            val size = text(124, 12).trim().ifEmpty { "0" }.toLong(8)
            val padded = (size + 511) / 512 * 512
            val type = header[156].toInt().toChar()
            var name = longName ?: (text(345, 155).let { if (it.isEmpty()) "" else "$it/" } + text(0, 100))
            var link = longLink ?: text(157, 100)
            val mode = text(100, 8).trim().ifEmpty { "644" }.toInt(8) and 0xFFF

            if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                // A longer name for the entry that follows, in the GNU or the pax way.
                val data = ByteArray(size.toInt())
                readFully(data, size.toInt())
                skip(padded - size)
                val value = String(data).trimEnd('\u0000', '\n')
                when (type) {
                    'L' -> longName = value
                    'K' -> longLink = value
                    'x' -> for (record in value.split('\n')) {
                        val field = record.substringAfter(' ', "")
                        if (field.startsWith("path=")) longName = field.removePrefix("path=")
                        if (field.startsWith("linkpath=")) longLink = field.removePrefix("linkpath=")
                    }
                }
                continue
            }
            longName = null
            longLink = null

            name = name.removePrefix("./").trimEnd('/')
            if (name.isEmpty() || name.split('/').contains("..")) {
                skip(padded)
                continue
            }
            val target = File(into, name)
            target.parentFile?.mkdirs()
            when (type) {
                '5' -> {
                    target.mkdirs()
                    runCatching { Os.chmod(target.path, mode or 0x1C0) }   // the owner can always enter and write
                }
                '2' -> {
                    target.delete()
                    runCatching { Os.symlink(link, target.path) }
                }
                '1' -> {
                    // A hard link: the other name is a path inside the archive.
                    val source = File(into, link.removePrefix("./"))
                    target.delete()
                    runCatching { Os.link(source.path, target.path) }.onFailure { runCatching { source.copyTo(target, overwrite = true) } }
                }
                '0', '\u0000', '7' -> {
                    target.delete()
                    target.outputStream().use { out ->
                        var left = size
                        val buffer = ByteArray(1 shl 16)
                        while (left > 0) {
                            val read = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            left -= read
                        }
                    }
                    skip(padded - size)
                    runCatching { Os.chmod(target.path, mode or 0x180) }   // the owner can always read and write
                    continue
                }
                // Device nodes and pipes cannot be made by an app; the tree works without them.
            }
            skip(padded)
        }
    }

    // ---- running ------------------------------------------------------------------------------

    private fun process(command: String): ProcessBuilder {
        if (!installed) error("Linux-окружение не установлено. Откройте «Настройки» Axiko, раздел «Linux-окружение».")
        if (!supported) error("Linux-окружение не работает на этом устройстве: у него страницы памяти по 16 КБ, а встроенный PRoot собран под 4 КБ.")
        val libs = App.instance.applicationInfo.nativeLibraryDir
        val tmp = File(base, "tmp").apply { mkdirs() }
        val shm = File(base, "shm").apply { mkdirs() }
        val arguments = listOf(
            "$libs/libproot.so", "--kill-on-exit", "--link2symlink", "-L", "-0",
            "-r", rootfs.path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${shm.path}:/dev/shm",
            // The modules' data folder, shared with the Linux side as /axiko.
            "-b", "${Workspace.data.path}:/axiko",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LANG=C.UTF-8", "TERM=xterm-256color", "TMPDIR=/tmp",
            "DEBIAN_FRONTEND=noninteractive",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "/bin/bash", "-lc", command,
        )
        return ProcessBuilder(arguments).redirectErrorStream(true).apply {
            environment().apply {
                remove("LD_PRELOAD")
                remove("LD_LIBRARY_PATH")
                put("PROOT_LOADER", "$libs/libproot-loader.so")
                put("PROOT_LOADER_32", "$libs/libproot-loader32.so")
                put("PROOT_TMP_DIR", tmp.path)
            }
        }
    }

    /** Runs a command in the Linux environment (bash, as root there) and waits for it. */
    fun run(command: String, timeoutMs: Long = 120_000): Shell.Result {
        val started = process(command).start()
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                started.inputStream.bufferedReader().use { stream ->
                    val buffer = CharArray(8192)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        if (output.length < 200_000) output.append(buffer, 0, read)
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        reader.join(timeoutMs)
        if (reader.isAlive) {
            started.destroy()
            reader.join(500)
            return Shell.Result(-1, output.toString() + "\n… команда остановлена: не уложилась в ${timeoutMs / 1000} с")
        }
        return Shell.Result(started.waitFor(), output.toString())
    }

    /** Starts a long-running program (a server) under a name; its output goes to a log file. */
    fun spawn(name: String, command: String) {
        stop(name)
        val log = File(logs, "$name.log")
        running[name] = process(command).redirectOutput(ProcessBuilder.Redirect.to(log)).start()
    }

    fun stop(name: String) {
        running.remove(name)?.destroy()
    }

    fun alive(name: String): Boolean = running[name]?.isAlive == true

    /** The last lines a spawned program printed. */
    fun log(name: String, lines: Int = 40): String =
        File(logs, "$name.log").takeIf { it.isFile }?.readLines()?.takeLast(lines)?.joinToString("\n").orEmpty()
}
