package com.geneing.epubreader.playback

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.geneing.epubreader.data.SpeechEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.readium.r2.shared.publication.Locator
import java.util.UUID

data class PlaybackUiState(
    val bookUri: String? = null,
    val title: String = "",
    val author: String? = null,
    val coverPath: String? = null,
    val progress: Float = 0f,
    val isPlaying: Boolean = false,
    val showMiniPlayer: Boolean = false,
    val currentLocator: Locator? = null,
    val errorMessage: String? = null,
    /** Sentence currently being narrated, for the phone and car read-along views. */
    val currentUtterance: String? = null,
    /** Word range inside [currentUtterance], when the engine reports word progress. */
    val currentRangeStart: Int? = null,
    val currentRangeEnd: Int? = null,
    /** When on, the car screen shows the narrated sentence instead of the book title/cover. */
    val readAlong: Boolean = false,
    val speechRate: Float = 1f,
)

object PlaybackStateStore {
    private val mutableState = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = mutableState.asStateFlow()

    internal fun update(state: PlaybackUiState) {
        mutableState.value = state
    }

    internal fun clear() {
        mutableState.value = PlaybackUiState()
    }
}

object PlaybackServiceCommands {
    private const val EXTRA_PROCESS_COMMAND_TOKEN = "playback_process_command_token"
    private val processCommandToken: String = UUID.randomUUID().toString()

    const val ACTION_START = "com.geneing.epubreader.playback.START"
    const val ACTION_PLAY = "com.geneing.epubreader.playback.PLAY"
    const val ACTION_PAUSE = "com.geneing.epubreader.playback.PAUSE"
    const val ACTION_TOGGLE = "com.geneing.epubreader.playback.TOGGLE"
    const val ACTION_SKIP_BACK = "com.geneing.epubreader.playback.SKIP_BACK"
    const val ACTION_SKIP_FORWARD = "com.geneing.epubreader.playback.SKIP_FORWARD"
    const val ACTION_STOP = "com.geneing.epubreader.playback.STOP"
    const val ACTION_SEEK_TO_LOCATOR = "com.geneing.epubreader.playback.SEEK_TO_LOCATOR"
    const val ACTION_CHANGE_VOICE = "com.geneing.epubreader.playback.CHANGE_VOICE"
    const val ACTION_SET_RATE = "com.geneing.epubreader.playback.SET_RATE"
    const val ACTION_CYCLE_VOICE = "com.geneing.epubreader.playback.CYCLE_VOICE"
    const val ACTION_CYCLE_RATE = "com.geneing.epubreader.playback.CYCLE_RATE"
    const val ACTION_TOGGLE_READ_ALONG = "com.geneing.epubreader.playback.TOGGLE_READ_ALONG"
    const val ACTION_REFRESH_SETTINGS = "com.geneing.epubreader.playback.REFRESH_SETTINGS"
    const val EXTRA_BOOK_URI = "playback_book_uri"
    const val EXTRA_INITIAL_LOCATOR = "playback_initial_locator"
    const val EXTRA_PROGRESS_PERCENT = "playback_progress_percent"
    const val EXTRA_ALIGN_TO_SELECTION = "playback_align_to_selection"
    const val EXTRA_VOICE_ENGINE = "playback_voice_engine"
    const val EXTRA_VOICE_ID = "playback_voice_id"
    const val EXTRA_SPEECH_RATE = "playback_speech_rate"

    fun start(
        context: Context,
        bookUri: String,
        progressPercent: Double,
        initialLocator: Locator? = null,
        alignToSelection: Boolean = false,
    ) {
        val intent = commandIntent(context, ACTION_START)
            .putExtra(EXTRA_BOOK_URI, bookUri)
            .putExtra(EXTRA_PROGRESS_PERCENT, progressPercent)
            .putExtra(EXTRA_INITIAL_LOCATOR, initialLocator)
            .putExtra(EXTRA_ALIGN_TO_SELECTION, alignToSelection)
        ContextCompat.startForegroundService(context, intent)
    }

    fun send(context: Context, action: String) {
        context.startService(commandIntent(context, action))
    }

    fun seekToLocator(context: Context, locator: Locator) {
        context.startService(
            commandIntent(context, ACTION_SEEK_TO_LOCATOR)
                .putExtra(EXTRA_INITIAL_LOCATOR, locator),
        )
    }

    /**
     * Switches the running narration to another voice. The engine and voice are
     * persisted by the caller; the service restarts the controller at the
     * current sentence so playback continues with the new voice.
     */
    fun changeVoice(context: Context, engine: SpeechEngine, voice: String) {
        context.startService(
            commandIntent(context, ACTION_CHANGE_VOICE)
                .putExtra(EXTRA_VOICE_ENGINE, engine.storageKey)
                .putExtra(EXTRA_VOICE_ID, voice),
        )
    }

    /** Changes the pace of the running narration without restarting the session. */
    fun setSpeechRate(context: Context, rate: Float) {
        context.startService(
            commandIntent(context, ACTION_SET_RATE)
                .putExtra(EXTRA_SPEECH_RATE, rate),
        )
    }

    /** Steps to the next voice of the current engine, keeping the current sentence. */
    fun cycleVoice(context: Context) = send(context, ACTION_CYCLE_VOICE)

    /** Steps through the available speech rates. */
    fun cycleSpeechRate(context: Context) = send(context, ACTION_CYCLE_RATE)

    /** Toggles showing the narrated sentence on the car now-playing screen. */
    fun toggleReadAlong(context: Context) = send(context, ACTION_TOGGLE_READ_ALONG)

    internal fun isAuthorized(intent: Intent?): Boolean =
        intent?.getStringExtra(EXTRA_PROCESS_COMMAND_TOKEN) == processCommandToken

    private fun commandIntent(context: Context, action: String): Intent =
        Intent(context, BookPlaybackService::class.java)
            .setAction(action)
            .putExtra(EXTRA_PROCESS_COMMAND_TOKEN, processCommandToken)

    fun refreshSettings(context: Context) {
        if (PlaybackStateStore.state.value.showMiniPlayer) send(context, ACTION_REFRESH_SETTINGS)
    }
}
