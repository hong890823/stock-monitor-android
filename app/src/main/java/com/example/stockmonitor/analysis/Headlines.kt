package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.NewsItem
import com.example.stockmonitor.core.NewsTrack
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

enum class NewsCategory(val label: String, val priority: Int) {
    MACRO_POLICY("宏观/政策", 3),
    SECTOR_INDUSTRY("行业板块", 2),
    COMPANY_EVENT("公司事件", 1),
    MARKET_GENERAL("市场快讯", 0),
}

data class NewsFocusContext(
    val heldCodes: Set<String> = emptySet(),
    val heldNames: Set<String> = emptySet(),
    val watchCodes: Set<String> = emptySet(),
    val watchNames: Set<String> = emptySet(),
    val observationCodes: Set<String> = emptySet(),
    val observationNames: Set<String> = emptySet(),
)

data class HeadlineScore(
    val focus: Int = 0,
    val event: Int = 0,
    val trust: Int = 0,
    val corroboration: Int = 0,
    val freshness: Int = 0,
    val penalty: Int = 0,
) {
    val total: Int
        get() = focus + event + trust + corroboration + freshness - penalty
}

data class SelectedHeadline(
    val item: NewsItem,
    val category: NewsCategory,
    val track: NewsTrack = item.track,
    val score: HeadlineScore = HeadlineScore(),
    val supportingSources: List<String> = listOf(item.source),
)

object HeadlineSelector {
    const val CARD_LIMIT = 5
    const val CARD_LANE_LIMIT = 3
    const val DETAIL_LIMIT = 20
    const val DETAIL_LANE_LIMIT = 10
    private val shanghaiTimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    private val macroKeywords = listOf(
        "央行", "国务院", "财政部", "发改委", "证监会", "降准", "降息", "lpr", "pmi", "cpi", "ppi",
        "社融", "信贷", "关税", "汇率", "货币政策", "财政政策", "宏观经济", "稳增长", "逆周期",
    )
    private val sectorKeywords = listOf(
        "行业", "板块", "产业", "补贴", "涨价", "订单", "新能源", "半导体", "人工智能", "机器人",
        "券商", "保险", "银行", "房地产", "汽车", "医药", "消费", "有色", "化工", "军工", "科技",
    )
    private val companyKeywords = listOf(
        "业绩", "财报", "年报", "季报", "并购", "重组", "回购", "减持", "增持", "中标", "项目",
        "停牌", "复牌", "退市", "立案", "处罚", "股东", "定增", "配股", "澄清",
    )

    private val highValueThemes = listOf(
        "ai", "人工智能", "大模型", "芯片", "半导体", "算力", "机器人", "消费电子",
        "云计算", "云服务", "出口管制", "智能驾驶", "储能", "科技",
    )
    private val materialEventKeywords = listOf(
        "订单", "扩产", "产能", "量产", "并购", "收购", "重组", "中标", "获批", "出口管制",
        "禁令", "license", "acquisition", "order", "capacity", "production", "regulation",
    )
    private val promotionalKeywords = listOf(
        "新品发布会", "发布会宣传", "未来趋势", "趋势预测", "行业预测", "白皮书", "生态大会",
        "全新体验", "软文", "推广", "sponsored", "webinar", "launch event",
    )

    fun select(
        items: List<NewsItem>,
        nowMs: Long,
        limit: Int = CARD_LIMIT,
        focus: NewsFocusContext = NewsFocusContext(),
        maxAgeDays: Int = 1,
    ): List<SelectedHeadline> {
        if (limit <= 0) return emptyList()
        val candidates = items.filter { isWithinLocalDays(it.publishedAtMs, nowMs, maxAgeDays) }
            .map { SelectedHeadline(it, categoryOf(it.title, it.rawCategory)) }
        val clusters = cluster(candidates, focus)
        val scored = clusters.map { clusterItems -> scoreCluster(clusterItems, focus, nowMs) }
        return selectBalanced(scored, limit.coerceAtMost(DETAIL_LIMIT))
    }

