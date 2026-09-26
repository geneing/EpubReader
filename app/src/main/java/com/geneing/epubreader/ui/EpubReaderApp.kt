package com.geneing.epubreader.ui

import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import com.geneing.epubreader.LibraryViewModel
import com.geneing.epubreader.data.BookEntity
import com.geneing.epubreader.data.BookFolderEntity
import com.geneing.epubreader.data.BookFormat
import com.geneing.epubreader.data.AppPreferences
import com.geneing.epubreader.data.ReaderFontFamily
import com.geneing.epubreader.data.SpeechEngine
import com.geneing.epubreader.data.ThemeMode
import com.geneing.epubreader.playback.PlaybackServiceCommands
import com.geneing.epubreader.playback.PlaybackUiState
import com.geneing.epubreader.reader.ReaderActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val Sage = Color(0xFF3E6655)

private enum class LibraryDestination(
    val title: String,
    val icon: ImageVector,
) {
    RECENT("Recent files", Icons.Outlined.History),
    ALL_FILES("All files", Icons.AutoMirrored.Outlined.LibraryBooks),
    FOLDERS("Folders", Icons.Outlined.FolderOpen),
    FOLDER_CONTENTS("Folder contents", Icons.Outlined.FolderOpen),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubReaderApp(viewModel: LibraryViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = androidx.compose.material3.rememberDrawerState(DrawerValue.Closed)
    var destination by rememberSaveable { mutableStateOf(LibraryDestination.RECENT) }
    var selectedFolderUri by rememberSaveable { mutableStateOf<String?>(null) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        uri?.let(viewModel::addFolder)
    }
    val bookPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let(viewModel::addDocument)
    }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.dismissMessage()
        }
    }

    EpubReaderTheme(state.themeMode) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                        Text("EpubReader", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("Your personal bookshelf", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    LibraryDestination.entries.filterNot { it == LibraryDestination.FOLDER_CONTENTS }.forEach { item ->
                        NavigationDrawerItem(
                            label = { Text(item.title) },
                            selected = destination == item,
                            onClick = {
                                destination = item
                                selectedFolderUri = null
                                scope.launch { drawerState.close() }
                            },
                            icon = { Icon(item.icon, contentDescription = null) },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            },
        ) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    if (state.playback.showMiniPlayer) {
                        PlaybackMiniPlayer(
                            playback = state.playback,
                            onSkipBack = { PlaybackServiceCommands.send(context, PlaybackServiceCommands.ACTION_SKIP_BACK) },
                            onToggle = { PlaybackServiceCommands.send(context, PlaybackServiceCommands.ACTION_TOGGLE) },
                            onSkipForward = { PlaybackServiceCommands.send(context, PlaybackServiceCommands.ACTION_SKIP_FORWARD) },
                            onStop = { PlaybackServiceCommands.send(context, PlaybackServiceCommands.ACTION_STOP) },
                            onClick = {
                                state.books.firstOrNull { it.uri == state.playback.bookUri }?.let { book ->
                                    viewModel.markBookOpened(book)
                                    openBook(context, book)
                                }
                            },
                        )
                    }
                },
                topBar = {
                    CenterAlignedTopAppBar(
                        navigationIcon = {
                            if (destination == LibraryDestination.FOLDER_CONTENTS) {
                                IconButton(onClick = {
                                    selectedFolderUri = null
                                    destination = LibraryDestination.FOLDERS
                                }) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to folders")
                                }
                            } else {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Outlined.Menu, contentDescription = "Open navigation menu")
                                }
                            }
                        },
                        title = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("EpubReader", fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (destination == LibraryDestination.FOLDER_CONTENTS) {
                                        state.folders.firstOrNull { it.treeUri == selectedFolderUri }?.displayName ?: "Folder contents"
                                    } else destination.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        actions = {
                            if (destination == LibraryDestination.RECENT || destination == LibraryDestination.ALL_FILES || destination == LibraryDestination.FOLDER_CONTENTS) {
                                IconButton(onClick = { bookPicker.launch(arrayOf("*/*")) }) {
                                    Icon(Icons.Outlined.Add, contentDescription = "Add a book file")
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                    )
                },
                floatingActionButton = {
                    if (destination != LibraryDestination.SETTINGS) {
                        ExtendedFloatingActionButton(
                            onClick = { folderPicker.launch(null) },
                            icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                            text = { Text("Add book folder") },
                            containerColor = Sage,
                            contentColor = Color.White,
                        )
                    }
                },
                containerColor = MaterialTheme.colorScheme.background,
            ) { innerPadding ->
                when (destination) {
                    LibraryDestination.RECENT -> LibraryContent(
                        title = "Recently opened",
                        description = "Pick up where you left off.",
                        books = state.books.filter { it.lastOpenedAt != null }.sortedByDescending { it.lastOpenedAt },
                        totalBooks = state.books.size,
                        emptyTitle = "Your recent reads will appear here",
                        emptyDescription = "Open a book from All files and it will show up here.",
                        innerPadding = innerPadding,
                        onAddFolder = { folderPicker.launch(null) },
                        onAddFile = { bookPicker.launch(arrayOf("*/*")) },
                        onOpenBook = { book ->
                            viewModel.markBookOpened(book)
                            openBook(context, book)
                        },
                        onLoadMetadata = viewModel::loadBookMetadata,
                        onShareBook = { book -> shareBook(context, book) },
                        onRemoveBook = viewModel::removeBook,
                        busyKeys = state.busyKeys,
                    )
                    LibraryDestination.ALL_FILES -> LibraryContent(
                        title = "All files",
                        description = "Your EPUB and PDF books, wherever they are stored.",
                        books = state.books.sortedWith(compareByDescending<BookEntity> { it.lastOpenedAt }.thenBy { it.displayName.lowercase() }),
                        totalBooks = state.books.size,
                        emptyTitle = "Your bookshelf is ready",
                        emptyDescription = "Add a folder or choose a book file to get started.",
                        innerPadding = innerPadding,
                        onAddFolder = { folderPicker.launch(null) },
                        onAddFile = { bookPicker.launch(arrayOf("*/*")) },
                        onOpenBook = { book ->
                            viewModel.markBookOpened(book)
                            openBook(context, book)
                        },
                        onLoadMetadata = viewModel::loadBookMetadata,
                        onShareBook = { book -> shareBook(context, book) },
                        onRemoveBook = viewModel::removeBook,
                        busyKeys = state.busyKeys,
                    )
                    LibraryDestination.FOLDERS -> FoldersContent(
                        folders = state.folders,
                        books = state.books,
                        busyKeys = state.busyKeys,
                        innerPadding = innerPadding,
                        onAddFolder = { folderPicker.launch(null) },
                        onScan = viewModel::scanFolder,
                        onRemove = viewModel::removeFolder,
                        onOpenFolder = { folder ->
                            selectedFolderUri = folder.treeUri
                            destination = LibraryDestination.FOLDER_CONTENTS
                        },
                    )
                    LibraryDestination.FOLDER_CONTENTS -> {
                        val folder = state.folders.firstOrNull { it.treeUri == selectedFolderUri }
                        if (folder == null) {
                            LibraryContent(
                                title = "Folder unavailable",
                                description = "This folder is no longer connected to EpubReader.",
                                books = emptyList(),
                                totalBooks = 0,
                                emptyTitle = "Return to folder management",
                                emptyDescription = "Choose another connected folder or add a new one.",
                                innerPadding = innerPadding,
                                onAddFolder = { folderPicker.launch(null) },
                                onAddFile = null,
                                onOpenBook = {},
                                onLoadMetadata = {},
                                onShareBook = {},
                                onRemoveBook = {},
                                busyKeys = state.busyKeys,
                            )
                        } else {
                            FolderBooksContent(
                                folder = folder,
                                books = state.books.filter { it.folderUri == folder.treeUri },
                                innerPadding = innerPadding,
                                busyKeys = state.busyKeys,
                                onOpenBook = { book ->
                                    viewModel.markBookOpened(book)
                                    openBook(context, book)
                                },
                                onLoadMetadata = viewModel::loadBookMetadata,
                                onShareBook = { book -> shareBook(context, book) },
                                onRemoveBook = viewModel::removeBook,
                            )
                        }
                    }
                    LibraryDestination.SETTINGS -> SettingsContent(
                        themeMode = state.themeMode,
                        readerFontFamily = state.readerFontFamily,
                        readerFontScale = state.readerFontScale,
                        speechEngine = state.speechEngine,
                        speechRate = state.speechRate,
                        pocketTtsVoice = state.pocketTtsVoice,
                        pocketModels = state.pocketModels,
                        playbackGraceMinutes = state.playbackGraceMinutes,
                        resumeOnBluetoothReconnect = state.resumeOnBluetoothReconnect,
                        resumeAfterLongInterruption = state.resumeAfterLongInterruption,
                        innerPadding = innerPadding,
                        onThemeModeSelected = viewModel::setThemeMode,
                        onFontFamilySelected = viewModel::setReaderFontFamily,
                        onFontScaleChanged = viewModel::setReaderFontScale,
                        onSpeechEngineSelected = viewModel::setSpeechEngine,
                        onSpeechRateChanged = viewModel::setSpeechRate,
                        onPocketTtsVoiceSelected = viewModel::setPocketTtsVoice,
                        onInstallPocketModels = viewModel::installPocketModels,
                        onCancelPocketModelInstall = viewModel::cancelPocketModelInstall,
                        onRefreshPocketModels = viewModel::refreshPocketModels,
                        onPlaybackGraceMinutesChanged = viewModel::setPlaybackGraceMinutes,
                        onResumeOnBluetoothReconnectChanged = viewModel::setResumeOnBluetoothReconnect,
                        onResumeAfterLongInterruptionChanged = viewModel::setResumeAfterLongInterruption,
                    )
                }
            }
        }
    }
}

