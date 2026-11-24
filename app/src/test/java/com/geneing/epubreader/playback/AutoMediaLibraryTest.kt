package com.geneing.epubreader.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoMediaLibraryTest {

    @Test
    fun bookMediaIdRoundTripsContentUri() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ABooks/document/primary%3ABooks%2FNovel.epub"
        assertEquals(uri, AutoMediaLibrary.bookUri(AutoMediaLibrary.bookMediaId(uri)))
    }

    @Test
    fun bookUriRejectsBrowseNodes() {
        assertNull(AutoMediaLibrary.bookUri(AutoMediaLibrary.RECENT_ID))
        assertNull(AutoMediaLibrary.bookUri(null))
        assertNull(AutoMediaLibrary.bookUri("book:"))
    }

    @Test
    fun folderNodeRoundTripsTreeAndPath() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3ABooks"
        val path = "Science Fiction/Asimov"
        val id = AutoMediaLibrary.folderNodeId(tree, path)

        val node = AutoMediaLibrary.folderNode(id)

        assertEquals(tree, node?.treeUri)
        assertEquals(path, node?.path)
    }

    @Test
    fun folderNodeHandlesEmptyRootPath() {
        val tree = "content://provider/tree/root"
        val node = AutoMediaLibrary.folderNode(AutoMediaLibrary.folderNodeId(tree, ""))

        assertEquals(tree, node?.treeUri)
        assertEquals("", node?.path)
    }

    @Test
    fun folderNodeRejectsOtherMediaIds() {
        assertNull(AutoMediaLibrary.folderNode(AutoMediaLibrary.ROOT_ID))
        assertNull(AutoMediaLibrary.folderNode("book:content://example/book.epub"))
        assertNull(AutoMediaLibrary.folderNode(null))
    }
}
