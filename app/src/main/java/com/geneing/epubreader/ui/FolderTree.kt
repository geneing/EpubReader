package com.geneing.epubreader.ui

import com.geneing.epubreader.data.BookEntity

/** A subfolder shown while browsing a connected folder. */
internal data class FolderNode(
    val name: String,
    val path: String,
    val bookCount: Int,
)

/** The folders and books directly inside one directory of a connected folder. */
internal data class FolderDirectoryContents(
    val folders: List<FolderNode>,
    val books: List<BookEntity>,
)

/** Path relative to the connected folder's root, including the file name. */
internal fun bookPathWithinFolder(book: BookEntity): String =
    book.relativePath.ifBlank { book.displayName }

/** Parent directory of a book, relative to the connected folder's root. */
internal fun parentPathOf(path: String): String = path.substringBeforeLast('/', "")

/**
 * Splits the books of a connected folder into the subfolders and files visible in
 * [directory]. Folder counts include every book nested below that folder, and
 * ordering is alphabetical by name. Books whose stored path is missing fall back
 * to the root so they stay reachable.
 */
internal fun directoryContents(
    books: List<BookEntity>,
    directory: String,
): FolderDirectoryContents {
    val prefix = if (directory.isBlank()) "" else "$directory/"
    val nestedCounts = linkedMapOf<String, Int>()
    val directBooks = mutableListOf<BookEntity>()

    books.forEach { book ->
        val path = bookPathWithinFolder(book)
        if (directory.isNotBlank()) {
            if (!path.startsWith(prefix)) return@forEach
        }
        val remainder = path.removePrefix(prefix)
        val separator = remainder.indexOf('/')
        if (separator < 0) {
            directBooks += book
        } else {
            val name = remainder.substring(0, separator)
            val folderPath = if (directory.isBlank()) name else "$directory/$name"
            nestedCounts[folderPath] = (nestedCounts[folderPath] ?: 0) + 1
        }
    }

    val folders = nestedCounts
        .map { (path, count) -> FolderNode(name = path.substringAfterLast('/'), path = path, bookCount = count) }
        .sortedBy { it.name.lowercase() }
    return FolderDirectoryContents(
        folders = folders,
        books = directBooks.sortedBy { it.displayName.lowercase() },
    )
}

/** Path segments leading to [directory], used for the breadcrumb bar. */
internal fun breadcrumbSegments(directory: String): List<String> =
    directory.split('/').filter { it.isNotBlank() }
