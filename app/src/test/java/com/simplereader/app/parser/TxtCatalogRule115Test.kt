package com.simplereader.app.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TxtCatalogRule115Test {
    @Test fun prefixedChapterSectionRoundVolumePartIgnoreOnlyWholeLineTrailingPunctuation() {
        val accepted = listOf(
            "第1章。",
            "第一章：",
            "第十二章？",
            "第3节！",
            "第四回，",
            "第五卷、",
            "第六篇……",
            "第3节 新的开始。",
            "第四回 新的开始！",
            "第五卷 新的开始？",
            "第六篇 新的开始……"
        )
        accepted.forEach { line ->
            assertEquals("expected Rule115 title: $line", line, TxtParser.extractStructuredChapterTitle(line))
        }
    }

    @Test fun trailingPunctuationDoesNotTurnOrdinaryWordsIntoCatalogTitles() {
        val rejected = listOf(
            "第3节课。",
            "第12章鱼！",
            "第2回家？"
        )
        rejected.forEach { line ->
            assertNull("must remain prose: $line", TxtParser.extractStructuredChapterTitle(line))
        }
    }

    @Test fun sentencePunctuationInsideTheLineIsNotIgnoredAsTrailingPunctuation() {
        val rejected = listOf(
            "第3节。正文",
            "第四回！继续说",
            "第五卷？后记"
        )
        rejected.forEach { line ->
            assertNull("middle sentence punctuation must not be relaxed: $line", TxtParser.extractStructuredChapterTitle(line))
        }
    }
}
