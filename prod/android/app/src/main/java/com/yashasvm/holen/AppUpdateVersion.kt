package com.yashasvm.holen

/** Small, dependency-free helpers used before an APK is downloaded. */
internal object AppUpdateVersion {
    private val version = Regex("""^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:[-+].*)?$""")
    private const val ANDROID_TAG_PREFIX = "android-"

    /** Returns the numeric Android app version encoded by this repository's release tag. */
    fun androidVersionFromTag(tag: String): String? {
        val taggedVersion = tag.trim().takeIf { it.startsWith(ANDROID_TAG_PREFIX) }
            ?.removePrefix(ANDROID_TAG_PREFIX)
            ?: return null
        if (parse(taggedVersion) == null) return null
        return taggedVersion.removePrefix("v")
    }

    /** Only conventional numeric or project Android release versions can trigger updates. */
    fun isNewer(candidate: String, installed: String): Boolean {
        val normalizedCandidate = androidVersionFromTag(candidate) ?: candidate
        val candidateParts = parse(normalizedCandidate) ?: return false
        val installedParts = parse(installed) ?: return false
        return candidateParts.zip(installedParts).firstOrNull { it.first != it.second }
            ?.let { it.first > it.second }
            ?: false
    }

    /**
     * Picks the release APK for the device's preferred ABI, falling back to the universal APK.
     * Installing an APK without a matching native ABI fails, so an unknown ABI yields null.
     */
    fun selectApkAssetName(assetNames: List<String>, supportedAbis: List<String>): String? {
        val apks = assetNames.filter { it.endsWith(".apk", ignoreCase = true) }
        for (abi in supportedAbis) {
            val tokens = when (abi) {
                "arm64-v8a" -> listOf("arm64-v8a", "arm64")
                "armeabi-v7a" -> listOf("armeabi-v7a", "armv7")
                else -> continue
            }
            apks.firstOrNull { name -> tokens.any { name.contains(it, ignoreCase = true) } }
                ?.let { return it }
        }
        val supportsArm = supportedAbis.any { it == "arm64-v8a" || it == "armeabi-v7a" }
        return apks.firstOrNull { supportsArm && it.contains("universal", ignoreCase = true) }
    }

    fun isAllowedGitHubHost(host: String?): Boolean = host?.lowercase() in setOf(
        "api.github.com",
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    private fun parse(value: String): List<Long>? {
        val match = version.matchEntire(value.trim()) ?: return null
        return (1..3).map { index -> match.groupValues[index].ifBlank { "0" }.toLongOrNull() ?: return null }
    }
}
