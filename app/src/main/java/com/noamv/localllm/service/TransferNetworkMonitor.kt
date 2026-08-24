package com.noamv.localllm.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.noamv.localllm.transfer.TransferNetworkBlockReason
import com.noamv.localllm.transfer.TransferNetworkDecision
import com.noamv.localllm.transfer.TransferNetworkMonitorException
import com.noamv.localllm.transfer.TransferNetworkPolicy
import com.noamv.localllm.transfer.TransferNetworkPolicyException
import com.noamv.localllm.transfer.TransferNetworkSnapshot
import com.noamv.localllm.transfer.TransferNetworkTransport
import com.noamv.localllm.transfer.evaluateTransferNetwork
import okhttp3.Call
import okhttp3.Request
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface TransferNetworkRegistration : AutoCloseable {
    override fun close()
}

internal sealed interface TransferNetworkLeaseResult {
    data class Approved(val lease: TransferNetworkLease) : TransferNetworkLeaseResult
    data class Blocked(val reason: TransferNetworkBlockReason) : TransferNetworkLeaseResult
}

/**
 * A one-run lease over the exact Android default [network] approved at owner start.
 *
 * [invalidate] closes a one-way fence before cancellation reaches OkHttp. Every later
 * chunk request calls [validateOrThrow], so it cannot be created after policy loss was
 * decided. The pinned client additionally prevents transparent migration to cellular.
 */
internal class TransferNetworkLease(
    val network: Network,
    val policy: TransferNetworkPolicy,
    private val decisionNow: () -> TransferNetworkDecision,
) {
    private val callFence = TransferCallFence(decisionNow)

    fun invalidate(reason: TransferNetworkBlockReason): Boolean = callFence.invalidate(reason)

    fun validateOrThrow() = callFence.validateOrThrow()

    fun bind(callFactory: Call.Factory): Call.Factory = callFence.bind(callFactory)

    fun terminalBlockReason(): TransferNetworkBlockReason? = callFence.terminalBlockReason()
}

/**
 * Atomically fences HTTP call creation and synchronously cancels every call from this run.
 * If invalidation wins, the delegate factory is never reached. If call creation wins,
 * invalidation observes the registered call and cancels it before returning.
 */
internal class TransferCallFence(
    private val decisionNow: () -> TransferNetworkDecision,
) {
    private val lock = Any()
    private var terminalReason: TransferNetworkBlockReason? = null
    private val calls = mutableListOf<Call>()

    fun terminalBlockReason(): TransferNetworkBlockReason? = synchronized(lock) {
        terminalReason
    }

    fun invalidate(reason: TransferNetworkBlockReason): Boolean {
        val toCancel = synchronized(lock) {
            if (terminalReason != null) return false
            terminalReason = reason
            calls.toList()
        }
        toCancel.forEach(Call::cancel)
        return true
    }

    fun validateOrThrow() {
        val blocked = try {
            synchronized(lock) {
                terminalReason?.let { return@synchronized TransferNetworkDecision.Blocked(it) }
                (decisionNow() as? TransferNetworkDecision.Blocked)?.also {
                    terminalReason = it.reason
                }
            }
        } catch (error: RuntimeException) {
            invalidate(TransferNetworkBlockReason.NO_VALIDATED_NETWORK)
            throw TransferNetworkMonitorException(error)
        } ?: return
        val toCancel = synchronized(lock) { calls.toList() }
        toCancel.forEach(Call::cancel)
        throw TransferNetworkPolicyException(blocked.reason)
    }

    fun bind(delegate: Call.Factory): Call.Factory = Call.Factory { request ->
        newCall(delegate, request)
    }

    private fun newCall(delegate: Call.Factory, request: Request): Call {
        val outcome = try {
            synchronized(lock) {
                val existing = terminalReason
                if (existing != null) {
                    return@synchronized CallCreation.Blocked(existing, calls.toList())
                }
                val blocked = decisionNow() as? TransferNetworkDecision.Blocked
                if (blocked != null) {
                    terminalReason = blocked.reason
                    CallCreation.Blocked(blocked.reason, calls.toList())
                } else {
                    delegate.newCall(request).also(calls::add).let(CallCreation::Created)
                }
            }
        } catch (error: RuntimeException) {
            invalidate(TransferNetworkBlockReason.NO_VALIDATED_NETWORK)
            throw TransferNetworkMonitorException(error)
        }
        return when (outcome) {
            is CallCreation.Created -> outcome.call
            is CallCreation.Blocked -> {
                outcome.calls.forEach(Call::cancel)
                throw TransferNetworkPolicyException(outcome.reason)
            }
        }
    }

    private sealed interface CallCreation {
        data class Created(val call: Call) : CallCreation
        data class Blocked(
            val reason: TransferNetworkBlockReason,
            val calls: List<Call>,
        ) : CallCreation
    }
}

