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
                        .put("advice_reason", snapshot.adviceReason)
                        .put("price", snapshot.price.finiteOrZero())
                        .put("support", snapshot.support.finiteOrZero())
                        .put("resistance", snapshot.resistance.finiteOrZero())
                        .put("ma5", snapshot.ma5.finiteOrZero())
                        .put("ma10", snapshot.ma10.finiteOrZero())
                        .put("ma20", snapshot.ma20.finiteOrZero())
                        .put("ma60", snapshot.ma60.finiteOrZero())
                        .put("volume_ratio", snapshot.volumeRatio ?: JSONObject.NULL)
                        .put("atr_pct", snapshot.atrPct ?: JSONObject.NULL)
                        .put("chip_line", snapshot.chipLine ?: JSONObject.NULL)
                        .put("chip_trapped_line", snapshot.chipTrappedLine ?: JSONObject.NULL)
                        .put("momentum_line", snapshot.momentumLine ?: JSONObject.NULL),
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
                        // 旧缓存没有价位字段：0/null 落到「暂缺」语义，等下一次评估覆盖。
                        price = json.optDouble("price", 0.0).takeIf { it.isFinite() } ?: 0.0,
                        support = json.optDouble("support", 0.0).takeIf { it.isFinite() } ?: 0.0,
                        resistance = json.optDouble("resistance", 0.0).takeIf { it.isFinite() } ?: 0.0,
                        ma5 = json.optDouble("ma5", 0.0).takeIf { it.isFinite() } ?: 0.0,
                        ma10 = json.optDouble("ma10", 0.0).takeIf { it.isFinite() } ?: 0.0,
                        ma20 = json.optDouble("ma20", 0.0).takeIf { it.isFinite() } ?: 0.0,
                        ma60 = json.optDouble("ma60", 0.0).takeIf { it.isFinite() } ?: 0.0,
                        volumeRatio = json.optDoubleOrNull("volume_ratio"),
                        atrPct = json.optDoubleOrNull("atr_pct"),
                        chipLine = json.optNullableString("chip_line"),
                        chipTrappedLine = json.optNullableString("chip_trapped_line"),
                        momentumLine = json.optNullableString("momentum_line"),
                    )
                    put(code, snapshot)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: default

    private fun Double.finiteOrZero(): Double = takeIf { it.isFinite() } ?: 0.0

    /** null/缺失/非数值都返回 null；optDouble 对 NULL 值的返回值在不同实现里有差异，这里显式判空。 */
    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val value = optDouble(key, Double.NaN)
        return value.takeIf { it.isFinite() }
    }

    /** null/缺失/空白都返回 null。 */
    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }
}
