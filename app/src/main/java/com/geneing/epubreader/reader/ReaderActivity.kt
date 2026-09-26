package com.geneing.epubreader.reader

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.content.res.Configuration
import android.graphics.PointF
import android.util.Size
import android.view.View
import android.view.ViewConfiguration
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VerticalAlignCenter
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.geneing.epubreader.data.AppPreferences
import com.geneing.epubreader.data.BookFormat
import com.geneing.epubreader.data.LibraryRepository
import com.geneing.epubreader.data.ReaderFontFamily
import com.geneing.epubreader.playback.PlaybackServiceCommands
import com.geneing.epubreader.playback.PlaybackStateStore
import com.geneing.epubreader.playback.PlaybackUiState
import com.geneing.epubreader.ui.EpubReaderTheme
import com.geneing.epubreader.ui.PlaybackMiniPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.VisualNavigator
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.html.cssSelector
import org.readium.r2.shared.publication.services.coverFitting
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.publication.services.search.SearchIterator
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.publication.Link
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.adapter.pdfium.navigator.PdfiumDefaults
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.navigator.preferences.Theme as ReadiumTheme
import org.json.JSONObject
import kotlinx.coroutines.delay

@OptIn(ExperimentalReadiumApi::class)
class ReaderActivity : FragmentActivity() {
    private val loader by lazy { ReadiumPublicationLoader(applicationContext) }
    private val libraryRepository by lazy { LibraryRepository(applicationContext) }
    private val navigatorContainerId = View.generateViewId()
    private val pdfiumEngineProvider by lazy {
        PdfiumEngineProvider(defaults = PdfiumDefaults(scroll = true))
    }

    private var bookTitle by mutableStateOf("Reader")
    private var isLoading by mutableStateOf(true)
    private var errorMessage by mutableStateOf<String?>(null)
    private var playbackError by mutableStateOf<String?>(null)
    private var isNavigatorReady by mutableStateOf(false)
    private var isTtsPlaying by mutableStateOf(false)
    private var playbackState by mutableStateOf(PlaybackUiState())
    private var bookAuthor by mutableStateOf<String?>(null)
    private var coverPath by mutableStateOf<String?>(null)
    private var currentProgress by mutableStateOf(0f)
    private var currentPage by mutableStateOf(1)
    private var pageCount by mutableStateOf(0)
    private var pageLabel by mutableStateOf("Preparing location…")
    private var searchDialogOpen by mutableStateOf(false)
    private var tocDialogOpen by mutableStateOf(false)
    private var searchResults by mutableStateOf<List<Locator>>(emptyList())
    private var searchError by mutableStateOf<String?>(null)
    private var tableOfContents by mutableStateOf<List<Link>>(emptyList())
    private var positionLocators: List<Locator> = emptyList()
    private var publication: Publication? = null
    private var currentFormat: BookFormat? = null
    private var initialLocator: Locator? = null
    private var containerAvailable = false
    private var currentSearch: SearchIterator? = null
    private var observedPlaybackLocator: Locator? = null
    private var autoFollowReadingPosition by mutableStateOf(true)
    private var recenterButtonVisible by mutableStateOf(false)
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        val format = BookFormat.from(
            name = intent.getStringExtra(EXTRA_BOOK_NAME),
            mimeType = intent.getStringExtra(EXTRA_BOOK_MIME),
        )
        currentFormat = format
        // Readium navigator fragments need their factory before FragmentActivity restores them.
        // The live Publication is process-scoped, so return to the library after process recreation.
        if (savedInstanceState != null) {
            supportFragmentManager.fragmentFactory = when (format) {
                BookFormat.PDF -> PdfNavigatorFragment.createDummyFactory(pdfiumEngineProvider)
                else -> EpubNavigatorFragment.createDummyFactory()
            }
            super.onCreate(savedInstanceState)
            finish()
            return
        }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val uri = intent.getStringExtra(EXTRA_BOOK_URI)?.let(Uri::parse)
        bookTitle = intent.getStringExtra(EXTRA_BOOK_NAME) ?: "Reader"

