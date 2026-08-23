package com.noamv.localllm.model

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Progress during a download. [percent] is -1 while the total size is unknown. */
data class DownloadProgress(val bytesRead: Long, val totalBytes: Long, val percent: Int)

sealed class ModelStoreException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

class InsufficientModelStorageException(
    val requiredFreeBytes: Long,
    val availableFreeBytes: Long,
    build: ModelBuild,
) : ModelStoreException(
    "Not enough free space for ${build.displayName}: " +
        "$requiredFreeBytes bytes required, $availableFreeBytes bytes available.",
)

class ModelNetworkException(build: ModelBuild, cause: IOException) :
    ModelStoreException("Network failure while downloading ${build.fileName}.", cause)

class ModelDownloadHttpException(val statusCode: Int, build: ModelBuild) :
    ModelStoreException("Download failed with HTTP $statusCode for ${build.fileName}.")

class InvalidModelRangeException(
    val contentRange: String?,
    val expectedStart: Long,
    val expectedTotal: Long,
    build: ModelBuild,
) : ModelStoreException(
    "Invalid Content-Range for ${build.fileName}: expected bytes beginning at " +
        "$expectedStart of $expectedTotal, received ${contentRange ?: "no header"}.",
)

class ModelDownloadTooLargeException(
    val maximumBytes: Long,
    val observedBytes: Long,
    build: ModelBuild,
) : ModelStoreException(
    "Download exceeded the pinned size for ${build.fileName}: " +
        "$observedBytes bytes observed, $maximumBytes bytes allowed.",
)

class IncompleteModelDownloadException(
    val expectedBytes: Long,
    val actualBytes: Long,
    build: ModelBuild,
) : ModelStoreException(
    "Download ended early for ${build.fileName}: " +
        "$actualBytes of $expectedBytes bytes are available.",
)

class ModelChecksumException(
    val expectedSha256: String,
    val actualSha256: String,
    build: ModelBuild,
) : ModelStoreException(
    "Model verification failed for ${build.fileName}. " +
        "Expected $expectedSha256 but the downloaded file hashed to $actualSha256.",
)

class ModelStorageException(message: String, cause: Throwable? = null) :
    ModelStoreException(message, cause)

class ModelPromotionException(build: ModelBuild, cause: Throwable) :
    ModelStoreException("Could not atomically promote the verified ${build.fileName}.", cause)

class ModelDownloadResponseLimitException(
    val maximumResponses: Int,
    build: ModelBuild,
) : ModelStoreException(
    "Download used more than $maximumResponses partial responses for ${build.fileName}.",
)

/**
 * Downloads, verifies and stores model files.
 *
 * Files live in the app's internal `files/models` directory. That location is excluded
 * from backup, is removed automatically when the app is uninstalled, and needs no
 * storage permission. The trade-off is that it counts toward the app's data usage in
 * Settings, which for a 2 GB model is worth being explicit about in the UI.
 */
