package com.yashasvm.holen.ui

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yashasvm.holen.MainViewModel
import com.yashasvm.holen.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class OnboardingStage { Welcome, About, Tutorial, FairDownload, Folder }

private object OnboardingMotion {
    const val Reveal = 420
    const val Stagger = 360L
    const val Exit = 210
    const val Pill = 260
    const val Frame = 1_800L
    const val ManualPause = 3_000L
}

private data class TutorialData(
    val image: Int,
    val caption: String,
    val description: String,
)

private val tutorialFrames = listOf(
    TutorialData(
        R.drawable.onboarding_share_01,
        "Find the video or audio you want to save.",
        "YouTube video page ready to share.",
    ),
    TutorialData(
        R.drawable.onboarding_share_02,
        "Tap Share, then open More options.",
        "Initial share sheet with the More options action.",
    ),
    TutorialData(
        R.drawable.onboarding_share_03,
        "Choose HOLEN from the share menu.",
        "Expanded Android share sheet showing HOLEN.",
    ),
    TutorialData(
        R.drawable.onboarding_share_04,
        "Pick a video or audio format, then tap Download.",
        "HOLEN format selection dialog.",
    ),
    TutorialData(
        R.drawable.onboarding_share_05,
        "That’s it—HOLEN downloads it in the background.",
        "Android notification showing a HOLEN download in progress.",
    ),
)

@Composable
internal fun OnboardingFlow(
    viewModel: MainViewModel,
    onChooseFolder: () -> Unit,
    onOpenSource: () -> Unit,
) {
    val folderGranted by viewModel.folderGranted.collectAsStateWithLifecycle()
    var stageName by rememberSaveable { mutableStateOf(OnboardingStage.Welcome.name) }
    val stage = OnboardingStage.entries.firstOrNull { it.name == stageName }
        ?: OnboardingStage.Welcome
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

    fun goTo(next: OnboardingStage) {
        stageName = next.name
    }

    BackHandler(enabled = stage != OnboardingStage.Welcome) {
        goTo(OnboardingStage.entries[stage.ordinal - 1])
    }

    AnimatedContent(
        targetState = stage,
        modifier = Modifier
            .fillMaxSize()
            .background(HolenBackground)
            .clipToBounds(),
        transitionSpec = {
            if (reducedMotion) {
                fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            } else {
                val forward = targetState.ordinal > initialState.ordinal
                (
                    slideInHorizontally(
                        tween(320, easing = FastOutSlowInEasing),
                        initialOffsetX = { if (forward) it / 4 else -it / 4 },
                    ) + slideInVertically(
                        tween(320, easing = FastOutSlowInEasing),
                        initialOffsetY = { if (forward) it / 8 else -it / 8 },
                    ) + fadeIn(tween(260))
                    ) togetherWith (
                    slideOutHorizontally(
                        tween(OnboardingMotion.Exit),
                        targetOffsetX = { if (forward) -it / 5 else it / 5 },
                    ) + fadeOut(tween(OnboardingMotion.Exit))
                    )
            }
        },
        label = "Onboarding stage",
    ) { current ->
        when (current) {
            OnboardingStage.Welcome -> WelcomeStage(reducedMotion) {
                goTo(OnboardingStage.About)
            }
            OnboardingStage.About -> AboutStage(reducedMotion, onOpenSource) {
                goTo(OnboardingStage.Tutorial)
            }
            OnboardingStage.Tutorial -> ShareTutorialStage(reducedMotion) {
                goTo(OnboardingStage.FairDownload)
            }
            OnboardingStage.FairDownload -> FairDownloadStage(reducedMotion) {
                viewModel.acknowledgeRights()
                goTo(OnboardingStage.Folder)
            }
            OnboardingStage.Folder -> FolderSetupStage(
                reducedMotion = reducedMotion,
                folderGranted = folderGranted,
                onChooseFolder = onChooseFolder,
                onComplete = viewModel::completeOnboarding,
            )
        }
    }
}

