package com.geneing.epubreader.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.pockettts.PocketTts
import dev.pockettts.PocketTtsEngine
import java.util.Random
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/**
 * App-owned Pocket TTS narration loop.
 *
 * Sentences are read from the publication with [PublicationSentenceIterator] and
 * rendered ahead of playback into an in-memory PCM cache. Pocket TTS generates
 * faster than real time, so a single engine worker can keep the cache filled to
 * about [TARGET_BUFFER_FRAMES] of speech even though the current sentence is
 * still playing. Audio is written to an [AudioTrack] with a small randomized
 * silence between sentences.
 *
 * Playback position is tracked from the AudioTrack head, so the current sentence
 * (and therefore the reader highlight) advances with what is actually audible.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalReadiumApi::class)
internal class PocketNarrationSession private constructor(
    private val appContext: Context,
    private val publication: Publication,
    private val title: String,
    private val author: String?,
    private val voice: String,
    private val rate: Float,
    private val pitch: Float,
    private val mediaId: String,
) : NarrationController {

    private class Segment(
        val sentence: PublicationSentenceIterator.Sentence,
        val pcm: FloatArray,
        val pauseFrames: Int,
        val endFrame: Long,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine: PocketTtsEngine by lazy { PocketTtsRuntime.engine(appContext) }
    private val iterator = PublicationSentenceIterator(publication, null)
    private val iteratorMutex = Mutex()
    private val audioManager: AudioManager = appContext.getSystemService(AudioManager::class.java)
    private val random = Random()

    private val lock = Any()
    private val pending = ArrayDeque<PublicationSentenceIterator.Sentence>()
    private val segments = ArrayList<Segment>()
    private var writeIndex = 0
    private var playedIndex = -1
    private var renderedFrames = 0L
    private var playedFrames = 0L
    private var generation = 0L
    private var exhausted = false
    private var endNotified = false
    private var started = false
    private var closed = false
    private var currentSentence: PublicationSentenceIterator.Sentence? = null

    @Volatile
    private var playWhenReady = false

    @Volatile
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var trackGeneration = -1L

    private val mutablePlayback = MutableStateFlow(NarrationPlayback(playWhenReady = false))
    override val playback: StateFlow<NarrationPlayback> = mutablePlayback.asStateFlow()

    private val mutableLocation = MutableStateFlow(placeholderLocation())
    override val location: StateFlow<NarrationLocation> = mutableLocation.asStateFlow()

    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { change -> onAudioFocusChange(change) }
            .build()

    private var sessionPlayer: SessionPlayer? = null

    override fun play() {
        if (closed) return
        playWhenReady = true
        mutablePlayback.value = NarrationPlayback(playWhenReady = true)
        ensureStarted()
        runCatching { audioTrack?.play() }
        requestFocus()
    }

    override fun pause() {
        if (closed) return
        playWhenReady = false
        mutablePlayback.value = mutablePlayback.value.copy(playWhenReady = false)
        runCatching { audioTrack?.pause() }
        abandonFocus()
    }

    override fun go(locator: Locator) {
        if (closed) return
        scope.launch {
            val first = iteratorMutex.withLock {
                iterator.seek(locator)
                iterator.next()
            }
            if (first != null) startAt(first) else markExhausted()
        }
    }

    override fun skipToNextUtterance() {
        if (closed) return
        scope.launch {
            val buffered = synchronized(lock) { segments.getOrNull(playedIndex + 1)?.sentence }
            if (buffered != null) {
                alignAndStart(buffered)
                return@launch
            }
            val next = iteratorMutex.withLock { iterator.next() }
            if (next != null) startAt(next) else markExhausted()
        }
    }

    override fun skipToPreviousUtterance() {
        if (closed) return
        scope.launch {
            val buffered = synchronized(lock) { segments.getOrNull(playedIndex - 1)?.sentence }
            if (buffered != null) alignAndStart(buffered)
        }
    }

    override fun hasNextUtterance(): Boolean = !exhausted

    override fun asMedia3Player(): Player {
        return sessionPlayer ?: SessionPlayer().also { sessionPlayer = it }
    }

    override fun close() {
        if (closed) return
        closed = true
        playWhenReady = false
        abandonFocus()
        releaseTrack()
        scope.cancel()
        sessionPlayer?.let { runCatching { it.release() } }
        sessionPlayer = null
    }

    // ---- playback state handling -----------------------------------------

    private fun ensureStarted() {
        synchronized(lock) {
            if (started || closed) return
            started = true
        }
        scope.launch { renderLoop() }
        scope.launch(Dispatchers.IO) { writeLoop() }
        scope.launch { positionLoop() }
    }

    private fun startAt(sentence: PublicationSentenceIterator.Sentence) {
        synchronized(lock) {
            pending.clear()
            pending.add(sentence)
            segments.clear()
            writeIndex = 0
            playedIndex = -1
            renderedFrames = 0L
            playedFrames = 0L
            exhausted = false
            endNotified = false
            generation++
            currentSentence = sentence
        }
        releaseTrack()
        mutableLocation.value = locationFor(sentence)
        if (playWhenReady) ensureStarted()
    }

    private suspend fun alignAndStart(target: PublicationSentenceIterator.Sentence) {
        val found = iteratorMutex.withLock {
            iterator.seek(locatorFor(target))
            repeat(MAX_ALIGN) {
                val candidate = iterator.next() ?: return@withLock null
                if (normalize(candidate.text) == normalize(target.text)) {
                    return@withLock candidate
                }
            }
            null
        }
        startAt(found ?: target)
    }

    private fun markExhausted() {
        val finished = synchronized(lock) {
            exhausted = true
            !endNotified && segments.isEmpty()
        }
        if (finished) notifyEnded()
    }

    private fun notifyEnded() {
        if (closed) return
        synchronized(lock) { endNotified = true }
        playWhenReady = false
        mutablePlayback.value = NarrationPlayback(playWhenReady = false, ended = true)
        runCatching { audioTrack?.pause() }
        abandonFocus()
    }

    // ---- rendering --------------------------------------------------------

    private suspend fun renderLoop() {
        while (scope.isActive && !closed) {
            val buffered = synchronized(lock) { pending.removeFirstOrNull() }
            val forGeneration: Long
            val sentence: PublicationSentenceIterator.Sentence?
            if (buffered != null) {
                forGeneration = synchronized(lock) { generation }
                sentence = buffered
            } else {
                val shouldRender = synchronized(lock) {
                    !exhausted && renderedFrames - playedFrames < TARGET_BUFFER_FRAMES
                }
                if (!shouldRender) {
                    delay(25)
                    continue
                }
                forGeneration = synchronized(lock) { generation }
                sentence = iteratorMutex.withLock { iterator.next() }
            }
            if (sentence == null) {
                markExhausted()
                delay(25)
                continue
            }
            val pcm = renderSentence(sentence)
            if (closed) return
            synchronized(lock) {
                if (generation != forGeneration) return@synchronized
                val start = renderedFrames
                val pause = pauseFrames()
                val end = start + pcm.size + pause
                segments.add(Segment(sentence, pcm, pause, end))
                renderedFrames = end
            }
        }
    }

    private fun renderSentence(sentence: PublicationSentenceIterator.Sentence): FloatArray {
        val parts = ArrayList<FloatArray>()
        val session = engine.newSession(voice)
        try {
            session.rate = rate
            session.pitch = pitch
            session.stream(sentence.text) { chunk ->
                if (chunk.isNotEmpty()) parts.add(chunk)
            }
        } finally {
            runCatching { session.close() }
        }
        var total = 0
        for (part in parts) total += part.size
        val out = FloatArray(total)
        var offset = 0
        for (part in parts) {
            System.arraycopy(part, 0, out, offset, part.size)
            offset += part.size
        }
        return out
    }

    private fun pauseFrames(): Int {
        val ms = (BASE_PAUSE_MS + random.nextGaussian() * PAUSE_JITTER_MS).coerceAtLeast(0.0)
        return (ms / 1000.0 * PocketTts.SAMPLE_RATE).roundToInt()
    }

    // ---- writing to the AudioTrack ---------------------------------------

    private suspend fun writeLoop() {
        var seenGeneration = -1L
        while (scope.isActive && !closed) {
            val segment: Segment?
            val shouldPlay: Boolean
            var resetTrack = false
            synchronized(lock) {
                if (generation != seenGeneration) {
                    seenGeneration = generation
                    writeIndex = 0
                    resetTrack = true
                }
                segment = segments.getOrNull(writeIndex)
                shouldPlay = playWhenReady
            }
            if (resetTrack) releaseTrack()
            if (segment == null) {
                delay(15)
                continue
            }
            if (!shouldPlay) {
                runCatching { audioTrack?.pause() }
                delay(20)
                continue
            }
            if (generationChanged(seenGeneration)) continue
            val track = ensureTrack()
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                runCatching { track.play() }
            }
            if (writeSegment(track, segment, seenGeneration)) {
                synchronized(lock) {
                    if (generation == seenGeneration) writeIndex++
                }
            }
        }
    }

    private fun generationChanged(expected: Long): Boolean =
        synchronized(lock) { generation != expected }

    /** Returns true when the whole segment was handed to the track. */
    private fun writeSegment(track: AudioTrack, segment: Segment, expectedGeneration: Long): Boolean {
        val pcmBytes = toPcm16(segment.pcm)
        if (!writeBytes(track, pcmBytes, expectedGeneration)) return false
        val silence = ByteArray(segment.pauseFrames * 2)
        return writeBytes(track, silence, expectedGeneration)
    }

    private fun writeBytes(track: AudioTrack, bytes: ByteArray, expectedGeneration: Long): Boolean {
        var offset = 0
        while (offset < bytes.size) {
            val size = minOf(FLUSH_CHUNK_BYTES, bytes.size - offset)
            val written = try {
                track.write(bytes, offset, size, AudioTrack.WRITE_BLOCKING)
            } catch (_: Exception) {
                -1
            }
            if (written <= 0) {
                if (generationChanged(expectedGeneration)) return false
                // Unrecoverable track error: drop the rest of this segment.
                releaseTrack()
                return true
            }
            offset += written
            if (generationChanged(expectedGeneration)) return false
        }
        return true
    }

    private fun toPcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var offset = 0
        for (sample in samples) {
            val value = (sample.coerceIn(-1f, 1f) * 32767f).toInt()
            out[offset++] = (value and 0xFF).toByte()
            out[offset++] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun ensureTrack(): AudioTrack {
        audioTrack?.let { return it }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(PocketTts.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(
            PocketTts.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer, PocketTts.SAMPLE_RATE / 2 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        trackGeneration = synchronized(lock) { generation }
        return track
    }

    private fun releaseTrack() {
        val track = audioTrack ?: return
        audioTrack = null
        trackGeneration = -1L
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.release() }
    }

    // ---- position tracking ------------------------------------------------

    private suspend fun positionLoop() {
        while (scope.isActive && !closed) {
            delay(40)
            val track = audioTrack
            val upToDate = trackGeneration == synchronized(lock) { generation }
            if (track != null && upToDate && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                var updated: PublicationSentenceIterator.Sentence? = null
                var finished = false
                synchronized(lock) {
                    playedFrames = head
                    var index = -1
                    for (i in segments.indices) {
                        if (head < segments[i].endFrame) {
                            index = i
                            break
                        }
                    }
                    if (index != playedIndex) {
                        playedIndex = index
                        if (index >= 0) {
                            val sentence = segments[index].sentence
                            if (sentence !== currentSentence) {
                                currentSentence = sentence
                                updated = sentence
                            }
                        }
                    }
                    pruneLocked()
                    val lastEnd = segments.lastOrNull()?.endFrame ?: 0L
                    if (exhausted && !endNotified && playedFrames >= lastEnd) {
                        endNotified = true
                        finished = true
                    }
                }
                updated?.let { mutableLocation.value = locationFor(it) }
                if (finished) notifyEnded()
            } else {
                val finished = synchronized(lock) {
                    if (exhausted && !endNotified && segments.isEmpty()) {
                        endNotified = true
                        true
                    } else {
                        false
                    }
                }
                if (finished) notifyEnded()
            }
        }
    }

    private fun pruneLocked() {
        while (segments.size > MAX_SEGMENTS &&
            segments[0].endFrame < playedFrames - 2L * PocketTts.SAMPLE_RATE
        ) {
            segments.removeAt(0)
            if (playedIndex > 0) playedIndex--
            if (writeIndex > 0) writeIndex--
        }
    }

    // ---- audio focus ------------------------------------------------------

    private fun requestFocus() {
        runCatching { audioManager.requestAudioFocus(focusRequest) }
    }

    private fun abandonFocus() {
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    private fun onAudioFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> pause()

            else -> Unit
        }
    }

    // ---- location ---------------------------------------------------------

    private fun locatorFor(sentence: PublicationSentenceIterator.Sentence): Locator {
        val link = publication.readingOrder[sentence.resourceIndex]
        val base = publication.locatorFromLink(link)
            ?: error("Reading order item has no locator.")
        return base.copy(locations = sentence.locations, text = sentence.textContext)
    }

    private fun locationFor(sentence: PublicationSentenceIterator.Sentence): NarrationLocation {
        val locator = locatorFor(sentence)
        return NarrationLocation(
            href = locator.href,
            utterance = sentence.text,
            range = null,
            utteranceLocator = locator,
        )
    }

    private fun placeholderLocation(): NarrationLocation {
        val link = publication.readingOrder.firstOrNull()
            ?: error("This EPUB does not provide readable text for narration.")
        val locator = publication.locatorFromLink(link)
            ?: error("This EPUB does not provide readable text for narration.")
        return NarrationLocation(locator.href, "", null, locator)
    }

    private fun normalize(text: String): String = WHITESPACE_REGEX.replace(text, " ").trim()

    // ---- Media3 session player -------------------------------------------

    private inner class SessionPlayer : SimpleBasePlayer(Looper.getMainLooper()) {

        private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        init {
            playerScope.launch { playback.collect { invalidateState() } }
            playerScope.launch { location.collect { invalidateState() } }
        }

        override fun getState(): SimpleBasePlayer.State {
            val metadata = MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(author)
                .build()
            val mediaItem = MediaItem.Builder()
                .setMediaId(mediaId)
                .setMediaMetadata(metadata)
                .build()
            val itemData = SimpleBasePlayer.MediaItemData.Builder(mediaId)
                .setMediaItem(mediaItem)
                .setMediaMetadata(metadata)
                .setDurationUs(C.TIME_UNSET)
                .build()
            val commands = Player.Commands.Builder()
                .add(Player.COMMAND_PLAY_PAUSE)
                .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_GET_TIMELINE)
                .build()
            val state = playback.value
            return SimpleBasePlayer.State.Builder()
                .setAvailableCommands(commands)
                .setPlayWhenReady(
                    state.playWhenReady,
                    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
                )
                .setPlaybackState(
                    when {
                        state.failed -> Player.STATE_IDLE
                        state.ended -> Player.STATE_ENDED
                        else -> Player.STATE_READY
                    },
                )
                .setPlaylist(listOf(itemData))
                .setCurrentMediaItemIndex(0)
                .setContentPositionMs(C.TIME_UNSET)
                .build()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            if (playWhenReady) this@PocketNarrationSession.play()
            else this@PocketNarrationSession.pause()
            return Futures.immediateFuture(null)
        }

        override fun handlePrepare(): ListenableFuture<*> = Futures.immediateFuture(null)

        override fun handleStop(): ListenableFuture<*> {
            this@PocketNarrationSession.pause()
            return Futures.immediateFuture(null)
        }

        override fun handleSeek(
            mediaItemIndex: Int,
            positionMs: Long,
            seekCommand: Int,
        ): ListenableFuture<*> = Futures.immediateFuture(null)

        override fun handleRelease(): ListenableFuture<*> {
            playerScope.cancel()
            return Futures.immediateFuture(null)
        }
    }

    companion object {
        /** Target amount of synthesized speech buffered ahead of playback. */
        private const val TARGET_BUFFER_FRAMES = 12L * PocketTts.SAMPLE_RATE

        /** Random silence inserted between sentences. */
        private const val BASE_PAUSE_MS = 400.0
        private const val PAUSE_JITTER_MS = 200.0

        private const val MAX_ALIGN = 40
        private const val MAX_SEGMENTS = 40
        private const val FLUSH_CHUNK_BYTES = 8192
        private val WHITESPACE_REGEX = Regex("\\s+")

        suspend fun create(
            context: Context,
            publication: Publication,
            title: String,
            author: String?,
            voice: String,
            rate: Float,
            pitch: Float,
            initialLocator: Locator?,
            mediaId: String,
        ): PocketNarrationSession {
            val session = PocketNarrationSession(
                appContext = context.applicationContext,
                publication = publication,
                title = title,
                author = author,
                voice = voice,
                rate = rate,
                pitch = pitch,
                mediaId = mediaId,
            )
            val first = session.iteratorMutex.withLock {
                session.iterator.seek(initialLocator)
                session.iterator.next()
            }
            if (first != null) {
                session.startAt(first)
            } else {
                session.markExhausted()
            }
            return session
        }
    }
}
