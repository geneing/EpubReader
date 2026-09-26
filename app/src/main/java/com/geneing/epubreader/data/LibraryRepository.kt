package com.geneing.epubreader.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import java.io.File
import java.security.MessageDigest

data class ReadingStop(
    val locator: Locator,
    val createdAt: Long,
)

class LibraryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = LibraryDatabase.get(appContext)
    private val scanner = SafBookScanner(appContext)

    fun observeFolders(): Flow<List<BookFolderEntity>> = database.folders().observeAll()

    fun observeBooks(): Flow<List<BookEntity>> = database.books().observeAll()

    suspend fun addFolder(uri: Uri) {
        appContext.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        val folder = DocumentFile.fromTreeUri(appContext, uri)
            ?: error("Android couldn't open that folder. Try selecting it again.")
        check(folder.isDirectory && folder.canRead()) {
            "Select a folder that EpubReader can read."
        }

        database.folders().upsert(
            BookFolderEntity(
                treeUri = uri.toString(),
                displayName = folder.name ?: "Book folder",
                addedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun addDocument(uri: Uri) {
        appContext.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        val document = DocumentFile.fromSingleUri(appContext, uri)
            ?: error("Android couldn't open that file. Try selecting it again.")
        val displayName = document.name ?: uri.lastPathSegment ?: "Book"
        val mimeType = appContext.contentResolver.getType(uri)
            ?: BookFormat.from(displayName, null)?.mimeType
            ?: "application/octet-stream"
        val format = BookFormat.from(displayName, mimeType)
            ?: error("Choose an EPUB or PDF file.")
        val existing = database.books().find(uri.toString())

        database.books().upsert(
            BookEntity(
                uri = uri.toString(),
                folderUri = null,
                displayName = displayName,
                mimeType = format.mimeType,
                sizeBytes = document.length().coerceAtLeast(0L),
                lastModified = document.lastModified().coerceAtLeast(0L),
                lastOpenedAt = existing?.lastOpenedAt,
                addedAt = existing?.addedAt?.takeIf { it > 0L } ?: System.currentTimeMillis(),
                publicationTitle = existing?.publicationTitle,
                author = existing?.author,
                coverPath = existing?.coverPath,
                progressPercent = existing?.progressPercent ?: 0.0,
                metadataLoaded = existing?.metadataLoaded ?: false,
                lastLocatorJson = existing?.lastLocatorJson,
            ),
        )
    }

    suspend fun scanFolder(uriString: String) {
        val folder = database.folders().find(uriString)
            ?: error("This folder is no longer in your library.")
        val folderUri = Uri.parse(uriString)

        try {
            val foundBooks = scanner.scan(folderUri, uriString)
            val previousBooks = database.books().findInFolder(uriString).associateBy(BookEntity::uri)
            val refreshedBooks = foundBooks.map { foundBook ->
                val previousBook = previousBooks[foundBook.uri]
                foundBook.copy(
                    lastOpenedAt = previousBook?.lastOpenedAt,
                    addedAt = previousBook?.addedAt?.takeIf { it > 0L } ?: System.currentTimeMillis(),
                    publicationTitle = previousBook?.publicationTitle,
                    author = previousBook?.author,
                    coverPath = previousBook?.coverPath,
                    progressPercent = previousBook?.progressPercent ?: 0.0,
                    metadataLoaded = previousBook?.metadataLoaded ?: false,
                    lastLocatorJson = previousBook?.lastLocatorJson,
                )
            }
            database.withTransaction {
                database.books().markFolderBooksUnavailable(uriString)
                database.books().upsertAll(refreshedBooks)
                database.folders().upsert(
                    folder.copy(
                        lastScannedAt = System.currentTimeMillis(),
                        lastScanError = null,
                    ),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            database.folders().upsert(folder.copy(lastScanError = error.message))
            throw error
        }
    }

    suspend fun removeFolder(uriString: String) {
        database.withTransaction {
            database.readingStops().deleteForFolder(uriString)
            database.books().deleteForFolder(uriString)
            database.folders().delete(uriString)
        }
        releaseReadPermission(Uri.parse(uriString))
    }

    suspend fun removeBook(uriString: String) {
        val book = database.books().find(uriString)
        database.readingStops().deleteForBook(uriString)
        database.books().delete(uriString)
        if (book?.folderUri == null) releaseReadPermission(Uri.parse(uriString))
    }

    suspend fun markBookOpened(uriString: String) {
        database.books().markOpened(uriString, System.currentTimeMillis())
    }

    suspend fun updatePublicationInfo(
        uriString: String,
        title: String?,
        author: String?,
        coverBitmap: Bitmap?,
    ) {
        val previous = database.books().find(uriString)
        val coverPath = coverBitmap?.let { bitmap ->
            withContext(Dispatchers.IO) { saveCover(uriString, bitmap) }
        } ?: previous?.coverPath
        database.books().updatePublicationInfo(
            uri = uriString,
            title = title?.takeIf(String::isNotBlank),
            author = author?.takeIf(String::isNotBlank),
            coverPath = coverPath,
        )
    }

    suspend fun updateReadingProgress(uriString: String, progress: Double) {
        database.books().updateProgress(uriString, progress.coerceIn(0.0, 1.0) * 100.0)
    }

    suspend fun updateReadingPosition(uriString: String, locator: Locator) {
        val json = locator.toJSON().toString()
        val existingProgress = database.books().find(uriString)?.progressPercent?.div(100.0) ?: 0.0
        val progressPercent = (locator.locations.totalProgression ?: existingProgress)
            .coerceIn(0.0, 1.0) * 100.0
        database.books().updateReadingPosition(uriString, progressPercent, json)
    }

    suspend fun lastReadingPosition(uriString: String): Locator? =
        database.books().find(uriString)?.lastLocatorJson?.let(::parseLocator)

    suspend fun recentReadingStops(uriString: String): List<ReadingStop> =
        database.readingStops().recentForBook(uriString).mapNotNull { stop ->
            parseLocator(stop.locatorJson)?.let { ReadingStop(it, stop.createdAt) }
        }

    suspend fun recordReadingStop(uriString: String, locator: Locator) {
        val json = locator.toJSON().toString()
        updateReadingPosition(uriString, locator)
        val newest = database.readingStops().recentForBook(uriString).firstOrNull()
        if (newest?.locatorJson != json) {
            database.readingStops().insert(
                ReadingStopEntity(
                    bookUri = uriString,
                    locatorJson = json,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            database.readingStops().trimForBook(uriString)
        }
    }

    private fun parseLocator(json: String): Locator? =
        runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()

    suspend fun findBook(uriString: String): BookEntity? = database.books().find(uriString)

    private fun saveCover(uriString: String, bitmap: Bitmap): String? = runCatching {
        val coversDirectory = File(appContext.filesDir, "book-covers").apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(uriString.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        val coverFile = File(coversDirectory, "$digest.webp")
        coverFile.outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 82, output))
        }
        coverFile.absolutePath
    }.getOrNull()

    private fun releaseReadPermission(uri: Uri) {
        runCatching {
            appContext.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }
}
