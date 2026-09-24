package com.geneing.epubreader.reader

import android.net.Uri
import android.os.Bundle
import android.content.res.Configuration
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.util.Size
import android.view.View
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import com.geneing.epubreader.ui.EpubReaderTheme
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
import org.readium.r2.navigator.SelectableNavigator
import org.readium.r2.navigator.VisualNavigator
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.Locator
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
                ReaderScreen(
                    title = bookTitle,
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    playbackError = playbackError,
                    navigatorReady = isNavigatorReady,
                    navigatorContainerId = navigatorContainerId,
                    format = format,
                    isTtsPlaying = isTtsPlaying,
                    currentProgress = currentProgress,
                    pageLabel = pageLabel,
                    pageCount = pageCount,
                    searchResults = searchResults,
                    searchError = searchError,
                    searchDialogOpen = searchDialogOpen,
                    tableOfContents = flattenTableOfContents(tableOfContents),
                    onTogglePlayback = ::togglePlayback,
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
                        isTtsPlaying = false
                        return@collect
                    }
                    isTtsPlaying = state.isPlaying
                    playbackError = state.errorMessage
                    val locator = state.currentLocator
                    if (locator != null && locator != observedPlaybackLocator) {
                        observedPlaybackLocator = locator
                        currentVisualNavigator()?.go(locator, animated = false)
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
                    }
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
                        selectionActionModeCallback = selectionActionModeCallback,
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
            is EpubNavigatorFragment -> observeReadingProgress(navigator.currentLocator, openedBookUri(), isPdf = false)
            is PdfNavigatorFragment<*, *> -> observeReadingProgress(navigator.currentLocator, openedBookUri(), isPdf = true)
        }
        isNavigatorReady = true
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

    private val selectionActionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, MENU_READ_FROM_HERE, Menu.NONE, "Read from here")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(Menu.NONE, MENU_COPY_SELECTION, Menu.NONE, "Copy")
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != MENU_READ_FROM_HERE && item.itemId != MENU_COPY_SELECTION) return false
            val actionId = item.itemId
            lifecycleScope.launch {
                val selection = (currentNavigatorFragment() as? SelectableNavigator)?.currentSelection()
                val selectedText = selection?.locator?.text?.highlight.orEmpty()
                mode.finish()
                if (actionId == MENU_READ_FROM_HERE) {
                    startTtsAt(selection?.locator)
                } else if (selectedText.isNotBlank()) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Selected text", selectedText))
                }
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = Unit
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

    private fun startTtsAt(locator: Locator?) {
        if (currentFormat != BookFormat.EPUB) {
            playbackError = "Text-to-speech is currently available for EPUB books."
            return
        }
        val uri = intent.getStringExtra(EXTRA_BOOK_URI) ?: return
        playbackError = null
        launchPlaybackService(locator)
    }

    private fun launchPlaybackService(locator: Locator?) {
        val uri = intent.getStringExtra(EXTRA_BOOK_URI) ?: return
        PlaybackServiceCommands.start(
            context = this,
            bookUri = uri,
            progressPercent = currentProgress * 100.0,
            initialLocator = locator,
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
            typeScale = AppPreferences.readerFontScale(this).toDouble(),
            theme = if (darkTheme) ReadiumTheme.DARK else ReadiumTheme.LIGHT,
        )
    }

    override fun onDestroy() {
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
        private const val MENU_READ_FROM_HERE = 1
        private const val MENU_COPY_SELECTION = 2
        private const val TTS_DECORATION_ID = "tts-current-utterance"
        private const val TTS_DECORATION_GROUP = "tts"
        private const val MAX_SEARCH_RESULTS = 100
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
    isTtsPlaying: Boolean,
    currentProgress: Float,
    pageLabel: String,
    pageCount: Int,
    searchResults: List<Locator>,
    searchError: String?,
    searchDialogOpen: Boolean,
    tableOfContents: List<Link>,
    tocDialogOpen: Boolean,
    onTogglePlayback: () -> Unit,
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
    val pageUnit = if (format == BookFormat.PDF) "page" else "location"

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
            if (navigatorReady && errorMessage == null) {
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
            }
        },
        floatingActionButton = {
            if (navigatorReady && errorMessage == null) {
                ExtendedFloatingActionButton(
                    text = {
                        Text(
                            when {
                                format != BookFormat.EPUB -> "Narration unavailable"
                                isTtsPlaying -> "Pause reading"
                                else -> "Read aloud"
                            },
                        )
                    },
                    icon = {
                        Icon(
                            if (isTtsPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = null,
                        )
                    },
                    onClick = onTogglePlayback,
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

            when {
                errorMessage != null -> ReaderMessage(
                    message = errorMessage,
                    contentPadding = padding,
                )
                isLoading || !navigatorReady -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
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
