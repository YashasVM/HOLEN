package com.yashasvm.holen

import android.content.Context
import androidx.core.content.edit
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

class YtDlpEngine private constructor(private val context: Context) {
    private val initMutex = Mutex()
    private val cookieStore = CookieStore(context)
    private val preferences = context.getSharedPreferences(
        HolenStore.PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Volatile
    private var initialized = false

    @Volatile
    private var ffmpegInitialized = false

    val bundledVersion: String = "youtubedl-android $WRAPPER_VERSION"

    val activeVersion: String
        get() = preferences.getString(HolenStore.PREF_ENGINE_VERSION, null)
            ?: "Bundled (loads on first media analysis)"

    suspend fun analyze(url: String): SourceAnalysis = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            ensureInitialized(needsFfmpeg = false)
            val request = YoutubeDLRequest(url).apply {
                addSiteAuthentication(url)
                addOption("--ignore-config")
                addOption("--dump-single-json")
                addOption("--flat-playlist")
                addOption("--playlist-end", PLAYLIST_PREVIEW_LIMIT)
                addOption("--skip-download")
                addOption("--no-warnings")
            }
            val response = runYoutubeAware(url) {
                YoutubeDL.execute(request, "analysis", null)
            }
            val json = response.out.toJsonObject()
            val entries = json.optJSONArray("entries")
            if (entries != null) json.toPlaylist(url, entries) else json.toMedia(url)
        }
    }

    suspend fun download(
        job: DownloadJob,
        directory: File,
        isCancelled: () -> Boolean = { false },
        onProgress: (TransferProgress) -> Unit,
    ): StagedDownload = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            validatePublicHttpsUrl(job.sourceUrl)
            ensureInitialized(needsFfmpeg = true)
            if (isCancelled()) throw CancellationException("Download cancelled")
            directory.mkdirs()
            val outputTemplate = File(
                directory,
                "%(title).180B [%(id)s].%(ext)s",
            ).absolutePath
            val request = YoutubeDLRequest(job.sourceUrl).apply {
                addSiteAuthentication(job.sourceUrl)
                addOption("--ignore-config")
                addCommands(downloadArguments(job.format))
                addCommands(
                    listOf(
                        "--continue",
                        "--restrict-filenames",
                        "--no-overwrites",
                        "--embed-metadata",
                        "--concurrent-fragments", "2",
                        "--retries", "3",
                        "--fragment-retries", "3",
                        "--socket-timeout", "20",
                        "--newline",
                        "--no-playlist",
                        "--output", outputTemplate,
                    ),
                )
            }
            var lastUpdate = 0L
            runYoutubeAware(job.sourceUrl) {
                YoutubeDL.execute(request, job.id, false) { wrapperPercent, wrapperEta, line ->
                    if (isCancelled()) {
                        YoutubeDL.destroyProcessById(job.id)
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 1_000 || wrapperPercent >= 100f) {
                            val parsed = parseTransferLine(line)
                            onProgress(
                                TransferProgress(
                                    percent = parsed?.percent
                                        ?: wrapperPercent.toInt().coerceIn(0, 100),
                                    bytesDownloaded = null,
                                    totalBytes = null,
                                    speedBytesPerSecond = parsed?.speedBytesPerSecond,
                                    etaSeconds = parsed?.etaSeconds ?: wrapperEta.takeIf { it >= 0 },
                                ),
                            )
                            lastUpdate = now
                        }
                    }
                }
            }
            if (isCancelled()) throw CancellationException("Download cancelled")
            val completed = directory.walkTopDown()
                .filter { file ->
                    file.isFile &&
                        !file.name.endsWith(".part") &&
                        !file.name.endsWith(".ytdl") &&
                        !file.name.endsWith(".temp")
                }
                .maxByOrNull(File::length)
                ?: throw IOException("The media engine completed without an output file.")
            StagedDownload(
                file = completed,
                fileName = sanitizeFileName(completed.name),
                mimeType = OutputStore.mimeTypeFor(completed.name),
            )
        }
    }

    fun cancel(processId: String) {
        if (initialized) YoutubeDL.destroyProcessById(processId)
    }

    fun hasAccountSession(): Boolean = cookieStore.hasSession()

    suspend fun updateStable(): String = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            ensureInitialized(needsFfmpeg = false)
            try {
                YoutubeDL.updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
                val version = validateVersion()
                preferences.edit { putString(HolenStore.PREF_ENGINE_VERSION, version) }
                version
            } catch (error: Throwable) {
                resetToBundledLocked()
                throw IOException("Engine update failed and the bundled engine was restored.", error)
            }
        }
    }

    suspend fun resetToBundled(): String = withContext(Dispatchers.IO) {
        engineMutex.withLock { resetToBundledLocked() }
    }

    private suspend fun resetToBundledLocked(): String =
        initMutex.withLock {
            YoutubeDL.destroyProcessById("analysis")
            val root = File(context.noBackupFilesDir, YoutubeDL.baseName)
            val engineDirectory = File(root, YoutubeDL.ytdlpDirName)
            engineDirectory.deleteRecursively()
            YoutubeDL.init_ytdlp(context, engineDirectory)
            context.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE).edit {
                remove("dlpVersion")
                remove("dlpVersionName")
            }
            val version = "Bundled ${validateVersion()}"
            preferences.edit { putString(HolenStore.PREF_ENGINE_VERSION, version) }
            initialized = true
            version
        }

    private suspend fun ensureInitialized(needsFfmpeg: Boolean) {
        if (initialized && (!needsFfmpeg || ffmpegInitialized)) return
        initMutex.withLock {
            if (!initialized) {
                try {
                    YoutubeDL.init(context)
                    preferences.edit {
                        putString(
                            HolenStore.PREF_ENGINE_VERSION,
                            YoutubeDL.version(context) ?: bundledVersion,
                        )
                    }
                    initialized = true
                } catch (error: Throwable) {
                    val root = File(context.noBackupFilesDir, YoutubeDL.baseName)
                    val engineDirectory = File(root, YoutubeDL.ytdlpDirName)
                    engineDirectory.deleteRecursively()
                    YoutubeDL.init_ytdlp(context, engineDirectory)
                    preferences.edit {
                        putString(HolenStore.PREF_ENGINE_VERSION, bundledVersion)
                    }
                    initialized = true
                }
            }
            if (needsFfmpeg && !ffmpegInitialized) {
                FFmpeg.init(context)
                ffmpegInitialized = true
            }
        }
    }

    private fun validateVersion(): String {
        val response = YoutubeDL.execute(
            YoutubeDLRequest(emptyList()).addOption("--version"),
            "engine-version",
            null,
        )
        return response.out.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: throw IOException("The media engine returned no version.")
    }

    private fun YoutubeDLRequest.addSiteAuthentication(sourceUrl: String) {
        if (isYoutubeUrl(sourceUrl)) {
            cookieStore.fileOrNull()?.let { addOption("--cookies", it.absolutePath) }
            // `default` lets yt-dlp select its current cookie-compatible fallback
            // clients. Forcing Android/TV clients can discard account cookies or
            // require a PO token, so it is intentionally avoided here.
            addOption("--extractor-args", "youtube:player_client=default")
        }
    }

    private fun <T> runYoutubeAware(sourceUrl: String, block: () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        if (!isYoutubeUrl(sourceUrl) || !isYoutubeAuthenticationError(error.message.orEmpty())) {
            throw error
        }
        val guidance = if (cookieStore.hasYouTubeCookies()) {
            "YouTube rejected the saved account session. Update the media engine, then re-import fresh " +
                "YouTube cookies from an age-verified account."
        } else {
            "This YouTube video requires sign-in or age verification. In Settings, import fresh Netscape " +
                "cookies.txt from an age-verified YouTube account, then retry."
        }
        throw IOException(guidance, error)
    }

    private fun JSONObject.toMedia(fallbackUrl: String): SourceAnalysis.Media {
        val formats = optJSONArray("formats") ?: JSONArray()
        val estimates = DownloadFormat.entries
            .filter { it != DownloadFormat.ORIGINAL }
            .associateWith { format -> estimateSize(formats, format) }
        return SourceAnalysis.Media(
            sourceUrl = optString("webpage_url").ifBlank { fallbackUrl },
            title = optString("title").ifBlank { "Untitled media" },
            uploader = optString("uploader").takeIf { it.isNotBlank() },
            durationSeconds = optLong("duration").takeIf { it > 0 },
            thumbnailUrl = optString("thumbnail").takeIf { it.isNotBlank() },
            estimatedSizes = estimates,
        )
    }

    private fun JSONObject.toPlaylist(
        fallbackUrl: String,
        entries: JSONArray,
    ): SourceAnalysis.Playlist {
        val preview = buildList {
            for (index in 0 until minOf(entries.length(), PLAYLIST_PREVIEW_LIMIT)) {
                val item = entries.optJSONObject(index) ?: continue
                val id = item.optString("id").ifBlank { index.toString() }
                val candidate = item.optString("webpage_url")
                    .ifBlank { item.optString("url") }
                if (!candidate.startsWith("https://")) continue
                add(
                    PlaylistEntry(
                        id = id,
                        url = candidate,
                        title = item.optString("title").ifBlank { "Playlist item ${index + 1}" },
                        thumbnailUrl = item.optString("thumbnail").takeIf { it.isNotBlank() },
                        durationSeconds = item.optLong("duration").takeIf { it > 0 },
                    ),
                )
            }
        }
        if (preview.isEmpty()) {
            throw IOException("This playlist did not expose any public downloadable entries.")
        }
        return SourceAnalysis.Playlist(
            sourceUrl = optString("webpage_url").ifBlank { fallbackUrl },
            title = optString("title").ifBlank { "Playlist" },
            uploader = optString("uploader").takeIf { it.isNotBlank() },
            entries = preview,
        )
    }

    companion object {
        const val WRAPPER_VERSION = "0.18.1"
        const val PLAYLIST_PREVIEW_LIMIT = 100
        const val PLAYLIST_QUEUE_LIMIT = 25

        fun downloadArguments(format: DownloadFormat): List<String> = when (format) {
            DownloadFormat.ORIGINAL -> error("Original format is handled by the direct downloader.")
            DownloadFormat.BEST_MP4 -> listOf(
                "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
                "--merge-output-format", "mp4",
            )
            DownloadFormat.MP4_1080 -> listOf(
                "-f",
                "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/best[height<=1080][ext=mp4]/best[height<=1080]",
                "--merge-output-format", "mp4",
            )
            DownloadFormat.MP4_720 -> listOf(
                "-f",
                "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720][ext=mp4]/best[height<=720]",
                "--merge-output-format", "mp4",
            )
            DownloadFormat.AUDIO_M4A -> listOf(
                "-f", "bestaudio[ext=m4a]/bestaudio",
                "--extract-audio",
                "--audio-format", "m4a",
            )
            DownloadFormat.AUDIO_MP3 -> listOf(
                "-f", "bestaudio",
                "--extract-audio",
                "--audio-format", "mp3",
                "--audio-quality", "0",
            )
        }

        private fun estimateSize(formats: JSONArray, target: DownloadFormat): Long? {
            val matching = buildList {
                for (index in 0 until formats.length()) {
                    val item = formats.optJSONObject(index) ?: continue
                    val size = item.optLong("filesize").takeIf { it > 0 }
                        ?: item.optLong("filesize_approx").takeIf { it > 0 }
                        ?: continue
                    val height = item.optInt("height")
                    val video = item.optString("vcodec") != "none"
                    val audio = item.optString("acodec") != "none"
                    val ext = item.optString("ext")
                    val match = when (target) {
                        DownloadFormat.BEST_MP4 -> video
                        DownloadFormat.MP4_1080 -> video && height in 1..1080
                        DownloadFormat.MP4_720 -> video && height in 1..720
                        DownloadFormat.AUDIO_M4A -> audio && !video && ext == "m4a"
                        DownloadFormat.AUDIO_MP3 -> audio && !video
                        DownloadFormat.ORIGINAL -> false
                    }
                    if (match) add(size)
                }
            }
            return matching.maxOrNull()
        }

        private fun String.toJsonObject(): JSONObject {
            val start = indexOf('{')
            val end = lastIndexOf('}')
            if (start < 0 || end <= start) throw IOException("The media engine returned invalid metadata.")
            return JSONObject(substring(start, end + 1))
        }

        private fun isYoutubeUrl(value: String): Boolean {
            val host = runCatching { java.net.URI(value).host?.lowercase() }.getOrNull() ?: return false
            return host == "youtu.be" ||
                host == "youtube.com" ||
                host.endsWith(".youtube.com") ||
                host == "youtube-nocookie.com" ||
                host.endsWith(".youtube-nocookie.com")
        }

        private fun isYoutubeAuthenticationError(message: String): Boolean {
            val normalized = message.lowercase()
            return listOf(
                "sign in",
                "login",
                "age-restricted",
                "age restricted",
                "age verification",
                "confirm you're not a bot",
                "confirm you’re not a bot",
            ).any(normalized::contains)
        }

        private val engineMutex = Mutex()

        @Volatile
        private var instance: YtDlpEngine? = null

        fun get(context: Context): YtDlpEngine =
            instance ?: synchronized(this) {
                instance ?: YtDlpEngine(context.applicationContext).also { instance = it }
            }
    }
}
