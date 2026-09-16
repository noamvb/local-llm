package com.noamv.localllm.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechModelCatalogTest {
    @Test
    fun hasExactlyTheTwoEnglishModels() {
        assertEquals(listOf("whisper-base-en", "whisper-small-en"), SpeechModelCatalog.all.map { it.id })
    }

    @Test
    fun pinsBothSha256Digests() {
        assertEquals("a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002", SpeechModelCatalog.BASE_EN.sha256)
        assertEquals("c6138d6d58ecc8322097e0f987c32f1be8bb0a18532a3f88f734d1bbf9c41e5d", SpeechModelCatalog.SMALL_EN.sha256)
    }

    @Test
    fun buildsExactBaseDownloadUrl() {
        assertEquals("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin?download=true", SpeechModelCatalog.BASE_EN.url)
    }
}
