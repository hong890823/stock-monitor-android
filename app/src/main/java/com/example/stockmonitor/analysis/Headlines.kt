package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.NewsItem
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

enum class NewsCategory(val label: String, val priority: Int) {
    MACRO_POLICY("宏观/政策", 3),
    SECTOR_INDUSTRY("行业板块", 2),
    COMPANY_EVENT("公司事件", 1),
    MARKET_GENERAL("市场快讯", 0),
}

data class SelectedHeadline(
    val item: NewsItem,
    val category: NewsCategory,
)

object HeadlineSelector {
    const val CARD_LIMIT = 5
    const val DETAIL_LIMIT = 20
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

    fun select(items: List<NewsItem>, nowMs: Long, limit: Int = CARD_LIMIT): List<SelectedHeadline> {
        if (limit <= 0) return emptyList()
        val selected = LinkedHashMap<String, SelectedHeadline>()
        items.filter { isSameLocalDay(it.publishedAtMs, nowMs) }
            .map { SelectedHeadline(it, categoryOf(it.title, it.rawCategory)) }
            .forEach { candidate ->
                val key = normalizeTitle(candidate.item.title)
                if (key.isBlank()) return@forEach
                val previous = selected[key]
                if (previous == null || outranks(candidate, previous)) selected[key] = candidate
            }
        return selected.values
            .sortedWith(
                compareByDescending<SelectedHeadline> { it.category.priority }
                    .thenByDescending { it.item.publishedAtMs }
                    .thenBy { it.item.title },
            )
            .take(limit.coerceAtMost(DETAIL_LIMIT))
    }

    fun formatReport(items: List<SelectedHeadline>, updatedAtMs: Long): String = buildString {
        appendLine("今日要闻｜更新时间：${formatTime(updatedAtMs)}")
        appendLine("来源与发布时间按接口返回展示；分类为关键词归类，不代表确定的利好或利空方向。")
        appendLine()
        if (items.isEmpty()) {
            appendLine("暂无今日可用要闻")
            return@buildString
        }
        items.forEachIndexed { index, headline ->
            val item = headline.item
            appendLine("${index + 1}. ${item.title}")
            appendLine("   ${headline.category.label}｜${item.source}｜${formatTime(item.publishedAtMs)}")
            item.url?.let { appendLine("   $it") }
        }
    }

    private fun categoryOf(title: String, rawCategory: String?): NewsCategory {
        val text = "$title ${rawCategory.orEmpty()}".lowercase(Locale.CHINA)
        return when {
            macroKeywords.any { text.contains(it) } -> NewsCategory.MACRO_POLICY
            sectorKeywords.any { text.contains(it) } -> NewsCategory.SECTOR_INDUSTRY
            companyKeywords.any { text.contains(it) } -> NewsCategory.COMPANY_EVENT
            else -> NewsCategory.MARKET_GENERAL
        }
    }

    private fun outranks(candidate: SelectedHeadline, previous: SelectedHeadline): Boolean =
        candidate.category.priority > previous.category.priority ||
            (candidate.category.priority == previous.category.priority &&
                candidate.item.publishedAtMs > previous.item.publishedAtMs)

    private fun normalizeTitle(title: String): String =
        title.lowercase(Locale.CHINA)
            .replace(Regex("[\\s\\p{Punct}，。！？；：、“”‘’（）【】《》]+"), "")

    private fun isSameLocalDay(timestampMs: Long, nowMs: Long): Boolean {
        if (timestampMs <= 0L || nowMs <= 0L) return false
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply { timeZone = shanghaiTimeZone }
        return formatter.format(timestampMs) == formatter.format(nowMs)
    }

    private fun formatTime(timestampMs: Long): String =
        if (timestampMs <= 0L) "—" else SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
            .apply { timeZone = shanghaiTimeZone }
            .format(timestampMs)
}
