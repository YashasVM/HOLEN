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

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        store = HolenStore.get(this)
        outputStore = OutputStore(this)
        engine = YtDlpEngine(this)
        createNotificationChannel()
        scope.launch { outputStore.cleanOrphanStaging() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_WAKE_QUEUE) {
            ACTION_CANCEL -> intent?.getStringExtra(EXTRA_JOB_ID)?.let(::cancelJob)
            ACTION_WAKE_QUEUE -> {
                startTransferForeground(notification("Preparing queue", 0, null, null, null))
                scope.launch { processQueue() }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        currentJobId?.let {
            directDownloader.cancel()
            engine.cancel(it)
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        currentJobId?.let { id ->
            timedOutIds += id
            directDownloader.cancel()
            engine.cancel(id)
            scope.launch {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        } ?: stopSelf(startId)
    }

    private fun cancelJob(id: String) {
        cancelledIds += id
        if (currentJobId == id) {
            directDownloader.cancel()
            engine.cancel(id)
        }
        scope.launch {
            store.get(id)?.let { job ->
                if (job.status == JobStatus.QUEUED ||
                    job.status == JobStatus.RUNNING ||
                    job.status == JobStatus.FINALIZING
                ) {
                    store.transition(id, JobStatus.CANCELLED)
                }
            }
            if (currentJobId != id) {
                outputStore.clearStaging(id)
                cancelledIds -= id
            }
        }
    }

    private suspend fun processQueue() = processorMutex.withLock {
        if (!recovered) {
            store.requeueInterrupted()
            recovered = true
        }
        while (true) {
            val job = store.nextQueued() ?: break
            currentJobId = job.id
            cancelledIds -= job.id
            timedOutIds -= job.id
            try {
                store.transition(job.id, JobStatus.RUNNING)
                updateNotification(job, TransferProgress(0, null, null, null, null))
                val directory = outputStore.stagingDirectory(job.id)
                val staged = if (job.sourceKind == SourceKind.DIRECT_FILE) {
                    directDownloader.download(job, directory) { progress ->
                        if (job.id !in cancelledIds) {
                            store.updateProgress(job.id, progress)
                            updateNotification(job, progress)
                        }
                    }
                } else {
                    engine.download(job, directory) { progress ->
                        if (job.id !in cancelledIds) {
                            scope.launch { store.updateProgress(job.id, progress) }
                            updateNotification(job, progress)
                        }
                    }
                }
                check(job.id !in cancelledIds) { "Download cancelled" }
                store.transition(job.id, JobStatus.FINALIZING)
                updateNotification(
                    job.copy(status = JobStatus.FINALIZING),
                    TransferProgress(99, staged.file.length(), staged.file.length(), null, null),
                )
                val published = outputStore.publish(staged) { job.id in cancelledIds }
                check(job.id !in cancelledIds) { "Download cancelled" }
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
            } catch (error: Throwable) {
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
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

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
        val detail = buildList {
            speed?.takeIf { it > 0 }?.let { add("${formatBytes(it)}/s") }
            eta?.takeIf { it >= 0 }?.let { add("${formatDuration(it)} left") }
        }.joinToString(" · ").ifBlank { if (progress > 0) "$progress%" else "Starting…" }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(detail)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress.coerceIn(0, 100), progress <= 0)
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

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val ACTION_WAKE_QUEUE = "com.yashasvm.holen.action.WAKE_QUEUE"
        const val ACTION_CANCEL = "com.yashasvm.holen.action.CANCEL"
        const val EXTRA_JOB_ID = "job_id"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 410

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
