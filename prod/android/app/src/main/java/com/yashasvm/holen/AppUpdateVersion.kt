package com.yashasvm.holen

/** Small, dependency-free helpers used before an APK is downloaded. */
internal object AppUpdateVersion {
    private val version = Regex("""^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:[-+].*)?$""")

    /**
     * GitHub's latest-release endpoint intentionally excludes prereleases. We only accept a
     * conventional numeric release tag so an arbitrary tag can never trigger an update prompt.
     */
    fun isNewer(candidate: String, installed: String): Boolean {
        val candidateParts = parse(candidate) ?: return false
        val installedParts = parse(installed) ?: return false
        return candidateParts.zip(installedParts).firstOrNull { it.first != it.second }
            ?.let { it.first > it.second }
            ?: false
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
