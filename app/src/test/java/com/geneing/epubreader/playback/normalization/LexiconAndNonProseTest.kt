package com.geneing.epubreader.playback.normalization

import org.junit.Assert.assertEquals
import org.junit.Test

class LexiconAndNonProseTest {

    private val lexicon = LexiconNormalizer()
    private val withUserLexicon = LexiconNormalizer(mapOf("Readium" to "Ready-um", "CFI" to "C F I"))
    private val nonProse = NonProseNormalizer()

    @Test
    fun knownAcronymsArePronouncedAsWords() {
        assertEquals("Nato and Nasa met", lexicon.apply("NATO and NASA met"))
        assertEquals("Covid and radar", lexicon.apply("COVID and RADAR"))
    }

    @Test
    fun vowelLessCapsAreSpelledOut() {
        assertEquals("X Y Z", lexicon.apply("XYZ"))
        assertEquals("the T V A", lexicon.apply("the TVA"))
    }

    @Test
    fun mixedCaseAndOrdinaryWordsAreLeftAlone() {
        assertEquals("Nato is fine", lexicon.apply("Nato is fine"))
        assertEquals("USB and USA", lexicon.apply("USB and USA"))
        // Longer than the spell-out limit: leave it to the engine.
        assertEquals("ABCDEFGH", lexicon.apply("ABCDEFGH"))
    }

    @Test
    fun userLexiconSubstitutionsWin() {
        assertEquals("Ready-um books", withUserLexicon.apply("Readium books"))
        assertEquals("a C F I locator", withUserLexicon.apply("a CFI locator"))
    }

    @Test
    fun urlsAndEmailsAreSpelledAsSequences() {
        assertEquals(
            "e x a m p l e dot c o m slash p a t h",
            nonProse.apply("example.com/path"),
        )
        assertEquals(
            "Call u s e r at e x a m p l e dot c o m",
            nonProse.apply("Call user@example.com"),
        )
        assertEquals(
            "h t t p s colon slash slash a dot b",
            nonProse.apply("https://a.b"),
        )
    }

    @Test
    fun inlineCodeIsSpelledAndProseIsPreserved() {
        assertEquals(
            "Use the r u n function here.",
            nonProse.apply("Use the `run` function here."),
        )
    }

    @Test
    fun urlsAreSpelledByThePipelineToo() {
        assertEquals(
            "Visit e x a m p l e dot c o m.",
            TextNormalizer.English.normalize("Visit example.com."),
        )
    }
}
