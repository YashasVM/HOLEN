package com.yashasvm.holen

import android.content.Context
import androidx.core.content.edit
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

/** Metadata work is intentionally split from more expensive format inspection. */
enum class AnalysisMode { QUICK, FULL }

/**
 * Coroutine-friendly read/write gate. Normal engine operations are concurrent readers, while
 * update/reset are exclusive writers. Once maintenance is requested, new work waits so the
 * writer cannot be starved by a stream of preview requests.
 */
internal class EngineOperationGate {
    private val stateMutex = Mutex()
    private val maintenanceMutex = Mutex()
    private var activeOperations = 0
    private var maintenanceRequested = false
    private var operationsDrained: CompletableDeferred<Unit>? = null
    private var maintenanceFinished: CompletableDeferred<Unit>? = null

    suspend fun <T> withOperation(block: suspend () -> T): T {
        while (true) {
            val waitForMaintenance = stateMutex.withLock {
                if (!maintenanceRequested) {
                    activeOperations++
                    null
                } else {
                    checkNotNull(maintenanceFinished)
                }
            }
            if (waitForMaintenance == null) break
            waitForMaintenance.await()
        }
        return try {
            block()
        } finally {
            stateMutex.withLock {
                check(activeOperations > 0)
                activeOperations--
                if (activeOperations == 0) {
                    operationsDrained?.complete(Unit)
                    operationsDrained = null
                }
            }
        }
    }

    suspend fun <T> withMaintenance(block: suspend () -> T): T = maintenanceMutex.withLock {
        lateinit var finished: CompletableDeferred<Unit>
        val waitForOperations = stateMutex.withLock {
            maintenanceRequested = true
            finished = CompletableDeferred()
            maintenanceFinished = finished
            if (activeOperations == 0) {
                null
            } else {
                CompletableDeferred<Unit>().also { operationsDrained = it }
            }
        }
        try {
            waitForOperations?.await()
            block()
        } finally {
            stateMutex.withLock {
                maintenanceRequested = false
                operationsDrained = null
                maintenanceFinished = null
                finished.complete(Unit)
            }
        }
    }
}

