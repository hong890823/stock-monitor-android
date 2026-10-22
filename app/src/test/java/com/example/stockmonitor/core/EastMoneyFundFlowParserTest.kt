package com.example.stockmonitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EastMoneyFundFlowParserTest {

    @Test
    fun parsesMainAndOrderSizeBuckets() {
        val parsed = EastMoneyFundFlowClient.parse(
            """
            callback({"data":{"diff":[{"f12":"000001","f14":"平安银行","f6":123000000.0,"f62":13039016.0,"f184":55.57,"f66":21424170.0,"f69":31.2,"f72":-8385154.0,"f75":-12.1,"f78":2100000.0,"f81":3.1,"f84":-3200000.0,"f87":-4.6}]}})
            """.trimIndent(),
        )

        val flow = parsed.getValue("000001")
        assertEquals("平安银行", flow.name)
        assertEquals(123_000_000.0, flow.amountYuan!!, 0.01)
        assertEquals(13_039_016.0, flow.mainNetYuan!!, 0.01)
        assertEquals(55.57, flow.mainNetPct!!, 0.001)
        assertEquals(21_424_170.0, flow.superLargeNetYuan!!, 0.01)
        assertEquals(-8_385_154.0, flow.largeNetYuan!!, 0.01)
    }

    @Test
    fun missingBucketStaysNull() {
        val parsed = EastMoneyFundFlowClient.parse(
            """{"data":{"diff":[{"f12":"600293","f14":"南矿集团","f6":"-","f62":"-","f184":"-"}]}}""",
        )

        val flow = parsed.getValue("600293")
        assertNull(flow.amountYuan)
        assertNull(flow.mainNetYuan)
        assertNull(flow.mainNetPct)
    }

    @Test
    fun convertsCodesToEastMoneySecids() {
        assertEquals(
            listOf("1.600000", "0.000001", "1.688001"),
            EastMoneyFundFlowClient.secids(listOf("sh600000", "sz000001", "688001")),
        )
    }
}
