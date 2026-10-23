package com.claxe.sorune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LibraryTest {
    private fun folder() = Files.createTempDirectory("sorune-library").toFile()

    @Test
    fun repeatVisitsCountOnceAndSurviveARestart() {
        val dir = folder()
        val library = Library(dir)
        library.visit("https://a.com", "A", now = 1_000)
        library.visit("https://a.com", "A again", now = 2_000)
        library.visit("https://b.com", "", now = 3_000)
        library.titleFor("https://b.com", "B")
        assertEquals(listOf("https://b.com", "https://a.com"), library.history.map { it.url })
        assertEquals("A again", library.history[1].title)
        library.save()

        val reopened = Library(dir)
        assertEquals("B", reopened.history[0].title)
        assertEquals(1, reopened.searchHistory("a again").size)
    }

    @Test
    fun bookmarksToggleAndComeFirstInSuggestions() {
        val library = Library(folder())
        library.visit("https://www.webtoons.com/en/", "Webtoon history", now = 1_000)
        assertTrue(library.toggleBookmark("https://www.webtoons.com/tr/", "WEBTOON TR", now = 2_000))
        assertTrue(library.isBookmarked("https://www.webtoons.com/tr/"))
        val suggestions = library.suggest("webtoon")
        assertEquals("https://www.webtoons.com/tr/", suggestions[0].first)
        assertEquals(2, suggestions.size)
        assertFalse(library.toggleBookmark("https://www.webtoons.com/tr/", "WEBTOON TR"))
        assertFalse(library.isBookmarked("https://www.webtoons.com/tr/"))
    }

    @Test
    fun oldHistoryIsDropped() {
        val library = Library(folder())
        val day = 24 * 60 * 60 * 1000L
        library.visit("https://old.com", "Old", now = 0)
        library.visit("https://new.com", "New", now = 200 * day)
        assertEquals(listOf("https://new.com"), library.history.map { it.url })
    }
}
