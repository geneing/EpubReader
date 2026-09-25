package com.geneing.epubreader.playback

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
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
    const val ACTION_REFRESH_SETTINGS = "com.geneing.epubreader.playback.REFRESH_SETTINGS"
    const val EXTRA_BOOK_URI = "playback_book_uri"
    const val EXTRA_INITIAL_LOCATOR = "playback_initial_locator"
    const val EXTRA_PROGRESS_PERCENT = "playback_progress_percent"
    const val EXTRA_ALIGN_TO_SELECTION = "playback_align_to_selection"

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
