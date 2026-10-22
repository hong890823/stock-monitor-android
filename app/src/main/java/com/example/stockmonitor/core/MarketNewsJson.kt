package com.example.stockmonitor.core

import com.example.stockmonitor.analysis.NewsCategory
import com.example.stockmonitor.analysis.SelectedHeadline
import org.json.JSONArray
import org.json.JSONObject

object MarketNewsJson {
    fun encode(items: List<SelectedHeadline>, updatedAtMs: Long, lastAttemptAtMs: Long): String =
        JSONObject()
            .put("updated_at_ms", updatedAtMs)
            .put("last_attempt_at_ms", lastAttemptAtMs)
            .put(
                "items",
                JSONArray().apply {
                    items.forEach { selected ->
                        put(
                            JSONObject()
                                .put("title", selected.item.title)
                                .put("published_at_ms", selected.item.publishedAtMs)
                                .put("source", selected.item.source)
                                .put("url", selected.item.url)
                                .put("raw_category", selected.item.rawCategory)
                                .put("category", selected.category.name),
                        )
                    }
                },
            )
            .toString()

    fun decode(json: String?): TodayHeadlinesSnapshot? {
        if (json.isNullOrBlank()) return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val rows = root.optJSONArray("items") ?: return null
        val items = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val title = row.optString("title").trim()
            val publishedAtMs = row.optLong("published_at_ms")
            if (title.isBlank() || publishedAtMs <= 0L) return@mapNotNull null
            val category = runCatching {
                NewsCategory.valueOf(row.optString("category", NewsCategory.MARKET_GENERAL.name))
            }.getOrDefault(NewsCategory.MARKET_GENERAL)
            SelectedHeadline(
                NewsItem(
                    title = title,
                    publishedAtMs = publishedAtMs,
                    source = if (row.isNull("source")) "公开财经快讯" else row.optString("source").ifBlank { "公开财经快讯" },
                    url = if (row.isNull("url")) null else row.optString("url").takeIf { it.isNotBlank() },
                    rawCategory = if (row.isNull("raw_category")) null else row.optString("raw_category").takeIf { it.isNotBlank() },
                ),
                category,
            )
        }
        return TodayHeadlinesSnapshot(
            items = items,
            updatedAtMs = root.optLong("updated_at_ms"),
            lastAttemptAtMs = root.optLong("last_attempt_at_ms"),
        )
    }
}

data class TodayHeadlinesSnapshot(
    val items: List<SelectedHeadline>,
    val updatedAtMs: Long,
    val lastAttemptAtMs: Long,
)
