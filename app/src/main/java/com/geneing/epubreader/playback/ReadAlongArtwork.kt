package com.geneing.epubreader.playback

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import java.io.ByteArrayOutputStream

/**
 * Renders the narrated sentence as album artwork.
 *
 * Android Auto media apps cannot open a free-form text screen, but the
 * now-playing screen does show the item's artwork. Turning the current sentence
 * into artwork is therefore the reliable way to put the text being read in front
 * of the driver, and the current word is drawn with a highlight.
 */
internal object ReadAlongArtwork {

    fun render(
        text: String,
        range: IntRange?,
        bookTitle: String,
        progressLabel: String,
    ): ByteArray? = runCatching {
        val sentence = clip(text.trim())
        if (sentence.isEmpty()) return null
        val size = 1024
        val padding = 96f
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(BACKGROUND)

            val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = MUTED
                textSize = 30f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            canvas.drawText(ellipsize(bookTitle, headerPaint, size - padding * 2), padding, 84f, headerPaint)

            val body = highlighted(sentence, range)
            var textSize = 58f
            var layout = buildLayout(body, textSize, (size - padding * 2).toInt())
            val available = size - 220f
            while (layout.height > available && textSize > 26f) {
                textSize -= 3f
                layout = buildLayout(body, textSize, (size - padding * 2).toInt())
            }
            canvas.save()
            canvas.translate(padding, 128f + ((available - layout.height) / 2f).coerceAtLeast(0f))
            layout.draw(canvas)
            canvas.restore()

            val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = MUTED
                textSize = 28f
            }
            canvas.drawText(progressLabel, padding, size - 64f, footerPaint)
            canvas.drawText("EpubReader", size - padding - footerPaint.measureText("EpubReader"), size - 64f, footerPaint)

            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()

    private fun buildLayout(text: CharSequence, textSize: Float, width: Int): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BODY
            this.textSize = textSize
            typeface = Typeface.create("serif", Typeface.NORMAL)
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(10f, 1.1f)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .build()
    }

    private fun highlighted(text: String, range: IntRange?): CharSequence {
        val builder = SpannableStringBuilder(text)
        if (range == null) {
            builder.setSpan(
                ForegroundColorSpan(HIGHLIGHT_TEXT),
                0,
                text.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            return builder
        }
        val start = range.first.coerceIn(0, text.length)
        val end = (range.last + 1).coerceIn(start, text.length)
        if (start == end) {
            builder.setSpan(
                ForegroundColorSpan(HIGHLIGHT_TEXT),
                0,
                text.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            return builder
        }
        builder.setSpan(
            BackgroundColorSpan(HIGHLIGHT_BACKGROUND),
            start,
            end,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(
            ForegroundColorSpan(HIGHLIGHT_TEXT),
            start,
            end,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return builder
    }

    private fun clip(text: String): String = if (text.length <= MAX_CHARS) {
        text
    } else {
        text.take(MAX_CHARS).substringBeforeLast(' ', text.take(MAX_CHARS)).trimEnd() + "…"
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + ellipsis) > maxWidth) {
            end--
        }
        return text.substring(0, end).trimEnd() + ellipsis
    }

    private const val MAX_CHARS = 420
    private val BACKGROUND = Color.rgb(20, 37, 30)
    private val BODY = Color.rgb(243, 241, 232)
    private val MUTED = Color.rgb(167, 199, 183)
    private val HIGHLIGHT_BACKGROUND = Color.rgb(232, 196, 102)
    private val HIGHLIGHT_TEXT = Color.rgb(245, 226, 160)
}
