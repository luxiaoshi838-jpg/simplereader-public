package com.simplereader.app.reader

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Historical class name retained; V768 intentionally supersedes the V114 unit restriction. */
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
    fun rule115ExtendsWholeLineTrailingPunctuationToSectionRoundVolumeAndPart() {
        listOf(
            "第1节 新的开始。",
            "第一回 新的开始！",
            "第2卷 新的开始？",
            "第三篇 新的开始……",
            "第3节。",
            "第四回！",
            "第五卷、",
            "第六篇……"
        ).forEach { line ->
            assertNotNull("规则115应识别：$line", DirectTxtCatalogV100.recognize(line))
        }
    }

    @Test
    fun ordinaryWordsAndMiddleSentencePunctuationRemainRejected() {
        listOf(
            "第1章鱼很好吃。",
            "第3节课。",
            "第2回家！",
            "第1章 标题。还有正文",
            "第3节。还有正文",
            "第四回！继续说",
            "第五卷？后记"
        ).forEach { line ->
            assertNull("不应因规则115尾标点特例被识别：$line", DirectTxtCatalogV100.recognize(line))
        }
    }
}
