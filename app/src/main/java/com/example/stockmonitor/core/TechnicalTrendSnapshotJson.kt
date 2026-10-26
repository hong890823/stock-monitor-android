package com.example.stockmonitor.core

import com.example.stockmonitor.analysis.SwingTrend
import com.example.stockmonitor.analysis.TechnicalTrendSnapshot
import org.json.JSONArray
import org.json.JSONObject

/** 技术趋势缓存的稳定落盘格式；重启后自选股/持仓页可先显示上一次评估。 */
object TechnicalTrendSnapshotJson {
    fun encode(snapshots: Map<String, TechnicalTrendSnapshot>): String =
        JSONArray().also { array ->
            snapshots.values.sortedBy { it.code }.forEach { snapshot ->
                array.put(
                    JSONObject()
                        .put("code", snapshot.code)
                        .put("name", snapshot.name)
                        .put("as_of", snapshot.asOf)
                        .put("technical_score", snapshot.technicalScore)
                        .put("trend_points", snapshot.trendPoints)
                        .put("structure_points", snapshot.structurePoints)
                        .put("momentum_points", snapshot.momentumPoints)
                        .put("risk_points", snapshot.riskPoints)
                        .put("weekly_trend", snapshot.weeklyTrend.name)
                        .put("monthly_trend", snapshot.monthlyTrend.name)
                        .put("trend_weak", snapshot.trendWeak)
                        .put("allows_add_position", snapshot.allowsAddPosition)
                        .put("primary_advice", snapshot.primaryAdvice)
                        .put("advice_reason", snapshot.adviceReason),
                )
            }
        }.toString()

    fun decode(raw: String?): Map<String, TechnicalTrendSnapshot> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val array = JSONArray(raw)
            buildMap {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val code = json.optString("code").takeIf { it.isNotBlank() } ?: continue
                    val snapshot = TechnicalTrendSnapshot(
                        code = code,
                        name = json.optString("name"),
                        asOf = json.optString("as_of"),
                        technicalScore = json.optInt("technical_score"),
                        trendPoints = json.optInt("trend_points"),
                        structurePoints = json.optInt("structure_points"),
                        momentumPoints = json.optInt("momentum_points"),
                        riskPoints = json.optInt("risk_points"),
                        weeklyTrend = enumValueOrDefault(json.optString("weekly_trend"), SwingTrend.MISSING),
                        monthlyTrend = enumValueOrDefault(json.optString("monthly_trend"), SwingTrend.MISSING),
                        trendWeak = json.optBoolean("trend_weak"),
                        allowsAddPosition = json.optBoolean("allows_add_position"),
                        primaryAdvice = json.optString("primary_advice", "暂无操作建议，等待重新计算"),
                        adviceReason = json.optString("advice_reason", "旧缓存未包含评分依据，等待重新计算"),
                    )
                    put(code, snapshot)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: default
}
