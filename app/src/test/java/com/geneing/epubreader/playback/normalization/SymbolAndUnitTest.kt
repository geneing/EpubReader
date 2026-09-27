package com.geneing.epubreader.playback.normalization

import org.junit.Assert.assertEquals
import org.junit.Test

class SymbolAndUnitTest {

    private val symbols = SymbolNormalizer()
    private val units = UnitAndSymbolNormalizer()
    private val fractions = FractionNormalizer()
    private val hygiene = HygieneNormalizer()

    @Test
    fun symbolsBecomeWords() {
        assertEquals("salt and pepper", symbols.apply("salt & pepper"))
        assertEquals("copyright 2007", symbols.apply("© 2007"))
        assertEquals("two plus two equals four", symbols.apply("2 + 2 = 4"))
    }

    @Test
    fun abbreviationPeriodFallsInLowercaseChainsOnly() {
        assertEquals("eg this and ie that", symbols.apply("e.g this and i.e that"))
        assertEquals("am and pm", symbols.apply("a.m and p.m"))
        // A sentence-ending period after a capitalised word must survive.
        assertEquals("He saw Dr. X. Then he left.", symbols.apply("He saw Dr. X. Then he left."))
    }

    @Test
    fun unitsExpandAfterANumber() {
        assertEquals("5 kilometers", units.apply("5 km").trim())
        assertEquals("6 feet", units.apply("6 ft.").trim())
        assertEquals("70 miles per hour", units.apply("70 mph").trim())
        assertEquals("20 degrees Celsius", units.apply("20 °C").trim())
        assertEquals("10 kilograms", units.apply("10 kg").trim())
        assertEquals("25 degrees", units.apply("25°").trim())
        assertEquals("two times three", units.apply("2 x 3"))
    }

    @Test
    fun fractions() {
        assertEquals("three quarters", fractions.apply("3/4"))
        assertEquals("one half", fractions.apply("1/2"))
        assertEquals("three tenths", fractions.apply("3/10"))
        assertEquals("two twenty-sevenths", fractions.apply("2/27"))
    }

    @Test
    fun hygieneNormalizesWhitespaceAndPunctuation() {
        assertEquals("a b c", hygiene.apply("a\u00A0b\u2009c"))
        assertEquals("What?", hygiene.apply("What?!?"))
        assertEquals("etc...", hygiene.apply("etc\u2026"))
    }

    @Test
    fun hygieneRepairsLineBreakHyphenation() {
        assertEquals("example", hygiene.apply("exam-\nple"))
    }

    @Test
    fun hygieneDecodesEntitiesAndSmartQuotes() {
        assertEquals("\"It's here\"", hygiene.apply("\u201CIt\u2019s here\u201D"))
        assertEquals("Mark - Twain", hygiene.apply("Mark\u2014Twain"))
        assertEquals("Tom & Jerry", hygiene.apply("Tom &amp; Jerry"))
    }
}
