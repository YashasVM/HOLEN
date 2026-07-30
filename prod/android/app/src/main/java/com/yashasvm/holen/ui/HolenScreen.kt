package com.yashasvm.holen.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.yashasvm.holen.DownloadFormat
import com.yashasvm.holen.DownloadJob
import com.yashasvm.holen.JobStatus
import com.yashasvm.holen.MainViewModel
import com.yashasvm.holen.SourceAnalysis
import com.yashasvm.holen.YtDlpEngine
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HolenScreen(
    viewModel: MainViewModel,
    onChooseFolder: () -> Unit,
    onQueue: () -> Unit,
    onOpen: (DownloadJob) -> Unit,
    onShare: (DownloadJob) -> Unit,
    onOpenSource: () -> Unit,
) {
    val onboardingCompleted by viewModel.onboardingCompleted.collectAsStateWithLifecycle()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = HolenBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = { CreatorCredit() },
    ) { contentPadding ->
        AnimatedContent(
            targetState = onboardingCompleted,
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "Holen app destination",
        ) { completed ->
            if (completed) {
                DownloadHome(
                    viewModel = viewModel,
                    onChooseFolder = onChooseFolder,
                    onQueue = onQueue,
                    onOpen = onOpen,
                    onShare = onShare,
                )
            } else {
                OnboardingFlow(
                    viewModel = viewModel,
                    onChooseFolder = onChooseFolder,
                    onOpenSource = onOpenSource,
                )
            }
        }
    }
}

