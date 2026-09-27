package com.geneing.epubreader.playback.normalization

/**
 * A single rewrite applied to an utterance before it is handed to a speech
 * engine.
 *
 * Rules are intentionally small and pure (`String -> String`) so they can be
 * composed in a fixed order, unit-tested in isolation, and added one at a time
 * without touching the narration loop. Rules may expand matched source spans,
 * but should leave unrelated text alone and avoid characters the engine cannot
 * pronounce. Output is considered final spoken text unless a named downstream
 * rule is its designated consumer (as [DottedNumberNormalizer] is for the
 * intermediate dotted groups from `DigitGroupNormalizer`). Other later rules
 * must not reinterpret it as fresh source input. The combined pipeline is
 * expected to be idempotent.
 *
 * See `docs/TEXT_NORMALIZATION.md` for the full pipeline and the catalogue of
 * planned rules.
 */
internal fun interface TextNormalizationRule {

    /** Returns [text] rewritten for speech. Must not return null. */
    fun apply(text: String): String
}
