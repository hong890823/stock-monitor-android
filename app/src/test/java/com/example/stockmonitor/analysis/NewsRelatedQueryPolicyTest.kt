package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.NewsItem
import com.example.stockmonitor.core.NewsTrack
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsRelatedQueryPolicyTest {
    private val nowMs = timestamp("2026-10-25 15:00:00")

    @Test
    fun stockNameMatchesOnlyRelatedHeadlines() {
        val matches = NewsRelatedQueryPolicy.query(
            items = listOf(
                item("宁德时代获得储能大额订单"),
                item("某公司发布新品"),
            ),
            query = "宁德时代",
            nowMs = nowMs,
            limit = 5,
        )

        assertEquals(listOf("宁德时代获得储能大额订单"), matches.map { it.headline.item.title })
        assertTrue(matches.single().matchedTerms.contains("宁德时代"))
    }

    @Test
    fun sectorQueryExpandsCommonAliases() {
        val matches = NewsRelatedQueryPolicy.query(
            items = listOf(
                item("芯片厂商宣布扩产"),
                item("消费行业销售回暖"),
            ),
            query = "半导体",
            nowMs = nowMs,
            limit = 5,
        )

        assertEquals(listOf("芯片厂商宣布扩产"), matches.map { it.headline.item.title })
        assertTrue(matches.single().matchedTerms.any { it in setOf("半导体", "芯片", "集成电路", "晶圆") })
    }

    @Test
    fun queryUsesCompositeOrderAndLimit() {
        val matches = NewsRelatedQueryPolicy.query(
            items = listOf(
                item("机器人公司发布产品", trustScore = 60, publishedAtMs = timestamp("2026-10-25 14:00:00")),
                item("机器人企业锁定量产订单", trustScore = 80, publishedAtMs = timestamp("2026-10-25 10:00:00")),
                item("机器人大会宣传未来趋势", trustScore = 90, publishedAtMs = timestamp("2026-10-25 14:30:00")),
            ),
            query = "机器人",
            nowMs = nowMs,
            limit = 2,
        )

        assertEquals(2, matches.size)
        assertEquals("机器人企业锁定量产订单", matches.first().headline.item.title)
    }

    @Test
    fun queryWindowCanIncludeRecentMultiDayEvents() {
        val matches = NewsRelatedQueryPolicy.query(
            items = listOf(
                item("宁德时代两日前订单", publishedAtMs = timestamp("2026-10-23 12:00:00")),
                item("宁德时代四日前订单", publishedAtMs = timestamp("2026-10-21 12:00:00")),
            ),
            query = "宁德时代",
            nowMs = nowMs,
            limit = 5,
            maxAgeDays = 3,
        )

        assertEquals(listOf("宁德时代两日前订单"), matches.map { it.headline.item.title })
    }

    @Test
    fun queryFindsMatchBeyondFirstTwentyHeadlines() {
        val unrelated = (1..25).map { item("无关科技动态 $it") }
        val matches = NewsRelatedQueryPolicy.query(
            items = unrelated + item("宁德时代订单落地"),
            query = "宁德时代",
            nowMs = nowMs,
            limit = 1,
        )

        assertEquals(listOf("宁德时代订单落地"), matches.map { it.headline.item.title })
    }

    private fun item(
        title: String,
        trustScore: Int = 75,
        publishedAtMs: Long = timestamp("2026-10-25 12:00:00"),
    ) = NewsItem(
        title = title,
        publishedAtMs = publishedAtMs,
        source = "测试来源",
        sourceId = "test",
        track = NewsTrack.TECHNOLOGY,
        trustScore = trustScore,
    )

    private fun timestamp(value: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
            .parse(value)!!.time
}
