package com.noamv.localllm.speech

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WavReaderTest {
    @Test
    fun reads1600SamplesFromPointOneSecondMonoPcm() {
        val samples = WavReader.read(ByteArrayInputStream(wav(channels = 1, sampleRate = 16_000, samples = 1_600)))
        assertEquals(1_600, samples.size)
        assertTrue(samples.all { it in -1f..1f })
    }

    @Test
    fun rejectsStereoAudioWithOffendingChannelField() {
        val error = runCatching {
            WavReader.read(ByteArrayInputStream(wav(channels = 2, sampleRate = 16_000, samples = 1_600)))
        }.exceptionOrNull()
        assertTrue(error is AudioFormatException)
        assertTrue(error?.message?.contains("channels") == true)
    }

    @Test
    fun rejects44100HzAudioWithOffendingSampleRateField() {
        val error = runCatching {
            WavReader.read(ByteArrayInputStream(wav(channels = 1, sampleRate = 44_100, samples = 1_600)))
        }.exceptionOrNull()
        assertTrue(error is AudioFormatException)
        assertTrue(error?.message?.contains("sample rate") == true)
    }

    @Test
    fun rejects61SecondHeaderBeforeReadingItsMissingData() {
        val error = runCatching {
            WavReader.read(ByteArrayInputStream(wavHeader(channels = 1, sampleRate = 16_000, dataBytes = 960_001L * 2L)))
        }.exceptionOrNull()
        assertTrue(error is AudioFormatException)
        assertTrue(error?.message?.contains("60 seconds") == true)
    }

    private fun wav(channels: Int, sampleRate: Int, samples: Int): ByteArray {
        val data = ByteArray(samples * channels * 2)
        for (sample in 0 until samples) {
            val value = (sin(2 * PI * sample / 160.0) * 16_000).toInt().toShort().toInt()
            repeat(channels) { channel ->
                val offset = (sample * channels + channel) * 2
                data[offset] = value.toByte()
                data[offset + 1] = (value shr 8).toByte()
            }
        }
        return wavHeader(channels, sampleRate, data.size.toLong(), data)
    }

    private fun wavHeader(channels: Int, sampleRate: Int, dataBytes: Long, data: ByteArray = ByteArray(0)): ByteArray {
        val output = ByteArrayOutputStream()
        output.writeAscii("RIFF")
        output.writeIntLE((36 + dataBytes).toInt())
        output.writeAscii("WAVE")
        output.writeAscii("fmt ")
        output.writeIntLE(16)
        output.writeShortLE(1)
        output.writeShortLE(channels)
        output.writeIntLE(sampleRate)
        output.writeIntLE(sampleRate * channels * 2)
        output.writeShortLE(channels * 2)
        output.writeShortLE(16)
        output.writeAscii("data")
        output.writeIntLE(dataBytes.toInt())
        output.write(data)
        return output.toByteArray()
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) = write(value.toByteArray(Charsets.US_ASCII))
    private fun ByteArrayOutputStream.writeShortLE(value: Int) { write(value and 0xff); write((value ushr 8) and 0xff) }
    private fun ByteArrayOutputStream.writeIntLE(value: Int) {
        write(value and 0xff); write((value ushr 8) and 0xff); write((value ushr 16) and 0xff); write((value ushr 24) and 0xff)
    }
}
