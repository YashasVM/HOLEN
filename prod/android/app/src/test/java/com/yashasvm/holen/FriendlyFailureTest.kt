package com.yashasvm.holen

import java.io.IOException
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendlyFailureTest {
    @Test
    fun directHttpFailuresAreActionableByStatusClass() {
        assertTrue(friendlyFailure(IOException("Network response 401")).contains("denied access"))
        assertTrue(friendlyFailure(IOException("Network response 403")).contains("access"))
        assertTrue(friendlyFailure(IOException("Network response 404")).contains("no longer available"))
        assertTrue(friendlyFailure(IOException("Network response 410")).contains("fresh download URL"))
        assertTrue(friendlyFailure(IOException("Network response 429")).contains("rate-limiting"))
        assertTrue(friendlyFailure(IOException("Network response 503")).contains("temporarily unavailable"))
        assertTrue(friendlyFailure(IOException("Network response 418")).contains("HTTP 418"))
    }
}
