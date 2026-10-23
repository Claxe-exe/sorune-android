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
