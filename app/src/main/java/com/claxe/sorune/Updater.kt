package com.claxe.sorune

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

/**
 * Updates from GitHub releases of this app's repository: the newest release's APK is downloaded
 * into the app's cache and handed to Android's installer, which asks for one tap. (Installing
 * without any tap is only possible through an app store.) Every release must be signed with the
 * same key, or Android refuses the update.
 */
object Updater {
    data class Release(val version: String, val apkUrl: String, val notes: String)

    suspend fun latest(): Release? {
        val text = Net.getText(
            "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest",
            mapOf("Accept" to "application/vnd.github+json"),
        ) ?: return null
        return runCatching {
            val json = JSONObject(text)
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
            Release(
                version = json.getString("tag_name").removePrefix("v"),
                apkUrl = apk.getString("browser_download_url"),
                notes = json.optString("body"),
            )
        }.getOrNull()
    }

    fun isNewer(release: Release): Boolean = Versions.isNewer(release.version, BuildConfig.VERSION_NAME)

    fun updateFile(activity: Activity): File = File(activity.cacheDir, "updates/Sorune-update.apk")

    /** False when Android first needs "install unknown apps" allowed for Sorune (its settings page is opened). */
    fun install(activity: Activity, apk: File): Boolean {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            return false
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
