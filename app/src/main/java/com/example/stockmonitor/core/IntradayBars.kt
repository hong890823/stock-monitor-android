package com.example.stockmonitor.core

import java.time.LocalDateTime
import org.json.JSONArray

enum class MinuteTrend {
    UP,
    DOWN,
    NEUTRAL,
    UNKNOWN,
}

data class IntradayBar(
    val timestamp: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val completed: Boolean = true,
) {
    val valid: Boolean
        get() = timestamp.isNotBlank() &&
            listOf(open, high, low, close, volume).all { it.isFinite() } &&
            open > 0.0 && high > 0.0 && low > 0.0 && close > 0.0 && volume >= 0.0 &&
            high >= maxOf(open, low, close) && low <= minOf(open, high, close)
}

data class IntradayBarWindow(val bars: List<IntradayBar>) {
    val complete: Boolean
        get() = bars.size >= 2 && bars.all { it.valid && it.completed } &&
            bars.zipWithNext().all { (previous, current) -> previous.timestamp < current.timestamp }

    val trend: MinuteTrend
        get() {
            if (!complete) return MinuteTrend.UNKNOWN
            val closes = bars.map { it.close }
            val last = closes.last()
            val previous = closes[closes.lastIndex - 1]
            val reference = closes.getOrNull(closes.lastIndex - 2) ?: previous
            return when {
                last > previous && last >= reference -> MinuteTrend.UP
                last < previous && last <= reference -> MinuteTrend.DOWN
                else -> MinuteTrend.NEUTRAL
            }
        }

    fun upsert(bar: IntradayBar, limit: Int = 240): IntradayBarWindow {
        require(bar.valid) { "无效分钟K线" }
        require(limit > 0) { "K线数量必须大于0" }
        val updated = bars.filterNot { it.timestamp == bar.timestamp } + bar
        return IntradayBarWindow(updated.sortedBy { it.timestamp }.takeLast(limit))
    }

    fun completedAt(now: LocalDateTime, quoteDate: String?, minutes: Int): IntradayBarWindow {
        val latest = TimeframeEvidence.latestCompletedSlot(now, minutes, quoteDate) ?: return IntradayBarWindow(emptyList())
        return IntradayBarWindow(bars.filter { it.valid && it.completed && it.timestamp <= latest })
    }

    fun withCompletionState(now: LocalDateTime, quoteDate: String?, minutes: Int): IntradayBarWindow {
        val latest = TimeframeEvidence.latestCompletedSlot(now, minutes, quoteDate)
            ?: return IntradayBarWindow(bars.map { it.copy(completed = false) })
        return IntradayBarWindow(bars.map { it.copy(completed = it.timestamp <= latest) })
    }
}

data class DualTimeframeDecision(
    val allowed: Boolean,
    val reason: String,
)

object DualTimeframePolicy {
    fun decide(
        riskSignal: Boolean,
        execution: IntradayBarWindow,
        direction: IntradayBarWindow,
    ): DualTimeframeDecision {
        if (riskSignal) return DualTimeframeDecision(true, "风险卖出以15分钟执行信号为准")
        if (!execution.complete || !direction.complete) {
            return DualTimeframeDecision(false, "15/30分钟数据不完整，仅观察，不执行买入或做T")
        }
        return when (direction.trend) {
            MinuteTrend.DOWN -> DualTimeframeDecision(false, "30分钟方向向下，仅观察，不执行买入或做T")
            MinuteTrend.UNKNOWN -> DualTimeframeDecision(false, "30分钟方向待确认，仅观察")
            MinuteTrend.UP, MinuteTrend.NEUTRAL -> DualTimeframeDecision(true, "15分钟触发且30分钟不反向")
        }
    }
}

private val INTRADAY_TIMESTAMP = Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")

internal fun String.intradayFiniteDoubleOrNull(): Double? =
    toDoubleOrNull()?.takeIf { it.isFinite() }

internal fun parseIntradayBars(
    payload: String,
    code: String,
    minutes: Int,
): List<IntradayBar> {
    require(minutes == 15 || minutes == 30) { "分钟K线仅支持15或30分钟" }
    val array = JSONArray(payload)
    val bars = ArrayList<IntradayBar>(array.length())
    for (index in 0 until array.length()) {
        val row = array.optJSONObject(index) ?: continue
        val timestamp = row.optString("day").trim()
        if (!timestamp.matches(INTRADAY_TIMESTAMP)) continue
        val open = row.optString("open").intradayFiniteDoubleOrNull() ?: continue
        val high = row.optString("high").intradayFiniteDoubleOrNull() ?: continue
        val low = row.optString("low").intradayFiniteDoubleOrNull() ?: continue
        val close = row.optString("close").intradayFiniteDoubleOrNull() ?: continue
        val volume = row.optString("volume").intradayFiniteDoubleOrNull() ?: continue
        val bar = IntradayBar(
            timestamp = timestamp,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
        )
        if (bar.valid) bars.add(bar)
    }
    return bars.distinctBy { it.timestamp }.sortedBy { it.timestamp }.also {
        if (it.isEmpty() && array.length() > 0) {
            throw IllegalArgumentException("$code ${minutes}分钟K线无有效数据")
        }
    }
}
