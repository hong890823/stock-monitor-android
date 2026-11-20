package com.example.stockmonitor.analysis

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 启动初期发现原型（仅测试源码，未接入生产链路）。
 *
 * 条件：收盘价突破前 20 个交易日最高收盘价、当日涨幅 >= 3%、
 * 5 分钟量 >= 前 6 根均量 2 倍、动态 MA5 乖离 <= 8%、非涨停；每日一次。
 */
internal object LaunchDiscoveryPrototype {
    data class Bar(
        val time: String,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
    )

    data class Fixture(val dates: List<String>, val closes: List<Double>, val bars: List<Bar>)

    data class Trigger(val time: String, val price: Double, val biasPct: Double)

    fun loadFixture(code: String): Fixture {
        val stream = requireNotNull(LaunchDiscoveryPrototype::class.java.getResourceAsStream("/entry-timing-2026-11-19/$code.json"))
        val json = stream.bufferedReader().use { JSONObject(it.readText()) }
        val daily = json.getJSONArray("daily")
        val minutes = json.getJSONArray("minutes")
        val dates = (0 until daily.length()).map { daily.getJSONArray(it).getString(0) }
        val closes = (0 until daily.length()).map { daily.getJSONArray(it).getDouble(2) }
        val bars = (0 until minutes.length()).map { index ->
            val row = minutes.getJSONArray(index)
            Bar(
                time = row.getString(0).substring(0, 16),
                open = row.getDouble(1),
                high = row.getDouble(2),
                low = row.getDouble(3),
                close = row.getDouble(4),
                volume = row.getDouble(5),
            )
        }
        return Fixture(dates, closes, bars)
    }

    fun replay(code: String): List<Trigger> {
        val fixture = loadFixture(code)
        val triggers = mutableListOf<Trigger>()
        val days = fixture.bars.map { it.time.substring(0, 10) }.distinct().sorted()
        for (day in days) {
            val dayIndex = fixture.dates.indexOf(day)
            if (dayIndex <= 4) continue
            val previousClose = fixture.closes[dayIndex - 1]
            val platform = fixture.closes.subList(maxOf(0, dayIndex - 20), dayIndex).max()
            val priorCloses = fixture.closes.subList(dayIndex - 4, dayIndex)
            val limit = limitPrice(previousClose)
            val dayBars = fixture.bars.filter { it.time.startsWith(day) }
            for (index in 3 until dayBars.size) {
                val bar = dayBars[index]
                val baseline = dayBars.subList(maxOf(0, index - 6), index).map { it.volume }
                val baselineAverage = baseline.average()
                val dayChangePct = (bar.close / previousClose - 1.0) * 100.0
                val ma5 = (priorCloses.sum() + bar.close) / 5.0
                val biasPct = (bar.close / ma5 - 1.0) * 100.0
                val qualified = bar.close > platform &&
                    bar.volume >= 2 * baselineAverage &&
                    dayChangePct >= 3.0 &&
                    biasPct <= 8.0 &&
                    bar.close < limit - 0.005
                if (qualified) {
                    triggers += Trigger(bar.time, bar.close, biasPct)
                    break
                }
            }
        }
        return triggers
    }

    fun qualifyingDailyDates(code: String): List<String> {
        val fixture = loadFixture(code)
        return fixture.dates.indices.filter { index ->
            if (index < 5) return@filter false
            val platform = fixture.closes.subList(maxOf(0, index - 20), index).max()
            val priorCloses = fixture.closes.subList(index - 4, index)
            val close = fixture.closes[index]
            close > platform &&
                (close / fixture.closes[index - 1] - 1.0) * 100.0 >= 3.0 &&
                (close / ((priorCloses.sum() + close) / 5.0) - 1.0) * 100.0 <= 8.0
        }.map { fixture.dates[it] }
    }

    fun limitPrice(previousClose: Double): Double =
        BigDecimal.valueOf(previousClose).multiply(BigDecimal("1.10"))
            .setScale(2, RoundingMode.HALF_UP).toDouble()
}
