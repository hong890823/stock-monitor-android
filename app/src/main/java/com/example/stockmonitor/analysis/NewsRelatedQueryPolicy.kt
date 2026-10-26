package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.NewsItem

data class RelatedNewsMatch(
    val headline: SelectedHeadline,
    val matchedTerms: List<String>,
)

object NewsRelatedQueryPolicy {
    private val aliases = mapOf(
        "半导体" to listOf("半导体", "芯片", "集成电路", "晶圆"),
        "人工智能" to listOf("人工智能", "ai", "大模型", "算力"),
        "ai" to listOf("ai", "人工智能", "大模型", "算力"),
        "机器人" to listOf("机器人", "人形机器人", "具身智能"),
        "云计算" to listOf("云计算", "云服务", "数据中心"),
        "消费电子" to listOf("消费电子", "手机", "可穿戴"),
        "新能源" to listOf("新能源", "储能", "动力电池", "光伏", "锂电"),
        "算力" to listOf("算力", "ai", "芯片", "数据中心"),
    )

    fun query(
        items: List<NewsItem>,
        query: String,
        nowMs: Long,
        limit: Int,
        maxAgeDays: Int = 1,
    ): List<RelatedNewsMatch> {
        if (query.isBlank() || limit <= 0) return emptyList()
        val terms = queryTerms(query)
        if (terms.isEmpty()) return emptyList()
        val focus = NewsFocusContext(
            watchNames = setOf(query),
            watchCodes = setOf(query),
        )
        return HeadlineSelector.select(
            items = items,
            nowMs = nowMs,
            limit = HeadlineSelector.DETAIL_LIMIT,
            focus = focus,
            maxAgeDays = maxAgeDays,
        ).mapNotNull { headline ->
            val matched = matchedTerms(headline.item, terms)
            if (matched.isEmpty()) null else RelatedNewsMatch(headline, matched)
        }.take(limit)
    }

    private fun matchedTerms(item: NewsItem, terms: List<String>): List<String> {
        val text = normalize("${item.title} ${item.rawCategory.orEmpty()}")
        return terms.filter { normalize(it) in text }
    }

    private fun queryTerms(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        val expanded = aliases[normalize(trimmed)].orEmpty()
        return (listOf(trimmed) + expanded + trimmed.split(Regex("[\\s，、/|]+")))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[\\s\\p{Punct}，。！？；：、“”‘’（）【】《》]+"), "")
}
