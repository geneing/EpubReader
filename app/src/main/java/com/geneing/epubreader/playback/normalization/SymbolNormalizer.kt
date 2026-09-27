package com.geneing.epubreader.playback.normalization

/**
 * Spells symbols and number-adjacent signs: `&`, `+`, `=`, `©`, a leading
 * phone `+`, and repeated abbreviation periods.
 *
 * `%`, `°` and `x`/`×` are handled where their meaning depends on a preceding
 * number ([PercentageNormalizer], [UnitAndSymbolNormalizer]). A period left
 * between single letters (`e.g` -> `eg`, `a.m` -> `am`) is only collapsed when
 * every letter around it is lower case, so the sentence-ending period of
 * "He saw Dr. X." stays put.
 */
internal class SymbolNormalizer : TextNormalizationRule {

    /** A lower-case abbreviation chain left without its first period. */
    private val abbreviationPeriod = Regex("(?<=[a-z])\\.(?=[a-zA-Z])")

    override fun apply(text: String): String {
        var result = text
        result = result.replace(abbreviationPeriod, "")
        for ((symbol, words) in SYMBOLS) {
            result = result.replace(symbol, words)
        }
        return result
    }

    private companion object {
        /** Applied in this order because some values contain other symbols. */
        private val SYMBOLS = listOf(
            "©" to "copyright",
            "®" to "registered trademark",
            "™" to "trademark",
            "&" to " and ",
            "=" to " equals ",
            "+" to " plus ",
            "±" to " plus or minus ",
            "÷" to " divided by ",
        )
    }
}
