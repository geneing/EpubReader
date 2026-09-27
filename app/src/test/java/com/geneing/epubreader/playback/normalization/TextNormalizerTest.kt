package com.geneing.epubreader.playback.normalization

import org.junit.Assert.assertEquals
import org.junit.Test
import org.readium.r2.shared.util.Language

class TextNormalizerTest {

    private val normalizer = TextNormalizer.English

    @Test
    fun combinesAbbreviationAndNumberRules() {
        assertEquals(
            "Mister Smith paid one thousand two hundred thirty-four in nineteen ninety-nine.",
            normalizer.normalize("Mr. Smith paid 1,234 in 1999."),
        )
    }

    @Test
    fun sentenceWithManyShapesIsNormalized() {
        assertEquals(
            "On January thirteenth two thousand seven, Mister Smith paid " +
                "one thousand two hundred thirty-four dollars and fifty cents " +
                "for three point five kilograms of coffee.",
            normalizer.normalize(
                "On Jan. 13, 2007, Mr. Smith paid $1,234.50 for 3.5 kg of coffee.",
            ),
        )
    }

    @Test
    fun rangesPercentagesAndTimes() {
        assertEquals(
            "nineteen ninety to two thousand and one thousand five hundred percent " +
                "and nine thirty in the morning",
            normalizer.normalize("1990-2000 and 1500% and 9:30 a.m."),
        )
    }

    @Test
    fun urlsAndFractions() {
        assertEquals(
            "three quarters of one half",
            normalizer.normalize("3/4 of 1/2"),
        )
        assertEquals(
            "e x a m p l e dot c o m slash p a t h and u s e r at e x a m p l e dot c o m",
            normalizer.normalize("example.com/path and user@example.com"),
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
