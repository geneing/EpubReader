package com.geneing.epubreader

import android.app.Application
import android.util.Size
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geneing.epubreader.data.BookEntity
import com.geneing.epubreader.data.BookFolderEntity
import com.geneing.epubreader.data.AppPreferences
import com.geneing.epubreader.data.LibraryRepository
import com.geneing.epubreader.data.ReaderFontFamily
import com.geneing.epubreader.data.SpeechEngine
import com.geneing.epubreader.data.ThemeMode
import com.geneing.epubreader.reader.ReadiumPublicationLoader
import com.geneing.epubreader.playback.PlaybackStateStore
import com.geneing.epubreader.playback.PlaybackUiState
import com.geneing.epubreader.playback.PlaybackServiceCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.services.coverFitting

data class LibraryUiState(
    val folders: List<BookFolderEntity> = emptyList(),
    val books: List<BookEntity> = emptyList(),
    val busyKeys: Set<String> = emptySet(),
    val message: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val readerFontFamily: ReaderFontFamily = ReaderFontFamily.BOOK_DEFAULT,
    val readerFontScale: Float = AppPreferences.DEFAULT_FONT_SCALE,
    val speechEngine: SpeechEngine = SpeechEngine.ANDROID_SYSTEM,
    val speechRate: Float = AppPreferences.DEFAULT_SPEECH_RATE,
    val playbackGraceMinutes: Int = AppPreferences.DEFAULT_PLAYBACK_GRACE_MINUTES,
    val resumeOnBluetoothReconnect: Boolean = false,
    val resumeAfterLongInterruption: Boolean = false,
    val playback: PlaybackUiState = PlaybackUiState(),
)

