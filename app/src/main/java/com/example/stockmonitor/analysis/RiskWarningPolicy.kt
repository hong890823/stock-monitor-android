package com.example.stockmonitor.analysis

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 重大风险预警：ST/风险警示、立案调查、行政处罚等。
 *
 * 为什么单独一层：[Governance] 只看最近 180 天，用来解释「近期公告」；但立案调查到
 * 被实施风险警示可能隔半年以上，等 ST 落地时原始立案公告已经掉出窗口，报告就只剩
 * 一条「风险警示」，看不出前因。
 *
 * 这里做三件事：
 * 1. 用行情简称识别 ST / *ST / 退市整理，不依赖公告接口；
 * 2. 对立案、处罚、风险警示、资金占用、违规担保等重大合规事项按 730 天回溯；
 * 3. 识别后续的结案/撤销公告，避免把已经解除的事项一直当未决风险。
 *
 * 输出只用于展示与健康分级：不直接改评分，不生成买卖指令。
 */
enum class RiskWarningKind(val label: String) {
    ST_OTHER("其他风险警示（ST）"),
    ST_DELISTING("退市风险警示（*ST）"),
    DELISTING("退市/终止上市"),
    RISK_WARNING("风险警示"),
    INVESTIGATION("立案调查"),
    PENALTY("行政处罚"),
    MARKET_BAN("市场禁入"),
    FUND_OCCUPATION("资金占用"),
    GUARANTEE("违规担保"),
    AUDIT("审计意见非标"),
    FREEZE("股份/账户冻结"),
    OTHER("重大合规事项"),
}

data class RiskWarning(
    val kind: RiskWarningKind,
    val date: String?,
    val title: String,
    val note: String,
    val fromName: Boolean = false,
)

data class RiskWarningAssessment(
    val stockName: String,
    val warnings: List<RiskWarning> = emptyList(),
    val unresolvedInvestigation: Boolean = false,
    val investigationAndStLinked: Boolean = false,
) {
    val hasWarning: Boolean get() = warnings.isNotEmpty()
}

object RiskWarningPolicy {

    /** 重大合规事项的回溯窗口。立案到风险警示常跨半年，180 天不够。 */
    const val MAJOR_WINDOW_DAYS = 730L

    private data class Rule(val keyword: String, val kind: RiskWarningKind)

    // 顺序有意义：先识别更具体的风险警示类型，再落到泛化关键词。
    private val TITLE_RULES = listOf(
        Rule("退市风险警示", RiskWarningKind.ST_DELISTING),
        Rule("其他风险警示", RiskWarningKind.ST_OTHER),
        Rule("风险警示", RiskWarningKind.RISK_WARNING),
        Rule("终止上市", RiskWarningKind.DELISTING),
        Rule("退市整理", RiskWarningKind.DELISTING),
        Rule("退市", RiskWarningKind.DELISTING),
        Rule("立案", RiskWarningKind.INVESTIGATION),
        Rule("行政处罚", RiskWarningKind.PENALTY),
        Rule("处罚决定", RiskWarningKind.PENALTY),
        Rule("事先告知书", RiskWarningKind.PENALTY),
        Rule("市场禁入", RiskWarningKind.MARKET_BAN),
        Rule("资金占用", RiskWarningKind.FUND_OCCUPATION),
        Rule("违规担保", RiskWarningKind.GUARANTEE),
        Rule("公开谴责", RiskWarningKind.OTHER),
        Rule("纪律处分", RiskWarningKind.OTHER),
        Rule("监管措施", RiskWarningKind.OTHER),
        Rule("警示函", RiskWarningKind.OTHER),
        Rule("无法表示意见", RiskWarningKind.AUDIT),
        Rule("否定意见", RiskWarningKind.AUDIT),
        Rule("保留意见", RiskWarningKind.AUDIT),
        Rule("冻结", RiskWarningKind.FREEZE),
    )

    private val NOTES = mapOf(
        RiskWarningKind.ST_OTHER to
            "已被实施其他风险警示；涨跌幅限制、流动性与退市规则不同于普通股票，具体以交易所公告为准。",
        RiskWarningKind.ST_DELISTING to
            "已被实施退市风险警示（*ST），存在终止上市风险；具体以交易所公告为准。",
        RiskWarningKind.DELISTING to
            "涉及终止上市或退市整理，流动性与交易规则显著变化，优先核对交易所决定。",
        RiskWarningKind.RISK_WARNING to
            "公司股票被实施风险警示，通常伴随停牌或简称变更；先核对生效日、触发原因和撤销条件。",
        RiskWarningKind.INVESTIGATION to
            "立案调查可能持续数月到数年；未看到结案/撤销公告前按未决合规风险处理，严重时可能触发处罚、风险警示或退市。",
        RiskWarningKind.PENALTY to
            "行政处罚可能伴随罚款、整改、风险警示甚至退市；需核对处罚决定书认定的违规事实、金额和整改期限。",
        RiskWarningKind.MARKET_BAN to
            "市场禁入会限制相关责任人的任职与交易资格，需核对涉及人员和期限。",
        RiskWarningKind.FUND_OCCUPATION to
            "资金占用可能触发其他风险警示；需核对占用金额、归还计划和整改期限。",
        RiskWarningKind.GUARANTEE to
            "违规担保可能触发其他风险警示；需核对担保余额、解除安排和对净资产的影响。",
        RiskWarningKind.AUDIT to
            "审计意见非标可能触发退市风险警示；需核对具体意见类型和涉及事项。",
        RiskWarningKind.FREEZE to
            "股份或账户冻结可能影响控制权与流动性；需核对冻结原因、金额和解除进展。",
        RiskWarningKind.OTHER to
            "属于重大合规事项，需打开公告原文核对事实、金额和后续处理。",
    )

