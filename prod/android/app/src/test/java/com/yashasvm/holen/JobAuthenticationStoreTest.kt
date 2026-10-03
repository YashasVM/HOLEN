package com.yashasvm.holen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JobAuthenticationStoreTest {
    @Test
    fun missingOrUnknownPolicyPreservesConfiguredCookies() {
        assertEquals(
            JobAuthenticationPolicy.CONFIGURED,
            JobAuthenticationStore.decodePolicy(null),
        )
        assertEquals(
            JobAuthenticationPolicy.CONFIGURED,
            JobAuthenticationStore.decodePolicy("FUTURE_VALUE"),
        )
    }

    @Test
    fun withoutCookiesPolicyRoundTrips() {
        assertEquals(
            JobAuthenticationPolicy.WITHOUT_COOKIES,
            JobAuthenticationStore.decodePolicy(JobAuthenticationPolicy.WITHOUT_COOKIES.name),
        )
    }

    @Test
    fun preferenceKeysRejectInvalidJobIds() {
        assertThrows(IllegalArgumentException::class.java) {
            JobAuthenticationStore.preferenceKey("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            JobAuthenticationStore.preferenceKey("x".repeat(129))
        }
        assertEquals("job:abc", JobAuthenticationStore.preferenceKey("abc"))
    }
}
