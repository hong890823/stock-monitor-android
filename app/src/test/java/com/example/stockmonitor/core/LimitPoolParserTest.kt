package com.example.stockmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitPoolParserTest {
    @Test
    fun parserExtractsSealStrengthOpeningCountAndSealTimes() {
        val clientClass =
            runCatching { Class.forName("com.example.stockmonitor.core.EastMoneyLimitClient") }
                .getOrNull()
        assertNotNull("应提供东方财富涨跌停池解析器", clientClass)
        val client = clientClass!!.getDeclaredConstructor().newInstance()
        val method =
            clientClass.methods.singleOrNull {
                it.name == "parsePools" && it.parameterCount == 2
            }
        assertNotNull("解析器应提供 parsePools(upJson, downJson)", method)

        val data = method!!.invoke(client, UP_POOL, DOWN_POOL) as LimitData
        val up = data.up.single()
        val down = data.down.single()

        assertEquals("sz001360", up.code)
        assertEquals("南矿集团", up.name)
        assertEquals(18.58, up.price, 0.001)
        assertEquals(326_898_805.0, metric(up, "getSealAmountYuan") as Double, 0.01)
        assertEquals(23_462_397.0, metric(up, "getTurnoverYuan") as Double, 0.01)
        assertEquals(2_331_499_297.32, metric(up, "getFloatMarketValueYuan") as Double, 0.01)
        assertEquals(0, metric(up, "getOpenCount"))
        assertEquals("09:25:00", metric(up, "getFirstSealTime"))
        assertEquals("09:25:00", metric(up, "getLastSealTime"))
        assertEquals(1, metric(up, "getLimitStreak"))
        assertEquals(1393.288, metric(up, "getSealToTurnoverPct") as Double, 0.01)
        assertEquals(14.0208, metric(up, "getSealToFloatPct") as Double, 0.001)

        assertEquals("sh688137", down.code)
        assertEquals(3_171_746.0, metric(down, "getSealAmountYuan") as Double, 0.01)
        assertEquals(0, metric(down, "getOpenCount"))
        assertNull(metric(down, "getFirstSealTime"))
        assertEquals("15:00:00", metric(down, "getLastSealTime"))
        assertTrue(metric(down, "getSealToTurnoverPct") is Double)
    }

    private fun metric(stock: LimitStock, getter: String): Any? =
        stock.javaClass.getMethod(getter).invoke(stock)

    private companion object {
        val UP_POOL =
            """
            callback({"rc":0,"data":{"tc":1,"qdate":20261022,"pool":[{
              "c":"001360","m":0,"n":"南矿集团","p":18580,"zdp":10.00592041015625,
              "amount":23462397,"ltsz":2331499297.3199999,"hs":1.0063222646713257,
              "lbc":1,"fbt":92500,"lbt":92500,"fund":326898805,"zbc":0,"hybk":"专用设备"
            }]}})
            """.trimIndent()

        val DOWN_POOL =
            """
            callback({"rc":0,"data":{"tc":1,"qdate":20261022,"pool":[{
              "c":"688137","m":1,"n":"近岸蛋白","p":106800,"zdp":-20.0,
              "amount":995743120,"ltsz":7467183980.4,"hs":12.160721778869629,
              "fund":3171746,"lbt":150000,"fba":25258626,"days":1,"oc":0,"hybk":"生物制品"
            }]}})
            """.trimIndent()
    }
}
