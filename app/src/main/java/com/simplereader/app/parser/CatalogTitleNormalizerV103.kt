package com.simplereader.app.parser

/** Compatibility facade; canonical V103 implementation lives beside the V745 direct detector. */
object CatalogTitleNormalizerV103 {
    fun normalize(raw: String): String = com.simplereader.app.reader.CatalogTitleNormalizerV103.normalize(raw).orEmpty()
}
