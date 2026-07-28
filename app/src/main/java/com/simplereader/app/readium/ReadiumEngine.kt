package com.simplereader.app.readium

import android.content.Context
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

/** Shared Readium services used to open local EPUB publications. */
class ReadiumEngine(context: Context) {
    private val appContext = context.applicationContext

    val httpClient = DefaultHttpClient()

    val assetRetriever = AssetRetriever(
        contentResolver = appContext.contentResolver,
        httpClient = httpClient
    )

    val publicationOpener = PublicationOpener(
        publicationParser = DefaultPublicationParser(
            context = appContext,
            assetRetriever = assetRetriever,
            httpClient = httpClient,
            pdfFactory = null
        )
    )
}
