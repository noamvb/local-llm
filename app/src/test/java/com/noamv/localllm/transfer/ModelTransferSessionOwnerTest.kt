package com.noamv.localllm.transfer

import com.noamv.localllm.service.ModelTransferCommand
import com.noamv.localllm.service.ModelTransferService
import com.noamv.localllm.service.routeModelTransferCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTransferSessionOwnerTest {
    @Test
    fun `repeated starts coalesce without widening the active policy`() {
        val owner = ModelTransferSessionOwner()
        val first = owner.start(TransferNetworkPolicy.UNMETERED_WIFI)
            as TransferStartDecision.Started
        val repeated = owner.start(TransferNetworkPolicy.ALLOW_METERED_ONCE)
            as TransferStartDecision.Coalesced

        assertEquals(first.session.id, repeated.session.id)
        assertEquals(TransferNetworkPolicy.UNMETERED_WIFI, repeated.session.policy)
        assertFalse(owner.finish(first.session.id + 1))
        assertSame(first.session, owner.active())
        assertTrue(owner.finish(first.session.id))

        val later = owner.start(TransferNetworkPolicy.ALLOW_METERED_ONCE)
            as TransferStartDecision.Started
        assertNotEquals(first.session.id, later.session.id)
    }

    @Test
    fun `command routing is explicit start only and retry never resumes`() {
        assertEquals(
            ModelTransferCommand.Start(TransferNetworkPolicy.UNMETERED_WIFI),
            routeModelTransferCommand(ModelTransferService.ACTION_START, false, 0),
        )
        assertEquals(
            ModelTransferCommand.Start(TransferNetworkPolicy.ALLOW_METERED_ONCE),
            routeModelTransferCommand(ModelTransferService.ACTION_START, true, 0),
        )
        assertEquals(
            ModelTransferCommand.Cancel,
            routeModelTransferCommand(ModelTransferService.ACTION_CANCEL, false, 0),
        )
        assertEquals(ModelTransferCommand.Invalid, routeModelTransferCommand(null, true, 0))
        assertEquals(
            ModelTransferCommand.Invalid,
            routeModelTransferCommand(
                ModelTransferService.ACTION_START,
                true,
                android.app.Service.START_FLAG_RETRY,
            ),
        )
    }

    @Test
    fun `app deadline remains below Android data sync limit`() {
        assertTrue(ModelTransferService.TRANSFER_DEADLINE_MILLIS > 0)
        assertTrue(ModelTransferService.TRANSFER_DEADLINE_MILLIS < 6L * 60L * 60L * 1_000L)
    }

    @Test
    fun `critical trim registry reaches only current foreground owner`() {
        val registry = ForegroundTransferCancellationRegistry()
        val reasons = mutableListOf<TransferStopReason>()
        val registration = registry.register(8) { reasons += it }

        assertTrue(registry.cancel(TransferStopReason.CRITICAL_MEMORY))
        assertEquals(listOf(TransferStopReason.CRITICAL_MEMORY), reasons)
        registration.close()
        assertFalse(registry.cancel(TransferStopReason.CRITICAL_MEMORY))
    }
}
