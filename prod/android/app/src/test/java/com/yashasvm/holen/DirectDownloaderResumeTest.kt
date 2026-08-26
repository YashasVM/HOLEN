package com.yashasvm.holen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectDownloaderResumeTest {
    @Test
    fun strongEtagIsAcceptedForIfRange() {
        assertEquals(
            "\"abc123\"",
            DirectDownloader.selectResumeValidator("\"abc123\""),
        )
    }

    @Test
    fun weakEtagDoesNotEnableResume() {
        assertNull(DirectDownloader.selectResumeValidator("W/\"abc123\""))
    }

    @Test
    fun resumeIsDisabledWithoutAUsableValidator() {
        assertNull(DirectDownloader.selectResumeValidator(null))
    }
}
