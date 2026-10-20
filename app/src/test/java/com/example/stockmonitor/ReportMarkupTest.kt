package com.example.stockmonitor

import com.example.stockmonitor.analysis.ReportMarkup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 报告里的重点标记：显示时染红，复制/分享时必须还原成纯文本，
 * 粘到聊天窗口里不能出现「«»」这种残留。
 */
class ReportMarkupTest {

    @Test
    fun marksOnlyTheTaggedRange() {
        val segments = ReportMarkup.parse("现价 ${ReportMarkup.red("17.74")} 元")

        assertEquals(listOf("现价 ", "17.74", " 元"), segments.map { it.text })
        assertEquals(listOf(false, true, false), segments.map { it.highlight })
    }

    @Test
    fun plainTextHasNoMarksLeft() {
        val text = "评分 ${ReportMarkup.red("62/100")}，区间 ${ReportMarkup.red("12.00 ~ 19.00")}"

        val plain = ReportMarkup.plain(text)

        assertEquals("评分 62/100，区间 12.00 ~ 19.00", plain)
        assertFalse(plain.contains(ReportMarkup.OPEN))
        assertFalse(plain.contains(ReportMarkup.CLOSE))
    }

    @Test
    fun unclosedMarkHighlightsTheRestInsteadOfLeakingIt() {
        val segments = ReportMarkup.parse("现价 ${ReportMarkup.OPEN}17.74 元")

        assertEquals(listOf("现价 ", "17.74 元"), segments.map { it.text })
        assertTrue(segments.last().highlight)
    }

    @Test
    fun textWithoutMarksIsOnePlainSegment() {
        val segments = ReportMarkup.parse("没有重点的普通文本")

        assertEquals(1, segments.size)
        assertFalse(segments[0].highlight)
    }
}
