package com.yashasvm.holen

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class HolenInstrumentedTest {
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val clearFirstLaunchState = TestRule { base: Statement, _: Description ->
        object : Statement() {
            override fun evaluate() {
                ApplicationProvider.getApplicationContext<Context>()
                    .getSharedPreferences(HolenStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: TestRule = RuleChain
        .outerRule(clearFirstLaunchState)
        .around(composeRule)

    @Test
    fun firstLaunchRunsCinematicOnboardingInOrder() {
        composeRule.waitUntil(5_000) {
            runCatching {
                composeRule.onNodeWithText("Welcome to\nHOLEN.").assertIsDisplayed()
            }.isSuccess
        }
        composeRule.onNodeWithText("Welcome to\nHOLEN.").assertIsDisplayed()
        composeRule.onNodeWithTag("persistent-creator-credit").assertDoesNotExist()
        composeRule.onNodeWithText("Welcome to\nHOLEN.").performClick()
        composeRule.onNodeWithTag("onboarding-about").assertIsDisplayed()
        composeRule.onNodeWithTag("persistent-creator-credit").assertDoesNotExist()
        composeRule.onNodeWithText(
            "Download anything with HOLEN. Free.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "Skip sketchy websites and unsafe download pages.",
        ).assertIsDisplayed()
        composeRule.waitUntil(4_000) {
            composeRule.onAllNodesWithText(
                "HOLEN OSS is MIT-licensed.*",
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("about-repository-link").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Download anything with HOLEN. Free.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "Skip sketchy websites and unsafe download pages.",
        ).assertIsDisplayed()

        composeRule.onNodeWithTag("onboarding-next").performClick()
        composeRule.onNodeWithTag("onboarding-tutorial").assertIsDisplayed()
        composeRule.onNodeWithTag("tutorial-dot-5").performClick()
        composeRule.onNodeWithTag("tutorial-frame-5").assertIsDisplayed()
        composeRule.onNodeWithText("Replay").assertDoesNotExist()
        composeRule.onNodeWithTag("tutorial-page-next").performClick()
        composeRule.onNodeWithTag("onboarding-fair-download").assertIsDisplayed()
        composeRule.onNodeWithText(
            "A tiny favor from the admin.",
        ).assertIsDisplayed()
    }

    @Test
    fun tutorialScreenshotsArePortraitBitmaps() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resources = listOf(
            R.drawable.onboarding_share_01,
            R.drawable.onboarding_share_02,
            R.drawable.onboarding_share_03,
            R.drawable.onboarding_share_04,
            R.drawable.onboarding_share_05,
        )
        resources.forEachIndexed { index, resource ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.resources.openRawResource(resource).use {
                BitmapFactory.decodeStream(it, null, options)
            }
            assertEquals("frame ${index + 1} width", 1440, options.outWidth)
            assertEquals(
                "frame ${index + 1} height",
                if (index == 0) 2561 else 2560,
                options.outHeight,
            )
        }
    }

    @Test
    fun sqliteSchemaIsCreatedAtCurrentVersion() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = HolenStore.get(context).readableDatabase
        assertEquals(HolenStore.SCHEMA_VERSION, database.version)
        database.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='jobs'",
            null,
        ).use { cursor -> assertEquals(true, cursor.moveToFirst()) }
    }

    @Test
    fun interruptedJobsAreRequeuedAndClaimedAtomically() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = HolenStore.get(context)
        val now = System.currentTimeMillis()
        val jobId = "restoration-test-$now"
        try {
            store.insert(
                listOf(
                    DownloadJob(
                        id = jobId,
                        sourceUrl = "https://example.com/file.zip",
                        sourceKind = SourceKind.DIRECT_FILE,
                        format = DownloadFormat.ORIGINAL,
                        title = "file.zip",
                        thumbnailUrl = null,
                        status = JobStatus.QUEUED,
                        progress = 0,
                        bytesDownloaded = 0,
                        totalBytes = null,
                        speedBytesPerSecond = null,
                        etaSeconds = null,
                        outputUri = null,
                        fileName = "file.zip",
                        mimeType = "application/zip",
                        errorMessage = null,
                        createdAt = 1,
                        updatedAt = now,
                    ),
                ),
            )

            val claims = coroutineScope {
                List(2) {
                    async(Dispatchers.IO) { store.claimNextQueued() }
                }.awaitAll()
            }
            assertEquals(1, claims.count { it?.id == jobId })
            assertEquals(JobStatus.RUNNING, store.get(jobId)?.status)

            store.requeueInterrupted()
            assertEquals(JobStatus.QUEUED, store.get(jobId)?.status)
            assertEquals(jobId, store.claimNextQueued()?.id)
            assertEquals(JobStatus.RUNNING, store.get(jobId)?.status)
            assertEquals(
                true,
                store.updateProgress(jobId, TransferProgress(10, 100, 1_000, 50, 18)),
            )
            assertEquals(true, store.cancelActive(jobId))
            assertEquals(
                false,
                store.updateProgress(jobId, TransferProgress(90, 900, 1_000, 50, 2)),
            )
            assertEquals(JobStatus.CANCELLED, store.get(jobId)?.status)
            assertEquals(10, store.get(jobId)?.progress)
        } finally {
            store.remove(jobId)
        }
    }
}
