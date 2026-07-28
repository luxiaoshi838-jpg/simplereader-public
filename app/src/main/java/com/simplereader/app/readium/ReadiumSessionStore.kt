package com.simplereader.app.readium

import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

/**
 * Keeps non-parcelable Readium publication objects while an EPUB reader activity is alive.
 * The activity reopens the source file after process recreation before replacing its reader fragment.
 */
object ReadiumSessionStore {
    data class Session(
        val publication: Publication,
        val initialLocator: Locator?,
        val navigatorFactory: EpubNavigatorFactory
    )

    private val sessions = mutableMapOf<Long, Session>()

    @Synchronized
    operator fun get(bookId: Long): Session? = sessions[bookId]

    @Synchronized
    fun put(bookId: Long, session: Session) {
        sessions.remove(bookId)?.publication?.close()
        sessions[bookId] = session
    }

    @Synchronized
    fun remove(bookId: Long): Session? = sessions.remove(bookId)
}
