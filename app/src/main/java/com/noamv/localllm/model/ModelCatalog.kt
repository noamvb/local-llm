package com.noamv.localllm.model

/**
 * Which backend a model build is compiled for.
 *
 * NPU builds are compiled per chipset, so a given NPU model only runs on the SoC named
 * in [ModelBuild.requiresBoard].
 */
enum class ModelBackend { CPU, GPU, NPU }

/**
 * A downloadable model file.
 *
 * @param sha256         the file digest, taken from the HuggingFace LFS object id
 * @param requiresBoard  ro.board.platform value this build requires, null if portable
 */
data class ModelBuild(
    val id: String,
    val displayName: String,
    val repo: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val backend: ModelBackend,
    val requiresBoard: String? = null,
) {
    /**
     * Direct download URL. These repositories are public and ungated and Gemma 4 is
     * Apache 2.0 licensed, so no HuggingFace token or licence click-through is needed.
     */
    val url: String get() = "https://huggingface.co/$repo/resolve/main/$fileName?download=true"

    val sizeGb: Double get() = sizeBytes / 1_000_000_000.0
}

/**
 * The model builds this app knows how to fetch.
 *
 * Digests and sizes were read from the HuggingFace API on 2026-08-18. If a repository
 * republishes a file the download will fail verification rather than silently install a
 * different model, which is the intended behaviour.
 */
object ModelCatalog {

    /** Snapdragon 8 Elite, the SoC in the Galaxy Z Fold 7 and S25 family. */
    const val BOARD_SM8750 = "sm8750"

    val E2B_GPU = ModelBuild(
        id = "gemma-4-E2B-it-gpu",
        displayName = "Gemma 4 E2B (GPU)",
        repo = "litert-community/gemma-4-E2B-it-litert-lm",
        fileName = "gemma-4-E2B-it-gpu.litertlm",
        sizeBytes = 2_008_432_640L,
        sha256 = "a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5",
        backend = ModelBackend.GPU,
    )

    val E2B_NPU_SM8750 = ModelBuild(
        id = "gemma-4-E2B-it-npu-sm8750",
        displayName = "Gemma 4 E2B (NPU, Snapdragon 8 Elite)",
        repo = "litert-community/gemma-4-E2B-it-litert-lm",
        fileName = "gemma-4-E2B-it_qualcomm_sm8750.litertlm",
        sizeBytes = 3_016_294_400L,
        sha256 = "41dd675fbe735b6029012b5576a5716bac614fd8156de0128db4c9dff3cebd4e",
        backend = ModelBackend.NPU,
        requiresBoard = BOARD_SM8750,
    )

    val E2B_CPU = ModelBuild(
        id = "gemma-4-E2B-it",
        displayName = "Gemma 4 E2B (CPU)",
        repo = "litert-community/gemma-4-E2B-it-litert-lm",
        fileName = "gemma-4-E2B-it.litertlm",
        sizeBytes = 2_588_147_712L,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        backend = ModelBackend.CPU,
    )

    val E4B_GPU = ModelBuild(
        id = "gemma-4-E4B-it-gpu",
        displayName = "Gemma 4 E4B (GPU)",
        repo = "litert-community/gemma-4-E4B-it-litert-lm",
        fileName = "gemma-4-E4B-it-gpu.litertlm",
        sizeBytes = 2_969_059_328L,
        sha256 = "4912bb5a9c30993c51a7711f763212077458529312175df0573a78323a2bb7ff",
        backend = ModelBackend.GPU,
    )

    val all: List<ModelBuild> = listOf(E2B_GPU, E2B_NPU_SM8750, E2B_CPU, E4B_GPU)

    fun byId(id: String): ModelBuild? = all.firstOrNull { it.id == id }

    /**
     * Picks a build for the running device.
     *
     * A matching chipset is necessary but NOT sufficient to use an NPU build. LiteRT-LM
     * dispatches NPU work through vendor libraries (libQnnHtp*.so for Qualcomm) that are
     * not part of the litertlm-android artifact. Google distributes them only inside the
     * QAIRT SDK, to be built with Bazel and bundled by the app. This app does not ship
     * them, so [npuDispatchAvailable] is false and the portable GPU build is used.
     *
     * Selecting an NPU build without those libraries does not fail at load time. It fails
     * several seconds later, during the inference warm-up, with "No usable Dispatch
     * runtime found" — which is why the check is on the libraries rather than the SoC.
     */
    fun defaultFor(board: String?, npuDispatchAvailable: Boolean = false): ModelBuild {
        if (!npuDispatchAvailable) return E2B_GPU
        val normalised = board?.lowercase().orEmpty()
        return all.firstOrNull { it.requiresBoard != null && normalised.contains(it.requiresBoard) }
            ?: E2B_GPU
    }

    /** Builds to try, in order, when [primary] cannot be initialised. */
    fun fallbacksFor(primary: ModelBuild): List<ModelBuild> =
        listOf(primary) + listOf(E2B_GPU, E2B_CPU).filter { it.id != primary.id }
}
