package com.geneing.epubreader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.geneing.epubreader.data.LibraryRepository
import com.geneing.epubreader.playback.PlaybackStateStore
import com.geneing.epubreader.reader.ReaderActivity
import com.geneing.epubreader.ui.EpubReaderApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val libraryViewModel: LibraryViewModel by viewModels()
    private val libraryRepository by lazy { LibraryRepository(this) }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Narration still works without it; only the media notification is hidden. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        setContent {
            EpubReaderApp(viewModel = libraryViewModel)
        }
        if (savedInstanceState == null) openLastReaderIfPlaying()
    }

    /**
     * Starting the app while a narration session is alive opens the last text
     * screen by default; the library stays available with Back.
     */
    private fun openLastReaderIfPlaying() {
        val playback = PlaybackStateStore.state.value
        val bookUri = playback.bookUri ?: return
        if (!playback.showMiniPlayer) return
        lifecycleScope.launch {
            val book = withContext(Dispatchers.IO) { libraryRepository.findBook(bookUri) } ?: return@launch
            startActivity(
                Intent(this@MainActivity, ReaderActivity::class.java).apply {
                    putExtra(ReaderActivity.EXTRA_BOOK_URI, book.uri)
                    putExtra(ReaderActivity.EXTRA_BOOK_NAME, book.displayName)
                    putExtra(ReaderActivity.EXTRA_BOOK_MIME, book.mimeType)
                    putExtra(ReaderActivity.EXTRA_PROGRESS_PERCENT, book.progressPercent)
                },
            )
        }
    }

    /**
     * Media3 posts the background-playback notification from its service, but on
     * Android 13+ that notification is dropped unless the app holds
     * POST_NOTIFICATIONS, so ask for it once on launch.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
