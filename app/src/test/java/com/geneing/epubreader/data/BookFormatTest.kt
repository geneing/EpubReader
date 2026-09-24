package com.geneing.epubreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookFormatTest {
    @Test
    fun recognizesSupportedExtensionsWithoutCaseSensitivity() {
        assertEquals(BookFormat.EPUB, BookFormat.from("A Novel.EPUB", null))
        assertEquals(BookFormat.PDF, BookFormat.from("paper.PdF", null))
    }

    @Test
    fun mimeTypeRecognizesBooksWithoutKnownExtensions() {
        assertEquals(BookFormat.EPUB, BookFormat.from("download", "application/epub+zip"))
        assertEquals(BookFormat.PDF, BookFormat.from("document", "application/pdf"))
    }

    @Test
    fun rejectsUnsupportedFiles() {
        assertNull(BookFormat.from("cover.jpg", "image/jpeg"))
        assertNull(BookFormat.from(null, null))
    }
}
