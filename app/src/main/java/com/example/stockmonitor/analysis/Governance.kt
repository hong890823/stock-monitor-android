package com.example.stockmonitor.analysis

import java.text.SimpleDateFormat
import java.util.Locale

/** 一条公告；正文只对筛选出的重点公告按需获取。 */
data class Announcement(
    val date: String,
    val title: String,
    val artCode: String? = null,
    val content: String? = null,
)

/**
 * [count] 是同一原因在窗口内的条数。一次重大重组能连发十几条公告，逐条列出来会把其他信号挤掉，
 * 所以同一个原因只保留最新一条，条数记在这里。
 */
data class GovernanceItem(
    val date: String,
    val title: String,
    val level: RiskLevel,
    val reason: String,
    val count: Int = 1,
    val assessment: String? = null,
)

/**
 * 公司治理与合规的公告扫描。
 *
 * 质押、处罚、问询、诉讼这些东西财报里看不到，只在公告里，所以这一块的数据源必须是公告而不是报表。
 * 标题用于筛选重点公告，正文用于解释对投资者的影响；正文获取失败时不编造结论。
 */
object Governance {

    /** 只看最近这段时间的公告；更早的事情多半已经在财报里体现。 */
    const val WINDOW_DAYS = 180L

    private val ALERT_KEYWORDS = listOf(
        "立案" to "被立案调查",
        "处罚" to "行政处罚",
        "违规" to "违规事项",
        "冻结" to "股份被冻结",
        "终止上市" to "终止上市",
        "退市" to "退市相关",
        "无法表示意见" to "审计意见异常",
        "否定意见" to "审计意见异常",
        "保留意见" to "审计意见非标",
        "监管措施" to "被采取监管措施",
        "公开谴责" to "公开谴责",
        "警示函" to "监管警示函",
        "纪律处分" to "纪律处分",
        "会计差错" to "会计差错更正",
        // 业绩变脸的最早信号通常在这里，比财报快一到两个季度。
        // 关键词必须带「业绩」语境：裸「下修」会把可转债「向下修正转股价格」误判成业绩下修。
        "预亏" to "业绩预亏",
        "预减" to "业绩预减",
        "预降" to "业绩预降",
        "业绩下修" to "业绩下修",
        "下修业绩" to "业绩下修",
        "业绩修正" to "业绩预告修正",
        "业绩预告修正" to "业绩预告修正",
    )

    private val GOOD_KEYWORDS = listOf(
        "解除质押" to "质押解除",
        "回购" to "股份回购",
        "增持" to "股东增持",
        "分红" to "分红",
        "权益分派" to "权益分派",
        "股权激励" to "股权激励",
        "预增" to "业绩预增",
    )

    private val WATCH_KEYWORDS = listOf(
        "向特定对象发行" to "定增/再融资事项",
        "非公开发行" to "定增/再融资事项",
        "定向增发" to "定增/再融资事项",
        "定增" to "定增/再融资事项",
        "再融资" to "定增/再融资事项",
        "质押" to "股东质押",
        "减持" to "股东减持",
        "问询" to "交易所问询",
        "关注函" to "交易所关注函",
        "诉讼" to "涉及诉讼",
        "仲裁" to "涉及仲裁",
        "减值" to "计提减值",
        "商誉" to "商誉事项",
        "重组" to "重大资产重组",
        "风险提示" to "风险提示",
        "更正" to "公告更正",
        "解禁" to "限售股解禁",
        "变更会计师" to "更换年审会计师",
        "延期回复" to "延期回复问询",
    )

    /**
     * 扫描公告标题，按「重大风险 → 正面事项 → 需要留意」的顺序分类，每一条只归一类。
     * 结果按「风险等级优先、时间新优先」排序，取前 [limit] 条。
     */
    fun scan(
        items: List<Announcement>,
        referenceDate: String,
        windowDays: Long = WINDOW_DAYS,
        limit: Int = 6,
    ): List<GovernanceItem> {
        val reference = parseDate(referenceDate)
        val byReason = LinkedHashMap<String, MutableList<GovernanceItem>>()
        for (item in items) {
            val date = parseDate(item.date) ?: continue
            if (reference != null && (date.after(reference) || daysBetween(date, reference) > windowDays)) continue
            val alert = match(item.title, ALERT_KEYWORDS)
                ?.takeUnless { item.title.contains("解除") && it == "股份被冻结" }
                ?.takeUnless { it == "业绩预告修正" && !item.title.contains("年度") }
            val good = match(item.title, GOOD_KEYWORDS)
                ?.takeUnless { item.title.contains("终止") }
            val watch = match(item.title, WATCH_KEYWORDS)
                ?: if (item.title.contains("业绩预告修正")) "业绩预告修正" else null
            val level = when {
                alert != null -> RiskLevel.ALERT
                good != null -> RiskLevel.GOOD
                watch != null -> RiskLevel.WATCH
                else -> continue
            }
            val reason = alert ?: good ?: watch ?: continue
            byReason.getOrPut(reason) { mutableListOf() }
                .add(
                    GovernanceItem(
                        date = item.date,
                        title = item.title,
                        level = level,
                        reason = reason,
                        assessment = AnnouncementSummary.assess(item.title, reason, item.content),
                    )
                )
        }
        if (items.any { it.title.contains("解除冻结") || it.title.contains("全部解除冻结") }) {
            byReason.remove("股份被冻结")
        }
        if (items.any { it.title.contains("终止回购") || it.title.contains("终止回购计划") }) {
            byReason.remove("股份回购")
        }
        return byReason.values
            .map { group ->
                val newest = group.maxByOrNull { it.date } ?: return@map null
                newest.copy(count = group.size)
            }
            .filterNotNull()
            .sortedWith(compareBy<GovernanceItem> { severity(it.level) }.thenByDescending { it.date })
            .take(limit)
    }

    private fun severity(level: RiskLevel): Int = when (level) {
        RiskLevel.ALERT -> 0
        RiskLevel.WATCH -> 1
        RiskLevel.GOOD -> 2
    }

    private fun match(title: String, keywords: List<Pair<String, String>>): String? =
        keywords.firstOrNull { title.contains(it.first) }?.second

    private fun isResolved(title: String): Boolean =
        title.contains("解除") || title.contains("终止")

    private fun parseDate(value: String): java.util.Date? {
        if (value.length < 10) return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply { isLenient = false }.parse(value.take(10))
        }.getOrNull()
    }

    private fun daysBetween(from: java.util.Date, to: java.util.Date): Long =
        (to.time - from.time) / 86_400_000L
}
