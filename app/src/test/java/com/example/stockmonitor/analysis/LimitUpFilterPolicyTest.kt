package com.example.stockmonitor.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitUpFilterPolicyTest {
    @Test
    fun classifiesOnlyCurrentLimitUpsIntoExact102030Bands() {
        val mainBoard = LimitUpFilterPolicy.Stock("sh600519", "贵州茅台", isLimitUp = true)
        val growth = LimitUpFilterPolicy.Stock("sz300750", "宁德时代", isLimitUp = true)
        val star = LimitUpFilterPolicy.Stock("sh688041", "海光信息", isLimitUp = true)
        val beijing = LimitUpFilterPolicy.Stock("bj920001", "北交所样本", isLimitUp = true)
        val stMainBoard = LimitUpFilterPolicy.Stock("sh600001", "ST样本", isLimitUp = true)

        assertEquals(LimitUpFilterPolicy.LimitBand.LIMIT_10, LimitUpFilterPolicy.bandOf(mainBoard))
        assertEquals(LimitUpFilterPolicy.LimitBand.LIMIT_20, LimitUpFilterPolicy.bandOf(growth))
        assertEquals(LimitUpFilterPolicy.LimitBand.LIMIT_20, LimitUpFilterPolicy.bandOf(star))
        assertEquals(LimitUpFilterPolicy.LimitBand.LIMIT_30, LimitUpFilterPolicy.bandOf(beijing))
        assertNull(LimitUpFilterPolicy.bandOf(stMainBoard))
    }

    @Test
    fun specificBandsRequireCurrentLimitUpButAllKeepsEveryStock() {
        val waiting = LimitUpFilterPolicy.Stock("sh600519", "贵州茅台", isLimitUp = false)
        val limitUp = waiting.copy(isLimitUp = true)

        assertFalse(LimitUpFilterPolicy.matches(LimitUpFilterPolicy.Option.LIMIT_10, waiting))
        assertTrue(LimitUpFilterPolicy.matches(LimitUpFilterPolicy.Option.LIMIT_10, limitUp))
        assertTrue(LimitUpFilterPolicy.matches(LimitUpFilterPolicy.Option.ALL, waiting))
    }

    @Test
    fun filtersRowsWithoutMutatingSourceOrder() {
        val rows = listOf(
            LimitUpFilterPolicy.Stock("sh600519", "贵州茅台", isLimitUp = true),
            LimitUpFilterPolicy.Stock("sz300750", "宁德时代", isLimitUp = true),
            LimitUpFilterPolicy.Stock("sh600001", "ST样本", isLimitUp = true),
        )

        assertEquals(
            listOf("sh600519"),
            LimitUpFilterPolicy.filter(rows, LimitUpFilterPolicy.Option.LIMIT_10).map { it.code },
        )
        assertEquals(rows, LimitUpFilterPolicy.filter(rows, LimitUpFilterPolicy.Option.ALL))
    }
}
