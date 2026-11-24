package com.geneing.epubreader.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.geneing.epubreader.data.BookEntity
import com.geneing.epubreader.data.BookFolderEntity
import java.io.File
import java.util.Base64

/**
 * Browse tree shown by Android Auto and other MediaBrowser clients.
 *
 * The root mirrors the library's priorities: recently opened books first, then
 * the folders connected to EpubReader on the phone, then the whole library. The
 * phone app remains the place where folders are connected and scanned; the car
 * only browses what is already in the library.
 */
internal object AutoMediaLibrary {

    const val ROOT_ID = "root"
    const val RECENT_ID = "recent"
    const val FOLDERS_ID = "folders"
    const val ALL_BOOKS_ID = "all"

    private const val BOOK_PREFIX = "book:"
    private const val FOLDER_PREFIX = "folder:"

    data class FolderNodeRef(
        val treeUri: String,
        val path: String,
    )

    fun bookMediaId(uri: String): String = BOOK_PREFIX + uri

    fun bookUri(mediaId: String?): String? = mediaId
        ?.takeIf { it.startsWith(BOOK_PREFIX) }
        ?.removePrefix(BOOK_PREFIX)
        ?.takeIf(String::isNotBlank)

    fun folderNodeId(treeUri: String, path: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val tree = encoder.encodeToString(treeUri.toByteArray(Charsets.UTF_8))
        val relative = encoder.encodeToString(path.toByteArray(Charsets.UTF_8))
        return "$FOLDER_PREFIX$tree:$relative"
    }

    fun folderNode(mediaId: String?): FolderNodeRef? {
        val parts = mediaId?.split(':', limit = 3) ?: return null
        if (parts.size != 3 || parts[0] != FOLDER_PREFIX.trimEnd(':')) return null
        return runCatching {
            val decoder = Base64.getUrlDecoder()
            FolderNodeRef(
                treeUri = String(decoder.decode(parts[1]), Charsets.UTF_8),
                path = String(decoder.decode(parts[2]), Charsets.UTF_8),
            )
        }.getOrNull()
    }

    fun rootItem(): MediaItem = folderItem(
        mediaId = ROOT_ID,
        title = "EpubReader",
        subtitle = "Your audiobooks and connected folders",
    )

