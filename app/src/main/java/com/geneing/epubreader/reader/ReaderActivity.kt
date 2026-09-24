package com.geneing.epubreader.reader

import android.net.Uri
import android.os.Bundle
import android.content.res.Configuration
import android.util.Size
import android.view.View
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.geneing.epubreader.ui.EpubReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.coverFitting
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.navigator.preferences.Theme as ReadiumTheme

@OptIn(ExperimentalReadiumApi::class)
class ReaderActivity : FragmentActivity() {
    private val loader by lazy { ReadiumPublicationLoader(applicationContext) }
    private val libraryRepository by lazy { LibraryRepository(applicationContext) }
    private val navigatorContainerId = View.generateViewId()
    private val pdfiumEngineProvider by lazy { PdfiumEngineProvider() }

    private var bookTitle by mutableStateOf("Reader")
    private var isLoading by mutableStateOf(true)
    private var errorMessage by mutableStateOf<String?>(null)
    private var isNavigatorReady by mutableStateOf(false)
    private var publication: Publication? = null
    private var initialLocator: Locator? = null
    private var containerAvailable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val format = BookFormat.from(
            name = intent.getStringExtra(EXTRA_BOOK_NAME),
            mimeType = intent.getStringExtra(EXTRA_BOOK_MIME),
        )
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
                    navigatorReady = isNavigatorReady,
                    navigatorContainerId = navigatorContainerId,
                    onContainerAvailable = {
                        containerAvailable = true
                        installNavigatorIfReady()
                    },
                    onBack = ::finish,
                )
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
            is EpubNavigatorFragment -> observeReadingProgress(navigator.currentLocator, openedBookUri())
            is PdfNavigatorFragment<*, *> -> observeReadingProgress(navigator.currentLocator, openedBookUri())
        }
        isNavigatorReady = true
    }

    private fun observeReadingProgress(locators: StateFlow<Locator>, bookUri: String) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                locators.collect { locator ->
                    locator.locations.totalProgression?.let { progress ->
                        withContext(Dispatchers.IO) {
                            libraryRepository.updateReadingProgress(bookUri, progress)
                        }
                    }
                }
            }
        }
    }

    private fun openedBookUri(): String = requireNotNull(intent.getStringExtra(EXTRA_BOOK_URI))

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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderScreen(
    title: String,
    isLoading: Boolean,
    errorMessage: String?,
    navigatorReady: Boolean,
    navigatorContainerId: Int,
    onContainerAvailable: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to library")
                    }
                },
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
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