@Composable
internal fun CreatorCredit(modifier: Modifier = Modifier) {
    Text(
        "Made by @yashas.vm",
        modifier = modifier
            .fillMaxWidth()
            .background(HolenBackground)
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics {
                testTag = "persistent-creator-credit"
                contentDescription = "Made by at yashas dot vm."
            },
        color = HolenBlue,
        style = MaterialTheme.typography.labelLarge,
        textAlign = TextAlign.Center,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadHome(
    viewModel: MainViewModel,
    onChooseFolder: () -> Unit,
    onQueue: () -> Unit,
    onOpen: (DownloadJob) -> Unit,
    onShare: (DownloadJob) -> Unit,
) {
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val url by viewModel.url.collectAsStateWithLifecycle()
    val analysis by viewModel.analysis.collectAsStateWithLifecycle()
    val format by viewModel.selectedFormat.collectAsStateWithLifecycle()
    val selectedEntries by viewModel.selectedEntries.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val folderGranted by viewModel.folderGranted.collectAsStateWithLifecycle()
    val rightsAcknowledged by viewModel.rightsAcknowledged.collectAsStateWithLifecycle()
    val engineVersion by viewModel.engineVersion.collectAsStateWithLifecycle()
    val engineMessage by viewModel.engineMessage.collectAsStateWithLifecycle()
    val cookiesConfigured by viewModel.cookiesConfigured.collectAsStateWithLifecycle()
    val cookieMessage by viewModel.cookieMessage.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<DownloadJob?>(null) }
    val reducedMotion = remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }

    val activeCount = jobs.count {
        it.status == JobStatus.QUEUED ||
            it.status == JobStatus.RUNNING ||
            it.status == JobStatus.FINALIZING
    }
    val finished = jobs.filter { it.status.isTerminal }
    val idle = url.isBlank() &&
        analysis == null &&
        jobs.isEmpty() &&
        !busy &&
        error == null &&
        folderGranted

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(HolenBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val idleTop = ((maxHeight - 520.dp) / 2).coerceAtLeast(20.dp)
        val topPadding by animateDpAsState(
            if (idle) idleTop else 20.dp,
            tween(if (reducedMotion) 0 else 360, easing = FastOutSlowInEasing),
            label = "Home hero position",
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .semantics { testTag = if (idle) "home-idle" else "home-active" },
            contentPadding = PaddingValues(
                start = 16.dp,
                top = topPadding,
                end = 16.dp,
                bottom = 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
        item("header") {
            Header(
                activeCount = activeCount,
                onSettings = { showSettings = true },
            )
        }

        if (!folderGranted) {
            item("folder-warning") {
                BauhausCard(background = HolenYellow) {
                    SectionTitle("DOWNLOAD FOLDER REQUIRED")
                    Text(
                        "Choose a local folder once. Holen only receives access to that folder.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    HolenButton("Choose download folder", onChooseFolder, HolenInk)
                }
            }
        }

        item("url") {
            BauhausCard {
                SectionTag("NEW DOWNLOAD", HolenBlue)
                Text(
                    "Paste an HTTPS file or media page",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = viewModel::setUrl,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    label = { Text("Source URL", style = MaterialTheme.typography.bodyMedium) },
                    placeholder = { Text("https://…", style = MaterialTheme.typography.bodyLarge) },
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                    isError = error != null,
                    supportingText = error?.let { message ->
                        { Text(message, color = HolenRed, style = MaterialTheme.typography.bodySmall) }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = RectangleShape,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HolenButton(
                        label = "Paste",
                        onClick = {
                            coroutineScope.launch {
                                clipboard.getClipEntry()
                                    ?.clipData
                                    ?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)
                                    ?.coerceToText(context)
                                    ?.toString()
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let(viewModel::setUrl)
                            }
                        },
                        background = HolenSurfaceTwo,
                        foreground = HolenInk,
                    )
                    AnimatedVisibility(
                        visible = url.isNotEmpty(),
                        enter = fadeIn(tween(if (reducedMotion) 0 else 150)) +
                            slideInVertically(tween(if (reducedMotion) 0 else 150)) { it / 4 },
                        exit = fadeOut(tween(if (reducedMotion) 0 else 100)),
                    ) {
                        HolenButton(
                            "Clear",
                            viewModel::clearUrl,
                            background = HolenSurface,
                            foreground = HolenInk,
                        )
                    }
                    AnimatedContent(
                        targetState = busy,
                        transitionSpec = {
                            fadeIn(tween(if (reducedMotion) 0 else 120)) togetherWith
                                fadeOut(tween(if (reducedMotion) 0 else 100))
                        },
                        label = "Analyze state",
                    ) { analyzing ->
                        HolenButton(
                            label = if (analyzing) "Analyzing…" else "Analyze",
                            onClick = viewModel::analyze,
                            background = HolenBlue,
                            enabled = url.isNotBlank() && !analyzing,
                            loading = analyzing,
                        )
                    }
                }
            }
        }

        when (val preview = analysis) {
            is SourceAnalysis.DirectFile -> item("direct-preview") {
                MotionEntrance(reducedMotion, "direct-preview") {
                    PreviewCard(
                        tag = "DIRECT FILE",
                        title = preview.title,
                        thumbnailUrl = null,
                        details = listOfNotNull(
                            preview.mimeType,
                            preview.sizeBytes?.let(::formatBytes),
                            "Original format",
                        ),
                    ) {
                        RightsQueueHelper(rightsAcknowledged)
                        HolenButton(
                            "Queue original",
                            onQueue,
                            HolenGreen,
                            enabled = !busy && folderGranted && rightsAcknowledged,
                        )
                    }
                }
            }

            is SourceAnalysis.Media -> item("media-preview") {
                MotionEntrance(reducedMotion, "media-preview") {
                    PreviewCard(
                        tag = preview.uploader ?: "MEDIA",
                        title = preview.title,
                        thumbnailUrl = preview.thumbnailUrl,
                        details = listOfNotNull(
                            preview.durationSeconds?.let(::formatDuration),
                            preview.estimatedSizes[format]?.let { "Est. ${formatBytes(it)}" },
                        ),
                    ) {
                        FormatPicker(format, viewModel::setFormat)
                        RightsQueueHelper(rightsAcknowledged)
                        HolenButton(
                            "Add to queue",
                            onQueue,
                            HolenGreen,
                            enabled = !busy && folderGranted && rightsAcknowledged,
                        )
                    }
                }
            }

            is SourceAnalysis.Playlist -> {
                item("playlist-preview") {
                    MotionEntrance(reducedMotion, "playlist-preview") {
                        BauhausCard(background = HolenSurfaceTwo) {
                        SectionTag("PLAYLIST", HolenRed)
                        Text(
                            preview.title,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            listOfNotNull(
                                preview.uploader,
                                "${preview.entries.size} preview items",
                                "${selectedEntries.size} selected",
                                "max ${YtDlpEngine.PLAYLIST_QUEUE_LIMIT} per queue",
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = HolenMuted,
                        )
                        FormatPicker(format, viewModel::setFormat)
                        RightsQueueHelper(rightsAcknowledged)
                        if (selectedEntries.size > YtDlpEngine.PLAYLIST_QUEUE_LIMIT) {
                            Text(
                                "Select ${selectedEntries.size - YtDlpEngine.PLAYLIST_QUEUE_LIMIT} fewer items to queue.",
                                color = HolenRed,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            HolenButton(
                                if (preview.entries.all { it.id in selectedEntries }) {
                                    "Deselect all"
                                } else {
                                    "Select all"
                                },
                                viewModel::toggleAllEntries,
                                HolenSurface,
                                foreground = HolenInk,
                            )
                            HolenButton(
                                "Queue ${selectedEntries.size}",
                                onQueue,
                                HolenGreen,
                                enabled = selectedEntries.isNotEmpty() &&
                                    selectedEntries.size <= YtDlpEngine.PLAYLIST_QUEUE_LIMIT &&
                                    !busy &&
                                    folderGranted &&
                                    rightsAcknowledged,
                            )
                        }
                    }
                }
                }
                items(preview.entries, key = { "playlist-${it.id}" }) { entry ->
                    val selected = entry.id in selectedEntries
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (selected) Color(0xFFDCE8F5) else HolenSurface)
                            .border(2.dp, HolenInk)
                            .toggleable(
                                value = selected,
                                role = Role.Checkbox,
                                onValueChange = { viewModel.toggleEntry(entry.id) },
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(
                                checkedColor = HolenBlue,
                                checkmarkColor = Color.White,
                            ),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            entry.durationSeconds?.let {
                                Text(
                                    formatDuration(it),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = HolenMuted,
                                )
                            }
                        }
                    }
                }
            }

            null -> Unit
        }

        if (jobs.isNotEmpty()) item("queue-header") {
            MotionEntrance(reducedMotion, "queue-header") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider(thickness = 4.dp, color = HolenInk)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SectionTag("QUEUE", HolenBlue)
                    Text(
                        "${jobs.size} ${if (jobs.size == 1) "JOB" else "JOBS"}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    if (jobs.any { it.status == JobStatus.QUEUED } &&
                        jobs.none {
                            it.status == JobStatus.RUNNING || it.status == JobStatus.FINALIZING
                        }
                    ) {
                        HolenButton(
                            "Resume",
                            viewModel::resumeQueue,
                            background = HolenGreen,
                        )
                    }
                }
                if (finished.isNotEmpty()) {
                    TextButton(onClick = { viewModel.clearFinished() }) {
                        Text("Clear finished", color = HolenMuted)
                    }
                }
                }
            }
        }

        if (jobs.isEmpty()) {
            item("empty") {
                Text(
                    "Paste a link above, or share one to Holen from another app.",
                    color = HolenMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        } else {
            items(jobs, key = { it.id }) { job ->
                MotionEntrance(reducedMotion, "job-${job.id}", Modifier.animateItem()) {
                    JobCard(
                        job = job,
                        reducedMotion = reducedMotion,
                        onCancel = { viewModel.cancel(job) },
                        onRetry = { viewModel.retry(job) },
                        onOpen = { onOpen(job) },
                        onShare = { onShare(job) },
                        onDelete = { deleteCandidate = job },
                    )
                }
            }
        }

        }
    }

    if (showSettings) {
        SettingsDialog(
            folderGranted = folderGranted,
            bundledVersion = viewModel.bundledEngineVersion,
            activeVersion = engineVersion,
            message = engineMessage,
            cookiesConfigured = cookiesConfigured,
            cookieMessage = cookieMessage,
            busy = busy,
            onChooseFolder = onChooseFolder,
            onUpdate = viewModel::updateEngine,
            onReset = viewModel::resetEngine,
            onSaveCookies = viewModel::saveCookies,
            onClearCookies = viewModel::clearCookies,
            onDismiss = { showSettings = false },
        )
    }

    deleteCandidate?.let { job ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            shape = RectangleShape,
            containerColor = HolenSurface,
            title = { Text("Delete saved file?", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "This permanently deletes ${job.fileName ?: job.title} from the chosen folder and removes its history.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFile(job)
                    deleteCandidate = null
                }) {
                    Text("Delete", color = HolenRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("Keep file") }
            },
        )
    }
}

