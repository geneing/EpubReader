package com.geneing.epubreader.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import org.readium.navigator.media.common.DefaultMediaMetadataProvider
import org.readium.navigator.media.tts.AndroidTtsNavigatorFactory
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.coverFitting
import org.readium.r2.shared.util.getOrElse
import java.util.concurrent.TimeUnit
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalReadiumApi::class)
class BookPlaybackService : MediaSessionService() {
    private val repository by lazy { LibraryRepository(applicationContext) }
    private val publicationLoader by lazy { ReadiumPublicationLoader(applicationContext) }
    private val pocketTtsModelManager by lazy { PocketTtsModelManager(applicationContext) }
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    private var placeholderPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var publication: Publication? = null
    private var ttsNavigator: NarrationController? = null
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
    private var hasPlaybackNotification = false

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
        val session = MediaSession.Builder(this, requireNotNull(placeholderPlayer))
            .setCallback(mediaSessionCallback)
            .setMediaButtonPreferences(mediaButtonPreferences())
            .build()
        mediaSession = session
        // This service owns a single session and may not receive an external controller
        // connection before playback starts. Register it so Media3 can create its internal
        // notification controller and publish the media-style notification.
        addSession(session)
        setForegroundServiceTimeoutMs(
            TimeUnit.MINUTES.toMillis(AppPreferences.playbackGraceMinutes(this).toLong()),
        )
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS)
        setMediaNotificationProvider(
            ProgressMediaNotificationProvider(this).apply { setSmallIcon(R.drawable.ic_stat_reader) },
        )
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
                val alignToSelection = intent.getBooleanExtra(
                    PlaybackServiceCommands.EXTRA_ALIGN_TO_SELECTION,
                    false,
                )
                if (!uri.isNullOrBlank()) {
                    if (ttsNavigator == null) showPreparingNotification()
                    startPlayback(uri, progress, locator, alignToSelection)
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

    private fun startPlayback(
        uriString: String,
        fallbackProgress: Double,
        initialLocator: Locator?,
        alignToSelection: Boolean,
    ) {
        val existingNavigator = ttsNavigator
        if (activeBookUri == uriString && existingNavigator != null) {
            lifecycleScope.launch {
                if (initialLocator != null) {
                    val before = existingNavigator.location.value
                    existingNavigator.go(initialLocator)
                    withTimeoutOrNull(LOCATION_CHANGE_TIMEOUT_MS) {
                        existingNavigator.location.first {
                            it.utterance != before.utterance || it.href != before.href
                        }
                    }
                    if (alignToSelection) alignToSelectedText(existingNavigator, initialLocator)
                }
                resumePlayback()
            }
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
                val navigator = createNarration(
                    publication = opened.publication,
                    title = actualTitle,
                    author = actualAuthor,
                    initialLocator = initialLocator ?: opened.initialLocator,
                    mediaId = uriString,
                )
                ttsNavigator = navigator
                mediaSession?.setSessionActivity(createReaderPendingIntent(uriString, book?.displayName ?: displayName, book?.mimeType))
                mediaSession?.setPlayer(ProgressPlayer(navigator.asMedia3Player()))
                observePlayback(navigator, uriString, actualTitle, actualAuthor, book?.coverPath)
                explicitUserPause = false
                pausedForHeadsetDisconnect = false
                // "Read from here" starts the navigator at the selected block; advance to
                // the exact selected sentence before playing.
                if (alignToSelection) alignToSelectedText(navigator, initialLocator)
                navigator.play()
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

    /**
     * The content iterator can only start at an HTML block (a `cssSelector`), so a
     * selected sentence in the middle of a paragraph would otherwise be read from
     * the paragraph's first sentence. Advance utterance by utterance until the
     * navigator's current text matches the text that was selected.
     */
    private suspend fun alignToSelectedText(
        navigator: NarrationController,
        locator: Locator?,
    ) {
        val target = locator?.text?.highlight
            ?.let(::normalizeUtterance)
            ?.takeIf { it.isNotBlank() }
            ?: return
        repeat(MAX_ALIGN_SKIPS) {
            val current = normalizeUtterance(navigator.location.value.utterance)
            if (current.isNotEmpty() && matchesUtterance(current, target)) return
            if (!navigator.hasNextUtterance()) return
            val before = navigator.location.value
            navigator.skipToNextUtterance()
            withTimeoutOrNull(LOCATION_CHANGE_TIMEOUT_MS) {
                navigator.location.first { it.utterance != before.utterance || it.range != before.range }
            }
        }
    }

    private fun matchesUtterance(current: String, target: String): Boolean {
        if (current == target || current.contains(target)) return true
        // Readium may split our sentence further; accept a long-enough leading fragment.
        return target.startsWith(current) && current.length >= 12
    }

    private fun normalizeUtterance(text: String): String =
        text.replace(WHITESPACE_REGEX, " ").trim()


    private suspend fun createNarration(
        publication: Publication,
        title: String,
        author: String?,
        initialLocator: Locator?,
        mediaId: String,
    ): NarrationController {
        val speechRate = AppPreferences.speechRate(applicationContext)

        return when (AppPreferences.speechEngine(applicationContext)) {
            com.geneing.epubreader.data.SpeechEngine.ANDROID_SYSTEM -> {
                val metadataProvider = DefaultMediaMetadataProvider(title = title, author = author)
                val listener = object : TtsNavigator.Listener {
                    override fun onStopRequested() {
                        stopPlayback()
                    }
                }
                val factory = AndroidTtsNavigatorFactory(
                    application,
                    publication,
                    metadataProvider = metadataProvider,
                ) ?: error("This EPUB does not provide readable text for narration.")
                val navigator = factory.createNavigator(
                    listener = listener,
                    initialLocator = initialLocator,
                    initialPreferences = AndroidTtsPreferences(speed = speechRate.toDouble()),
                ).getOrElse { error -> error(error.message) }
                ReadiumNarrationController(navigator)
            }
            com.geneing.epubreader.data.SpeechEngine.POCKET -> {
                val modelStatus = withContext(Dispatchers.IO) { pocketTtsModelManager.status() }
                check(modelStatus.installed) {
                    "Pocket TTS models are not installed (${modelStatus.missingFiles.size} files missing). Open Settings to install them."
                }
                PocketNarrationSession.create(
                    context = applicationContext,
                    publication = publication,
                    title = title,
                    author = author,
                    voice = AppPreferences.pocketTtsVoice(applicationContext),
                    rate = speechRate,
                    pitch = 1f,
                    initialLocator = initialLocator,
                    mediaId = mediaId,
                )
            }
        }
    }

    private fun observePlayback(
        navigator: NarrationController,
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
                val isPlaying = playback.playWhenReady && !playback.ended && !playback.failed
                val wasPlaying = lastPlayWhenReady
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
                    longInterruptionJob?.cancel()
                    explicitUserPause = false
                } else {
                    scheduleMiniPlayerExpiry()
                    if (playback.ended || playback.failed) {
                        longInterruptionJob?.cancel()
                    } else if (wasPlaying && !explicitUserPause && !pausedForHeadsetDisconnect) {
                        scheduleLongInterruptionResume(navigator)
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

    private fun scheduleLongInterruptionResume(navigator: NarrationController) {
        longInterruptionJob?.cancel()
        longInterruptionJob = lifecycleScope.launch {
            delay(LONG_INTERRUPTION_THRESHOLD_MS)
            if (PlaybackResumePolicy.shouldResumeAfterLongInterruption(
                    explicitlyPaused = explicitUserPause,
                    headsetDisconnected = pausedForHeadsetDisconnect,
                    resumeAfterLongInterruption = AppPreferences.resumeAfterLongInterruption(applicationContext),
                ) && navigator === ttsNavigator && !navigator.playback.value.playWhenReady
            ) {
                // Readium's Media3 player requests audio focus for the resume attempt.
                navigator.play()
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
        longInterruptionJob?.cancel()
        PlaybackStateStore.update(PlaybackStateStore.state.value.copy(errorMessage = null))
        ttsNavigator?.play()
    }

    private fun pauseByUser() {
        explicitUserPause = true
        pausedForHeadsetDisconnect = false
        longInterruptionJob?.cancel()
        ttsNavigator?.pause()
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
        longInterruptionJob?.cancel()
        navigator.pause()
    }

    private fun stopPlayback() {
        graceJob?.cancel()
        longInterruptionJob?.cancel()
        explicitUserPause = true
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

    private fun refreshMediaNotification() {
        mediaSession?.let { session ->
            session.setMediaButtonPreferences(mediaButtonPreferences())
            triggerNotificationUpdate()
        }
    }

    /**
     * Buttons shown in the notification and on the lock screen. The slots place the
     * sentence back/forward buttons around Media3's automatic play/pause button, so
     * the controls read "previous sentence · play/pause · next sentence", with Stop
     * tucked into the overflow menu.
     */
    private fun mediaButtonPreferences(): List<CommandButton> = listOf(
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
            .setDisplayName("Previous sentence")
            .setSessionCommand(skipBackCommand)
            .setSlots(CommandButton.SLOT_BACK)
            .build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
            .setDisplayName("Next sentence")
            .setSessionCommand(skipForwardCommand)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build(),
        CommandButton.Builder(CommandButton.ICON_STOP)
            .setDisplayName("Stop playback")
            .setSessionCommand(stopCommand)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
    )

    private fun createReaderPendingIntent(uri: String, name: String, mimeType: String?): PendingIntent {
        val intent = Intent(this, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_BOOK_URI, uri)
            .putExtra(ReaderActivity.EXTRA_BOOK_NAME, name)
            .putExtra(ReaderActivity.EXTRA_BOOK_MIME, mimeType)
            .putExtra(
                ReaderActivity.EXTRA_PROGRESS_PERCENT,
                (PlaybackStateStore.state.value.progress * 100.0).toDouble(),
            )
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
        longInterruptionJob?.cancel()
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
        private var artworkPath: String? = null
        private var artworkData: ByteArray? = null

        override fun getMediaMetadata(): MediaMetadata {
            val builder = super.getMediaMetadata()
                .buildUpon()
                .setSubtitle("${(PlaybackStateStore.state.value.progress * 100).toInt()}% read")
            artworkIfChanged()?.let { bytes ->
                builder.setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            }
            return builder.build()
        }

        /**
         * The derived cover is read once per book so the notification and lock
         * screen show the same artwork as the in-app mini-player.
         */
        private fun artworkIfChanged(): ByteArray? {
            val path = PlaybackStateStore.state.value.coverPath
            if (path == artworkPath) return artworkData
            artworkPath = path
            artworkData = path?.let { candidate ->
                runCatching { File(candidate).takeIf(File::isFile)?.readBytes() }.getOrNull()
            }
            return artworkData
        }
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
        private const val MAX_ALIGN_SKIPS = 25
        private const val LOCATION_CHANGE_TIMEOUT_MS = 4_000L
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
