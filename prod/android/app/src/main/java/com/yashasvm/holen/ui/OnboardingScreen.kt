package com.yashasvm.holen.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yashasvm.holen.MainViewModel

private const val PAGE_COUNT = 3

@Composable
internal fun OnboardingFlow(
    viewModel: MainViewModel,
    onChooseFolder: () -> Unit,
    onImportCookies: () -> Unit,
    onOpenCreator: () -> Unit,
    onOpenSource: () -> Unit,
) {
    val rightsAcknowledged by viewModel.rightsAcknowledged.collectAsStateWithLifecycle()
    val folderGranted by viewModel.folderGranted.collectAsStateWithLifecycle()
    val sessionConnected by viewModel.sessionConnected.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var agreementChecked by rememberSaveable(rightsAcknowledged) {
        mutableStateOf(rightsAcknowledged)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HolenBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OnboardingHeader(page)
        AnimatedContent(
            targetState = page,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                val direction = if (targetState > initialState) 1 else -1
                (
                    slideInHorizontally(
                        animationSpec = tween(260, easing = FastOutSlowInEasing),
                        initialOffsetX = { it * direction },
                    ) + fadeIn(tween(180))
                    ) togetherWith (
                    slideOutHorizontally(
                        animationSpec = tween(220, easing = FastOutSlowInEasing),
                        targetOffsetX = { -it * direction / 2 },
                    ) + fadeOut(tween(150))
                    )
            },
            label = "Onboarding page",
        ) { destination ->
            when (destination) {
                0 -> WelcomePage(
                    onContinue = { page = 1 },
                    onOpenCreator = onOpenCreator,
                )
                1 -> AgreementPage(
                    checked = agreementChecked,
                    onCheckedChange = { agreementChecked = it },
                    onBack = { page = 0 },
                    onContinue = {
                        viewModel.acknowledgeRights()
                        page = 2
                    },
                )
                else -> FolderPage(
                    folderGranted = folderGranted,
                    sessionConnected = sessionConnected,
                    onChooseFolder = onChooseFolder,
                    onImportCookies = onImportCookies,
                    onBack = { page = 1 },
                    onComplete = viewModel::completeOnboarding,
                    onOpenSource = onOpenSource,
                )
            }
        }
    }
}

@Composable
private fun OnboardingHeader(page: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(HolenRed)
                    .border(3.dp, HolenInk),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "H",
                    color = Color.White,
                    fontFamily = Syne,
                    fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Text("HOLEN", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "0${page + 1} / 0$PAGE_COUNT",
            modifier = Modifier
                .background(HolenInk)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun WelcomePage(
    onContinue: () -> Unit,
    onOpenCreator: () -> Unit,
) {
    var revealHero by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealHero = true }

    OnboardingPage {
        AnimatedVisibility(
            visible = revealHero,
            enter = fadeIn(tween(180)) + slideInVertically(
                animationSpec = tween(220, easing = FastOutSlowInEasing),
                initialOffsetY = { it / 8 },
            ) + scaleIn(initialScale = 0.98f, animationSpec = tween(220)),
        ) {
        Box(Modifier.fillMaxWidth().height(128.dp)) {
            Box(
                Modifier
                    .size(92.dp)
                    .align(Alignment.CenterEnd)
                    .clip(CircleShape)
                    .background(HolenYellow)
                    .border(3.dp, HolenInk, CircleShape),
            )
            Box(
                Modifier
                    .size(58.dp)
                    .align(Alignment.BottomEnd)
                    .background(HolenBlue)
                    .border(3.dp, HolenInk),
            )
            Text(
                "SAVE\nWHAT'S\nYOURS.",
                modifier = Modifier.align(Alignment.CenterStart),
                color = HolenInk,
                fontFamily = Syne,
                fontWeight = FontWeight.ExtraBold,
                style = MaterialTheme.typography.displaySmall,
            )
        }
        }
        SectionTag("WELCOME TO HOLEN", HolenRed)
        Text(
            "Your downloads,\non your terms.",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Holen downloads supported public media and direct files on your device—without an account, cloud queue, or tracking.",
            style = MaterialTheme.typography.bodyLarge,
            color = HolenMuted,
        )
        FeatureGrid()
        Spacer(Modifier.height(2.dp))
        HolenButton(
            label = "Show me how",
            onClick = onContinue,
            background = HolenBlue,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = onOpenCreator,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                "Made by @yashas.vm",
                color = HolenMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun FeatureGrid() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FeatureRow(
            number = "01",
            title = "SHARE DIRECTLY",
            detail = "Choose Holen from YouTube or any app's Share menu.",
            accent = HolenRed,
        )
        FeatureRow(
            number = "02",
            title = "PICK YOUR QUALITY",
            detail = "MP4, 1080p, 720p, M4A, MP3, or the original file.",
            accent = HolenBlue,
        )
        FeatureRow(
            number = "03",
            title = "WATCH IT MOVE",
            detail = "See real download percentage, speed, and time left.",
            accent = HolenGreen,
        )
    }
}

@Composable
private fun FeatureRow(
    number: String,
    title: String,
    detail: String,
    accent: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HolenSurface)
            .border(2.dp, HolenInk)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            number,
            modifier = Modifier
                .background(accent)
                .padding(horizontal = 7.dp, vertical = 5.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = HolenMuted)
        }
    }
}

