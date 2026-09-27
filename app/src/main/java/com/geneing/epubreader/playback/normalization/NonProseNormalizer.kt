package com.geneing.epubreader.playback.normalization

/**
 * Splits a sentence into any non-prose spans it contains: inline code, URLs,
 * email addresses and mathematical expressions.
 *
 * These spans are spoken as sequences of letters and symbols rather than as
 * words, e.g. `user@example.com` becomes "u s e r at example dot com". The
 * scanner walks the text and only hands the prose between the spans to the rest
 * of the pipeline, so a version number inside a URL is never read as a
 * quantity.
 */
internal class NonProseNormalizer : TextNormalizationRule {

    override fun apply(text: String): String {
        val output = StringBuilder(text.length)
        val prose = StringBuilder()

        fun flushProse() {
            if (prose.isNotEmpty()) {
                output.append(prose)
                prose.clear()
            }
        }

        var index = 0
        while (index < text.length) {
            val span = spanAt(text, index)
            if (span != null) {
                flushProse()
                output.append(spellSpan(span))
                index += span.length
            } else {
                prose.append(text[index])
                index++
            }
        }
        flushProse()
        return output.toString()
    }

    /** The longest non-prose span starting at [index], or null for prose. */
    private fun spanAt(text: String, index: Int): String? {
        for (pattern in PATTERNS) {
            val match = pattern.matchAt(text, index) ?: continue
            val value = match.value
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun spellSpan(span: String): String = buildString(span.length * 2) {
        for (character in span) {
            when {
                character.isLetterOrDigit() -> {
                    if (isNotEmpty()) append(' ')
                    append(character.lowercaseChar())
                }
                character == '@' -> append(" at ")
                character == '.' -> append(" dot ")
                character == '/' -> append(" slash ")
                character == ':' -> append(" colon ")
                character == '-' -> append(" dash ")
                character == '_' -> append(" underscore ")
                character == '&' -> append(" and ")
                character == '=' -> append(" equals ")
                character == '+' -> append(" plus ")
                character == '#' -> append(" hash ")
                character == '\\' -> append(" backslash ")
                character == '^' -> append(" caret ")
                character == '~' -> append(" tilde ")
                character == '|' -> append(" pipe ")
                character == '?' -> append(" question mark ")
                character == '%' -> append(" percent ")
                character.isWhitespace() -> Unit
                else -> {
                    if (isNotEmpty()) append(' ')
                    append(character)
                }
            }
        }
        trimEnd()
    }

    private companion object {
        /** Ordered by the span kind that should win at a given position. */
        private val PATTERNS = listOf(
            Regex("`[^`\\n]+`"),
            Regex("https?://\\S+", RegexOption.IGNORE_CASE),
            Regex("www\\.\\S+", RegexOption.IGNORE_CASE),
            Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}"),
        )
    }
}
