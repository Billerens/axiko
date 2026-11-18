package app.axiko.core

import android.net.wifi.WifiManager
import app.axiko.App
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * The phone's name on the local network: `axiko.local` instead of an address made of numbers.
 *
 * Computers find such names by asking everyone on the network at once (multicast DNS); whoever
 * bears the name answers with its address. Android gives an app no way to choose the name it is
 * announced under, so this answers by itself: it listens to the questions and, when one is about
 * its name, tells the phone's address. Nothing else of the protocol is needed for a browser to
 * open `http://axiko.local:8765`.
 */
object Mdns {
    private const val PORT = 5353
    private const val TTL_SECONDS = 120
    private const val A = 1
    private const val AAAA = 28
    private const val NSEC = 47
    private const val ANY = 255
    private val GROUP: InetAddress = InetAddress.getByName("224.0.0.251")

    @Volatile
    private var socket: MulticastSocket? = null

    @Volatile
    private var lock: WifiManager.MulticastLock? = null

    /** The name it answers to, without ".local"; empty when it is not running. */
    @Volatile
    var name = ""
        private set

    /** How many questions about the name it has answered: for checks. */
    @Volatile
    var answered = 0
        private set

    val running: Boolean get() = socket != null

    @Synchronized
    fun start(host: String) {
        stop()
        val made = runCatching {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
                timeToLive = 255
                soTimeout = 30_000
            }
        }.getOrElse {
            Engine.host.log("error", "Имя $host.local в сети не объявлено: " + Engine.describe(it))
            return
        }
        // Without this the phone's Wi-Fi drops what is sent to everyone, and the questions never arrive.
        lock = runCatching {
            App.instance.getSystemService(WifiManager::class.java).createMulticastLock("axiko-name").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        name = host.lowercase()
        socket = made
        Thread({ listen(made) }, "axiko-name").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        socket = null
        runCatching { lock?.release() }
        lock = null
        name = ""
    }

    /** The networks the phone is on with an address of a home or office network. */
    private fun networks(): List<Pair<NetworkInterface, Inet4Address>> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && it.supportsMulticast() }.mapNotNull { network ->
            network.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.let { network to it }
        }
    }.getOrDefault(emptyList())

    private fun listen(socket: MulticastSocket) {
        val joined = HashSet<String>()
        // The phone moves between networks: each one it appears on is joined, and told the name.
        fun join() {
            for ((network, _) in networks()) {
                if (!joined.add(network.name)) continue
                runCatching { socket.joinGroup(InetSocketAddress(GROUP, PORT), network) }
                    .onSuccess { answer(socket, wantsAddress = true, wantsNewer = false) }
                    .onFailure { joined.remove(network.name) }
            }
            joined.retainAll(networks().map { it.first.name }.toSet())
        }
        val buffer = ByteArray(9000)
        var joinedAt = 0L
        while (!socket.isClosed) {
            // A busy LAN can deliver hundreds of unrelated multicast packets a minute.
            // Re-enumerate interfaces on a timer, not for every packet received.
            val now = android.os.SystemClock.elapsedRealtime()
            if (joinedAt == 0L || now - joinedAt >= 30_000) {
                join()
                joinedAt = now
            }
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (quiet: SocketTimeoutException) {
                continue
            } catch (closed: Exception) {
                break
            }
            runCatching {
                val asked = questions(packet.data, packet.length).filter { it.first.equals("$name.local", ignoreCase = true) }.map { it.second }
                if (asked.isEmpty()) return@runCatching
                answer(socket, wantsAddress = asked.any { it == A || it == ANY }, wantsNewer = asked.any { it == AAAA })
                answered++
            }
        }
    }

    /** The names and kinds of records a packet asks about; nothing for a packet that is itself an answer. */
    private fun questions(data: ByteArray, length: Int): List<Pair<String, Int>> {
        if (length < 12 || data[2].toInt() and 0x80 != 0) return emptyList()
        val count = (data[4].toInt() and 0xff shl 8) or (data[5].toInt() and 0xff)
        val out = ArrayList<Pair<String, Int>>()
        var at = 12
        repeat(minOf(count, 32)) {
            val labels = ArrayList<String>()
            var cursor = at
            var after = -1
            var hops = 0
            while (true) {
                if (cursor >= length) return out
                val size = data[cursor].toInt() and 0xff
                if (size == 0) {
                    cursor++
                    break
                }
                if (size and 0xc0 == 0xc0) {
                    // A name may continue elsewhere in the packet.
                    if (cursor + 1 >= length || ++hops > 16) return out
                    if (after < 0) after = cursor + 2
                    cursor = (size and 0x3f shl 8) or (data[cursor + 1].toInt() and 0xff)
                    continue
                }
                if (cursor + 1 + size > length) return out
                labels.add(String(data, cursor + 1, size, Charsets.UTF_8))
                cursor += 1 + size
            }
            at = if (after >= 0) after else cursor
            if (at + 4 > length) return out
            out.add(labels.joinToString(".") to ((data[at].toInt() and 0xff shl 8) or (data[at + 1].toInt() and 0xff)))
            at += 4
        }
        return out
    }

    /**
     * Tells every network the phone is on what address the name has there. To a question about
     * an address of the newer kind (IPv6) the answer is that the name has only the older one —
     * otherwise the computer waits for an answer that never comes before it opens the page.
     */
    private fun answer(socket: MulticastSocket, wantsAddress: Boolean, wantsNewer: Boolean) {
        for ((network, address) in networks()) {
            val out = ByteArrayOutputStream()
            fun short(value: Int) {
                out.write(value shr 8 and 0xff)
                out.write(value and 0xff)
            }
            fun host() {
                for (label in listOf(name, "local")) {
                    val bytes = label.toByteArray(Charsets.UTF_8)
                    out.write(bytes.size)
                    out.write(bytes)
                }
                out.write(0)
            }
            fun record(kind: Int, body: ByteArray) {
                host()
                short(kind)
                short(0x8001)
                short(0)
                short(TTL_SECONDS)
                short(body.size)
                out.write(body)
            }
            short(0)
            short(0x8400)
            short(0)
            short(if (wantsNewer && wantsAddress) 2 else 1)
            short(0)
            short(if (wantsNewer && !wantsAddress) 1 else 0)
            val only = ByteArrayOutputStream().also { body ->
                for (label in listOf(name, "local")) {
                    val bytes = label.toByteArray(Charsets.UTF_8)
                    body.write(bytes.size)
                    body.write(bytes)
                }
                // "Of all kinds of records this name has only the first": an address of the older kind.
                body.write(byteArrayOf(0, 0, 1, 0x40))
            }.toByteArray()
            if (wantsAddress) record(A, address.address)
            if (wantsNewer) record(NSEC, only)
            if (!wantsAddress) record(A, address.address)
            val bytes = out.toByteArray()
            runCatching {
                synchronized(socket) {
                    socket.networkInterface = network
                    socket.send(DatagramPacket(bytes, bytes.size, GROUP, PORT))
                }
            }
        }
    }
}
