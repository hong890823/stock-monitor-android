package com.example.stockmonitor

import com.example.stockmonitor.core.SinaClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 腾讯前复权日线的解析。
 *
 * 这里锁两件容易错的事：一是列顺序是「开、收、高、低、量」，和新浪的字段名不一样，
 * 按高低收的顺序读会把收盘价和最高价对调；二是成交量单位是「手」，程序内部统一用「股」，
 * 不乘 100 的话量比会小 100 倍（分母偏大算不出放量）。
 */
class TencentKlineParserTest {
    @Test
    fun swingHistoryRequiresExplicitAdjustedSeries() {
        val unadjusted = payload("""{"sh600000":{"day":[["2026-10-14","10","10","11","9","100"]]}}""")
        assertNull(SinaClient.parseSwingHistory(unadjusted, "sh600000"))
        val adjusted = unadjusted.replace("\"day\"", "\"qfqday\"")
        assertEquals(1, SinaClient.parseSwingHistory(adjusted, "sh600000")!!.size)
    }

    @Test
    fun swingHistoryRejectsMissingOpenAndInvalidVolumeRatherThanSubstituting() {
        listOf(
            "[\"2026-10-14\",\"bad\",\"10\",\"11\",\"9\",\"100\"]",
            "[\"2026-10-14\",\"10\",\"10\",\"11\",\"9\",\"bad\"]",
            "[\"2026-10-14\",\"10\",\"10\",\"11\",\"9\",\"-1\"]",
        ).forEach { row ->
            assertNull(SinaClient.parseSwingHistory(payload("""{"sh600000":{"qfqday":[$row]}}"""), "sh600000"))
        }
    }


    private fun payload(body: String): String =
        """{"code":0,"msg":"","data":$body}"""

    @Test
    fun readsColumnsInTencentOrderAndConvertsLotsToShares() {
        val text = payload(
            """{"sh601088":{"qfqday":[""" +
                """["2026-09-22","46.430","47.510","47.740","46.150","248559.000"],""" +
                """["2026-09-23","47.51","47.21","47.84","47.12","132385"]""" +
                """]}}"""
        )
        val bars = SinaClient.parseTencentQfq(text, "sh601088")!!
        assertEquals(2, bars.size)

        val first = bars[0]
        assertEquals("2026-09-22", first.date)
        assertEquals(46.430, first.open, 1e-9)
        assertEquals("第 3 列是收盘、第 4 列是最高", 47.510, first.close, 1e-9)
        assertEquals(47.740, first.high, 1e-9)
        assertEquals(46.150, first.low, 1e-9)
        assertEquals(24_855_900.0, first.volume, 1e-9)

        // 当日那行腾讯不带小数（"132385" 而不是 "132385.000"），解析要一样成立。
        val latest = bars[1]
        assertEquals("2026-09-23", latest.date)
        assertEquals(47.21, latest.close, 1e-9)
        assertEquals(13_238_500.0, latest.volume, 1e-9)
    }

    @Test
    fun indexUsesTheUnadjustedDayField() {
        // 指数没有复权概念，腾讯返回的是 day 而不是 qfqday。
        val text = payload(
            """{"sh000001":{"day":[["2026-09-23","3951.370","3936.520","3951.520","3934.460","466913933.000"]]}}"""
        )
        val bars = SinaClient.parseTencentQfq(text, "sh000001")!!
        assertEquals(1, bars.size)
        assertEquals(3936.520, bars[0].close, 1e-9)
        assertEquals(3934.460, bars[0].low, 1e-9)
        assertEquals(46_691_393_300.0, bars[0].volume, 1e-9)
    }

    @Test
    fun missingCodeOrEmptySeriesReturnsNull() {
        assertNull(SinaClient.parseTencentQfq(payload("""{"sz002185":{"qfqday":[]}}"""), "sz002185"))
        assertNull(SinaClient.parseTencentQfq(payload("""{"sz002185":{"qfqday":[]}}"""), "sh600000"))
        assertNull(SinaClient.parseTencentQfq(payload("""{"sz002185":{"qt":[]}}"""), "sz002185"))
        assertNull(SinaClient.parseTencentQfq("""{"code":1,"msg":"bad"}""", "sz002185"))
    }

    @Test
    fun skipsRowsThatAreTooShortOrUnparsable() {
        val text = payload(
            """{"sz002185":{"qfqday":[""" +
                """["2026-09-21","18.68","18.55"],""" +
                """["2026-09-22","18.910","18.280","18.950","18.160","4054468.000"],""" +
                """["2026-09-23","18.28","abc","18.62","17.82","3314654"]""" +
                """]}}"""
        )
        val bars = SinaClient.parseTencentQfq(text, "sz002185")!!
        assertEquals(1, bars.size)
        assertEquals("2026-09-22", bars[0].date)
        assertEquals(405_446_800.0, bars[0].volume, 1e-9)
    }

    @Test
    fun realResponseShapeFromTheLiveEndpointParses() {
        // 从真机 curl 抓下来的一段真实返回（截断到 3 行），确保字段名没记错。
        val text =
            """{"code":0,"msg":"","data":{"sz002185":{"qfqday":""" +
                """[["2026-06-16","17.300","17.820","18.050","17.200","3443888.000"],""" +
                """["2026-06-17","17.500","19.050","19.190","17.450","5197749.000"],""" +
                """["2026-06-18","19.300","19.610","19.940","19.050","4711825.000"]]}}}"""
        val bars = SinaClient.parseTencentQfq(text, "sz002185")!!
        assertEquals(3, bars.size)
        assertEquals(19.610, bars[2].close, 1e-9)
        assertEquals(19.940, bars[2].high, 1e-9)
        assertEquals(471_182_500.0, bars[2].volume, 1e-9)
    }
}
