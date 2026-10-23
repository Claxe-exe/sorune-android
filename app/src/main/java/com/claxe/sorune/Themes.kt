package com.claxe.sorune

import android.content.Context
import android.graphics.Color
import org.json.JSONObject

/** A Sorune theme (the same JSON files as the desktop app). */
data class SoruneTheme(
    val id: String,
    val name: String,
    val description: String,
    val wallpaper: String?,
    /** Where the character stands in the wallpaper (0 = left, 1 = right): kept in view on narrow screens. */
    val focusX: Float,
    val base: Int,
    val chromeTint: Int,
    val chromeTintOpacity: Float,
    val surface: Int,
    val border: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val accent: Int,
    val accentSecondary: Int,
    val overlay: Int,
    val hex: Map<String, String>,
)

object Themes {
    const val DEFAULT_ID = "midnight-mei"

    /**
     * Crops a wallpaper like "cover" but keeps the focus point in the middle of the screen where
     * possible: a phone held upright shows only a narrow slice of a wide picture.
     */
    fun focusMatrix(imageWidth: Int, imageHeight: Int, viewWidth: Int, viewHeight: Int, focusX: Float): android.graphics.Matrix {
        val scale = maxOf(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
        val scaledWidth = imageWidth * scale
        val dx = (viewWidth / 2f - focusX * scaledWidth).coerceIn(viewWidth - scaledWidth, 0f)
        val dy = (viewHeight - imageHeight * scale) / 2f
        return android.graphics.Matrix().apply { setScale(scale, scale); postTranslate(dx, dy) }
    }

    /** Pages reach the theme files under this address (served from the app's assets). */
    const val ASSET_ORIGIN = "https://appassets.androidplatform.net"

    fun list(context: Context): List<SoruneTheme> =
        (context.assets.list("themes") ?: emptyArray())
            .filter { it.endsWith(".json") }
            .mapNotNull { file -> runCatching { parse(context.assets.open("themes/$file").bufferedReader().use { it.readText() }) }.getOrNull() }
            .sortedWith(compareBy({ it.id != DEFAULT_ID }, { it.name }))

    fun find(context: Context, id: String?): SoruneTheme {
        val all = list(context)
        return all.firstOrNull { it.id == id } ?: all.first()
    }

    fun wallpaperUrl(theme: SoruneTheme): String? = theme.wallpaper?.let { "$ASSET_ORIGIN/themes/$it" }

    fun thumbUrl(theme: SoruneTheme): String? = theme.wallpaper?.let { "$ASSET_ORIGIN/themes/thumbs/$it" }

    fun parse(json: String): SoruneTheme {
        val root = JSONObject(json)
        val colors = root.getJSONObject("colors")
        fun c(name: String, fallback: String = "#FF00FF") = colors.optString(name, fallback)
        val hex = colors.keys().asSequence().associateWith { colors.getString(it) }
        val overlay = root.optJSONObject("newTab")?.optString("overlay") ?: c("base")
        return SoruneTheme(
            id = root.getString("id"),
            name = root.getString("name"),
            description = root.optString("description"),
            wallpaper = root.optString("wallpaper").takeIf { it.isNotEmpty() },
            focusX = root.optDouble("focusX", 0.5).toFloat().coerceIn(0f, 1f),
            base = Color.parseColor(c("base")),
            chromeTint = Color.parseColor(c("chromeTint", c("base"))),
            chromeTintOpacity = root.optDouble("chromeTintOpacity", 0.72).toFloat(),
            surface = Color.parseColor(c("surface")),
            border = Color.parseColor(c("border")),
            textPrimary = Color.parseColor(c("textPrimary")),
            textSecondary = Color.parseColor(c("textSecondary")),
            accent = Color.parseColor(c("accent")),
            accentSecondary = Color.parseColor(c("accentSecondary", c("accent"))),
            overlay = Color.parseColor(overlay),
            hex = hex + ("overlay" to overlay),
        )
    }
}
