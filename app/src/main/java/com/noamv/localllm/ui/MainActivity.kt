package com.noamv.localllm.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noamv.localllm.contract.EngineState

/**
 * The manager UI. This app has no chat surface by design: it exists to hold the model
 * and answer other apps. The screen shows what is installed, what state the engine is
 * in, and lets the owner download or load the model and run a self-test.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 15+ draws apps edge to edge, so insets must be handled explicitly
        // or the heading sits underneath the status bar.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ManagerScreen(viewModel = viewModel(factory = ManagerViewModel.Factory))
                }
            }
        }
    }
}

@Composable
private fun ManagerScreen(viewModel: ManagerViewModel) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val timings by viewModel.timings.collectAsStateWithLifecycle()
    val selfTest by viewModel.selfTest.collectAsStateWithLifecycle()
    val transfer by viewModel.transferStatus.collectAsStateWithLifecycle()
    val transferCommandMessage by viewModel.transferCommandMessage.collectAsStateWithLifecycle()
    var confirmMetered by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("LocalLLM", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Holds an on-device language model so your other apps can turn their own " +
                "statistics into readable summaries. Nothing is sent off this phone.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Text("Model: ${viewModel.modelName}", style = MaterialTheme.typography.titleMedium)
        Text("Download size: ${"%.2f".format(viewModel.modelSizeGb)} GB")
        Text("Chipset: ${viewModel.chipset ?: "unknown"}")
        Text("Model file: ${modelFileText(status)}")
        Text("Status: ${engineStatusText(status)}")
        Text("Last load: ${lastLoadText(timings)}")
        Text("Last response: ${lastResponseText(timings)}")

        if (status.state == EngineState.DOWNLOADING && status.downloadPercent >= 0) {
            LinearProgressIndicator(
                progress = { status.downloadPercent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${status.downloadPercent}%")
        }

        if (transfer.sessionId != 0L) {
            Text("Transfer stage: ${transfer.phase.name.lowercase().replace('_', ' ')}")
            if (transfer.bytes.expectedBytes > 0L) {
                Text("Expected: ${transfer.bytes.expectedBytes} bytes")
                Text("Partial at start: ${transfer.bytes.partialBytesAtStart} bytes")
                Text("Transferred this run: ${transfer.bytes.transferredThisRunBytes} bytes")
                Text("Remaining: ${transfer.bytes.remainingBytes} bytes")
            }
            transfer.failureCategory?.let {
                Text("Failure category: ${it.name.lowercase().replace('_', ' ')}")
            }
            transfer.blockReason?.let {
                Text("Network result: ${it.name.lowercase().replace('_', ' ')}")
            }
            transfer.stopReason?.let {
                Text("Stopped because: ${it.name.lowercase().replace('_', ' ')}")
            }
        }

        Button(
            onClick = viewModel::prepare,
            enabled = !transfer.isActive && !status.modelDownloaded,
        ) {
            Text("Download on unmetered Wi-Fi")
        }

        OutlinedButton(
            onClick = { confirmMetered = true },
            enabled = !transfer.isActive && !status.modelDownloaded,
        ) {
            Text("Use mobile or metered network once")
        }

        if (transfer.isActive) {
            OutlinedButton(onClick = viewModel::cancelTransfer) {
                Text("Cancel transfer")
            }
        }
        if (status.modelDownloaded &&
            status.state == EngineState.MODEL_MISSING &&
            !transfer.isActive
        ) {
            Button(onClick = viewModel::loadInstalledModel) {
                Text("Load installed model")
            }
        }

        transferCommandMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Button(onClick = viewModel::runSelfTest, enabled = status.state == EngineState.READY) {
            Text("Run a self-test summary")
        }

        selfTest?.let {
            Text("Self-test output", style = MaterialTheme.typography.titleMedium)
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (confirmMetered) {
        AlertDialog(
            onDismissRequest = { confirmMetered = false },
            title = { Text("Use mobile or metered data once?") },
            text = {
                Text(
                    "This one transfer may use a metered connection. " +
                        "The setting is not saved for later transfers.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmMetered = false
                    viewModel.prepareOnMeteredNetworkOnce()
                }) {
                    Text("Use once")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmMetered = false }) {
                    Text("Keep Wi-Fi only")
                }
            },
        )
    }
}
