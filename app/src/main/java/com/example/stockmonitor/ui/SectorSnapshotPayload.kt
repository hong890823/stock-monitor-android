package com.example.stockmonitor.ui

import com.example.stockmonitor.analysis.MarketBreadthFreshnessPolicy
import com.example.stockmonitor.core.ConceptItem
import com.example.stockmonitor.core.SectorItem
import org.json.JSONArray
import org.json.JSONObject

object SectorSnapshotPayload {
    fun build(
        query: String,
        industries: List<SectorItem>,
        concepts: List<ConceptItem>,
        industryUpdatedAtMs: Long,
        conceptUpdatedAtMs: Long,
        nowMs: Long,
        conceptRisingCount: Int? = null,
        conceptFallingCount: Int? = null,
        limit: Int = 5,
        includeRankings: Boolean = true,
    ): JSONObject {
        val normalized = normalize(query)
        val industryMatches = matching(industries, normalized) { it.name }
        val conceptMatches = matching(concepts, normalized) { it.name }
        val gaps = mutableListOf<String>()
        if (industries.isEmpty()) gaps += "行业板块数据暂缺"
        if (concepts.isEmpty()) gaps += "概念板块数据暂缺"
        if (normalized.isNotBlank() && industryMatches.isEmpty() && conceptMatches.isEmpty()) {
            gaps += "没有找到与“$query”匹配的行业或概念板块"
        }
        val industryStale = stale(industryUpdatedAtMs, nowMs)
        val conceptStale = stale(conceptUpdatedAtMs, nowMs)
        if (industries.isNotEmpty() && industryStale) gaps += "行业板块数据超过有效期"
        if (concepts.isNotEmpty() && conceptStale) gaps += "概念板块数据超过有效期"

        val result = JSONObject()
            .put("query", query)
            .put("industry_matches", JSONArray(industryMatches.map { sectorJson(it, industryUpdatedAtMs) }))
            .put("concept_matches", JSONArray(conceptMatches.map { conceptJson(it, conceptUpdatedAtMs) }))
            .put(
                "breadth",
                JSONObject()
                    .put(
                        "industry",
                        breadthJson(
                            rising = industries.count { it.change > 0 },
                            falling = industries.count { it.change < 0 },
                            total = industries.size,
                        ),
                    )
                    .put(
                        "concept",
                        conceptBreadthJson(
                            concepts = concepts,
                            explicitRising = conceptRisingCount,
                            explicitFalling = conceptFallingCount,
                        ),
                    ),
            )
            .put(
                "dataset_sources",
                JSONObject()
                    .put(
                        "industry",
                        JSONObject()
                            .put("source", "程序行业板块缓存")
                            .put("updated_at_ms", industryUpdatedAtMs),
                    )
                    .put(
                        "concept",
                        JSONObject()
                            .put("source", "程序概念汇总缓存")
                            .put("updated_at_ms", conceptUpdatedAtMs),
                    ),
            )
            .put(
                "freshness",
                JSONObject()
                    .put("industry_updated_at_ms", industryUpdatedAtMs)
                    .put("concept_updated_at_ms", conceptUpdatedAtMs)
                    .put("stale_after_ms", MarketBreadthFreshnessPolicy.MAX_AGE_MS)
                    .put("industry_stale", industryStale)
                    .put("concept_stale", conceptStale),
            )
            .put("gaps", JSONArray(gaps))
            .put("notice", "这是板块快照，不代表其中任意个股的买卖结论。数据超期时必须按缺失处理。")
        if (includeRankings) {
            val safeLimit = limit.coerceIn(1, 20)
            result
                .put("industry_top", JSONArray(industries.sortedByDescending { it.change }.take(safeLimit).map { sectorJson(it, industryUpdatedAtMs) }))
                .put("industry_bottom", JSONArray(industries.sortedBy { it.change }.take(safeLimit).map { sectorJson(it, industryUpdatedAtMs) }))
                .put("concept_top", JSONArray(concepts.sortedByDescending { it.change }.take(safeLimit).map { conceptJson(it, conceptUpdatedAtMs) }))
                .put("concept_bottom", JSONArray(concepts.sortedBy { it.change }.take(safeLimit).map { conceptJson(it, conceptUpdatedAtMs) }))
        }
        return result
    }

    private fun normalize(query: String): String = query.lowercase()
        .replace(Regex("[\\s·、，,。\\-_/（）()]"), "")
        .replace("概念板块", "")
        .replace("行业板块", "")
        .replace("概念股", "")
        .replace("行业", "")
        .replace("概念", "")
        .replace("板块", "")

    private fun <T> matching(items: List<T>, normalized: String, name: (T) -> String): List<T> =
        if (normalized.isBlank()) items else items.filter {
            val itemName = name(it).lowercase()
            itemName.contains(normalized) || normalized.contains(itemName)
        }

    private fun stale(updatedAtMs: Long, nowMs: Long): Boolean =
        updatedAtMs <= 0L || nowMs - updatedAtMs !in 0..MarketBreadthFreshnessPolicy.MAX_AGE_MS

    private fun breadthJson(rising: Int, falling: Int, total: Int): JSONObject = JSONObject()
        .put("rising", rising)
        .put("falling", falling.coerceAtLeast(0))
        .put("flat", (total - rising - falling).coerceAtLeast(0))
        .put("total", total)
        .put("ratio", if (total > 0) rising.toDouble() / total else JSONObject.NULL)

    private fun conceptBreadthJson(
        concepts: List<ConceptItem>,
        explicitRising: Int?,
        explicitFalling: Int?,
    ): JSONObject {
        if (concepts.isNotEmpty()) {
            val rising = concepts.count { it.change > 0 }
            val falling = concepts.count { it.change < 0 }
            return breadthJson(rising, falling, concepts.size)
        }
        val rising = explicitRising ?: 0
        val falling = explicitFalling ?: 0
        val total = rising + falling
        return breadthJson(rising, falling, total)
    }

    private fun sectorJson(item: SectorItem, updatedAtMs: Long): JSONObject = JSONObject()
        .put("name", item.name)
        .put("change_pct", item.change)
        .put("constituent_count", item.count)
        .put("volume", item.volume)
        .put("lead", item.lead)
        .put("lead_change_pct", item.leadPct)
        .put("source", "程序行业板块缓存")
        .put("updated_at_ms", updatedAtMs)

    private fun conceptJson(item: ConceptItem, updatedAtMs: Long): JSONObject = JSONObject()
        .put("name", item.name)
        .put("code", item.code)
        .put("change_pct", item.change)
        .put("volume", item.volume)
        .put("lead", item.lead)
        .put("lead_change_pct", item.leadPct)
        .put("source", "程序概念汇总缓存")
        .put("updated_at_ms", updatedAtMs)
}
