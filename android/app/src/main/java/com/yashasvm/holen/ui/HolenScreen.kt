package com.yashasvm.holen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HolenScreen(
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
    val clipboard = LocalClipboardManager.current
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var selectedJobs by rememberSaveable { mutableStateOf(setOf<String>()) }
    var deleteCandidate by remember { mutableStateOf<DownloadJob?>(null) }

    val activeCount = jobs.count {
        it.status == JobStatus.QUEUED ||
            it.status == JobStatus.RUNNING ||
            it.status == JobStatus.FINALIZING
    }
    val finished = jobs.filter { it.status.isTerminal }
    LaunchedEffect(jobs) {
        selectedJobs = selectedJobs.intersect(finished.map { it.id }.toSet())
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(HolenBackground),
        contentPadding = PaddingValues(start = 16.dp, top = 20.dp, end = 16.dp),
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
                    "Paste a public HTTPS file or media page",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = viewModel::setUrl,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Source URL") },
                    placeholder = { Text("https://…") },
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                    isError = error != null,
                    supportingText = error?.let { message ->
                        { Text(message, color = HolenRed) }
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
                            clipboard.getText()?.text?.takeIf { it.isNotBlank() }
                                ?.let(viewModel::setUrl)
                        },
                        background = HolenSurfaceTwo,
                        foreground = HolenInk,
                    )
                    if (url.isNotEmpty()) {
                        HolenButton(
                            "Clear",
                            viewModel::clearUrl,
                            background = HolenSurface,
                            foreground = HolenInk,
                        )
                    }
                    HolenButton(
                        label = if (busy) "Analyzing…" else "Analyze",
                        onClick = viewModel::analyze,
                        background = HolenBlue,
                        enabled = url.isNotBlank() && !busy,
                        loading = busy,
                    )
                }
            }
        }

        when (val preview = analysis) {
            is SourceAnalysis.DirectFile -> item("direct-preview") {
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
                    HolenButton(
                        "Queue original",
                        onQueue,
                        HolenGreen,
                        enabled = !busy && folderGranted && rightsAcknowledged,
                    )
                }
            }

            is SourceAnalysis.Media -> item("media-preview") {
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
                    HolenButton(
                        "Add to queue",
                        onQueue,
                        HolenGreen,
                        enabled = !busy && folderGranted && rightsAcknowledged,
                    )
                }
            }

            is SourceAnalysis.Playlist -> {
                item("playlist-preview") {
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
                                "${selectedEntries.size}/${YtDlpEngine.PLAYLIST_QUEUE_LIMIT} selected",
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = HolenMuted,
                        )
                        FormatPicker(format, viewModel::setFormat)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            HolenButton(
                                if (selectedEntries.isEmpty()) "Select first 25" else "Deselect all",
                                viewModel::toggleAllEntries,
                                HolenSurface,
                                HolenInk,
                            )
                            HolenButton(
                                "Queue ${selectedEntries.size}",
                                onQueue,
                                HolenGreen,
                                enabled = selectedEntries.isNotEmpty() &&
                                    !busy &&
                                    folderGranted &&
                                    rightsAcknowledged,
                            )
                        }
                    }
                }
                items(preview.entries, key = { "playlist-${it.id}" }) { entry ->
                    val selected = entry.id in selectedEntries
                    val capped = !selected &&
                        selectedEntries.size >= YtDlpEngine.PLAYLIST_QUEUE_LIMIT
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (selected) Color(0xFFDCE8F5) else HolenSurface)
                            .border(2.dp, HolenInk)
                            .clickable(
                                enabled = !capped,
                                role = Role.Checkbox,
                                onClick = { viewModel.toggleEntry(entry.id) },
                            )
                            .semantics { this.selected = selected }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = { viewModel.toggleEntry(entry.id) },
                            enabled = !capped,
                            colors = CheckboxDefaults.colors(
                                checkedColor = HolenBlue,
                                checkmarkColor = Color.White,
                            ),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
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

        item("queue-header") {
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
                    if (jobs.any { it.status == JobStatus.QUEUED }) {
                        HolenButton(
                            "Resume",
                            viewModel::resumeQueue,
                            background = HolenGreen,
                        )
                    }
                }
                if (finished.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HolenButton(
                            if (selectedJobs.size == finished.size) "Deselect all" else "Select finished",
                            onClick = {
                                selectedJobs = if (selectedJobs.size == finished.size) {
                                    emptySet()
                                } else {
                                    finished.map { it.id }.toSet()
                                }
                            },
                            background = HolenSurfaceTwo,
                            foreground = HolenInk,
                        )
                        HolenButton(
                            "Clear finished",
                            onClick = { viewModel.clearFinished() },
                            background = HolenSurface,
                            foreground = HolenInk,
                        )
                        if (selectedJobs.isNotEmpty()) {
                            HolenButton(
                                "Clear ${selectedJobs.size}",
                                onClick = {
                                    viewModel.clearFinished(selectedJobs)
                                    selectedJobs = emptySet()
                                },
                                background = HolenRed,
                            )
                        }
                    }
                }
            }
        }

        if (jobs.isEmpty()) {
            item("empty") {
                BauhausCard(background = HolenSurfaceTwo) {
                    SectionTitle("NO DOWNLOADS YET")
                    Text(
                        "Analyze a link above. Downloads and conversions stay on this device.",
                        color = HolenMuted,
                    )
                }
            }
        } else {
            items(jobs, key = { it.id }) { job ->
                JobCard(
                    job = job,
                    selected = job.id in selectedJobs,
                    onSelect = {
                        selectedJobs = if (job.id in selectedJobs) {
                            selectedJobs - job.id
                        } else {
                            selectedJobs + job.id
                        }
                    },
                    onCancel = { viewModel.cancel(job) },
                    onRetry = { viewModel.retry(job) },
                    onOpen = { onOpen(job) },
                    onShare = { onShare(job) },
                    onDelete = { deleteCandidate = job },
                )
            }
        }

        item("rights") {
            BauhausCard(background = HolenYellow) {
                SectionTitle("DOWNLOAD RESPONSIBLY")
                Text(
                    "Only download files you own or are authorized to save. Holen does not bypass DRM, accounts, or site restrictions.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!rightsAcknowledged) {
                    HolenButton("I understand", viewModel::acknowledgeRights, HolenInk)
                } else {
                    SectionTag("ACKNOWLEDGED", HolenGreen)
                }
            }
        }

        item("bottom-inset") {
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }

    if (showSettings) {
        SettingsDialog(
            folderGranted = folderGranted,
            bundledVersion = viewModel.bundledEngineVersion,
            activeVersion = engineVersion,
            message = engineMessage,
            busy = busy,
            onChooseFolder = onChooseFolder,
            onUpdate = viewModel::updateEngine,
            onReset = viewModel::resetEngine,
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
private fun FormatPicker(
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
    selected: Boolean,
    onSelect: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    BauhausCard(
        background = HolenSurface,
        borderColor = if (selected) HolenBlue else HolenInk,
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
                    StatusBadge(job.status)
                    Text(
                        job.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (job.status in ACTIVE_STATUSES) {
                    LinearProgressIndicator(
                        progress = { job.progress / 100f },
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
            if (job.status.isTerminal) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onSelect() },
                    modifier = Modifier.semantics {
                        contentDescription = "Select ${job.title} history"
                    },
                    colors = CheckboxDefaults.colors(checkedColor = HolenBlue),
                )
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
    busy: Boolean,
    onChooseFolder: () -> Unit,
    onUpdate: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = HolenSurface,
        title = { Text("SETTINGS", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                Text(
                    "Holen Android is GPL-3.0. Engine updates are manual and use yt-dlp’s stable upstream release.",
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
}

@Composable
private fun BauhausCard(
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
private fun HolenButton(
    label: String,
    onClick: () -> Unit,
    background: Color,
    foreground: Color = Color.White,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
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
private fun SectionTag(text: String, background: Color) {
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