private fun openBook(context: android.content.Context, book: BookEntity) {
    runCatching {
        context.startActivity(
            Intent(context, ReaderActivity::class.java).apply {
                putExtra(ReaderActivity.EXTRA_BOOK_URI, book.uri)
                putExtra(ReaderActivity.EXTRA_BOOK_NAME, book.displayName)
                putExtra(ReaderActivity.EXTRA_BOOK_MIME, book.mimeType)
                putExtra(ReaderActivity.EXTRA_PROGRESS_PERCENT, book.progressPercent)
            },
        )
    }
}

private fun shareBook(context: android.content.Context, book: BookEntity) {
    runCatching {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = book.mimeType
            putExtra(Intent.EXTRA_STREAM, Uri.parse(book.uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share book"))
    }
}

@Composable
private fun LibraryContent(
    title: String,
    description: String,
    books: List<BookEntity>,
    totalBooks: Int,
    emptyTitle: String,
    emptyDescription: String,
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
    onAddFolder: (() -> Unit)?,
    onAddFile: (() -> Unit)?,
    onOpenBook: (BookEntity) -> Unit,
    onLoadMetadata: (BookEntity) -> Unit,
    onShareBook: (BookEntity) -> Unit,
    onRemoveBook: (BookEntity) -> Unit,
    busyKeys: Set<String>,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = maxWidth.coerceAtMost(720.dp))
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                top = 12.dp,
                end = 20.dp,
                bottom = 112.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ScreenHeader(
                    title = title,
                    description = description,
                    trailing = "$totalBooks ${if (totalBooks == 1) "book" else "books"} in library",
                )
            }
            if (books.isEmpty()) {
                item {
                    RouteEmptyCard(
                        title = emptyTitle,
                        description = emptyDescription,
                        onAddFolder = onAddFolder,
                        onAddFile = onAddFile,
                    )
                }
            } else {
                item { SectionHeading("BOOKS", books.size.toString()) }
                items(books, key = { "book:${it.uri}" }) { book ->
                    BookCard(
                        book = book,
                        removing = "remove:${book.uri}" in busyKeys,
                        onOpen = { onOpenBook(book) },
                        onLoadMetadata = { onLoadMetadata(book) },
                        onRemove = { onRemoveBook(book) },
                        onShare = { onShareBook(book) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FoldersContent(
    folders: List<BookFolderEntity>,
    books: List<BookEntity>,
    busyKeys: Set<String>,
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
    onAddFolder: (() -> Unit)?,
    onScan: (BookFolderEntity) -> Unit,
    onRemove: (BookFolderEntity) -> Unit,
    onOpenFolder: (BookFolderEntity) -> Unit,
) {
    val orderedFolders = folders.sortedByDescending { it.lastScannedAt ?: it.addedAt }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = maxWidth.coerceAtMost(720.dp))
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                top = 12.dp,
                end = 20.dp,
                bottom = 112.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ScreenHeader(
                    title = "Folder management",
                    description = "Connect folders, refresh their contents, or remove a folder from EpubReader.",
                    trailing = "${folders.size} ${if (folders.size == 1) "folder" else "folders"}",
                )
            }
            if (folders.isEmpty()) {
                item {
                    RouteEmptyCard(
                        title = "No folders connected",
                        description = "Add a folder to find EPUB and PDF books while leaving the files in place.",
                        onAddFolder = onAddFolder,
                        onAddFile = null,
                    )
                }
            } else {
                item { SectionHeading("CONNECTED FOLDERS", folders.size.toString()) }
                items(orderedFolders, key = { "all-folder:${it.treeUri}" }) { folder ->
                    FolderCard(
                        folder = folder,
                        bookCount = books.count { it.folderUri == folder.treeUri && it.isAvailable },
                        scanning = "scan:${folder.treeUri}" in busyKeys,
                        removing = "remove:${folder.treeUri}" in busyKeys,
                        onScan = { onScan(folder) },
                        onRemove = { onRemove(folder) },
                        onOpen = { onOpenFolder(folder) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderBooksContent(
    folder: BookFolderEntity,
    books: List<BookEntity>,
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
    busyKeys: Set<String>,
    onOpenBook: (BookEntity) -> Unit,
    onLoadMetadata: (BookEntity) -> Unit,
    onShareBook: (BookEntity) -> Unit,
    onRemoveBook: (BookEntity) -> Unit,
) {
    val orderedBooks = books.sortedWith(
        compareByDescending<BookEntity> { it.lastOpenedAt }.thenBy { it.displayName.lowercase() },
    )
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = maxWidth.coerceAtMost(720.dp))
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                top = 12.dp,
                end = 20.dp,
                bottom = 112.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ScreenHeader(
                    title = folder.displayName,
                    description = "Books found in this folder. Source files remain in their original location.",
                    trailing = "${orderedBooks.count(BookEntity::isAvailable)} available · last scanned ${folder.lastScannedAt?.let(::formatDate) ?: "not yet"}",
                )
            }
            if (orderedBooks.isEmpty()) {
                item {
                    RouteEmptyCard(
                        title = "No EPUB or PDF files found",
                        description = "Scan this folder again after adding books.",
                        onAddFolder = null,
                        onAddFile = null,
                    )
                }
            } else {
                item { SectionHeading("FILES IN THIS FOLDER", orderedBooks.size.toString()) }
                items(orderedBooks, key = { "folder-book:${it.uri}" }) { book ->
                    BookCard(
                        book = book,
                        removing = "remove:${book.uri}" in busyKeys,
                        onOpen = { onOpenBook(book) },
                        onLoadMetadata = { onLoadMetadata(book) },
                        onRemove = { onRemoveBook(book) },
                        onShare = { onShareBook(book) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsContent(
    themeMode: ThemeMode,
    readerFontFamily: ReaderFontFamily,
    readerFontScale: Float,
    speechEngine: SpeechEngine,
    speechRate: Float,
    pocketTtsVoice: String,
    pocketModels: com.geneing.epubreader.playback.PocketModelUiState,
    playbackGraceMinutes: Int,
    resumeOnBluetoothReconnect: Boolean,
    resumeAfterLongInterruption: Boolean,
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onFontFamilySelected: (ReaderFontFamily) -> Unit,
    onFontScaleChanged: (Float) -> Unit,
    onSpeechEngineSelected: (SpeechEngine) -> Unit,
    onSpeechRateChanged: (Float) -> Unit,
    onPocketTtsVoiceSelected: (String) -> Unit,
    onInstallPocketModels: () -> Unit,
    onCancelPocketModelInstall: () -> Unit,
    onRefreshPocketModels: () -> Unit,
    onPlaybackGraceMinutesChanged: (Int) -> Unit,
    onResumeOnBluetoothReconnectChanged: (Boolean) -> Unit,
    onResumeAfterLongInterruptionChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = maxWidth.coerceAtMost(720.dp))
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ScreenHeader(
                title = "Settings",
                description = "Personalize the reading experience and narration.",
            )
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Use your device theme or choose a fixed appearance.",
                        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ThemeMode.entries.forEach { mode ->
                        PreferenceRadioRow(
                            label = mode.label,
                            selected = themeMode == mode,
                            onClick = { onThemeModeSelected(mode) },
                        )
                    }
                }
            }
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Reading font", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Choose a typeface and comfortable text size for EPUBs.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ReaderFontFamily.entries.forEach { family ->
                        PreferenceRadioRow(
                            label = family.label,
                            selected = readerFontFamily == family,
                            onClick = { onFontFamilySelected(family) },
                        )
                    }
                    Text("Text size · ${"%.0f".format(readerFontScale * 100)}%", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = readerFontScale,
                        onValueChange = onFontScaleChanged,
                        valueRange = AppPreferences.MIN_FONT_SCALE..AppPreferences.MAX_FONT_SCALE,
                    )
                }
            }
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Speech engine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Choose a narration provider and adjust its default pace.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SpeechEngine.entries.forEach { engine ->
                        PreferenceRadioRow(
                            label = engine.label,
                            selected = speechEngine == engine,
                            enabled = engine == SpeechEngine.ANDROID_SYSTEM || pocketModels.installed,
                            onClick = { onSpeechEngineSelected(engine) },
                        )
                    }
                    Text("Speech rate · ${"%.1f".format(speechRate)}×", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = speechRate,
                        onValueChange = onSpeechRateChanged,
                        valueRange = AppPreferences.MIN_SPEECH_RATE..AppPreferences.MAX_SPEECH_RATE,
                        steps = 14,
                    )
                    TextButton(
                        onClick = {
                            runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                        },
                    ) { Text("Manage voices and engines") }
                }
            }
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pocket TTS models", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (pocketModels.installed) {
                            "Pocket TTS ${dev.pockettts.PocketTtsModels.DEFAULT_VERSION} is ready · ${pocketModels.requiredFiles} required files are installed."
                        } else {
                            "Models are stored in EpubReader's app-specific files, not in the APK. ${pocketModels.installedFiles}/${pocketModels.requiredFiles} required files are present."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (pocketModels.isInstalling) {
                        LinearProgressIndicator(
                            progress = { pocketModels.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = onCancelPocketModelInstall) { Text("Cancel download") }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = onRefreshPocketModels) { Text("Check files") }
                            TextButton(onClick = onInstallPocketModels) {
                                Text(if (pocketModels.installed) "Verify / repair" else "Download models")
                            }
                        }
                    }
                    pocketModels.message?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (speechEngine == SpeechEngine.POCKET && pocketModels.installed) {
                        val voices = pocketModels.voices
                        if (voices.isEmpty()) {
                            Text(
                                "No Pocket voices were found in the model's voices folder.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            PreferenceDropdown(
                                label = "Voice",
                                selected = pocketTtsVoice.takeIf { it in voices } ?: voices.first(),
                                options = voices,
                                onSelected = onPocketTtsVoiceSelected,
                            )
                        }
                    }
                }
            }
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Playback and Bluetooth", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Playback controls stay available briefly after pausing. Bluetooth and audio-focus behavior follows these preferences.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (playbackGraceMinutes == 0) {
                            "Mini-player and notification grace · Off"
                        } else {
                            "Mini-player and notification grace · $playbackGraceMinutes min"
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Slider(
                        value = playbackGraceMinutes.toFloat(),
                        onValueChange = { onPlaybackGraceMinutesChanged(it.toInt()) },
                        valueRange = AppPreferences.MIN_PLAYBACK_GRACE_MINUTES.toFloat()..AppPreferences.MAX_PLAYBACK_GRACE_MINUTES.toFloat(),
                        steps = AppPreferences.MAX_PLAYBACK_GRACE_MINUTES - 1,
                    )
                    PreferenceSwitchRow(
                        title = "Resume when Bluetooth reconnects",
                        description = "Off by default. Resume only if playback was paused when a headset or car audio disconnected.",
                        checked = resumeOnBluetoothReconnect,
                        onCheckedChange = onResumeOnBluetoothReconnectChanged,
                    )
                    PreferenceSwitchRow(
                        title = "Resume after long interruptions",
                        description = "Short interruptions resume automatically. When enabled, also resume after a long audio-focus interruption such as a phone call.",
                        checked = resumeAfterLongInterruption,
                        onCheckedChange = onResumeAfterLongInterruptionChanged,
                    )
                }
            }
        }
    }
}

