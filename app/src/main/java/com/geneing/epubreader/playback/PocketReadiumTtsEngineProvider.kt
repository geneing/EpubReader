package com.geneing.epubreader.playback

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import com.geneing.epubreader.data.AppPreferences
import dev.pockettts.PocketTts
import java.util.Locale
import kotlinx.coroutines.suspendCancellableCoroutine
import org.readium.navigator.media.tts.TtsEngine
import org.readium.navigator.media.tts.TtsEngineProvider
import org.readium.navigator.media.tts.android.AndroidTtsDefaults
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.navigator.media.tts.android.AndroidTtsPreferencesEditor
import org.readium.navigator.media.tts.android.AndroidTtsSettings
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.DebugError
import org.readium.r2.shared.util.Error
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.Try
import kotlin.coroutines.resume

/** Readium adapter that selects this app's Pocket TextToSpeechService explicitly. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalReadiumApi::class)
internal class PocketReadiumTtsEngineProvider(
    private val context: Context,
) : TtsEngineProvider<
    AndroidTtsSettings,
    AndroidTtsPreferences,
    AndroidTtsPreferencesEditor,
    AndroidTtsEngine.Error,
    AndroidTtsEngine.Voice,
    > {

    override suspend fun createEngine(
        publication: Publication,
        initialPreferences: AndroidTtsPreferences,
    ): Try<TtsEngine<AndroidTtsSettings, AndroidTtsPreferences, AndroidTtsEngine.Error, AndroidTtsEngine.Voice>, Error> {
        val settings = settings(publication, initialPreferences)
        val engine = PocketReadiumTtsEngine.create(context, settings)
            ?: return Try.failure(DebugError("Pocket TTS service failed to initialize."))
        return Try.success(engine)
    }

    override fun createPreferencesEditor(
        publication: Publication,
        initialPreferences: AndroidTtsPreferences,
    ): AndroidTtsPreferencesEditor = AndroidTtsPreferencesEditor(
        initialPreferences,
        publication.metadata,
        AndroidTtsDefaults(),
    )

    override fun createEmptyPreferences(): AndroidTtsPreferences = AndroidTtsPreferences()

    override fun getPlaybackParameters(settings: AndroidTtsSettings): PlaybackParameters =
        PlaybackParameters(settings.speed.toFloat(), settings.pitch.toFloat())

    override fun updatePlaybackParameters(
        previousPreferences: AndroidTtsPreferences,
        playbackParameters: PlaybackParameters,
    ): AndroidTtsPreferences = previousPreferences.copy(
        speed = playbackParameters.speed.toDouble(),
        pitch = playbackParameters.pitch.toDouble(),
    )

    override fun mapEngineError(error: AndroidTtsEngine.Error): PlaybackException =
        PlaybackException(
            "Pocket TTS error: ${error.message}",
            null,
            PlaybackException.ERROR_CODE_UNSPECIFIED,
        )

    private fun settings(
        publication: Publication,
        preferences: AndroidTtsPreferences,
    ): AndroidTtsSettings = AndroidTtsSettings(
        language = preferences.language ?: publication.metadata.language ?: Language(Locale.getDefault()),
        overrideContentLanguage = preferences.language != null,
        pitch = preferences.pitch ?: 1.0,
        speed = preferences.speed ?: 1.0,
        voices = preferences.voices ?: emptyMap(),
    )
}

@OptIn(ExperimentalReadiumApi::class)
private class PocketReadiumTtsEngine private constructor(
    private val context: Context,
    private val textToSpeech: TextToSpeech,
    initialSettings: AndroidTtsSettings,
    override val voices: Set<AndroidTtsEngine.Voice>,
) : TtsEngine<
    AndroidTtsSettings,
    AndroidTtsPreferences,
    AndroidTtsEngine.Error,
    AndroidTtsEngine.Voice,
    > {

    companion object {
        suspend fun create(
            context: Context,
            settings: AndroidTtsSettings,
        ): PocketReadiumTtsEngine? = suspendCancellableCoroutine { continuation ->
            lateinit var tts: TextToSpeech
            tts = TextToSpeech(context, { status ->
                if (!continuation.isActive) {
                    tts.shutdown()
                } else if (status == TextToSpeech.SUCCESS) {
                    val engineVoices = runCatching { tts.voices.orEmpty() }
                        .getOrDefault(emptySet())
                        .map { voice -> voice.toReadiumVoice() }
                        .toSet()
                    continuation.resume(
                        PocketReadiumTtsEngine(context, tts, settings, engineVoices),
                    )
                } else {
                    tts.shutdown()
                    continuation.resume(null)
                }
            }, context.packageName)
            continuation.invokeOnCancellation {
                runCatching { tts.stop() }
                tts.shutdown()
            }
        }

        private fun android.speech.tts.Voice.toReadiumVoice(): AndroidTtsEngine.Voice =
            AndroidTtsEngine.Voice(
                id = AndroidTtsEngine.Voice.Id(name),
                language = Language(locale),
                quality = when (quality) {
                    android.speech.tts.Voice.QUALITY_VERY_HIGH -> AndroidTtsEngine.Voice.Quality.Highest
                    android.speech.tts.Voice.QUALITY_HIGH -> AndroidTtsEngine.Voice.Quality.High
                    android.speech.tts.Voice.QUALITY_NORMAL -> AndroidTtsEngine.Voice.Quality.Normal
                    android.speech.tts.Voice.QUALITY_LOW -> AndroidTtsEngine.Voice.Quality.Low
                    else -> AndroidTtsEngine.Voice.Quality.Lowest
                },
                requiresNetwork = isNetworkConnectionRequired,
            )
    }

    private var currentListener: TtsEngine.Listener<AndroidTtsEngine.Error>? = null
    private val mutableSettings = kotlinx.coroutines.flow.MutableStateFlow(initialSettings)
    override val settings: kotlinx.coroutines.flow.StateFlow<AndroidTtsSettings> = mutableSettings

    init {
        configureSpeechRateAndPitch(settings.value)
        configureListener()
    }

    override fun submitPreferences(preferences: AndroidTtsPreferences) {
        val previous = mutableSettings.value
        val settings = previous.copy(
            language = preferences.language ?: previous.language,
            overrideContentLanguage = preferences.language != null,
            speed = preferences.speed ?: previous.speed,
            pitch = preferences.pitch ?: previous.pitch,
            voices = preferences.voices ?: previous.voices,
        )
        mutableSettings.value = settings
        configureSpeechRateAndPitch(settings)
    }

    override fun speak(
        requestId: TtsEngine.RequestId,
        text: String,
        language: Language?,
    ) {
        configureVoice(language)
        val result = textToSpeech.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), requestId.value)
        if (result != TextToSpeech.SUCCESS) {
            currentListener?.onError(requestId, AndroidTtsEngine.Error.Synthesis)
        }
    }

    override fun stop() {
        textToSpeech.stop()
    }

    override fun setListener(listener: TtsEngine.Listener<AndroidTtsEngine.Error>?) {
        currentListener = listener
        configureListener()
    }

    override fun close() {
        textToSpeech.setOnUtteranceProgressListener(null)
        textToSpeech.shutdown()
    }

    private fun configureSpeechRateAndPitch(settings: AndroidTtsSettings) {
        textToSpeech.setSpeechRate(settings.speed.toFloat())
        textToSpeech.setPitch(settings.pitch.toFloat())
    }

    private fun configureVoice(utteranceLanguage: Language?) {
        val settings = mutableSettings.value
        val effectiveLanguage = utteranceLanguage
            .takeUnless { settings.overrideContentLanguage }
            ?: settings.language
        val preferredName = settings.voices.entries
            .firstOrNull { (language, _) -> language.locale.language == effectiveLanguage.locale.language }
            ?.value
            ?.value
            ?: PocketTts.voiceId(AppPreferences.pocketTtsVoice(context))
        val selectedVoice = runCatching { textToSpeech.voices.orEmpty().firstOrNull { it.name == preferredName } }
            .getOrNull()
        if (selectedVoice != null) {
            textToSpeech.voice = selectedVoice
        } else {
            textToSpeech.language = effectiveLanguage.locale
        }
    }

    private fun configureListener() {
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                utteranceId?.let { currentListener?.onStart(TtsEngine.RequestId(it)) }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                utteranceId?.let { currentListener?.onRange(TtsEngine.RequestId(it), start until end) }
            }

            override fun onDone(utteranceId: String?) {
                utteranceId?.let { currentListener?.onDone(TtsEngine.RequestId(it)) }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                utteranceId?.let { request ->
                    if (interrupted) {
                        currentListener?.onInterrupted(TtsEngine.RequestId(request))
                    } else {
                        currentListener?.onFlushed(TtsEngine.RequestId(request))
                    }
                }
            }

            @Deprecated("Deprecated by Android TextToSpeech", ReplaceWith("onError(utteranceId, -1)"), level = DeprecationLevel.ERROR)
            override fun onError(utteranceId: String?) {
                onError(utteranceId, TextToSpeech.ERROR_SYNTHESIS)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let {
                    currentListener?.onError(
                        TtsEngine.RequestId(it),
                        errorFrom(errorCode),
                    )
                }
            }
        })
    }

    private fun errorFrom(errorCode: Int): AndroidTtsEngine.Error = when (errorCode) {
        TextToSpeech.ERROR_INVALID_REQUEST -> AndroidTtsEngine.Error.InvalidRequest
        TextToSpeech.ERROR_NETWORK -> AndroidTtsEngine.Error.Network
        TextToSpeech.ERROR_NETWORK_TIMEOUT -> AndroidTtsEngine.Error.NetworkTimeout
        TextToSpeech.ERROR_NOT_INSTALLED_YET -> AndroidTtsEngine.Error.NotInstalledYet
        TextToSpeech.ERROR_OUTPUT -> AndroidTtsEngine.Error.Output
        TextToSpeech.ERROR_SERVICE -> AndroidTtsEngine.Error.Service
        else -> AndroidTtsEngine.Error.Synthesis
    }
}
