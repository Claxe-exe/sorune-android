package com.claxe.sorune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressResolverTest {
    private val google = SearchEngines.find("google")

    @Test
    fun addressesOpenAsSites() {
        assertEquals("https://example.com", AddressResolver.resolve("example.com", google))
        assertEquals("https://www.webtoons.com/en/", AddressResolver.resolve(" www.webtoons.com/en/ ", google))
        assertEquals("http://192.168.1.2:8080", AddressResolver.resolve("http://192.168.1.2:8080", google))
        assertEquals("https://localhost:3000", AddressResolver.resolve("localhost:3000", google))
    }

    @Test
    fun everythingElseIsSearched() {
        assertEquals("https://www.google.com/search?q=claude%20skills", AddressResolver.resolve("claude skills", google))
        assertEquals("https://www.google.com/search?q=%C3%A7ay", AddressResolver.resolve("çay", google))
        assertNull(AddressResolver.resolve("   ", google))
        assertFalse(AddressResolver.isAddress("what is example.com"))
    }

    @Test
    fun versionsCompareNumberByNumber() {
        assertTrue(Versions.isNewer("0.10.0", "0.9.9"))
        assertTrue(Versions.isNewer("v0.2.0", "0.1.0"))
        assertFalse(Versions.isNewer("0.1.0", "0.1.0"))
        assertFalse(Versions.isNewer("0.1.0", "0.2.0"))
    }
}
