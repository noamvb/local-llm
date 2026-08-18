package com.noamv.localllm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import com.noamv.localllm.LocalLlmApplication
import com.noamv.localllm.contract.EngineStatus
import com.noamv.localllm.contract.Fact
import com.noamv.localllm.contract.InsightRequest
import com.noamv.localllm.contract.InsightTask
import com.noamv.localllm.contract.Period
import com.noamv.localllm.engine.LiteRtEngine
import com.noamv.localllm.engine.LlmEngine
import com.noamv.localllm.model.ModelBuild
import com.noamv.localllm.model.ModelCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ManagerViewModel(
    private val engine: LlmEngine,
    private val build: ModelBuild,
) : ViewModel() {

    val status: StateFlow<EngineStatus> = engine.status

    private val _selfTest = MutableStateFlow<String?>(null)
    val selfTest: StateFlow<String?> = _selfTest.asStateFlow()

    val modelName: String get() = build.displayName
    val modelSizeGb: Double get() = build.sizeGb
    val chipset: String? get() = LiteRtEngine.boardPlatform()

    fun prepare() {
        viewModelScope.launch {
            runCatching { engine.prepare() }
                .onFailure { _selfTest.value = "Preparation failed: ${it.message}" }
        }
    }

    /**
     * Exercises the whole pipeline with fixed facts, so the owner can tell the model is
     * working without needing a client app installed.
     */
    fun runSelfTest() {
        viewModelScope.launch {
            _selfTest.value = ""
            val request = InsightRequest(
                clientId = "localllm-selftest",
                task = InsightTask.PERIOD_SUMMARY,
                subject = "a self-test of the local model",
                period = Period(label = "the last 7 days"),
                facts = listOf(
                    Fact("Entries recorded", "12"),
                    Fact("Busiest day", "Tuesday", "4 entries"),
                    Fact("Average per day", "1.7"),
                ),
                maxWords = 60,
            )
            runCatching {
                engine.generate(request).collect { fragment ->
                    _selfTest.value = (_selfTest.value ?: "") + fragment
                }
            }.onFailure { _selfTest.value = "Self-test failed: ${it.message}" }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as LocalLlmApplication
                ManagerViewModel(
                    engine = app.engine,
                    build = ModelCatalog.defaultFor(LiteRtEngine.boardPlatform()),
                )
            }
        }
    }
}
