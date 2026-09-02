package com.yashasvm.holen

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class CookieIsolationUiTest {
    private val eligibleId = "cookie-isolation-eligible"
    private val accountGatedId = "cookie-isolation-account-gated"
    private val cancelledId = "cookie-isolation-cancelled"
    private val seededIds = listOf(eligibleId, accountGatedId, cancelledId)

    val composeRule = createAndroidComposeRule<MainActivity>()

    private val seedState = TestRule { base: Statement, _: Description ->
        object : Statement() {
            override fun evaluate() {
                val context = ApplicationProvider.getApplicationContext<Context>()
                val preferences = context.getSharedPreferences(
                    HolenStore.PREFERENCES_NAME,
                    Context.MODE_PRIVATE,
                )
                val cookieStore = CookieStore(context)
                val store = HolenStore.get(context)
                check(
                    preferences.edit()
                        .clear()
                        .putBoolean(HolenStore.PREF_ONBOARDING_COMPLETED, true)
                        .putInt(HolenStore.PREF_ONBOARDING_VERSION, HolenStore.ONBOARDING_VERSION)
                        .commit(),
                ) { "Could not seed onboarding state before launching MainActivity" }
                cookieStore.clear()
                cookieStore.save(
                    "# Netscape HTTP Cookie File\n" +
                        ".youtube.com\tTRUE\t/\tTRUE\t0\tSID\ttest-session\n",
                )
                runBlocking {
                    seededIds.forEach { store.remove(it) }
                    store.insert(seedJobs())
                }
                try {
                    base.evaluate()
                } finally {
                    runBlocking { seededIds.forEach { store.remove(it) } }
                    cookieStore.clear()
                    preferences.edit().clear().commit()
                }
            }
        }
    }

    @get:Rule
    val rules: TestRule = RuleChain
        .outerRule(seedState)
        .around(composeRule)

    @Test
    fun retryWithoutCookiesIsVisibleOnlyForEligibleFailedMedia() {
        composeRule.waitUntil(5_000) {
            runCatching {
                composeRule.onNodeWithTag("retry-without-cookies-$eligibleId")
                    .assertIsDisplayed()
            }.isSuccess
        }

        composeRule.onNodeWithTag("retry-without-cookies-$eligibleId").assertIsDisplayed()
        composeRule.onNodeWithTag("retry-without-cookies-$accountGatedId").assertDoesNotExist()
        composeRule.onNodeWithTag("retry-without-cookies-$cancelledId").assertDoesNotExist()
    }

    private fun seedJobs(): List<DownloadJob> {
        val now = System.currentTimeMillis()
        val publicFailure =
            "The source denied access (HTTP 403). If this is public media and cookies are configured, retry once without cookies; otherwise refresh the cookies/account access."
        return listOf(
            testJob(
                id = eligibleId,
                status = JobStatus.FAILED,
                errorMessage = publicFailure,
                createdAt = now + 2,
            ),
            testJob(
                id = accountGatedId,
                status = JobStatus.FAILED,
                errorMessage = "This source needs a signed-in account. Add fresh cookies from an account permitted to access it, then retry.",
                createdAt = now + 1,
            ),
            testJob(
                id = cancelledId,
                status = JobStatus.CANCELLED,
                errorMessage = publicFailure,
                createdAt = now,
            ),
        )
    }

    private fun testJob(
        id: String,
        status: JobStatus,
        errorMessage: String,
        createdAt: Long,
    ) = DownloadJob(
        id = id,
        sourceUrl = "https://www.youtube.com/watch?v=test",
        sourceKind = SourceKind.MEDIA,
        format = DownloadFormat.BEST_MP4,
        title = id,
        thumbnailUrl = null,
        status = status,
        progress = 0,
        bytesDownloaded = 0,
        totalBytes = null,
        speedBytesPerSecond = null,
        etaSeconds = null,
        outputUri = null,
        fileName = null,
        mimeType = null,
        errorMessage = errorMessage,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
