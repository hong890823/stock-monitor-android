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

    @Test
    fun positiveEventKindOnlyTakesRealCatalysts() {
        // 真利好：重组落地获批、业绩爆发、中标/重大合同、大股东/实控人增持。
        assertEquals("重组落地", EventCoil.positiveEventKind("华天科技:关于标的资产过户完成的公告"))
        assertEquals("重组落地", EventCoil.positiveEventKind("甲:关于重大资产重组实施完成的公告"))
        assertEquals("业绩预增", EventCoil.positiveEventKind("甲:2026年前三季度业绩预增公告"))
        assertEquals("中标", EventCoil.positiveEventKind("甲:重大项目中标公告"))
        assertEquals("重大合同", EventCoil.positiveEventKind("甲:关于签订重大合同的公告"))
        assertEquals("大股东增持", EventCoil.positiveEventKind("甲:关于控股股东增持公司股份计划的公告"))

        // 高占比回购注销算真利好：看「占总股本比例」（≥ 0.5%），不看绝对金额。
        assertEquals(
            "回购注销",
            EventCoil.positiveEventKind(
                "甲:关于回购股份注销完成的公告",
                "本次注销回购股份800万股，占公司总股本的1.2%，累计支付总金额人民币2.5亿元。",
            ),
        )
        // 金额再大、占比太小也没意思；只报金额不报占比的宁缺勿滥。
        assertNull(
            EventCoil.positiveEventKind(
                "甲:关于回购股份注销完成的公告",
                "本次注销回购股份80万股，占公司总股本的0.15%，累计支付总金额人民币2.5亿元。",
            )
        )
        assertNull(
            EventCoil.positiveEventKind(
                "甲:关于回购股份注销完成的公告",
                "累计支付总金额人民币2.5亿元。",
            )
        )
        assertNull(EventCoil.positiveEventKind("甲:关于回购股份注销完成的公告"))
        // 股权激励的限制性股票注销是例行公事，不算利好（占比再大也不算）。
        assertNull(
            EventCoil.positiveEventKind(
                "甲:关于回购注销部分限制性股票的公告",
                "占公司总股本的1.5%。",
            )
        )

        // 不算真利好：分红/送转、回购计划与实施完成、董监高小额增持计划、「实施完成」的其他用法。
        assertNull(EventCoil.positiveEventKind("甲:2025年年度权益分派实施完成的公告"))
        assertNull(EventCoil.positiveEventKind("甲:关于回购股份实施完成的公告"))
        assertNull(EventCoil.positiveEventKind("甲:关于回购计划的公告"))
        assertNull(EventCoil.positiveEventKind("甲:关于部分董事增持公司股份计划的公告"))
        assertNull(EventCoil.positiveEventKind("甲:关于股权激励计划实施完成的公告"))
        // 未中标是利空，不能因为含「中标」二字就算利好。
        assertNull(EventCoil.positiveEventKind("甲:关于项目未中标的公告"))
    }

    @Test
    fun ratioPctPicksLargestShareOfCapital() {
        assertEquals(1.2, EventCoil.ratioPct("本次注销占公司总股本的1.2%")!!, 0.01)
        assertEquals(0.85, EventCoil.ratioPct("", "占目前公司总股本比例为 0.85%")!!, 0.01)
        // 多个比例取最大；净资产/总资产的比例不是这个口径，不认。
        assertEquals(2.5, EventCoil.ratioPct("占总股本的1.2%，占总市值的2.5%")!!, 0.01)
        assertNull(EventCoil.ratioPct("占公司最近一期经审计净资产的 5%"))
        assertNull(EventCoil.ratioPct("关于回购股份注销完成的公告"))
    }
}