    /**
     * Children of a browse node. Only available books are listed; a folder that
     * disappeared from the phone is skipped until the library scans it again.
     */
    fun children(
        parentId: String,
        folders: List<BookFolderEntity>,
        books: List<BookEntity>,
        page: Int = 0,
        pageSize: Int = PAGE_SIZE,
    ): List<MediaItem> {
        val available = books.filter { it.isAvailable }
        val items = when {
            parentId == ROOT_ID -> listOf(
                folderItem(
                    mediaId = RECENT_ID,
                    title = "Recent files",
                    subtitle = recentSubtitle(available),
                ),
                folderItem(
                    mediaId = FOLDERS_ID,
                    title = "Folders",
                    subtitle = "Browse the folders connected to EpubReader",
                ),
                folderItem(
                    mediaId = ALL_BOOKS_ID,
                    title = "All books",
                    subtitle = countLabel(available.size),
                ),
            )

            parentId == RECENT_ID -> available
                .filter { it.lastOpenedAt != null }
                .sortedByDescending { it.lastOpenedAt }
                .take(RECENT_LIMIT)
                .map(::bookItem)

            parentId == FOLDERS_ID -> if (folders.isEmpty()) {
                listOf(
                    MediaItem.Builder()
                        .setMediaId("guidance:folders")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("No folders connected")
                                .setSubtitle("Connect a book folder in EpubReader on your phone")
                                .setIsBrowsable(false)
                                .setIsPlayable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
                                .build(),
                        )
                        .build(),
                )
            } else {
                folders
                    .sortedBy { it.displayName.lowercase() }
                    .map { folder ->
                        val count = available.count { it.folderUri == folder.treeUri }
                        folderItem(
                            mediaId = folderNodeId(folder.treeUri, ""),
                            title = folder.displayName,
                            subtitle = countLabel(count),
                            artist = "Connected folder",
                        )
                    }
            }

            parentId == ALL_BOOKS_ID -> available
                .sortedWith(
                    compareBy<BookEntity> { (it.publicationTitle ?: it.displayName).lowercase() }
                        .thenBy { it.author.orEmpty().lowercase() },
                )
                .map(::bookItem)

            else -> folderChildren(parentId, folders, available)
        }
        return items.paginate(page, pageSize)
    }

    fun item(
        mediaId: String,
        folders: List<BookFolderEntity>,
        books: List<BookEntity>,
    ): MediaItem? {
        val available = books.filter { it.isAvailable }
        return when (mediaId) {
            ROOT_ID -> rootItem()
            RECENT_ID -> folderItem(
                mediaId = RECENT_ID,
                title = "Recent files",
                subtitle = recentSubtitle(available),
            )
            FOLDERS_ID -> folderItem(
                mediaId = FOLDERS_ID,
                title = "Folders",
                subtitle = "Browse the folders connected to EpubReader",
            )
            ALL_BOOKS_ID -> folderItem(
                mediaId = ALL_BOOKS_ID,
                title = "All books",
                subtitle = countLabel(available.size),
            )
            else -> {
                bookUri(mediaId)?.let { uri ->
                    available.firstOrNull { it.uri == uri }?.let(::bookItem)
                } ?: folderNode(mediaId)?.let { node ->
                    folderItemForNode(node, folders, available)
                }
            }
        }
    }

    fun search(
        query: String,
        folders: List<BookFolderEntity>,
        books: List<BookEntity>,
        page: Int = 0,
        pageSize: Int = PAGE_SIZE,
    ): List<MediaItem> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        if (terms.isEmpty()) return emptyList()
        val matches = books
            .filter { it.isAvailable }
            .filter { book ->
                val haystack = buildString {
                    append(book.displayName)
                    append(' ')
                    append(book.publicationTitle.orEmpty())
                    append(' ')
                    append(book.author.orEmpty())
                }.lowercase()
                terms.all(haystack::contains)
            }
            .sortedWith(compareByDescending<BookEntity> { it.lastOpenedAt }.thenBy { it.displayName.lowercase() })
            .map(::bookItem)
        return matches.paginate(page, pageSize)
    }

    private fun folderChildren(
        parentId: String,
        folders: List<BookFolderEntity>,
        books: List<BookEntity>,
    ): List<MediaItem> {
        val node = folderNode(parentId) ?: return emptyList()
        val folder = folders.firstOrNull { it.treeUri == node.treeUri } ?: return emptyList()
        val inFolder = books.filter { it.folderUri == node.treeUri }
        val path = node.path
        val directories = mutableListOf<String>()
        val directBooks = mutableListOf<BookEntity>()
        inFolder.forEach { book ->
            val relative = book.relativePath.replace('\\', '/').trim('/')
            val parent = relative.substringBeforeLast('/', missingDelimiterValue = "")
            if (parent == path) {
                directBooks.add(book)
            } else if (path.isEmpty() || parent == path || parent.startsWith("$path/")) {
                val remainder = relative.removePrefix(if (path.isEmpty()) "" else "$path/")
                val child = remainder.substringBefore('/').takeIf(String::isNotBlank)
                if (child != null && child !in directories) directories.add(child)
            }
        }
        val folderItems = directories.sortedBy { it.lowercase() }.map { child ->
            val childPath = if (path.isEmpty()) child else "$path/$child"
            val count = inFolder.count { it.relativePath.replace('\\', '/').trim('/').let { rel ->
                rel == childPath || rel.startsWith("$childPath/")
            } }
            folderItem(
                mediaId = folderNodeId(node.treeUri, childPath),
                title = child,
                subtitle = countLabel(count),
                artist = folder.displayName,
            )
        }
        val bookItems = directBooks
            .sortedBy { it.displayName.lowercase() }
            .map(::bookItem)
        return folderItems + bookItems
    }

    private fun folderItemForNode(
        node: FolderNodeRef,
        folders: List<BookFolderEntity>,
        books: List<BookEntity>,
    ): MediaItem? {
        val folder = folders.firstOrNull { it.treeUri == node.treeUri } ?: return null
        if (node.path.isEmpty()) {
            return folderItem(
                mediaId = folderNodeId(node.treeUri, ""),
                title = folder.displayName,
                subtitle = countLabel(books.count { it.folderUri == node.treeUri }),
                artist = "Connected folder",
            )
        }
        return folderItem(
            mediaId = folderNodeId(node.treeUri, node.path),
            title = node.path.substringAfterLast('/'),
            subtitle = "Folder in ${folder.displayName}",
        )
    }

    private fun bookItem(book: BookEntity): MediaItem {
        val title = book.publicationTitle?.takeIf(String::isNotBlank) ?: book.displayName
        val author = book.author?.takeIf(String::isNotBlank)
            ?: if (book.mimeType.contains("pdf", ignoreCase = true)) "PDF" else "EPUB"
        val progress = if (book.progressPercent > 0.0) {
            "${book.progressPercent.toInt()}% read"
        } else {
            "Not started"
        }
        val builder = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(author)
            .setSubtitle(progress)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            .setIsBrowsable(false)
            .setIsPlayable(true)
        book.relativePath
            .substringBeforeLast('/', missingDelimiterValue = "")
            .takeIf(String::isNotBlank)
            ?.let(builder::setDescription)
        book.coverPath?.let { path ->
            if (File(path).isFile) builder.setArtworkUri(Uri.fromFile(File(path)))
        }
        return MediaItem.Builder()
            .setMediaId(bookMediaId(book.uri))
            .setUri(Uri.parse(book.uri))
            .setMediaMetadata(builder.build())
            .build()
    }

    private fun folderItem(
        mediaId: String,
        title: String,
        subtitle: String,
        artist: String? = null,
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
            .setIsBrowsable(true)
            .setIsPlayable(false)
        artist?.let(metadata::setArtist)
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata.build())
            .build()
    }

    private fun recentSubtitle(books: List<BookEntity>): String {
        val count = books.count { it.lastOpenedAt != null }
        return if (count == 0) "Open a book on your phone to start listening" else countLabel(count)
    }

    private fun countLabel(count: Int): String =
        "$count ${if (count == 1) "book" else "books"}"

    private fun List<MediaItem>.paginate(page: Int, pageSize: Int): List<MediaItem> {
        if (pageSize <= 0) return this
        val start = (page.coerceAtLeast(0) * pageSize).coerceAtMost(size)
        return subList(start, (start + pageSize).coerceAtMost(size))
    }

    private const val RECENT_LIMIT = 30
    private const val PAGE_SIZE = 40
}
