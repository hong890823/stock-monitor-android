package com.example.stockmonitor.core

import com.example.stockmonitor.analysis.HeadlineScore
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
                                .put("category", selected.category.name)
                                .put("source_id", selected.item.sourceId)
                                .put("track", selected.track.name)
                                .put("trust_score", selected.item.trustScore)
                                .put("supporting_sources", JSONArray(selected.supportingSources))
                                .put(
                                    "score",
                                    JSONObject()
                                        .put("focus", selected.score.focus)
                                        .put("event", selected.score.event)
                                        .put("trust", selected.score.trust)
                                        .put("corroboration", selected.score.corroboration)
                                        .put("freshness", selected.score.freshness)
                                        .put("penalty", selected.score.penalty),
                                ),
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
            val source = if (row.isNull("source")) "公开财经快讯" else row.optString("source").ifBlank { "公开财经快讯" }
            val track = runCatching {
                NewsTrack.valueOf(row.optString("track", NewsTrack.INVESTMENT.name))
            }.getOrDefault(NewsTrack.INVESTMENT)
            val scoreJson = row.optJSONObject("score")
            val score = HeadlineScore(
                focus = scoreJson?.optInt("focus") ?: 0,
                event = scoreJson?.optInt("event") ?: 0,
                trust = scoreJson?.optInt("trust") ?: 0,
                corroboration = scoreJson?.optInt("corroboration") ?: 0,
                freshness = scoreJson?.optInt("freshness") ?: 0,
                penalty = scoreJson?.optInt("penalty") ?: 0,
            )
            val supportingSources = row.optJSONArray("supporting_sources")?.let { values ->
                (0 until values.length()).mapNotNull { index -> values.optString(index).takeIf { it.isNotBlank() } }
            }?.takeIf { it.isNotEmpty() } ?: listOf(source)
            SelectedHeadline(
                item = NewsItem(
                    title = title,
                    publishedAtMs = publishedAtMs,
                    source = source,
                    url = if (row.isNull("url")) null else row.optString("url").takeIf { it.isNotBlank() },
                    rawCategory = if (row.isNull("raw_category")) null else row.optString("raw_category").takeIf { it.isNotBlank() },
                    sourceId = row.optString("source_id"),
                    track = track,
                    trustScore = row.optInt("trust_score", 50),
                ),
                category = category,
                track = track,
                score = score,
                supportingSources = supportingSources,
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