    /** 行情简称里的 ST 状态；只认前缀，避免公司名中间带 ST 被误判。 */
    fun stKind(name: String?): RiskWarningKind? {
        val normalized = name.orEmpty().trim().uppercase(Locale.CHINA).replace(" ", "")
        if (normalized.isEmpty()) return null
        return when {
            normalized.startsWith("退") -> RiskWarningKind.DELISTING
            normalized.startsWith("S*ST") || normalized.startsWith("*ST") ->
                RiskWarningKind.ST_DELISTING
            normalized.startsWith("SST") || normalized.startsWith("ST") ->
                RiskWarningKind.ST_OTHER
            else -> null
        }
    }

    fun matchKind(title: String): RiskWarningKind? {
        for (rule in TITLE_RULES) {
            if (!title.contains(rule.keyword)) continue
            // 「非经营性资金占用及其他关联资金往来情况汇总表」是定期披露，不是风险事项；
            // 只有带违规/整改/风险警示/清偿等语境时才按资金占用风险处理。
            if (rule.kind == RiskWarningKind.FUND_OCCUPATION && listOf(
                    "违规", "整改", "风险警示", "被占用", "清偿", "归还", "监管",
                ).none(title::contains)
            ) {
                continue
            }
            return rule.kind
        }
        return null
    }

    /** 是否属于本策略接管的重大合规标题（用于避免与普通公告风险重复入列）。 */
    fun isMajorComplianceTitle(title: String): Boolean =
        matchKind(title) != null || listOf("结案", "终止调查", "调查终止", "不予立案", "撤销立案")
            .any(title::contains)

    fun assess(
        stockName: String?,
        announcements: List<Announcement>,
        referenceDate: String,
    ): RiskWarningAssessment {
        val name = stockName.orEmpty().trim()
        val reference = parseDate(referenceDate)
        val direct = stKind(name)
        val warnings = mutableListOf<RiskWarning>()
        if (direct != null) {
            warnings += RiskWarning(
                kind = direct,
                date = null,
                title = "行情简称：$name",
                note = NOTES[direct].orEmpty(),
                fromName = true,
            )
        }
        if (reference == null) {
            return RiskWarningAssessment(stockName = name, warnings = warnings)
        }

        val resolutions = resolutionDates(announcements)
        val newestByKind = LinkedHashMap<RiskWarningKind, Pair<Date, Announcement>>()
        for (item in announcements) {
            val date = parseDate(item.date) ?: continue
            if (date.after(reference) || daysBetween(date, reference) > MAJOR_WINDOW_DAYS) continue
            val kind = matchKind(item.title) ?: continue
            val current = newestByKind[kind]
            if (current == null || date.after(current.first)) newestByKind[kind] = date to item
        }

        for ((kind, pair) in newestByKind) {
            val (date, item) = pair
            if (kind in setOf(RiskWarningKind.ST_OTHER, RiskWarningKind.ST_DELISTING) && direct != null) {
                continue
            }
            if (isResolved(kind, date, resolutions)) continue
            warnings += RiskWarning(
                kind = kind,
                date = item.date.take(10),
                title = item.title,
                note = NOTES[kind].orEmpty(),
            )
        }

        warnings.sortWith(
            compareBy<RiskWarning> { priority(it.kind) }
                .thenBy { it.date ?: "9999-12-31" },
        )

        val investigationOpen = warnings.any {
            it.kind == RiskWarningKind.INVESTIGATION && !it.fromName
        }
        val linked = direct != null && investigationOpen
        if (linked) {
            val index = warnings.indexOfFirst { it.kind == RiskWarningKind.INVESTIGATION }
            if (index >= 0) {
                warnings[index] = warnings[index].copy(
                    note = "历史立案调查未看到结案/撤销公告；与当前风险警示可能相关，" +
                        "需核对公告原文。立案调查可能持续数月到数年，未结案前按未决合规风险处理。",
                )
            }
        }
        return RiskWarningAssessment(
            stockName = name,
            warnings = warnings,
            unresolvedInvestigation = investigationOpen,
            investigationAndStLinked = linked,
        )
    }

