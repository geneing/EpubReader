package com.geneing.epubreader.playback

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url

/** Narration playback state, independent of the engine driving it. */
internal data class NarrationPlayback(
    val playWhenReady: Boolean,
    val ended: Boolean = false,
    val failed: Boolean = false,
)

/** Current narration position, independent of the engine driving it. */
internal data class NarrationLocation(
    val href: Url,
    val utterance: String,
    val range: IntRange?,
    val utteranceLocator: Locator,
)

/**
 * App-owned narration interface. `PocketNarrationSession` implements it with
 * EpubReader's own sentence loop and audio cache; `ReadiumNarrationController`
 * adapts Readium's TTS navigator for the Android System TTS engine.
 */
internal interface NarrationController {
    val playback: StateFlow<NarrationPlayback>
    val location: StateFlow<NarrationLocation>

    fun play()
    fun pause()
    fun go(locator: Locator)
    fun skipToNextUtterance()
    fun skipToPreviousUtterance()
    fun hasNextUtterance(): Boolean
    fun asMedia3Player(): Player
    fun close()
}

/** Adapts Readium's TTS navigator (Android System TTS) to [NarrationController]. */
@OptIn(ExperimentalReadiumApi::class)
internal class ReadiumNarrationController(
    private val navigator: TtsNavigator<*, *, *, *>,
) : NarrationController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val playback: StateFlow<NarrationPlayback> =
        navigator.playback
            .map { toNarrationPlayback(it) }
            .stateIn(scope, SharingStarted.Eagerly, toNarrationPlayback(navigator.playback.value))

    override val location: StateFlow<NarrationLocation> =
        navigator.location
            .map { toNarrationLocation(it) }
            .stateIn(scope, SharingStarted.Eagerly, toNarrationLocation(navigator.location.value))

    private fun toNarrationPlayback(
        playback: org.readium.navigator.media.tts.TtsNavigator.Playback,
    ): NarrationPlayback = NarrationPlayback(
        playWhenReady = playback.playWhenReady,
        ended = playback.state is org.readium.navigator.media.common.MediaNavigator.State.Ended,
        failed = playback.state is org.readium.navigator.media.common.MediaNavigator.State.Failure,
    )

    private fun toNarrationLocation(
        location: org.readium.navigator.media.tts.TtsNavigator.Location,
    ): NarrationLocation = NarrationLocation(
        href = location.href,
        utterance = location.utterance,
        range = location.range,
        utteranceLocator = location.utteranceLocator,
    )

    override fun play() = navigator.play()

    override fun pause() = navigator.pause()

    override fun go(locator: Locator) = navigator.go(locator)

    override fun skipToNextUtterance() = navigator.skipToNextUtterance()

    override fun skipToPreviousUtterance() = navigator.skipToPreviousUtterance()

    override fun hasNextUtterance(): Boolean = navigator.hasNextUtterance()

    override fun asMedia3Player(): Player = navigator.asMedia3Player()

    override fun close() {
        scope.cancel()
        navigator.close()
    }
}
