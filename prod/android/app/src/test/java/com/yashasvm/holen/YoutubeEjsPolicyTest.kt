package com.yashasvm.holen

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeEjsPolicyTest {
    @Test
    fun youtubeUsesOfficialRemoteSolverWithAnExplicitCache() {
        val cache = File("build/test-ejs-cache")
        val expected = listOf(
            "--cache-dir", cache.absolutePath,
            "--remote-components", "ejs:github",
        )

        listOf(
            "https://www.youtube.com/watch?v=BaW_jenozKc",
            "https://youtu.be/BaW_jenozKc",
            "https://www.youtube-nocookie.com/embed/BaW_jenozKc",
        ).forEach { url ->
            assertEquals(expected, YtDlpEngine.youtubeEjsArguments(url, cache))
        }
    }

    @Test
    fun nonYoutubeExtractorsDoNotEnableRemoteComponents() {
        val cache = File("build/test-ejs-cache")

        listOf(
            "https://example.com/video",
            "https://notyoutube.com/video",
            "https://youtube.com.example.org/video",
        ).forEach { url ->
            assertTrue(YtDlpEngine.youtubeEjsArguments(url, cache).isEmpty())
        }
    }
}
