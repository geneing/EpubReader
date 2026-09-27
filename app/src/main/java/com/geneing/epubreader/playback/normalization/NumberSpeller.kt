package com.geneing.epubreader.playback.normalization

/**
 * Spells out English numerals in words.
 *
 * Cardinal output follows the CLDR `spellout-numbering` convention (no "and",
 * hyphenated tens, e.g. `1234` -> "one thousand two hundred thirty-four") and
 * years follow `spellout-numbering-year` (`1999` -> "nineteen ninety-nine",
 * `2005` -> "two thousand five", `2013` -> "twenty thirteen"). Floating point
 * is read digit by digit after "point" (`3.14` -> "three point one four").
 *
 * Pure Kotlin and locale-independent so the rules are testable on the host
 * JVM and do not depend on `android.icu`.
 */
internal object NumberSpeller {

    private val ONES = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
    )

    private val TEENS = listOf(
        "ten", "eleven", "twelve", "thirteen", "fourteen",
        "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )

    private val TENS = listOf(
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
    )

    /** Scale words indexed by thousands group (10^0, 10^3, ... 10^18). */
    private val SCALES = listOf(
        "", "thousand", "million", "billion", "trillion", "quadrillion", "quintillion",
    )

    /** Spells any [value] the way it would be read as a quantity. */
    fun cardinal(value: Long): String {
        if (value == 0L) return ONES[0]
        if (value < 0) return "minus " + cardinal(-value)

        val groups = ArrayList<Int>()
        var remaining = value
        while (remaining > 0) {
            groups.add((remaining % 1000).toInt())
            remaining /= 1000
        }

        val parts = ArrayList<String>()
        for (index in groups.indices.reversed()) {
            val group = groups[index]
            if (group == 0) continue
            val words = under1000(group)
            val scale = SCALES[index]
            parts.add(if (scale.isEmpty()) words else "$words $scale")
        }
        return parts.joinToString(" ")
    }

    /**
     * Spells a four-digit year the way it is normally spoken. Values outside
     * the 1000..2099 range fall back to [cardinal].
     */
    fun year(value: Int): String = when {
        value == 1000 -> "one thousand"
        value in 1001..1999 -> {
            val first = value / 100
            val last = value % 100
            when {
                last == 0 -> "${cardinal(first.toLong())} hundred"
                last < 10 -> "${cardinal(first.toLong())} oh ${under1000(last)}"
                else -> "${cardinal(first.toLong())} ${under1000(last)}"
            }
        }
        value in 2000..2009 -> {
            val last = value % 100
            if (last == 0) "two thousand" else "two thousand ${under1000(last)}"
        }
        value in 2010..2099 -> "twenty ${under1000(value % 100)}"
        else -> cardinal(value.toLong())
    }

    /** Spells a decimal number, reading [fractionDigits] one digit at a time. */
    fun decimal(integerPart: Long, fractionDigits: String): String =
        "${cardinal(integerPart)} point ${digits(fractionDigits)}"

    /** Spells each character of [digits] independently, e.g. `"14"` -> "one four". */
    fun digits(digits: String): String =
        digits.map { character ->
            if (character.isDigit()) ONES[character - '0'] else character.toString()
        }.joinToString(" ")

    private fun under1000(value: Int): String = when {
        value < 10 -> ONES[value]
        value < 20 -> TEENS[value - 10]
        value < 100 -> {
            val tens = TENS[value / 10]
            val ones = value % 10
            if (ones == 0) tens else "$tens-${ONES[ones]}"
        }
        else -> {
            val hundreds = "${ONES[value / 100]} hundred"
            val rest = value % 100
            if (rest == 0) hundreds else "$hundreds ${under1000(rest)}"
        }
    }
}