        setContent {
            EpubReaderTheme(AppPreferences.themeMode(this)) {
                val activePlayback = playbackState
                    .takeIf { it.bookUri == uri?.toString() && it.showMiniPlayer }
                val displayedPlayback = activePlayback ?: PlaybackUiState(
                    bookUri = uri?.toString(),
                    title = bookTitle,
                    author = bookAuthor,
                    coverPath = coverPath,
                    progress = currentProgress,
                )
                ReaderScreen(
                    title = bookTitle,
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    playbackError = playbackError,
                    navigatorReady = isNavigatorReady,
                    navigatorContainerId = navigatorContainerId,
                    format = format,
                    playback = displayedPlayback,
                    showRecenterButton = recenterButtonVisible,
                    currentProgress = currentProgress,
                    pageLabel = pageLabel,
                    pageCount = pageCount,
                    searchResults = searchResults,
                    searchError = searchError,
                    searchDialogOpen = searchDialogOpen,
                    tableOfContents = flattenTableOfContents(tableOfContents),
                    onTogglePlayback = ::togglePlayback,
                    onSkipBack = { skipPlayback(forward = false) },
                    onSkipForward = { skipPlayback(forward = true) },
                    onStopPlayback = ::stopPlaybackFromReader,
                    onRecenter = ::recenterOnReadingPosition,
                    onPreviousPage = { navigateReader(forward = false) },
                    onNextPage = { navigateReader(forward = true) },
                    onSeekProgress = ::seekToProgress,
                    onJumpToPage = ::jumpToPage,
                    onSearchRequested = { searchDialogOpen = true },
                    onSearch = ::searchPublication,
                    onSearchResultSelected = ::goToLocator,
                    onTocRequested = { tocDialogOpen = true },
                    onTocItemSelected = ::goToLink,
                    tocDialogOpen = tocDialogOpen,
                    onDismissSearch = {
                        searchDialogOpen = false
                        currentSearch?.close()
                        currentSearch = null
                    },
                    onDismissToc = { tocDialogOpen = false },
                    onContainerAvailable = {
                        containerAvailable = true
                        installNavigatorIfReady()
                    },
                    onBack = ::finish,
                )
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PlaybackStateStore.state.collect { state ->
                    if (state.bookUri != uri?.toString()) {
                        playbackState = PlaybackUiState()
                        isTtsPlaying = false
                        recenterButtonVisible = false
                        return@collect
                    }
                    playbackState = state
                    isTtsPlaying = state.isPlaying
                    playbackError = state.errorMessage
                    val locator = state.currentLocator
                    if (locator != null && locator != observedPlaybackLocator) {
                        observedPlaybackLocator = locator
                        applyTtsDecoration(locator)
                        if (autoFollowReadingPosition) followTtsLocator(locator)
                    }
                }
            }
        }

