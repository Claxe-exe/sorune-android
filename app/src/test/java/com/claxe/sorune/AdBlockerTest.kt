package com.claxe.sorune

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockerTest {
    @Test
    fun blocksListedServersAndTheirSubdomains() {
        assertTrue(AdBlocker.isBlocked("doubleclick.net"))
        assertTrue(AdBlocker.isBlocked("securepubads.g.doubleclick.net"))
        assertTrue(AdBlocker.isBlocked("pagead2.googlesyndication.com"))
        assertTrue(AdBlocker.isBlocked("CDN.POPADS.NET."))
    }

    @Test
    fun leavesEverythingElseAlone() {
        assertFalse(AdBlocker.isBlocked("www.google.com"))
        assertFalse(AdBlocker.isBlocked("www.webtoons.com"))
        assertFalse(AdBlocker.isBlocked("notdoubleclick.net"))
        assertFalse(AdBlocker.isBlocked("net"))
        assertFalse(AdBlocker.isBlocked(null))
    }
}
