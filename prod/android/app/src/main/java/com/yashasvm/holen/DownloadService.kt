package com.yashasvm.holen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processorMutex = Mutex()
    private val cancelledIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val timedOutIds = Collections.synchronizedSet(mutableSetOf<String>())
    private lateinit var store: HolenStore
    private lateinit var outputStore: OutputStore
    private lateinit var engine: YtDlpEngine
    private val directDownloader = DirectDownloader()

    @Volatile
    private var currentJobId: String? = null

    @Volatile
    private var recovered = false

    @Volatile
    private var stopping = false

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
        currentJobId?.let {
            directDownloader.cancel()
            engine.cancel(it)
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
        val interruptedId = currentJobId
        interruptedId?.let { id ->
            timedOutIds += id
            directDownloader.cancel()
            engine.cancel(id)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        scope.launch {
            interruptedId?.let { id ->
                runCatching { store.transition(id, JobStatus.QUEUED) }
            }
            stopSelf()
        }
    }

    private fun cancelJob(id: String, startId: Int) {
        cancelledIds += id
        if (currentJobId == id) {
            directDownloader.cancel()
            engine.cancel(id)
        }
        scope.launch {
            store.cancelActive(id)
            processorMutex.withLock {
                if (currentJobId != id) {
                    outputStore.clearStaging(id)
                    cancelledIds -= id
                }
                if (currentJobId == null) {
                    if (stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
        }
    }

    private suspend fun processQueue(startId: Int) = processorMutex.withLock {
        if (!recovered) {
            store.requeueInterrupted()
            recovered = true
        }
        while (!stopping) {
            val job = store.claimNextQueued() ?: break
            currentJobId = job.id
            var publishedOutput: OutputStore.PublishedFile? = null
            try {
                updateNotification(job, TransferProgress(0, null, null, null, null))
                val directory = outputStore.stagingDirectory(job.id)
                val staged = if (job.sourceKind == SourceKind.DIRECT_FILE) {
                    directDownloader.download(job, directory, { shouldAbort(job.id) }) { progress ->
                        if (!shouldAbort(job.id)) {
                            store.updateProgress(job.id, progress)
                            updateNotification(job, progress)
                        }
                    }
                } else {
                    engine.download(job, directory, { shouldAbort(job.id) }) { progress ->
                        if (!shouldAbort(job.id)) {
                            scope.launch { store.updateProgress(job.id, progress) }
                            updateNotification(job, progress)
                        }
                    }
                }
                check(!shouldAbort(job.id)) { "Download cancelled" }
                store.transition(job.id, JobStatus.FINALIZING)
                updateNotification(
                    job.copy(status = JobStatus.FINALIZING),
                    TransferProgress(99, staged.file.length(), staged.file.length(), null, null),
                )
                val published = outputStore.publish(staged) { shouldAbort(job.id) }
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
                    outputStore.deleteDocument(published.uri)
                    error("Download cancelled")
                }
                runCatching { showCompletionNotification(job, published) }
                publishedOutput = null
            } catch (error: Throwable) {
                publishedOutput?.let { runCatching { outputStore.deleteDocument(it.uri) } }
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
                cancelledIds -= job.id
                timedOutIds -= job.id
                currentJobId = null
            }
        }
        if (stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun shouldAbort(jobId: String): Boolean =
        stopping || jobId in cancelledIds || jobId in timedOutIds

    private fun updateNotification(job: DownloadJob, progress: TransferProgress) {
        startTransferForeground(
            notification(
                title = job.title,
                progress = progress.percent,
                speed = progress.speedBytesPerSecond,
                eta = progress.etaSeconds,
                jobId = job.id,
            ),
        )
    }

    private fun notification(
        title: String,
        progress: Int,
        speed: Long?,
        eta: Long?,
        jobId: String?,
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
            .setProgress(100, progress.coerceIn(0, 100), false)
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
