package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.NewsItem
import com.example.stockmonitor.core.NewsTrack
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadlineSelectorTest {
    private val nowMs = timestamp("2026-10-22 15:00:00")

    @Test
    fun heldSecurityRelevanceOutranksUnrelatedFresherHeadline() {
        val selected = HeadlineSelector.select(
            listOf(
                item("某科技公司发布新品", 88, publishedAtMs = timestamp("2026-10-22 14:55:00")),
                item("宁德时代获得储能大额订单", 75, publishedAtMs = timestamp("2026-10-22 12:00:00")),
            ),
            nowMs,
            limit = 2,
            focus = NewsFocusContext(heldNames = setOf("宁德时代")),
        )

        assertEquals("宁德时代获得储能大额订单", selected.first().item.title)
        assertTrue(selected.first().score.focus >= 100)
    }

    @Test
    fun materialThemeEventOutranksOrdinaryMacroHeadline() {
        val selected = HeadlineSelector.select(
            listOf(
                item("央行公开市场操作平稳", 70, NewsTrack.INVESTMENT),
                item("半导体设备厂商宣布扩产并锁定订单", 70, NewsTrack.TECHNOLOGY),
            ),
            nowMs,
            limit = 2,
        )

        assertEquals("半导体设备厂商宣布扩产并锁定订单", selected.first().item.title)
        assertTrue(selected.first().score.event >= 50)
    }

    @Test
    fun promotionalLaunchCopyIsPenalized() {
        val selected = HeadlineSelector.select(
            listOf(
                item("厂商新品发布会宣传未来趋势", 90, NewsTrack.TECHNOLOGY),
                item("AI 芯片公司获得量产订单", 70, NewsTrack.TECHNOLOGY),
            ),
            nowMs,
            limit = 2,
        )

        assertEquals("AI 芯片公司获得量产订单", selected.first().item.title)
        assertTrue(selected.first().score.penalty == 0)
    }

    @Test
    fun duplicateStoriesAreClusteredBeforeCorroborationScore() {
        val selected = HeadlineSelector.select(
            listOf(
                item("英伟达发布新一代AI芯片", 80, sourceId = "infoq_cn", source = "InfoQ 中文"),
                item("英伟达推出新一代 AI 芯片", 75, sourceId = "techmeme", source = "Techmeme"),
                item("机器人公司扩建生产基地", 75, sourceId = "kr36", source = "36氪"),
            ),
            nowMs,
            limit = 3,
        )

        assertEquals(2, selected.size)
        val cluster = selected.first { it.supportingSources.size == 2 }
        assertEquals(setOf("InfoQ 中文", "Techmeme"), cluster.supportingSources.toSet())
        assertEquals(10, cluster.score.corroboration)
    }

    @Test
    fun selectionBalancesInvestmentAndTechnologyLanes() {
        val items = listOf(
            item("央行下调存款准备金率", 80, NewsTrack.INVESTMENT),
            item("券商宣布并购重组方案", 79, NewsTrack.INVESTMENT),
            item("财政部发布专项补贴", 78, NewsTrack.INVESTMENT),
            item("机器人公司订单落地", 77, NewsTrack.TECHNOLOGY),
            item("云计算厂商宣布扩产", 76, NewsTrack.TECHNOLOGY),
        )

        val selected = HeadlineSelector.select(items, nowMs, limit = 4)

        assertEquals(2, selected.count { it.track == NewsTrack.INVESTMENT })
        assertEquals(2, selected.count { it.track == NewsTrack.TECHNOLOGY })
    }

    @Test
    fun freshnessBreaksScoreTies() {
        val selected = HeadlineSelector.select(
            listOf(
                item("AI 芯片量产订单落地", 75, publishedAtMs = timestamp("2026-10-22 10:00:00")),
                item("人工智能服务器采购订单签约", 75, publishedAtMs = timestamp("2026-10-22 14:00:00")),
            ),
            nowMs,
            limit = 2,
        )

        assertEquals("人工智能服务器采购订单签约", selected.first().item.title)
    }

    private fun item(
        title: String,
        trustScore: Int,
        track: NewsTrack = NewsTrack.TECHNOLOGY,
        publishedAtMs: Long = nowMs,
        sourceId: String = "test",
        source: String = "测试来源",
    ) = NewsItem(
        title = title,
        publishedAtMs = publishedAtMs,
        source = source,
        sourceId = sourceId,
        track = track,
        trustScore = trustScore,
    )

    private fun timestamp(value: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
            .parse(value)!!.time
}
