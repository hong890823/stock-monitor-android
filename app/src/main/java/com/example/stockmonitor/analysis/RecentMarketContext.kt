package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar
import org.json.JSONArray
import org.json.JSONObject

data class RecentPerformance(
    val latestDate: String?,
    val latestClose: Double?,
    private val changes: Map<Int, Double?>,
    val missingHorizons: List<Int>,
) {
    fun changePct(horizon: Int): Double? = changes[horizon]

    fun toJson(): JSONObject = JSONObject()
        .put("latest_date", latestDate ?: JSONObject.NULL)
        .put("latest_close", latestClose ?: JSONObject.NULL)
        .put("change_1d_pct", changes[1] ?: JSONObject.NULL)
        .put("change_5d_pct", changes[5] ?: JSONObject.NULL)
        .put("change_20d_pct", changes[20] ?: JSONObject.NULL)
        .put("missing_horizons", JSONArray(missingHorizons))
}

object RecentMarketContext {
    private val HORIZONS = listOf(1, 5, 20)

    fun summarize(bars: List<DayBar>): RecentPerformance {
        val ordered = bars.sortedBy { it.date }
        val latest = ordered.lastOrNull()
        val changes = HORIZONS.associateWith { horizon ->
            val previous = ordered.getOrNull(ordered.lastIndex - horizon)
            if (latest == null || previous == null || previous.close <= 0.0) {
                null
            } else {
                (latest.close / previous.close - 1.0) * 100.0
            }
        }
        return RecentPerformance(
            latestDate = latest?.date,
            latestClose = latest?.close,
            changes = changes,
            missingHorizons = changes.filterValues { it == null }.keys.toList(),
        )
    }
}
