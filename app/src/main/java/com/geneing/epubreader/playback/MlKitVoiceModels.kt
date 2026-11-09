package com.geneing.epubreader.playback

import android.content.Context
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.texttospeech.SpeechSynthesis
import com.google.mlkit.genai.texttospeech.SpeechSynthesizer
import com.google.mlkit.genai.texttospeech.SpeechSynthesizerOptions
import com.google.mlkit.genai.texttospeech.VoiceInfo
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * ML Kit GenAI voices (`star02`..`star16`).
 *
 * Google's TTS engine already publishes these voice models through the regular
 * [android.speech.tts.TextToSpeech] voice list, so playback stays on the system
 * engine. ML Kit is used here for the two things the platform API does not
 * expose: the download state of a voice model, and downloading it before the
 * first utterance. A downloadable voice is fetched with
 * [SpeechSynthesizer.download] and only then selected for playback.
 */
internal class MlKitVoiceModels {

    private val lock = Mutex()
    private val voicesByLocale = mutableMapOf<String, List<VoiceInfo>>()
    private var clientInstance: SpeechSynthesizer? = null

    private fun client(): SpeechSynthesizer? {
        clientInstance?.let { return it }
        return runCatching {
            SpeechSynthesis.getClient(
                SpeechSynthesizerOptions.builder(Locale.getDefault()).build(),
            )
        }.getOrNull()?.also { clientInstance = it }
    }

    /** ML Kit feature status per ML Kit voice id for [locale]. Empty when unavailable. */
    suspend fun statuses(locale: Locale): Map<String, Int> = lock.withLock {
        val client = client()
        if (client == null) {
            android.util.Log.w("MlKitVoiceModels", "ML Kit TTS client unavailable")
            return@withLock emptyMap()
        }
        val voices = voicesLocked(client, locale)
        if (voices == null) {
            android.util.Log.w("MlKitVoiceModels", "No ML Kit voices for $locale")
            return@withLock emptyMap()
        }
        val statuses = voices.mapNotNull { voice ->
            val status = runCatching { client.checkStatus(voice) }.getOrNull()
            status?.let { voice.name to it }
        }.toMap()
        android.util.Log.i("MlKitVoiceModels", "statuses($locale)=$statuses")
        statuses
    }

    /**
     * Makes sure the ML Kit model for [voiceId] is on the device. Returns true
     * when the voice is ready (available now or after this download).
     */
    suspend fun ensureDownloaded(
        locale: Locale,
        voiceId: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Boolean = lock.withLock {
        val client = client() ?: return@withLock false
        val voices = voicesLocked(client, locale) ?: return@withLock false
        val bare = voiceId.removeSuffix("-local").removeSuffix("-network")
        val voice = voices.firstOrNull { it.name == bare || it.name == voiceId }
            ?: return@withLock false
        when (runCatching { client.checkStatus(voice) }.getOrNull()) {
            FeatureStatus.AVAILABLE -> return@withLock true
            FeatureStatus.UNAVAILABLE -> return@withLock false
            else -> Unit
        }
        var completed = false
        var total = -1L
        withContext(Dispatchers.Main.immediate) {
            runCatching {
                client.download(voice).collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted -> total = status.bytesToDownload
                        is DownloadStatus.DownloadProgress ->
                            onProgress(status.totalBytesDownloaded, total)
                        DownloadStatus.DownloadCompleted -> completed = true
                        is DownloadStatus.DownloadFailed -> Unit
                    }
                }
            }
        }
        if (!completed) {
            completed = runCatching { client.checkStatus(voice) }.getOrNull() ==
                FeatureStatus.AVAILABLE
        }
        android.util.Log.i(
            "MlKitVoiceModels",
            "ensureDownloaded($voiceId) completed=$completed progress=$total",
        )
        completed
    }

    fun close() {
        runCatching { clientInstance?.close() }
        clientInstance = null
        voicesByLocale.clear()
    }

    private suspend fun voicesLocked(
        client: SpeechSynthesizer,
        locale: Locale,
    ): List<VoiceInfo>? {
        val key = locale.toLanguageTag()
        voicesByLocale[key]?.let { return it }
        val voices = runCatching { client.getSupportedVoices(locale) }.getOrNull()
            ?: return null
        voicesByLocale[key] = voices
        return voices
    }
}
