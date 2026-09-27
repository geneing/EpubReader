package com.geneing.epubreader.playback.normalization

/**
 * Converts Arabic numerals to spoken words.
 *
 * Handles comma-grouped thousands (`1,234,567`), decimal points (`3.14`) and
 * four-digit years (`1999`, `2005`). Numbers attached to letters on both sides
 * (`3D`, `1st`) are left alone, and ranges such as `3-5` become `three-five`
 * rather than a spurious negative number.
 *
 * Year detection is a heuristic: a bare four-digit integer in 1000..2099 is
 * read as a year regardless of context. Currency, percentages, ordinals,
 * ranges and units are planned rules (see `docs/TEXT_NORMALIZATION.md`).
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
        token.replace(text) { match -> spell(match.value) }

    private fun spell(raw: String): String {
        var token = raw
        var prefix = ""
        if (token.startsWith("-")) {
            prefix = "minus "
            token = token.substring(1)
        }

        val dot = token.indexOf('.')
        val fraction = if (dot >= 0) token.substring(dot + 1) else null
        val integerText = (if (dot >= 0) token.substring(0, dot) else token).replace(",", "")

        // Year heuristic: a bare four-digit integer in the common year span.
        if (fraction == null && ',' !in raw && integerText.length == 4) {
            val year = integerText.toIntOrNull()
            if (year != null && year in 1000..2099) {
                return prefix + NumberSpeller.year(year)
            }
        }

        val integer = integerText.toLongOrNull() ?: return raw
        return if (fraction == null) {
            prefix + NumberSpeller.cardinal(integer)
        } else {
            prefix + NumberSpeller.decimal(integer, fraction)
        }
    }
}
