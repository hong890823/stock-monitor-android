package com.example.stockmonitor.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class AlertRecord(
    val id: String,
    val notificationId: Int,
    val kind: String,
    val level: String,
    val triggerType: String,
    val title: String,
    val body: String,
    val createdAtMs: Long,
)

object AlertRecordBook {
    const val MAX_ENTRIES = 200
    const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000

    fun append(
        existing: List<AlertRecord>,
        record: AlertRecord,
        nowMs: Long = record.createdAtMs,
    ): List<AlertRecord> = upsert(existing, record, nowMs)

    fun upsert(
        existing: List<AlertRecord>,
        record: AlertRecord,
        nowMs: Long,
    ): List<AlertRecord> =
        prune(existing.filterNot { it.id == record.id } + record, nowMs)

    fun prune(records: List<AlertRecord>, nowMs: Long): List<AlertRecord> =
        records
            .sortedBy { it.createdAtMs }
            .filter { nowMs - it.createdAtMs < RETENTION_MS }
            .takeLast(MAX_ENTRIES)

    fun replayRecord(
        persisted: AlertRecord?,
        intentFallback: AlertRecord?,
    ): AlertRecord? =
        persisted?.takeIf(::isComplete)
            ?: intentFallback?.takeIf(::isComplete)

    fun encode(records: List<AlertRecord>): String = JSONArray().apply {
        records.forEach { record ->
            put(
                JSONObject()
                    .put("id", record.id)
                    .put("notification_id", record.notificationId)
                    .put("kind", record.kind)
                    .put("level", record.level)
                    .put("trigger_type", record.triggerType)
                    .put("title", record.title)
                    .put("body", record.body)
                    .put("created_at_ms", record.createdAtMs),
            )
        }
    }.toString()

    fun decode(raw: String?): List<AlertRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                AlertRecord(
                    id = item.optString("id"),
                    notificationId = item.optInt("notification_id"),
                    kind = item.optString("kind"),
                    level = item.optString("level"),
                    triggerType = item.optString("trigger_type"),
                    title = item.optString("title"),
                    body = item.optString("body"),
                    createdAtMs = item.optLong("created_at_ms"),
                ).takeIf { it.id.isNotBlank() && it.title.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    fun find(records: List<AlertRecord>, id: String?): AlertRecord? =
        records.firstOrNull { it.id == id }

    fun detailText(record: AlertRecord): String = buildString {
        appendLine("时间：${formatTime(record.createdAtMs)}")
        appendLine("类型：${kindLabel(record.kind)}")
        if (record.level.isNotBlank()) appendLine("级别：${record.level}")
        if (record.triggerType.isNotBlank()) appendLine("触发：${record.triggerType}")
        appendLine()
        append(record.body)
    }

    fun kindLabel(kind: String): String = when (kind) {
        "market_risk" -> "大盘风险"
        "risk_sell" -> "持仓风险"
        "signal" -> "盘中信号"
        "alert" -> "行情提醒"
        "analysis_complete" -> "分析完成"
        "analysis_failed" -> "分析失败"
        else -> kind.ifBlank { "提醒" }
    }

    fun formatTime(timestampMs: Long): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"))
            .format(Instant.ofEpochMilli(timestampMs))

    private fun isComplete(record: AlertRecord): Boolean =
        record.id.isNotBlank() &&
            record.title.isNotBlank() &&
            record.body.isNotBlank()
}
