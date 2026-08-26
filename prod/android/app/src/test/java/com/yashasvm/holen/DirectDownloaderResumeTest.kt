package com.yashasvm.holen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectDownloaderResumeTest {
    @Test
    fun strongEtagIsPreferredForIfRange() {
        assertEquals(
            "\"abc123\"",
            DirectDownloader.selectResumeValidator(
                "\"abc123\"",
                "Wed, 21 Oct 2015 07:28:00 GMT",
            ),
        )
    }

    @Test
    fun weakEtagFallsBackToLastModified() {
        assertEquals(
            "Wed, 21 Oct 2015 07:28:00 GMT",
            DirectDownloader.selectResumeValidator(
                "W/\"abc123\"",
                "Wed, 21 Oct 2015 07:28:00 GMT",
            ),
        )
    }

    @Test
    fun resumeIsDisabledWithoutAUsableValidator() {
        assertNull(DirectDownloader.selectResumeValidator("W/\"abc123\"", null))
        assertNull(DirectDownloader.selectResumeValidator(null, null))
    }
}
