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
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noamv.localllm.contract.EngineState

/**
 * The manager UI. This app has no chat surface by design: it exists to hold the model
 * and answer other apps. The screen shows what is installed, what state the engine is
 * in, and lets the owner download or remove the model and run a self-test.
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

        Button(
            onClick = viewModel::prepare,
            enabled = status.state != EngineState.DOWNLOADING,
        ) {
            Text(prepareButtonLabel(status))
        }

        Button(onClick = viewModel::runSelfTest, enabled = status.state == EngineState.READY) {
            Text("Run a self-test summary")
        }

        selfTest?.let {
            Text("Self-test output", style = MaterialTheme.typography.titleMedium)
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
