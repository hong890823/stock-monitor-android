package com.example.stockmonitor.core

import com.example.stockmonitor.analysis.BoardKind
import com.example.stockmonitor.analysis.SectorStrengthDay
import com.example.stockmonitor.analysis.SectorStrengthPoint
import org.json.JSONArray
import org.json.JSONObject

object SectorStrengthHistoryJson {
    fun encode(history: List<SectorStrengthDay>): String =
        JSONArray().also { root ->
            history.sortedBy { it.date }.forEach { day ->
                root.put(
                    JSONObject()
                        .put("date", day.date)
                        .put(
                            "points",
                            JSONArray().also { points ->
                                day.points.forEach { point ->
                                    points.put(
                                        JSONObject()
                                            .put("kind", point.kind.name)
                                            .put("date", point.date)
                                            .put("name", point.name)
                                            .putIfPresent("code", point.code)
                                            .putIfFinite("change_pct", point.changePct)
                                            .putIfFinite("change_rank_pct", point.changeRankPct)
                                            .putIfFinite("turnover_rank_pct", point.turnoverRankPct)
                                            .putIfFinite("main_net_ratio_pct", point.mainNetRatioPct),
                                    )
                                }
                            },
                        ),
                )
            }
        }.toString()

    fun decode(raw: String?): List<SectorStrengthDay> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val root = JSONArray(raw)
            (0 until root.length()).mapNotNull { index ->
                val day = root.optJSONObject(index) ?: return@mapNotNull null
                val points = day.optJSONArray("points") ?: JSONArray()
                SectorStrengthDay(
                    date = day.optString("date"),
                    points = (0 until points.length()).mapNotNull { pointIndex ->
                        val point = points.optJSONObject(pointIndex) ?: return@mapNotNull null
                        SectorStrengthPoint(
                            kind = runCatching {
                                BoardKind.valueOf(point.optString("kind"))
                            }.getOrDefault(BoardKind.INDUSTRY),
                            date = point.optString("date", day.optString("date")),
                            name = point.optString("name"),
                            code = point.optString("code").takeIf { it.isNotBlank() },
                            changePct = point.optDouble("change_pct"),
                            changeRankPct = point.optDoubleOrNull("change_rank_pct"),
                            turnoverRankPct = point.optDoubleOrNull("turnover_rank_pct"),
                            mainNetRatioPct = point.optDoubleOrNull("main_net_ratio_pct"),
                        )
                    },
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun JSONObject.putIfPresent(key: String, value: String?): JSONObject =
        if (value.isNullOrBlank()) this else put(key, value)

    private fun JSONObject.putIfFinite(key: String, value: Double?): JSONObject =
        if (value != null && value.isFinite()) put(key, value) else this

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key) else null
}
