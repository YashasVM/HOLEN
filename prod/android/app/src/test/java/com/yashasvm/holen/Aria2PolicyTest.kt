package com.yashasvm.holen

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aria2PolicyTest {
    @Test
    fun externalDownloaderKeepsTheWrapperCaCertificateArguments() {
        val source = sequenceOf(
            File("app/src/main/java/com/yashasvm/holen/YtDlpEngine.kt"),
            File("src/main/java/com/yashasvm/holen/YtDlpEngine.kt"),
            File("prod/android/app/src/main/java/com/yashasvm/holen/YtDlpEngine.kt"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("YtDlpEngine.kt not found from test working directory")

        // youtubedl-android supplies aria2c's --ca-certificate via --external-downloader-args.
        // yt-dlp keeps only the last value per downloader, so HOLEN must never pass its own.
        assertTrue(source.contains("\"--downloader\", \"libaria2c.so\""))
        assertFalse(source.contains("\"--downloader-args\", \"aria2c:"))
        assertFalse(source.contains("--retry-wait"))
    }
}
