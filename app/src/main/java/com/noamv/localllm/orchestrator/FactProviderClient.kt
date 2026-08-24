package com.noamv.localllm.orchestrator

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.noamv.localllm.contract.v2.AggregateQuery
import com.noamv.localllm.contract.v2.AppSource
import com.noamv.localllm.contract.v2.AssistantContractV2
import com.noamv.localllm.contract.v2.ProviderFactsResult
import com.noamv.localllm.v2.IAssistantFactsCallbackV2
import com.noamv.localllm.v2.IAssistantFactsProviderV2
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

internal class FactProviderClient(
    private val context: Context,
) {
    private val appContext = context.applicationContext ?: context

    suspend fun queryFacts(source: AppSource, query: AggregateQuery): ProviderFactsResult {
        val targetPackage = when (source) {
            AppSource.CANNSHEET -> "com.noamv.cannsheet.mobile"
            AppSource.POOP_SCHEDULE -> "com.noamv.poopschedule"
            else -> return ProviderFactsResult(
                sourceApp = source,
                facts = emptyList(),
                revision = "unsupported_source",
                asOfTime = System.currentTimeMillis(),
                timezone = "UTC",
                warnings = listOf("Unsupported source: $source"),
            )
        }

        val result = withTimeoutOrNull(5_000L) {
            executeFactQuery(targetPackage, source, query)
        }

        return result ?: ProviderFactsResult(
            sourceApp = source,
            facts = emptyList(),
            revision = "timeout",
            asOfTime = System.currentTimeMillis(),
            timezone = "UTC",
            warnings = listOf("Query to $source timed out or failed to connect"),
        )
    }

    private suspend fun executeFactQuery(
        targetPackage: String,
        source: AppSource,
        query: AggregateQuery,
    ): ProviderFactsResult? = suspendCancellableCoroutine { cont ->
        val intent = Intent("com.noamv.localllm.v2.action.BIND_FACTS_PROVIDER").apply {
            setPackage(targetPackage)
        }

        val unbindOnce = AtomicBoolean(false)
        val queryIdRef = AtomicReference<String?>(null)
        val providerRef = AtomicReference<IAssistantFactsProviderV2?>(null)
        lateinit var connection: ServiceConnection

        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) {
                    if (!unbindOnce.getAndSet(true)) appContext.unbindService(this)
                    if (cont.isActive) cont.resume(null)
                    return
                }

                val provider = IAssistantFactsProviderV2.Stub.asInterface(binder)
                providerRef.set(provider)

                val callback = object : IAssistantFactsCallbackV2.Stub() {
                    override fun onFactsResult(queryId: String?, resultJson: String?) {
                        if (!unbindOnce.getAndSet(true)) {
                            runCatching { appContext.unbindService(connection) }
                        }
                        if (resultJson != null) {
                            try {
                                val result = AssistantContractV2.json.decodeFromString(ProviderFactsResult.serializer(), resultJson)
                                if (cont.isActive) cont.resume(result)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to decode ProviderFactsResult from $targetPackage", e)
                                if (cont.isActive) cont.resume(null)
                            }
                        } else {
                            if (cont.isActive) cont.resume(null)
                        }
                    }

                    override fun onProviderError(queryId: String?, errorCode: Int, message: String?) {
                        if (!unbindOnce.getAndSet(true)) {
                            runCatching { appContext.unbindService(connection) }
                        }
                        Log.w(TAG, "Provider error from $targetPackage: code=$errorCode, message=$message")
                        if (cont.isActive) {
                            cont.resume(
                                ProviderFactsResult(
                                    sourceApp = source,
                                    facts = emptyList(),
                                    revision = "error",
                                    asOfTime = System.currentTimeMillis(),
                                    timezone = "UTC",
                                    warnings = listOf(message ?: "Provider error code $errorCode"),
                                ),
                            )
                        }
                    }
                }

                try {
                    val queryJson = AssistantContractV2.json.encodeToString(AggregateQuery.serializer(), query)
                    val qId = provider.queryFacts(queryJson, callback)
                    queryIdRef.set(qId)
                } catch (e: Exception) {
                    Log.e(TAG, "Exception calling queryFacts on $targetPackage", e)
                    if (!unbindOnce.getAndSet(true)) {
                        runCatching { appContext.unbindService(connection) }
                    }
                    if (cont.isActive) cont.resume(null)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {}

            override fun onBindingDied(name: ComponentName?) {
                if (!unbindOnce.getAndSet(true)) {
                    runCatching { appContext.unbindService(connection) }
                }
                if (cont.isActive) cont.resume(null)
            }
        }

        val bound = try {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "SecurityException/Exception binding to facts provider for $targetPackage", e)
            false
        }

        if (!bound) {
            if (cont.isActive) cont.resume(null)
            return@suspendCancellableCoroutine
        }

        cont.invokeOnCancellation {
            val qId = queryIdRef.get()
            val prov = providerRef.get()
            if (qId != null && prov != null) {
                runCatching { prov.cancelQuery(qId) }
            }
            if (!unbindOnce.getAndSet(true)) {
                runCatching { appContext.unbindService(connection) }
            }
        }
    }

    companion object {
        private const val TAG = "FactProviderClient"
    }
}
