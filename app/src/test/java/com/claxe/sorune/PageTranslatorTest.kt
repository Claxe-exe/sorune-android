package com.claxe.sorune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTranslatorTest {
    @Test
    fun readsBothAnswerShapes() {
        val pairs = PageTranslator.parse("""[["Selam Dünya","en"],["Ben &amp; sen","en"],42]""")
        assertEquals(3, pairs.size)
        assertEquals("Selam Dünya" to "en", pairs[0])
        assertEquals("Ben & sen", pairs[1].first)
        assertEquals("", pairs[2].first)
        assertEquals("Günaydın" to null, PageTranslator.parse("""["Günaydın"]""")[0])
    }

    @Test
    fun languagesAndNames() {
        assertTrue(PageTranslator.sameLanguage("tr", "TR"))
        assertTrue(PageTranslator.sameLanguage("zh-CN", "zh"))
        assertFalse(PageTranslator.sameLanguage(null, "tr"))
        assertEquals("İngilizce", PageTranslator.languageName("en"))
        assertEquals("XX", PageTranslator.languageName("xx"))
    }
}
