package com.yashasvm.holen

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppStartupTimingTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("holenAppStartupTiming") == "true",
        )
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.resolve(STARTUP_REPORT_FILE).delete()
        val preferences = context.getSharedPreferences(
            HolenStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        check(
            preferences.edit()
                .clear()
                .putBoolean(HolenStore.PREF_ONBOARDING_COMPLETED, true)
                .putInt(HolenStore.PREF_ONBOARDING_VERSION, HolenStore.ONBOARDING_VERSION)
                .commit(),
        ) { "Could not seed startup timing state" }
    }

    @After
    fun tearDown() {
        if (::context.isInitialized) {
            context.getSharedPreferences(HolenStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    @Test
    fun measureTimeToRenderedHome() {
        val startedAt = SystemClock.elapsedRealtime()
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitUntil(timeoutMillis = STARTUP_TIMEOUT_MS) {
                runCatching {
                    composeRule.onNodeWithTag("home-active").assertIsDisplayed()
                }.isSuccess
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val timingLine = "app_home_ms=$elapsedMs"
            context.filesDir.resolve(STARTUP_REPORT_FILE).writeText("$timingLine\n")
            Log.i(LOG_TAG, timingLine)
        }
    }

    companion object {
        private const val LOG_TAG = "HOLENAppStartup"
        private const val STARTUP_REPORT_FILE = "app-startup-timing.txt"
        private const val STARTUP_TIMEOUT_MS = 15_000L
    }
}
