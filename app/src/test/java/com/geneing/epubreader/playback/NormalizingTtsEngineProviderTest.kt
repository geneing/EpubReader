package com.geneing.epubreader.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.readium.navigator.media.tts.TtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.navigator.media.tts.android.AndroidTtsSettings
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.Language

/**
 * Verifies the Android System TTS seam: the engine wrapper must rewrite the
 * utterance text before it reaches the underlying engine, leaving everything
 * else (settings, voices, listener, stop/close) untouched.
 */
@OptIn(ExperimentalReadiumApi::class)
class NormalizingTtsEngineProviderTest {

    @Test
    fun speakNormalizesTheUtteranceBeforeDelegating() {
        val delegate = FakeEngine()
        val engine = NormalizingTtsEngineProvider.NormalizingEngine(delegate)

        engine.speak(TtsEngine.RequestId("1"), "Mr. Smith paid $1,234 in 1999.", Language("en"))

        assertEquals(
            "Mister Smith paid one thousand two hundred thirty-four dollars in nineteen ninety-nine.",
            delegate.spoken,
        )
    }

    @Test
    fun nonEnglishTextIsPassedThroughUnchanged() {
        val delegate = FakeEngine()
        val engine = NormalizingTtsEngineProvider.NormalizingEngine(delegate)

        // The wrapper always uses the English pipeline; prose without English
        // patterns is left as-is.
        engine.speak(TtsEngine.RequestId("1"), "Bonjour le monde", Language("fr"))

        assertEquals("Bonjour le monde", delegate.spoken)
    }

    @Test
    fun otherOperationsDelegateUnchanged() {
        val delegate = FakeEngine()
        val engine = NormalizingTtsEngineProvider.NormalizingEngine(delegate)

        engine.stop()
        engine.close()
        engine.setListener(null)

        assertEquals(true, delegate.stopped)
        assertEquals(true, delegate.closed)
        assertEquals(true, delegate.listenerCleared)
        assertEquals(delegate.settings, engine.settings)
        assertEquals(delegate.voices, engine.voices)
    }

    private class FakeEngine : TtsEngine<
        AndroidTtsSettings,
        AndroidTtsPreferences,
        AndroidTtsEngine.Error,
        AndroidTtsEngine.Voice,
        > {

        var spoken: String? = null
        var stopped = false
        var closed = false
        var listenerCleared = false

        override val settings: StateFlow<AndroidTtsSettings> = MutableStateFlow(
            AndroidTtsSettings(
                language = Language("en"),
                overrideContentLanguage = false,
                pitch = 1.0,
                speed = 1.0,
                voices = emptyMap(),
            ),
        )

        override val voices: Set<AndroidTtsEngine.Voice> = emptySet()

        override fun speak(requestId: TtsEngine.RequestId, text: String, language: Language?) {
            spoken = text
        }

        override fun stop() {
            stopped = true
        }

        override fun setListener(listener: TtsEngine.Listener<AndroidTtsEngine.Error>?) {
            if (listener == null) listenerCleared = true
        }

        override fun submitPreferences(preferences: AndroidTtsPreferences) = Unit

        override fun close() {
            closed = true
        }
    }
}
