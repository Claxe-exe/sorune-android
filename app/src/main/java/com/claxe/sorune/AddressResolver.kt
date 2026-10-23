package com.claxe.sorune

import java.net.URLEncoder

/** A search engine: where searches go and where suggestions come from (OpenSearch JSON). */
data class SearchEngine(val id: String, val name: String, val queryTemplate: String, val suggestTemplate: String) {
    fun searchUrl(query: String): String = queryTemplate.replace("{q}", URLEncoder.encode(query, "UTF-8").replace("+", "%20"))
}

object SearchEngines {
    val all = listOf(
        SearchEngine("google", "Google", "https://www.google.com/search?q={q}",
            "https://suggestqueries.google.com/complete/search?client=firefox&ie=utf-8&oe=utf-8&hl=tr&q={q}"),
        SearchEngine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q={q}", "https://duckduckgo.com/ac/?type=list&q={q}"),
        SearchEngine("bing", "Bing", "https://www.bing.com/search?q={q}", "https://api.bing.com/osjson.aspx?query={q}"),
        SearchEngine("brave", "Brave Search", "https://search.brave.com/search?q={q}", "https://search.brave.com/api/suggest?q={q}"),
    )

    fun find(id: String?): SearchEngine = all.firstOrNull { it.id == id } ?: all[0]
}

/** What the address bar does with typed text: open it as an address, or search for it. */
object AddressResolver {
    private val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val host = Regex("^(localhost|(\\d{1,3}\\.){3}\\d{1,3}|([\\p{L}\\d-]+\\.)+[\\p{L}]{2,})(:\\d{1,5})?([/?#].*)?$")

    fun resolve(input: String, engine: SearchEngine): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (scheme.containsMatchIn(text) || text.startsWith("about:")) return text
        if (!text.contains(' ') && host.matches(text)) return "https://$text"
        return engine.searchUrl(text)
    }

    /** True when the text would be opened as a site rather than searched. */
    fun isAddress(input: String): Boolean {
        val text = input.trim()
        return scheme.containsMatchIn(text) || (!text.contains(' ') && host.matches(text))
    }
}

/** "0.10.2" style versions, compared number by number. */
object Versions {
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parts(version: String): List<Int> =
        version.trim().removePrefix("v").substringBefore('-').substringBefore('+').split('.').map { it.toIntOrNull() ?: 0 }
}
