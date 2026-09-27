package com.geneing.epubreader.playback.normalization

/**
 * Spells fractions: `3/4` -> "three quarters", `1/2` -> "one half", `3/10` ->
 * "three tenths".
 *
 * A numeric fraction is built from a cardinal numerator and a pluralized
 * ordinal denominator, then the irregular denominators are corrected so the
 * result is "quarter"/"half" rather than "fourth"/"second".
 * [NumericDateNormalizer] claims slashed dates such as `02/03/2007` first.
 */
internal class FractionNormalizer : TextNormalizationRule {

    private val fraction = Regex(
        "(?<![\\p{L}\\p{N}/.])" +
            "(\\d{1,3})\\s*/\\s*(\\d{1,3})" +
            "(?![\\p{L}\\p{N}/])",
    )

    private val word = Regex(
        "(?<![\\p{L}'’])" +
            "(one|two|three|five|eight|nine|twelve" +
            "|fourth|sixth|seventh|tenth|eleventh" +
            "|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)" +
            "(third|fifth|second|fourth)s" +
            "(?![\\p{L}'’])",
        RegexOption.IGNORE_CASE,
    )

    override fun apply(text: String): String {
        val spelled = fraction.replace(text) { match ->
            val numerator = match.groupValues[1].toInt()
            val denominator = match.groupValues[2].toInt()
            if (denominator == 0) {
                match.value
            } else {
                val numeratorWord = NumberSpeller.cardinal(numerator.toLong())
                val denominatorWord = pluralize(NumberSpeller.ordinal(denominator.toLong()))
                "$numeratorWord $denominatorWord"
            }
        }
        return word.replace(spelled) { match ->
            val numerator = match.groupValues[1].lowercase()
            val denominator = when (match.groupValues[2].lowercase()) {
                "third" -> "third"
                "fifth" -> "fifth"
                "second" -> "half"
                "fourth" -> "quarter"
                else -> match.groupValues[2]
            }
            "$numerator ${denominator}s"
        }
    }

    private fun pluralize(word: String): String =
        if (word.endsWith("f")) word.dropLast(1) + "ves" else word + "s"
}
