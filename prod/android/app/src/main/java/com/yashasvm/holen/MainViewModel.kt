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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = HolenStore.get(application)
    private val outputStore = OutputStore(application)
    private val engine = YtDlpEngine.get(application)
    private val cookieStore = CookieStore(application)
    private val jobAuthenticationStore = JobAuthenticationStore(application)
    private val analyzer = SourceAnalyzer(engine)
    private val appUpdateManager = AppUpdateManager(application)
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

    /**
     * A short, user-facing status for an interactive metadata request. This is
     * deliberately separate from [busy] so surfaces can explain a wait without
     * exposing extractor diagnostics.
     */
    private val mutableAnalysisPhase = MutableStateFlow<String?>(null)
    val analysisPhase = mutableAnalysisPhase.asStateFlow()

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

    private val mutableCookiesConfigured = MutableStateFlow(false)
    val cookiesConfigured = mutableCookiesConfigured.asStateFlow()

    private val mutableCookieMessage = MutableStateFlow<String?>(null)
    val cookieMessage = mutableCookieMessage.asStateFlow()

    private val mutableFilenameSuffixEnabled = MutableStateFlow(
        preferences.getBoolean(HolenStore.PREF_FILENAME_SUFFIX_ENABLED, true),
    )
    val filenameSuffixEnabled = mutableFilenameSuffixEnabled.asStateFlow()

    private val mutableAppUpdate = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val appUpdate = mutableAppUpdate.asStateFlow()

    private val mutableAppInstallEvents = MutableSharedFlow<File>(extraBufferCapacity = 1)
    val appInstallEvents = mutableAppInstallEvents.asSharedFlow()

    private val mutableQueueEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val queueEvents = mutableQueueEvents.asSharedFlow()

    val bundledEngineVersion: String = engine.bundledVersion

    private var analysisJob: Job? = null
    private var analysisProcessId: String? = null
    private var warmupJob: Job? = null
    private var latestAnalysisRequest = 0L
    private var lastAnalysisMode = AnalysisMode.FULL
    private var lastAnalysisFailed = false
    private var appUpdateCheckJob: Job? = null

    init {
        viewModelScope.launch {
            val folderGranted = withContext(Dispatchers.IO) {
                mutableCookiesConfigured.value = cookieStore.validateExisting()
                outputStore.hasValidTreeGrant()
            }
            mutableFolderGranted.value = folderGranted
            if (mutableOnboardingCompleted.value) warmEngine()
        }
    }

    fun recoverQueue() {
        viewModelScope.launch {
            if (!DownloadService.isRunning) {
                if (withContext(Dispatchers.IO) {
                        outputStore.hasValidTreeGrant() && store.hasRecoverableWork()
                    }
                ) {
                    DownloadService.wake(getApplication())
                }
            }
            outputStore.cleanOrphanStaging()
        }
    }

    fun setUrl(value: String) {
        cancelAnalysis()
        mutableUrl.value = value
        mutableAnalysis.value = null
        mutableError.value = null
        mutableAnalysisPhase.value = null
        lastAnalysisFailed = false
    }

    fun clearUrl() = setUrl("")

    fun receiveIncomingUrl(value: String, mode: AnalysisMode = AnalysisMode.FULL) {
        setUrl(value)
        if (mutableOnboardingCompleted.value) analyze(mode)
    }

    fun setFormat(format: DownloadFormat) {
        mutableSelectedFormat.value = format
    }

    fun analyze(mode: AnalysisMode = AnalysisMode.FULL) {
        val requestedUrl = mutableUrl.value.trim()
        if (requestedUrl.isBlank()) {
            mutableError.value = "Paste a public HTTPS link first."
            return
        }
        cancelAnalysis()
        val request = ++latestAnalysisRequest
        val processId = engine.createAnalysisProcessId()
        analysisProcessId = processId
        lastAnalysisMode = mode
        lastAnalysisFailed = false
        mutableBusy.value = true
        mutableError.value = null
        mutableAnalysis.value = null
        mutableAnalysisPhase.value = when (mode) {
            AnalysisMode.QUICK -> "Reading shared link"
            AnalysisMode.FULL -> "Fetching download options"
        }
        analysisJob = viewModelScope.launch {
            try {
                val result = analyzer.analyze(requestedUrl, mode, processId)
                if (request == latestAnalysisRequest) {
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
                    mutableAnalysisPhase.value = null
                    lastAnalysisFailed = false
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (request == latestAnalysisRequest) {
                    mutableError.value = friendlyFailure(error)
                    mutableAnalysisPhase.value = null
                    lastAnalysisFailed = true
                }
            } finally {
                if (request == latestAnalysisRequest) {
                    mutableBusy.value = false
                    analysisJob = null
                    analysisProcessId = null
                }
            }
        }
    }

    fun retryAnalysis() = analyze(lastAnalysisMode)

    fun stopAnalysis() {
        cancelAnalysis()
        if (mutableUrl.value.isNotBlank()) {
            mutableError.value = "Analysis cancelled. Try again when you are ready."
        }
    }

    /** Cancels only the in-flight preview request; queue downloads continue untouched. */
    fun cancelAnalysis() {
        if (analysisJob?.isActive == true) {
            ++latestAnalysisRequest
            analysisJob?.cancel()
            analysisProcessId?.let(engine::cancelAnalysis)
        }
        analysisJob = null
        analysisProcessId = null
        mutableBusy.value = false
        mutableAnalysisPhase.value = null
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
                withContext(Dispatchers.IO) { jobAuthenticationStore.clear(job.id) }
                check(store.transition(job.id, JobStatus.QUEUED)) {
                    "The download could not be requeued."
                }
                DownloadService.wake(getApplication())
            }.onFailure { mutableError.value = friendlyFailure(it) }
        }
    }

    fun retryWithoutCookies(job: DownloadJob) {
        if (!shouldOfferCookieIsolationRetry(
                sourceKind = job.sourceKind,
                status = job.status,
                errorMessage = job.errorMessage,
                cookiesConfigured = mutableCookiesConfigured.value,
            )
        ) {
            mutableError.value =
                "Retry without cookies is only available for failed public media that may be affected by configured cookies."
            return
        }
        viewModelScope.launch {
            var requeued = false
            try {
                withContext(Dispatchers.IO) {
                    jobAuthenticationStore.set(job.id, JobAuthenticationPolicy.WITHOUT_COOKIES)
                }
                requeued = store.transition(job.id, JobStatus.QUEUED)
                check(requeued) { "The download could not be requeued." }
                DownloadService.wake(getApplication())
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (!requeued) {
                    withContext(Dispatchers.IO) { jobAuthenticationStore.clear(job.id) }
                }
                mutableError.value = friendlyFailure(error)
            }
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
            runCatching {
                store.clearFinished(ids)
                val knownJobIds = store.knownJobIds()
                withContext(Dispatchers.IO) { jobAuthenticationStore.prune(knownJobIds) }
            }.onFailure { mutableError.value = friendlyFailure(it) }
        }
    }

    fun deleteFile(job: DownloadJob) {
        viewModelScope.launch {
            runCatching {
                val uri = job.outputUri?.let(android.net.Uri::parse)
                    ?: error("The saved file location is missing.")
                if (!outputStore.deleteDocument(uri)) error("The file could not be deleted.")
                store.remove(job.id)
                withContext(Dispatchers.IO) { jobAuthenticationStore.clear(job.id) }
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
            warmEngine()
            if (mutableUrl.value.isNotBlank()) analyze()
        }
    }

    private fun warmEngine() {
        if (warmupJob?.isActive == true) return
        warmupJob = viewModelScope.launch {
            runCatching { engine.warmup() }
                .onSuccess { mutableEngineVersion.value = engine.activeVersion }
            warmupJob = null
            if (analysisJob?.isActive != true && !mutableBusy.value) {
                checkAppUpdate()
            }
        }
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

    fun saveCookies(text: String) {
        viewModelScope.launch {
            mutableCookieMessage.value = null
            runCatching { withContext(Dispatchers.IO) { cookieStore.save(text) } }
                .onSuccess {
                    mutableCookiesConfigured.value = true
                    mutableCookieMessage.value = "Cookies saved on this device."
                    if (lastAnalysisFailed && mutableUrl.value.isNotBlank()) {
                        analyze(lastAnalysisMode)
                    }
                }
                .onFailure { error ->
                    mutableCookieMessage.value = when (error.message) {
                        CookieStore.ERROR_INVALID, CookieStore.ERROR_TOO_LARGE -> error.message
                        else -> CookieStore.ERROR_SAVE
                    }
                }
        }
    }

    fun clearCookies() {
        viewModelScope.launch {
            mutableCookieMessage.value = null
            val cleared = withContext(Dispatchers.IO) { cookieStore.clear() }
            if (cleared) {
                mutableCookiesConfigured.value = false
                mutableCookieMessage.value = "Cookies cleared."
            } else {
                mutableCookieMessage.value = "Cookies could not be cleared."
            }
        }
    }

    fun setFilenameSuffixEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(HolenStore.PREF_FILENAME_SUFFIX_ENABLED, enabled) }
        mutableFilenameSuffixEnabled.value = enabled
    }

    /** A maximum of one automatic GitHub request per day; Settings can request a manual check. */
    fun checkAppUpdate(manual: Boolean = false) {
        if (appUpdateCheckJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (!manual && !isAppUpdateCheckDue(preferences.getLong(HolenStore.PREF_APP_UPDATE_LAST_CHECK_AT, 0L), now)) {
            return
        }
        appUpdateCheckJob = viewModelScope.launch {
            if (manual) mutableAppUpdate.value = AppUpdateState.Checking
            preferences.edit { putLong(HolenStore.PREF_APP_UPDATE_LAST_CHECK_AT, now) }
            runCatching { appUpdateManager.latestRelease() }
                .onSuccess { release ->
                    val dismissed = preferences.getString(HolenStore.PREF_APP_UPDATE_DISMISSED_TAG, null)
                    mutableAppUpdate.value = if (release != null && release.tag != dismissed) {
                        AppUpdateState.Available(release)
                    } else {
                        AppUpdateState.Idle
                    }
                }
                .onFailure { error ->
                    mutableAppUpdate.value = if (manual) {
                        AppUpdateState.Error(friendlyAppUpdateFailure(error))
                    } else {
                        AppUpdateState.Idle
                    }
                }
            appUpdateCheckJob = null
        }
    }

    fun dismissAppUpdate() {
        val available = mutableAppUpdate.value as? AppUpdateState.Available ?: return
        preferences.edit { putString(HolenStore.PREF_APP_UPDATE_DISMISSED_TAG, available.release.tag) }
        mutableAppUpdate.value = AppUpdateState.Idle
    }

    fun downloadAppUpdate() {
        val available = mutableAppUpdate.value as? AppUpdateState.Available ?: return
        val release = available.release
        viewModelScope.launch {
            runCatching {
                appUpdateManager.download(release) { downloaded, total ->
                    mutableAppUpdate.value = AppUpdateState.Downloading(release, downloaded, total)
                }
            }.onSuccess { apk ->
                mutableAppUpdate.value = AppUpdateState.Ready(release, apk)
                mutableAppInstallEvents.tryEmit(apk)
            }.onFailure { error ->
                mutableAppUpdate.value = AppUpdateState.Error(friendlyAppUpdateFailure(error))
            }
        }
    }

    fun clearAppUpdateMessage() {
        if (mutableAppUpdate.value is AppUpdateState.Error) mutableAppUpdate.value = AppUpdateState.Idle
    }

    fun installDownloadedAppUpdate() {
        val ready = mutableAppUpdate.value as? AppUpdateState.Ready ?: return
        mutableAppInstallEvents.tryEmit(ready.apk)
    }

    private fun isAppUpdateCheckDue(lastCheckAt: Long, now: Long): Boolean =
        lastCheckAt <= 0L || now - lastCheckAt >= APP_UPDATE_CHECK_INTERVAL_MS

    private fun friendlyAppUpdateFailure(error: Throwable): String = when {
        error.message?.contains("signing key", ignoreCase = true) == true ->
            "This release uses a different signing key, so Android cannot update this installation."
        error.message?.contains("not newer", ignoreCase = true) == true ->
            "The release is not newer than this installation."
        else -> "Could not prepare the app update. Check your connection and try again."
    }

    override fun onCleared() {
        cancelAnalysis()
    }

    private companion object {
        const val APP_UPDATE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
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