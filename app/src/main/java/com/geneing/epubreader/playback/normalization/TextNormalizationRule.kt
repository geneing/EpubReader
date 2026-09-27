package com.geneing.epubreader.playback.normalization

/**
 * A single rewrite applied to an utterance before it is handed to a speech
 * engine.
 *
 * Rules are intentionally small and pure (`String -> String`) so they can be
 * composed in a fixed order, unit-tested in isolation, and added one at a time
 * without touching the narration loop. A rule must preserve or shorten the
 * text; it should never introduce characters the engine cannot pronounce.
 *
 * See `docs/TEXT_NORMALIZATION.md` for the full pipeline and the catalogue of
 * planned rules.
 */
internal fun interface TextNormalizationRule {

    /** Returns [text] rewritten for speech. Must not return null. */
    fun apply(text: String): String
}