@Composable
private fun WelcomeStage(
    reducedMotion: Boolean,
    onContinue: () -> Unit,
) {
    var focused by remember { mutableStateOf(reducedMotion) }
    val blur by animateDpAsState(
        if (focused) 0.dp else 14.dp,
        tween(if (reducedMotion) 0 else 460, easing = FastOutSlowInEasing),
        label = "Welcome blur",
    )
    LaunchedEffect(Unit) {
        focused = true
        if (!reducedMotion) delay(2_600)
        onContinue()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .clickable(
                role = Role.Button,
                onClickLabel = "Skip welcome",
                onClick = onContinue,
            )
            .semantics {
                testTag = "onboarding-welcome"
                contentDescription = "Welcome to HOLEN. Tap to continue."
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Welcome to\nHOLEN.",
            modifier = Modifier.blur(blur).semantics { heading() },
            color = HolenInk,
            fontFamily = Syne,
            fontWeight = FontWeight.ExtraBold,
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AboutStage(
    reducedMotion: Boolean,
    onOpenSource: () -> Unit,
    onNext: () -> Unit,
) {
    var step by remember { mutableIntStateOf(if (reducedMotion) 5 else 0) }
    LaunchedEffect(Unit) {
        if (!reducedMotion) {
            step = 1
            delay(OnboardingMotion.Reveal.toLong())
            step = 2
            delay(1_400)
            step = 3
            delay(120)
            step = 4
            delay(220)
            step = 5
        }
    }
    StageLayout("onboarding-about") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(tween(if (reducedMotion) 0 else 320)),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            BlurRevealText(
                text = "Download anything with HOLEN. Free.",
                visible = step >= 1,
                reducedMotion = reducedMotion,
                singleLine = true,
            )
            BlurRevealText(
                text = "Skip sketchy websites and unsafe download pages.",
                visible = step >= 2,
                reducedMotion = reducedMotion,
                supporting = true,
            )
            AnimatedVisibility(
                visible = step >= 3,
                enter = fadeIn(tween(if (reducedMotion) 0 else 400)) +
                    slideInVertically(tween(if (reducedMotion) 0 else 400)) { it / 5 },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BlurRevealText(
                        text = "HOLEN OSS is MIT-licensed.*",
                        visible = true,
                        reducedMotion = reducedMotion,
                        singleLine = true,
                    )
                    Text(
                        "* The Android app is GPL-3.0 because it includes GPL-licensed media components. See Third-Party Notices.",
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                        color = HolenMuted,
                    )
                    AnimatedVisibility(visible = step >= 4, enter = pillEnter(reducedMotion)) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.Start)
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = "Open HOLEN repository on GitHub",
                                    onClick = onOpenSource,
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .semantics {
                                    testTag = "about-repository-link"
                                    contentDescription = "GitHub repository, YashasVM slash HOLEN"
                                },
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(
                                painterResource(R.drawable.ic_github),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                            Text(
                                "YashasVM/HOLEN",
                                color = HolenBlue,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = step >= 5,
            enter = pillEnter(reducedMotion),
        ) {
            OnboardingPillButton("Next", onNext, Modifier.semantics { testTag = "onboarding-next" })
        }
    }
}

@Composable
private fun ShareTutorialStage(reducedMotion: Boolean, onNext: () -> Unit) {
    var frame by rememberSaveable { mutableIntStateOf(0) }
    var manualPauseUntil by remember { mutableLongStateOf(0L) }

    fun select(index: Int) {
        frame = index.coerceIn(tutorialFrames.indices)
        manualPauseUntil = android.os.SystemClock.uptimeMillis() + OnboardingMotion.ManualPause
    }

    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        while (frame < tutorialFrames.lastIndex) {
            val remaining = manualPauseUntil - android.os.SystemClock.uptimeMillis()
            if (remaining > 0) delay(remaining)
            delay(OnboardingMotion.Frame)
            frame++
        }
    }

    StageLayout("onboarding-tutorial", topAligned = true) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "How to download with HOLEN",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Share a link. Pick a format. HOLEN handles the rest.",
                color = HolenMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TutorialFrame(frame, reducedMotion)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tutorialFrames.indices.forEach { index ->
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(
                            role = Role.RadioButton,
                            onClickLabel = "Show tutorial frame ${index + 1}",
                        ) { select(index) }
                        .semantics {
                            testTag = "tutorial-dot-${index + 1}"
                            stateDescription = if (index == frame) "Selected" else "Not selected"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(
                                width = if (index == frame) 22.dp else 8.dp,
                                height = if (index == frame) 10.dp else 8.dp,
                            )
                            .clip(CircleShape)
                            .background(if (index == frame) HolenRed else HolenMuted),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallPill("Previous", enabled = frame > 0) { select(frame - 1) }
            if (frame == tutorialFrames.lastIndex) {
                OnboardingPillButton(
                    "Next",
                    onNext,
                    Modifier
                        .widthIn(max = 180.dp)
                        .semantics { testTag = "tutorial-page-next" },
                )
            } else {
                SmallPill("Next frame") { select(frame + 1) }
            }
        }
    }
}

@Composable
private fun TutorialFrame(frame: Int, reducedMotion: Boolean) {
    val data = tutorialFrames[frame]
    val imageMaxHeight = LocalConfiguration.current.screenHeightDp.dp * .48f
    val scale by animateFloatAsState(
        if (frame % 2 == 0) 1f else 0.992f,
        tween(if (reducedMotion) 0 else 300),
        label = "Stop motion scale",
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AnimatedContent(
            targetState = frame,
            transitionSpec = {
                fadeIn(tween(if (reducedMotion) 0 else 260)) togetherWith
                    fadeOut(tween(if (reducedMotion) 0 else 220))
            },
            label = "Tutorial image",
        ) {
            Image(
                painter = painterResource(tutorialFrames[it].image),
                contentDescription = tutorialFrames[it].description,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = imageMaxHeight)
                    .aspectRatio(9f / 16f, matchHeightConstraintsFirst = true)
                    .clip(RoundedCornerShape(20.dp))
                    .background(HolenSurfaceTwo)
                    .scale(scale)
                    .semantics { testTag = "tutorial-frame-${it + 1}" },
                contentScale = ContentScale.Fit,
            )
        }
        Text(
            data.caption,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = data.caption
                },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FairDownloadStage(reducedMotion: Boolean, onSigned: () -> Unit) {
    var signed by rememberSaveable { mutableStateOf(false) }
    var step by remember { mutableIntStateOf(if (reducedMotion) 6 else 0) }
    LaunchedEffect(Unit) {
        if (!reducedMotion) {
            for (next in 1..6) {
                step = next
                delay(if (next in 3..5) 95 else 180)
            }
        }
    }
    StageLayout("onboarding-fair-download") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .align(Alignment.CenterHorizontally)
                .animateContentSize(tween(if (reducedMotion) 0 else 320)),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            BlurRevealText("A tiny favor from the admin.", step >= 1, reducedMotion)
            AnimatedVisibility(step >= 2, enter = pillEnter(reducedMotion)) {
                Text(
                    "I’d rather not meet a lawyer because of your download history. Please save only files you own, have permission to use, or that are legally available to download. Deal? :)",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    "Respect creators, copyright, and local laws.",
                    "Do not use HOLEN to bypass DRM, accounts, age gates, or access controls.",
                    "You are responsible for what you choose to download.",
                ).forEachIndexed { index, text ->
                    AnimatedVisibility(step >= index + 3, enter = pillEnter(reducedMotion)) {
                        LegalLine(text)
                    }
                }
            }
            AnimatedVisibility(step >= 6, enter = pillEnter(reducedMotion)) {
                HoldToSignButton(signed = signed, reducedMotion = reducedMotion) { signed = true }
            }
            AnimatedVisibility(signed, enter = pillEnter(reducedMotion)) {
                OnboardingPillButton(
                    "Continue",
                    onSigned,
                    Modifier.semantics { testTag = "hold-continue" },
                )
            }
        }
    }
}

@Composable
private fun HoldToSignButton(
    signed: Boolean,
    reducedMotion: Boolean,
    onSigned: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var held by remember { mutableStateOf(false) }
    var target by remember { mutableFloatStateOf(if (signed) 1f else 0f) }
    val progress by animateFloatAsState(
        target,
        tween(if (target == 1f) 1_200 else 220),
        finishedListener = {
            if (it == 1f && held && !signed) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onSigned()
            }
        },
        label = "Hold progress",
    )
    val shape = RoundedCornerShape(28.dp)
    val completedScale by animateFloatAsState(
        if (signed) 1.03f else 1f,
        tween(if (reducedMotion) 0 else 150),
        label = "Signed pulse",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .scale(completedScale)
            .clip(shape)
            .background(HolenInk)
            .pointerInput(signed) {
                if (!signed) awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    held = true
                    target = 1f
                    val up = waitForUpOrCancellation()
                    if (up == null || progress < 0.99f) {
                        held = false
                        target = 0f
                    }
                }
            }
            .semantics {
                testTag = "hold-to-sign"
                role = Role.Button
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                stateDescription = if (signed) "Signed" else "${(progress * 100).toInt()} percent held"
                onClick("Sign responsible download acknowledgment") {
                    // Accessibility activation cannot express a precisely timed hold.
                    if (!signed) {
                        scope.launch {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSigned()
                        }
                    }
                    true
                }
            },
    ) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { scaleX = progress; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, .5f) }
                .background(HolenRed),
        )
        AnimatedContent(
            targetState = signed,
            modifier = Modifier.align(Alignment.Center),
            transitionSpec = {
                fadeIn(tween(if (reducedMotion) 0 else 140)) togetherWith
                    fadeOut(tween(if (reducedMotion) 0 else 100))
            },
            label = "Signed label",
        ) { complete ->
            Text(
                if (complete) "✓  Signed. Nobody panic." else "Hold to sign",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun FolderSetupStage(
    reducedMotion: Boolean,
    folderGranted: Boolean,
    onChooseFolder: () -> Unit,
    onComplete: () -> Unit,
) {
    StageLayout("onboarding-folder") {
        Text(
            "One last practical thing.",
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Choose where HOLEN should save your downloads. Android grants access only to that folder, and you can change it later in Settings.",
            style = MaterialTheme.typography.bodyLarge,
            color = HolenMuted,
        )
        if (folderGranted) {
            AnimatedVisibility(true, enter = pillEnter(reducedMotion)) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("✓", color = HolenGreen, fontSize = 42.sp)
                    Text("Download folder ready.", style = MaterialTheme.typography.titleLarge)
                    SmallPill("Change folder", onClick = onChooseFolder)
                }
            }
        } else {
            OnboardingPillButton(
                "Choose download folder",
                onChooseFolder,
                Modifier.semantics { testTag = "choose-folder" },
            )
        }
        OnboardingPillButton(
            "Enter HOLEN",
            onComplete,
            Modifier.semantics { testTag = "enter-holen" },
            enabled = folderGranted,
        )
    }
}