@Composable
private fun AgreementPage(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    OnboardingPage {
        SectionTag("DOWNLOAD RESPONSIBLY", HolenYellow)
        Text(
            "Keep downloads fair.",
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Holen is a tool for files you own, public-domain material, and content you have permission to save.",
            style = MaterialTheme.typography.bodyLarge,
        )
        BauhausCard(background = HolenSurfaceTwo) {
            AgreementLine("Only download content you own or are authorized to save.")
            HorizontalDivider(thickness = 2.dp, color = HolenInk)
            AgreementLine("Respect copyright, creator terms, and the rules where you live.")
            HorizontalDivider(thickness = 2.dp, color = HolenInk)
            AgreementLine("Holen does not bypass DRM, accounts, age gates, or access controls.")
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (checked) Color(0xFFDCEFE4) else HolenSurface)
                .border(3.dp, HolenInk)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .semantics {
                    contentDescription = "Responsible download agreement"
                    stateDescription = if (checked) "Accepted" else "Not accepted"
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = HolenGreen,
                    checkmarkColor = Color.White,
                ),
            )
            Text(
                "I understand and agree to download responsibly.",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        OnboardingActions(
            primaryLabel = "I agree — continue",
            primaryEnabled = checked,
            onPrimary = onContinue,
            onBack = onBack,
        )
    }
}

@Composable
private fun AgreementLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(10.dp)
                .background(HolenRed)
                .border(1.dp, HolenInk),
        )
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FolderPage(
    folderGranted: Boolean,
    sessionConnected: Boolean,
    onChooseFolder: () -> Unit,
    onImportCookies: () -> Unit,
    onBack: () -> Unit,
    onComplete: () -> Unit,
    onOpenSource: () -> Unit,
) {
    OnboardingPage {
        SectionTag("ONE LAST STEP", HolenBlue)
        Text(
            "Choose where files land.",
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Android's folder picker lets you grant access to one location. Holen cannot browse the rest of your storage.",
            style = MaterialTheme.typography.bodyLarge,
            color = HolenMuted,
        )
        BauhausCard(background = if (folderGranted) Color(0xFFDCEFE4) else HolenYellow) {
            Text(
                if (folderGranted) "FOLDER READY" else "DOWNLOAD LOCATION",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                if (folderGranted) {
                    "Permission saved. You can change this folder later in Settings."
                } else {
                    "A Downloads/Holen folder is a good choice. Existing files are never overwritten."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            HolenButton(
                label = if (folderGranted) "Change folder" else "Choose download folder",
                onClick = onChooseFolder,
                background = if (folderGranted) HolenSurface else HolenInk,
                foreground = if (folderGranted) HolenInk else Color.White,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AnimatedVisibility(
            visible = folderGranted,
            enter = fadeIn(tween(180)) + slideInVertically(
                animationSpec = tween(220, easing = FastOutSlowInEasing),
                initialOffsetY = { it / 3 },
            ),
            exit = fadeOut(tween(150)),
        ) {
            Text(
                "You're set. Links shared to Holen will open straight into quality selection.",
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HolenGreen)
                    .border(2.dp, HolenInk)
                    .padding(12.dp),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
        OptionalYouTubeSessionCard(
            sessionConnected = sessionConnected,
            onImportCookies = onImportCookies,
        )
        TextButton(
            onClick = onOpenSource,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                "Curious what the tiny code goblins do? Peek at the OSS code ->",
                color = HolenMuted,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        OnboardingActions(
            primaryLabel = "Enter Holen",
            primaryEnabled = folderGranted,
            onPrimary = onComplete,
            onBack = onBack,
        )
    }
}

@Composable
private fun OptionalYouTubeSessionCard(
    sessionConnected: Boolean,
    onImportCookies: () -> Unit,
) {
    BauhausCard(background = if (sessionConnected) Color(0xFFDCEFE4) else HolenSurfaceTwo) {
        SectionTag(
            if (sessionConnected) "YOUTUBE COOKIES IMPORTED" else "OPTIONAL: YOUTUBE SIGN-IN",
            if (sessionConnected) HolenGreen else HolenRed,
        )
        Text(
            "For YouTube's age gate.",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            "Import YouTube cookies only to download videos when YouTube requires sign-in or age verification. " +
                "They stay on this device and are never sent to other sites.",
            style = MaterialTheme.typography.bodyMedium,
            color = HolenMuted,
        )
        Text(
            "This does not create a Holen account, post, subscribe, or do anything else on your behalf.",
            style = MaterialTheme.typography.bodySmall,
            color = HolenMuted,
        )
        if (!sessionConnected) {
            Text(
                "You'll choose a Netscape cookies.txt export from a browser where you're already signed in to YouTube.",
                style = MaterialTheme.typography.bodySmall,
                color = HolenMuted,
            )
        }
        HolenButton(
            label = if (sessionConnected) "Replace YouTube cookies" else "Sign in with YouTube (cookies.txt)",
            onClick = onImportCookies,
            background = HolenRed,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!sessionConnected) {
            Text(
                "Skip it if you like - public downloads work without it.",
                style = MaterialTheme.typography.bodySmall,
                color = HolenMuted,
            )
        }
    }
}

@Composable
private fun OnboardingActions(
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HolenButton(
            label = "Back",
            onClick = onBack,
            background = HolenSurface,
            foreground = HolenInk,
            modifier = Modifier.weight(0.35f),
        )
        HolenButton(
            label = primaryLabel,
            onClick = onPrimary,
            background = HolenBlue,
            enabled = primaryEnabled,
            modifier = Modifier.weight(0.65f),
        )
    }
}

@Composable
private fun OnboardingPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}
