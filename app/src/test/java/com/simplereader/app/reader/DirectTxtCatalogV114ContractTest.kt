package com.simplereader.app.reader

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DirectTxtCatalogV114ContractTest {
    @Test
    fun prefixedChapterIgnoresOnlyTrailingPunctuation() {
        listOf(
            "第1章 新的开始。",
            "第一章 新的开始！",
            "第十二章 新的开始？？！！……",
            "第1章：新的开始。",
            "第1章。"
        ).forEach { line ->
            assertNotNull("应识别：$line", DirectTxtCatalogV100.recognize(line))
        }
    }

    @Test
    fun otherUnitsAndOrdinaryWordsDoNotInheritChapterException() {
        listOf(
            "第1节 新的开始。",
            "第一回 新的开始！",
            "第2卷 新的开始？",
            "第1章鱼很好吃。",
            "第1章 标题。还有正文"
        ).forEach { line ->
            assertNull("不应因V767尾标点特例被识别：$line", DirectTxtCatalogV100.recognize(line))
        }
    }
}
