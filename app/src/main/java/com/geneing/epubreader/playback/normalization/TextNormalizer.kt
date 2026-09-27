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
 * Rules are ordered: abbreviations are expanded before number conversion so an
 * expansion such as "for example" can never be re-scanned as a number.
 *
 * ## Extending
 * Add a [TextNormalizationRule] and append it to [English]. Keep each rule
 * pure, deterministic and independently unit-tested. The pipeline, the planned
 * rule catalogue and the design principles live in
 * `docs/TEXT_NORMALIZATION.md`.
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
                AbbreviationNormalizer(),
                NumberNormalizer(),
            ),
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
