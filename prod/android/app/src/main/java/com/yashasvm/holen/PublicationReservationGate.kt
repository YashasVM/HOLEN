package com.yashasvm.holen

/**
 * Serializes the small SAF destination-reservation window while allowing the
 * expensive media copy to continue concurrently after a document is reserved.
 */
internal class PublicationReservationGate {
    private val lock = Any()

    fun <T> withReservation(block: () -> T): T = synchronized(lock, block)
}
