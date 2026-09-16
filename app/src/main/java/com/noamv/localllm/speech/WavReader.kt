package com.noamv.localllm.speech

import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import android.os.ParcelFileDescriptor

class AudioFormatException(message: String) : IOException(message)

object WavReader {
    private const val MAX_SAMPLES = 960_000L

    fun read(fileDescriptor: FileDescriptor): FloatArray =
        ParcelFileDescriptor.AutoCloseInputStream(
            ParcelFileDescriptor.dup(fileDescriptor),
        ).use(::read)

    fun read(input: InputStream): FloatArray {
        val riff = ByteArray(12)
        input.readFully(riff, "RIFF header")
        if (riff.ascii(0, 4) != "RIFF") throw AudioFormatException("container must be RIFF")
        if (riff.ascii(8, 4) != "WAVE") throw AudioFormatException("format must be WAVE")

        var channels: Int? = null
        var sampleRate: Int? = null
        var bitsPerSample: Int? = null
        var audioFormat: Int? = null
        var data: ByteArray? = null

        while (data == null) {
            val chunkHeader = ByteArray(8)
            if (!input.readMaybe(chunkHeader)) break
            val chunkId = chunkHeader.ascii(0, 4)
            val chunkSize = chunkHeader.u32(4)
            when (chunkId) {
                "fmt " -> {
                    if (chunkSize < 16L) throw AudioFormatException("fmt chunk size $chunkSize")
                    val fmt = ByteArray(chunkSize.toInt())
                    input.readFully(fmt, "fmt chunk")
                    audioFormat = fmt.u16(0)
                    channels = fmt.u16(2)
                    sampleRate = fmt.i32(4)
                    bitsPerSample = fmt.u16(14)
                }
                "data" -> {
                    if (chunkSize / 2L > MAX_SAMPLES) {
                        throw AudioFormatException("duration exceeds 60 seconds")
                    }
                    data = ByteArray(chunkSize.toInt())
                    input.readFully(data, "data chunk")
                    if (chunkSize % 2L != 0L) input.skipFully(1)
                }
                else -> {
                    input.skipFully(chunkSize + (chunkSize and 1L))
                }
            }
        }

        if (audioFormat == null) throw AudioFormatException("missing fmt chunk")
        if (audioFormat != 1) throw AudioFormatException("audio format $audioFormat; expected PCM format 1")
        if (channels == null) throw AudioFormatException("missing channel count")
        if (channels != 1) throw AudioFormatException("channels $channels; expected 1")
        if (sampleRate == null) throw AudioFormatException("missing sample rate")
        if (sampleRate != 16_000) throw AudioFormatException("sample rate $sampleRate; expected 16000 Hz")
        if (bitsPerSample == null) throw AudioFormatException("missing bits per sample")
        if (bitsPerSample != 16) throw AudioFormatException("bits per sample $bitsPerSample; expected 16")
        val bytes = data ?: throw AudioFormatException("missing data chunk")
        if (bytes.size % 2 != 0) throw AudioFormatException("data size ${bytes.size}; expected 16-bit samples")

        val samples = FloatArray(bytes.size / 2)
        for (index in samples.indices) {
            val offset = index * 2
            val value = (bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)
            samples[index] = value.toShort().toInt() / 32768f
        }
        return samples
    }

    private fun ByteArray.ascii(offset: Int, length: Int): String =
        String(this, offset, length, Charsets.US_ASCII)

    private fun ByteArray.u16(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.i32(offset: Int): Int =
        u16(offset) or (u16(offset + 2) shl 16)

    private fun ByteArray.u32(offset: Int): Long =
        (i32(offset).toLong() and 0xffff_ffffL)

    private fun InputStream.readMaybe(buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val count = read(buffer, offset, buffer.size - offset)
            if (count < 0) return false
            if (count == 0) continue
            offset += count
        }
        return true
    }

    private fun InputStream.readFully(buffer: ByteArray, what: String) {
        if (!readMaybe(buffer)) throw AudioFormatException("truncated $what")
    }

    private fun InputStream.skipFully(count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (read() < 0) {
                throw AudioFormatException("truncated WAV chunk")
            } else {
                remaining--
            }
        }
    }
}