class YtDlpEngine private constructor(private val context: Context) {
    private val initMutex = Mutex()
    private val cookieStore = CookieStore(context)
    private val preferences = context.getSharedPreferences(
        HolenStore.PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val analysisCache = AnalysisCache()
    private val analysisProcessSequence = AtomicLong()
    private val activeAnalysisIds = ConcurrentHashMap.newKeySet<String>()
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val operationGate = EngineOperationGate()

    @Volatile
    private var initialized = false

    @Volatile
    private var ffmpegInitialized = false

    val bundledVersion: String = "youtubedl-android $WRAPPER_VERSION"

    val activeVersion: String
        get() = preferences.getString(HolenStore.PREF_ENGINE_VERSION, null)
            ?: "Bundled (loads on first media analysis)"

    /**
     * Preview requests get their own wrapper process ID, so they need not wait for a download.
     * The wrapper uses process IDs for targeted cancellation; initialization and runtime updates
     * are still synchronized below.
     */
    suspend fun analyze(
        url: String,
        mode: AnalysisMode = AnalysisMode.FULL,
        processId: String = createAnalysisProcessId(),
    ): SourceAnalysis = withContext(Dispatchers.IO) {
        require(processId.startsWith(ANALYSIS_PROCESS_PREFIX)) { "Invalid analysis process ID." }
        activeAnalysisIds += processId
        try {
            operationGate.withOperation {
                ensureInitialized(needsFfmpeg = false)
                val cacheKey = AnalysisCache.Key(url, mode, cookieStore.cacheKey(), activeVersion)
                analysisCache.get(cacheKey)?.let { return@withOperation it }
                val timedOut = AtomicBoolean(false)
                val watchdog = if (mode == AnalysisMode.QUICK) {
                    watchdogScope.launch {
                        delay(QUICK_ANALYSIS_TIMEOUT_MS)
                        timedOut.set(true)
                        YoutubeDL.destroyProcessById(processId)
                    }
                } else {
                    null
                }
                try {
                    val request = YoutubeDLRequest(url).apply {
                        addOption("--ignore-config")
                        addCommands(cookieStore.cookieArguments())
                        addOption("--dump-single-json")
                        addOption("--flat-playlist")
                        addOption("--playlist-end", playlistPreviewLimit(mode))
                        addOption("--skip-download")
                        addOption("--no-warnings")
                    }
                    val response = YoutubeDL.execute(request, processId, null)
                    currentCoroutineContext().ensureActive()
                    if (timedOut.get()) throw IOException(METADATA_TIMEOUT_MESSAGE)
                    val json = response.out.toJsonObject()
                    val entries = json.optJSONArray("entries")
                    val analysis = if (entries != null) {
                        json.toPlaylist(url, entries, playlistPreviewLimit(mode))
                    } else {
                        json.toMedia(url, includeEstimates = mode == AnalysisMode.FULL)
                    }
                    analysisCache.put(cacheKey, analysis, mode)
                    analysis
                } catch (error: Throwable) {
                    if (timedOut.get()) throw IOException(METADATA_TIMEOUT_MESSAGE, error)
                    currentCoroutineContext().ensureActive()
                    throw error
                } finally {
                    watchdog?.cancel()
                }
            }
        } catch (error: CancellationException) {
            cancelAnalysis(processId)
            throw error
        } finally {
            activeAnalysisIds -= processId
        }
    }

    /** Prepares the Python/yt-dlp runtime during app idle time without starting a transfer. */
    suspend fun warmup() = withContext(Dispatchers.IO) {
        operationGate.withOperation { ensureInitialized(needsFfmpeg = false) }
        // A stable update check is intentionally rate-limited and runs only from the
        // non-interactive warm-up path. withMaintenance waits for any active transfer
        // instead of terminating it, so an update can never interrupt a download.
        if (isEngineCheckDue(preferences.getLong(HolenStore.PREF_ENGINE_LAST_CHECK_AT, 0L))) {
            runCatching { updateStable() }
        }
    }

    suspend fun download(
        job: DownloadJob,
        directory: File,
        isCancelled: () -> Boolean = { false },
        onProgress: (TransferProgress) -> Unit,
    ): StagedDownload = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            operationGate.withOperation {
                validatePublicHttpsUrl(job.sourceUrl)
                ensureInitialized(needsFfmpeg = true)
                if (isCancelled()) throw CancellationException("Download cancelled")
                directory.mkdirs()
                val existingFiles = completedFiles(directory).mapTo(mutableSetOf()) { it.canonicalPath }
                val outputTemplate = outputTemplateFor(
                    directory,
                    preferences.getBoolean(HolenStore.PREF_FILENAME_SUFFIX_ENABLED, true),
                )
                val request = YoutubeDLRequest(job.sourceUrl).apply {
                    addOption("--ignore-config")
                    addCommands(cookieStore.cookieArguments())
                    addCommands(downloadArguments(job.format))
                    addCommands(
                        listOf(
                            "--continue",
                            // Keep a readable title while yt-dlp still strips characters
                            // which are unsafe for the destination document provider.
                            "--windows-filenames",
                            "--no-overwrites",
                            "--embed-metadata",
                            "--concurrent-fragments", "2",
                            "--retries", "3",
                            "--fragment-retries", "3",
                            "--socket-timeout", "20",
                            // --print normally makes yt-dlp quiet. Request progress explicitly
                            // and use a machine-readable template so the wrapper callback does
                            // not depend on yt-dlp's human-facing wording.
                            "--progress",
                            "--progress-template",
                            "download:$PROGRESS_MARKER %(progress._percent_str)s|%(progress.downloaded_bytes)s|%(progress.total_bytes)s|%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.eta)s",
                            "--newline",
                            "--no-playlist",
                            "--output", outputTemplate,
                            "--print", "after_move:filepath",
                        ),
                    )
                }
                var lastUpdate = 0L
                var lastProgress: TransferProgress? = null
                // yt-dlp writes progress to stderr. The wrapper only delivers its
                // stdout stream to the callback, so merge stderr before launching
                // the process or no live progress ever reaches the app.
                val response = YoutubeDL.execute(request, job.id, true) { wrapperPercent, wrapperEta, line ->
                    if (isCancelled()) {
                        YoutubeDL.destroyProcessById(job.id)
                    } else {
                        val now = System.currentTimeMillis()
                        val progress = transferProgressFromCallback(
                            line = line,
                            wrapperPercent = wrapperPercent,
                            wrapperEta = wrapperEta,
                            previous = lastProgress,
                        )
                        // Do not allow a noisy non-progress line to consume the one-second
                        // window before the actual progress record arrives.  Real progress is
                        // still capped to four UI/DB writes per second.
                        if (progress != null && (now - lastUpdate >= 250 || progress.percent >= 100)) {
                            if (progress != lastProgress || progress.percent >= 100) {
                                onProgress(progress)
                            }
                            lastProgress = progress
                            lastUpdate = now
                        }
                    }
                }
                if (isCancelled()) throw CancellationException("Download cancelled")
                val completed = completedOutputFrom(response.out, directory)
                    ?: completedFiles(directory)
                        .filter { it.canonicalPath !in existingFiles }
                        .maxByOrNull(File::lastModified)
                    ?: throw IOException("The media engine completed without an output file.")
                StagedDownload(
                    file = completed,
                    fileName = sanitizeFileName(completed.name),
                    mimeType = OutputStore.mimeTypeFor(completed.name),
                )
            }
        }
    }

    fun cancel(processId: String) {
        if (initialized) YoutubeDL.destroyProcessById(processId)
    }

    fun createAnalysisProcessId(): String =
        "$ANALYSIS_PROCESS_PREFIX${analysisProcessSequence.incrementAndGet()}"

    /** Cancels only the metadata request owned by the caller. */
    fun cancelAnalysis(processId: String) {
        if (processId in activeAnalysisIds) cancel(processId)
    }

    suspend fun updateStable(): String = withContext(Dispatchers.IO) {
        operationGate.withMaintenance {
            ensureInitialized(needsFfmpeg = false)
            preferences.edit {
                putLong(HolenStore.PREF_ENGINE_LAST_CHECK_AT, System.currentTimeMillis())
            }
            try {
                YoutubeDL.updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
                val version = validateVersion()
                preferences.edit {
                    putString(HolenStore.PREF_ENGINE_VERSION, version)
                    putLong(HolenStore.PREF_ENGINE_LAST_SUCCESSFUL_UPDATE_AT, System.currentTimeMillis())
                }
                version
            } catch (error: Throwable) {
                resetToBundledLocked()
                throw IOException("Engine update failed. Close and reopen HOLEN to restore the bundled engine.", error)
            }
        }
    }

    suspend fun resetToBundled(): String = withContext(Dispatchers.IO) {
        operationGate.withMaintenance { resetToBundledLocked() }
    }

    private suspend fun resetToBundledLocked(): String =
        initMutex.withLock {
            clearRuntimeLocked()
            preferences.edit { putString(HolenStore.PREF_ENGINE_VERSION, "Bundled (restores after restart)") }
            "Bundled runtime cleared. Close and reopen HOLEN to rebuild it."
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
                } catch (firstError: Throwable) {
                    try {
                        clearRuntimeLocked()
                        YoutubeDL.init(context)
                        preferences.edit {
                            putString(HolenStore.PREF_ENGINE_VERSION, bundledVersion)
                        }
                        initialized = true
                    } catch (fallbackError: Throwable) {
                        throw IOException(
                            "Media engine startup failed. The bundled engine could not be restored.",
                            fallbackError,
                        ).also { it.addSuppressed(firstError) }
                    }
                }
            }
            if (needsFfmpeg && !ffmpegInitialized) {
                try {
                    FFmpeg.init(context)
                    ffmpegInitialized = true
                } catch (error: Throwable) {
                    throw IOException("Media engine startup failed. The media tools could not load.", error)
                }
            }
        }
    }

    /**
     * The wrapper extracts Python, yt-dlp, and FFmpeg into this directory. Clearing only
     * yt-dlp leaves a broken Python/FFmpeg runtime behind, so recovery must remove all of it.
     */
    private fun clearRuntimeLocked() {
        File(context.noBackupFilesDir, YoutubeDL.baseName).deleteRecursively()
        context.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE).edit {
            remove("pythonLibVersion")
            remove("ffmpegLibVersion")
            remove("dlpVersion")
            remove("dlpVersionName")
        }
        initialized = false
        ffmpegInitialized = false
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

    private fun JSONObject.toMedia(
        fallbackUrl: String,
        includeEstimates: Boolean,
    ): SourceAnalysis.Media {
        val formats = optJSONArray("formats") ?: JSONArray()
        val estimates = if (includeEstimates) {
            DownloadFormat.entries
                .filter { it != DownloadFormat.ORIGINAL }
                .associateWith { format -> estimateSize(formats, format) }
        } else {
            emptyMap()
        }
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
        limit: Int,
    ): SourceAnalysis.Playlist {
        val preview = buildList {
            for (index in 0 until minOf(entries.length(), limit)) {
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
        const val QUICK_PLAYLIST_PREVIEW_LIMIT = 3
        const val PLAYLIST_QUEUE_LIMIT = 25
        const val QUICK_ANALYSIS_TIMEOUT_MS = 12_000L
        const val METADATA_TIMEOUT_MESSAGE = "Metadata lookup timed out. Check the link or try again."
        internal const val ENGINE_CHECK_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
        private const val ANALYSIS_PROCESS_PREFIX = "analysis-"

        private fun playlistPreviewLimit(mode: AnalysisMode): Int = when (mode) {
            AnalysisMode.QUICK -> QUICK_PLAYLIST_PREVIEW_LIMIT
            AnalysisMode.FULL -> PLAYLIST_PREVIEW_LIMIT
        }

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

        private fun completedFiles(directory: File): Sequence<File> = directory.walkTopDown()
            .filter { file ->
                file.isFile &&
                    !file.name.endsWith(".part") &&
                    !file.name.endsWith(".ytdl") &&
                    !file.name.endsWith(".temp")
            }

        private fun completedOutputFrom(output: String, directory: File): File? {
            val directoryPath = directory.canonicalFile.toPath()
            return output.lineSequence()
                .map(String::trim)
                .mapNotNull { line -> runCatching { File(line).canonicalFile }.getOrNull() }
                .lastOrNull { file ->
                    file.isFile && file.toPath().startsWith(directoryPath) &&
                        !file.name.endsWith(".part") && !file.name.endsWith(".ytdl")
                }
        }

        internal fun outputTemplateFor(
            directory: File,
            includeHolenSuffix: Boolean = true,
        ): String = File(
            directory,
            if (includeHolenSuffix) {
                "%(title).160B (HOLEN).%(ext)s"
            } else {
                "%(title).180B.%(ext)s"
            },
        ).absolutePath

        internal fun isEngineCheckDue(
            lastCheckAt: Long,
            now: Long = System.currentTimeMillis(),
        ): Boolean = lastCheckAt <= 0L || now - lastCheckAt >= ENGINE_CHECK_INTERVAL_MS

        private val downloadMutex = Mutex()

        @Volatile
        private var instance: YtDlpEngine? = null

        fun get(context: Context): YtDlpEngine =
            instance ?: synchronized(this) {
                instance ?: YtDlpEngine(context.applicationContext).also { instance = it }
            }
    }

    private class AnalysisCache {
        data class Key(
            val url: String,
            val mode: AnalysisMode,
            val cookieState: String,
            val engineVersion: String,
        )

        private data class Value(val analysis: SourceAnalysis, val expiresAt: Long)
        private val values = object : LinkedHashMap<Key, Value>(MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Value>): Boolean =
                size > MAX_ENTRIES
        }

        @Synchronized
        fun get(key: Key): SourceAnalysis? {
            val value = values[key] ?: return null
            if (value.expiresAt > System.currentTimeMillis()) return value.analysis
            values.remove(key)
            return null
        }

        @Synchronized
        fun put(key: Key, analysis: SourceAnalysis, mode: AnalysisMode) {
            val ttl = if (mode == AnalysisMode.QUICK) QUICK_CACHE_TTL_MS else FULL_CACHE_TTL_MS
            values[key] = Value(analysis, System.currentTimeMillis() + ttl)
        }

        private companion object {
            const val MAX_ENTRIES = 32
            const val QUICK_CACHE_TTL_MS = 90_000L
            const val FULL_CACHE_TTL_MS = 45_000L
        }
    }
}
