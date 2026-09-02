package com.yashasvm.holen

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.fetchSemanticsNodes
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodes
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
                composeRule.onAllNodes(hasTestTag("home-active"))
                    .fetchSemanticsNodes(atLeastOneRootRequired = false)
                    .isNotEmpty()
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            Log.i(LOG_TAG, "app_home_ms=$elapsedMs")
        }
    }

    companion object {
        private const val LOG_TAG = "HOLENAppStartup"
        private const val STARTUP_TIMEOUT_MS = 15_000L
    }
}