class ModelStore internal constructor(
    private val root: File,
    private val client: OkHttpClient,
    private val freeBytesProvider: (File) -> Long,
    private val verifiedFilePromoter: (File, File) -> Unit,
    private val fileHasher: suspend (File) -> String = ::sha256OfFile,
) {
    constructor(root: File, client: OkHttpClient = OkHttpClient()) : this(
        root = root,
        client = client,
        freeBytesProvider = { it.usableSpace },
        verifiedFilePromoter = ::atomicPromote,
    )

    /** The real construction path; [root] exists so tests can supply a temporary folder. */
    constructor(context: Context, client: OkHttpClient = OkHttpClient()) : this(
        root = File(context.filesDir, "models"),
        client = client,
        freeBytesProvider = { it.usableSpace },
        verifiedFilePromoter = ::atomicPromote,
    )

    private val transferMutex = Mutex()
    private val coordinationLock = Any()
    private val pendingDeletionCounts = mutableMapOf<String, Int>()
    private var activeTransfer: ActiveTransfer? = null

    private val modelsDir: File
        get() = root.apply {
            if ((!exists() && !mkdirs()) || !isDirectory) {
                throw ModelStorageException("Could not create the model directory.")
            }
        }

    fun fileFor(build: ModelBuild): File = File(modelsDir, build.fileName)

    internal fun partFor(build: ModelBuild): File = File(modelsDir, "${build.fileName}.part")

    /** True when the model is present. Does not re-verify the digest, which is slow. */
    fun isInstalled(build: ModelBuild): Boolean =
        fileFor(build).let { it.isFile && it.length() == build.sizeBytes }

    /** Free space on the volume holding the model directory. */
    fun freeBytes(): Long = freeBytesProvider(modelsDir)

    /** Bytes still required after accounting for a valid partial download. */
    fun remainingDownloadBytes(build: ModelBuild): Long {
        val partialBytes = partFor(build).takeIf { it.isFile }?.length().orZero()
        return if (partialBytes in 0..build.sizeBytes) {
            build.sizeBytes - partialBytes
        } else {
            build.sizeBytes
        }
    }

    /** Remaining model bytes plus the fixed safety headroom. */
    fun requiredFreeBytes(build: ModelBuild): Long =
        remainingDownloadBytes(build).saturatedPlus(STORAGE_HEADROOM_BYTES)

    /**
     * Headroom required before starting. A resumed transfer needs only its remaining
     * bytes, not another full copy of the pinned artifact.
     */
    fun hasRoomFor(build: ModelBuild): Boolean = freeBytes() >= requiredFreeBytes(build)

    /**
     * Downloads [build] if it is not already installed, resuming a valid partial download,
     * then verifies the SHA-256 and atomically promotes it into place.
     *
     * Cancellation leaves the partial file in place. A valid complete partial is verified
     * and promoted without a network request. An oversized partial and a stale range that
     * receives HTTP 416 are reset safely. The existing target is never deleted before its
     * verified replacement is atomically promoted.
     */
    suspend fun ensureAvailable(
        build: ModelBuild,
        onProgress: (DownloadProgress) -> Unit = {},
    ): File = coroutineScope {
        // A dedicated child owns the transfer. A delete request can cancel that child
        // without cancelling an unrelated parent scope, while caller cancellation still
        // propagates into it normally.
        async(Dispatchers.IO) {
            transferMutex.withLock {
                val transfer = ActiveTransfer(build.fileName, currentCoroutineContext()[Job]!!)
                registerActiveTransferUnlessDeleting(transfer)
                try {
                    ensureAvailableLocked(build, ProgressReporter(onProgress))
                } finally {
                    clearActiveTransfer(transfer)
                }
            }
        }.await()
    }

    private suspend fun <T> executeCancellableCall(
        build: ModelBuild,
        call: Call,
        consume: suspend (Response) -> T,
    ): T = coroutineScope {
        // The watcher is a live child for the whole execute/read/close lifetime. Parent
        // cancellation immediately runs its finally block on another coroutine and calls
        // OkHttp's cancellation primitive, which unblocks DNS/connect/read waits rather
        // than waiting for a socket timeout.
        val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            val response = try {
                call.execute()
            } catch (failure: IOException) {
                currentCoroutineContext().ensureActive()
                throw ModelNetworkException(build, failure)
            }

            try {
                response.use { consume(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (known: ModelStoreException) {
                throw known
            } catch (failure: IOException) {
                currentCoroutineContext().ensureActive()
                throw ModelNetworkException(build, failure)
            }
        } finally {
            withContext(NonCancellable) {
                cancellationWatcher.cancelAndJoin()
            }
        }
    }

    private suspend fun ensureAvailableLocked(
        build: ModelBuild,
        progress: ProgressReporter,
    ): File {
        require(build.sizeBytes > 0) { "Model size must be positive." }

        val target = fileFor(build)
        if (isInstalled(build)) return target

        val part = partFor(build)
        if (part.isFile && part.length() > build.sizeBytes) {
            deleteOrThrow(part, "Could not discard the oversized partial model file.")
        }

        if (part.isFile && part.length() == build.sizeBytes) {
            return verifyAndPromote(build, part, target)
        }

        checkFreeSpace(build)
        progress.report(part.takeIf { it.isFile }?.length().orZero(), build.sizeBytes)

        var retriedAfterRangeFailure = false
        var responseCount = 0
        while (true) {
            coroutineContext.ensureActive()
            responseCount++
            if (responseCount > MAX_DOWNLOAD_RESPONSES) {
                throw ModelDownloadResponseLimitException(MAX_DOWNLOAD_RESPONSES, build)
            }
            val existing = part.takeIf { it.isFile }?.length().orZero()
            val request = Request.Builder()
                .url(build.url)
                .apply { if (existing > 0) header("Range", "bytes=$existing-") }
                .build()

            val outcome = executeCancellableCall(build, client.newCall(request)) { response ->
                if (response.code == HTTP_RANGE_NOT_SATISFIABLE &&
                    existing > 0 &&
                    !retriedAfterRangeFailure
                ) {
                    deleteOrThrow(part, "Could not reset the rejected partial model file.")
                    retriedAfterRangeFailure = true
                    checkFreeSpace(build)
                    progress.report(0, build.sizeBytes)
                    return@executeCancellableCall ResponseOutcome.RESET
                }

                val responsePlan = writeResponse(build, part, existing, response, progress)
                ResponseOutcome.WRITTEN_PARTIAL.takeIf { responsePlan.partialContent }
                    ?: ResponseOutcome.WRITTEN_COMPLETE_RESPONSE
            }

            if (outcome == ResponseOutcome.RESET) continue

            val actualBytes = part.takeIf { it.isFile }?.length().orZero()
            if (actualBytes == build.sizeBytes) break

            // A valid 206 may contain only a bounded portion of an open-ended range.
            // Continue from the new offset in the same owner-started operation. A short
            // 200 remains an incomplete transfer because retrying it here could loop on a
            // server that repeatedly ignores Range and restarts from byte zero.
            if (outcome == ResponseOutcome.WRITTEN_PARTIAL && actualBytes > existing) {
                continue
            }
            break
        }

        val actualBytes = part.takeIf { it.isFile }?.length().orZero()
        if (actualBytes != build.sizeBytes) {
            throw IncompleteModelDownloadException(build.sizeBytes, actualBytes, build)
        }

        progress.report(build.sizeBytes, build.sizeBytes)
        return verifyAndPromote(build, part, target)
    }

    private suspend fun writeResponse(
        build: ModelBuild,
        part: File,
        existing: Long,
        response: Response,
        progress: ProgressReporter,
    ): ResponsePlan {
        val responsePlan = responsePlan(build, existing, response)
        val body = response.body
            ?: throw ModelNetworkException(build, IOException("Download returned no body."))
        val declaredBytes = body.contentLength()
        if (declaredBytes > responsePlan.maximumResponseBytes) {
            throw ModelDownloadTooLargeException(
                maximumBytes = responsePlan.maximumResponseBytes,
                observedBytes = declaredBytes,
                build = build,
            )
        }

        val initialLength = responsePlan.writeOffset
        val output = try {
            FileOutputStream(part, responsePlan.append)
        } catch (failure: IOException) {
            throw ModelStorageException("Could not open the partial model file.", failure)
        }

        var receivedThisResponse = 0L
        try {
            output.use { destination ->
                val source = try {
                    body.byteStream()
                } catch (failure: IOException) {
                    throw ModelNetworkException(build, failure)
                }
                try {
                    source.use {
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = readNetworkChunk(build, source, buffer)
                            if (read == -1) break

                            val nextResponseBytes = receivedThisResponse + read
                            val nextTotalBytes = initialLength + nextResponseBytes
                            if (nextResponseBytes > responsePlan.maximumResponseBytes ||
                                nextTotalBytes > build.sizeBytes
                            ) {
                                rollbackPartial(part, initialLength)
                                throw ModelDownloadTooLargeException(
                                    maximumBytes = responsePlan.maximumResponseBytes,
                                    observedBytes = nextResponseBytes,
                                    build = build,
                                )
                            }

                            try {
                                destination.write(buffer, 0, read)
                            } catch (failure: IOException) {
                                throw ModelStorageException(
                                    "Could not write the partial model file.",
                                    failure,
                                )
                            }
                            receivedThisResponse = nextResponseBytes
                            progress.report(nextTotalBytes, build.sizeBytes)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (known: ModelStoreException) {
                    throw known
                } catch (failure: IOException) {
                    // At this point individual file writes are already wrapped as storage.
                    // The remaining raw IOException paths belong to the response stream,
                    // including its close operation.
                    coroutineContext.ensureActive()
                    throw ModelNetworkException(build, failure)
                }
                try {
                    destination.fd.sync()
                } catch (failure: IOException) {
                    throw ModelStorageException("Could not sync the partial model file.", failure)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (known: ModelStoreException) {
            throw known
        } catch (failure: IOException) {
            throw ModelStorageException("Could not write the partial model file.", failure)
        }

        responsePlan.exactResponseBytes?.let { expected ->
            if (receivedThisResponse != expected) {
                throw IncompleteModelDownloadException(
                    expectedBytes = build.sizeBytes,
                    actualBytes = part.length(),
                    build = build,
                )
            }
        }
        return responsePlan
    }

    private fun responsePlan(build: ModelBuild, existing: Long, response: Response): ResponsePlan {
        if (existing > 0 && response.code == HTTP_PARTIAL_CONTENT) {
            val contentRange = parseContentRange(response.header("Content-Range"))
            if (contentRange == null ||
                contentRange.start != existing ||
                contentRange.end < contentRange.start ||
                contentRange.end >= build.sizeBytes ||
                contentRange.total != build.sizeBytes
            ) {
                throw InvalidModelRangeException(
                    contentRange = response.header("Content-Range"),
                    expectedStart = existing,
                    expectedTotal = build.sizeBytes,
                    build = build,
                )
            }
            return ResponsePlan(
                append = true,
                writeOffset = existing,
                maximumResponseBytes = contentRange.length,
                exactResponseBytes = contentRange.length,
                partialContent = true,
            )
        }

        if (existing == 0L && response.code == HTTP_PARTIAL_CONTENT) {
            val contentRange = parseContentRange(response.header("Content-Range"))
            if (contentRange == null ||
                contentRange.start != 0L ||
                contentRange.end < 0L ||
                contentRange.end >= build.sizeBytes ||
                contentRange.total != build.sizeBytes
            ) {
                throw InvalidModelRangeException(
                    contentRange = response.header("Content-Range"),
                    expectedStart = 0L,
                    expectedTotal = build.sizeBytes,
                    build = build,
                )
            }
            return ResponsePlan(
                append = false,
                writeOffset = 0,
                maximumResponseBytes = contentRange.length,
                exactResponseBytes = contentRange.length,
                partialContent = true,
            )
        }

        if (response.code == HTTP_OK) {
            return ResponsePlan(
                append = false,
                writeOffset = 0,
                maximumResponseBytes = build.sizeBytes,
                exactResponseBytes = null,
                partialContent = false,
            )
        }

        throw ModelDownloadHttpException(response.code, build)
    }

    private suspend fun verifyAndPromote(build: ModelBuild, part: File, target: File): File {
        val digest = fileHasher(part)
        if (!digest.equals(build.sha256, ignoreCase = true)) {
            deleteOrThrow(part, "Could not discard the model file that failed verification.")
            throw ModelChecksumException(build.sha256, digest, build)
        }

        try {
            verifiedFilePromoter(part, target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (outOfMemory: OutOfMemoryError) {
            throw outOfMemory
        } catch (failure: ModelStoreException) {
            throw failure
        } catch (failure: Exception) {
            throw ModelPromotionException(build, failure)
        }

        if (!target.isFile || target.length() != build.sizeBytes) {
            throw ModelPromotionException(
                build,
                IOException("Atomic promotion completed without the pinned target file."),
            )
        }
        return target
    }

    private fun checkFreeSpace(build: ModelBuild) {
        val available = freeBytes()
        val required = requiredFreeBytes(build)
        if (available < required) {
            throw InsufficientModelStorageException(required, available, build)
        }
    }

    /**
     * Cancels an active transfer for [build], waits for its child job to release the file,
     * and then removes both installed and partial artifacts without blocking a caller's
     * thread. Queued transfers for the same file observe the pending deletion and cancel
     * instead of downloading a replacement immediately before it is deleted.
     */
    suspend fun delete(build: ModelBuild) {
        val activeJob = markDeletionPending(build.fileName)
        activeJob?.cancel(CancellationException("Model deletion requested for ${build.id}"))
        try {
            transferMutex.withLock {
                withContext(Dispatchers.IO) {
                    deleteOrThrow(fileFor(build), "Could not delete the installed model file.")
                    deleteOrThrow(partFor(build), "Could not delete the partial model file.")
                }
            }
        } finally {
            clearDeletionPending(build.fileName)
        }
    }

    /** Every model file currently on disk, including partials. */
    fun installedFiles(): List<File> = modelsDir.listFiles()?.toList().orEmpty()

    /**
     * Deletes every model file except [keep], returning the bytes reclaimed.
     *
     * Without this, changing the selected build silently strands the previous one. These
     * files are two to three gigabytes each, so an orphan is not a tidiness problem — it
     * is a meaningful chunk of the user's storage that nothing will ever reclaim.
     */
    suspend fun pruneExcept(keep: ModelBuild): Long = transferMutex.withLock {
        withContext(Dispatchers.IO) {
            val keepName = keep.fileName
            installedFiles()
                .filter { it.name != keepName }
                .sumOf { file ->
                    val size = file.length()
                    deleteOrThrow(file, "Could not prune unused model file ${file.name}.")
                    size
                }
        }
    }

    private fun registerActiveTransferUnlessDeleting(transfer: ActiveTransfer) {
        synchronized(coordinationLock) {
            if (pendingDeletionCounts.getOrDefault(transfer.fileName, 0) > 0) {
                throw CancellationException("Model deletion is pending for ${transfer.fileName}")
            }
            check(activeTransfer == null) { "Only one transfer may own ModelStore files." }
            activeTransfer = transfer
        }
    }

    private fun clearActiveTransfer(transfer: ActiveTransfer) {
        synchronized(coordinationLock) {
            if (activeTransfer === transfer) activeTransfer = null
        }
    }

    private fun markDeletionPending(fileName: String): Job? = synchronized(coordinationLock) {
        pendingDeletionCounts[fileName] = pendingDeletionCounts.getOrDefault(fileName, 0) + 1
        activeTransfer?.takeIf { it.fileName == fileName }?.job
    }

    private fun clearDeletionPending(fileName: String) {
        synchronized(coordinationLock) {
            val remaining = pendingDeletionCounts.getOrDefault(fileName, 0) - 1
            if (remaining <= 0) pendingDeletionCounts.remove(fileName)
            else pendingDeletionCounts[fileName] = remaining
        }
    }

    private data class ActiveTransfer(
        val fileName: String,
        val job: Job,
    )

    private suspend fun readNetworkChunk(
        build: ModelBuild,
        source: InputStream,
        buffer: ByteArray,
    ): Int =
        try {
            source.read(buffer)
        } catch (failure: IOException) {
            coroutineContext.ensureActive()
            throw ModelNetworkException(build, failure)
        }

    private fun rollbackPartial(part: File, length: Long) {
        try {
            java.io.RandomAccessFile(part, "rw").use { it.setLength(length) }
        } catch (failure: IOException) {
            throw ModelStorageException("Could not restore the partial model after rejection.", failure)
        }
    }

    private fun deleteOrThrow(file: File, message: String) {
        if (file.exists() && !file.delete()) throw ModelStorageException(message)
    }

    private data class ResponsePlan(
        val append: Boolean,
        val writeOffset: Long,
        val maximumResponseBytes: Long,
        val exactResponseBytes: Long?,
        val partialContent: Boolean,
    )

    private enum class ResponseOutcome {
        RESET,
        WRITTEN_PARTIAL,
        WRITTEN_COMPLETE_RESPONSE,
    }

    private data class ContentRange(
        val start: Long,
        val end: Long,
        val total: Long,
    ) {
        val length: Long get() = end - start + 1
    }

    private fun parseContentRange(value: String?): ContentRange? {
        val match = value?.trim()?.let(CONTENT_RANGE_PATTERN::matchEntire) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        return ContentRange(start, end, total)
    }

    private companion object {
        const val BUFFER_BYTES = 1 shl 16
        const val STORAGE_HEADROOM_BYTES = 250_000_000L
        const val MAX_DOWNLOAD_RESPONSES = 512
        const val HTTP_OK = 200
        const val HTTP_PARTIAL_CONTENT = 206
        const val HTTP_RANGE_NOT_SATISFIABLE = 416

        val CONTENT_RANGE_PATTERN = Regex(
            pattern = "bytes\\s+(\\d+)-(\\d+)/(\\d+)",
            option = RegexOption.IGNORE_CASE,
        )

        fun atomicPromote(part: File, target: File) {
            Files.move(
                part.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}

private fun Long?.orZero(): Long = this ?: 0L

private fun Long.saturatedPlus(other: Long): Long =
    if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

private class ProgressReporter(
    private val callback: (DownloadProgress) -> Unit,
) {
    private var lastBytes = -1L
    private var lastPercent = Int.MIN_VALUE

    /**
     * Version-one clients consume integer percentages, so report only a changed percent.
     * Bytes are held to a per-operation high-water mark: a 416 reset or a server that
     * ignores Range must not make the public progress bar run backwards.
     */
    fun report(actualBytes: Long, totalBytes: Long) {
        val monotonicBytes = maxOf(lastBytes.coerceAtLeast(0), actualBytes.coerceIn(0, totalBytes))
        val next = progressFor(monotonicBytes, totalBytes)
        if (lastBytes < 0 || next.percent != lastPercent || monotonicBytes == totalBytes) {
            if (next.bytesRead != lastBytes || next.percent != lastPercent) callback(next)
            lastBytes = next.bytesRead
            lastPercent = next.percent
        }
    }
}

private fun progressFor(bytesRead: Long, totalBytes: Long): DownloadProgress {
    val percent = if (totalBytes > 0) {
        ((bytesRead.coerceIn(0, totalBytes) * 100) / totalBytes).toInt()
    } else {
        -1
    }
    return DownloadProgress(bytesRead, totalBytes, percent)
}

private suspend fun sha256OfFile(file: File): String = withContext(Dispatchers.IO) {
    val digest = MessageDigest.getInstance("SHA-256")
    try {
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IOException) {
        throw ModelStorageException("Could not read the partial model for verification.", failure)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}
