package com.noamv.localllm.ui

import com.noamv.localllm.contract.EngineState
import com.noamv.localllm.contract.EngineStatus
import com.noamv.localllm.engine.EngineTimings
import java.util.Locale

/**
 * Turns the wire-level [EngineStatus] into text a human can read without decoding an
 * enum name.
 *
 * [EngineState.MODEL_MISSING] means "no engine is loaded right now", not "the model
 * file is gone". Android kills this process whenever it is backgrounded, so the owner
 * hits this state every time they return to the app after switching away -- with the
 * 2+ GB model still fully verified on disk. Showing the raw enum name here reads as
 * "your model vanished" and has been mistaken for exactly that. [EngineStatus.modelDownloaded]
 * already carries the real answer; these functions just say it in words instead of
 * making the screen's reader decode a constant.
 *
 * Kept out of MainActivity.kt, which only holds `private` composables, so a unit test
 * can call these directly.
 */

/** The human-readable state, distinct from whether the model file is on disk. */
internal fun engineStateLabel(status: EngineStatus): String = when (status.state) {
    EngineState.READY -> "Ready"
    EngineState.DOWNLOADING -> "Downloading"
    EngineState.INITIALISING -> "Loading"
    // UNSUPPORTED is the one failure the owner most needs the file's fate for: it is
    // published when every candidate build failed to start, and prepare() deliberately
    // reports modelDownloaded truthfully there so a survivable file is not mistaken for
    // a lost one.
    EngineState.UNSUPPORTED ->
        if (status.modelDownloaded) "Could not start (model is on this device)" else "Could not start"
    EngineState.MODEL_MISSING ->
        if (status.modelDownloaded) "Downloaded, not loaded" else "No model downloaded"
}

/** Whether the model file itself is on this device, stated plainly and on its own. */
internal fun modelFileText(status: EngineStatus): String =
    if (status.modelDownloaded) "on this device" else "not downloaded"

/**
 * The full status line for the manager screen: the human-readable state, plus
 * [EngineStatus.detail] when the service has something to say beyond it.
 *
 * `detail` frequently restates the state in more words — the engine sets "Ready" at
 * READY and "Loading Gemma 4 E2B (GPU)" at INITIALISING — so concatenating the two
 * unconditionally produces "Ready Ready" and "Loading Loading Gemma 4 E2B (GPU)". When
 * detail already opens with the label it is the more specific of the two and wins
 * outright; otherwise it carries something new, such as a failure reason, and is kept.
 */
internal fun engineStatusText(status: EngineStatus): String {
    val label = engineStateLabel(status)
    val detail = status.detail.trim()
    return when {
        detail.isEmpty() -> label
        detail.startsWith(label, ignoreCase = true) -> detail
        else -> "$label — $detail"
    }
}

/**
 * What tapping the prepare button will actually do. A client that only checked `state`
 * would offer to "download" a model that is already sitting on disk in every state but
 * READY, which is the other half of the same bug: [EngineStatus.modelDownloaded] is
 * what decides whether a download is really needed.
 */
internal fun prepareButtonLabel(status: EngineStatus): String = when {
    status.state == EngineState.READY -> "Reload model"
    status.modelDownloaded -> "Load model"
    else -> "Download and load model"
}

/** The duration and backend of the most recent model load. */
internal fun lastLoadText(timings: EngineTimings): String =
    timings.lastInitMillis?.let { millis ->
        String.format(
            Locale.ROOT,
            "%.1f s on %s",
            millis / 1000.0,
            timings.lastInitBackend ?: "unknown",
        )
    } ?: "not loaded yet"

/** Time-to-first-token and warm/cold status of the most recent generation. */
internal fun lastResponseText(timings: EngineTimings): String =
    timings.lastTimeToFirstTokenMillis?.let { millis ->
        val formattedSeconds = String.format(Locale.ROOT, "%.1f s", millis / 1000.0)
        val mode = if (timings.lastRequestWasWarm == true) "already loaded" else "cold start"
        "first word after $formattedSeconds ($mode)"
    } ?: "no request yet"

