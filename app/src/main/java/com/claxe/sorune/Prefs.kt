package com.claxe.sorune

import android.content.Context
import org.json.JSONArray

/** Settings and the open tabs (restored on the next start). */
class Prefs(context: Context) {
    private val store = context.getSharedPreferences("sorune", Context.MODE_PRIVATE)

    var themeId: String
        get() = store.getString("theme", Themes.DEFAULT_ID) ?: Themes.DEFAULT_ID
        set(value) = store.edit().putString("theme", value).apply()

    var searchEngine: String
        get() = store.getString("engine", "google") ?: "google"
        set(value) = store.edit().putString("engine", value).apply()

    var searchSuggestions: Boolean
        get() = store.getBoolean("suggest", true)
        set(value) = store.edit().putBoolean("suggest", value).apply()

    /** Translate the screen by itself when it changes (from the menu). */
    var autoScreenTranslate: Boolean
        get() = store.getBoolean("autoScreen", false)
        set(value) = store.edit().putBoolean("autoScreen", value).apply()

    /** How often automatic screen translation looks, 3–20 seconds. */
    var autoScreenSeconds: Int
        get() = store.getInt("autoScreenSeconds", 10).coerceIn(3, 20)
        set(value) = store.edit().putInt("autoScreenSeconds", value.coerceIn(3, 20)).apply()

    /** Ads and pop-ups blocked (on unless turned off). */
    var adBlock: Boolean
        get() = store.getBoolean("adBlock", true)
        set(value) = store.edit().putBoolean("adBlock", value).apply()

    /** Address bar at the bottom, within thumb's reach. */
    var toolbarBottom: Boolean
        get() = store.getBoolean("toolbarBottom", false)
        set(value) = store.edit().putBoolean("toolbarBottom", value).apply()

    /** Sites shown dark (their own dark theme, or darkened by Android when they have none). */
    var siteDark: Boolean
        get() = store.getBoolean("siteDark", false)
        set(value) = store.edit().putBoolean("siteDark", value).apply()

    /** Page text size, 50–200 %. */
    var textZoom: Int
        get() = store.getInt("textZoom", 100).coerceIn(50, 200)
        set(value) = store.edit().putInt("textZoom", value.coerceIn(50, 200)).apply()

    /** A fullscreen video keeps playing in a small window when leaving Sorune. */
    var pictureInPicture: Boolean
        get() = store.getBoolean("pip", true)
        set(value) = store.edit().putBoolean("pip", value).apply()

    /** New tab look (fonts, clock, effect, dim, blur, own shortcuts), as JSON. */
    var newTab: String
        get() = store.getString("newTab", "{}") ?: "{}"
        set(value) = store.edit().putString("newTab", value).apply()

    /** Swipe the address bar sideways to switch tabs. */
    /** Where the tabs button lives: "bar" (address bar), "menu" (☰ panel) or "swap" (tabs in the panel, quick menu in the bar). */
    var tabsPlace: String
        get() = store.getString("tabsPlace", null) ?: if (store.getBoolean("tabsInMenu", false)) "menu" else "bar"
        set(value) = store.edit().putString("tabsPlace", value).apply()

    var swipeTabs: Boolean
        get() = store.getBoolean("swipeTabs", true)
        set(value) = store.edit().putBoolean("swipeTabs", value).apply()

    /** Pull the page down to reload (off: it can fire on sites that scroll inside a box, like webtoon readers). */
    var hideToolbar: Boolean
        get() = store.getBoolean("hideToolbar", true)
        set(value) = store.edit().putBoolean("hideToolbar", value).apply()

    var secureDns: Boolean
        get() = store.getBoolean("secureDns", false)
        set(value) = store.edit().putBoolean("secureDns", value).apply()

    var privateLock: Boolean
        get() = store.getBoolean("privateLock", false)
        set(value) = store.edit().putBoolean("privateLock", value).apply()

    var dataSaver: Boolean
        get() = store.getBoolean("dataSaver", false)
        set(value) = store.edit().putBoolean("dataSaver", value).apply()

    var pullRefresh: Boolean
        get() = store.getBoolean("pullRefresh", false)
        set(value) = store.edit().putBoolean("pullRefresh", value).apply()

    /** The language pages and screens are translated into. */
    var translateLanguage: String
        get() = store.getString("translateLanguage", "tr") ?: "tr"
        set(value) = store.edit().putString("translateLanguage", value).apply()

    /** Sites always opened as their desktop version (host names). */
    var desktopSites: Set<String>
        get() = store.getStringSet("desktopSites", emptySet()) ?: emptySet()
        set(value) = store.edit().putStringSet("desktopSites", value.toSet()).apply()

    /** When updates were last looked for (ms); checked at most every few hours. */
    var lastUpdateCheck: Long
        get() = store.getLong("updateCheck", 0)
        set(value) = store.edit().putLong("updateCheck", value).apply()

    fun saveSession(urls: List<String>, active: Int) {
        store.edit()
            .putString("session", JSONArray(urls).toString())
            .putInt("sessionActive", active)
            .apply()
    }

    fun loadSession(): Pair<List<String>, Int> {
        val urls = runCatching {
            val array = JSONArray(store.getString("session", "[]"))
            List(array.length()) { array.getString(it) }
        }.getOrDefault(emptyList())
        return urls to store.getInt("sessionActive", 0)
    }
}
