package com.geneing.epubreader.playback.normalization

/**
 * Spells a date written with a month name: "13 Feb. 2007", "February 13,
 * 2007", "Feb 2007". Runs before [NumberNormalizer] so the day reads as an
 * ordinal rather than a cardinal.
 *
 * The month name may still carry its abbreviation period; [AbbreviationNormalizer]
 * strips it later, which is harmless because the period is the last character.
 */
internal class MonthDayNormalizer : TextNormalizationRule {

    /** Builds a range check from a numeric group, e.g. day 1..31. */
    private fun inRange(group: String, range: IntRange): Boolean {
        val value = group.toIntOrNull() ?: return false
        return value in range
    }

    override fun apply(text: String): String =
        MONTH_FIRST.replace(text) { match ->
            val month = match.groupValues[1]
            val day = match.groupValues[2]
            val year = match.groupValues[3]
            if (!inRange(day, 1..31) || (year.isNotEmpty() && !inRange(year, 1000..2099))) {
                return@replace match.value
            }
            val spoken = "${monthName(month)} ${NumberSpeller.ordinal(day.toLong())}"
            if (year.isEmpty()) spoken else "$spoken ${NumberSpeller.spellToken(year, ordinal = true)}"
        }

    private fun monthName(raw: String): String =
        MONTHS.getValue(raw.lowercase().removeSuffix("."))

    private companion object {
        private val MONTHS = mapOf(
            "jan" to "January", "feb" to "February", "mar" to "March", "apr" to "April",
            "may" to "May", "jun" to "June", "jul" to "July", "aug" to "August",
            "sep" to "September", "sept" to "September", "oct" to "October",
            "nov" to "November", "dec" to "December",
        )

        private val MONTH = MONTHS.keys.sortedByDescending { it.length }.joinToString("|")

        private val MONTH_FIRST = Regex(
            "(?<![\\p{L}\\p{N}])($MONTH)\\.?" +
                "\\s+(\\d{1,2})(?:st|nd|rd|th)?" +
                "(?:(?:\\s*,\\s*|\\s+)(\\d{1,4}))?" +
                "(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE,
        )
    }
}
