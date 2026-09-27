package com.geneing.epubreader.playback.normalization

import org.junit.Assert.assertEquals
import org.junit.Test
import org.readium.r2.shared.util.Language

class TextNormalizerTest {

    @Test
    fun combinesAbbreviationAndNumberRules() {
        assertEquals(
            "Mister Smith paid one thousand two hundred thirty-four dollars in nineteen ninety-nine.",
            TextNormalizer.English.normalize(
                "Mr. Smith paid 1,234 dollars in 1999.",
            ),
        )
    }

    @Test
    fun emptyTextStaysEmpty() {
        assertEquals("", TextNormalizer.English.normalize(""))
    }

    @Test
    fun unknownLanguageFallsBackToEnglish() {
        assertEquals(
            "one thousand two hundred thirty-four",
            TextNormalizer.forLanguage(null).normalize("1,234"),
        )
        assertEquals(
            "one thousand two hundred thirty-four",
            TextNormalizer.forLanguage(Language("en-GB")).normalize("1,234"),
        )
    }

    @Test
    fun nonEnglishIsPassedThroughUnchanged() {
        assertEquals(
            "M. Dupont a payé 1 234 euros.",
            TextNormalizer.forLanguage(Language("fr")).normalize(
                "M. Dupont a payé 1 234 euros.",
            ),
        )
    }
}
