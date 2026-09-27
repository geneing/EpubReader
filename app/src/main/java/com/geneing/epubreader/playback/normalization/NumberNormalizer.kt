package com.geneing.epubreader.playback.normalization

/**
 * Converts bare Arabic numerals to spoken words.
 *
 * Handles comma-grouped thousands (`1,234,567`), decimal points (`3.14`) and
 * four-digit years (`1999`, `2005`). Numbers attached to letters on both sides
 * (`3D`, `1st`) are left alone, and ranges such as `3-5` become `three-five`
 * rather than a spurious negative number. Structured numeric forms (currency,
 * percentages, ordinals, dates, phones, units, ranges, versions) are consumed
 * by earlier rules in the pipeline; this is the catch-all for what remains.
 */
internal class NumberNormalizer : TextNormalizationRule {

    private val token = Regex(
        "(?<![\\p{L}\\p{N}])" +
            "(?:" +
            "-?\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?" + "|" + // 1,234 / 1,234.5
            "-?\\d+\\.\\d+" + "|" +                        // 3.14
            "-?\\d+" +                                     // 42
            ")" +
            "(?![\\p{L}\\p{N}])",
    )

    override fun apply(text: String): String =
        token.replace(text) { match -> NumberSpeller.spellToken(match.value) }
}
