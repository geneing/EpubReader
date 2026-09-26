package com.geneing.epubreader.playback

import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.indexOfFirstWithHref
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.ContentService
import org.readium.r2.shared.publication.services.content.TextContentTokenizer
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.tokenizer.TextUnit

/**
 * Iterates the sentences of a [Publication] in reading order, mirroring the
 * sentence granularity Readium's own TTS navigator uses (the default
 * [TextUnit.Sentence] tokenizer). EpubReader owns the narration loop, so this
 * replaces Readium's internal `TtsUtteranceIterator`.
 *
 * Not thread-safe; callers serialize access (see `PocketNarrationSession`).
 */
@OptIn(ExperimentalReadiumApi::class)
internal class PublicationSentenceIterator(
    private val publication: Publication,
    initialLocator: Locator?,
) {

    data class Sentence(
        val text: String,
        val resourceIndex: Int,
        val href: Url,
        val locations: Locator.Locations,
        val textContext: Locator.Text,
        val language: Language?,
    )

    private val contentService: ContentService =
        publication.findService(ContentService::class)
            ?: error("The publication does not provide a readable content service.")

    private var publicationIterator: Content.Iterator = createIterator(initialLocator)

    /** Sentences extracted from the current publication element, with a cursor. */
    private var buffer: List<Sentence> = emptyList()
    private var cursor: Int = -1

    fun seek(locator: Locator?) {
        publicationIterator = createIterator(locator)
        buffer = emptyList()
        cursor = -1
    }

    /** Advances to the next sentence, or null at the end of the publication. */
    suspend fun next(): Sentence? {
        if (cursor + 1 < buffer.size) {
            cursor++
            return buffer[cursor]
        }
        while (true) {
            val element = publicationIterator.nextOrNull() ?: return null
            val sentences = element.toSentencesTokenized()
            if (sentences.isEmpty()) continue
            buffer = sentences
            cursor = 0
            return buffer[0]
        }
    }

    private fun createIterator(locator: Locator?): Content.Iterator =
        contentService.content(locator).iterator()

    private fun Content.Element.toSentences(): List<Sentence> {
        fun sentence(text: String, locator: Locator, language: Language?): Sentence? {
            if (!text.any { it.isLetterOrDigit() }) return null
            val resourceIndex = publication.readingOrder.indexOfFirstWithHref(locator.href)
                ?: return null
            return Sentence(
                text = text,
                resourceIndex = resourceIndex,
                href = locator.href,
                locations = locator.locations,
                textContext = locator.text,
                language = language,
            )
        }

        return when (this) {
            is Content.TextElement -> segments.mapNotNull { segment ->
                sentence(segment.text, segment.locator, segment.language)
            }

            is Content.TextualElement -> listOfNotNull(
                text?.takeIf { it.isNotBlank() }?.let { sentence(it, locator, language) },
            )

            else -> emptyList()
        }
    }

    private fun Content.Element.tokenize(): List<Content.Element> =
        TextContentTokenizer(
            language = language,
            unit = TextUnit.Sentence,
            overrideContentLanguage = overrideContentLanguage,
        ).tokenize(this)

    /** Tokenizer language, applied when content does not carry its own. */
    var language: Language? = null

    /** Whether [language] should supersede content language. */
    var overrideContentLanguage: Boolean = false

    private fun Content.Element.toSentencesTokenized(): List<Sentence> =
        tokenize().flatMap { it.toSentences() }
}
