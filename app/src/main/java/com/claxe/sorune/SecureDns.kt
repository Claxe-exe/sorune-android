package com.claxe.sorune

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Secure DNS for Sorune only: a tiny proxy on the phone itself. WebView hands it its HTTPS
 * connections; it looks the site's name up over DNS-over-HTTPS (the chosen service, reached by address,
 * so the operator's DNS is never asked) and joins the two ends. The encrypted traffic passes untouched,
 * and the rest of the phone keeps its own DNS.
 */
object SecureDns {
    private var server: ServerSocket? = null
    private val cache = ConcurrentHashMap<String, Pair<InetAddress, Long>>()

    /** Starts the proxy (only reachable from this phone) and returns its port. */
    @Synchronized
    fun start(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = socket
        thread(name = "sorune-secure-dns", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(client) } }
            }
        }
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(client: Socket) = client.use {
        val input = BufferedInputStream(client.getInputStream())
        val output = client.getOutputStream()
        val request = readHead(input)?.lineSequence()?.firstOrNull()?.split(" ") ?: return
        val target = request.getOrNull(1)?.let(::splitHostPort)
        if (request[0] != "CONNECT" || target == null) {
            output.write("HTTP/1.1 405 Method Not Allowed\r\n\r\n".toByteArray())
            return
        }
        val remote = runCatching {
            Socket().apply { connect(InetSocketAddress(resolve(target.first), target.second), 15_000) }
        }.getOrElse {
            output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
            return
        }
        remote.use {
            output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            output.flush()
            // Page to site on its own thread; whichever side ends first closes both.
            thread(isDaemon = true) {
                pipe(input, remote.getOutputStream())
                runCatching { remote.shutdownOutput() }
            }
            pipe(remote.getInputStream(), output)
        }
    }

    /** The request head up to the empty line; the rest stays in the stream for the tunnel. */
    private fun readHead(input: InputStream): String? {
        val head = StringBuilder()
        while (head.length < 8192) {
            val byte = input.read()
            if (byte < 0) return null
            head.append(byte.toChar())
            if (head.endsWith("\r\n\r\n")) return head.toString()
        }
        return null
    }

    private fun splitHostPort(value: String): Pair<String, Int>? {
        val colon = value.lastIndexOf(':')
        if (colon <= 0) return null
        val port = value.substring(colon + 1).toIntOrNull() ?: return null
        return value.substring(0, colon).removePrefix("[").removeSuffix("]") to port
    }

    private fun pipe(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        runCatching {
            while (true) {
                val count = from.read(buffer)
                if (count < 0) break
                to.write(buffer, 0, count)
                to.flush()
            }
        }
    }

    /**
     * DNS-over-HTTPS: the chosen service, then the others if it cannot be reached (the phone may
     * have held back the network while Sorune was in the background). If none answers, an older
     * answer is still better than the operator's, which for a blocked site is the block page.
     * The phone's own DNS is the very last resort and is never remembered.
     */
    private fun resolve(host: String): InetAddress {
        if (host.all { it.isDigit() || it == '.' } || ':' in host) return InetAddress.getByName(host)
        val now = System.currentTimeMillis()
        val cached = cache[host]
        if (cached != null && cached.second > now) return cached.first
        val order = listOf(provider) + Provider.entries.filter { it != provider }
        for (service in order) {
            val found = try {
                lookup(host, service.url)
            } catch (e: Exception) {
                failures++
                lastProblem = "${service.title} cevap vermedi (${e.javaClass.simpleName})"
                lastProblemAt = now
                continue
            }
            // A working service with no address for the name: asking the others would not help.
            if (found == null) break
            answered++
            cache[host] = found.first to now + found.second.coerceAtLeast(300) * 1000
            return found.first
        }
        cached?.let { return it.first }
        return InetAddress.getByName(host)
    }

    @Volatile var answered = 0
        private set
    @Volatile var failures = 0
        private set
    @Volatile var lastProblem: String? = null
        private set
    @Volatile var lastProblemAt = 0L
        private set

    /** Asks the chosen service (RFC 8484, the standard every one of them speaks): A first, then AAAA. */
    fun lookup(host: String, resolver: String = provider.url): Pair<InetAddress, Long>? {
        for (type in intArrayOf(1, 28)) {
            val query = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(dnsQuery(host, type))

            val connection = URL("$resolver?dns=$query").openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Accept", "application/dns-message")
            val body = try {
                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
            dnsAnswer(body, type)?.let { return it }
        }
        return null
    }

    private fun dnsQuery(host: String, type: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0)) // id 0, recursion wanted, one question
        for (label in host.trimEnd('.').split('.')) {
            val bytes = label.toByteArray()
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(byteArrayOf(0, 0, type.toByte(), 0, 1))
        return out.toByteArray()
    }

    /** The first address of [type] in a DNS answer, with its lifetime in seconds. */
    private fun dnsAnswer(message: ByteArray, type: Int): Pair<InetAddress, Long>? {
        fun u16(at: Int) = ((message[at].toInt() and 0xff) shl 8) or (message[at + 1].toInt() and 0xff)
        fun skipName(start: Int): Int {
            var at = start
            while (true) {
                val length = message[at].toInt() and 0xff
                if (length == 0) return at + 1
                if (length and 0xc0 == 0xc0) return at + 2
                at += length + 1
            }
        }
        if (message.size < 12) return null
        var at = 12
        repeat(u16(4)) { at = skipName(at) + 4 }
        repeat(u16(6)) {
            at = skipName(at)
            val recordType = u16(at)
            val ttl = ((u16(at + 4).toLong()) shl 16) or u16(at + 6).toLong()
            val length = u16(at + 8)
            at += 10
            if (recordType == type && (length == 4 || length == 16))
                return InetAddress.getByAddress(message.copyOfRange(at, at + length)) to ttl.coerceIn(30, 3600)
            at += length
        }
        return null
    }

    /** Services to choose from, reached by address where their certificate allows it (the operator's DNS is then never needed). */
    enum class Provider(val id: String, val title: String, val note: String, val url: String, val privateDns: String) {
        CLOUDFLARE("cloudflare", "Cloudflare", "Hızlı, Türkiye'de sunucusu var (önerilen)", "https://1.1.1.1/dns-query", "one.one.one.one"),
        GOOGLE("google", "Google", "Hızlı ve kararlı", "https://8.8.8.8/dns-query", "dns.google"),
        ADGUARD("adguard", "AdGuard", "Reklam ve izleyicileri de engeller", "https://94.140.14.14/dns-query", "dns.adguard-dns.com"),
        OPENDNS("opendns", "OpenDNS", "Cisco'nun, kararlı", "https://146.112.41.2/dns-query", "one.one.one.one"),
        DNSSB("dnssb", "DNS.SB", "Kayıt tutmaz, gizliliğe odaklı", "https://185.222.222.222/dns-query", "dot.sb");

        companion object {
            fun find(id: String?) = entries.firstOrNull { it.id == id } ?: CLOUDFLARE
        }
    }

    @Volatile
    var provider: Provider = Provider.CLOUDFLARE
        set(value) {
            field = value
            cache.clear()
        }
}