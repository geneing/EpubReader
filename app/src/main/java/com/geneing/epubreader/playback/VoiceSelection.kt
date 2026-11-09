package com.geneing.epubreader.playback

import android.content.Context
import com.geneing.epubreader.data.AppPreferences
import com.geneing.epubreader.data.SpeechEngine

/**
 * Persists the voice chosen in a picker and, when narration is already running,
 * asks the playback service to continue the current sentence with it.
 */
internal fun persistPlaybackVoice(context: Context, engine: SpeechEngine, voice: String) {
    AppPreferences.setSpeechEngine(context, engine)
    when (engine) {
        SpeechEngine.ANDROID_SYSTEM -> AppPreferences.setSystemTtsVoice(context, voice)
        SpeechEngine.POCKET -> AppPreferences.setPocketTtsVoice(context, voice)
    }
    if (PlaybackStateStore.state.value.showMiniPlayer) {
        PlaybackServiceCommands.changeVoice(context, engine, voice)
    }
}

/** Persists the speech rate and applies it to narration that is already running. */
internal fun persistSpeechRate(context: Context, rate: Float) {
    val bounded = rate.coerceIn(AppPreferences.MIN_SPEECH_RATE, AppPreferences.MAX_SPEECH_RATE)
    AppPreferences.setSpeechRate(context, bounded)
    if (PlaybackStateStore.state.value.showMiniPlayer) {
        PlaybackServiceCommands.setSpeechRate(context, bounded)
    }
}
