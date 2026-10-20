package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.SectorItem

/** Tracks whether a sector's cumulative turnover is accelerating between snapshots. */
class SectorTurnoverTracker(
    private val maxIntervals: Int = 6,
) {
    private data class State(
        var lastTurnover: Double,
        val intervals: ArrayDeque<Double> = ArrayDeque(),
    )

    private val states = mutableMapOf<String, State>()

    fun update(items: List<SectorItem>, asOfMs: Long): Map<String, Double?> {
        val ratios = linkedMapOf<String, Double?>()
        items.forEach { item ->
            if (!item.volume.isFinite() || item.volume <= 0) return@forEach
            val state = states[item.name]
            if (state == null) {
                states[item.name] = State(item.volume)
                ratios[item.name] = null
                return@forEach
            }

            val delta = item.volume - state.lastTurnover
            state.lastTurnover = item.volume
            if (!delta.isFinite() || delta <= 0) {
                state.intervals.clear()
                ratios[item.name] = null
                return@forEach
            }

            val baseline = state.intervals.toList().medianOrNull()
            ratios[item.name] = baseline?.takeIf { it > 0 }?.let { delta / it }
            state.intervals.addLast(delta)
            while (state.intervals.size > maxIntervals) state.intervals.removeFirst()
        }
        return ratios
    }

    private fun List<Double>.medianOrNull(): Double? {
        if (isEmpty()) return null
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }
}
