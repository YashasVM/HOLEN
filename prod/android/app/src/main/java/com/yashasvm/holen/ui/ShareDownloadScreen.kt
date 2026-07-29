package com.yashasvm.holen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yashasvm.holen.MainViewModel
import com.yashasvm.holen.SourceAnalysis
import kotlinx.coroutines.flow.collectLatest
import java.net.URI

@Composable
fun ShareDownloadScreen(
    viewModel: MainViewModel,
    hasValidUrl: Boolean,
    onDownload: () -> Unit,
    onOpenHolen: () -> Unit,
    onDismiss: () -> Unit,
    onQueued: () -> Unit,
) {
    val analysis by viewModel.analysis.collectAsStateWithLifecycle()
    val sharedUrl by viewModel.url.collectAsStateWithLifecycle()
    val format by viewModel.selectedFormat.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val setupComplete by viewModel.onboardingCompleted.collectAsStateWithLifecycle()
    val folderGranted by viewModel.folderGranted.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.queueEvents.collectLatest { onQueued() }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(HolenBackground)
            .border(3.dp, HolenInk)
            .heightIn(max = 640.dp)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "DOWNLOAD WITH HOLEN",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            HolenButton("Close", onDismiss, HolenSurface, foreground = HolenInk)
        }

        when {
            !hasValidUrl -> MessageState(
                "No HTTPS link found",
                "Share a public media or file link with Holen.",
            )
            !setupComplete || !folderGranted -> {
                MessageState(
                    "Finish setup first",
                    "Choose a download folder and accept the responsible-download agreement.",
                )
                PrimaryButton("Open Holen", onOpenHolen)
            }
            busy && analysis == null -> LoadingState(sharedUrl)
            error != null -> {
                MessageState("This link needs attention", error.orEmpty())
                if (error.orEmpty().contains("account", true) ||
                    error.orEmpty().contains("age", true)
                ) {
                    Text(
                        "Open Holen → Settings → Account session to connect cookies.txt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = HolenMuted,
                    )
                }
                PrimaryButton("Open Holen", onOpenHolen)
            }
            analysis is SourceAnalysis.DirectFile -> {
                val item = analysis as SourceAnalysis.DirectFile
                SharedTitle(item.title, "Original file")
                PrimaryButton("Download original", onDownload, enabled = !busy)
            }
            analysis is SourceAnalysis.Media -> {
                val item = analysis as SourceAnalysis.Media
                SharedTitle(item.title, item.uploader ?: "Media")
                FormatPicker(format, viewModel::setFormat)
                PrimaryButton("Download", onDownload, enabled = !busy)
            }
            analysis is SourceAnalysis.Playlist -> {
                val item = analysis as SourceAnalysis.Playlist
                SharedTitle(item.title, "Playlist")
                Text(
                    "Choose playlist items in the full app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = HolenMuted,
                )
                PrimaryButton("Open Holen", onOpenHolen)
            }
            else -> LoadingState(sharedUrl)
        }
    }
}

@Composable
private fun SharedTitle(title: String, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTag(label.uppercase(), HolenBlue)
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MessageState(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = HolenMuted)
    }
}

@Composable
private fun LoadingState(url: String) {
    val host = runCatching { URI(url).host.removePrefix("www.") }.getOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTag(
            text = if (host?.contains("youtu", ignoreCase = true) == true) "YOUTUBE" else "SHARED LINK",
            background = HolenYellow,
        )
        Text(
            "Fetching file details",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 3.dp,
                color = HolenBlue,
            )
            Text(
                host ?: "Preparing your download options…",
                style = MaterialTheme.typography.bodyMedium,
                color = HolenMuted,
            )
        }
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    HolenButton(
        label = label,
        onClick = onClick,
        background = HolenBlue,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
    )
}
