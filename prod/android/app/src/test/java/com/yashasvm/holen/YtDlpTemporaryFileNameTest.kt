package com.yashasvm.holen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpTemporaryFileNameTest {
    @Test
    fun fragmentAndResumeArtifactsCannotBeCompletedMedia() {
        listOf(
            "clip.mp4.part",
            "clip.mp4.ytdl",
            "clip.mp4.temp",
            "clip.mp4.part-Frag1",
            "clip.mp4.part-Frag49.part",
        ).forEach { fileName ->
            assertTrue(
                "$fileName must remain a temporary yt-dlp artifact",
                YtDlpEngine.isYtDlpTemporaryFileName(fileName),
            )
        }
    }

    @Test
    fun finalizedMediaNamesRemainEligible() {
        listOf(
            "clip.mp4",
            "clip (HOLEN).mp4",
            "audio.m4a",
            "part-Frag documentary.mp4",
        ).forEach { fileName ->
            assertFalse(
                "$fileName must remain eligible as completed media",
                YtDlpEngine.isYtDlpTemporaryFileName(fileName),
            )
        }
    }
}
