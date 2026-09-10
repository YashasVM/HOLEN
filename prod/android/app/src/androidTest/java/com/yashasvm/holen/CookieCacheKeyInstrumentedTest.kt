package com.yashasvm.holen

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CookieCacheKeyInstrumentedTest {
    private lateinit var store: CookieStore

    @Before
    fun setUp() {
        store = CookieStore(ApplicationProvider.getApplicationContext())
        store.clear()
    }

    @After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun cookieStateGenerationPreservesHitsAndIsolatesChanges() {
        val noCookiesKey = store.cacheKey()
        assertEquals(noCookiesKey, store.cacheKey())

        store.save(validCookies("first"))
        val firstAuthenticatedKey = store.cacheKey()
        assertEquals(firstAuthenticatedKey, store.cacheKey())
        assertTrue(store.cookieArguments().contains("--cookies"))

        store.save(validCookies("second"))
        val replacementKey = store.cacheKey()
        assertNotEquals(firstAuthenticatedKey, replacementKey)
        assertEquals(replacementKey, store.cacheKey())

        store.clear()
        val clearedKey = store.cacheKey()
        assertNotEquals(noCookiesKey, clearedKey)
        assertNotEquals(replacementKey, clearedKey)
        assertEquals(clearedKey, store.cacheKey())
    }

    private fun validCookies(value: String): String = """
        # Netscape HTTP Cookie File
        .youtube.com	TRUE	/	TRUE	0	SID	$value
    """.trimIndent()
}