    fun formatReport(items: List<SelectedHeadline>, updatedAtMs: Long): String = buildString {
        appendLine("今日要闻｜更新时间：${formatTime(updatedAtMs)}")
        appendLine("综合评分顺序：持仓/观察池关联 > 可投资事件 > 来源可信度 > 多源交叉 > 新鲜度。")
        appendLine("来源与发布时间按接口返回展示；分类与评分不代表确定的利好或利空方向。")
        appendLine()
        if (items.isEmpty()) {
            appendLine("暂无今日可用要闻")
            return@buildString
        }
        NewsTrack.values().forEach { track ->
            val trackItems = items.filter { it.track == track }
            if (trackItems.isEmpty()) return@forEach
            appendLine("【${track.label}】")
            trackItems.forEachIndexed { index, headline ->
                val item = headline.item
                appendLine("${index + 1}. ${item.title}")
                appendLine(
                    "   ${headline.category.label}｜${item.source}｜${formatTime(item.publishedAtMs)}｜综合评分 ${headline.score.total}",
                )
                if (headline.supportingSources.size > 1) {
                    appendLine("   多源印证：${headline.supportingSources.joinToString("、")}")
                }
                item.url?.let { appendLine("   $it") }
            }
            appendLine()
        }
    }

    private fun cluster(
        candidates: List<SelectedHeadline>,
        focus: NewsFocusContext,
    ): List<List<SelectedHeadline>> {
        val clusters = mutableListOf<MutableList<SelectedHeadline>>()
        candidates.sortedWith(
            compareByDescending<SelectedHeadline> { focusScore(it.item, focus) }
                .thenByDescending { eventScore(it.item) }
                .thenByDescending { it.item.trustScore }
                .thenByDescending { it.item.publishedAtMs },
        ).forEach { candidate ->
            val normalized = normalizeTitle(candidate.item.title)
            if (normalized.isBlank()) return@forEach
            val existing = clusters.firstOrNull { members ->
                members.any { previous -> similarity(normalized, normalizeTitle(previous.item.title)) >= SIMILARITY_THRESHOLD }
            }
            if (existing == null) {
                clusters += mutableListOf(candidate)
            } else {
                existing += candidate
            }
        }
        return clusters
    }

    private fun scoreCluster(
        clusterItems: List<SelectedHeadline>,
        focus: NewsFocusContext,
        nowMs: Long,
    ): SelectedHeadline {
        val representative = clusterItems.maxWithOrNull(
            compareByDescending<SelectedHeadline> { focusScore(it.item, focus) }
                .thenByDescending { eventScore(it.item) }
                .thenByDescending { it.item.trustScore }
                .thenByDescending { it.item.publishedAtMs },
        ) ?: clusterItems.first()
        val supportingSources = clusterItems.map { it.item.source }.distinct()
        val score = HeadlineScore(
            focus = focusScore(representative.item, focus),
            event = eventScore(representative.item),
            trust = (representative.item.trustScore.coerceIn(0, 100) * TRUST_WEIGHT / 100),
            corroboration = ((supportingSources.size - 1).coerceAtLeast(0) * CORROBORATION_STEP)
                .coerceAtMost(MAX_CORROBORATION),
            freshness = freshnessScore(representative.item.publishedAtMs, nowMs),
            penalty = promotionalPenalty(representative.item),
        )
        return representative.copy(
            score = score,
            supportingSources = supportingSources,
        )
    }

    private fun selectBalanced(items: List<SelectedHeadline>, limit: Int): List<SelectedHeadline> {
        if (items.isEmpty()) return emptyList()
        val ordered = items.sortedWith(
            compareByDescending<SelectedHeadline> { it.score.total }
                .thenByDescending { it.item.publishedAtMs }
                .thenBy { it.item.title },
        )
        if (limit < 4) return ordered.take(limit)
        val technologyQuota = limit / 2
        val investmentQuota = limit - technologyQuota
        val selected = LinkedHashSet<SelectedHeadline>()
        selected += ordered.filter { it.track == NewsTrack.INVESTMENT }.take(investmentQuota)
        selected += ordered.filter { it.track == NewsTrack.TECHNOLOGY }.take(technologyQuota)
        ordered.forEach {
            if (selected.size < limit) selected += it
        }
        return selected.toList()
    }

    private fun focusScore(item: NewsItem, focus: NewsFocusContext): Int {
        val text = normalizedComparable("${item.title} ${item.rawCategory.orEmpty()}")
        return when {
            matchesAny(text, focus.heldCodes, focus.heldNames) -> HELD_FOCUS_SCORE
            matchesAny(text, focus.watchCodes, focus.watchNames) -> WATCH_FOCUS_SCORE
            matchesAny(text, focus.observationCodes, focus.observationNames) -> OBSERVATION_FOCUS_SCORE
            else -> 0
        }
    }

