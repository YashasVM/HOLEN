package com.yashasvm.holen

import kotlinx.coroutines.CancellationException

/**
 * Classifies failures while publishing a completed staging file through SAF.
 * Cancellation and already-classified storage failures must retain their meaning.
 */
internal inline fun <T> publicationStorage(
    failureMessage: String = "The selected folder could not be written.",
    block: () -> T,
): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: StorageException) {
    throw error
} catch (error: Exception) {
    throw StorageException(failureMessage, error)
}
