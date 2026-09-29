package com.yashasvm.holen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateVersionTest {
    @Test
    fun androidReleaseTagsExposeNumericVersion() {
        assertEquals("5.0.3", AppUpdateVersion.androidVersionFromTag("android-v5.0.3"))
        assertEquals("5.1.0", AppUpdateVersion.androidVersionFromTag(" android-v5.1.0 "))
        assertNull(AppUpdateVersion.androidVersionFromTag("windows-v5.0.3"))
        assertNull(AppUpdateVersion.androidVersionFromTag("android-latest"))
    }

    @Test
    fun androidReleaseTagsParticipateInVersionComparison() {
        assertTrue(AppUpdateVersion.isNewer("android-v5.0.3", "5.0.2"))
        assertFalse(AppUpdateVersion.isNewer("android-v5.0.2", "5.0.2"))
        assertFalse(AppUpdateVersion.isNewer("windows-v6.0.0", "5.0.2"))
    }

    @Test
    fun updateAssetMatchesTheDeviceAbi() {
        val assets = listOf(
            "HOLEN-v5.1.0-arm64-v8a.apk",
            "HOLEN-v5.1.0-armeabi-v7a.apk",
            "HOLEN-v5.1.0-universal.apk",
            "SHA256SUMS",
        )
        assertEquals(
            "HOLEN-v5.1.0-arm64-v8a.apk",
            AppUpdateVersion.selectApkAssetName(assets, listOf("arm64-v8a", "armeabi-v7a", "armeabi")),
        )
        assertEquals(
            "HOLEN-v5.1.0-armeabi-v7a.apk",
            AppUpdateVersion.selectApkAssetName(assets, listOf("armeabi-v7a", "armeabi")),
        )
        assertEquals(
            "HOLEN-v5.1.0-universal.apk",
            AppUpdateVersion.selectApkAssetName(assets.drop(1), listOf("arm64-v8a")),
        )
        assertNull(AppUpdateVersion.selectApkAssetName(assets, listOf("x86_64")))
    }
}