@Composable
private fun MotionEntrance(
    reducedMotion: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = true,
        modifier = modifier.semantics { testTag = tag },
        enter = fadeIn(tween(if (reducedMotion) 0 else 280)) +
            slideInVertically(tween(if (reducedMotion) 0 else 320)) { it.coerceAtMost(24) } +
            scaleIn(
                initialScale = .98f,
                animationSpec = tween(if (reducedMotion) 0 else 320),
            ),
    ) { content() }
}

@Composable
private fun Header(activeCount: Int, onSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(HolenRed)
                    .border(3.dp, HolenInk),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "H",
                    color = Color.White,
                    fontFamily = Syne,
                    fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            Column(Modifier.weight(1f)) {
                SectionTag("ON DEVICE", HolenBlue)
                Text(
                    "HOLEN",
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.semantics { heading() },
                )
                if (activeCount > 0) {
                    Text(
                        "$activeCount active",
                        style = MaterialTheme.typography.bodySmall,
                        color = HolenMuted,
                    )
                }
            }
            HolenButton(
                "Settings",
                onSettings,
                background = HolenSurface,
                foreground = HolenInk,
            )
        }
        HorizontalDivider(thickness = 4.dp, color = HolenInk)
    }
}

@Composable
private fun PreviewCard(
    tag: String,
    title: String,
    thumbnailUrl: String?,
    details: List<String>,
    actions: @Composable ColumnScope.() -> Unit,
) {
    BauhausCard(background = HolenSurfaceTwo) {
        if (thumbnailUrl != null) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = "$title thumbnail",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RectangleShape)
                    .border(3.dp, HolenInk),
                contentScale = ContentScale.Crop,
            )
        }
        SectionTag(tag.uppercase(), HolenRed)
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        if (details.isNotEmpty()) {
            Text(
                details.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = HolenMuted,
            )
        }
        actions()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FormatPicker(
    selected: DownloadFormat,
    onSelected: (DownloadFormat) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("FORMAT", style = MaterialTheme.typography.labelMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MEDIA_FORMATS.forEach { format ->
                val active = selected == format
                HolenButton(
                    label = format.label,
                    onClick = { onSelected(format) },
                    background = if (active) HolenBlue else HolenSurface,
                    foreground = if (active) Color.White else HolenInk,
                    modifier = Modifier.semantics { this.selected = active },
                )
            }
        }
    }
}