    private fun matchesAny(text: String, codes: Set<String>, names: Set<String>): Boolean =
        codes.any { normalizedComparable(it) in text } ||
            names.filter { normalizedComparable(it).length >= 2 }
                .any { normalizedComparable(it) in text }

    private fun eventScore(item: NewsItem): Int {
        val text = normalizedComparable("${item.title} ${item.rawCategory.orEmpty()}")
        val hasTheme = highValueThemes.any { normalizedComparable(it) in text }
        val hasMaterialEvent = materialEventKeywords.any { normalizedComparable(it) in text }
        return when {
            hasTheme && hasMaterialEvent -> THEME_SCORE + MATERIAL_EVENT_SCORE
            hasTheme -> THEME_SCORE
            hasMaterialEvent -> MATERIAL_EVENT_SCORE
            else -> 0
        }
    }

    private fun promotionalPenalty(item: NewsItem): Int {
        val text = normalizedComparable("${item.title} ${item.rawCategory.orEmpty()}")
        return if (promotionalKeywords.any { normalizedComparable(it) in text }) PROMOTION_PENALTY else 0
    }

    private fun freshnessScore(publishedAtMs: Long, nowMs: Long): Int {
        val elapsedHours = ((nowMs - publishedAtMs).coerceAtLeast(0L) / 3_600_000L).coerceAtMost(24L)
        return (20.0 - elapsedHours * 0.5).toInt().coerceIn(0, 20)
    }

    private fun similarity(left: String, right: String): Double {
        if (left == right) return 1.0
        val leftTokens = titleTokens(left)
        val rightTokens = titleTokens(right)
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0
        val overlap = leftTokens.intersect(rightTokens).size
        return overlap.toDouble() / minOf(leftTokens.size, rightTokens.size)
    }

    private fun titleTokens(normalized: String): Set<String> =
        if (normalized.length <= 2) setOf(normalized)
        else normalized.windowed(2).toSet()

    private fun normalizedComparable(value: String): String =
        value.lowercase(Locale.CHINA).replace(Regex("[\\s\\p{Punct}，。！？；：、“”‘’（）【】《》]+"), "")

    private fun categoryOf(title: String, rawCategory: String?): NewsCategory {
        val text = "$title ${rawCategory.orEmpty()}".lowercase(Locale.CHINA)
        return when {
            macroKeywords.any { text.contains(it) } -> NewsCategory.MACRO_POLICY
            sectorKeywords.any { text.contains(it) } -> NewsCategory.SECTOR_INDUSTRY
            companyKeywords.any { text.contains(it) } -> NewsCategory.COMPANY_EVENT
            else -> NewsCategory.MARKET_GENERAL
        }
    }

    private fun normalizeTitle(title: String): String =
        normalizedComparable(title)

    private fun isWithinLocalDays(timestampMs: Long, nowMs: Long, maxAgeDays: Int): Boolean {
        if (timestampMs <= 0L || nowMs <= 0L) return false
        val calendar = Calendar.getInstance(shanghaiTimeZone)
        calendar.timeInMillis = nowMs
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val oldestDayStart = calendar.timeInMillis -
            (maxAgeDays.coerceIn(1, 30) - 1).toLong() * 24L * 60L * 60L * 1_000L
        val todayEndExclusive = calendar.timeInMillis + 24L * 60L * 60L * 1_000L
        return timestampMs in oldestDayStart until todayEndExclusive
    }

    private fun formatTime(timestampMs: Long): String =
        if (timestampMs <= 0L) "—" else SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
            .apply { timeZone = shanghaiTimeZone }
            .format(timestampMs)

    private const val SIMILARITY_THRESHOLD = 0.68
    private const val HELD_FOCUS_SCORE = 120
    private const val WATCH_FOCUS_SCORE = 80
    private const val OBSERVATION_FOCUS_SCORE = 65
    private const val THEME_SCORE = 30
    private const val MATERIAL_EVENT_SCORE = 25
    private const val TRUST_WEIGHT = 25
    private const val CORROBORATION_STEP = 10
    private const val MAX_CORROBORATION = 30
    private const val PROMOTION_PENALTY = 45
}
