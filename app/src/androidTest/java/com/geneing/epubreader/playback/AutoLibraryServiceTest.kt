package com.geneing.epubreader.playback

import android.app.Instrumentation
import android.content.ComponentName
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.geneing.epubreader.data.BookEntity
import com.geneing.epubreader.data.BookFolderEntity
import com.geneing.epubreader.data.LibraryDatabase
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Binds a MediaBrowser to the playback service the same way Android Auto does
 * and verifies the library tree it exposes. It does not need the Android Auto
 * app or a car head unit.
 */
@RunWith(AndroidJUnit4::class)
class AutoLibraryServiceTest {

    @Test
    fun browserBrowsesRootCategories() = withBrowser { instrumentation, browser ->
        val root = rootId(instrumentation, browser)
        val ids = children(instrumentation, browser, root).map { it.mediaId }
        assertTrue("Missing Recent files: $ids", AutoMediaLibrary.RECENT_ID in ids)
        assertTrue("Missing Folders: $ids", AutoMediaLibrary.FOLDERS_ID in ids)
        assertTrue("Missing All books: $ids", AutoMediaLibrary.ALL_BOOKS_ID in ids)

        // The categories themselves must be browsable even on an empty library.
        children(instrumentation, browser, AutoMediaLibrary.RECENT_ID)
        children(instrumentation, browser, AutoMediaLibrary.FOLDERS_ID)
        children(instrumentation, browser, AutoMediaLibrary.ALL_BOOKS_ID)
    }

    @Test
    fun folderTreeExposesSeededBooksAndRecent() = withBrowser { instrumentation, browser ->
        val context = instrumentation.targetContext
        val database = LibraryDatabase.get(context)
        val treeUri = "content://com.geneing.epubreader.test/tree/shelf"
        val bookUri = "content://com.geneing.epubreader.test/document/novel.epub"
        val folder = BookFolderEntity(
            treeUri = treeUri,
            displayName = "Test shelf",
            addedAt = 1L,
            lastScannedAt = 1L,
        )
        val book = BookEntity(
            uri = bookUri,
            folderUri = treeUri,
            displayName = "novel.epub",
            mimeType = "application/epub+zip",
            relativePath = "Science Fiction/novel.epub",
            sizeBytes = 1L,
            lastModified = 1L,
            lastOpenedAt = 10L,
            addedAt = 1L,
            publicationTitle = "A Test Novel",
            author = "Ada Lovelace",
            progressPercent = 42.0,
        )
        try {
            runBlocking {
                database.folders().upsert(folder)
                database.books().upsert(book)
            }

            val folderRoot = children(instrumentation, browser, AutoMediaLibrary.FOLDERS_ID)
                .single { AutoMediaLibrary.folderNode(it.mediaId)?.treeUri == treeUri }
            assertEquals("Test shelf", folderRoot.mediaMetadata.title)

            // The folder root shows the subfolder, not the nested book.
            val shelf = children(instrumentation, browser, folderRoot.mediaId)
            val subfolder = shelf.single { it.mediaMetadata.title == "Science Fiction" }
            assertTrue(shelf.none { it.mediaId == AutoMediaLibrary.bookMediaId(bookUri) })

            val subfolderBooks = children(instrumentation, browser, subfolder.mediaId)
            val audioBook = subfolderBooks.single { it.mediaId == AutoMediaLibrary.bookMediaId(bookUri) }
            assertEquals("A Test Novel", audioBook.mediaMetadata.title)
            assertEquals("Ada Lovelace", audioBook.mediaMetadata.artist)
            assertEquals(true, audioBook.mediaMetadata.isPlayable)
            assertEquals(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK, audioBook.mediaMetadata.mediaType)
            assertEquals("42% read", audioBook.mediaMetadata.subtitle)

            val recent = children(instrumentation, browser, AutoMediaLibrary.RECENT_ID)
            assertTrue(recent.any { it.mediaId == AutoMediaLibrary.bookMediaId(bookUri) })
        } finally {
            runBlocking {
                database.books().delete(bookUri)
                database.folders().delete(treeUri)
            }
        }
    }

