package com.yashasvm.holen

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class PublicationStorageTest {
    @Test
    fun providerFailureIsClassifiedAsStorage() {
        val cause = IOException("provider write failed")
        try {
            publicationStorage<Unit> { throw cause }
            fail("Expected provider failure to be classified as storage")
        } catch (error: StorageException) {
            assertEquals("The selected folder could not be written.", error.message)
            assertSame(cause, error.cause)
        }
    }

    @Test
    fun customFailureMessageIsUsedForCreationErrors() {
        val cause = IOException("provider create failed")
        try {
            publicationStorage<Unit>("The selected folder could not create a file.") { throw cause }
            fail("Expected provider creation failure to be classified as storage")
        } catch (error: StorageException) {
            assertEquals("The selected folder could not create a file.", error.message)
            assertSame(cause, error.cause)
        }
    }

    @Test
    fun existingStorageFailureIsPreserved() {
        val expected = StorageException("already classified")
        try {
            publicationStorage<Unit> { throw expected }
            fail("Expected storage failure")
        } catch (error: StorageException) {
            assertSame(expected, error)
        }
    }

    @Test
    fun cancellationIsPreserved() {
        val expected = CancellationException("cancelled")
        try {
            publicationStorage<Unit> { throw expected }
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertSame(expected, error)
        }
    }
}
