package com.geneing.epubreader.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionError
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.lifecycle.lifecycleScope
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.geneing.epubreader.MainActivity
import com.geneing.epubreader.R
import com.geneing.epubreader.data.AppPreferences
import com.geneing.epubreader.data.BookFormat
import com.geneing.epubreader.data.LibraryRepository
import com.geneing.epubreader.reader.ReadiumPublicationLoader
import com.geneing.epubreader.reader.ReaderActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.readium.navigator.media.common.DefaultMediaMetadataProvider
import org.readium.navigator.media.tts.AndroidTtsNavigator
import org.readium.navigator.media.tts.AndroidTtsNavigatorFactory
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.coverFitting
import org.readium.r2.shared.util.getOrElse
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalReadiumApi::class)
class BookPlaybackService : MediaSessionService() {
    private val repository by lazy { LibraryRepository(applicationContext) }
    private val publicationLoader by lazy { ReadiumPublicationLoader(applicationContext) }
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    private var placeholderPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var publication: Publication? = null
    private var ttsNavigator: AndroidTtsNavigator? = null
    private var activeBookUri: String? = null
    private var foregroundBookTitle = ""
    private var graceJob: Job? = null
    private var playbackObserver: Job? = null
    private var locationObserver: Job? = null
    private var longInterruptionJob: Job? = null
    private var lastPlayWhenReady = false
    private var explicitUserPause = false
    private var pausedForHeadsetDisconnect = false
    private var wasPlayingBeforeHeadsetDisconnect = false
    private var disconnectedOutputWasBluetooth = false
    private var lastKnownBluetoothOutputConnected = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusGranted = false
    private var resumeAfterFocusGain = false
    private var interruptionStartedAtMs: Long? = null
    private var hasPlaybackNotification = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                audioFocusGranted = true
                val interruptionDuration = interruptionStartedAtMs?.let {
                    SystemClock.elapsedRealtime() - it
                } ?: 0L
                val wasInterrupted = resumeAfterFocusGain
                val shouldResume = PlaybackResumePolicy.afterAudioFocusGain(
                    playbackWasInterrupted = resumeAfterFocusGain,
                    explicitlyPaused = explicitUserPause,
                    headsetDisconnected = pausedForHeadsetDisconnect,
                    interruptionDurationMs = interruptionDuration,
                    resumeAfterLongInterruption = AppPreferences.resumeAfterLongInterruption(applicationContext),
                    longInterruptionThresholdMs = LONG_INTERRUPTION_THRESHOLD_MS,
                )
                resumeAfterFocusGain = false
                interruptionStartedAtMs = null
                if (shouldResume) {
                    PlaybackStateStore.update(PlaybackStateStore.state.value.copy(errorMessage = null))
                    ttsNavigator?.play()
                } else if (wasInterrupted) {
                    abandonAudioFocus()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                audioFocusGranted = false
                val navigator = ttsNavigator
                if (navigator?.playback?.value?.playWhenReady == true && !explicitUserPause) {
                    resumeAfterFocusGain = true
                    interruptionStartedAtMs = SystemClock.elapsedRealtime()
                    navigator.pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                audioFocusGranted = false
                resumeAfterFocusGain = false
                interruptionStartedAtMs = null
                if (ttsNavigator?.playback?.value?.playWhenReady == true) {
                    explicitUserPause = false
                    ttsNavigator?.pause()
                }
                abandonAudioFocus()
            }
        }
    }

    private val skipBackCommand = SessionCommand(PlaybackServiceCommands.ACTION_SKIP_BACK, Bundle.EMPTY)
    private val skipForwardCommand = SessionCommand(PlaybackServiceCommands.ACTION_SKIP_FORWARD, Bundle.EMPTY)
    private val stopCommand = SessionCommand(PlaybackServiceCommands.ACTION_STOP, Bundle.EMPTY)

    private val mediaSessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon()
                .add(skipBackCommand)
                .add(skipForwardCommand)
                .add(stopCommand)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                PlaybackServiceCommands.ACTION_SKIP_BACK -> skipBackward()
                PlaybackServiceCommands.ACTION_SKIP_FORWARD -> skipForward()
                PlaybackServiceCommands.ACTION_STOP -> stopPlayback()
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int,
        ): Int {
            if (playerCommand == Player.COMMAND_PLAY_PAUSE) {
                if (session.player.playWhenReady) {
                    explicitUserPause = true
                    pausedForHeadsetDisconnect = false
                } else {
                    explicitUserPause = false
                    pausedForHeadsetDisconnect = false
                    longInterruptionJob?.cancel()
                }
            }
            return SessionResult.RESULT_SUCCESS
        }

        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            val keyEvent = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                ?: return super.onMediaButtonEvent(session, controllerInfo, intent)
            if (keyEvent.action != KeyEvent.ACTION_DOWN) return true
            return when (keyEvent.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    skipForward()
                    true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    skipBackward()
                    true
                }
                else -> super.onMediaButtonEvent(session, controllerInfo, intent)
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pauseForHeadsetDisconnect(isBluetooth = lastKnownBluetoothOutputConnected)
                lastKnownBluetoothOutputConnected = false
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            val removedBluetooth = removedDevices.any { it.isBluetoothOutput() }
            val removedHeadset = removedBluetooth || removedDevices.any { it.isHeadsetOutput() }
            if (removedHeadset) {
                pauseForHeadsetDisconnect(isBluetooth = removedBluetooth || lastKnownBluetoothOutputConnected)
                if (removedBluetooth) lastKnownBluetoothOutputConnected = false
            }
        }

        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            if (addedDevices.none { it.isBluetoothOutput() }) return
            lastKnownBluetoothOutputConnected = true
            if (!pausedForHeadsetDisconnect) return
            val shouldResume = PlaybackResumePolicy.afterBluetoothReconnect(
                disconnectedOutputWasBluetooth = disconnectedOutputWasBluetooth,
                wasPlayingBeforeDisconnect = wasPlayingBeforeHeadsetDisconnect,
                resumeOnBluetoothReconnect = AppPreferences.resumeOnBluetoothReconnect(applicationContext),
            )
            pausedForHeadsetDisconnect = false
            wasPlayingBeforeHeadsetDisconnect = false
            disconnectedOutputWasBluetooth = false
            if (shouldResume) resumePlayback()
        }
    }

    override fun onCreate() {
        super.onCreate()
        placeholderPlayer = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, requireNotNull(placeholderPlayer))
            .setCallback(mediaSessionCallback)
            .setMediaButtonPreferences(mediaButtonPreferences())
            .build()
        setForegroundServiceTimeoutMs(
            TimeUnit.MINUTES.toMillis(AppPreferences.playbackGraceMinutes(this).toLong()),
        )
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS)
        setMediaNotificationProvider(ProgressMediaNotificationProvider(this))
        lastKnownBluetoothOutputConnected = bluetoothOutputConnected()
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), RECEIVER_NOT_EXPORTED)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serviceResult = super.onStartCommand(intent, flags, startId)
        if (!PlaybackServiceCommands.isAuthorized(intent)) return serviceResult
        when (intent?.action) {
            PlaybackServiceCommands.ACTION_START -> {
                val uri = intent.getStringExtra(PlaybackServiceCommands.EXTRA_BOOK_URI)
                val locator = intent.getParcelableExtra(
                    PlaybackServiceCommands.EXTRA_INITIAL_LOCATOR,
                    Locator::class.java,
                )
                val progress = intent.getDoubleExtra(PlaybackServiceCommands.EXTRA_PROGRESS_PERCENT, 0.0)
                if (!uri.isNullOrBlank()) {
                    if (ttsNavigator == null) showPreparingNotification()
                    startPlayback(uri, progress, locator)
                }
            }
            PlaybackServiceCommands.ACTION_PLAY -> resumePlayback()
            PlaybackServiceCommands.ACTION_PAUSE -> pauseByUser()
            PlaybackServiceCommands.ACTION_TOGGLE -> togglePlayback()
            PlaybackServiceCommands.ACTION_SKIP_BACK -> skipBackward()
            PlaybackServiceCommands.ACTION_SKIP_FORWARD -> skipForward()
            PlaybackServiceCommands.ACTION_STOP -> stopPlayback()
            PlaybackServiceCommands.ACTION_REFRESH_SETTINGS -> {
                applyGracePeriodPreference()
            }
            PlaybackServiceCommands.ACTION_SEEK_TO_LOCATOR -> {
                intent.getParcelableExtra(
                    PlaybackServiceCommands.EXTRA_INITIAL_LOCATOR,
                    Locator::class.java,
                )?.let { ttsNavigator?.go(it) }
            }
        }
        return START_NOT_STICKY
    }

    private fun startPlayback(uriString: String, fallbackProgress: Double, initialLocator: Locator?) {
        if (activeBookUri == uriString && ttsNavigator != null) {
            initialLocator?.let(ttsNavigator!!::go)
            resumePlayback()
            return
        }
        lifecycleScope.launch {
            try {
                closeActivePublication()
                activeBookUri = uriString
                val book = withContext(Dispatchers.IO) { repository.findBook(uriString) }
                val displayName = book?.publicationTitle?.takeIf(String::isNotBlank)
                    ?: book?.displayName
                    ?: "Book"
                foregroundBookTitle = displayName
                PlaybackStateStore.update(
                    PlaybackUiState(
                        bookUri = uriString,
                        title = displayName,
                        author = book?.author,
                        coverPath = book?.coverPath,
                        progress = (book?.progressPercent?.div(100.0) ?: fallbackProgress / 100.0)
                            .toFloat().coerceIn(0f, 1f),
                        showMiniPlayer = true,
                    ),
                )

                val opened = withContext(Dispatchers.IO) {
                    publicationLoader.open(
                        Uri.parse(uriString),
                        book?.progressPercent ?: fallbackProgress,
                    )
                }
                publication = opened.publication
                val actualTitle = opened.publication.metadata.title?.takeIf(String::isNotBlank) ?: displayName
                val actualAuthor = book?.author?.takeIf(String::isNotBlank)
                    ?: opened.publication.metadata.authors.firstOrNull { it.name.isNotBlank() }?.name
                foregroundBookTitle = actualTitle
                val factory = AndroidTtsNavigatorFactory(
                    application,
                    opened.publication,
                    metadataProvider = DefaultMediaMetadataProvider(
                        title = actualTitle,
                        author = actualAuthor,
                    ),
                ) ?: error("This EPUB does not provide readable text for narration.")
                val navigator = factory.createNavigator(
                    listener = object : TtsNavigator.Listener {
                        override fun onStopRequested() {
                            stopPlayback()
                        }
                    },
                    initialLocator = initialLocator ?: opened.initialLocator,
                    initialPreferences = AndroidTtsPreferences(
                        speed = AppPreferences.speechRate(applicationContext).toDouble(),
                    ),
                ).getOrElse { error -> error(error.message) }
                ttsNavigator = navigator
                mediaSession?.setSessionActivity(createReaderPendingIntent(uriString, book?.displayName ?: displayName, book?.mimeType))
                mediaSession?.setPlayer(ProgressPlayer(navigator.asMedia3Player()))
                observePlayback(navigator, uriString, actualTitle, actualAuthor, book?.coverPath)
                explicitUserPause = false
                pausedForHeadsetDisconnect = false
                if (requestAudioFocus()) {
                    PlaybackStateStore.update(PlaybackStateStore.state.value.copy(errorMessage = null))
                    navigator.play()
                } else {
                    PlaybackStateStore.update(
                        PlaybackStateStore.state.value.copy(
                            errorMessage = "Audio is in use by another app. Resume when it is available.",
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                PlaybackStateStore.update(
                    PlaybackUiState(
                        bookUri = uriString,
                        title = foregroundBookTitle,
                        showMiniPlayer = true,
                    ),
                )
                stopPlayback()
            }
        }
    }

    private fun observePlayback(
        navigator: AndroidTtsNavigator,
        uriString: String,
        title: String,
        author: String?,
        coverPath: String?,
    ) {
        playbackObserver?.cancel()
        locationObserver?.cancel()
        lastPlayWhenReady = false
        playbackObserver = lifecycleScope.launch {
            navigator.playback.collect { playback ->
                val isPlaying = playback.playWhenReady && playback.state !is org.readium.navigator.media.common.MediaNavigator.State.Ended
                lastPlayWhenReady = playback.playWhenReady
                val previous = PlaybackStateStore.state.value
                PlaybackStateStore.update(
                    previous.copy(
                        bookUri = uriString,
                        title = title,
                        author = author,
                        coverPath = coverPath,
                        isPlaying = isPlaying,
                        showMiniPlayer = true,
                    ),
                )
                if (isPlaying) {
                    graceJob?.cancel()
                    explicitUserPause = false
                } else {
                    scheduleMiniPlayerExpiry()
                    if (playback.state is org.readium.navigator.media.common.MediaNavigator.State.Ended ||
                        playback.state is org.readium.navigator.media.common.MediaNavigator.State.Failure
                    ) {
                        resumeAfterFocusGain = false
                        abandonAudioFocus()
                    }
                }
                refreshMediaNotification()
            }
        }
        locationObserver = lifecycleScope.launch {
            navigator.location.collect { location ->
                val locator = location.utteranceLocator
                val progress = locator.locations.totalProgression
                    ?: PlaybackStateStore.state.value.progress.toDouble()
                repository.updateReadingProgress(uriString, progress)
                PlaybackStateStore.update(
                    PlaybackStateStore.state.value.copy(
                        currentLocator = locator,
                        progress = progress.toFloat().coerceIn(0f, 1f),
                        showMiniPlayer = true,
                    ),
                )
                refreshMediaNotification()
            }
        }
    }

    private fun togglePlayback() {
        val navigator = ttsNavigator ?: return
        if (navigator.playback.value.playWhenReady) pauseByUser() else resumePlayback()
    }

    private fun resumePlayback() {
        explicitUserPause = false
        pausedForHeadsetDisconnect = false
        resumeAfterFocusGain = false
        interruptionStartedAtMs = null
        if (requestAudioFocus()) {
            PlaybackStateStore.update(PlaybackStateStore.state.value.copy(errorMessage = null))
            ttsNavigator?.play()
        } else {
            PlaybackStateStore.update(
                PlaybackStateStore.state.value.copy(
                    errorMessage = "Audio is in use by another app. Resume when it is available.",
                ),
            )
        }
    }

    private fun pauseByUser() {
        explicitUserPause = true
        pausedForHeadsetDisconnect = false
        resumeAfterFocusGain = false
        interruptionStartedAtMs = null
        ttsNavigator?.pause()
        abandonAudioFocus()
    }

    private fun skipBackward() {
        ttsNavigator?.skipToPreviousUtterance()
    }

    private fun skipForward() {
        ttsNavigator?.skipToNextUtterance()
    }

    private fun pauseForHeadsetDisconnect(isBluetooth: Boolean) {
        val navigator = ttsNavigator ?: return
        if (!navigator.playback.value.playWhenReady) return
        wasPlayingBeforeHeadsetDisconnect = true
        disconnectedOutputWasBluetooth = isBluetooth
        pausedForHeadsetDisconnect = true
        explicitUserPause = false
        resumeAfterFocusGain = false
        interruptionStartedAtMs = null
        navigator.pause()
        abandonAudioFocus()
    }

    private fun stopPlayback() {
        graceJob?.cancel()
        explicitUserPause = true
        resumeAfterFocusGain = false
        interruptionStartedAtMs = null
        abandonAudioFocus()
        PlaybackStateStore.clear()
        closeActivePublication()
        mediaSession?.release()
        mediaSession = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        hasPlaybackNotification = false
        stopSelf()
    }

    private fun closeActivePublication() {
        playbackObserver?.cancel()
        locationObserver?.cancel()
        playbackObserver = null
        locationObserver = null
        placeholderPlayer?.let { player -> runCatching { mediaSession?.setPlayer(player) } }
        ttsNavigator?.close()
        ttsNavigator = null
        publication?.close()
        publication = null
        activeBookUri = null
        lastPlayWhenReady = false
    }

    private fun scheduleMiniPlayerExpiry() {
        if (!PlaybackStateStore.state.value.showMiniPlayer) return
        graceJob?.cancel()
        val graceMinutes = AppPreferences.playbackGraceMinutes(applicationContext)
        setForegroundServiceTimeoutMs(TimeUnit.MINUTES.toMillis(graceMinutes.toLong()))
        if (graceMinutes == 0) {
            stopPlayback()
            return
        }
        graceJob = lifecycleScope.launch {
            delay(TimeUnit.MINUTES.toMillis(graceMinutes.toLong()))
            if (ttsNavigator?.playback?.value?.playWhenReady != true) stopPlayback()
        }
    }

    private fun applyGracePeriodPreference() {
        setForegroundServiceTimeoutMs(
            TimeUnit.MINUTES.toMillis(AppPreferences.playbackGraceMinutes(applicationContext).toLong()),
        )
        if (ttsNavigator?.playback?.value?.playWhenReady != true) {
            scheduleMiniPlayerExpiry()
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (audioFocusGranted) return true
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(audioFocusChangeListener, Handler(Looper.getMainLooper()))
            .build()
        audioFocusRequest = request
        audioFocusGranted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return audioFocusGranted
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        audioFocusRequest = null
        audioFocusGranted = false
    }

    private fun refreshMediaNotification() {
        mediaSession?.let { session ->
            session.setMediaButtonPreferences(mediaButtonPreferences())
            triggerNotificationUpdate()
        }
    }

    private fun mediaButtonPreferences(): List<CommandButton> = listOf(
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
            .setDisplayName("Previous sentence")
            .setSessionCommand(skipBackCommand)
            .build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
            .setDisplayName("Next sentence")
            .setSessionCommand(skipForwardCommand)
            .build(),
        CommandButton.Builder(CommandButton.ICON_STOP)
            .setDisplayName("Stop playback")
            .setSessionCommand(stopCommand)
            .build(),
    )

    private fun createReaderPendingIntent(uri: String, name: String, mimeType: String?): PendingIntent {
        val intent = Intent(this, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_BOOK_URI, uri)
            .putExtra(ReaderActivity.EXTRA_BOOK_NAME, name)
            .putExtra(ReaderActivity.EXTRA_BOOK_MIME, mimeType)
        return PendingIntent.getActivity(
            this,
            uri.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun showPreparingNotification() {
        if (hasPlaybackNotification) return
        ensureNotificationChannel()
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Preparing book playback")
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        hasPlaybackNotification = true
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                android.app.NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.playback_notification_channel),
                    android.app.NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun bluetoothOutputConnected(): Boolean = audioManager
        .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .any { it.isBluetoothOutput() }

    override fun onDestroy() {
        graceJob?.cancel()
        abandonAudioFocus()
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        runCatching { unregisterReceiver(noisyReceiver) }
        closeActivePublication()
        val session = mediaSession
        mediaSession = null
        session?.release()
        placeholderPlayer?.release()
        placeholderPlayer = null
        PlaybackStateStore.clear()
        super.onDestroy()
    }

    private inner class ProgressPlayer(player: Player) : ForwardingPlayer(player) {
        override fun getMediaMetadata(): MediaMetadata = super.getMediaMetadata()
            .buildUpon()
            .setSubtitle("${(PlaybackStateStore.state.value.progress * 100).toInt()}% read")
            .build()
    }

    private inner class ProgressMediaNotificationProvider(context: Context) :
        DefaultMediaNotificationProvider(
            context,
            { NOTIFICATION_ID },
            NOTIFICATION_CHANNEL_ID,
            R.string.playback_notification_channel,
        ) {
        override fun getNotificationContentText(metadata: MediaMetadata): CharSequence {
            val state = PlaybackStateStore.state.value
            val author = state.author?.takeIf(String::isNotBlank)
            val reading = "${(state.progress * 100).toInt()}% read"
            return listOfNotNull(author, reading).joinToString(" · ")
        }
    }

    private fun AudioDeviceInfo.isBluetoothOutput(): Boolean = type in setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
    )

    private fun AudioDeviceInfo.isHeadsetOutput(): Boolean = isBluetoothOutput() || type in setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
    )

    companion object {
        private const val NOTIFICATION_ID = 4102
        private const val NOTIFICATION_CHANNEL_ID = "book_playback"
        private const val LONG_INTERRUPTION_THRESHOLD_MS = 30_000L
    }
}
