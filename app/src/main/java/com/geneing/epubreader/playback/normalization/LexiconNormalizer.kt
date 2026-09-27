package com.geneing.epubreader.playback.normalization

/**
 * Handles initialisms and acronyms, optionally with a user lexicon.
 *
 * A known acronym is replaced by its pronunciation ("NATO" -> "Nato", "WHO" ->
 * "W H O"); an unlisted all-caps or vowel-less word is spelled letter by letter
 * ("NASA" is known, "XYZ" -> "X Y Z"). Mixed-case words and ordinary words that
 * merely start a sentence are left alone.
 *
 * [lexicon] holds user pronunciation substitutions, applied afterwards so the
 * user always wins. It is a plain map of written form to spoken form. There is
 * no UI for it yet; wiring a Settings editor to this parameter is the intended
 * extension point (see `docs/TEXT_NORMALIZATION.md`).
 */
internal class LexiconNormalizer(
    private val lexicon: Map<String, String> = emptyMap(),
) : TextNormalizationRule {

    private val command = Regex(
        "(?<![\\p{L}\\p{N}])(?:[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}" +
            "|https?://\\S+|www\\.\\S+)(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )

    private val caps = Regex("(?<![\\p{L}\\p{N}'’])([A-Z][A-Z]{1,})(?![\\p{L}\\p{N}])")

    override fun apply(text: String): String {
        val normalized = caps.replace(text) { match ->
            val word = match.value
            when {
                ACRONYMS.containsKey(word) -> ACRONYMS.getValue(word)
                word.length > MAX_SPELLED_LENGTH -> word
                word.any { it in "AEIOU" } -> word
                // A digit is not a vowel, so a lone letter must not be
                // "spelled" into a word the rest of the pipeline invented.
                word.length < 2 -> word
                else -> spellLetters(word)
            }
        }
        if (lexicon.isEmpty()) return normalized

        var result = normalized
        for ((written, spoken) in lexicon) {
            if (written.isBlank()) continue
            result = Regex(
                "(?<![\\p{L}\\p{N}])${Regex.escape(written)}(?![\\p{L}\\p{N}])",
                RegexOption.IGNORE_CASE,
            ).replace(result, spoken)
        }
        return result
    }

    private fun spellLetters(word: String): String =
        word.map { it.toString() }.joinToString(" ")

    private companion object {
        private const val MAX_SPELLED_LENGTH = 6

        /** Acronyms pronounced as a word (or with an unusual reading). */
        private val ACRONYMS = mapOf(
            "NATO" to "Nato",
            "NASA" to "Nasa",
            "UNESCO" to "Unesco",
            "UNICEF" to "Unicef",
            "OPEC" to "Opec",
            "FIFA" to "Fifa",
            "NASDAQ" to "Nasdaq",
            "AIDS" to "Aids",
            "SARS" to "Sars",
            "COVID" to "Covid",
            "LASER" to "laser",
            "RADAR" to "radar",
            "SCUBA" to "scuba",
        )
    }
}
