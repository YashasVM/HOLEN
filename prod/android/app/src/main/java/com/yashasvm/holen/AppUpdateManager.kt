package com.yashasvm.holen

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class AppRelease(
    val tag: String,
    val versionName: String,
    val title: String,
    val notes: String,
    val assetUrl: String,
    val assetName: String,
)

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data class Available(val release: AppRelease) : AppUpdateState
    data class Downloading(
        val release: AppRelease,
        val downloadedBytes: Long,
        val totalBytes: Long?,
    ) : AppUpdateState
    data class Ready(val release: AppRelease, val apk: File) : AppUpdateState
    data class Error(val message: String) : AppUpdateState
}

/**
 * Checks one explicit GitHub repository and downloads only its ARM64 release asset. The platform
 * package installer remains the final authority and always asks the person using the device.
 */
class AppUpdateManager(private val context: Context) {
    suspend fun latestRelease(): AppRelease? = withContext(Dispatchers.IO) {
        val body = getText(LATEST_RELEASE_URL)
        val release = JSONObject(body)
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return@withContext null
        val tag = release.optString("tag_name").trim()
        val versionName = AppUpdateVersion.androidVersionFromTag(tag) ?: return@withContext null
        if (!AppUpdateVersion.isNewer(versionName, BuildConfig.VERSION_NAME)) return@withContext null
        val asset = selectArm64Asset(release.optJSONArray("assets")) ?: return@withContext null
        AppRelease(
            tag = tag,
            versionName = versionName,
            title = release.optString("name").ifBlank { "HOLEN $versionName" },
            notes = release.optString("body").take(MAX_NOTES_CHARS),
            assetUrl = asset.getString("browser_download_url"),
            assetName = asset.getString("name"),
        )
    }

    suspend fun download(release: AppRelease, onProgress: (Long, Long?) -> Unit): File =
        withContext(Dispatchers.IO) {
            val updates = File(context.cacheDir, UPDATE_DIRECTORY).apply { mkdirs() }
            if (!updates.isDirectory) throw IOException("Update storage is unavailable.")
            updates.listFiles()?.forEach { if (it.name.endsWith(".apk", true)) it.delete() }
            val target = File(updates, "HOLEN-${release.versionName}-arm64.apk")
            val temporary = File(updates, "${target.name}.part")
            temporary.delete()
            downloadFile(release.assetUrl, temporary, onProgress)
            validateApk(temporary)
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            target
        }

    private fun selectArm64Asset(assets: JSONArray?): JSONObject? {
        if (assets == null) return null
        return (0 until assets.length())
            .mapNotNull { assets.optJSONObject(it) }
            .firstOrNull { asset ->
                val name = asset.optString("name").lowercase()
                name.endsWith(".apk") && "arm64" in name &&
                    isAllowedUrl(asset.optString("browser_download_url"))
            }
    }

    private fun getText(url: String): String {
        val connection = open(url)
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Update check returned HTTP ${connection.responseCode}.")
            }
            val length = connection.contentLengthLong
            if (length > MAX_METADATA_BYTES) throw IOException("Update metadata is too large.")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readText().also {
                    if (it.length > MAX_METADATA_BYTES) throw IOException("Update metadata is too large.")
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadFile(url: String, target: File, onProgress: (Long, Long?) -> Unit) {
        val connection = open(url)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Update download returned HTTP ${connection.responseCode}.")
            }
            val total = connection.contentLengthLong.takeIf { it >= 0L }
            if (total != null && total > MAX_APK_BYTES) throw IOException("The update is too large.")
            var downloaded = 0L
            var lastProgressAt = 0L
            var lastReportedBytes = 0L
            val deadline = SystemClock.elapsedRealtime() + DOWNLOAD_TIMEOUT_MS
            connection.inputStream.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        coroutineContext.ensureActive()
                        val now = SystemClock.elapsedRealtime()
                        if (now > deadline) throw IOException("Update download timed out.")
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        if (downloaded > MAX_APK_BYTES) throw IOException("The update is too large.")
                        output.write(buffer, 0, count)
                        if (now - lastProgressAt >= PROGRESS_INTERVAL_MS || downloaded == total) {
                            onProgress(downloaded, total)
                            lastProgressAt = now
                            lastReportedBytes = downloaded
                        }
                    }
                }
            }
            if (downloaded == 0L || (total != null && downloaded != total)) {
                throw IOException("The update download was incomplete.")
            }
            if (downloaded != lastReportedBytes) onProgress(downloaded, total)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(initialUrl: String): HttpURLConnection {
        var url = initialUrl
        repeat(MAX_REDIRECTS + 1) { attempt ->
            if (!isAllowedUrl(url)) throw IOException("Update link is not from GitHub.")
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = connection.responseCode
            if (code !in 300..399) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (attempt == MAX_REDIRECTS || location.isNullOrBlank()) throw IOException("Invalid update redirect.")
            url = URL(URL(url), location).toString()
        }
        error("Unreachable")
    }

    private fun validateApk(file: File) {
        val archive = packageInfo(file) ?: throw IOException("The downloaded file is not an Android app.")
        if (archive.packageName != context.packageName) throw IOException("This update is for a different app.")
        if (archive.longVersionCode <= currentPackage().longVersionCode) {
            throw IOException("This update is not newer than the installed app.")
        }
        if (!sameSigner(currentPackage(), archive)) {
            throw IOException("This update was not signed with HOLEN's installed signing key.")
        }
    }

    @Suppress("DEPRECATION")
    private fun currentPackage(): PackageInfo = packageManager().getPackageInfo(
        context.packageName,
        PackageManager.GET_SIGNING_CERTIFICATES,
    )

    @Suppress("DEPRECATION")
    private fun packageInfo(file: File): PackageInfo? = packageManager().getPackageArchiveInfo(
        file.absolutePath,
        PackageManager.GET_SIGNING_CERTIFICATES,
    )

    private fun packageManager() = context.packageManager

    private fun sameSigner(installed: PackageInfo, archive: PackageInfo): Boolean {
        fun fingerprints(info: PackageInfo): Set<String> = info.signingInfo?.apkContentsSigners
            ?.map { signer ->
                MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
                    .joinToString("") { byte -> "%02x".format(byte) }
            }
            ?.toSet()
            .orEmpty()
        val installedSigners = fingerprints(installed)
        val archiveSigners = fingerprints(archive)
        return installedSigners.isNotEmpty() && installedSigners == archiveSigners
    }

    private fun isAllowedUrl(value: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.userInfo == null &&
            AppUpdateVersion.isAllowedGitHubHost(uri.host)
    }.getOrDefault(false)

    companion object {
        const val UPDATE_DIRECTORY = "updates"
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/YashasVM/HOLEN/releases/latest"
        private const val USER_AGENT = "HOLEN-Android-Updater"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val DOWNLOAD_TIMEOUT_MS = 10 * 60_000L
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val MAX_REDIRECTS = 5
        private const val MAX_METADATA_BYTES = 1_000_000
        private const val MAX_NOTES_CHARS = 3_000
        private const val MAX_APK_BYTES = 250L * 1024L * 1024L
        private const val BUFFER_BYTES = 32 * 1024
    }
}
