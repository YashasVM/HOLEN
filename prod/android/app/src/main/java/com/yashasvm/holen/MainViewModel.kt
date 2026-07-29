package com.yashasvm.holen

import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = HolenStore.get(application)
    private val outputStore = OutputStore(application)
    private val engine = YtDlpEngine.get(application)
    private val analyzer = SourceAnalyzer(engine)
    private val preferences = application.getSharedPreferences(
        HolenStore.PREFERENCES_NAME,
        android.content.Context.MODE_PRIVATE,
    )

    val jobs = store.jobs

    private val mutableUrl = MutableStateFlow("")
    val url = mutableUrl.asStateFlow()

    private val mutableAnalysis = MutableStateFlow<SourceAnalysis?>(null)
    val analysis = mutableAnalysis.asStateFlow()

    private val mutableSelectedFormat = MutableStateFlow(DownloadFormat.BEST_MP4)
    val selectedFormat = mutableSelectedFormat.asStateFlow()

    private val mutableSelectedEntries = MutableStateFlow<Set<String>>(emptySet())
    val selectedEntries = mutableSelectedEntries.asStateFlow()

    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()

    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    private val mutableFolderGranted = MutableStateFlow(
        preferences.contains(HolenStore.PREF_DOWNLOAD_TREE),
    )
    val folderGranted = mutableFolderGranted.asStateFlow()

    private val mutableRightsAcknowledged = MutableStateFlow(
        preferences.getBoolean(HolenStore.PREF_RIGHTS_ACKNOWLEDGED, false),
    )
    val rightsAcknowledged = mutableRightsAcknowledged.asStateFlow()

    private val mutableOnboardingCompleted = MutableStateFlow(
        preferences.getBoolean(HolenStore.PREF_ONBOARDING_COMPLETED, false) &&
            preferences.getInt(HolenStore.PREF_ONBOARDING_VERSION, 0) >=
            HolenStore.ONBOARDING_VERSION,
    )
    val onboardingCompleted = mutableOnboardingCompleted.asStateFlow()

    private val mutableEngineVersion = MutableStateFlow(engine.activeVersion)
    val engineVersion = mutableEngineVersion.asStateFlow()

    private val mutableEngineMessage = MutableStateFlow<String?>(null)
    val engineMessage = mutableEngineMessage.asStateFlow()

    private val mutableQueueEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val queueEvents = mutableQueueEvents.asSharedFlow()

    val bundledEngineVersion: String = engine.bundledVersion

    init {
        viewModelScope.launch {
            val folderGranted = withContext(Dispatchers.IO) {
                // Session-file support was removed; clear any credential material left by older builds.
                File(getApplication<Application>().noBackupFilesDir, "auth").deleteRecursively()
                outputStore.hasValidTreeGrant()
            }
            mutableFolderGranted.value = folderGranted
        }
    }

    fun recoverQueue() {
        viewModelScope.launch {
            if (!DownloadService.isRunning) {
                store.requeueInterrupted()
                if (withContext(Dispatchers.IO) {
                        outputStore.hasValidTreeGrant() && store.hasQueued()
                    }
                ) {
                    DownloadService.wake(getApplication())
                }
            }
            outputStore.cleanOrphanStaging()
        }
    }

    fun setUrl(value: String) {
        mutableUrl.value = value
        mutableAnalysis.value = null
        mutableError.value = null
    }

    fun clearUrl() = setUrl("")

    fun receiveIncomingUrl(value: String) {
        setUrl(value)
        if (mutableOnboardingCompleted.value) analyze()
    }

    fun setFormat(format: DownloadFormat) {
        mutableSelectedFormat.value = format
    }

    fun analyze() {
        if (mutableBusy.value) return
        viewModelScope.launch {
            mutableBusy.value = true
            mutableError.value = null
            mutableAnalysis.value = null
            runCatching { analyzer.analyze(mutableUrl.value) }
                .onSuccess { result ->
                    mutableAnalysis.value = result
                    mutableSelectedFormat.value = when (result) {
                        is SourceAnalysis.DirectFile -> DownloadFormat.ORIGINAL
                        else -> DownloadFormat.BEST_MP4
                    }
                    mutableSelectedEntries.value = if (result is SourceAnalysis.Playlist) {
                        result.entries.take(YtDlpEngine.PLAYLIST_QUEUE_LIMIT).map { it.id }.toSet()
                    } else {
                        emptySet()
                    }
                    mutableEngineVersion.value = engine.activeVersion
                }
                .onFailure { mutableError.value = friendlyFailure(it) }
            mutableBusy.value = false
        }
    }

    fun toggleEntry(id: String) {
        val selected = mutableSelectedEntries.value.toMutableSet()
        if (id in selected) {
            selected -= id
        } else {
            selected += id
        }
        mutableSelectedEntries.value = selected
    }

    fun toggleAllEntries() {
        val playlist = mutableAnalysis.value as? SourceAnalysis.Playlist ?: return
        val selectable = playlist.entries.map { it.id }.toSet()
        mutableSelectedEntries.value =
            if (selectable.all { it in mutableSelectedEntries.value }) emptySet() else selectable
    }

    fun queue() {
        val preview = mutableAnalysis.value ?: return
        if (preview is SourceAnalysis.Playlist &&
            mutableSelectedEntries.value.size > YtDlpEngine.PLAYLIST_QUEUE_LIMIT
        ) {
            mutableError.value = "Select no more than 25 playlist items for one queue action."
            return
        }
        if (!mutableFolderGranted.value) {
            mutableFolderGranted.value = false
            mutableError.value = "Choose a download folder first."
            return
        }
        if (!mutableRightsAcknowledged.value) {
            mutableError.value = "Acknowledge the download-rights notice first."
            return
        }
        viewModelScope.launch {
            if (!withContext(Dispatchers.IO) { outputStore.hasValidTreeGrant() }) {
                mutableFolderGranted.value = false
                mutableError.value = "Choose a download folder first."
                return@launch
            }
            mutableBusy.value = true
            val now = System.currentTimeMillis()
            val jobs = when (preview) {
                is SourceAnalysis.DirectFile -> listOf(
                    preview.toJob(
                        id = UUID.randomUUID().toString(),
                        createdAt = now,
                    ),
                )
                is SourceAnalysis.Media -> listOf(
                    preview.toJob(
                        id = UUID.randomUUID().toString(),
                        format = mutableSelectedFormat.value,
                        createdAt = now,
                    ),
                )
                is SourceAnalysis.Playlist -> preview.entries
                    .filter { it.id in mutableSelectedEntries.value }
                    .mapIndexed { index, entry ->
                        entry.toJob(
                            id = UUID.randomUUID().toString(),
                            format = mutableSelectedFormat.value,
                            createdAt = now + index,
                        )
                    }
            }
            if (jobs.isEmpty()) {
                mutableError.value = "Select at least one playlist item."
            } else {
                runCatching {
                    store.insert(jobs)
                    DownloadService.wake(getApplication())
                }.onSuccess {
                    mutableAnalysis.value = null
                    mutableUrl.value = ""
                    mutableSelectedEntries.value = emptySet()
                    mutableError.value = null
                    mutableQueueEvents.tryEmit(Unit)
                }.onFailure {
                    mutableError.value = friendlyFailure(it)
                }
            }
            mutableBusy.value = false
        }
    }

    fun retry(job: DownloadJob) {
        viewModelScope.launch {
            runCatching {
                store.transition(job.id, JobStatus.QUEUED)
                DownloadService.wake(getApplication())
            }.onFailure { mutableError.value = friendlyFailure(it) }
        }
    }

    fun resumeQueue() {
        if (jobs.value.none { it.status == JobStatus.QUEUED }) return
        DownloadService.wake(getApplication())
    }

    fun cancel(job: DownloadJob) {
        if (job.status == JobStatus.QUEUED) {
            viewModelScope.launch {
                if (store.cancelIfQueued(job.id)) {
                    outputStore.clearStaging(job.id)
                } else {
                    DownloadService.cancel(getApplication(), job.id)
                }
            }
        } else {
            DownloadService.cancel(getApplication(), job.id)
        }
    }

    fun clearFinished(ids: Set<String>? = null) {
        viewModelScope.launch {
            store.clearFinished(ids)
        }
    }

    fun deleteFile(job: DownloadJob) {
        viewModelScope.launch {
            runCatching {
                val uri = job.outputUri?.let(android.net.Uri::parse)
                    ?: error("The saved file location is missing.")
                if (!outputStore.deleteDocument(uri)) error("The file could not be deleted.")
                store.remove(job.id)
            }.onFailure { mutableError.value = friendlyFailure(it) }
        }
    }

    fun acknowledgeRights() {
        preferences.edit { putBoolean(HolenStore.PREF_RIGHTS_ACKNOWLEDGED, true) }
        mutableRightsAcknowledged.value = true
        mutableError.value = null
    }

    fun completeOnboarding() {
        if (!mutableRightsAcknowledged.value) {
            mutableError.value = "Accept the responsible-download agreement to continue."
            return
        }
        if (!mutableFolderGranted.value) {
            mutableFolderGranted.value = false
            mutableError.value = "Choose a download folder to continue."
            return
        }
        viewModelScope.launch {
            if (!withContext(Dispatchers.IO) { outputStore.hasValidTreeGrant() }) {
                mutableFolderGranted.value = false
                mutableError.value = "Choose a download folder to continue."
                return@launch
            }
            preferences.edit {
                putBoolean(HolenStore.PREF_ONBOARDING_COMPLETED, true)
                putInt(HolenStore.PREF_ONBOARDING_VERSION, HolenStore.ONBOARDING_VERSION)
            }
            mutableOnboardingCompleted.value = true
            mutableError.value = null
            if (mutableUrl.value.isNotBlank()) analyze()
        }
    }

    fun restartOnboarding() {
        preferences.edit {
            putBoolean(HolenStore.PREF_ONBOARDING_COMPLETED, false)
            putInt(HolenStore.PREF_ONBOARDING_VERSION, 0)
        }
        mutableOnboardingCompleted.value = false
        mutableError.value = null
    }

    fun refreshFolderGrant() = viewModelScope.launch {
        mutableFolderGranted.value = withContext(Dispatchers.IO) { outputStore.hasValidTreeGrant() }
    }

    fun folderSelectionSucceeded() {
        refreshFolderGrant()
        mutableError.value = null
        viewModelScope.launch {
            if (store.hasQueued()) DownloadService.wake(getApplication())
        }
    }

    fun folderSelectionFailed() {
        refreshFolderGrant()
        mutableError.value = "That folder did not grant persistent read and write access."
    }

    fun updateEngine() {
        if (mutableBusy.value) return
        viewModelScope.launch {
            mutableBusy.value = true
            mutableEngineMessage.value = null
            runCatching { engine.updateStable() }
                .onSuccess {
                    mutableEngineVersion.value = it
                    mutableEngineMessage.value = "Engine updated to $it."
                }
                .onFailure {
                    mutableEngineVersion.value = engine.activeVersion
                    mutableEngineMessage.value = friendlyFailure(it)
                }
            mutableBusy.value = false
        }
    }

    fun resetEngine() {
        if (mutableBusy.value) return
        viewModelScope.launch {
            mutableBusy.value = true
            mutableEngineMessage.value = null
            runCatching { engine.resetToBundled() }
                .onSuccess {
                    mutableEngineVersion.value = it
                    mutableEngineMessage.value = "Engine files cleared. Close and reopen HOLEN to rebuild them."
                }
                .onFailure { mutableEngineMessage.value = friendlyFailure(it) }
            mutableBusy.value = false
        }
    }

    override fun onCleared() {
        engine.cancel("analysis")
    }

    private fun SourceAnalysis.DirectFile.toJob(
        id: String,
        createdAt: Long,
    ) = DownloadJob(
        id = id,
        sourceUrl = sourceUrl,
        sourceKind = SourceKind.DIRECT_FILE,
        format = DownloadFormat.ORIGINAL,
        title = title,
        thumbnailUrl = null,
        status = JobStatus.QUEUED,
        progress = 0,
        bytesDownloaded = 0,
        totalBytes = sizeBytes,
        speedBytesPerSecond = null,
        etaSeconds = null,
        outputUri = null,
        fileName = fileName,
        mimeType = mimeType,
        errorMessage = null,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun SourceAnalysis.Media.toJob(
        id: String,
        format: DownloadFormat,
        createdAt: Long,
    ) = DownloadJob(
        id = id,
        sourceUrl = sourceUrl,
        sourceKind = SourceKind.MEDIA,
        format = format,
        title = title,
        thumbnailUrl = thumbnailUrl,
        status = JobStatus.QUEUED,
        progress = 0,
        bytesDownloaded = null,
        totalBytes = estimatedSizes[format],
        speedBytesPerSecond = null,
        etaSeconds = null,
        outputUri = null,
        fileName = null,
        mimeType = null,
        errorMessage = null,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun PlaylistEntry.toJob(
        id: String,
        format: DownloadFormat,
        createdAt: Long,
    ) = DownloadJob(
        id = id,
        sourceUrl = url,
        sourceKind = SourceKind.MEDIA,
        format = format,
        title = title,
        thumbnailUrl = thumbnailUrl,
        status = JobStatus.QUEUED,
        progress = 0,
        bytesDownloaded = null,
        totalBytes = null,
        speedBytesPerSecond = null,
        etaSeconds = null,
        outputUri = null,
        fileName = null,
        mimeType = null,
        errorMessage = null,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
