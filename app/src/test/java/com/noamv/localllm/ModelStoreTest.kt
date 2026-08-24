package com.noamv.localllm

import com.noamv.localllm.model.ModelBackend
import com.noamv.localllm.model.ModelBuild
import com.noamv.localllm.model.DownloadProgress
import com.noamv.localllm.model.IncompleteModelDownloadException
import com.noamv.localllm.model.InsufficientModelStorageException
import com.noamv.localllm.model.InvalidModelRangeException
import com.noamv.localllm.model.ModelChecksumException
import com.noamv.localllm.model.ModelDownloadHttpException
import com.noamv.localllm.model.ModelDownloadResponseLimitException
import com.noamv.localllm.model.ModelDownloadTooLargeException
import com.noamv.localllm.model.ModelNetworkException
import com.noamv.localllm.model.ModelPromotionException
import com.noamv.localllm.model.ModelStore
import com.noamv.localllm.model.ModelStoreTransferStage
import com.noamv.localllm.model.ModelStorageException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
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
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

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
    fun `a complete partial verifies and promotes without a network request`() = runTest {
        partFile.writeBytes(payload)
        val calls = AtomicInteger()
        val validations = AtomicInteger()
        val store = ModelStore(
            temporaryFolder.root,
            scriptedClient { request, _ ->
                calls.incrementAndGet()
                response(request, 500, ByteArray(0))
            },
        )

        val file = store.ensureAvailableWithTransport(
            build = build,
            validateNetwork = { validations.incrementAndGet() },
        )

        assertEquals(0, calls.get())
        assertEquals(0, validations.get())
        assertTrue(store.isInstalled(build))
        assertEquals(payload.toList(), file.readBytes().toList())
        assertFalse(partFile.exists())
    }

    @Test
    fun `a valid partial sends Range and appends a 206 response`() = runTest {
        val prefixBytes = 16 * 1024
        partFile.writeBytes(payload.copyOf(prefixBytes))
        val requests = mutableListOf<Request>()
        val store = ModelStore(
            temporaryFolder.root,
            scriptedClient(requests) { request, _ ->
                response(
                    request = request,
                    code = 206,
                    body = payload.copyOfRange(prefixBytes, payload.size),
                    contentRange = "bytes $prefixBytes-${payload.lastIndex}/${payload.size}",
                )
            },
        )

        val file = store.ensureAvailable(build)

        assertEquals(listOf("bytes=$prefixBytes-"), requests.map { it.header("Range") })
        assertEquals(payload.toList(), file.readBytes().toList())
        assertTrue(store.isInstalled(build))
    }

    @Test
    fun `bounded 206 chunks continue within one ensure call`() = runTest {
        val chunkBytes = 16 * 1024
        val requests = mutableListOf<Request>()
        val store = ModelStore(
            temporaryFolder.root,
            scriptedClient(requests) { request, _ ->
                val start = request.header("Range")
                    ?.removePrefix("bytes=")
                    ?.removeSuffix("-")
                    ?.toInt()
                    ?: 0
                val endExclusive = minOf(start + chunkBytes, payload.size)
                response(
                    request = request,
                    code = 206,
                    body = payload.copyOfRange(start, endExclusive),
                    contentRange = "bytes $start-${endExclusive - 1}/${payload.size}",
                )
            },
        )

        val file = store.ensureAvailable(build)

        assertEquals(4, requests.size)
        assertEquals(
            listOf(null, "bytes=16384-", "bytes=32768-", "bytes=49152-"),
            requests.map { it.header("Range") },
        )
        assertEquals(payload.toList(), file.readBytes().toList())
        assertFalse(partFile.exists())
    }

    @Test
    fun `network lease is revalidated before every bounded response`() = runTest {
        val chunkBytes = 16 * 1024
        val validations = AtomicInteger()
        val requests = mutableListOf<Request>()
        val store = ModelStore(
            temporaryFolder.root,
            scriptedClient(requests) { request, _ ->
                val start = request.header("Range")
                    ?.removePrefix("bytes=")
                    ?.removeSuffix("-")
                    ?.toInt()
                    ?: 0
                val endExclusive = minOf(start + chunkBytes, payload.size)
                response(
                    request = request,
                    code = 206,
                    body = payload.copyOfRange(start, endExclusive),
                    contentRange = "bytes $start-${endExclusive - 1}/${payload.size}",
                )
            },
        )

        store.ensureAvailableWithTransport(
            build = build,
            validateNetwork = { validations.incrementAndGet() },
        )

        assertEquals(4, requests.size)
        assertEquals(requests.size, validations.get())
    }

    @Test
    fun `closed network lease prevents creation of the next chunk request`() = runTest {
        val chunkBytes = 16 * 1024
        val validations = AtomicInteger()
        val requests = mutableListOf<Request>()
        val store = ModelStore(
            temporaryFolder.root,
            scriptedClient(requests) { request, _ ->
                response(
                    request = request,
                    code = 206,
                    body = payload.copyOfRange(0, chunkBytes),
                    contentRange = "bytes 0-${chunkBytes - 1}/${payload.size}",
                )
            },
        )

        val failure = runCatching {
            store.ensureAvailableWithTransport(
                build = build,
                validateNetwork = {
                    if (validations.incrementAndGet() > 1) {
                        throw IOException("lease closed")
                    }
                },
            )
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(2, validations.get())
        assertEquals(1, requests.size)
        assertEquals(chunkBytes.toLong(), partFile.length())
    }

    @Test
    fun `transfer stages expose verification and atomic installation`() = runTest {
        val stages = mutableListOf<ModelStoreTransferStage>()
        val store = ModelStore(temporaryFolder.root, clientServing(payload))

        store.ensureAvailableWithTransport(build = build, onStage = stages::add)

        assertEquals(
            listOf(
                ModelStoreTransferStage.DOWNLOADING,
                ModelStoreTransferStage.VERIFYING,
                ModelStoreTransferStage.INSTALLING,
            ),
            stages,
        )
        assertTrue(store.isInstalled(build))
        assertFalse(partFile.exists())
    }

    @Test
    fun `partial response loops stop at the bounded response limit`() = runTest {
        val manyChunks = ByteArray(600) { (it % 251).toByte() }
        val manyChunkBuild = buildFor(manyChunks, "many-chunks")
        val calls = AtomicInteger()
        val store = ModelStore(
            temporaryFolder.root,
            scriptedClient { request, _ ->
                val start = request.header("Range")
                    ?.removePrefix("bytes=")
                    ?.removeSuffix("-")
                    ?.toInt()
                    ?: 0
                calls.incrementAndGet()
                response(
                    request = request,
                    code = 206,
                    body = byteArrayOf(manyChunks[start]),
                    contentRange = "bytes $start-$start/${manyChunks.size}",
                )
            },
        )

        val failure = runCatching { store.ensureAvailable(manyChunkBuild) }.exceptionOrNull()

        assertTrue(failure is ModelDownloadResponseLimitException)
        assertEquals(512, calls.get())
        assertEquals(512, store.partFor(manyChunkBuild).length())
        assertFalse(store.isInstalled(manyChunkBuild))
    }

    @Test
    fun `a server that ignores Range restarts safely from byte zero`() = runTest {
        val prefixBytes = 12 * 1024
        partFile.writeBytes(payload.copyOf(prefixBytes))
        val requests = mutableListOf<Request>()
        val store = ModelStore(
            temporaryFolder.root,
            scriptedClient(requests) { request, _ -> response(request, 200, payload) },
        )

        val file = store.ensureAvailable(build)

        assertEquals("bytes=$prefixBytes-", requests.single().header("Range"))
        assertEquals(payload.toList(), file.readBytes().toList())
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
    fun `cancelling a blocked call cancels OkHttp immediately and preserves partial bytes`() = runBlocking {
        val prefix = payload.copyOf(7 * 1024)
        partFile.writeBytes(prefix)
        val callStarted = CompletableDeferred<Unit>()
        val callCancelled = CompletableDeferred<Unit>()
        val store = ModelStore(
            temporaryFolder.root,
            clientBlockingUntilCancelled(callStarted, callCancelled),
        )

        val transfer = launch { store.ensureAvailable(build) }
        callStarted.await()
        withTimeout(2_000) { transfer.cancelAndJoin() }
        withTimeout(2_000) { callCancelled.await() }

        assertTrue(transfer.isCancelled)
        assertEquals(prefix.toList(), partFile.readBytes().toList())
        assertFalse(store.isInstalled(build))
    }

    @Test
    fun `installed artifact checks do not wait behind an active transfer`() = runBlocking {
        val prefix = payload.copyOf(7 * 1024)
        partFile.writeBytes(prefix)
        val callStarted = CompletableDeferred<Unit>()
        val callCancelled = CompletableDeferred<Unit>()
        val store = ModelStore(
            temporaryFolder.root,
            clientBlockingUntilCancelled(callStarted, callCancelled),
        )

        val transfer = launch { store.ensureAvailable(build) }
        callStarted.await()

        val installed = withTimeout(1_000) {
            kotlinx.coroutines.withContext(Dispatchers.Default) { store.isInstalled(build) }
        }

        assertFalse(installed)
        transfer.cancelAndJoin()
        withTimeout(2_000) { callCancelled.await() }
        assertEquals(prefix.toList(), partFile.readBytes().toList())
    }

    @Test
    fun `concurrent ensure calls share one serialized transfer`() = runTest {
        val callStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val store = ModelStore(
            temporaryFolder.root,
            scriptedClient { request, _ ->
                calls.incrementAndGet()
                callStarted.complete(Unit)
                runBlocking { releaseResponse.await() }
                response(request, 200, payload)
            },
        )

        val first = launch { store.ensureAvailable(build) }
        callStarted.await()
        val second = launch { store.ensureAvailable(build) }
        releaseResponse.complete(Unit)
        first.join()
        second.join()

        assertFalse(first.isCancelled)
        assertFalse(second.isCancelled)
        assertEquals(1, calls.get())
        assertTrue(store.isInstalled(build))
    }

    @Test
    fun `delete cancels an active transfer then removes its retained partial`() = runBlocking {
        val prefix = payload.copyOf(9 * 1024)
        partFile.writeBytes(prefix)
        val callStarted = CompletableDeferred<Unit>()
        val callCancelled = CompletableDeferred<Unit>()
        val store = ModelStore(
            temporaryFolder.root,
            clientBlockingUntilCancelled(callStarted, callCancelled),
        )

        val transfer = launch { store.ensureAvailable(build) }
        callStarted.await()
        withTimeout(2_000) { store.delete(build) }
        withTimeout(2_000) { callCancelled.await() }
        transfer.join()

        assertTrue(transfer.isCancelled)
        assertFalse(store.fileFor(build).exists())
        assertFalse(partFile.exists())
    }

    @Test
    fun `a cancelled transfer resumes end to end and installs the verified file`() = runTest {
        val started = CompletableDeferred<Unit>()
        val requests = mutableListOf<Request>()
        val client = scriptedClient(requests) { request, call ->
            if (call == 0) {
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("initial transfer")
                    .body(
                        ChunkedStream(payload, started).source().buffer()
                            .asResponseBody(
                                "application/octet-stream".toMediaType(),
                                payload.size.toLong(),
                            ),
                    )
                    .build()
            } else {
                val resumeAt = request.header("Range")
                    ?.removePrefix("bytes=")
                    ?.removeSuffix("-")
                    ?.toInt()
                    ?: error("resume request must carry a Range header")
                response(
                    request = request,
                    code = 206,
                    body = payload.copyOfRange(resumeAt, payload.size),
                    contentRange = "bytes $resumeAt-${payload.lastIndex}/${payload.size}",
                )
            }
        }
        val store = ModelStore(temporaryFolder.root, client)

        val interrupted = launch { store.ensureAvailable(build) }
        started.await()
        interrupted.cancelAndJoin()
        val retainedBytes = partFile.length()
        val file = store.ensureAvailable(build)

        assertTrue(retainedBytes in 1 until payload.size.toLong())
        assertEquals(2, requests.size)
        assertEquals("bytes=$retainedBytes-", requests[1].header("Range"))
        assertEquals(payload.toList(), file.readBytes().toList())
        assertFalse(partFile.exists())
    }

    @Test
    fun `HTTP 416 resets a stale partial and retries once from zero`() = runTest {
        val prefixBytes = 8 * 1024
        partFile.writeBytes(payload.copyOf(prefixBytes))
        val requests = mutableListOf<Request>()
        val progress = mutableListOf<DownloadProgress>()
        val store = ModelStore(
            temporaryFolder.root,
            scriptedClient(requests) { request, call ->
                if (call == 0) {
                    response(request, 416, ByteArray(0))
                } else {
                    response(request, 200, payload)
                }
            },
        )

        val file = store.ensureAvailable(build, progress::add)

        assertEquals(2, requests.size)
        assertEquals("bytes=$prefixBytes-", requests[0].header("Range"))
        assertEquals(null, requests[1].header("Range"))
        assertEquals(payload.toList(), file.readBytes().toList())
        progress.zipWithNext().forEach { (before, after) ->
            assertTrue("a server reset must not move progress backwards", after.bytesRead >= before.bytesRead)
            assertTrue(after.percent >= before.percent)
        }
        assertTrue(progress.any { it.actualBytes == 0L && it.bytesRead == prefixBytes.toLong() })
        assertEquals(payload.size.toLong(), progress.last().transferredThisRunBytes)
    }

    @Test
    fun `many writes within one percent count network bytes exactly once`() = runTest {
        val largePayload = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val largeBuild = buildFor(largePayload, "subpercent-accounting")
        val progress = mutableListOf<DownloadProgress>()
        val store = ModelStore(
            temporaryFolder.root,
            scriptedClient { request, _ ->
                responseFromStream(
                    request = request,
                    stream = FixedChunkStream(largePayload, 4 * 1024),
                    declaredLength = largePayload.size.toLong(),
                )
            },
        )

        store.ensureAvailable(largeBuild, progress::add)

        assertEquals(largePayload.size.toLong(), progress.last().transferredThisRunBytes)
        assertTrue(progress.size <= 101)
    }

    @Test
    fun `large downloads emit bounded monotonic progress including initial and final`() = runTest {
        val largePayload = ByteArray(8 * 1024 * 1024) { (it % 251).toByte() }
        val largeBuild = buildFor(largePayload, "large-progress")
        val progress = mutableListOf<DownloadProgress>()
        val store = ModelStore(
            temporaryFolder.root,
            scriptedClient { request, _ -> response(request, 200, largePayload) },
        )

        store.ensureAvailable(largeBuild, progress::add)

        assertEquals(DownloadProgress(0, largePayload.size.toLong(), 0), progress.first())
        assertEquals(
            DownloadProgress(
                bytesRead = largePayload.size.toLong(),
                totalBytes = largePayload.size.toLong(),
                percent = 100,
                actualBytes = largePayload.size.toLong(),
                transferredThisRunBytes = largePayload.size.toLong(),
            ),
            progress.last(),
        )
        assertTrue("integer percent coalescing should cap events", progress.size <= 101)
        progress.zipWithNext().forEach { (before, after) ->
            assertTrue(after.bytesRead >= before.bytesRead)
            assertTrue(after.percent >= before.percent)
        }
    }

    @Test
    fun `a second HTTP 416 fails cleanly after the one reset`() = runTest {
        partFile.writeBytes(payload.copyOf(4 * 1024))
        val store = ModelStore(
            temporaryFolder.root,
            scriptedClient { request, _ -> response(request, 416, ByteArray(0)) },
        )

        val failure = runCatching { store.ensureAvailable(build) }.exceptionOrNull()

        assertTrue(failure is ModelDownloadHttpException)
        assertFalse(partFile.exists())
        assertFalse(store.isInstalled(build))
    }

    @Test
    fun `an invalid Content-Range is rejected before the partial changes`() = runTest {
        val prefixBytes = 8 * 1024
        val original = payload.copyOf(prefixBytes)
        partFile.writeBytes(original)
        val store = ModelStore(
            temporaryFolder.root,
            scriptedClient { request, _ ->
                response(
                    request = request,
                    code = 206,
                    body = payload.copyOfRange(prefixBytes, payload.size),
                    contentRange = "bytes ${prefixBytes + 1}-${payload.lastIndex}/${payload.size}",
                )
            },
        )

        val failure = runCatching { store.ensureAvailable(build) }.exceptionOrNull()

        assertTrue(failure is InvalidModelRangeException)
        assertEquals(original.toList(), partFile.readBytes().toList())
        assertFalse(store.isInstalled(build))
    }

    @Test
    fun `a response with the wrong pinned total is rejected`() = runTest {
        val prefixBytes = 8 * 1024
        partFile.writeBytes(payload.copyOf(prefixBytes))
        val store = ModelStore(
            temporaryFolder.root,
            scriptedClient { request, _ ->
                response(
                    request = request,
                    code = 206,
                    body = payload.copyOfRange(prefixBytes, payload.size),
                    contentRange = "bytes $prefixBytes-${payload.lastIndex}/${payload.size + 1}",
                )
            },
        )

        val failure = runCatching { store.ensureAvailable(build) }.exceptionOrNull()

        assertTrue(failure is InvalidModelRangeException)
        assertEquals(prefixBytes.toLong(), partFile.length())
    }

    @Test
    fun `an oversized partial is discarded before a clean download`() = runTest {
        partFile.writeBytes(payload + byteArrayOf(1))
        val requests = mutableListOf<Request>()
        val store = ModelStore(
            temporaryFolder.root,
            scriptedClient(requests) { request, _ -> response(request, 200, payload) },
        )

        val file = store.ensureAvailable(build)

        assertEquals(null, requests.single().header("Range"))
        assertEquals(payload.toList(), file.readBytes().toList())
    }

    @Test
    fun `storage headroom accounts only for bytes remaining in a partial`() {
        val prefixBytes = 10 * 1024
        partFile.writeBytes(payload.copyOf(prefixBytes))
        var freeBytes = build.sizeBytes - prefixBytes + STORAGE_HEADROOM_BYTES
        val store = ModelStore(
            root = temporaryFolder.root,
            client = OkHttpClient(),
            freeBytesProvider = { freeBytes },
            verifiedFilePromoter = { _, _ -> },
        )

        assertEquals(build.sizeBytes - prefixBytes, store.remainingDownloadBytes(build))
        assertEquals(freeBytes, store.requiredFreeBytes(build))
        assertTrue(store.hasRoomFor(build))

        freeBytes -= 1
        assertFalse(store.hasRoomFor(build))
    }

    @Test
    fun `insufficient storage starts no request and mutates no artifact`() = runTest {
        val prefix = payload.copyOf(10 * 1024)
        val previousTarget = byteArrayOf(8, 6, 7, 5, 3, 0, 9)
        partFile.writeBytes(prefix)
        val target = File(temporaryFolder.root, build.fileName).apply { writeBytes(previousTarget) }
        val required = build.sizeBytes - prefix.size + STORAGE_HEADROOM_BYTES
        val calls = AtomicInteger()
        val store = ModelStore(
            root = temporaryFolder.root,
            client = scriptedClient { request, _ ->
                calls.incrementAndGet()
                response(request, 200, payload)
            },
            freeBytesProvider = { required - 1 },
            verifiedFilePromoter = { _, _ -> error("promotion must not run") },
        )

        val failure = runCatching { store.ensureAvailable(build) }.exceptionOrNull()

        assertTrue(failure is InsufficientModelStorageException)
        assertEquals(0, calls.get())
        assertEquals(prefix.toList(), partFile.readBytes().toList())
        assertEquals(previousTarget.toList(), target.readBytes().toList())
    }

    @Test
    fun `connection failures are typed as network failures`() = runTest {
        val store = ModelStore(
            temporaryFolder.root,
            OkHttpClient.Builder()
                .addInterceptor { throw IOException("synthetic connection failure") }
                .build(),
        )

        val failure = runCatching { store.ensureAvailable(build) }.exceptionOrNull()

        assertTrue(failure is ModelNetworkException)
        assertFalse(partFile.exists())
        assertFalse(store.isInstalled(build))
    }

    @Test
    fun `local partial open failures are typed as storage failures`() = runTest {
        assertTrue(partFile.mkdirs())
        val store = ModelStore(temporaryFolder.root, clientServing(payload))

        val failure = runCatching { store.ensureAvailable(build) }.exceptionOrNull()

        assertTrue(failure is ModelStorageException)
        assertFalse(store.isInstalled(build))
        assertTrue(partFile.isDirectory)
    }

    @Test
    fun `a non-directory model root is typed as a storage failure`() {
        val invalidRoot = temporaryFolder.newFile("not-a-model-directory")
        val store = ModelStore(invalidRoot, OkHttpClient())

        val failure = runCatching { store.fileFor(build) }.exceptionOrNull()

        assertTrue(failure is ModelStorageException)
    }

    @Test
    fun `response read failures are typed as network and retain received bytes`() = runTest {
        val receivedBeforeFailure = 11 * 1024
        val store = ModelStore(
            temporaryFolder.root,
            scriptedClient { request, _ ->
                responseFromStream(
                    request,
                    FailingReadStream(payload, receivedBeforeFailure),
                    payload.size.toLong(),
                )
            },
        )

        val failure = runCatching { store.ensureAvailable(build) }.exceptionOrNull()

        assertTrue(failure is ModelNetworkException)
        assertEquals(receivedBeforeFailure.toLong(), partFile.length())
        assertEquals(payload.copyOf(receivedBeforeFailure).toList(), partFile.readBytes().toList())
    }

    @Test
    fun `response close failures are typed as network rather than local storage`() = runTest {
        val store = ModelStore(
            temporaryFolder.root,
            scriptedClient { request, _ ->
                responseFromStream(
                    request,
                    CloseFailingStream(payload),
                    payload.size.toLong(),
                )
            },
        )

        val failure = runCatching { store.ensureAvailable(build) }.exceptionOrNull()

        assertTrue(failure is ModelNetworkException)
        assertEquals(payload.toList(), partFile.readBytes().toList())
        assertFalse(store.isInstalled(build))
    }

    @Test
    fun `a short response preserves its bytes for a later resume`() = runTest {
        val shortBody = payload.copyOf(payload.size / 2)
        val store = ModelStore(
            temporaryFolder.root,
            scriptedClient { request, _ -> response(request, 200, shortBody) },
        )

        val failure = runCatching { store.ensureAvailable(build) }.exceptionOrNull()

        assertTrue(failure is IncompleteModelDownloadException)
        assertEquals(shortBody.toList(), partFile.readBytes().toList())
        assertFalse(store.isInstalled(build))
    }

    @Test
    fun `a declared oversized response is rejected before writing`() = runTest {
        val prefixBytes = 8 * 1024
        val original = payload.copyOf(prefixBytes)
        partFile.writeBytes(original)
        val remaining = payload.copyOfRange(prefixBytes, payload.size)
        val store = ModelStore(
            temporaryFolder.root,
            scriptedClient { request, _ ->
                response(
                    request = request,
                    code = 206,
                    body = remaining,
                    contentRange = "bytes $prefixBytes-${payload.lastIndex}/${payload.size}",
                    declaredLength = remaining.size.toLong() + 1,
                )
            },
        )

        val failure = runCatching { store.ensureAvailable(build) }.exceptionOrNull()

        assertTrue(failure is ModelDownloadTooLargeException)
        assertEquals(original.toList(), partFile.readBytes().toList())
    }

    @Test
    fun `an unknown-length oversized body is capped and rolled back`() = runTest {
        val store = ModelStore(
            temporaryFolder.root,
            scriptedClient { request, _ ->
                response(
                    request = request,
                    code = 200,
                    body = payload + byteArrayOf(9),
                    declaredLength = -1,
                )
            },
        )

        val failure = runCatching { store.ensureAvailable(build) }.exceptionOrNull()

        assertTrue(failure is ModelDownloadTooLargeException)
        assertTrue(partFile.exists())
        assertEquals(0, partFile.length())
        assertFalse(store.isInstalled(build))
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
    fun `checksum failure preserves an existing target until a replacement is valid`() = runTest {
        val target = File(temporaryFolder.root, build.fileName)
        val previousTarget = byteArrayOf(4, 3, 2, 1)
        target.writeBytes(previousTarget)
        val store = ModelStore(
            temporaryFolder.root,
            clientServing(ByteArray(payload.size) { 7 }),
        )

        val failure = runCatching { store.ensureAvailable(build) }.exceptionOrNull()

        assertTrue(failure is ModelChecksumException)
        assertEquals(previousTarget.toList(), target.readBytes().toList())
        assertFalse(partFile.exists())
    }

    @Test
    fun `cancelling verification preserves the complete partial and old target`() = runTest {
        val previousTarget = byteArrayOf(2, 7, 1, 8)
        val target = File(temporaryFolder.root, build.fileName).apply { writeBytes(previousTarget) }
        partFile.writeBytes(payload)
        val verificationStarted = CompletableDeferred<Unit>()
        val store = ModelStore(
            root = temporaryFolder.root,
            client = OkHttpClient(),
            freeBytesProvider = { Long.MAX_VALUE },
            verifiedFilePromoter = { _, _ -> error("promotion must not run") },
            fileHasher = {
                verificationStarted.complete(Unit)
                awaitCancellation()
            },
        )

        val verification = launch(Dispatchers.Default) { store.ensureAvailable(build) }
        verificationStarted.await()
        verification.cancelAndJoin()

        assertTrue(verification.isCancelled)
        assertEquals(payload.toList(), partFile.readBytes().toList())
        assertEquals(previousTarget.toList(), target.readBytes().toList())
    }

    @Test
    fun `a verified replacement atomically replaces the previous target`() = runTest {
        val previousTarget = byteArrayOf(1, 1, 2, 3, 5, 8)
        val target = File(temporaryFolder.root, build.fileName).apply { writeBytes(previousTarget) }
        val store = ModelStore(temporaryFolder.root, clientServing(payload))

        val installed = store.ensureAvailable(build)

        assertEquals(target.absolutePath, installed.absolutePath)
        assertEquals(payload.toList(), target.readBytes().toList())
        assertFalse(partFile.exists())
        assertTrue(store.isInstalled(build))
    }

    @Test
    fun `atomic promotion failure preserves both the verified partial and old target`() = runTest {
        val target = File(temporaryFolder.root, build.fileName)
        val previousTarget = byteArrayOf(1, 2, 3)
        target.writeBytes(previousTarget)
        val store = ModelStore(
            root = temporaryFolder.root,
            client = clientServing(payload),
            freeBytesProvider = { Long.MAX_VALUE },
            verifiedFilePromoter = { _, _ -> throw IOException("simulated move failure") },
        )

        val failure = runCatching { store.ensureAvailable(build) }.exceptionOrNull()

        assertTrue(failure is ModelPromotionException)
        assertEquals(previousTarget.toList(), target.readBytes().toList())
        assertEquals(payload.toList(), partFile.readBytes().toList())
    }

    @Test
    fun `promotion never wraps an out of memory error and preserves artifacts`() = runTest {
        val previousTarget = byteArrayOf(3, 1, 4, 1, 5)
        val target = File(temporaryFolder.root, build.fileName).apply { writeBytes(previousTarget) }
        partFile.writeBytes(payload)
        val store = ModelStore(
            root = temporaryFolder.root,
            client = OkHttpClient(),
            freeBytesProvider = { Long.MAX_VALUE },
            verifiedFilePromoter = { _, _ -> throw OutOfMemoryError("synthetic promotion OOM") },
        )

        val failure = runCatching { store.ensureAvailable(build) }.exceptionOrNull()

        assertTrue(failure is OutOfMemoryError)
        assertEquals("synthetic promotion OOM", failure?.message)
        assertEquals(previousTarget.toList(), target.readBytes().toList())
        assertEquals(payload.toList(), partFile.readBytes().toList())
    }

    @Test
    fun `default atomic promotion reports failure and preserves a nonreplaceable target`() = runTest {
        val targetDirectory = File(temporaryFolder.root, build.fileName).apply { mkdirs() }
        val marker = File(targetDirectory, "keep.txt").apply { writeText("keep") }
        partFile.writeBytes(payload)
        val store = ModelStore(temporaryFolder.root, OkHttpClient())

        val failure = runCatching { store.ensureAvailable(build) }.exceptionOrNull()

        assertTrue(failure is ModelPromotionException)
        assertTrue(targetDirectory.isDirectory)
        assertEquals("keep", marker.readText())
        assertEquals(payload.toList(), partFile.readBytes().toList())
    }

    @Test
    fun `a file of the wrong length does not count as installed`() {
        val store = ModelStore(temporaryFolder.root)
        store.fileFor(build).writeBytes(payload.copyOf(payload.size - 1))

        assertFalse(store.isInstalled(build))
    }

    private fun buildFor(bytes: ByteArray, id: String): ModelBuild = build.copy(
        id = id,
        displayName = id,
        fileName = "$id.litertlm",
        sizeBytes = bytes.size.toLong(),
        sha256 = bytes.sha256(),
    )

    private fun clientBlockingUntilCancelled(
        started: CompletableDeferred<Unit>,
        cancelled: CompletableDeferred<Unit>,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            started.complete(Unit)
            while (!chain.call().isCanceled()) {
                LockSupport.parkNanos(1_000_000)
            }
            cancelled.complete(Unit)
            throw IOException("synthetic cancelled call")
        }
        .build()

    private fun scriptedClient(
        requests: MutableList<Request> = mutableListOf(),
        responder: (Request, Int) -> Response,
    ): OkHttpClient {
        val calls = AtomicInteger()
        return OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    requests += request
                    responder(request, calls.getAndIncrement())
                },
            )
            .build()
    }

    private fun responseFromStream(
        request: Request,
        stream: InputStream,
        declaredLength: Long,
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("test response")
        .body(
            stream.source().buffer()
                .asResponseBody("application/octet-stream".toMediaType(), declaredLength),
        )
        .build()

    private fun response(
        request: Request,
        code: Int,
        body: ByteArray,
        contentRange: String? = null,
        declaredLength: Long = body.size.toLong(),
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test response")
        .apply { contentRange?.let { header("Content-Range", it) } }
        .body(
            body.inputStream().source().buffer()
                .asResponseBody("application/octet-stream".toMediaType(), declaredLength),
        )
        .build()

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

    private companion object {
        const val STORAGE_HEADROOM_BYTES = 250_000_000L
    }
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

private class FixedChunkStream(
    private val body: ByteArray,
    private val chunkBytes: Int,
) : InputStream() {
    private var position = 0

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 0xff
    }

    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        if (position >= body.size) return -1
        val count = minOf(length, chunkBytes, body.size - position)
        body.copyInto(destination, offset, position, position + count)
        position += count
        return count
    }
}

private class FailingReadStream(
    private val body: ByteArray,
    private val failAfterBytes: Int,
) : InputStream() {
    private var position = 0

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 0xff
    }

    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        if (position >= failAfterBytes) throw IOException("synthetic response read failure")
        val count = minOf(length, failAfterBytes - position)
        body.copyInto(destination, offset, position, position + count)
        position += count
        return count
    }
}

private class CloseFailingStream(
    body: ByteArray,
) : InputStream() {
    private val delegate = body.inputStream()

    override fun read(): Int = delegate.read()

    override fun read(destination: ByteArray, offset: Int, length: Int): Int =
        delegate.read(destination, offset, length)

    override fun close() {
        delegate.close()
        throw IOException("synthetic response close failure")
    }
}

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
