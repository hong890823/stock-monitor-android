package com.example.stockmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 涨跌停阈值。
 *
 * 东财板块成分给的是裸 6 位码（`600522`），不是新浪那种 `sh600522`；两种写法都得判对，
 * 否则创业板会被当成 10% 主板——龙头封板就会漏判。
 */
class LimitUpThresholdTest {

    @Test
    fun limitPctAcceptsPrefixedAndBareCodesAlike() {
        assertEquals(10.0, SinaClient.limitPctFor("sh600522"), 0.001)
        assertEquals(10.0, SinaClient.limitPctFor("600522"), 0.001)
        assertEquals(20.0, SinaClient.limitPctFor("sz300750"), 0.001)
        assertEquals(20.0, SinaClient.limitPctFor("300750"), 0.001)
        assertEquals(20.0, SinaClient.limitPctFor("sh688001"), 0.001)
        assertEquals(20.0, SinaClient.limitPctFor("688001"), 0.001)
        assertEquals(30.0, SinaClient.limitPctFor("830799"), 0.001)
        assertEquals(30.0, SinaClient.limitPctFor("920001"), 0.001)
    }
}