internal interface TransferNetworkMonitor {
    fun acquire(policy: TransferNetworkPolicy): TransferNetworkLeaseResult

    /** Invalidates [lease] before delivering [onBlocked]. */
    fun monitor(
        lease: TransferNetworkLease,
        onBlocked: (TransferNetworkDecision.Blocked) -> Unit,
    ): TransferNetworkRegistration
}

internal class AndroidTransferNetworkMonitor(context: Context) : TransferNetworkMonitor {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun acquire(policy: TransferNetworkPolicy): TransferNetworkLeaseResult {
        val network = connectivity.activeNetwork
            ?: return TransferNetworkLeaseResult.Blocked(
                TransferNetworkBlockReason.NO_VALIDATED_NETWORK,
            )
        val decision = decisionFor(network, policy)
        if (decision is TransferNetworkDecision.Blocked) {
            return TransferNetworkLeaseResult.Blocked(decision.reason)
        }
        return TransferNetworkLeaseResult.Approved(
            TransferNetworkLease(
                network = network,
                policy = policy,
                decisionNow = { decisionFor(network, policy) },
            ),
        )
    }

    override fun monitor(
        lease: TransferNetworkLease,
        onBlocked: (TransferNetworkDecision.Blocked) -> Unit,
    ): TransferNetworkRegistration {
        val closed = AtomicBoolean(false)
        val delivered = AtomicBoolean(false)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = checkLease()

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) = checkLease()

            override fun onLost(network: Network) = checkLease()

            private fun checkLease() {
                if (closed.get()) return
                val blocked = try {
                    lease.validateOrThrow()
                    return
                } catch (error: TransferNetworkPolicyException) {
                    TransferNetworkDecision.Blocked(error.reason)
                }
                // The lease fence is closed inside validateOrThrow before this callback.
                if (delivered.compareAndSet(false, true)) onBlocked(blocked)
            }
        }

        connectivity.registerDefaultNetworkCallback(callback)
        return TransferNetworkRegistration {
            if (closed.compareAndSet(false, true)) {
                runCatching { connectivity.unregisterNetworkCallback(callback) }
            }
        }
    }

    private fun decisionFor(
        network: Network,
        policy: TransferNetworkPolicy,
    ): TransferNetworkDecision {
        if (connectivity.activeNetwork != network) {
            return TransferNetworkDecision.Blocked(
                TransferNetworkBlockReason.NO_VALIDATED_NETWORK,
            )
        }
        val capabilities = connectivity.getNetworkCapabilities(network)
            ?: return TransferNetworkDecision.Blocked(
                TransferNetworkBlockReason.NO_VALIDATED_NETWORK,
            )
        return evaluateTransferNetwork(policy, capabilities.toSnapshot())
    }
}

private fun NetworkCapabilities.toSnapshot(): TransferNetworkSnapshot = TransferNetworkSnapshot(
    validated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
    internetCapable = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
    metered = !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
    transports = buildSet {
        if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            add(TransferNetworkTransport.WIFI)
        }
        if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            add(TransferNetworkTransport.CELLULAR)
        }
        if (hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            add(TransferNetworkTransport.ETHERNET)
        }
        if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            add(TransferNetworkTransport.VPN)
        }
        if (isEmpty()) add(TransferNetworkTransport.OTHER)
    },
)
