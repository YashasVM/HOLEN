package com.yashasvm.holen

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HolenInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstLaunchShowsFolderAndRightsRequirements() {
        composeRule.onNodeWithText("HOLEN").assertIsDisplayed()
        composeRule.onNodeWithText("DOWNLOAD FOLDER REQUIRED").assertIsDisplayed()
        composeRule.onNodeWithText("DOWNLOAD RESPONSIBLY").assertIsDisplayed()
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
}
