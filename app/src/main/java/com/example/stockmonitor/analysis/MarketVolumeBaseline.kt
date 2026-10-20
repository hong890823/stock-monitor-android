package com.example.stockmonitor.analysis

import kotlin.math.roundToInt

enum class MarketVolumeLevel {
    NONE,
    ELEVATED,
    HIGH,
    EXTREME,
}

/** 同一交易时刻的历史成交额基线，避免把早盘和尾盘混在一起比较。 */
class MarketVolumeBaseline(
    private val minimumDays: Int = MINIMUM_DAYS,
    private val maximumDays: Int = MAXIMUM_DAYS,
) {
    private val samples = linkedMapOf<String, MutableMap<Int, Double>>()

    fun record(day: String, slotMinute: Int, amount: Double) {
        if (amount <= 0.0) return
        samples.getOrPut(day) { linkedMapOf() }[slotMinute] = amount
        while (samples.size > maximumDays) {
            samples.remove(samples.keys.first())
        }
    }

    fun ratio(today: String, slotMinute: Int, currentAmount: Double): Double? {
        if (currentAmount <= 0.0) return null
        val historical = samples
            .asSequence()
            .filter { it.key < today }
            .sortedByDescending { it.key }
            .mapNotNull { it.value[slotMinute] }
            .take(maximumDays)
            .toList()
        if (historical.size < minimumDays) return null
        val baseline = historical.sorted()[historical.size / 2]
        return if (baseline > 0.0) currentAmount / baseline else null
    }

    companion object {
        const val MINIMUM_DAYS = 10
        const val MAXIMUM_DAYS = 20

        fun classify(ratio: Double?): MarketVolumeLevel = when {
            ratio == null || ratio < 1.5 -> MarketVolumeLevel.NONE
            ratio < 2.0 -> MarketVolumeLevel.ELEVATED
            ratio < 3.0 -> MarketVolumeLevel.HIGH
            else -> MarketVolumeLevel.EXTREME
        }

        fun formatRatio(ratio: Double): String = "${(ratio * 10.0).roundToInt() / 10.0}"
    }
}
