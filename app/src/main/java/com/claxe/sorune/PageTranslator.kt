package com.claxe.sorune

import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

/** Runs script in the page and returns its JSON result (main thread). */
suspend fun WebView.eval(script: String): String = suspendCancellableCoroutine { done ->
    evaluateJavascript(script) { result -> if (done.isActive) done.resume(result ?: "null") }
}

/**
 * Translates a page in place with Google Translate, like the desktop app: the page's text nodes
 * are read, sent to Google in batches and written back, keeping the originals so "show original"
 * restores them. Only visible text goes to Google; never form fields, code or editable areas.
 */
object PageTranslator {
    private const val MAX_BATCH_CHARS = 4500
    private const val MAX_BATCH_ITEMS = 120

    /** Collects text nodes not handled yet and returns [[id, text], …]; defines apply/restore on first use. */
    private val COLLECT = """
        (() => {
            const s = window.__soruneTr || (window.__soruneTr = { nodes: [], originals: new Map(), seen: new WeakSet() });
            if (!s.apply) {
                s.apply = (pairs) => {
                    for (const [id, text] of pairs) {
                        const node = s.nodes[id];
                        if (!node || !node.isConnected) continue;
                        if (!s.originals.has(node)) s.originals.set(node, node.nodeValue);
                        const raw = s.originals.get(node);
                        node.nodeValue = raw.match(/^\s*/)[0] + text + raw.match(/\s*${'$'}/)[0];
                    }
                    return true;
                };
                s.restore = () => {
                    for (const [node, text] of s.originals) if (node.isConnected) node.nodeValue = text;
                    s.originals.clear();
                    s.nodes = [];
                    s.seen = new WeakSet();
                    return true;
                };
            }
            const skip = new Set(["SCRIPT", "STYLE", "NOSCRIPT", "TEXTAREA", "CODE", "PRE", "KBD", "SAMP", "INPUT", "SELECT", "OPTION", "SVG", "MATH"]);
            const out = [];
            let chars = 0;
            const walker = document.createTreeWalker(document.body || document.documentElement, NodeFilter.SHOW_TEXT, {
                acceptNode(node) {
                    if (s.seen.has(node) || !/\p{L}/u.test(node.nodeValue || "")) return NodeFilter.FILTER_REJECT;
                    for (let el = node.parentElement; el; el = el.parentElement) {
                        if (skip.has(el.tagName.toUpperCase()) || el.isContentEditable
                            || el.getAttribute("translate") === "no" || el.classList.contains("notranslate")) return NodeFilter.FILTER_REJECT;
                    }
                    return NodeFilter.FILTER_ACCEPT;
                },
            });
            while (out.length < 800 && chars < 80000 && walker.nextNode()) {
                const node = walker.currentNode;
                s.seen.add(node);
                const text = node.nodeValue.trim().slice(0, 1500);
                out.push([s.nodes.push(node) - 1, text]);
                chars += text.length;
            }
            return out;
        })()
    """.trimIndent()

    const val RESTORE = "window.__soruneTr ? window.__soruneTr.restore() : false"

    data class Result(val translated: Int, val sourceLanguage: String?)

    /** Translates whatever text the page shows that wasn't translated yet. */
    suspend fun translateNewText(view: WebView, target: String): Result {
        val items = parsePairs(view.eval(COLLECT))
        if (items.isEmpty()) return Result(0, null)

        val languages = mutableMapOf<String, Int>()
        var translated = 0
        for (batch in batches(items)) {
            val answers = translate(batch.map { it.second }, target)
            val pairs = JSONArray()
            for (i in 0 until minOf(answers.size, batch.size)) {
                val (text, language) = answers[i]
                if (language != null) languages[language] = (languages[language] ?: 0) + batch[i].second.length
                if (text.isNotEmpty() && text != batch[i].second) pairs.put(JSONArray().put(batch[i].first).put(text))
            }
            if (pairs.length() > 0) {
                view.eval("window.__soruneTr && window.__soruneTr.apply($pairs)")
                translated += pairs.length()
            }
        }
        return Result(translated, languages.maxByOrNull { it.value }?.key)
    }

