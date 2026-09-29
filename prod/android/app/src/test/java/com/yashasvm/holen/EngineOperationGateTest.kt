package com.yashasvm.holen

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineOperationGateTest {
    @Test
    fun maintenanceRunsWhenNothingBlocksIt() = runBlocking {
        val gate = EngineOperationGate()
        assertEquals("done", gate.withMaintenance { "done" })
    }

    @Test
    fun abortedMaintenanceDoesNotRunAndReleasesTheGate() = runBlocking {
        val gate = EngineOperationGate()
        var ran = false
        val error = runCatching {
            gate.withMaintenance(abortIf = { IOException("busy") }) { ran = true }
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertFalse(ran)
        // A later normal operation must not wait on the aborted maintenance request.
        assertEquals(1, gate.withOperation { 1 })
        assertEquals(2, gate.withMaintenance { 2 })
    }
}