        if (uri == null) {
            showError("This library entry doesn't have a file address. Return to the bookshelf and add it again.")
        } else if (format == null) {
            showError("This file format isn't supported by the reader.")
        } else {
            lifecycleScope.launch {
                try {
                    val openedPublication = withContext(Dispatchers.IO) {
                        loader.open(uri, intent.getDoubleExtra(EXTRA_PROGRESS_PERCENT, 0.0))
                    }
                    publication = openedPublication.publication
                    initialLocator = openedPublication.initialLocator
                    val opened = requireNotNull(publication)
                    positionLocators = withContext(Dispatchers.IO) {
                        opened.positionsByReadingOrder().flatten()
                    }
                    pageCount = positionLocators.size
                    tableOfContents = opened.tableOfContents
                    bookTitle = opened.metadata.title?.takeIf(String::isNotBlank) ?: bookTitle
                    val author = opened.metadata.authors.joinToString { it.name }.takeIf(String::isNotBlank)
                    bookAuthor = author
                    val cover = runCatching {
                        opened.coverFitting(Size(240, 360))
                    }.getOrNull()
                    withContext(Dispatchers.IO) {
                        libraryRepository.markBookOpened(uri.toString())
                        libraryRepository.updatePublicationInfo(
                            uriString = uri.toString(),
                            title = opened.metadata.title,
                            author = author,
                            coverBitmap = cover,
                        )
                        libraryRepository.findBook(uri.toString())?.coverPath
                    }?.let { coverPath = it }
                    isLoading = false
                    installNavigatorIfReady()
                } catch (error: Exception) {
                    showError(error.message ?: "EpubReader couldn't open this book.")
                }
            }
        }
    }

    private fun installNavigatorIfReady() {
        val openedPublication = publication ?: return
        if (!containerAvailable || isFinishing || supportFragmentManager.isStateSaved) return
        if (supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG) != null) {
            isNavigatorReady = true
            seekToActivePlaybackIfReady()
            return
        }

        val (factory, fragmentClass) = when (
            BookFormat.from(
                name = intent.getStringExtra(EXTRA_BOOK_NAME),
                mimeType = intent.getStringExtra(EXTRA_BOOK_MIME),
            )
        ) {
            BookFormat.EPUB -> EpubNavigatorFactory(openedPublication)
                .createFragmentFactory(
                    initialLocator = initialLocator,
                    initialPreferences = epubPreferences(),
                    configuration = EpubNavigatorFragment.Configuration(
                        shouldApplyInsetsPadding = false,
                    ),
                ) to EpubNavigatorFragment::class.java
            BookFormat.PDF -> PdfNavigatorFactory(openedPublication, pdfiumEngineProvider)
                .createFragmentFactory(initialLocator = initialLocator) to PdfNavigatorFragment::class.java
            null -> return
        }
        supportFragmentManager.fragmentFactory = factory
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .add(navigatorContainerId, fragmentClass, Bundle(), NAVIGATOR_TAG)
            .commitNow()
        when (val navigator = supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG)) {
            is EpubNavigatorFragment -> {
                observeReadingProgress(navigator.currentLocator, openedBookUri(), isPdf = false)
                navigator.addInputListener(readerInputListener)
            }
            is PdfNavigatorFragment<*, *> -> observeReadingProgress(navigator.currentLocator, openedBookUri(), isPdf = true)
        }
        isNavigatorReady = true
        seekToActivePlaybackIfReady()
    }

    /**
     * Playback state can arrive while the navigator is still installing, in which
     * case the single `StateFlow` emission is missed and the reader would stay at
     * the saved percentage. Once the navigator exists, jump to the locator the
     * session is narrating right now.
     */
    private fun seekToActivePlaybackIfReady() {
        val uri = intent.getStringExtra(EXTRA_BOOK_URI) ?: return
        val playback = PlaybackStateStore.state.value
        if (playback.bookUri != uri || !playback.showMiniPlayer) return
        val locator = playback.currentLocator ?: return
        observedPlaybackLocator = locator
        lifecycleScope.launch {
            applyTtsDecoration(locator)
            followTtsLocator(locator)
        }
    }

    private fun observeReadingProgress(locators: StateFlow<Locator>, bookUri: String, isPdf: Boolean) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                locators.collect { locator ->
                    locator.locations.totalProgression?.let { progress ->
                        currentProgress = progress.toFloat().coerceIn(0f, 1f)
                        if (isPdf) {
                            currentPage = locator.locations.position
                                ?: ((currentProgress * (pageCount - 1)).toInt() + 1).coerceAtLeast(1)
                            pageLabel = "Page $currentPage of $pageCount"
                        } else if (pageLabel.startsWith("Location") || pageLabel.startsWith("Preparing")) {
                            currentPage = locator.locations.position
                                ?: ((currentProgress * (pageCount - 1)).toInt() + 1).coerceAtLeast(1)
                            pageLabel = "Location $currentPage of $pageCount"
                        }
                        withContext(Dispatchers.IO) {
                            libraryRepository.updateReadingProgress(bookUri, progress)
                        }
                    }
                }
            }
        }
    }

    private fun openedBookUri(): String = requireNotNull(intent.getStringExtra(EXTRA_BOOK_URI))

    /**
     * A double tap on the text starts narration at the sentence under the finger.
     * Readium's input listener only reports single taps, so a double tap is detected
     * by matching two taps that land close together in time and space.
     *
     * A touch drag is a genuine scroll by the reader, so it stops auto-following
     * until they tap the recenter button. Readium only reports these events for
     * real touch gestures, never for programmatic `go()`/`window.scrollBy` calls,
     * which makes this a reliable manual-scroll signal.
     */
    private val readerInputListener = object : InputListener {
        override fun onTap(event: TapEvent): Boolean {
            val now = SystemClock.uptimeMillis()
            val point = event.point
            val slop = ViewConfiguration.get(this@ReaderActivity).scaledDoubleTapSlop.toFloat()
            val dx = point.x - lastTapX
            val dy = point.y - lastTapY
            val isDoubleTap = lastTapAt != 0L &&
                now - lastTapAt <= DOUBLE_TAP_TIMEOUT_MS &&
                dx * dx + dy * dy <= slop * slop
            lastTapAt = if (isDoubleTap) 0L else now
            lastTapX = point.x
            lastTapY = point.y
            if (!isDoubleTap) return false
            startReadAloudAtPoint(point)
            return true
        }

        override fun onDrag(event: DragEvent): Boolean {
            if (event.type == DragEvent.Type.Start) {
                stopAutoFollowing()
            }
            return false
        }
    }

    /** The reader took over scrolling, so stop moving the page under them. */
    private fun stopAutoFollowing() {
        if (!isTtsPlaying || !autoFollowReadingPosition) return
        autoFollowReadingPosition = false
        recenterButtonVisible = true
    }

    private fun startReadAloudAtPoint(point: PointF) {
        if (currentFormat != BookFormat.EPUB) return
        lifecycleScope.launch {
            val selection = sentenceAtPoint(point)
            if (selection == null) {
                playbackError = "Couldn't find a sentence at that spot."
                return@launch
            }
            startTtsAt(selection.locator, alignToSelection = true)
        }
    }

    private data class SentenceSelection(val locator: Locator, val text: String)

    /**
     * Finds the sentence under a device-pixel [point] in the EPUB content and
     * returns it as a locator carrying the sentence text and the block's
     * `cssSelector`, so narration can start at the tapped sentence.
     */
    private suspend fun sentenceAtPoint(point: PointF): SentenceSelection? {
        val navigator = currentNavigatorFragment() as? EpubNavigatorFragment ?: return null
        val script = SENTENCE_AT_POINT_JS
            .replace("__X__", point.x.toString())
            .replace("__Y__", point.y.toString())
        val json = runCatching { navigator.evaluateJavascript(script) }.getOrNull()
        val obj = json?.takeIf { it != "null" }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null
        val text = obj.optString("text").takeIf { it.isNotBlank() } ?: return null
        val base = navigator.currentLocator.value
        val selector = obj.optString("selector").takeIf { it.isNotBlank() }
        val locations = if (selector == null) {
            base.locations
        } else {
            base.locations.copy(
                otherLocations = base.locations.otherLocations + ("cssSelector" to selector),
            )
        }
        return SentenceSelection(
            locator = base.copy(locations = locations, text = Locator.Text(highlight = text)),
            text = text,
        )
    }

    private suspend fun applyTtsDecoration(locator: Locator) {
        (currentNavigatorFragment() as? DecorableNavigator)?.applyDecorations(
            listOf(
                Decoration(
                    id = TTS_DECORATION_ID,
                    locator = locator,
                    style = Decoration.Style.Highlight(
                        tint = android.graphics.Color.YELLOW,
                        isActive = true,
                    ),
                ),
            ),
            TTS_DECORATION_GROUP,
        )
    }

    private fun recenterOnReadingPosition() {
        autoFollowReadingPosition = true
        recenterButtonVisible = false
        val locator = observedPlaybackLocator ?: return
        followTtsLocator(locator)
    }

    /**
     * Keeps the sentence being narrated inside a comfortable middle band. A new
     * resource (chapter) is loaded by Readium first; within a resource the view is
     * only moved when the sentence drifts outside the band, so the page does not
     * jump for every utterance.
     */
    private fun followTtsLocator(locator: Locator) {
        val navigator = currentVisualNavigator() ?: return
        lifecycleScope.launch {
            if (navigator.currentLocator.value.href != locator.href) {
                navigator.go(locator, animated = false)
                delay(RESOURCE_LOAD_SETTLE_MS)
            }
            centerOnNarratedSentence(locator)
        }
    }

    private suspend fun centerOnNarratedSentence(locator: Locator) {
        val fragment = currentNavigatorFragment() as? EpubNavigatorFragment ?: return
        val selector = locator.locations.cssSelector ?: return
        val highlight = locator.text.highlight?.takeIf(String::isNotBlank) ?: return
        val script = CENTER_SENTENCE_JS
            .replace("__SELECTOR__", JSONObject.quote(selector))
            .replace("__TEXT__", JSONObject.quote(highlight))
        runCatching { fragment.evaluateJavascript(script) }
    }

    private fun currentNavigatorFragment(): androidx.fragment.app.Fragment? =
        supportFragmentManager.findFragmentByTag(NAVIGATOR_TAG)

    private fun currentVisualNavigator(): VisualNavigator? =
        currentNavigatorFragment() as? VisualNavigator

    private fun togglePlayback() {
        if (currentFormat != BookFormat.EPUB) {
            playbackError = "Text-to-speech is currently available for EPUB books."
            return
        }
        val uri = intent.getStringExtra(EXTRA_BOOK_URI) ?: return
        val currentPlayback = PlaybackStateStore.state.value
        if (currentPlayback.bookUri == uri && currentPlayback.showMiniPlayer) {
            PlaybackServiceCommands.send(this, PlaybackServiceCommands.ACTION_TOGGLE)
            return
        }
        lifecycleScope.launch {
            try {
                val visibleLocator = currentVisualNavigator()?.firstVisibleElementLocator()
                startTtsAt(visibleLocator)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                playbackError = error.message ?: "Could not start narration."
            }
        }
    }

    /** Skip controls only act while this book is the active narration session. */
    private fun skipPlayback(forward: Boolean) {
        if (!isActivePlayback()) return
        PlaybackServiceCommands.send(
            this,
            if (forward) PlaybackServiceCommands.ACTION_SKIP_FORWARD else PlaybackServiceCommands.ACTION_SKIP_BACK,
        )
    }

    private fun stopPlaybackFromReader() {
        if (!isActivePlayback()) return
        PlaybackServiceCommands.send(this, PlaybackServiceCommands.ACTION_STOP)
    }

    private fun isActivePlayback(): Boolean {
        val uri = intent.getStringExtra(EXTRA_BOOK_URI) ?: return false
        val playback = PlaybackStateStore.state.value
        return playback.bookUri == uri && playback.showMiniPlayer
    }

    private fun startTtsAt(locator: Locator?, alignToSelection: Boolean = false) {
        if (currentFormat != BookFormat.EPUB) {
            playbackError = "Text-to-speech is currently available for EPUB books."
            return
        }
        val uri = intent.getStringExtra(EXTRA_BOOK_URI) ?: return
        playbackError = null
        autoFollowReadingPosition = true
        recenterButtonVisible = false
        launchPlaybackService(locator, alignToSelection)
    }

    private fun launchPlaybackService(locator: Locator?, alignToSelection: Boolean = false) {
        val uri = intent.getStringExtra(EXTRA_BOOK_URI) ?: return
        PlaybackServiceCommands.start(
            context = this,
            bookUri = uri,
            progressPercent = currentProgress * 100.0,
            initialLocator = locator,
            alignToSelection = alignToSelection,
        )
    }

    private fun seekToProgress(progress: Float) {
        currentProgress = progress.coerceIn(0f, 1f)
        val openedPublication = publication ?: return
        lifecycleScope.launch {
            val locator = withContext(Dispatchers.IO) {
                openedPublication.locateProgression(currentProgress.toDouble())
            }
            locator?.let(::goToLocator)
        }
    }

    private fun jumpToPage(page: Int) {
        val locator = positionLocators.getOrNull(page - 1)
        if (locator == null) {
            playbackError = "That page or location is not available in this publication."
        } else {
            goToLocator(locator)
        }
    }

    private fun navigateReader(forward: Boolean) {
        val navigator = currentVisualNavigator() as? OverflowableNavigator ?: return
        if (forward) navigator.goForward(animated = true) else navigator.goBackward(animated = true)
    }

    private fun goToLocator(locator: Locator) {
        currentVisualNavigator()?.go(locator, animated = false)
        val uri = intent.getStringExtra(EXTRA_BOOK_URI)
        val playback = PlaybackStateStore.state.value
        if (uri != null && playback.bookUri == uri && playback.showMiniPlayer) {
            PlaybackServiceCommands.seekToLocator(this, locator)
        }
    }

    private fun goToLink(link: Link) {
        currentVisualNavigator()?.go(link, animated = false)
        tocDialogOpen = false
    }

    private fun searchPublication(query: String) {
        val openedPublication = publication ?: return
        if (query.isBlank()) return
        currentSearch?.close()
        currentSearch = null
        searchError = null
        searchResults = emptyList()
        lifecycleScope.launch {
            try {
                val iterator = withContext(Dispatchers.IO) { openedPublication.search(query) }
                if (iterator == null) {
                    searchError = "Search is not available for this publication."
                    return@launch
                }
                currentSearch = iterator
                searchResults = withContext(Dispatchers.IO) {
                    buildList {
                        while (size < MAX_SEARCH_RESULTS) {
                            val batch = iterator.next().getOrElse { error ->
                                throw IllegalStateException(error.message)
                            } ?: break
                            addAll(batch.locators.take(MAX_SEARCH_RESULTS - size))
                        }
                    }
                }
                if (searchResults.isEmpty()) searchError = "No matches found."
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                searchError = error.message ?: "Search failed."
            }
        }
    }

    private fun flattenTableOfContents(links: List<Link>): List<Link> = buildList {
        fun append(items: List<Link>) {
            items.forEach { link ->
                add(link)
                append(link.children)
            }
        }
        append(links)
    }

    private fun epubPreferences(): EpubPreferences {
        val family = when (AppPreferences.readerFontFamily(this)) {
            ReaderFontFamily.BOOK_DEFAULT -> null
            ReaderFontFamily.SERIF -> FontFamily("serif")
            ReaderFontFamily.SANS_SERIF -> FontFamily("sans-serif")
        }
        val darkTheme = when (AppPreferences.themeMode(this)) {
            com.geneing.epubreader.data.ThemeMode.DARK -> true
            com.geneing.epubreader.data.ThemeMode.LIGHT -> false
            com.geneing.epubreader.data.ThemeMode.SYSTEM ->
                resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        return EpubPreferences(
            fontFamily = family,
            fontSize = AppPreferences.readerFontScale(this).toDouble(),
            scroll = true,
            theme = if (darkTheme) ReadiumTheme.DARK else ReadiumTheme.LIGHT,
        )
    }

    override fun onDestroy() {
        (currentNavigatorFragment() as? VisualNavigator)?.removeInputListener(readerInputListener)
        currentSearch?.close()
        currentSearch = null
        publication?.close()
        publication = null
        super.onDestroy()
    }

    private fun showError(message: String) {
        errorMessage = message
        isLoading = false
    }

    companion object {
        const val EXTRA_BOOK_URI = "book_uri"
        const val EXTRA_BOOK_NAME = "book_name"
        const val EXTRA_BOOK_MIME = "book_mime"
        const val EXTRA_PROGRESS_PERCENT = "progress_percent"
        private const val NAVIGATOR_TAG = "readium-epub-navigator"
        private const val TTS_DECORATION_ID = "tts-current-utterance"
        private const val TTS_DECORATION_GROUP = "tts"
        private const val MAX_SEARCH_RESULTS = 100
        private const val DOUBLE_TAP_TIMEOUT_MS = 300L
        private const val RESOURCE_LOAD_SETTLE_MS = 450L

        /**
         * Finds the sentence under the tapped point (device pixels) and returns
         * `{ selector, text }`, where `selector` is the CSS selector of the nearest
         * block element. Used to start narration at the double-tapped sentence.
         */
        private val SENTENCE_AT_POINT_JS = """
            (function () {
              var ratio = window.devicePixelRatio || 1;
              var range = null;
              var pointX = __X__ / ratio;
              var pointY = __Y__ / ratio;
              if (document.caretRangeFromPoint) {
                range = document.caretRangeFromPoint(pointX, pointY);
              } else if (document.caretPositionFromPoint) {
                var caret = document.caretPositionFromPoint(pointX, pointY);
                if (caret) {
                  range = document.createRange();
                  range.setStart(caret.offsetNode, caret.offset);
                  range.collapse(true);
                }
              }
              if (!range) return null;
              var startNode = range.startContainer;
              var startOffset = range.startOffset;
              var element = startNode.nodeType === 3 ? startNode.parentNode : startNode;
              var BLOCK = 'p,li,h1,h2,h3,h4,h5,h6,blockquote,td,th,figcaption,dd,dt,pre,article,section';
              var block = (element && element.closest) ? (element.closest(BLOCK) || element) : element;
              if (!block || !block.ownerDocument) return null;
              var walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT, null);
              var nodes = [];
              var blockText = '';
              var node;
              while ((node = walker.nextNode())) {
                nodes.push({ node: node, start: blockText.length, end: blockText.length + node.nodeValue.length });
                blockText += node.nodeValue;
              }
              if (nodes.length === 0) return null;
              function offsetOf(n, o) {
                for (var k = 0; k < nodes.length; k++) {
                  if (nodes[k].node === n) return nodes[k].start + o;
                }
                return -1;
              }
              var pos = offsetOf(startNode, startOffset);
              if (pos < 0) pos = 0;
              var i = pos;
              while (i > 0) {
                var before = blockText.charAt(i - 1);
                if (before === '.' || before === '!' || before === '?' || before === '\n' || before === '\r') break;
                i--;
              }
              var start = i;
              while (start < blockText.length && /\s/.test(blockText.charAt(start))) start++;
              var j = Math.max(pos, i);
              while (j < blockText.length) {
                var ch = blockText.charAt(j);
                if (ch === '.' || ch === '!' || ch === '?') { j++; break; }
                if (ch === '\n' || ch === '\r') break;
                j++;
              }
              while (j < blockText.length && /["'\u2019\u201d)\]]/.test(blockText.charAt(j))) j++;
              if (j <= start) return null;
              function selectorFor(el) {
                var parts = [];
                while (el && el.nodeType === 1 && el !== document.body && el !== document.documentElement) {
                  var part = el.tagName.toLowerCase();
                  var parent = el.parentElement;
                  if (parent && parent.children.length > 1) {
                    part += ':nth-child(' + (Array.prototype.indexOf.call(parent.children, el) + 1) + ')';
                  }
                  parts.unshift(part);
                  el = parent;
                }
                return parts.join(' > ');
              }
              return { selector: selectorFor(block), text: blockText.substring(start, j) };
            })()
        """.trimIndent()

        /**
         * Scrolls the narrated sentence into the middle band of the viewport, but
         * only when it has drifted outside the comfortable area so the page does not
         * jump for every utterance. `__SELECTOR__` and `__TEXT__` are replaced with
         * JSON-quoted arguments.
         */
        private val CENTER_SENTENCE_JS = """
            (function () {
              var selector = __SELECTOR__;
              var highlight = __TEXT__;
              if (!highlight) return false;
              var root = selector ? document.querySelector(selector) : document.body;
              if (!root) root = document.body;
              function buildIndex(el) {
                var walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null);
                var nodes = [];
                var text = '';
                var n;
                while ((n = walker.nextNode())) {
                  nodes.push({ node: n, start: text.length, end: text.length + n.nodeValue.length });
                  text += n.nodeValue;
                }
                return { nodes: nodes, text: text };
              }
              function normalizeMapping(raw) {
                var map = [];
                var out = '';
                var lastSpace = true;
                for (var i = 0; i < raw.length; i++) {
                  var c = raw.charAt(i);
                  if (/\s/.test(c)) {
                    if (!lastSpace && out.length > 0) { map.push(i); out += ' '; }
                    lastSpace = true;
                  } else {
                    map.push(i);
                    out += c;
                    lastSpace = false;
                  }
                }
                while (out.length > 0 && out.charAt(out.length - 1) === ' ') {
                  out = out.substring(0, out.length - 1);
                  map.pop();
                }
                return { text: out, map: map };
              }
              function locate(nodes, offset) {
                for (var k = 0; k < nodes.length; k++) {
                  if (offset >= nodes[k].start && offset <= nodes[k].end) {
                    return { node: nodes[k].node, offset: offset - nodes[k].start };
                  }
                }
                var last = nodes[nodes.length - 1];
                if (!last) return null;
                return { node: last.node, offset: last.node.nodeValue.length };
              }
              var index = buildIndex(root);
              var normalized = normalizeMapping(index.text);
              var target = highlight.replace(/\s+/g, ' ').replace(/^\s+|\s+$/g, '');
              if (target.length === 0) return false;
              var at = normalized.text.indexOf(target);
              if (at < 0) return false;
              var startRaw = normalized.map[at];
              var endNorm = at + target.length;
              var endRaw = endNorm < normalized.map.length ? normalized.map[endNorm] : index.text.length;
              var from = locate(index.nodes, startRaw);
              var to = locate(index.nodes, endRaw);
              if (!from || !to) return false;
              var range = document.createRange();
              try {
                range.setStart(from.node, from.offset);
                range.setEnd(to.node, to.offset);
              } catch (error) {
                return false;
              }
              var rect = range.getBoundingClientRect();
              if (!rect || (rect.width === 0 && rect.height === 0)) return false;
              var height = window.innerHeight || document.documentElement.clientHeight || 0;
              if (height <= 0) return false;
              if (rect.top >= height * 0.22 && rect.bottom <= height * 0.74) return true;
              window.scrollBy(0, rect.top - height * 0.38);
              return true;
            })()
        """.trimIndent()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderScreen(
    title: String,
    isLoading: Boolean,
    errorMessage: String?,
    playbackError: String?,
    navigatorReady: Boolean,
    navigatorContainerId: Int,
    format: BookFormat?,
    playback: PlaybackUiState,
    showRecenterButton: Boolean,
    currentProgress: Float,
    pageLabel: String,
    pageCount: Int,
    searchResults: List<Locator>,
    searchError: String?,
    searchDialogOpen: Boolean,
    tableOfContents: List<Link>,
    tocDialogOpen: Boolean,
    onTogglePlayback: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onStopPlayback: () -> Unit,
    onRecenter: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onSeekProgress: (Float) -> Unit,
    onJumpToPage: (Int) -> Unit,
    onSearchRequested: () -> Unit,
    onSearch: (String) -> Unit,
    onSearchResultSelected: (Locator) -> Unit,
    onTocRequested: () -> Unit,
    onTocItemSelected: (Link) -> Unit,
    onDismissSearch: () -> Unit,
    onDismissToc: () -> Unit,
    onContainerAvailable: () -> Unit,
    onBack: () -> Unit,
) {
    var pagePickerOpen by remember { mutableStateOf(false) }
    var pageEntry by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var sliderPosition by remember(currentProgress) { mutableStateOf(currentProgress.coerceIn(0f, 1f)) }
    var scrollBarVisible by remember { mutableStateOf(false) }
    val pageUnit = if (format == BookFormat.PDF) "page" else "location"
    val isPdf = format == BookFormat.PDF

    // The vertical scrollbar fades in while the user scrolls and out when idle.
    LaunchedEffect(currentProgress) {
        scrollBarVisible = true
        delay(900)
        scrollBarVisible = false
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to library")
                    }
                },
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = onTocRequested, enabled = tableOfContents.isNotEmpty()) {
                        Icon(Icons.Outlined.Book, contentDescription = "Table of contents")
                    }
                    IconButton(onClick = onSearchRequested) {
                        Icon(Icons.Outlined.Search, contentDescription = "Find in book")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            if (isPdf && navigatorReady && errorMessage == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        IconButton(onClick = onPreviousPage) {
                            Icon(Icons.Outlined.SkipPrevious, contentDescription = "Previous page")
                        }
                        TextButton(
                            onClick = {
                                pageEntry = (((currentProgress * (pageCount - 1)).toInt()) + 1)
                                    .coerceIn(1, pageCount.coerceAtLeast(1))
                                    .toString()
                                pagePickerOpen = true
                            },
                            enabled = pageCount > 0,
                        ) {
                            Text(pageLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = onNextPage) {
                            Icon(Icons.Outlined.SkipNext, contentDescription = "Next page")
                        }
                    }
                    Slider(
                        value = sliderPosition,
                        onValueChange = { sliderPosition = it },
                        onValueChangeFinished = { onSeekProgress(sliderPosition) },
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("${(sliderPosition * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                        TextButton(
                            onClick = {
                                pageEntry = ""
                                pagePickerOpen = true
                            },
                            enabled = pageCount > 0,
                        ) {
                            Text("Go to $pageUnit")
                        }
                        Text("100%", style = MaterialTheme.typography.labelSmall)
                    }
                    playbackError?.let { message ->
                        Text(
                            message,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            } else if (!isPdf && navigatorReady && errorMessage == null) {
                PlaybackMiniPlayer(
                    playback = playback,
                    onSkipBack = onSkipBack,
                    onToggle = onTogglePlayback,
                    onSkipForward = onSkipForward,
                    onStop = onStopPlayback,
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    FragmentContainerView(context).apply {
                        id = navigatorContainerId
                        setBackgroundColor(android.graphics.Color.WHITE)
                        onContainerAvailable()
                    }
                },
            )

            if (!isPdf && navigatorReady && errorMessage == null) {
                VerticalScrollIndicator(
                    progress = currentProgress,
                    visible = scrollBarVisible,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 3.dp)
                        .fillMaxHeight(),
                )
            }

            if (!isPdf && navigatorReady && errorMessage == null && showRecenterButton) {
                SmallFloatingActionButton(
                    onClick = onRecenter,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                ) {
                    Icon(
                        Icons.Outlined.VerticalAlignCenter,
                        contentDescription = "Jump to where narration is",
                    )
                }
            }

            when {
                errorMessage != null -> ReaderMessage(
                    message = errorMessage,
                    contentPadding = padding,
                )
                isLoading || !navigatorReady -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            if (isPdf) {
                playbackError?.let { message ->
                    Text(
                        message,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                playbackError?.let { message ->
                    Text(
                        message,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (pagePickerOpen) {
        AlertDialog(
            onDismissRequest = { pagePickerOpen = false },
            title = { Text("Go to $pageUnit") },
            text = {
                Column {
                    Text("Enter a $pageUnit from 1 to $pageCount.")
                    OutlinedTextField(
                        value = pageEntry,
                        onValueChange = { value -> pageEntry = value.filter(Char::isDigit).take(6) },
                        label = { Text(pageUnit.replaceFirstChar(Char::uppercase)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pageEntry.toIntOrNull()?.takeIf { it in 1..pageCount }?.let(onJumpToPage)
                        pagePickerOpen = false
                    },
                    enabled = pageEntry.toIntOrNull() in 1..pageCount,
                ) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { pagePickerOpen = false }) { Text("Cancel") } },
        )
    }

    if (searchDialogOpen) {
        AlertDialog(
            onDismissRequest = onDismissSearch,
            title = { Text("Find in book") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search text") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    searchError?.let { Text(it, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        searchResults.forEachIndexed { index, locator ->
                            TextButton(
                                onClick = {
                                    onSearchResultSelected(locator)
                                    onDismissSearch()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = locator.text.highlight?.takeIf(String::isNotBlank)
                                        ?: locator.title
                                        ?: "Match ${index + 1}",
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onSearch(searchQuery) }) { Text("Find") } },
            dismissButton = { TextButton(onClick = onDismissSearch) { Text("Close") } },
        )
    }

    if (tocDialogOpen) {
        AlertDialog(
            onDismissRequest = onDismissToc,
            title = { Text("Table of contents") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    tableOfContents.forEach { link ->
                        TextButton(onClick = { onTocItemSelected(link) }, modifier = Modifier.fillMaxWidth()) {
                            Text(link.title ?: link.href.toString(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismissToc) { Text("Close") } },
        )
    }
}

@Composable
private fun VerticalScrollIndicator(
    progress: Float,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    RoundedCornerShape(2.dp),
                ),
        ) {
            val thumbHeight = 56.dp
            val travel = (maxHeight - thumbHeight).coerceAtLeast(0.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(thumbHeight)
                    .offset(y = travel * progress.coerceIn(0f, 1f))
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        RoundedCornerShape(2.dp),
                    ),
            )
        }
    }
}

@Composable
private fun ReaderMessage(message: String, contentPadding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp)
            .padding(bottom = contentPadding.calculateBottomPadding()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("This book can't be opened yet", style = MaterialTheme.typography.titleLarge)
        Text(
            message,
            modifier = Modifier.padding(top = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
