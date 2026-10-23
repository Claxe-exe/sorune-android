package com.claxe.sorune

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Small HTTP helpers (no extra library): GET text, download a file with progress. */
object Net {
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) Sorune"

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 8000): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            try {
                if (connection.responseCode !in 200..299) null
                else connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    /** Follows redirects (GitHub release files live on another host). Reports 0..1, or -1 when the size is unknown. */
    suspend fun download(url: String, target: File, progress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            var address = url
            var connection: HttpURLConnection
            var hops = 0
            while (true) {
                connection = URL(address).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.setRequestProperty("User-Agent", USER_AGENT)
                val code = connection.responseCode
                if (code in 300..399 && hops++ < 6) {
                    address = connection.getHeaderField("Location") ?: return@runCatching false
                    connection.disconnect()
                    continue
                }
                if (code !in 200..299) return@runCatching false
                break
            }
            target.parentFile?.mkdirs()
            val partial = File(target.path + ".part")
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        progress(if (total > 0) done.toFloat() / total else -1f)
                    }
                }
            }
            connection.disconnect()
            target.delete()
            partial.renameTo(target)
        }.getOrDefault(false)
    }

    /** The search engine's suggestions for typed text (OpenSearch: ["query", ["s1", "s2", …]]). */
    suspend fun suggestions(engine: SearchEngine, query: String): List<String> {
        val url = engine.suggestTemplate.replace("{q}", URLEncoder.encode(query, "UTF-8"))
        val text = getText(url, timeoutMs = 3000) ?: return emptyList()
        return runCatching {
            val list = JSONArray(text).getJSONArray(1)
            List(list.length()) { list.optString(it) }.filter { it.isNotBlank() && !it.equals(query, ignoreCase = true) }.distinct().take(6)
        }.getOrDefault(emptyList())
    }
}
