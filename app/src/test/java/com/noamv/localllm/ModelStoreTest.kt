package com.noamv.localllm

import com.noamv.localllm.model.ModelBackend
import com.noamv.localllm.model.ModelBuild
import com.noamv.localllm.model.ModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Covers the download invariants the insight cards depend on: an interrupted download
 * must leave a partial file to resume from, and a model only counts as installed once
 * it is complete.
 *
 * Deleting that partial file on cancellation is what made a two-gigabyte download
 * restart from zero every time the app was closed, and left every client reporting no
 * model on disk.
 */
class ModelStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val payload = ByteArray(64 * 1024) { (it % 251).toByte() }

    private val build = ModelBuild(
        id = "test-build",
        displayName = "Test build",
        repo = "example/test",
        fileName = "test.litertlm",
        sizeBytes = payload.size.toLong(),
        sha256 = payload.sha256(),
        backend = ModelBackend.GPU,
    )

    private val partFile: File get() = File(temporaryFolder.root, "${build.fileName}.part")

    @Test
    fun `a completed download verifies and installs`() = runTest {
        val store = ModelStore(temporaryFolder.root, clientServing(payload))

        val file = store.ensureAvailable(build)

        assertTrue(store.isInstalled(build))
        assertEquals(payload.size.toLong(), file.length())
        assertFalse("the partial file should be renamed away", partFile.exists())
    }

    @Test
    fun `a cancelled download keeps its partial file for a later resume`() = runTest {
        val started = CompletableDeferred<Unit>()
        val store = ModelStore(temporaryFolder.root, clientServing(payload, onFirstChunk = started))

        val job = launch { store.ensureAvailable(build) }
        started.await()
        job.cancelAndJoin()

        assertFalse("an interrupted download must not count as installed", store.isInstalled(build))
        assertTrue("the partial file is what makes a resume possible", partFile.exists())
        assertTrue("it should hold the bytes fetched so far", partFile.length() > 0)
    }

    @Test
    fun `a download whose bytes do not match the digest is discarded`() = runTest {
        val store = ModelStore(temporaryFolder.root, clientServing(ByteArray(payload.size) { 7 }))

        val failure = runCatching { store.ensureAvailable(build) }.exceptionOrNull()

        assertTrue("verification failure should surface", failure != null)
        assertFalse(store.isInstalled(build))
        assertFalse("a file that failed verification must not be left to resume", partFile.exists())
    }

    @Test
    fun `a file of the wrong length does not count as installed`() {
        val store = ModelStore(temporaryFolder.root)
        store.fileFor(build).writeBytes(payload.copyOf(payload.size - 1))

        assertFalse(store.isInstalled(build))
    }

    /**
     * An OkHttp client that answers from memory, so these tests need neither a network
     * nor a server. The body is served in small chunks so that a cancellation is noticed
     * part-way through rather than after the whole payload has arrived.
     */
    private fun clientServing(
        body: ByteArray,
        onFirstChunk: CompletableDeferred<Unit>? = null,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                val stream = ChunkedStream(body, onFirstChunk)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        stream.source().buffer()
                            .asResponseBody("application/octet-stream".toMediaType(), body.size.toLong()),
                    )
                    .build()
            },
        )
        .build()
}

/**
 * Serves [body] a chunk at a time, pausing briefly between chunks.
 *
 * The pause matters: ModelStore checks for cancellation once per read, so a stream that
 * returns everything at once would finish before a test could interrupt it.
 */
private class ChunkedStream(
    private val body: ByteArray,
    private val onFirstChunk: CompletableDeferred<Unit>?,
) : InputStream() {

    private var position = 0

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 0xff
    }

    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        if (position >= body.size) return -1
        if (position > 0 || onFirstChunk == null) Thread.sleep(SETTLE_MILLIS)
        val count = minOf(length, CHUNK_BYTES, body.size - position)
        body.copyInto(destination, offset, position, position + count)
        position += count
        onFirstChunk?.complete(Unit)
        return count
    }

    private companion object {
        const val CHUNK_BYTES = 4096
        const val SETTLE_MILLIS = 5L
    }
}

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
