package com.yashasvm.holen

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aria2PolicyTest {
    @Test
    fun externalDownloaderUsesBoundedRetryAndTimeoutPolicy() {
        val source = sequenceOf(
            File("app/src/main/java/com/yashasvm/holen/YtDlpEngine.kt"),
            File("src/main/java/com/yashasvm/holen/YtDlpEngine.kt"),
            File("prod/android/app/src/main/java/com/yashasvm/holen/YtDlpEngine.kt"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("YtDlpEngine.kt not found from test working directory")

        assertTrue(
            source.contains(
                "\"--downloader-args\", \"aria2c:--max-tries=4 --connect-timeout=20 --timeout=20\"",
            ),
        )
        assertFalse(source.contains("--retry-wait"))
    }
}
