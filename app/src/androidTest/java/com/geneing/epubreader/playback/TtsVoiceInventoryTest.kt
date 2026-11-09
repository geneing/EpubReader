package com.geneing.epubreader.playback

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.texttospeech.SpeechSynthesis
import com.google.mlkit.genai.texttospeech.SpeechSynthesizerOptions
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnostics for the narration voice picker: logs every voice the device's
 * Android TTS engine and the ML Kit GenAI TTS client expose, so the lists can
 * be compared against what the UI shows on a real device.
 *
 * Run with:
 * `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.geneing.epubreader.playback.TtsVoiceInventoryTest`
 * then `adb logcat -d -s TtsVoiceInventory`.
 */
@RunWith(AndroidJUnit4::class)
class TtsVoiceInventoryTest {

    @Test
    fun logsSystemAndMlKitVoices() {
        logSystemVoices()
        logMlKitVoices()
        speakWithVoice("en-us-x-star04-local")
        speakWithVoice("en-gb-x-star04-local")
        speakWithVoice("en-us-x-sfg-local")
    }

    /** Proves whether a voice from the platform list actually synthesizes. */
    private fun speakWithVoice(voiceName: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = CountDownLatch(1)
        val tts = TextToSpeech(context) { ready.countDown() }
        check(ready.await(15, TimeUnit.SECONDS)) { "TextToSpeech did not initialize" }
        try {
            val voice = tts.voices.firstOrNull { it.name == voiceName }
            if (voice == null) {
                Log.w(TAG, "speak: voice $voiceName is not published by the engine")
                return
            }
            val setResult = tts.setVoice(voice)
            val done = CountDownLatch(1)
            val events = StringBuilder()
            tts.setOnUtteranceProgressListener(
                object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        events.append("start ")
                    }

                    override fun onDone(utteranceId: String?) {
                        events.append("done ")
                        done.countDown()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        events.append("error ")
                        done.countDown()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        events.append("error($errorCode) ")
                        done.countDown()
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        events.append("stop ")
                        done.countDown()
                    }
                },
            )
            val speakResult = tts.speak(
                "EpubReader tests the selected narration voice.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "voice-test-$voiceName",
            )
            val finished = done.await(20, TimeUnit.SECONDS)
            Log.i(
                TAG,
                "speak voice=$voiceName setVoice=$setResult speak=$speakResult " +
                    "finished=$finished events=[${events.toString().trim()}]",
            )
        } finally {
            tts.shutdown()
        }
    }

    private fun logSystemVoices() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        val tts = TextToSpeech(context) { result ->
            status = result
            ready.countDown()
        }
        check(ready.await(15, TimeUnit.SECONDS)) { "TextToSpeech did not initialize" }
        try {
            Log.i(TAG, "system default engine=${tts.defaultEngine}")
            tts.engines.forEach { engine ->
                Log.i(TAG, "engine package=${engine.name} label=${engine.label}")
            }
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TextToSpeech init status=$status")
            }
            val voices = runCatching { tts.voices }.getOrNull().orEmpty()
                .sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))
            Log.i(TAG, "system voice count=${voices.size}")
            voices.forEach { voice -> Log.i(TAG, "voice ${describe(voice)}") }
            Log.i(TAG, "device locales=${Locale.getAvailableLocales().size}")
        } finally {
            tts.shutdown()
        }
    }

    private fun describe(voice: Voice): String {
        val features = voice.features.orEmpty()
        val flags = buildList {
            if (voice.isNetworkConnectionRequired) add("network")
            if (TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in features) add("notInstalled")
            if (TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS in features) add("embedded")
            if (TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS in features) add("networkSynthesis")
        }
        return "id=${voice.name} locale=${voice.locale.toLanguageTag()} " +
            "quality=${voice.quality} latency=${voice.latency} flags=${flags.joinToString(",")} " +
            "features=$features"
    }

    private fun logMlKitVoices() = runBlocking {
        val options = SpeechSynthesizerOptions.builder(Locale.US).build()
        val client = runCatching { SpeechSynthesis.getClient(options) }.getOrElse { error ->
            Log.w(TAG, "ML Kit TTS client unavailable: ${error.message}")
            return@runBlocking
        }
        try {
            val locales = listOf(
                Locale.US, Locale.UK, Locale.GERMANY, Locale.FRANCE, Locale.CANADA_FRENCH,
                Locale("es", "ES"), Locale("es", "US"), Locale.ITALY, Locale("pt", "BR"),
                Locale("ja", "JP"), Locale("ko", "KR"), Locale("hi", "IN"),
                Locale("ar", "XA"), Locale("da", "DK"), Locale("nb", "NO"),
                Locale("nl", "NL"), Locale("pl", "PL"), Locale("ru", "RU"),
                Locale("sv", "SE"), Locale("tr", "TR"), Locale("id", "ID"),
            )
            for (locale in locales) {
                val voices = runCatching { client.getSupportedVoices(locale) }.getOrElse { error ->
                    Log.w(TAG, "ML Kit voices for ${locale.toLanguageTag()} failed: ${error.message}")
                    emptyList()
                }
                voices.forEach { voice ->
                    val status = runCatching { client.checkStatus(voice) }.getOrNull()
                    Log.i(
                        TAG,
                        "mlkit locale=${locale.toLanguageTag()} id=${voice.name} " +
                            "voiceLocale=${voice.locale.toLanguageTag()} gender=${voice.gender} " +
                            "status=${statusName(status)}",
                    )
                }
            }
        } finally {
            runCatching { client.close() }
        }
    }

    private fun statusName(status: Int?): String = when (status) {
        FeatureStatus.AVAILABLE -> "AVAILABLE($status)"
        FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE($status)"
        FeatureStatus.DOWNLOADING -> "DOWNLOADING($status)"
        FeatureStatus.UNAVAILABLE -> "UNAVAILABLE($status)"
        else -> "unknown($status)"
    }

    private companion object {
        const val TAG = "TtsVoiceInventory"
    }
}
