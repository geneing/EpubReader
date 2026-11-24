package com.geneing.epubreader.playback

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Stable Media3 player owned by [BookPlaybackService] for as long as the
 * service lives.
 *
 * The narration controllers are replaced when a book changes or a voice is
 * switched, so the session cannot wrap one of their players directly. This
 * player presents the app's single current book from [PlaybackStateStore] and
 * forwards transport actions back to the service. That gives Android Auto a
 * timeline that does not disappear during narration restarts, and it is the
 * place where read-along metadata and artwork are produced.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class BookSessionPlayer(
    private val callbacks: Callbacks,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    interface Callbacks {
        fun onPlayRequested()
        fun onPauseRequested()
        fun onStopRequested()
        fun onSkipPreviousRequested()
        fun onSkipNextRequested()
        fun onRateRequested(rate: Float)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var snapshot: PlaybackUiState = PlaybackStateStore.state.value

    private var coverPath: String? = null
    private var coverBytes: ByteArray? = null

    private var readAlongKey: String? = null
    private var readAlongBytes: ByteArray? = null
    private var pendingReadAlongKey: String? = null
    private var readAlongJob: Job? = null

    init {
        scope.launch {
            PlaybackStateStore.state.collect { state ->
                snapshot = state
                prepareReadAlongArtwork(state)
                invalidateState()
            }
        }
    }

    override fun getState(): State {
        val state = snapshot
        val hasBook = state.bookUri != null
        val metadata = metadataFor(state)
        val item = MediaItem.Builder()
            .setMediaId(state.bookUri ?: IDLE_MEDIA_ID)
            .setMediaMetadata(metadata)
            .build()
        val itemData = MediaItemData.Builder(state.bookUri ?: IDLE_MEDIA_ID)
            .setMediaItem(item)
            .setMediaMetadata(metadata)
            .setDurationUs(C.TIME_UNSET)
            .build()
        val commands = Player.Commands.Builder()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_METADATA)
            .add(Player.COMMAND_SET_MEDIA_ITEM)
            .add(Player.COMMAND_SET_SPEED_AND_PITCH)
            .add(Player.COMMAND_SEEK_BACK)
            .add(Player.COMMAND_SEEK_FORWARD)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .build()
        val rate = state.speechRate.takeIf { it > 0f } ?: 1f
        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(
                state.isPlaying,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            )
            .setPlaybackState(if (hasBook) Player.STATE_READY else Player.STATE_IDLE)
            .setPlaybackParameters(PlaybackParameters(rate))
            .setSeekBackIncrementMs(SEEK_BACK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_INCREMENT_MS)
            .setPlaylist(if (hasBook) listOf(itemData) else emptyList())
            .setCurrentMediaItemIndex(if (hasBook) 0 else C.INDEX_UNSET)
            .setContentPositionMs(C.TIME_UNSET)
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) callbacks.onPlayRequested() else callbacks.onPauseRequested()
        return Futures.immediateFuture(null)
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateFuture(null)

    override fun handleStop(): ListenableFuture<*> {
        callbacks.onStopRequested()
        return Futures.immediateFuture(null)
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateFuture(null)
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        // Selection is handled by MediaLibrarySession.Callback.onSetMediaItems,
        // which starts the narration before the session applies the item.
        return Futures.immediateFuture(null)
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            -> callbacks.onSkipPreviousRequested()

            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            -> callbacks.onSkipNextRequested()
        }
        return Futures.immediateFuture(null)
    }

    override fun handleSetPlaybackParameters(
        playbackParameters: PlaybackParameters,
    ): ListenableFuture<*> {
        callbacks.onRateRequested(playbackParameters.speed)
        return Futures.immediateFuture(null)
    }

    private fun metadataFor(state: PlaybackUiState): MediaMetadata {
        val sentence = state.currentUtterance
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val showText = state.readAlong && sentence != null
        val progressLabel = "${(state.progress * 100).toInt()}% read"
        val bookTitle = state.title.ifBlank { "EpubReader" }
        val author = state.author?.takeIf(String::isNotBlank)
        val builder = MediaMetadata.Builder()
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            .setIsBrowsable(false)
            .setIsPlayable(true)
        if (showText) {
            // Android Auto's now-playing screen gives the title the most space;
            // use it for the sentence while the button is on.
            builder.setTitle(sentence)
                .setArtist(listOfNotNull(bookTitle, author).joinToString(" · "))
                .setSubtitle(progressLabel)
                .setDescription(sentence)
        } else {
            builder.setTitle(bookTitle)
                .setArtist(listOfNotNull(author, progressLabel).joinToString(" · "))
                .setSubtitle(sentence.orEmpty())
        }
        val artwork = artworkFor(state, sentence, bookTitle, progressLabel)
        artwork?.let { bytes ->
            builder.setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        return builder.build()
    }

    private fun artworkFor(
        state: PlaybackUiState,
        sentence: String?,
        bookTitle: String,
        progressLabel: String,
    ): ByteArray? {
        if (state.readAlong && sentence != null) {
            val key = sentence
            if (key == readAlongKey) return readAlongBytes ?: coverFor(state.coverPath)
            return readAlongBytes ?: coverFor(state.coverPath)
        }
        return coverFor(state.coverPath)
    }

    private fun coverFor(path: String?): ByteArray? {
        if (path == coverPath) return coverBytes
        coverPath = path
        coverBytes = path?.let { candidate ->
            runCatching { File(candidate).takeIf(File::isFile)?.readBytes() }.getOrNull()
        }
        return coverBytes
    }

    private fun prepareReadAlongArtwork(state: PlaybackUiState) {
        val sentence = state.currentUtterance?.trim()?.takeIf(String::isNotBlank) ?: return
        if (!state.readAlong) return
        val key = sentence
        if (key == readAlongKey || key == pendingReadAlongKey) return
        pendingReadAlongKey = key
        readAlongJob?.cancel()
        val bookTitle = state.title.ifBlank { "EpubReader" }
        val progressLabel = "${(state.progress * 100).toInt()}% read"
        readAlongJob = scope.launch {
            val bytes = withContext(Dispatchers.Default) {
                ReadAlongArtwork.render(sentence, range = null, bookTitle, progressLabel)
            }
            if (pendingReadAlongKey == key) {
                pendingReadAlongKey = null
                readAlongKey = key
                readAlongBytes = bytes
                invalidateState()
            }
        }
    }

    companion object {
        private const val IDLE_MEDIA_ID = "epubreader:idle"
        private const val SEEK_BACK_INCREMENT_MS = 10_000L
        private const val SEEK_FORWARD_INCREMENT_MS = 10_000L
    }
}