@Composable
private fun BlurRevealText(
    text: String,
    visible: Boolean,
    reducedMotion: Boolean,
    supporting: Boolean = false,
    singleLine: Boolean = false,
) {
    val alpha by animateFloatAsState(
        if (visible) 1f else 0f,
        tween(if (reducedMotion) 0 else OnboardingMotion.Reveal),
        label = "Reveal alpha",
    )
    val blur by animateDpAsState(
        if (visible) 0.dp else 14.dp,
        tween(if (reducedMotion) 0 else OnboardingMotion.Reveal, easing = FastOutSlowInEasing),
        label = "Reveal blur",
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val preferredSize = when {
            maxWidth < 340.dp -> 19.sp
            maxWidth < 390.dp -> 21.sp
            else -> 23.sp
        }
        val textMeasurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val availableWidth = with(density) { maxWidth.roundToPx() }
        val headlineStyle = MaterialTheme.typography.headlineSmall
        val fittingSize = if (supporting || !singleLine) {
            preferredSize
        } else {
            (preferredSize.value.toInt() downTo 14).firstOrNull { candidate ->
                textMeasurer.measure(
                    text = text,
                    style = headlineStyle.copy(fontSize = candidate.sp),
                    maxLines = 1,
                    softWrap = false,
                ).size.width <= availableWidth
            }?.sp ?: 14.sp
        }
        val needsAccessibilityScroll = singleLine && !supporting &&
            textMeasurer.measure(
                text = text,
                style = headlineStyle.copy(fontSize = fittingSize),
                maxLines = 1,
                softWrap = false,
            ).size.width > availableWidth
        Text(
            text,
            modifier = Modifier
                .then(
                    if (needsAccessibilityScroll) {
                        Modifier.horizontalScroll(rememberScrollState())
                    } else {
                        Modifier
                    },
                )
                .blur(blur)
                .graphicsLayer {
                    this.alpha = alpha
                    translationY = (1f - alpha) * 18f
                }
                .semantics { if (!supporting) heading() },
            style = if (supporting) {
                MaterialTheme.typography.bodyLarge
            } else {
                headlineStyle.copy(fontSize = fittingSize)
            },
            color = if (supporting) HolenMuted else HolenInk,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            softWrap = !singleLine,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun StageLayout(
    tag: String,
    topAligned: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val gutter = when {
            maxWidth >= 720.dp -> 72.dp
            maxWidth >= 480.dp -> 40.dp
            else -> 22.dp
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = gutter, vertical = 24.dp)
                .semantics { testTag = tag },
            verticalArrangement = if (topAligned) {
                Arrangement.spacedBy(18.dp)
            } else {
                Arrangement.spacedBy(24.dp, Alignment.CenterVertically)
            },
            content = content,
        )
    }
}

@Composable
private fun LegalLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 7.dp).size(7.dp).clip(CircleShape).background(HolenRed))
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun OnboardingPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scale by animateFloatAsState(if (enabled) 1f else .98f, tween(120), label = "Pill state")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .scale(scale)
            .clip(RoundedCornerShape(28.dp))
            .background(if (enabled) HolenRed else HolenSurfaceTwo)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            )
            .semantics {
                stateDescription = if (enabled) "Enabled" else "Disabled"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) Color.White else HolenMuted,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun SmallPill(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (enabled) HolenSurfaceTwo else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) HolenInk else HolenMuted, style = MaterialTheme.typography.labelLarge)
    }
}

private fun pillEnter(reducedMotion: Boolean) =
    fadeIn(tween(if (reducedMotion) 0 else OnboardingMotion.Pill)) +
        slideInVertically(tween(if (reducedMotion) 0 else OnboardingMotion.Pill)) { it / 3 } +
        scaleIn(initialScale = .96f, animationSpec = tween(if (reducedMotion) 0 else OnboardingMotion.Pill))
