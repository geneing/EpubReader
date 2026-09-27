package com.geneing.epubreader.playback.normalization

import org.junit.Assert.assertEquals
import org.junit.Test
import org.readium.r2.shared.util.Language

class TextNormalizerTest {

    private val normalizer = TextNormalizer.English

    @Test
    fun combinesAbbreviationAndNumberRules() {
        assertEquals(
            "Mister Smith paid one thousand two hundred thirty-four dollars in nineteen ninety-nine.",
            normalizer.normalize("Mr. Smith paid 1,234 dollars in 1999."),
        )
    }

    @Test
    fun sentenceWithManyShapesIsNormalized() {
        assertEquals(
            "On January thirteenth, two thousand seven, Mister Smith paid " +
                "one thousand two hundred thirty-four dollars and fifty cents " +
                "for three point five kilograms of coffee.",
            normalizer.normalize(
                "On Jan. 13, 2007, Mr. Smith paid $1,234.50 for 3.5 kg of coffee.",
            ),
        )
    }

    @Test
    fun normalizationIsIdempotent() {
        val once = normalizer.normalize("Mr. Smith paid $1,234.56 on 02/03/2007 at 9:30 a.m.")
        assertEquals(once, normalizer.normalize(once))
    }

    @Test
    fun emptyTextStaysEmpty() {
        assertEquals("", normalizer.normalize(""))
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
