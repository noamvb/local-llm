package com.noamv.localllm.engine

import com.noamv.localllm.transfer.ModelRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelResidencyCoordinatorTest {

    @Test
    fun testDualResidencyPermittedWhenAvailableRamIsHigh() {
        val coordinator = ModelResidencyCoordinator { 3_000_000_000L } // 3.0 GB > 2.5 GB

        assertTrue(coordinator.canDualReside())
        assertFalse(
            coordinator.shouldUnloadOtherRoles(
                incomingRole = ModelRole.WRITER,
                currentResidentRoles = setOf(ModelRole.ROUTER),
            ),
        )
    }

    @Test
    fun testSingleRoleEnforcedWhenAvailableRamIsLow() {
        val coordinator = ModelResidencyCoordinator { 1_800_000_000L } // 1.8 GB < 2.5 GB

        assertFalse(coordinator.canDualReside())
        assertTrue(
            coordinator.shouldUnloadOtherRoles(
                incomingRole = ModelRole.WRITER,
                currentResidentRoles = setOf(ModelRole.ROUTER),
            ),
        )
    }

    @Test
    fun testIdleTimeoutExpiration() {
        val coordinator = ModelResidencyCoordinator { 3_000_000_000L }
        val now = 1000000L
        val activeRecent = now - (2 * 60 * 1000L) // 2 min ago
        val activeExpired = now - (6 * 60 * 1000L) // 6 min ago

        assertFalse(coordinator.isIdleExpired(activeRecent, now))
        assertTrue(coordinator.isIdleExpired(activeExpired, now))
    }
}
