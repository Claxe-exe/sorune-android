package com.claxe.sorune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupImportTest {
    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun readsComputerBackup() {
        val temp = Files.createTempDirectory("backup").toFile()
        val data = zip(
            "manifest.json" to """{"app":"Sorune","format":1}""",
            "bookmarks.json" to """[{"id":"a","url":"https://webtoons.com/","title":"Webtoon","folder":"","addedAt":"2026-10-01T10:00:00Z"},{"url":"javascript:alert(1)","title":"x"}]""",
            "shortcuts.json" to """[{"title":"YouTube","url":"https://www.youtube.com/"}]""",
            "settings.json" to """{"searchEngine":"duckduckgo","theme":"fate-saber","wallpaper":"custom:me.jpg"}""",
            "secure/history.json" to """[{"id":1,"url":"https://example.com/","title":"Example","visitedAt":"2026-10-20T12:00:00Z"}]""",
            "wallpapers/me.jpg" to "picture",
            "wallpapers/other.jpg" to "not used",
        )
        val result = BackupImport.read(ByteArrayInputStream(data), temp)
        assertNotNull(result)
        result!!
        assertEquals(listOf("https://webtoons.com/" to "Webtoon"), result.bookmarks)
        assertEquals(listOf("YouTube" to "https://www.youtube.com/"), result.shortcuts)
        assertEquals("duckduckgo", result.searchEngine)
        assertEquals("fate-saber", result.theme)
        assertEquals(1, result.history.size)
        assertEquals(java.time.Instant.parse("2026-10-20T12:00:00Z").toEpochMilli(), result.history[0].time)
        assertEquals("picture", result.wallpaper!!.readText())
        // Pictures that are not the wallpaper are not kept.
        assertEquals(1, temp.listFiles()!!.size)
    }

    @Test
    fun rejectsOtherZips() {
        val temp = Files.createTempDirectory("backup").toFile()
        assertNull(BackupImport.read(ByteArrayInputStream(zip("bookmarks.json" to "[]")), temp))
    }
}
