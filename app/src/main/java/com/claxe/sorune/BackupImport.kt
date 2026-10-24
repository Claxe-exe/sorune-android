package com.claxe.sorune

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.util.zip.ZipInputStream

/**
 * Reads a backup made by Sorune on the computer (Settings › Back up and move, a .sorune zip):
 * bookmarks, new tab shortcuts, history (if the backup has it), the search engine, the theme and
 * a picture set as wallpaper there. Nothing is written here; the caller decides what to keep.
 */
object BackupImport {
    private const val MAX_ENTRY = 64L * 1024 * 1024

    class Result(
        val bookmarks: List<Pair<String, String>>,
        val shortcuts: List<Pair<String, String>>,
        val history: List<Visit>,
        val searchEngine: String?,
        val theme: String?,
        /** The computer's own wallpaper picture, copied to a temporary file. */
        val wallpaper: File?,
    )

    /** Null if the file is not a Sorune backup. */
    fun read(input: InputStream, temp: File): Result? {
        val texts = mutableMapOf<String, String>()
        val pictures = mutableMapOf<String, File>()
        var isBackup = false
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory || ".." in name) continue
                when {
                    name == "manifest.json" -> isBackup = runCatching { JSONObject(zip.readLimited().decodeToString()).optString("app") == "Sorune" }.getOrDefault(false)
                    name in setOf("bookmarks.json", "shortcuts.json", "settings.json", "secure/history.json") -> texts[name] = zip.readLimited().decodeToString()
                    name.startsWith("wallpapers/") -> {
                        val file = File(temp, "import-" + name.substringAfter('/').filter { it.isLetterOrDigit() || it in ".-_" })
                        file.outputStream().use { out -> zip.copyLimited(out) }
                        pictures[name.substringAfter('/')] = file
                    }
                }
            }
        }
        if (!isBackup) {
            pictures.values.forEach { it.delete() }
            return null
        }

        fun array(name: String) = texts[name]?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
        fun web(url: String) = url.startsWith("https://") || url.startsWith("http://")

        val bookmarks = array("bookmarks.json").objects()
            .map { it.optString("url") to it.optString("title") }
            .filter { web(it.first) }
        val shortcuts = array("shortcuts.json").objects()
            .map { it.optString("title").take(24) to it.optString("url") }
            .filter { web(it.second) && it.first.isNotBlank() }
        val history = array("secure/history.json").objects().mapNotNull {
            val url = it.optString("url")
            val time = runCatching { Instant.parse(it.optString("visitedAt")).toEpochMilli() }.getOrNull()
            if (web(url) && time != null) Visit(url, it.optString("title"), time) else null
        }
        val settings = texts["settings.json"]?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        // "custom:{file}" is a picture of the user's own in wallpapers/.
        val wallpaperName = settings.optString("wallpaper").removePrefix("custom:").takeIf { settings.optString("wallpaper").startsWith("custom:") }
        val wallpaper = wallpaperName?.let { pictures[it] }
        pictures.values.filter { it != wallpaper }.forEach { it.delete() }
        return Result(
            bookmarks, shortcuts, history,
            settings.optString("searchEngine").ifBlank { null },
            settings.optString("theme").ifBlank { null },
            wallpaper,
        )
    }

    private fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }

    private fun ZipInputStream.readLimited(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        copyLimited(out)
        return out.toByteArray()
    }

    private fun ZipInputStream.copyLimited(out: java.io.OutputStream) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_ENTRY) throw java.io.IOException("Yedekteki bir dosya çok büyük")
            out.write(buffer, 0, count)
        }
    }
}
