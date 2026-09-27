package com.geneing.epubreader.playback.normalization

/**
 * Spells number ranges: `3-5`, `1990-2000`, `pp. 5-7`, `3 - 5`.
 *
 * Produces "three to five" / "nineteen ninety to two thousand". The
 * punctuation form is matched with the numerals (a single regex), so it does
 * not depend on the spacing around the dash; the spaced form runs afterward on
 * what is left.
 *
 * The dash direction is deliberately limited to the hyphen and en dash. A
 * minus sign is a different character, which keeps a genuine negative such as
 * `-5` in [NumberNormalizer] rather than turning it into a range.
 */
internal class RangeNormalizer : TextNormalizationRule {

    /** `three - five` / `twenty - four`, matched after numerals are spelled. */
    private val spaced = Regex(
        "([a-z][a-z-]*)\\s+[-\u2013]\\s+([a-z][a-z-]*)",
        RegexOption.IGNORE_CASE,
    )

    override fun apply(text: String): String {
        val result = punctuated.replace(text) { match ->
            "${NumberSpeller.spellToken(match.groupValues[1])} to " +
                NumberSpeller.spellToken(match.groupValues[2])
        }
        return spaced.replace(result) { match ->
            "${match.groupValues[1]} to ${match.groupValues[2]}"
        }
    }

    private companion object {
        /** Numerals joined by a hyphen or en dash, e.g. `3-5`, `1,000-2,000`. */
        private val punctuated = Regex(
            "(?<![\\p{L}\\p{N}-])" +
                "(\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?)" +
                "[-\\u2013]" +
                "(\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?)" +
                "(?![\\p{L}\\p{N}])",
        )
    }
}
