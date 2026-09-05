package com.yashasvm.holen

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OutputStoreStorageTest {
    @Test
    fun stagingDirectoryIsCreatedWhenMissing() {
        val parent = createTempDirectory("holen-output-store-").toFile()
        val directory = File(parent, "job")
        try {
            val prepared = OutputStore.prepareStagingDirectory(directory)
            assertSame(directory, prepared)
            assertTrue(prepared.isDirectory)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun stagingDirectoryConflictIsClassifiedAsStorage() {
        val occupiedPath = File.createTempFile("holen-output-store-", ".tmp")
        try {
            OutputStore.prepareStagingDirectory(occupiedPath)
            fail("Expected staging-path conflict to fail")
        } catch (error: StorageException) {
            assertEquals("Could not prepare private download storage.", error.message)
        } finally {
            occupiedPath.delete()
        }
    }
}
