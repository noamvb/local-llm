package com.noamv.localllm.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Progress during a download. [percent] is -1 while the total size is unknown. */
data class DownloadProgress(val bytesRead: Long, val totalBytes: Long, val percent: Int)

/**
 * Downloads, verifies and stores model files.
 *
 * Files live in the app's internal `files/models` directory. That location is excluded
 * from backup, is removed automatically when the app is uninstalled, and needs no
 * storage permission. The trade-off is that it counts toward the app's data usage in
 * Settings, which for a 2 GB model is worth being explicit about in the UI.
 */
class ModelStore(
    private val root: File,
    private val client: OkHttpClient = OkHttpClient(),
) {
    /** The real construction path; [root] exists so tests can supply a temporary folder. */
    constructor(context: Context, client: OkHttpClient = OkHttpClient()) :
        this(File(context.filesDir, "models"), client)

    private val modelsDir: File
        get() = root.apply { mkdirs() }

    fun fileFor(build: ModelBuild): File = File(modelsDir, build.fileName)

    private fun partFor(build: ModelBuild): File = File(modelsDir, "${build.fileName}.part")

    /** True when the model is present. Does not re-verify the digest, which is slow. */
    fun isInstalled(build: ModelBuild): Boolean =
        fileFor(build).let { it.isFile && it.length() == build.sizeBytes }

    /** Free space on the volume holding the model directory. */
    fun freeBytes(): Long = modelsDir.usableSpace

    /**
     * Headroom required before starting. The partial file and the final file coexist
     * only briefly, but the device still needs the full size free plus a small margin.
     */
    fun hasRoomFor(build: ModelBuild): Boolean = freeBytes() > build.sizeBytes + 250_000_000L

    /**
     * Downloads [build] if it is not already installed, resuming a partial download when
     * one exists, then verifies the SHA-256 before moving it into place.
     *
     * A model that fails verification is deleted rather than kept, because a corrupt
     * multi-gigabyte file that silently fails at load time is far harder to diagnose
     * than a failed download.
     *
     * Cancelling the calling coroutine aborts the download and leaves the partial file
     * in place for a later resume.
     */
    suspend fun ensureAvailable(
        build: ModelBuild,
        onProgress: (DownloadProgress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val target = fileFor(build)
        if (isInstalled(build)) return@withContext target

        require(hasRoomFor(build)) {
            "Not enough free space for ${build.displayName}: needs about " +
                "${"%.1f".format(build.sizeGb)} GB, ${"%.1f".format(freeBytes() / 1e9)} GB free."
        }

        val part = partFor(build)
        val existing = if (part.isFile) part.length() else 0L

        val request = Request.Builder()
            .url(build.url)
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .build()

        client.newCall(request).execute().use { response ->
            // A server that ignores the Range header restarts the file from zero.
            val resuming = existing > 0 && response.code == 206
            if (!response.isSuccessful) {
                throw IOException("Download failed with HTTP ${response.code} for ${build.fileName}")
            }
            if (existing > 0 && !resuming) part.delete()

            val body = response.body ?: throw IOException("Empty response body")
            val alreadyHave = if (resuming) existing else 0L
            val total = body.contentLength().let { if (it > 0) it + alreadyHave else build.sizeBytes }

            body.byteStream().use { input ->
                java.io.FileOutputStream(part, resuming).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var written = alreadyHave
                    var lastReported = -1
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        written += read
                        val percent = if (total > 0) ((written * 100) / total).toInt() else -1
                        if (percent != lastReported) {
                            lastReported = percent
                            onProgress(DownloadProgress(written, total, percent))
                        }
                    }
                    output.fd.sync()
                }
            }
        }

        val digest = sha256Of(part)
        if (!digest.equals(build.sha256, ignoreCase = true)) {
            part.delete()
            throw IOException(
                "Model verification failed for ${build.fileName}. " +
                    "Expected ${build.sha256} but the downloaded file hashed to $digest.",
            )
        }

        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("Could not move the verified model into place")
        target
    }

    fun delete(build: ModelBuild) {
        fileFor(build).delete()
        partFor(build).delete()
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
    fun pruneExcept(keep: ModelBuild): Long {
        val keepName = keep.fileName
        return installedFiles()
            .filter { it.name != keepName }
            .sumOf { file ->
                val size = file.length()
                if (file.delete()) size else 0L
            }
    }

    private suspend fun sha256Of(file: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
