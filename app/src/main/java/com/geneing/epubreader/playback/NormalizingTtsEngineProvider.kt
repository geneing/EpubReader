package com.geneing.epubreader.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import com.geneing.epubreader.playback.normalization.TextNormalizer
import org.readium.navigator.media.tts.TtsEngine
import org.readium.navigator.media.tts.TtsEngineProvider
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsEngineProvider
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.navigator.media.tts.android.AndroidTtsPreferencesEditor
import org.readium.navigator.media.tts.android.AndroidTtsSettings
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Error
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.Try

/**
 * Wraps Readium's [AndroidTtsEngineProvider] so every Android System TTS
 * utterance is rewritten by the app's [TextNormalizer] pipeline before it is
 * spoken.
 *
 * Readium owns the System TTS loop, so the only place to change the spoken
 * string is the engine it drives. Readium's own sentence tokenizer cannot do
 * this: `TextContentTokenizer` returns source offsets that are sliced straight
 * out of the book text, so expansion has to happen at the engine boundary.
 *
 * Normalization follows the utterance language through
 * [TextNormalizer.forLanguage], falling back to the publication language and
 * then English. A missing/default language therefore still normalizes with the
 * English pipeline. The resolver can be supplied directly, which keeps the
 * rewrite testable without an Android engine.
 */
@OptIn(ExperimentalReadiumApi::class)
internal class NormalizingTtsEngineProvider(
    private val delegate: AndroidTtsEngineProvider,
    private val normalizerFor: (Language?) -> TextNormalizer = TextNormalizer::forLanguage,
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
    ): Try<
        TtsEngine<AndroidTtsSettings, AndroidTtsPreferences, AndroidTtsEngine.Error, AndroidTtsEngine.Voice>,
        Error,
        > =
        delegate.createEngine(publication, initialPreferences)
            .map { engine ->
                NormalizingEngine(engine, normalizerFor, publication.metadata.language)
            }

    override fun createPreferencesEditor(
        publication: Publication,
        initialPreferences: AndroidTtsPreferences,
    ): AndroidTtsPreferencesEditor =
        delegate.createPreferencesEditor(publication, initialPreferences)

    override fun createEmptyPreferences(): AndroidTtsPreferences =
        delegate.createEmptyPreferences()

    override fun getPlaybackParameters(settings: AndroidTtsSettings): PlaybackParameters =
        delegate.getPlaybackParameters(settings)

    override fun updatePlaybackParameters(
        previousPreferences: AndroidTtsPreferences,
        playbackParameters: PlaybackParameters,
    ): AndroidTtsPreferences =
        delegate.updatePlaybackParameters(previousPreferences, playbackParameters)

    override fun mapEngineError(error: AndroidTtsEngine.Error): PlaybackException =
        delegate.mapEngineError(error)

    /**
     * A [TtsEngine] that rewrites utterance text before handing it to the
     * wrapped engine. Every other operation delegates unchanged.
     *
     * The normalizer is chosen per utterance with [normalizerFor], falling back
     * to [publicationLanguage] when Readium reports none; `null` resolves to the
     * English pipeline. The engine's range callbacks then report offsets into
     * the normalized string; `ReadiumNarrationController` normalizes
     * `location.utterance` with the same language gate so the reader highlight
     * stays aligned.
     */
    internal class NormalizingEngine(
        private val delegate: TtsEngine<
            AndroidTtsSettings,
            AndroidTtsPreferences,
            AndroidTtsEngine.Error,
            AndroidTtsEngine.Voice,
            >,
        private val normalizerFor: (Language?) -> TextNormalizer = TextNormalizer::forLanguage,
        private val publicationLanguage: Language? = null,
    ) : TtsEngine<
        AndroidTtsSettings,
        AndroidTtsPreferences,
        AndroidTtsEngine.Error,
        AndroidTtsEngine.Voice,
        > {

        /** The text that was last handed to [delegate], for tests. */
        internal var lastSpokenText: String? = null
            private set

        override val settings get() = delegate.settings
        override val voices get() = delegate.voices

        override fun speak(requestId: TtsEngine.RequestId, text: String, language: Language?) {
            val spoken = normalizerFor(language ?: publicationLanguage).normalize(text)
            lastSpokenText = spoken
            delegate.speak(requestId, spoken, language)
        }

        override fun stop() = delegate.stop()

        override fun setListener(listener: TtsEngine.Listener<AndroidTtsEngine.Error>?) =
            delegate.setListener(listener)

        override fun submitPreferences(preferences: AndroidTtsPreferences) =
            delegate.submitPreferences(preferences)

        override fun close() = delegate.close()
    }
}
