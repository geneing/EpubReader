package com.geneing.epubreader.ui

import com.geneing.epubreader.data.BookEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderTreeTest {
    private fun bookAt(path: String): BookEntity = BookEntity(
        uri = "content://folder/$path",
        folderUri = "content://folder",
        displayName = path.substringAfterLast('/'),
        mimeType = "application/epub+zip",
        relativePath = path,
        sizeBytes = 1L,
        lastModified = 0L,
    )

    private val books = listOf(
        bookAt("Root.epub"),
        bookAt("Series A/Book 1.epub"),
        bookAt("Series A/Book 2.epub"),
        bookAt("Series A/Extras/Notes.pdf"),
        bookAt("Series B/Only.epub"),
    )

    @Test
    fun `root shows subfolders and root files`() {
        val contents = directoryContents(books, "")

        assertEquals(listOf("Series A", "Series B"), contents.folders.map(FolderNode::name))
        assertEquals(listOf("Root.epub"), contents.books.map(BookEntity::displayName))
    }

    @Test
    fun `folder counts include nested books`() {
        val contents = directoryContents(books, "")

        assertEquals(3, contents.folders.first { it.name == "Series A" }.bookCount)
        assertEquals(1, contents.folders.first { it.name == "Series B" }.bookCount)
    }

    @Test
    fun `entering a folder lists its files and child folders`() {
        val contents = directoryContents(books, "Series A")

        assertEquals(listOf("Extras"), contents.folders.map(FolderNode::name))
        assertEquals(
            listOf("Book 1.epub", "Book 2.epub"),
            contents.books.map(BookEntity::displayName),
        )
    }

    @Test
    fun `nested folder path is relative to the connected folder`() {
        val contents = directoryContents(books, "Series A/Extras")

        assertEquals(emptyList<FolderNode>(), contents.folders)
        assertEquals(listOf("Notes.pdf"), contents.books.map(BookEntity::displayName))
    }

    @Test
    fun `blank relative path falls back to the root`() {
        val legacy = bookAt("Legacy.epub").copy(relativePath = "")
        val contents = directoryContents(listOf(legacy), "")

        assertTrue(contents.folders.isEmpty())
        assertEquals(listOf("Legacy.epub"), contents.books.map(BookEntity::displayName))
    }

    @Test
    fun `breadcrumbs split nested paths`() {
        assertEquals(listOf("Series A", "Extras"), breadcrumbSegments("Series A/Extras"))
        assertEquals(emptyList<String>(), breadcrumbSegments(""))
    }

    @Test
    fun `parent path strips the last segment`() {
        assertEquals("Series A", parentPathOf("Series A/Extras"))
        assertEquals("", parentPathOf("Series A"))
    }
}
