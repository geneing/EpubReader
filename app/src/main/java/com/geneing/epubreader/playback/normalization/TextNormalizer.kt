package com.geneing.epubreader.playback.normalization

import org.readium.r2.shared.util.Language

/**
 * Rewrites an utterance into the form a speech engine should pronounce.
 *
 * The normalizer runs **after** sentence segmentation and **before** synthesis,
 * so it only ever sees one sentence of source text and never has to preserve
 * locators or offsets: the original `Sentence.text`, its locator and the
 * reader highlight are untouched. Locators are resolved from source positions,
 * not from the spoken string.
 *
 * Rule order is significant and is documented in `docs/TEXT_NORMALIZATION.md`:
 * non-prose spans are isolated first, text is cleaned up, structured numeric
 * forms are claimed before bare numerals, and late rules must not re-interpret
 * another rule's emitted spoken text as source input.
 *
 * ## Extending
 * Add a [TextNormalizationRule] and append it to [English] in the intended
 * order. Keep each rule pure, deterministic and independently unit-tested.
 */
internal class TextNormalizer(private val rules: List<TextNormalizationRule>) {

    /** Applies every rule in order and returns the spoken form of [text]. */
    fun normalize(text: String): String {
        var result = text
        for (rule in rules) {
            result = rule.apply(result)
        }
        return result
    }

    companion object {

        /** English normalization, the only language the Pocket engine speaks. */
        val English: TextNormalizer = TextNormalizer(
            listOf(
                listOf(
                    // 1. Isolate spans that must never reach the prose rules.
                    NonProseNormalizer(),
                    // 2. Clean up characters and whitespace the rest relies on.
                    HygieneNormalizer(),
                ),
                // 3. Claim structured numeric forms before bare numerals.
                DATE_TIME_AND_QUANTITY_RULES,
                listOf(
                    FractionNormalizer(),
                    DigitGroupNormalizer(),
                    UnitAndSymbolNormalizer(),
                    // 4. Whatever is left is a plain numeral.
                    NumberNormalizer(),
                    DottedNumberNormalizer(),
                    // 5. Symbol words and word-level rewrites last.
                    AbbreviationNormalizer(),
                    SymbolNormalizer(),
                    LexiconNormalizer(),
                ),
            ).flatten(),
        )

        /** No-op normalizer for content we do not yet support rewriting. */
        private val Identity: TextNormalizer = TextNormalizer(emptyList())

        /**
         * Picks a normalizer for [language]. Unknown languages fall back to
         * English; non-English languages are currently passed through
         * unchanged rather than mis-pronounced by English rules.
         */
        fun forLanguage(language: Language?): TextNormalizer {
            val code = language?.code ?: return English
            return if (code == "en" || code.startsWith("en-")) English else Identity
        }
    }
}
