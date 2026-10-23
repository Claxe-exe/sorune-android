package com.claxe.sorune

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A visited page, a bookmark, or a download Sorune started. */
data class Visit(val url: String, var title: String, var time: Long)
data class Bookmark(val url: String, var title: String, val time: Long)
data class DownloadRecord(val id: Long, val name: String, val url: String, val time: Long)

/**
 * History, bookmarks and downloads, kept as small JSON files in the app's private folder.
 * Plain Kotlin (no Android types) so it can be unit tested.
 */
class Library(private val folder: File) {
    companion object {
        const val MAX_HISTORY = 5000
        const val HISTORY_DAYS = 180L
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        /** The same page again within this time counts as one visit. */
        private const val SAME_VISIT_MS = 30 * 60 * 1000L
    }

    val history = mutableListOf<Visit>()
    val bookmarks = mutableListOf<Bookmark>()
    val downloads = mutableListOf<DownloadRecord>()
    var dirty = false
        private set

    init {
        folder.mkdirs()
        read("history.json") { o -> history.add(Visit(o.getString("url"), o.optString("title"), o.optLong("time"))) }
        read("bookmarks.json") { o -> bookmarks.add(Bookmark(o.getString("url"), o.optString("title"), o.optLong("time"))) }
        read("downloads.json") { o -> downloads.add(DownloadRecord(o.getLong("id"), o.optString("name"), o.optString("url"), o.optLong("time"))) }
    }

    // ---------------------------------------------------------------- history (newest first)

    fun visit(url: String, title: String, now: Long = System.currentTimeMillis()) {
        val last = history.firstOrNull()
        if (last != null && last.url == url && now - last.time < SAME_VISIT_MS) {
            last.time = now
            if (title.isNotBlank()) last.title = title
        } else {
            history.add(0, Visit(url, title, now))
        }
        val oldest = now - HISTORY_DAYS * DAY_MS
        history.removeAll { it.time < oldest }
        while (history.size > MAX_HISTORY) history.removeAt(history.lastIndex)
        dirty = true
    }

    /** The title arrives after the page: put it on the latest visit of that page. */
    fun titleFor(url: String, title: String) {
        if (title.isBlank()) return
        history.firstOrNull { it.url == url }?.let { if (it.title != title) { it.title = title; dirty = true } }
        bookmarks.firstOrNull { it.url == url && it.title.isBlank() }?.let { it.title = title; dirty = true }
    }

    fun searchHistory(query: String, limit: Int = 200): List<Visit> {
        val q = query.trim()
        return history.asSequence()
            .filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) || it.url.contains(q, ignoreCase = true) }
            .take(limit).toList()
    }

    fun removeVisit(url: String, time: Long) {
        if (history.removeAll { it.url == url && it.time == time }) dirty = true
    }

    fun clearHistory() {
        history.clear()
        dirty = true
    }

    // ---------------------------------------------------------------- bookmarks (newest first)

    fun isBookmarked(url: String) = bookmarks.any { it.url == url }

    /** Adds or removes; returns true when the page is bookmarked afterwards. */
    fun toggleBookmark(url: String, title: String, now: Long = System.currentTimeMillis()): Boolean {
        dirty = true
        if (bookmarks.removeAll { it.url == url }) return false
        bookmarks.add(0, Bookmark(url, title, now))
        return true
    }

    fun removeBookmark(url: String) {
        if (bookmarks.removeAll { it.url == url }) dirty = true
    }

    // ---------------------------------------------------------------- downloads

    fun addDownload(record: DownloadRecord) {
        downloads.add(0, record)
        while (downloads.size > 200) downloads.removeAt(downloads.lastIndex)
        dirty = true
    }

    fun removeDownload(id: Long) {
        if (downloads.removeAll { it.id == id }) dirty = true
    }

    // ---------------------------------------------------------------- address bar

    /** Pages for the address bar: bookmarks first, then history, each address once. */
    fun suggest(query: String, limit: Int = 4): List<Pair<String, String>> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        fun matches(url: String, title: String) = title.contains(q, ignoreCase = true) || url.removePrefix("https://").removePrefix("www.").contains(q, ignoreCase = true)
        val seen = mutableSetOf<String>()
        return (bookmarks.filter { matches(it.url, it.title) }.map { it.url to it.title } +
            history.asSequence().filter { matches(it.url, it.title) }.take(50).map { it.url to it.title })
            .filter { seen.add(it.first) }
            .take(limit)
    }

    // ---------------------------------------------------------------- files

    /** Writes the three files now. */
    fun save() = snapshot()()

    /**
     * Copies the lists (cheap, on the thread that changes them) and returns the slow part, the
     * writing, which can then run in the background without the lists changing under it.
     */
    fun snapshot(): () -> Unit {
        val h = history.map { JSONObject().put("url", it.url).put("title", it.title).put("time", it.time) }
        val b = bookmarks.map { JSONObject().put("url", it.url).put("title", it.title).put("time", it.time) }
        val d = downloads.map { JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("time", it.time) }
        dirty = false
        return {
            write("history.json", h)
            write("bookmarks.json", b)
            write("downloads.json", d)
        }
    }

    private fun read(name: String, add: (JSONObject) -> Unit) {
        val file = File(folder, name)
        if (!file.exists()) return
        runCatching {
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) runCatching { add(array.getJSONObject(i)) }
        }
    }

    private fun write(name: String, items: List<JSONObject>) {
        val temp = File(folder, "$name.tmp")
        temp.writeText(JSONArray(items).toString())
        temp.renameTo(File(folder, name)).let { moved -> if (!moved) { File(folder, name).delete(); temp.renameTo(File(folder, name)) } }
    }
}