    /** Google's answer for each text: (translation, detected language). */
    suspend fun translate(texts: List<String>, target: String): List<Pair<String, String?>> = withContext(Dispatchers.IO) {
        val connection = URL("https://translate.googleapis.com/translate_a/t?client=gtx&sl=auto&tl=" + URLEncoder.encode(target, "UTF-8"))
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Sorune")
            val body = texts.joinToString("&") { "q=" + URLEncoder.encode(it, "UTF-8") }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) throw java.io.IOException("HTTP ${connection.responseCode}")
            parse(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    /** Accepts both shapes Google uses: [["text","en"], …] and ["text", …]. */
    fun parse(json: String): List<Pair<String, String?>> {
        val root = JSONTokener(json).nextValue() as? JSONArray ?: return emptyList()
        return List(root.length()) { i ->
            when (val item = root.opt(i)) {
                is String -> unescape(item) to null
                is JSONArray -> unescape(item.optString(0)) to item.optString(1).takeIf { item.length() > 1 && it.isNotEmpty() }
                else -> "" to null
            }
        }
    }

    private fun unescape(text: String): String = text
        .replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    fun sameLanguage(a: String?, b: String?): Boolean =
        a != null && b != null && a.substringBefore('-').equals(b.substringBefore('-'), ignoreCase = true)

    /** The language's Turkish name ("en" → "İngilizce"); the code when unknown. */
    fun languageName(code: String?): String {
        if (code.isNullOrBlank()) return "Bilinmeyen dil"
        return TURKISH_NAMES[code.lowercase().substringBefore('-')] ?: code.uppercase()
    }

    private val TURKISH_NAMES = mapOf(
        "en" to "İngilizce", "tr" to "Türkçe", "de" to "Almanca", "fr" to "Fransızca", "es" to "İspanyolca",
        "it" to "İtalyanca", "pt" to "Portekizce", "ru" to "Rusça", "ja" to "Japonca", "ko" to "Korece",
        "zh" to "Çince", "ar" to "Arapça", "fa" to "Farsça", "nl" to "Felemenkçe", "pl" to "Lehçe",
        "uk" to "Ukraynaca", "el" to "Yunanca", "sv" to "İsveççe", "no" to "Norveççe", "da" to "Danca",
        "fi" to "Fince", "cs" to "Çekçe", "hu" to "Macarca", "ro" to "Romence", "bg" to "Bulgarca",
        "az" to "Azerbaycanca", "hi" to "Hintçe", "id" to "Endonezyaca", "vi" to "Vietnamca", "th" to "Tayca",
        "he" to "İbranice", "iw" to "İbranice", "ka" to "Gürcüce", "kk" to "Kazakça", "sr" to "Sırpça",
    )

    private fun parsePairs(json: String): List<Pair<Int, String>> {
        val root = runCatching { JSONTokener(json).nextValue() as? JSONArray }.getOrNull() ?: return emptyList()
        return (0 until root.length()).mapNotNull { i ->
            val pair = root.optJSONArray(i) ?: return@mapNotNull null
            val text = pair.optString(1)
            if (pair.length() == 2 && text.isNotEmpty()) pair.getInt(0) to text else null
        }
    }

    private fun batches(items: List<Pair<Int, String>>): List<List<Pair<Int, String>>> {
        val result = mutableListOf<List<Pair<Int, String>>>()
        var batch = mutableListOf<Pair<Int, String>>()
        var chars = 0
        for (item in items) {
            if (batch.isNotEmpty() && (batch.size >= MAX_BATCH_ITEMS || chars + item.second.length > MAX_BATCH_CHARS)) {
                result.add(batch)
                batch = mutableListOf()
                chars = 0
            }
            batch.add(item)
            chars += item.second.length
        }
        if (batch.isNotEmpty()) result.add(batch)
        return result
    }
}