@Composable
private fun PreferenceSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PreferenceDropdown(
    label: String,
    selected: String,
    options: List<String>,
    enabled: Boolean = true,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    selected.replaceFirstChar(Char::uppercase),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.replaceFirstChar(Char::uppercase)) },
                        onClick = {
                            expanded = false
                            onSelected(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PreferenceRadioRow(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        TextButton(onClick = onClick, enabled = enabled) {
            Text(label, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun ScreenHeader(
    title: String,
    description: String,
    trailing: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        trailing?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun RouteEmptyCard(
    title: String,
    description: String,
    onAddFolder: (() -> Unit)?,
    onAddFile: (() -> Unit)?,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Outlined.Book,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(54.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).padding(13.dp),
            )
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            onAddFolder?.let { addFolder ->
                TextButton(onClick = addFolder) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Choose a book folder")
                }
            }
            onAddFile?.let { addFile -> TextButton(onClick = addFile) { Text("Add one book file") } }
        }
    }
}

@Composable
private fun SectionHeading(title: String, trailing: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
        )
        trailing?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FolderCard(
    folder: BookFolderEntity,
    bookCount: Int,
    scanning: Boolean,
    removing: Boolean,
    onOpen: () -> Unit,
    onScan: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        onClick = onOpen,
        enabled = !removing,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.FolderOpen,
                contentDescription = null,
                tint = Sage,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Sage.copy(alpha = 0.10f))
                    .padding(10.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(folder.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    folder.lastScanError ?: "$bookCount ${if (bookCount == 1) "book" else "books"} · ${folder.lastScannedAt?.let(::formatDate) ?: "Not scanned yet"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (folder.lastScanError == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (scanning) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onScan, enabled = !removing) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Scan ${folder.displayName}")
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, enabled = !removing) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Folder options")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Remove folder") },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRemove()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BookCard(
    book: BookEntity,
    removing: Boolean,
    onOpen: () -> Unit,
    onLoadMetadata: () -> Unit,
    onRemove: () -> Unit,
    onShare: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(book.uri, book.metadataLoaded, book.isAvailable) {
        if (book.isAvailable && !book.metadataLoaded) onLoadMetadata()
    }
    val format = BookFormat.from(book.displayName, book.mimeType)
    val coverBitmap by produceState<Bitmap?>(initialValue = null, book.coverPath) {
        value = book.coverPath?.let { path ->
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
        }
    }
    val title = book.publicationTitle?.takeIf(String::isNotBlank) ?: book.displayName
    val progress = (book.progressPercent / 100.0).toFloat().coerceIn(0f, 1f)
    Card(
        onClick = onOpen,
        enabled = book.isAvailable && !removing,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (book.isAvailable) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 14.dp, end = 8.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (coverBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = coverBitmap!!.asImageBitmap(),
                    contentDescription = "Cover of $title",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 68.dp, height = 92.dp).clip(RoundedCornerShape(12.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(width = 68.dp, height = 92.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Sage.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (format == BookFormat.PDF) Icons.Outlined.PictureAsPdf else Icons.Outlined.Book,
                        contentDescription = format?.label ?: "Book",
                        tint = Sage,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                book.author?.takeIf(String::isNotBlank)?.let { author ->
                    Text(author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(
                        onClick = {},
                        label = { Text(format?.label ?: "BOOK") },
                        modifier = Modifier.height(30.dp),
                    )
                    Text(
                        if (book.isAvailable) android.text.format.Formatter.formatShortFileSize(LocalContext.current, book.sizeBytes) else "File unavailable",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "Added ${formatDate(book.addedAt)}${book.lastOpenedAt?.let { " · opened ${formatDate(it)}" } ?: ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.weight(1f).height(5.dp).clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Text("${book.progressPercent.toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, enabled = !removing) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Book options")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Share") },
                        onClick = {
                            menuExpanded = false
                            onShare()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Remove from library") },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRemove()
                        },
                    )
                }
            }
        }
    }
}

private fun formatDate(timestamp: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(timestamp))
