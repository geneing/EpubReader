package com.geneing.epubreader.playback

import android.content.Context
import dev.pockettts.DirectorySource
import dev.pockettts.Downloader
import dev.pockettts.ModelManifest
import dev.pockettts.PocketTtsConfig
import dev.pockettts.PocketTtsEngine
import dev.pockettts.PocketTtsModels
import dev.pockettts.ReleaseModelSource
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

data class PocketModelUiState(
    val installed: Boolean = false,
    val installedFiles: Int = 0,
    val requiredFiles: Int = 0,
    val missingFiles: List<String> = emptyList(),
    val isInstalling: Boolean = false,
    val progress: Float = 0f,
    val message: String? = null,
)

/** Uses the pinned Pocket model manifest; app-owned model data stays outside the APK. */
class PocketTtsModelManager(context: Context) {
    private val appContext = context.applicationContext
    private val appModelDirectory = appContext.getExternalFilesDir(null) ?: appContext.filesDir
    private val pinnedManifest = appContext.assets.open("pockettts/models.json").bufferedReader().use {
        ModelManifest.parse(it.readText())
    }.also { manifest ->
        check(manifest.modelVersion == PocketTtsModels.DEFAULT_VERSION) {
            "Pocket model manifest version does not match the bundled model version."
        }
    }
    private val models: PocketTtsModels = PocketTtsModels.ofRelease(
        release = ReleaseModelSource(
            releaseBase = PocketTtsModels.releaseBase(PocketTtsModels.DEFAULT_VERSION),
            cacheDir = File(appModelDirectory, "models/${PocketTtsModels.DEFAULT_VERSION}"),
            downloader = InterruptibleHttpDownloader(),
            preloadedManifest = pinnedManifest,
        ),
        DirectorySource(appModelDirectory),
    )
    private val config = PocketTtsConfig.default(appContext, models)
    private val requiredFiles by lazy { PocketTtsEngine.requiredFiles(config).distinct() }

    fun status(message: String? = null): PocketModelUiState {
        val missing = requiredFiles.filterNot(models.store::exists)
        return PocketModelUiState(
            installed = missing.isEmpty(),
            installedFiles = requiredFiles.size - missing.size,
            requiredFiles = requiredFiles.size,
            missingFiles = missing,
            message = message,
        )
    }

    suspend fun install(onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }) {
        val missing = status().missingFiles
        if (missing.isEmpty()) return
        runInterruptible(Dispatchers.IO) {
            models.ensure(missing) { downloaded, total -> onProgress(downloaded, total) }
        }
        check(status().installed) { "Pocket TTS model files are still missing after installation." }
    }

    private class InterruptibleHttpDownloader : Downloader {
        override fun download(url: String, dst: File, onProgress: (Long, Long) -> Unit) {
            val partial = File(dst.parentFile, "${dst.name}.part")
            val existingBytes = partial.takeIf(File::isFile)?.length() ?: 0L
            val connection = open(url).apply {
                if (existingBytes > 0L) setRequestProperty("Range", "bytes=$existingBytes-")
            }
            try {
                val responseCode = connection.responseCode
                val resumed = existingBytes > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
                val contentLength = connection.contentLengthLong
                val total = if (contentLength <= 0) -1L else contentLength + if (resumed) existingBytes else 0L
                partial.parentFile?.mkdirs()
                connection.inputStream.use { input ->
                    FileOutputStream(partial, resumed).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var written = if (resumed) existingBytes else 0L
                        while (true) {
                            checkNotCancelled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            written += count
                            onProgress(written, total)
                        }
                    }
                }
                if (!partial.renameTo(dst)) {
                    partial.copyTo(dst, overwrite = true)
                    partial.delete()
                }
            } finally {
                connection.disconnect()
            }
        }

        override fun read(url: String): ByteArray {
            val connection = open(url)
            try {
                connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        checkNotCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    return output.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }

        private fun open(url: String): HttpURLConnection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "EpubReader-PocketTTS")
            }

        private fun checkNotCancelled() {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Pocket TTS model installation was cancelled.")
            }
        }
    }
}
