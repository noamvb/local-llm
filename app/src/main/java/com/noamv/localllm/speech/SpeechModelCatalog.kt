package com.noamv.localllm.speech

import com.noamv.localllm.model.DownloadableModel

data class SpeechModelBuild(
    override val id: String,
    override val displayName: String,
    val repo: String,
    override val fileName: String,
    override val sizeBytes: Long,
    override val sha256: String,
) : DownloadableModel {
    override val url: String get() = "https://huggingface.co/$repo/resolve/main/$fileName?download=true"
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
    // small.en: 4.4 s for 2.4 s of audio on a Z Fold 7 (16 Sep 2026) and first-try
    // accurate where base.en (1.3 s) misheard; clients may still request base.en.
    val default: SpeechModelBuild = SMALL_EN
}
