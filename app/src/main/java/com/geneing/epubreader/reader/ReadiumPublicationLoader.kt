package com.geneing.epubreader.reader

import android.content.Context
import android.net.Uri
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toAbsoluteUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.adapter.pdfium.document.PdfiumDocumentFactory

internal data class OpenedPublication(
    val publication: Publication,
    val initialLocator: Locator?,
)

internal class ReadiumPublicationLoader(context: Context) {
    private val appContext = context.applicationContext
    private val httpClient = DefaultHttpClient()
    private val assetRetriever = AssetRetriever(appContext.contentResolver, httpClient)
    private val publicationOpener = PublicationOpener(
        publicationParser = DefaultPublicationParser(
            context = appContext,
            httpClient = httpClient,
            assetRetriever = assetRetriever,
            pdfFactory = PdfiumDocumentFactory(appContext),
        ),
    )

    suspend fun open(uri: Uri, progressPercent: Double): OpenedPublication {
        val url = uri.toAbsoluteUrl()
            ?: error("Android couldn't create a readable address for this book.")
        val asset = assetRetriever.retrieve(url)
            .getOrElse { error("Readium couldn't access this file: $it") }

        val publication = publicationOpener.open(asset, allowUserInteraction = false)
            .getOrElse { error("Readium couldn't open this publication: $it") }
        val initialLocator = progressPercent
            .takeIf { it > 0.0 }
            ?.let { publication.locateProgression((it / 100.0).coerceIn(0.0, 0.99)) }
        return OpenedPublication(publication, initialLocator)
    }
}
