package com.yashasvm.holen

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PublicationReservationGateTest {
    @Test
    fun reservationWindowsDoNotOverlap() {
        val gate = PublicationReservationGate()
        val ready = CountDownLatch(2)
        val release = CountDownLatch(1)
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val futures = List(2) {
                executor.submit {
                    ready.countDown()
                    assertTrue(ready.await(2, TimeUnit.SECONDS))
                    gate.withReservation {
                        val now = active.incrementAndGet()
                        peak.accumulateAndGet(now, ::maxOf)
                        release.await(200, TimeUnit.MILLISECONDS)
                        active.decrementAndGet()
                    }
                }
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            Thread.sleep(50)
            release.countDown()
            futures.forEach { it.get(2, TimeUnit.SECONDS) }
            assertEquals(1, peak.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
