package com.yashasvm.holen

import org.junit.Assert.assertEquals
import org.junit.Test

class FragmentFailureClassificationTest {
    @Test
    fun `fragment not found gets actionable recovery guidance`() {
        val result = friendlyFailure(RuntimeException("ERROR: fragment not found; Skipping fragment 12"))

        assertEquals(
            "One or more media fragments could not be downloaded completely. Re-analyze the link and retry; if it persists, update the media engine before changing quality.",
            result,
        )
    }

    @Test
    fun `empty output after fragment failures gets actionable recovery guidance`() {
        val result = friendlyFailure(RuntimeException("ERROR: The downloaded file is empty"))

        assertEquals(
            "One or more media fragments could not be downloaded completely. Re-analyze the link and retry; if it persists, update the media engine before changing quality.",
            result,
        )
    }

    @Test
    fun `http fragment failure keeps more specific access guidance`() {
        val result = friendlyFailure(
            RuntimeException("ERROR: unable to download video data: HTTP Error 403: Forbidden"),
        )

        assertEquals(
            "The source denied access (HTTP 403). If this is public media and cookies are configured, retry once without cookies; otherwise refresh the cookies/account access.",
            result,
        )
    }
}
