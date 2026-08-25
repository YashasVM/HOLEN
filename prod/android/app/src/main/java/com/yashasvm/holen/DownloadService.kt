package com.yashasvm.holen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processorMutex = Mutex()
    private val cancelledIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val timedOutIds = Collections.synchronizedSet(mutableSetOf<String>())
    private lateinit var store: HolenStore
    private lateinit var outputStore: OutputStore
    private lateinit var engine: YtDlpEngine
    private val directDownloaders = ConcurrentHashMap<String, DirectDownloader>()
    private val activeJobIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val notificationUpdateLock = Any()


    @Volatile
    private var recovered = false

    @Volatile
    private var stopping = false

    private var blockedPublicationIds: Set<String> = emptySet()
    private var lastProgressNotificationJobId: String? = null
    private var lastProgressNotificationAt = 0L

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        store = HolenStore.get(this)
        outputStore = OutputStore(this)
        engine = YtDlpEngine.get(this)
        createNotificationChannel()
        scope.launch { outputStore.cleanOrphanStaging() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_WAKE_QUEUE) {
            ACTION_CANCEL -> intent?.getStringExtra(EXTRA_JOB_ID)?.let { cancelJob(it, startId) }
            ACTION_WAKE_QUEUE -> {
                if (stopping) return START_NOT_STICKY
                startTransferForeground(notification("Preparing queue", 0, null, null, null))
                scope.launch { processQueue(startId) }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopping = true
        isRunning = false
        activeJobIds.toList().forEach { id ->
            directDownloaders[id]?.cancel()
            engine.cancel(id)
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int) {
        handleTimeout()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        handleTimeout()
    }

    private fun handleTimeout() {
        if (stopping) return
        stopping = true
        val interruptedIds = activeJobIds.toList()
        interruptedIds.forEach { id ->
            timedOutIds += id
            directDownloaders[id]?.cancel()
            engine.cancel(id)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        scope.launch {
            interruptedIds.forEach { id ->
                runCatching { store.transition(id, JobStatus.QUEUED) }
            }
            stopSelf()
        }
    }

    private fun cancelJob(id: String, startId: Int) {
        cancelledIds += id
        if (id in activeJobIds) {
            directDownloaders[id]?.cancel()
            engine.cancel(id)
        }
        scope.launch {
            store.cancelActive(id)
            processorMutex.withLock {
                if (id !in activeJobIds) {
                    outputStore.clearStaging(id)
                    cancelledIds -= id
                }
            }
        }
    }

    private suspend fun processQueue(startId: Int) = processorMutex.withLock {
        if (!recovered) {
            blockedPublicationIds = recoverPublishedFiles()
            store.requeueInterrupted(blockedPublicationIds)
            recovered = true
        }
        coroutineScope {
            List(YtDlpEngine.MAX_ACTIVE_DOWNLOADS) {
                launch { processJobs() }
            }.joinAll()
        }
        if (stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private suspend fun processJobs() {
        while (!stopping) {
            val job = store.claimNextQueued(blockedPublicationIds) ?: return
            activeJobIds += job.id
            val directDownloader = if (job.sourceKind == SourceKind.DIRECT_FILE) {
                DirectDownloader().also { directDownloaders[job.id] = it }
            } else {
                null
            }
            try {
                processJob(job, directDownloader)
            } finally {
                directDownloaders.remove(job.id)
                activeJobIds -= job.id
            }
        }
    }

    private suspend fun processJob(job: DownloadJob, directDownloader: DirectDownloader?) {
        var publishedOutput: OutputStore.PublishedFile? = null
        val progressWriter = ProgressWriter(store, job.id)
        try {
            updateNotification(job, TransferProgress(0, null, null, null, null))
            val directory = outputStore.stagingDirectory(job.id)
            val stagingProgress = StagingProgressSampler(directory, job.totalBytes) { progress ->
                if (!shouldAbort(job.id)) {
                    progressWriter.report(progress)
                    updateNotification(job, progress)
                }
            }.also(StagingProgressSampler::start)
            val staged = try {
                val reportProgress: (TransferProgress) -> Unit = { progress ->
                    stagingProgress.observeExtractor(progress)
                    if (!shouldAbort(job.id)) {
                        progressWriter.report(progress)
                        updateNotification(job, progress)
                    }
                }
                if (directDownloader != null) {
                    directDownloader.download(job, directory, { shouldAbort(job.id) }, reportProgress)
                } else {
                    engine.download(job, directory, { shouldAbort(job.id) }, reportProgress)
                }
            } finally {
                stagingProgress.stop()
            }
            progressWriter.finish()
            check(!shouldAbort(job.id)) { "Download cancelled" }
            store.transition(job.id, JobStatus.FINALIZING)
            updateNotification(
                job.copy(status = JobStatus.FINALIZING),
                TransferProgress(99, staged.file.length(), staged.file.length(), null, null),
                force = true,
            )
            val published = outputStore.publish(job.id, staged) { shouldAbort(job.id) }
            publishedOutput = published
            check(!shouldAbort(job.id)) { "Download cancelled" }
            val completionRows = store.complete(
                job.id,
                published.uri.toString(),
                published.fileName,
                published.mimeType,
                published.byteCount,
            )
            if (completionRows == 0) {
                val deleted = runCatching { outputStore.deleteDocument(published.uri) }
                    .getOrDefault(false)
                if (deleted) outputStore.confirmPublication(job.id)
                error("Download cancelled")
            }
            outputStore.confirmPublication(job.id)
            runCatching { showCompletionNotification(job, published) }
            publishedOutput = null
        } catch (error: Throwable) {
            publishedOutput?.let {
                val deleted = runCatching { outputStore.deleteDocument(it.uri) }
                    .getOrDefault(false)
                if (deleted) outputStore.confirmPublication(job.id)
            }
            if (job.id in timedOutIds) {
                runCatching { store.transition(job.id, JobStatus.QUEUED) }
            } else if (job.id in cancelledIds) {
                outputStore.clearStaging(job.id)
                runCatching { store.transition(job.id, JobStatus.CANCELLED) }
            } else {
                runCatching {
                    store.transition(job.id, JobStatus.FAILED, friendlyFailure(error))
                }
            }
        } finally {
            progressWriter.finish()
            cancelledIds -= job.id
            timedOutIds -= job.id
        }
    }

    /** Reconciles SAF files copied just before an unexpected process death. */
    private suspend fun recoverPublishedFiles(): Set<String> {
        val blockedFinalizingIds = mutableSetOf<String>()
        for (jobId in outputStore.pendingPublicationIds()) {
            when (val recovery = outputStore.recoverPublication(jobId)) {
                is OutputStore.PublicationRecovery.Complete -> {
                    val published = recovery.file
                    val completed = store.complete(
                        jobId,
                        published.uri.toString(),
                        published.fileName,
                        published.mimeType,
                        published.byteCount,
                    )
                    val status = store.get(jobId)?.status
                    if (completed > 0 || status == JobStatus.COMPLETED) {
                        outputStore.confirmPublication(jobId)
                    } else {
                        val deleted = runCatching { outputStore.deleteDocument(published.uri) }
                            .getOrDefault(false)
                        if (deleted) outputStore.confirmPublication(jobId)
                        else blockedFinalizingIds += jobId
                    }
                }
                is OutputStore.PublicationRecovery.Partial -> {
                    val deleted = runCatching { outputStore.deleteDocument(recovery.uri) }
                        .getOrDefault(false)
                    if (deleted) outputStore.confirmPublication(jobId)
                    else blockedFinalizingIds += jobId
                }
                OutputStore.PublicationRecovery.NotCreated,
                OutputStore.PublicationRecovery.NoJournal,
                -> outputStore.confirmPublication(jobId)
                OutputStore.PublicationRecovery.Unavailable -> blockedFinalizingIds += jobId
            }
        }
        return blockedFinalizingIds
    }

    private fun updateNotification(
        job: DownloadJob,
        progress: TransferProgress,
        force: Boolean = false,
    ) {
        val now = SystemClock.elapsedRealtime()
        synchronized(notificationUpdateLock) {
            if (
                !force &&
                lastProgressNotificationJobId == job.id &&
                now - lastProgressNotificationAt < NOTIFICATION_UPDATE_INTERVAL_MS
            ) {
                return
            }
            lastProgressNotificationJobId = job.id
            lastProgressNotificationAt = now
        }
        startTransferForeground(
            notification(
                title = job.title,
                progress = progress.percent,
                speed = progress.speedBytesPerSecond,
                eta = progress.etaSeconds,
                jobId = job.id,
                totalBytes = progress.totalBytes,
            ),
        )
    }

    private fun notification(
        title: String,
        progress: Int,
        speed: Long?,
        eta: Long?,
        jobId: String?,
        totalBytes: Long? = null,
    ): Notification {
        val cancelIntent = jobId?.let {
            Intent(this, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_JOB_ID, it)
            }
        }
        val cancelPendingIntent = cancelIntent?.let {
            PendingIntent.getService(
                this,
                jobId.hashCode(),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val detail = buildList {
            speed?.takeIf { it > 0 }?.let { add("${formatBytes(it)}/s") }
            eta?.takeIf { it >= 0 }?.let { add("${formatDuration(it)} left") }
        }.joinToString(" · ").ifBlank { if (progress > 0) "$progress%" else "Starting…" }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(openPendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(
                if (totalBytes == null) 0 else 100,
                progress.coerceIn(0, 100),
                totalBytes == null,
            )
            .apply {
                if (cancelPendingIntent != null) {
                    addAction(Notification.Action.Builder(null, "Cancel", cancelPendingIntent).build())
                }
            }
            .build()
    }

    private fun startTransferForeground(notification: Notification) {
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun showCompletionNotification(
        job: DownloadJob,
        published: OutputStore.PublishedFile,
    ) {
        val openFileIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(published.uri, published.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val openFilePendingIntent = PendingIntent.getActivity(
            this,
            job.id.hashCode(),
            openFileIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val completed = Notification.Builder(this, COMPLETION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.download_complete_title))
            .setContentText(published.fileName)
            .setStyle(
                Notification.BigTextStyle().bigText(
                    getString(R.string.download_complete_detail, published.fileName),
                ),
            )
            .setContentIntent(openFilePendingIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        getSystemService(NotificationManager::class.java).notify(
            COMPLETION_NOTIFICATION_BASE + Math.floorMod(job.id.hashCode(), 100_000),
            completed,
        )
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                COMPLETION_CHANNEL_ID,
                getString(R.string.notification_completion_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.notification_completion_channel_description)
                setShowBadge(true)
            },
        )
    }

    companion object {
        const val ACTION_WAKE_QUEUE = "com.yashasvm.holen.action.WAKE_QUEUE"
        const val ACTION_CANCEL = "com.yashasvm.holen.action.CANCEL"
        const val EXTRA_JOB_ID = "job_id"
        private const val CHANNEL_ID = "downloads"
        private const val COMPLETION_CHANNEL_ID = "download_completions"
        private const val NOTIFICATION_ID = 410
        private const val COMPLETION_NOTIFICATION_BASE = 10_000
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 1_000L

        @Volatile
        internal var isRunning = false

        fun wake(context: android.content.Context) {
            context.startForegroundService(
                Intent(context, DownloadService::class.java).setAction(ACTION_WAKE_QUEUE),
            )
        }

        fun cancel(context: android.content.Context, jobId: String) {
            context.startService(
                Intent(context, DownloadService::class.java)
                    .setAction(ACTION_CANCEL)
                    .putExtra(EXTRA_JOB_ID, jobId),
            )
        }

        private fun formatBytes(value: Long): String {
            val units = arrayOf("B", "KB", "MB", "GB")
            var amount = value.toDouble()
            var unit = 0
            while (amount >= 1_000 && unit < units.lastIndex) {
                amount /= 1_000
                unit++
            }
            return if (unit == 0) "${amount.toLong()} ${units[unit]}"
            else "%.1f %s".format(amount, units[unit])
        }

        private fun formatDuration(seconds: Long): String =
            if (seconds >= 3600) {
                "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
            } else {
                "%d:%02d".format(seconds / 60, seconds % 60)
            }
    }
}

/**
 * Coalesces noisy extractor callbacks into ordered database updates. Unlike a
 * fire-and-forget coroutine per callback, [finish] is a real barrier before a
 * job starts finalization.
 */
private class ProgressWriter(
    private val store: HolenStore,
    private val jobId: String,
) {
    private val updates = Channel<TransferProgress>(Channel.CONFLATED)
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val worker = workerScope.launch {
        for (progress in updates) {
            runCatching { store.updateProgress(jobId, progress) }
        }
    }

    private var latest: TransferProgress? = null

    @Synchronized
    fun report(progress: TransferProgress) {
        val merged = mergeTransferProgress(latest, progress)
        if (merged != latest) updates.trySend(merged)
        latest = merged
    }

    suspend fun finish() {
        updates.close()
        worker.join()
        workerScope.cancel()
    }
}

/**
 * Samples the extractor's private staging area while a transfer is active.
 * This remains useful when an extractor omits progress lines, while the normal
 * yt-dlp callback remains the authoritative source for exact totals and ETA.
 */
private class StagingProgressSampler(
    private val directory: java.io.File,
    initialTotalBytes: Long?,
    private val onProgress: (TransferProgress) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: kotlinx.coroutines.Job? = null

    @Volatile
    private var knownTotalBytes: Long? = initialTotalBytes

    @Volatile
    private var floorPercent = 0

    @Volatile
    private var lastExtractorProgressAt = 0L

    private var previousBytes = 0L
    private var previousAt = 0L

    fun start() {
        worker = scope.launch {
            while (true) {
                delay(SAMPLE_INTERVAL_MS)
                sample()
            }
        }
    }

    fun observeExtractor(progress: TransferProgress) {
        progress.totalBytes?.let { knownTotalBytes = it }
        floorPercent = maxOf(floorPercent, progress.percent)
        lastExtractorProgressAt = SystemClock.elapsedRealtime()
    }

    private fun sample() {
        val now = SystemClock.elapsedRealtime()
        val extractorAt = lastExtractorProgressAt
        if (extractorAt > 0L && now - extractorAt < EXTRACTOR_SILENCE_MS) return

        val bytes = directory.takeIf(java.io.File::isDirectory)
            ?.walkTopDown()
            ?.filter { file ->
                file.isFile && !file.name.endsWith(".ytdl") && !file.name.endsWith(".part-Frag")
            }
            ?.sumOf(java.io.File::length)
            ?: return
        if (bytes <= previousBytes) return
        val elapsed = (now - previousAt).takeIf { previousAt > 0L && it > 0L }
        val total = knownTotalBytes
        val measuredPercent = total?.let {
            // A size estimate can be slightly low, but the media process has not
            // completed while a staging file is still growing.
            ((bytes * 100L / it.coerceAtLeast(1L)).toInt()).coerceIn(0, 99)
        } ?: floorPercent
        onProgress(
            TransferProgress(
                percent = maxOf(floorPercent, measuredPercent),
                bytesDownloaded = bytes,
                totalBytes = total,
                speedBytesPerSecond = elapsed?.let { (bytes - previousBytes) * 1_000L / it },
                etaSeconds = null,
            ),
        )
        previousBytes = bytes
        previousAt = now
    }

    suspend fun stop() {
        worker?.cancelAndJoin()
        scope.cancel()
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 500L
        const val EXTRACTOR_SILENCE_MS = 2_000L
    }
}