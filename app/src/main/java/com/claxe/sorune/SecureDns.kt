package com.claxe.sorune

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Secure DNS for Sorune only: a tiny proxy on the phone itself. WebView hands it its HTTPS
 * connections; it looks the site's name up over DNS-over-HTTPS (Google, reached by address, so the
 * operator's DNS is never asked) and joins the two ends. The encrypted traffic passes untouched,
 * and the rest of the phone keeps its own DNS.
 */
object SecureDns {
    private const val RESOLVER = "https://8.8.8.8/resolve"

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

    /** DNS-over-HTTPS first; the phone's own DNS only if Google cannot be reached. */
    private fun resolve(host: String): InetAddress {
        if (host.all { it.isDigit() || it == '.' } || ':' in host) return InetAddress.getByName(host)
        val now = System.currentTimeMillis()
        cache[host]?.let { (address, until) -> if (until > now) return address }
        val found = runCatching { lookup(host) }.getOrNull()
        val address = found?.first ?: InetAddress.getByName(host)
        cache[host] = address to now + (found?.second ?: 60L) * 1000
        return address
    }

    private fun lookup(host: String): Pair<InetAddress, Long>? {
        for (type in listOf(1, 28)) { // A, then AAAA
            val connection = URL("$RESOLVER?name=${URLEncoder.encode(host, "UTF-8")}&type=$type").openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            val body = try {
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
            val answers = JSONObject(body).optJSONArray("Answer") ?: continue
            for (i in 0 until answers.length()) {
                val answer = answers.getJSONObject(i)
                if (answer.optInt("type") == type)
                    return InetAddress.getByName(answer.getString("data")) to answer.optLong("TTL", 300).coerceIn(30, 3600)
            }
        }
        return null
    }
}
