package com.noamv.localllm.engine

import android.app.ActivityManager
import android.content.Context
import com.noamv.localllm.transfer.ModelRole

internal class ModelResidencyCoordinator(
    private val availableMemoryProvider: () -> Long,
) {
    constructor(context: Context) : this({
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memoryInfo)
        memoryInfo.availMem
    })

    fun canDualReside(): Boolean =
        availableMemoryProvider() >= DUAL_RESIDENCY_HEADROOM_BYTES

    fun shouldUnloadOtherRoles(
        incomingRole: ModelRole,
        currentResidentRoles: Set<ModelRole>,
    ): Boolean {
        if (currentResidentRoles.isEmpty()) return false
        if (currentResidentRoles.size == 1 && currentResidentRoles.contains(incomingRole)) return false
        // If device has insufficient headroom, enforce strict 1-role residency
        return !canDualReside()
    }

    fun isIdleExpired(lastActivityTime: Long, currentTime: Long = System.currentTimeMillis()): Boolean =
        (currentTime - lastActivityTime) >= IDLE_TIMEOUT_MILLIS

    companion object {
        const val DUAL_RESIDENCY_HEADROOM_BYTES = 2_500_000_000L // 2.5 GB
        const val IDLE_TIMEOUT_MILLIS = 5 * 60 * 1000L // 5 minutes
    }
}
