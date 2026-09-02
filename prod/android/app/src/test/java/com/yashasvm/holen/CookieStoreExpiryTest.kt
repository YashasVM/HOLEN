package com.yashasvm.holen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieStoreExpiryTest {
    private val header = "# Netscape HTTP Cookie File\n"

    @Test
    fun fullyExpiredCookiesAreRejected() {
        val cookies = header +
            ".youtube.com\tTRUE\t/\tTRUE\t100\tSID\texpired\n"

        assertFalse(CookieStore.validateCookieBytes(cookies.toByteArray()))
        val health = CookieStore.inspectCookieBytes(cookies.toByteArray(), nowEpochSeconds = 200)
        assertEquals(1, health?.totalCookies)
        assertEquals(0, health?.usableCookies)
        assertEquals(1, health?.expiredCookies)
    }

    @Test
    fun sessionCookiesRemainUsable() {
        val cookies = header +
            ".youtube.com\tTRUE\t/\tTRUE\t0\tSID\tsession\n"

        assertTrue(CookieStore.validateCookieBytes(cookies.toByteArray()))
    }

    @Test
    fun mixedCookieFileStaysValidWhileAnyCookieIsUsable() {
        val cookies = header +
            ".youtube.com\tTRUE\t/\tTRUE\t100\tOLD\texpired\n" +
            ".youtube.com\tTRUE\t/\tTRUE\t300\tSID\tcurrent\n"

        val health = CookieStore.inspectCookieBytes(cookies.toByteArray(), nowEpochSeconds = 200)
        assertEquals(2, health?.totalCookies)
        assertEquals(1, health?.usableCookies)
        assertEquals(1, health?.expiredCookies)
        assertTrue(health?.hasYoutubeCookies == true)

        val currentEpochCookies = header +
            ".youtube.com\tTRUE\t/\tTRUE\t4102444800\tSID\tcurrent\n" +
            ".youtube.com\tTRUE\t/\tTRUE\t100\tOLD\texpired\n"
        assertTrue(CookieStore.validateCookieBytes(currentEpochCookies.toByteArray()))
    }
}