private data class ReaderSettingsState(
    val themeMode: ThemeMode,
    val fontFamily: ReaderFontFamily,
    val fontScale: Float,
    val speechEngine: SpeechEngine,
    val speechRate: Float,
    val playbackGraceMinutes: Int,
    val resumeOnBluetoothReconnect: Boolean,
    val resumeAfterLongInterruption: Boolean,
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LibraryRepository(application)
    private val publicationLoader = ReadiumPublicationLoader(application)
    private val metadataJobs = mutableSetOf<String>()
    private val mutableSettings = MutableStateFlow(
        ReaderSettingsState(
            themeMode = AppPreferences.themeMode(application),
            fontFamily = AppPreferences.readerFontFamily(application),
            fontScale = AppPreferences.readerFontScale(application),
            speechEngine = AppPreferences.speechEngine(application),
            speechRate = AppPreferences.speechRate(application),
            playbackGraceMinutes = AppPreferences.playbackGraceMinutes(application),
            resumeOnBluetoothReconnect = AppPreferences.resumeOnBluetoothReconnect(application),
            resumeAfterLongInterruption = AppPreferences.resumeAfterLongInterruption(application),
        ),
    )
    private val mutableBusyKeys = MutableStateFlow<Set<String>>(emptySet())
    private val mutableMessage = MutableStateFlow<String?>(null)

    private val libraryState: StateFlow<LibraryUiState> = combine(
        repository.observeFolders(),
        repository.observeBooks(),
        mutableBusyKeys,
        mutableMessage,
        mutableSettings,
    ) { folders, books, busyKeys, message, settings ->
        LibraryUiState(
            folders = folders,
            books = books,
            busyKeys = busyKeys,
            message = message,
            themeMode = settings.themeMode,
            readerFontFamily = settings.fontFamily,
            readerFontScale = settings.fontScale,
            speechEngine = settings.speechEngine,
            speechRate = settings.speechRate,
            playbackGraceMinutes = settings.playbackGraceMinutes,
            resumeOnBluetoothReconnect = settings.resumeOnBluetoothReconnect,
            resumeAfterLongInterruption = settings.resumeAfterLongInterruption,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())
    val state: StateFlow<LibraryUiState> = combine(libraryState, PlaybackStateStore.state) { library, playback ->
        library.copy(playback = playback)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun addFolder(uri: Uri) = launchOperation("add-folder") {
        repository.addFolder(uri)
        repository.scanFolder(uri.toString())
        "Folder added. Books are read directly from that location."
    }

    fun addDocument(uri: Uri) = launchOperation("add-document") {
        repository.addDocument(uri)
        "Book added to your library. The original file stays in place."
    }

    fun scanFolder(folder: BookFolderEntity) = launchOperation("scan:${folder.treeUri}") {
        repository.scanFolder(folder.treeUri)
        "${folder.displayName} scanned."
    }

    fun removeFolder(folder: BookFolderEntity) = launchOperation("remove:${folder.treeUri}") {
        repository.removeFolder(folder.treeUri)
        "Folder removed from EpubReader. Its files were left untouched."
    }

    fun removeBook(book: BookEntity) = launchOperation("remove:${book.uri}") {
        repository.removeBook(book.uri)
        "Book removed from EpubReader. Its file was left untouched."
    }

    fun markBookOpened(book: BookEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.markBookOpened(book.uri)
        }
    }

    fun loadBookMetadata(book: BookEntity) {
        if (!book.isAvailable || book.metadataLoaded || !metadataJobs.add(book.uri)) return
        viewModelScope.launch {
            try {
                val opened = withContext(Dispatchers.IO) {
                    publicationLoader.open(Uri.parse(book.uri), book.progressPercent)
                }
                try {
                    val publication = opened.publication
                    val cover = withContext(Dispatchers.IO) {
                        runCatching { publication.coverFitting(Size(240, 360)) }.getOrNull()
                    }
                    withContext(Dispatchers.IO) {
                        repository.updatePublicationInfo(
                            uriString = book.uri,
                            title = publication.metadata.title,
                            author = publication.metadata.authors.joinToString { it.name },
                            coverBitmap = cover,
                        )
                    }
                } finally {
                    opened.publication.close()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Keep library browsing available when a file is malformed or its provider is offline.
            } finally {
                metadataJobs.remove(book.uri)
            }
        }
    }

    fun setThemeMode(themeMode: ThemeMode) {
        if (mutableSettings.value.themeMode == themeMode) return
        AppPreferences.setThemeMode(getApplication(), themeMode)
        mutableSettings.value = mutableSettings.value.copy(themeMode = themeMode)
    }

    fun setReaderFontFamily(family: ReaderFontFamily) {
        if (mutableSettings.value.fontFamily == family) return
        AppPreferences.setReaderFontFamily(getApplication(), family)
        mutableSettings.value = mutableSettings.value.copy(fontFamily = family)
    }

    fun setReaderFontScale(scale: Float) {
        val boundedScale = scale.coerceIn(AppPreferences.MIN_FONT_SCALE, AppPreferences.MAX_FONT_SCALE)
        if (mutableSettings.value.fontScale == boundedScale) return
        AppPreferences.setReaderFontScale(getApplication(), boundedScale)
        mutableSettings.value = mutableSettings.value.copy(fontScale = boundedScale)
    }

    fun setSpeechEngine(engine: SpeechEngine) {
        if (engine == SpeechEngine.POCKET || mutableSettings.value.speechEngine == engine) return
        AppPreferences.setSpeechEngine(getApplication(), engine)
        mutableSettings.value = mutableSettings.value.copy(speechEngine = engine)
    }

    fun setSpeechRate(rate: Float) {
        val boundedRate = rate.coerceIn(AppPreferences.MIN_SPEECH_RATE, AppPreferences.MAX_SPEECH_RATE)
        if (mutableSettings.value.speechRate == boundedRate) return
        AppPreferences.setSpeechRate(getApplication(), boundedRate)
        mutableSettings.value = mutableSettings.value.copy(speechRate = boundedRate)
    }

    fun setPlaybackGraceMinutes(minutes: Int) {
        val bounded = minutes.coerceIn(AppPreferences.MIN_PLAYBACK_GRACE_MINUTES, AppPreferences.MAX_PLAYBACK_GRACE_MINUTES)
        if (mutableSettings.value.playbackGraceMinutes == bounded) return
        AppPreferences.setPlaybackGraceMinutes(getApplication(), bounded)
        mutableSettings.value = mutableSettings.value.copy(playbackGraceMinutes = bounded)
        PlaybackServiceCommands.refreshSettings(getApplication())
    }

    fun setResumeOnBluetoothReconnect(enabled: Boolean) {
        if (mutableSettings.value.resumeOnBluetoothReconnect == enabled) return
        AppPreferences.setResumeOnBluetoothReconnect(getApplication(), enabled)
        mutableSettings.value = mutableSettings.value.copy(resumeOnBluetoothReconnect = enabled)
    }

    fun setResumeAfterLongInterruption(enabled: Boolean) {
        if (mutableSettings.value.resumeAfterLongInterruption == enabled) return
        AppPreferences.setResumeAfterLongInterruption(getApplication(), enabled)
        mutableSettings.value = mutableSettings.value.copy(resumeAfterLongInterruption = enabled)
    }

    fun dismissMessage() {
        mutableMessage.value = null
    }

    private fun launchOperation(key: String, operation: suspend () -> String) {
        if (key in mutableBusyKeys.value) return
        viewModelScope.launch {
            mutableBusyKeys.value = mutableBusyKeys.value + key
            try {
                mutableMessage.value = withContext(Dispatchers.IO) { operation() }
            } catch (error: Exception) {
                mutableMessage.value = error.message ?: "Something went wrong. Please try again."
            } finally {
                mutableBusyKeys.value = mutableBusyKeys.value - key
            }
        }
    }
}
