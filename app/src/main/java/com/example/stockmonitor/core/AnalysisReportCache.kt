package com.example.stockmonitor.core

import org.json.JSONObject

/**
 * 个股报告缓存（`Store.analysisReports`）。
 *
 * [snapshot] 是上次分析时的状态快照，用来写「较上次分析」——个股报告是手动触发的，
 * 上次可能是三小时前也可能是昨天，所以叫「较上次」而不是「较昨日」，并把时刻一并带上。
 */
data class CachedAnalysisReports(
    val code: String,
    val name: String,
    val technicalReport: String?,
    val fundamentalReport: String?,
    val updatedAt: String,
    val snapshot: RowSnapshot? = null,
)

object AnalysisReportCache {
    fun get(raw: String, code: String): CachedAnalysisReports? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val item = root.optJSONObject(code) ?: return null
        return CachedAnalysisReports(
            code = code,
            name = item.optString("name", code),
            technicalReport = item.optString("technical_report").takeIf { it.isNotBlank() },
            fundamentalReport = item.optString("fundamental_report").takeIf { it.isNotBlank() },
            updatedAt = item.optString("updated_at"),
            snapshot = decodeRow(item.optJSONObject("snapshot")),
        )
    }

    fun merge(
        raw: String,
        code: String,
        name: String,
        technical: String?,
        fundamental: String?,
        updatedAt: String,
        snapshot: RowSnapshot? = null,
    ): String {
        val root = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        val previous = root.optJSONObject(code)
        root.put(
            code,
            JSONObject()
                .put("name", name.ifBlank { previous?.optString("name", code) ?: code })
                .put("technical_report", technical ?: previous?.optString("technical_report").orEmpty())
                .put("fundamental_report", fundamental ?: previous?.optString("fundamental_report").orEmpty())
                .put("updated_at", updatedAt)
                // 没给新快照就沿用旧的，别把「较上次」的对照数据弄丢。
                .put("snapshot", snapshot?.let { encodeRow(it) } ?: previous?.optJSONObject("snapshot")),
        )
        return root.toString()
    }

    private fun encodeRow(row: RowSnapshot): JSONObject = JSONObject()
        .put("code", row.code)
        .put("signal", row.signal)
        .put("score", row.score)
        .put("support", row.support)
        .put("resistance", row.resistance)
        .put("price", row.price)
        .put("change_pct", row.changePct)
        .put("volume_ratio", row.volumeRatio)

    private fun decodeRow(obj: JSONObject?): RowSnapshot? {
        if (obj == null) return null
        return RowSnapshot(
            code = obj.optString("code"),
            signal = obj.optString("signal"),
            score = obj.optInt("score"),
            support = obj.optDouble("support", 0.0),
            resistance = obj.optDouble("resistance", 0.0),
            price = obj.optDouble("price", 0.0),
            changePct = obj.optDouble("change_pct", 0.0),
            volumeRatio = obj.optDouble("volume_ratio", 0.0),
        )
    }
}