@Composable
private fun JobCard(
    job: DownloadJob,
    reducedMotion: Boolean,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val animatedProgress by animateFloatAsState(
        job.progress.toFloat(),
        tween(if (reducedMotion) 0 else 220),
        label = "Job progress",
    )
    BauhausCard(
        background = HolenSurface,
        borderColor = HolenInk,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (job.thumbnailUrl != null) {
                AsyncImage(
                    model = job.thumbnailUrl,
                    contentDescription = "${job.title} thumbnail",
                    modifier = Modifier
                        .size(width = 88.dp, height = 54.dp)
                        .border(2.dp, HolenInk),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(width = 64.dp, height = 54.dp)
                        .background(HolenSurfaceTwo)
                        .border(2.dp, HolenInk),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (job.format.isAudio) "AUDIO" else "FILE",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedContent(
                        targetState = job.status,
                        transitionSpec = {
                            fadeIn(tween(if (reducedMotion) 0 else 140)) togetherWith
                                fadeOut(tween(if (reducedMotion) 0 else 100))
                        },
                        label = "Job status",
                    ) { status ->
                        StatusBadge(status)
                    }
                    Text(
                        job.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (job.status in ACTIVE_STATUSES) {
                    LinearProgressIndicator(
                        progress = { animatedProgress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .border(2.dp, HolenInk)
                            .semantics { contentDescription = "${job.progress}% downloaded" },
                        color = HolenRed,
                        trackColor = HolenSurfaceTwo,
                    )
                }
                val detail = jobDetail(job)
                if (detail.isNotBlank()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (job.status == JobStatus.FAILED) HolenRed else HolenMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        when (job.status) {
            JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.FINALIZING ->
                HolenButton("Cancel", onCancel, HolenRed)
            JobStatus.FAILED, JobStatus.CANCELLED ->
                HolenButton("Retry", onRetry, HolenBlue)
            JobStatus.COMPLETED -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HolenButton("Open", onOpen, HolenGreen, modifier = Modifier.weight(1f))
                HolenButton("Share", onShare, HolenBlue, modifier = Modifier.weight(1f))
                HolenButton("Delete", onDelete, HolenRed, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatusBadge(status: JobStatus) {
    val background = when (status) {
        JobStatus.COMPLETED -> HolenGreen
        JobStatus.FAILED -> HolenRed
        JobStatus.RUNNING, JobStatus.FINALIZING -> HolenYellow
        JobStatus.CANCELLED -> HolenSurfaceTwo
        JobStatus.QUEUED -> HolenBlue
    }
    val foreground = if (background == HolenBlue || background == HolenGreen || background == HolenRed) {
        Color.White
    } else {
        HolenInk
    }
    Text(
        status.name,
        modifier = Modifier
            .background(background)
            .border(2.dp, HolenInk)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        color = foreground,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun SettingsDialog(
    folderGranted: Boolean,
    bundledVersion: String,
    activeVersion: String,
    message: String?,
    cookiesConfigured: Boolean,
    cookieMessage: String?,
    busy: Boolean,
    onChooseFolder: () -> Unit,
    onUpdate: () -> Unit,
    onReset: () -> Unit,
    onSaveCookies: (String) -> Unit,
    onClearCookies: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showCookieDialog by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = HolenSurface,
        title = { Text("SETTINGS", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionTag(
                    if (folderGranted) "FOLDER READY" else "FOLDER REQUIRED",
                    if (folderGranted) HolenGreen else HolenRed,
                )
                HolenButton(
                    "Change download folder",
                    onChooseFolder,
                    background = HolenSurfaceTwo,
                    foreground = HolenInk,
                )
                HorizontalDivider(thickness = 2.dp, color = HolenInk)
                Text("MEDIA ENGINE", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Bundled: $bundledVersion\nActive: $activeVersion",
                    style = MaterialTheme.typography.bodySmall,
                )
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.contains("failed", true)) HolenRed else HolenGreen,
                    )
                }
                HolenButton(
                    if (busy) "Working…" else "Update engine",
                    onUpdate,
                    HolenBlue,
                    enabled = !busy,
                    loading = busy,
                )
                HolenButton(
                    "Reset to bundled",
                    onReset,
                    background = HolenSurfaceTwo,
                    foreground = HolenInk,
                    enabled = !busy,
                )
                HorizontalDivider(thickness = 2.dp, color = HolenInk)
                Text("ADVANCED", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (cookiesConfigured) "Configured on this device" else "Not configured",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (cookiesConfigured) HolenGreen else HolenMuted,
                    modifier = Modifier.semantics { testTag = "cookies-status" },
                )
                HolenButton(
                    "Configure cookies.txt",
                    { showCookieDialog = true },
                    background = HolenSurfaceTwo,
                    foreground = HolenInk,
                    modifier = Modifier.semantics { testTag = "configure-cookies" },
                )
                if (cookiesConfigured) {
                    HolenButton(
                        "Clear cookies",
                        { confirmClear = true },
                        background = HolenRed,
                        modifier = Modifier.semantics { testTag = "clear-cookies" },
                    )
                }
                cookieMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.contains("saved") || it.contains("cleared")) HolenGreen else HolenRed,
                    )
                }
                Text(
                    "HOLEN Android is GPL-3.0. Android runtime components are covered by the bundled third-party notices. Engine updates are manual and use yt-dlp’s stable upstream release.",
                    style = MaterialTheme.typography.bodySmall,
                    color = HolenMuted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = HolenBlue, fontWeight = FontWeight.Bold)
            }
        },
    )
    if (showCookieDialog) {
        CookieDialog(
            onSave = {
                onSaveCookies(it)
                showCookieDialog = false
            },
            onDismiss = { showCookieDialog = false },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            shape = RectangleShape,
            containerColor = HolenSurface,
            title = { Text("Clear cookies?") },
            text = { Text("This removes the private cookies.txt file from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    onClearCookies()
                    confirmClear = false
                }) { Text("Clear", color = HolenRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CookieDialog(
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    fun dismiss() {
        text = ""
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = ::dismiss,
        shape = RectangleShape,
        containerColor = HolenSurface,
        title = { Text("Configure cookies.txt") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Authentication cookies are sensitive and may grant access to your account. Paste only a Netscape-format cookies.txt file exported from an account you own or are authorized to use. HOLEN stores it only in private on-device storage. Cookies may expire, and they cannot bypass DRM or other access controls.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { candidate ->
                        if (candidate.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) text = candidate
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp)
                        .semantics { testTag = "cookies-input" },
                    label = { Text("Netscape cookies.txt") },
                    minLines = 7,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                    ),
                    visualTransformation =
                        if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { visible = !visible }) {
                            Text(if (visible) "Hide" else "Show")
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    val value = text
                    text = ""
                    onSave(value)
                },
            ) { Text("Save cookies", color = HolenBlue, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = ::dismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RightsQueueHelper(acknowledged: Boolean) {
    if (!acknowledged) {
        Text(
            "Acknowledge the download-rights notice below before queueing.",
            style = MaterialTheme.typography.bodySmall,
            color = HolenRed,
        )
    }
}

@Composable
internal fun BauhausCard(
    modifier: Modifier = Modifier,
    background: Color = HolenSurface,
    borderColor: Color = HolenInk,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier.padding(end = 5.dp, bottom = 5.dp)) {
        Box(
            Modifier
                .matchParentSize()
                .offset(5.dp, 5.dp)
                .background(HolenInk),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .border(3.dp, borderColor)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
internal fun HolenButton(
    label: String,
    onClick: () -> Unit,
    background: Color,
    modifier: Modifier = Modifier,
    foreground: Color = Color.White,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val context = LocalContext.current
    val reducedMotion = remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
    val pressScale by animateFloatAsState(
        if (pressed && !reducedMotion) .97f else 1f,
        tween(if (reducedMotion) 0 else 120),
        label = "Button press",
    )
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = 48.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
                alpha = if (pressed && !reducedMotion) .88f else 1f
            },
        interactionSource = interactionSource,
        shape = RectangleShape,
        border = androidx.compose.foundation.BorderStroke(2.dp, HolenInk),
        colors = ButtonDefaults.buttonColors(
            containerColor = background,
            contentColor = foreground,
            disabledContainerColor = HolenSurfaceTwo,
            disabledContentColor = HolenMuted,
        ),
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 10.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = foreground,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun SectionTag(text: String, background: Color) {
    Text(
        text,
        modifier = Modifier
            .background(background)
            .border(2.dp, HolenInk)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
}

private val ACTIVE_STATUSES = setOf(
    JobStatus.QUEUED,
    JobStatus.RUNNING,
    JobStatus.FINALIZING,
)

private val JobStatus.isTerminal: Boolean
    get() = this == JobStatus.COMPLETED || this == JobStatus.FAILED || this == JobStatus.CANCELLED

private val DownloadFormat.isAudio: Boolean
    get() = this == DownloadFormat.AUDIO_M4A || this == DownloadFormat.AUDIO_MP3

private val DownloadFormat.label: String
    get() = when (this) {
        DownloadFormat.ORIGINAL -> "Original"
        DownloadFormat.BEST_MP4 -> "Best MP4"
        DownloadFormat.MP4_1080 -> "1080p MP4"
        DownloadFormat.MP4_720 -> "720p MP4"
        DownloadFormat.AUDIO_M4A -> "M4A audio"
        DownloadFormat.AUDIO_MP3 -> "MP3"
    }

private val MEDIA_FORMATS = listOf(
    DownloadFormat.BEST_MP4,
    DownloadFormat.MP4_1080,
    DownloadFormat.MP4_720,
    DownloadFormat.AUDIO_M4A,
    DownloadFormat.AUDIO_MP3,
)

private fun jobDetail(job: DownloadJob): String = when (job.status) {
    JobStatus.QUEUED -> "Waiting in FIFO queue"
    JobStatus.RUNNING -> listOfNotNull(
        "${job.progress}%",
        job.speedBytesPerSecond?.let { "${formatBytes(it)}/s" },
        job.etaSeconds?.let { "${formatDuration(it)} left" },
    ).joinToString(" · ")
    JobStatus.FINALIZING -> "Finalizing and copying to your folder"
    JobStatus.COMPLETED -> listOfNotNull(job.fileName, job.totalBytes?.let(::formatBytes))
        .joinToString(" · ")
    JobStatus.FAILED -> job.errorMessage ?: "Download failed"
    JobStatus.CANCELLED -> "Cancelled — partial data removed"
}

private fun formatBytes(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var amount = bytes.toDouble()
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
