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

    suspend fun seek(locator: Locator?) {
        publicationIterator = createIterator(locator)
        buffer = emptyList()
        cursor = -1
        val target = locator?.locations?.progression
        if (target != null && target > 0.0 && target < 1.0) {
            seekToProgression(locator.href, target)
        }
    }

    /**
     * Resolves [locator] to the publication locator of the sentence it points at,
     * carrying the content parser's own CSS selector. Engines that seek solely by
     * `cssSelector` (Readium's TTS navigator) then land correctly, and selectors
     * produced by the visual navigator's JavaScript that the parser cannot match are
     * normalized. Returns [locator] unchanged when there is nothing to resolve to.
     */
    suspend fun resolve(locator: Locator?): Locator? {
        if (locator == null) return null
        seek(locator)
        val first = next() ?: return locator
        val resolved = locatorFor(first)
        // Keep the caller's text context (e.g. a selected sentence) so engines that
        // align by text can still do so; only the selector/progression is normalized.
        return if (locator.text == Locator.Text()) resolved else resolved.copy(text = locator.text)
    }

    private fun locatorFor(sentence: Sentence): Locator {
        val link = publication.readingOrder[sentence.resourceIndex]
        val base = publication.locatorFromLink(link)
            ?: error("Reading order item has no locator.")
        return base.copy(locations = sentence.locations, text = sentence.textContext)
    }

    /**
     * Positions the iterator at the block containing [target], a progression within
     * the resource.
     *
     * The content iterator only honors a locator's `cssSelector`. The visual
     * navigator's `firstVisibleElementLocator()` produces a selector from the live
     * DOM (`:root > :nth-child(2) > ...`) that Jsoup cannot match, and the reader's
     * `currentLocator` carries no selector at all, so both fall back to the start of
     * the resource. Resolving the target progression recovers the intended block
     * instead of restarting at the top of the chapter.
     */
    private suspend fun seekToProgression(href: Url, target: Double) {
        // Collect the elements up to the target without tokenizing them; only the
        // anchor is split into sentences. When the CSS selector already resolved to
        // the right block (the common skip case), the scan stops after one element.
        val elements = ArrayList<Content.Element>()
        val progressions = ArrayList<Double?>()
        var crossedIntoNextResource = false
        while (true) {
            val element = publicationIterator.nextOrNull() ?: break
            if (element.locator.href != href) {
                crossedIntoNextResource = true
                break
            }
            elements.add(element)
            val progression = element.locator.locations.progression
            progressions.add(progression)
            if (progression != null && progression > target) break
        }
        val anchor = progressionAnchor(progressions, target, crossedIntoNextResource)
        if (anchor.index < 0) return
        // Re-read an element that was already consumed while scanning: the block just
        // past the target, or the first block of the next resource when the target is
        // at the end of this one. The anchor itself needs no step back.
        if (anchor.stepBack && publicationIterator.hasPrevious()) {
            publicationIterator.previous()
        }
        buffer = elements[anchor.index].toSentencesTokenized()
        cursor = if (buffer.isEmpty()) -1 else 0
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

/** Element to anchor a progression seek at, and whether the next element is already consumed. */
internal data class ProgressionAnchor(val index: Int, val stepBack: Boolean)

/**
 * Chooses the element that a narration seek at `progression` [target] anchors at.
 *
 * [progressions] are the resource-ordered progressions of the scanned elements. The
 * anchor is the last element at or before [target]; when the first element is
 * already past the target it is used as-is. [stepBack] is true when the element just
 * past the anchor (or the first element of the next resource, when
 * [crossedIntoNextResource]) was consumed while scanning and must be read again.
 */
internal fun progressionAnchor(
    progressions: List<Double?>,
    target: Double,
    crossedIntoNextResource: Boolean = false,
): ProgressionAnchor {
    if (progressions.isEmpty()) return ProgressionAnchor(index = -1, stepBack = false)
    val overshoot = progressions.indexOfFirst { it != null && it > target }
    val index = when {
        overshoot < 0 -> progressions.lastIndex
        overshoot == 0 -> 0
        else -> overshoot - 1
    }
    return ProgressionAnchor(index = index, stepBack = crossedIntoNextResource || overshoot > 0)
}

