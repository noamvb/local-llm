package com.noamv.localllm.speech

data class SpeechModelBuild(
    val id: String,
    val displayName: String,
    val repo: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    val url: String get() = "https://huggingface.co/$repo/resolve/main/$fileName?download=true"
}

object SpeechModelCatalog {
    val BASE_EN = SpeechModelBuild(
        id = "whisper-base-en",
        displayName = "Whisper base (English)",
        repo = "ggerganov/whisper.cpp",
        fileName = "ggml-base.en.bin",
        sizeBytes = 147964211L,
        sha256 = "a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002",
    )

    val SMALL_EN = SpeechModelBuild(
        id = "whisper-small-en",
        displayName = "Whisper small (English)",
        repo = "ggerganov/whisper.cpp",
        fileName = "ggml-small.en.bin",
        sizeBytes = 487614201L,
        sha256 = "c6138d6d58ecc8322097e0f987c32f1be8bb0a18532a3f88f734d1bbf9c41e5d",
    )

    val all: List<SpeechModelBuild> = listOf(BASE_EN, SMALL_EN)
    fun byId(id: String): SpeechModelBuild? = all.firstOrNull { it.id == id }
    val default: SpeechModelBuild = BASE_EN
}
