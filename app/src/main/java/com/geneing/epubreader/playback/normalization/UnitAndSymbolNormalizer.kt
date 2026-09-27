package com.geneing.epubreader.playback.normalization

/**
 * Handles measurements and unit abbreviations: `5 km`, `6 ft.`, `70 mph`,
 * `20 °C`, `10 kg`, `3 in.`, `25 °`, and the multiplication sign (`2 x 3`).
 *
 * A unit is only expanded when a number precedes it, which is why `ft.` and
 * `in.` are absent from [AbbreviationNormalizer]'s list. `x`/`×` between two
 * numbers is multiplication ("2 x 3" -> "two times three"). The degree sign
 * always means degrees, including bare angles ("25°"). Units that share a
 * symbol (`m` metres vs miles) resolve to the SI reading.
 */
internal class UnitAndSymbolNormalizer : TextNormalizationRule {

    /**
     * The unit symbol is captured with a leading space (`( km)`) so the lookup
     * cannot collide with the `°`-prefixed entries, and so the joined result
     * keeps its spacing.
     */
    private val measurement = Regex(
        "(?<![\\p{L}\\p{N}.])" +
            "(\\d+(?:[.,]\\d+)?|\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?)" +
            "(\\s?(?:km/h|km|mph|kph|kg|mg|ml|cm|mm|ft|yd|mi|lb|oz|°C|°F|°|m|g|l|in\\.?|h|min|sec))" +
            "(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )

    private val multiplication = Regex(
        "(?<![\\p{L}\\p{N}.])" +
            "(\\d+(?:\\.\\d+)?)" +
            "\\s?[x×]\\s?" +
            "(\\d+(?:\\.\\d+)?)" +
            "(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )

    override fun apply(text: String): String {
        val multiplied = multiplication.replace(text) { match ->
            "${NumberSpeller.quantity(match.groupValues[1])} times " +
                NumberSpeller.quantity(match.groupValues[2])
        }
        return measurement.replace(multiplied) { match ->
            val number = NumberSpeller.quantity(match.groupValues[1])
            val unit = UNITS.getValue(match.groupValues[2].trim().lowercase())
            if (unit.isEmpty()) number else "$number $unit"
        }
    }

    private companion object {
        private val UNITS = mapOf(
            "km/h" to "kilometers per hour",
            "mph" to "miles per hour",
            "kph" to "kilometers per hour",
            "km" to "kilometers",
            "kg" to "kilograms",
            "mg" to "milligrams",
            "ml" to "milliliters",
            "cm" to "centimeters",
            "mm" to "millimeters",
            "ft" to "feet",
            "yd" to "yards",
            "mi" to "miles",
            "lb" to "pounds",
            "oz" to "ounces",
            "°c" to "degrees Celsius",
            "°f" to "degrees Fahrenheit",
            "°" to "degrees",
            "m" to "meters",
            "g" to "grams",
            "l" to "liters",
            "in" to "inches",
            "in." to "inches",
            "h" to "hours",
            "min" to "minutes",
            "sec" to "seconds",
        )
    }
}