    /** 报告顶部置顶的风险预警；没有风险时返回空列表。 */
    fun bannerLines(assessment: RiskWarningAssessment?): List<String> {
        if (assessment == null || !assessment.hasWarning) return emptyList()
        return buildList {
            add("⛔ 风险预警（优先看）")
            assessment.warnings.forEach { warning ->
                if (warning.fromName) {
                    add("· ${warning.kind.label}：${warning.title}；${warning.note}")
                } else {
                    add("· ${warning.date ?: "日期未知"} ${warning.kind.label}：《${warning.title}》——${warning.note}")
                }
            }
            add(
                "结论：风险警示/重大合规风险优先于财务评分；" +
                    "在风险警示未撤销、立案未结案前，不用低估值或超跌作为买入理由。",
            )
        }
    }

    /** 写进风险清单的文本；与报告顶部同源，避免两处口径不一致。 */
    fun riskTexts(assessment: RiskWarningAssessment?): List<String> {
        if (assessment == null || !assessment.hasWarning) return emptyList()
        return assessment.warnings.map { warning ->
            if (warning.fromName) {
                "风险警示：${warning.title}，${warning.kind.label}"
            } else {
                "公告提示：${warning.kind.label}——${warning.title}（${warning.date ?: "日期未知"}）"
            }
        }
    }

    /** 直接生成风险清单条目；名称类用分析日兜底，保证盘中健康分级仍能看到 ST 状态。 */
    fun riskItems(assessment: RiskWarningAssessment?, fallbackDate: String): List<RiskItem> {
        if (assessment == null || !assessment.hasWarning) return emptyList()
        return assessment.warnings.map { warning ->
            val text = if (warning.fromName) {
                "风险警示：${warning.title}，${warning.kind.label}"
            } else {
                "公告提示：${warning.kind.label}——${warning.title}（${warning.date ?: "日期未知"}）"
            }
            RiskItem(
                level = RiskLevel.ALERT,
                text = text,
                evidenceDate = warning.date ?: fallbackDate,
                announcement = true,
                majorCompliance = true,
            )
        }
    }

    private fun priority(kind: RiskWarningKind): Int = when (kind) {
        RiskWarningKind.ST_DELISTING, RiskWarningKind.ST_OTHER -> 0
        RiskWarningKind.DELISTING -> 1
        RiskWarningKind.RISK_WARNING -> 2
        RiskWarningKind.INVESTIGATION -> 3
        RiskWarningKind.PENALTY -> 4
        RiskWarningKind.MARKET_BAN -> 5
        RiskWarningKind.FUND_OCCUPATION -> 6
        RiskWarningKind.GUARANTEE -> 7
        RiskWarningKind.AUDIT -> 8
        RiskWarningKind.FREEZE -> 9
        RiskWarningKind.OTHER -> 10
    }

    private data class ResolutionDates(
        val investigation: List<Date>,
        val penalty: List<Date>,
        val riskWarning: List<Date>,
        val freeze: List<Date>,
    )

    private fun resolutionDates(items: List<Announcement>): ResolutionDates {
        val investigation = mutableListOf<Date>()
        val penalty = mutableListOf<Date>()
        val riskWarning = mutableListOf<Date>()
        val freeze = mutableListOf<Date>()
        for (item in items) {
            val date = parseDate(item.date) ?: continue
            val title = item.title
            if (listOf("结案", "终止调查", "调查终止", "不予立案", "撤销立案").any(title::contains)) {
                investigation += date
            }
            if (title.contains("撤销") && title.contains("处罚")) penalty += date
            if (title.contains("撤销") && (title.contains("风险警示") || title.uppercase(Locale.CHINA).contains("ST"))) {
                riskWarning += date
            }
            if (title.contains("解除") && title.contains("冻结")) freeze += date
        }
        return ResolutionDates(investigation, penalty, riskWarning, freeze)
    }

    private fun isResolved(kind: RiskWarningKind, newest: Date, dates: ResolutionDates): Boolean {
        val list = when (kind) {
            RiskWarningKind.INVESTIGATION -> dates.investigation
            RiskWarningKind.PENALTY -> dates.penalty
            RiskWarningKind.RISK_WARNING,
            RiskWarningKind.ST_OTHER,
            RiskWarningKind.ST_DELISTING,
            -> dates.riskWarning
            RiskWarningKind.FREEZE -> dates.freeze
            else -> return false
        }
        return list.any { !it.before(newest) }
    }

    private fun parseDate(value: String): Date? {
        if (value.length < 10) return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply { isLenient = false }
                .parse(value.take(10))
        }.getOrNull()
    }

    private fun daysBetween(from: Date, to: Date): Long = (to.time - from.time) / 86_400_000L
}
