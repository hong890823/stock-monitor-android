package com.example.stockmonitor

import com.example.stockmonitor.analysis.Announcement
import com.example.stockmonitor.analysis.EventCoil
import com.example.stockmonitor.core.DayBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事件后蓄势：利好落地（重组过户完成这类）+ 随后量缩价稳才成形态。
 * 只有事件没量、量价破位、公告是利空的都不给线索。以华天科技 2026-10-20~22 校准。
 */
class EventCoilTest {

    private val today = "2026-10-22"

    private fun bar(date: String, close: Double, volume: Double) = DayBar(date, close, close, close, close, volume)

    /** 20 日安静基线（量 100）+ 事件日放量上涨 + 缩量回踩。 */
    private fun coilingBars(): List<DayBar> {
        val bars = mutableListOf<DayBar>()
        for (i in 1..20) bars += bar("2026-09-%02d".format(i + 4), close = 14.0, volume = 100.0)
        bars += bar("2026-10-20", close = 14.35, volume = 130.0) // 事件日：+3.2% 放量
        bars += bar("2026-10-21", close = 14.25, volume = 85.0) // 缩量回踩、守住事件日收盘
        bars += bar("2026-10-22", close = 15.68, volume = 200.0) // 启动日（放量上涨也算守住）
        return bars
    }

    private val landingAnnouncement = Announcement(
        date = "2026-10-20",
        title = "华天科技:关于发行股份及支付现金购买资产并募集配套资金暨关联交易事项之标的资产过户完成的公告",
    )

    @Test
    fun positiveLandingWithCoilingVolumeGivesNote() {
        val signal = EventCoil.evaluate(listOf(landingAnnouncement), coilingBars(), today)!!

        assertEquals("2026-10-20", signal.eventDate)
        assertEquals("重组落地", signal.kind)
        assertTrue(signal.note.contains("事件后蓄势"))
        assertTrue(signal.note.contains("过户完成"))
        assertTrue(signal.note.contains("不改趋势判断"))
        assertTrue(signal.brief.contains("放量突破时值得盯"))
    }

    @Test
    fun negativeOrUnnoticedEventsAreSilent() {
        // 减持公告：一票否决。
        val negative = landingAnnouncement.copy(title = "华天科技:关于股东减持计划的公告")
        assertNull(EventCoil.evaluate(listOf(negative), coilingBars(), today))
        // 事件日既没放量也没涨：市场没理，不算形态。
        val unnoticed = coilingBars().map {
            if (it.date == "2026-10-20") it.copy(close = 14.05, volume = 95.0) else it
        }
        assertNull(EventCoil.evaluate(listOf(landingAnnouncement), unnoticed, today))
        // 没有任何利好公告。
        assertNull(EventCoil.evaluate(emptyList(), coilingBars(), today))
    }

    @Test
    fun breakingDownAfterEventKillsThePattern() {
        // 回踩跌破事件日收盘的 97%：利好被证伪，不再提示。
        val broken = coilingBars().map {
            if (it.date == "2026-10-21") it.copy(close = 13.5, volume = 200.0) else it
        }
        assertNull(EventCoil.evaluate(listOf(landingAnnouncement), broken, today))
    }
}
