package com.geneing.epubreader.playback

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * One voice published by the device's Android TTS engine.
 *
 * Android exposes every voice type through the same [TextToSpeech.voices] API:
 * on-device voices, network voices, voices that are not installed yet, and the
 * ML Kit GenAI voices (`star02`..`star16`) that Google's TTS engine publishes.
 * The app lists them all and lets the engine handle installation/download.
 */
internal data class SystemTtsVoice(
    val id: String,
    val engine: String,
    val locale: Locale,
    /** Short engine voice code, for example `iol`, `sfg` or `star04`. */
    val shortName: String,
    val requiresNetwork: Boolean,
    val installed: Boolean,
    val isMlKit: Boolean,
    /** ML Kit voice id (`en-us-x-star04`), for voices exposed by the ML Kit models. */
    val mlKitVoiceId: String?,
) {
    val localeTag: String get() = locale.toLanguageTag()

    val localeLabel: String
        get() = locale.getDisplayName(Locale.getDefault())
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }

    val usesMlKitModel: Boolean get() = isMlKit

    /** `star04` -> `Star 04 (male)`; other voices keep their engine code. */
    val displayName: String
        get() = when (val gender = ML_KIT_GENDERS[shortName]) {
            null -> shortName.uppercase(Locale.US)
            else -> "Star ${shortName.removePrefix("star")} ($gender)"
        }

    val detail: String
        get() = buildList {
            add(if (requiresNetwork) "Network" else "On-device")
            if (usesMlKitModel) add("ML Kit")
            if (!installed) add("Downloads on first use")
        }.joinToString(" · ")

    private companion object {
        val ML_KIT_GENDERS = mapOf(
            "star02" to "female",
            "star04" to "male",
            "star05" to "male",
            "star08" to "female",
            "star11" to "male",
            "star12" to "female",
            "star13" to "male",
            "star16" to "female",
        )
    }
}

/** Reads the voices the system TTS engine currently publishes. */
internal object SystemTtsVoiceCatalog {

    suspend fun load(context: Context, timeoutMs: Long = 15_000): List<SystemTtsVoice> =
        withContext(Dispatchers.Main) {
            val tts = initialize(context, timeoutMs)
            try {
                val engine = runCatching { tts.defaultEngine }.getOrNull().orEmpty()
                val voices = runCatching { tts.voices }.getOrNull().orEmpty()
                voices.mapNotNull { voice -> toVoice(voice, engine) }
                    .sortedWith(
                        compareBy(
                            { it.localeTag },
                            { it.isMlKit.not() },
                            { it.installed.not() },
                            { it.requiresNetwork },
                            { it.shortName },
                        ),
                    )
            } finally {
                runCatching { tts.shutdown() }
            }
        }

    /** Engine labels for the picker header, keyed by package name. */
    suspend fun engines(context: Context, timeoutMs: Long = 15_000): Map<String, String> =
        withContext(Dispatchers.Main) {
            val tts = initialize(context, timeoutMs)
            try {
                runCatching { tts.engines }
                    .getOrNull()
                    .orEmpty()
                    .associate { it.name to it.label }
            } finally {
                runCatching { tts.shutdown() }
            }
        }

    private suspend fun initialize(context: Context, timeoutMs: Long): TextToSpeech =
        withContext(Dispatchers.Main) {
            lateinit var engine: TextToSpeech
            val initialized = suspendCancellableCoroutine<Boolean> { continuation ->
                engine = TextToSpeech(context.applicationContext) { status ->
                    if (continuation.isActive) continuation.resume(status == TextToSpeech.SUCCESS)
                }
                continuation.invokeOnCancellation { runCatching { engine.shutdown() } }
            }
            check(initialized) { "The Android text-to-speech engine did not start." }
            engine
        }

    private fun toVoice(voice: Voice, engine: String): SystemTtsVoice? {
        val id = voice.name ?: return null
        // `en-us-language` style entries are legacy aliases for a whole locale,
        // not selectable voices, and would only duplicate the real list.
        if (id.endsWith("-language")) return null
        val core = id.substringAfter("-x-", missingDelimiterValue = "")
        val short = core.substringBeforeLast('-').takeIf { it.isNotBlank() }
            ?: id.substringAfterLast('-')
        val features = voice.features.orEmpty()
        return SystemTtsVoice(
            id = id,
            engine = engine,
            locale = voice.locale,
            shortName = short,
            requiresNetwork = voice.isNetworkConnectionRequired,
            installed = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in features,
            isMlKit = short.startsWith("star"),
            mlKitVoiceId = if (short.startsWith("star")) {
                id.removeSuffix("-local").removeSuffix("-network")
            } else {
                null
            },
        )
    }
}
