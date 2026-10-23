package com.claxe.sorune

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import org.json.JSONObject
import java.io.File

/**
 * Themes the user makes from their own picture: the picture becomes the wallpaper, a palette of
 * its vivid colours is offered for the accent, and the dark chrome colours are tinted to match.
 * Stored in the app's private folder and served to Sorune's pages at /custom/.
 */
object CustomThemes {
    const val URL_PATH = "/custom/"
    private const val PENDING = "pending.jpg"
    private const val MAX_SIDE = 2048

    fun folder(context: Context): File = File(context.filesDir, "themes").apply { mkdirs() }

    fun pendingFile(context: Context) = File(folder(context), PENDING)

    /**
     * Copies the chosen picture in (scaled to at most 2048 px) and returns the colours offered
     * for the accent. Null when it isn't a picture Android can read.
     */
    fun stage(context: Context, uri: Uri): List<Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        pendingFile(context).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return palette(bitmap)
    }

    /** Turns the staged picture into a theme; returns its id. */
    fun create(context: Context, name: String, accent: Int, focusX: Float): String? {
        val pending = pendingFile(context)
        if (!pending.exists()) return null
        val id = "custom-" + System.currentTimeMillis()
        val wallpaper = File(folder(context), "$id.jpg")
        if (!pending.renameTo(wallpaper)) return null

        BitmapFactory.decodeFile(wallpaper.path)?.let { full ->
            val width = 480
            val thumb = Bitmap.createScaledBitmap(full, width, (full.height * width.toFloat() / full.width).toInt().coerceAtLeast(1), true)
            File(folder(context), "$id-thumb.jpg").outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }

        val colors = colorsFor(accent)
        val json = JSONObject()
            .put("id", id)
            .put("name", name.trim().ifEmpty { "Kendi temam" }.take(24))
            .put("description", "Kendi resminden")
            .put("wallpaper", "$id.jpg")
            .put("thumbnail", "$id-thumb.jpg")
            .put("custom", true)
            .put("focusX", focusX.coerceIn(0f, 1f).toDouble())
            .put("colors", JSONObject(colors))
            .put("chromeTintOpacity", 0.72)
            .put("newTab", JSONObject().put("overlay", colors["base"]))
        File(folder(context), "$id.json").writeText(json.toString(2))
        return id
    }

    fun delete(context: Context, id: String) {
        if (!id.startsWith("custom-") || id.contains('/') || id.contains('\\')) return
        listOf("$id.json", "$id.jpg", "$id-thumb.jpg").forEach { File(folder(context), it).delete() }
    }

    fun list(context: Context): List<SoruneTheme> =
        (folder(context).listFiles { f -> f.name.startsWith("custom-") && f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { runCatching { Themes.parse(it.readText()) }.getOrNull() }
            .sortedBy { it.id }

    /** Up to six vivid colours of the picture, most common first (fallbacks for a grey picture). */
    fun palette(bitmap: Bitmap): List<Int> {
        val width = 64
        val height = (bitmap.height * width.toFloat() / bitmap.width).toInt().coerceIn(1, 128)
        val small = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        val counts = IntArray(24)
        val sums = Array(24) { FloatArray(3) }
        val hsv = FloatArray(3)
        for (p in pixels) {
            Color.colorToHSV(p, hsv)
            if (hsv[1] < 0.35f || hsv[2] < 0.3f) continue
            val bucket = (hsv[0] / 15f).toInt().coerceIn(0, 23)
            counts[bucket]++
            sums[bucket][0] += hsv[0]; sums[bucket][1] += hsv[1]; sums[bucket][2] += hsv[2]
        }
        val colors = counts.indices.filter { counts[it] >= 6 }.sortedByDescending { counts[it] }.take(6).map { i ->
            val n = counts[i].toFloat()
            Color.HSVToColor(floatArrayOf(sums[i][0] / n, maxOf(0.55f, sums[i][1] / n), maxOf(0.85f, sums[i][2] / n)))
        }
        val fallback = listOf("#B583FF", "#63D2FF", "#FF4D6A", "#4ADE80", "#F472B6", "#E9C46A").map { Color.parseColor(it) }
        return (colors + fallback).distinct().take(6)
    }

    /** Dark chrome tinted with the accent's hue, like the built-in themes. */
    fun colorsFor(accent: Int): Map<String, String> {
        val hsv = FloatArray(3)
        Color.colorToHSV(accent, hsv)
        val h = hsv[0]
        fun hex(s: Float, v: Float, hue: Float = h) = String.format("#%06X", 0xFFFFFF and Color.HSVToColor(floatArrayOf((hue + 360f) % 360f, s, v)))
        return mapOf(
            "base" to hex(0.35f, 0.06f),
            "chromeTint" to hex(0.35f, 0.08f),
            "surface" to hex(0.28f, 0.14f),
            "surfaceHover" to hex(0.26f, 0.18f),
            "surfacePressed" to hex(0.24f, 0.22f),
            "border" to hex(0.22f, 0.21f),
            "textPrimary" to hex(0.06f, 0.96f),
            "textSecondary" to hex(0.18f, 0.74f),
            "accent" to String.format("#%06X", 0xFFFFFF and accent),
            "accentSecondary" to hex(maxOf(0.45f, hsv[1]), maxOf(0.8f, hsv[2]), h + 35f),
            "danger" to "#E5484D",
            "warning" to "#F2B24C",
        )
    }
}