    @Test
    fun playsLibraryBookAndTogglesReadAlong() = withBrowser { instrumentation, browser ->
        val context = instrumentation.targetContext
        val database = LibraryDatabase.get(context)
        val bookFile = File(context.filesDir, "auto-test-book.epub")
        writeTestEpub(bookFile)
        val bookUri = bookFile.toURI().toString()
        val book = BookEntity(
            uri = bookUri,
            folderUri = null,
            displayName = "auto-test-book.epub",
            mimeType = "application/epub+zip",
            sizeBytes = bookFile.length(),
            lastModified = bookFile.lastModified(),
            lastOpenedAt = 1L,
            addedAt = 1L,
            publicationTitle = "Auto Test Book",
            author = "Test Author",
            progressPercent = 0.0,
        )
        runBlocking { database.books().upsert(book) }
        try {
            val getItem = onMain(instrumentation) {
                browser.getItem(AutoMediaLibrary.bookMediaId(bookUri))
            }.get(20, TimeUnit.SECONDS)
            assertEquals("Book is not browsable", LibraryResult.RESULT_SUCCESS, getItem.resultCode)
            val item = requireNotNull(getItem.value)

            instrumentation.runOnMainSync {
                browser.setMediaItem(item)
                browser.play()
            }
            val started = waitUntil(30_000) {
                onMainValue(instrumentation) {
                    browser.isPlaying && browser.mediaMetadata.title?.isNotBlank() == true
                }
            }
            assertTrue(
                "Narration did not start: isPlaying=" +
                    "${onMainValue(instrumentation) { browser.isPlaying }} " +
                    "state=${onMainValue(instrumentation) { browser.playbackState }} " +
                    "title=${onMainValue(instrumentation) { browser.mediaMetadata.title }}",
                started,
            )
            assertEquals(
                "Auto Test Book",
                onMainValue(instrumentation) { browser.mediaMetadata.title?.toString() },
            )

            val toggle = SessionCommand(PlaybackServiceCommands.ACTION_TOGGLE_READ_ALONG, Bundle.EMPTY)
            onMain(instrumentation) { browser.sendCustomCommand(toggle, Bundle.EMPTY) }
                .get(20, TimeUnit.SECONDS)

            val readAlong = waitUntil(15_000) {
                onMainValue(instrumentation) {
                    val metadata = browser.mediaMetadata
                    metadata.title?.toString()?.startsWith("Auto Test Book") == false &&
                        metadata.subtitle?.toString()?.endsWith("% read") == true &&
                        metadata.artworkData != null
                }
            }
            assertTrue("Read-along did not update the session metadata", readAlong)
            assertNotNull(
                "Read-along artwork is missing",
                onMainValue(instrumentation) { browser.mediaMetadata.artworkData },
            )
        } finally {
            val stop = SessionCommand(PlaybackServiceCommands.ACTION_STOP, Bundle.EMPTY)
            runCatching {
                onMain(instrumentation) { browser.sendCustomCommand(stop, Bundle.EMPTY) }
                    .get(20, TimeUnit.SECONDS)
            }
            runBlocking { database.books().delete(bookUri) }
            bookFile.delete()
        }
    }

    private fun writeTestEpub(file: File) {
        val mimetype = "application/epub+zip".toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(mimetype) }
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mimetype.size.toLong()
                    compressedSize = mimetype.size.toLong()
                    this.crc = crc.value
                },
            )
            zip.write(mimetype)
            zip.closeEntry()
            zip.writeEntry(
                "META-INF/container.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent(),
            )
            zip.writeEntry(
                "OEBPS/content.opf",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="id">auto-test-book</dc:identifier>
                    <dc:title>Auto Test Book</dc:title>
                    <dc:creator>Test Author</dc:creator>
                    <dc:language>en</dc:language>
                  </metadata>
                  <manifest>
                    <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine>
                    <itemref idref="chapter"/>
                  </spine>
                </package>
                """.trimIndent(),
            )
            zip.writeEntry(
                "OEBPS/chapter.xhtml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>Chapter One</title></head>
                  <body>
                    <h1>Chapter One</h1>
                    <p>The quick brown fox jumps over the lazy dog while the evening settles over the quiet harbor.</p>
                    <p>This second paragraph gives the narration another sentence to read aloud during the test.</p>
                  </body>
                </html>
                """.trimIndent(),
            )
        }
    }

    private fun ZipOutputStream.writeEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun withBrowser(block: (Instrumentation, MediaBrowser) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val token = SessionToken(context, ComponentName(context, BookPlaybackService::class.java))
        val browser = onMain(instrumentation) {
            MediaBrowser.Builder(context, token).buildAsync()
        }.get(20, TimeUnit.SECONDS)
        try {
            block(instrumentation, browser)
        } finally {
            instrumentation.runOnMainSync { browser.release() }
        }
    }

    private fun rootId(instrumentation: Instrumentation, browser: MediaBrowser): String {
        val root = onMain(instrumentation) { browser.getLibraryRoot(null) }
            .get(20, TimeUnit.SECONDS)
        assertEquals(LibraryResult.RESULT_SUCCESS, root.resultCode)
        return requireNotNull(root.value).mediaId
    }

    private fun children(
        instrumentation: Instrumentation,
        browser: MediaBrowser,
        parentId: String,
    ): List<MediaItem> {
        val result = onMain(instrumentation) {
            browser.getChildren(parentId, 0, 40, null)
        }.get(20, TimeUnit.SECONDS)
        assertEquals("getChildren($parentId)", LibraryResult.RESULT_SUCCESS, result.resultCode)
        return result.value.orEmpty()
    }

    private fun <T> onMain(
        instrumentation: Instrumentation,
        block: () -> ListenableFuture<T>,
    ): ListenableFuture<T> {
        val future = AtomicReference<ListenableFuture<T>>()
        instrumentation.runOnMainSync { future.set(block()) }
        return requireNotNull(future.get())
    }

    private fun <T> onMainValue(
        instrumentation: Instrumentation,
        block: () -> T,
    ): T {
        val value = AtomicReference<T>()
        instrumentation.runOnMainSync { value.set(block()) }
        return value.get()
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(250)
        }
        return condition()
    }
}
