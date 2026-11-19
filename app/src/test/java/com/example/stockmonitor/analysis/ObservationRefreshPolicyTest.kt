package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.Quote
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationRefreshPolicyTest {
    private val quote = Quote(
        code = "sz003021",
        name = "兆威机电",
        price = 10.0,
        prevClose = 9.5,
        open = 9.6,
        high = 10.1,
        low = 9.5,
        volume = 1_000.0,
        turnover = 10_000.0,
        quoteDate = "2026-10-21",
    )

    @Test
    fun ordinaryUpdatesPersistDespiteQuoteMovement() {
        // 活跃股每个 tick 的累计成交量/额都在变，普通状态更新不能因「行情不相等」被丢弃。
        assertTrue(ObservationRefreshPolicy.shouldPersist(quote, quote.copy(volume = 2_000.0, turnover = 20_000.0)))
        assertTrue(ObservationRefreshPolicy.shouldPersist(quote, quote.copy(price = 10.2)))
        // 没有评估时行情才丢弃；当前行情缺失时按评估结果落盘。
        assertFalse(ObservationRefreshPolicy.shouldPersist(null, quote))
        assertTrue(ObservationRefreshPolicy.shouldPersist(quote, null))
    }

    @Test
    fun entryConfirmationsRequirePriceDriftCheck() {
        // 买点晋级/确认轮次：价格小幅漂移（≤2%）可落盘，大幅漂移丢弃等下一轮。
        assertTrue(ObservationRefreshPolicy.shouldPersist(quote, quote.copy(price = 10.1), protectsEntry = true))
        assertTrue(ObservationRefreshPolicy.shouldPersist(quote, quote.copy(price = 9.8), protectsEntry = true))
        assertFalse(ObservationRefreshPolicy.shouldPersist(quote, quote.copy(price = 10.3), protectsEntry = true))
        // 累计量变化不算价格漂移。
        assertTrue(ObservationRefreshPolicy.shouldPersist(quote, quote.copy(volume = 9_999.0), protectsEntry = true))
        assertFalse(ObservationRefreshPolicy.shouldPersist(null, quote, protectsEntry = true))
    }
}
