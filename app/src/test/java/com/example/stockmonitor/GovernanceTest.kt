package com.example.stockmonitor

import com.example.stockmonitor.analysis.Announcement
import com.example.stockmonitor.analysis.Governance
import com.example.stockmonitor.analysis.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公告扫描的关键词边界：业绩变脸信号要抓到，但不能误伤同字面的无关公告——
 * 「向下修正转股价格」是可转债常规操作，不是业绩下修。
 */
class GovernanceTest {

    private fun scan(title: String) = Governance.scan(
        items = listOf(Announcement(date = "2026-10-01", title = title)),
        referenceDate = "2026-10-09",
    )

    @Test
    fun forecastLossIsAlert() {
        val items = scan("2026年前三季度业绩预亏公告")
        assertTrue(items.any { it.level == RiskLevel.ALERT && it.reason.contains("业绩预亏") })
    }

    @Test
    fun forecastDowngradeIsAlert() {
        val items = scan("2026年度业绩预告修正公告")
        assertTrue(items.any { it.level == RiskLevel.ALERT && it.reason.contains("业绩预告修正") })
    }

    @Test
    fun convertibleBondRateResetIsNotEarningsDowngrade() {
        // 可转债「向下修正转股价格」：含「下修」字面但与业绩无关，绝不能亮红灯。
        val items = scan("关于向下修正可转换公司债券转股价格的公告")
        assertEquals(null, items.firstOrNull { it.level == RiskLevel.ALERT })
    }
}
