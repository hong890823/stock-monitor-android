package com.example.stockmonitor.analysis

/** 阅读公告正文后，提炼对股东有用的影响判断；不把原文直接展示给用户。 */
object AnnouncementSummary {

    fun assess(title: String, reason: String, content: String?): String? {
        val plain = toPlainText(content) ?: return null
        return when {
            reason == "定增/再融资事项" -> privatePlacement(title, plain)
            reason.contains("处罚") || reason.contains("立案") || reason.contains("监管") ||
                reason.contains("纪律") || reason.contains("违规") -> negativeCompliance(plain)
            reason == "股东质押" -> pledge(plain)
            reason == "股东减持" -> reduction(plain)
            reason.contains("诉讼") || reason.contains("仲裁") -> litigation(plain)
            reason.contains("减值") || reason.contains("商誉") -> impairment(plain)
            resolved(title, plain) || cancelled(title, plain) ||
                proposalOnly(title, plain) || mixedResolved(title, plain) -> neutral(plain)
            reason == "股份回购" -> positive(
                confirmed = plain.contains("已实施") || plain.contains("完成回购"),
                note = "回购通常有助于改善市场预期，但拟议或计划状态不等于利好落地",
            )
            reason == "股东增持" -> positive(
                confirmed = plain.contains("已增持") || plain.contains("完成增持"),
                note = "增持体现一定信心，但拟议计划不等于利好落地",
            )
            reason == "分红" || reason == "权益分派" -> positive(
                confirmed = plain.contains("实施") || plain.contains("除权除息"),
                note = "分红或权益分派需以股东大会通过和实际实施为准",
            )
            reason == "股权激励" -> positive(
                confirmed = plain.contains("授予") || plain.contains("完成登记"),
                note = "股权激励需关注业绩条件、归属安排和费用摊销",
            )
            reason == "业绩预增" -> positive(
                confirmed = plain.contains("预计") || plain.contains("预增"),
                note = "业绩预增仍以正式财报为准",
            )
            else -> generic(reason, plain)
        }
    }

    private fun toPlainText(content: String?): String? {
        if (content.isNullOrBlank()) return null
        val text = content
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()
        return text.takeIf { it.isNotBlank() }
    }

    private fun privatePlacement(title: String, plain: String): String {
        val stage = when {
            plain.contains("终止") || title.contains("终止") -> "方案已终止"
            plain.contains("注册生效") || plain.contains("注册获批") -> "发行注册已获批"
            plain.contains("获得上海证券交易所受理") || plain.contains("已获受理") -> "发行申请已获交易所受理"
            plain.contains("尚需") && (plain.contains("审核") || plain.contains("注册")) -> "发行仍处于审核/注册推进阶段"
            plain.contains("董事会") || plain.contains("股东大会") -> "公司已进入发行方案审议阶段"
            else -> "公司正在推进发行方案"
        }
        val use = when {
            plain.contains("补充流动资金") && (plain.contains("项目") || plain.contains("募投")) ->
                "募集资金兼顾项目建设和补充流动资金"
            plain.contains("偿还") && plain.contains("债务") -> "募集资金包含偿还债务用途"
            plain.contains("补充流动资金") -> "募集资金包含补充流动资金用途"
            plain.contains("募投") || plain.contains("募集资金") -> "募集资金用于项目建设等经营安排"
            else -> "正文未明确披露具体资金用途"
        }
        val risk = if (
            plain.contains("摊薄") || plain.contains("股本") || plain.contains("发行数量") ||
            plain.contains("发行价格")
        ) {
            "需关注发行价格、股本摊薄及审核/注册未通过风险"
        } else {
            "仍需关注发行价格、股本变化及审核/注册不确定性"
        }
        val expansionRisk = if (isExpansionProject(plain)) {
            "；项目涉及扩产，需关注订单兑现、产能利用率、行业供需和投资回报，扩产过快可能导致盈利不及预期"
        } else {
            ""
        }
        val direction = if (stage == "方案已终止") "偏利空" else "中性偏积极"
        val resolved = resolved(title, plain) || cancelled(title, plain)
        val headline = when {
            resolved -> "影响待确认"
            proposalOnly(title, plain) -> "影响待确认"
            else -> direction
        }
        return "$headline：$stage；$use；$risk$expansionRisk。"
    }

    private fun isExpansionProject(plain: String): Boolean =
        listOf("产能", "扩产", "产线", "建设期", "生产基地", "项目建设", "新增产能")
            .any(plain::contains)

    private fun negativeCompliance(plain: String): String {
        val subject = when {
            plain.contains("信息披露") -> "信息披露合规和处罚事项"
            plain.contains("立案") -> "立案调查事项"
            plain.contains("整改") -> "违规整改事项"
            else -> "监管或处罚事项"
        }
        return "偏利空：涉及$subject，可能带来罚款、整改或追责影响，需关注后续处理结果。"
    }

    private fun pledge(plain: String): String = if (plain.contains("解除质押") &&
        (plain.contains("再质押") || plain.contains("新增质押"))
    ) {
        "影响待确认：部分质押已解除但同时新增再质押，平仓压力是否下降要看质押规模变化。"
    } else if (plain.contains("解除质押")) {
        "偏利好：相关股份质押已解除，短期平仓压力下降；仍需关注股东后续融资安排。"
    } else {
        "风险提示：股东存在股份质押，需关注质押比例、平仓线及是否影响控制权稳定。"
    }

    private fun reduction(plain: String): String = if (plain.contains("完成减持") || plain.contains("减持完毕")) {
        "偏利空影响已阶段性落地：股东减持计划已完成，后续仍需关注是否有新的减持安排。"
    } else {
        "偏利空：股东存在减持安排，可能增加短期供给和股价压力，需关注减持规模与期限。"
    }

    private fun litigation(plain: String): String =
        "风险提示：公司涉及诉讼或仲裁，需关注涉案金额、进展及是否对经营和资产造成实质影响。"

    private fun impairment(plain: String): String =
        "偏利空：公告涉及减值或商誉事项，可能压低当期利润和净资产，需核对减值规模及可逆性。"

    private fun positive(confirmed: Boolean, note: String): String =
        if (confirmed) "偏利好：$note。" else "影响待确认：$note。"

    private fun generic(reason: String, plain: String): String {
        val direction = when {
            reason.contains("风险") || reason.contains("问询") || reason.contains("关注") -> "风险提示"
            else -> "中性"
        }
        return "$direction：公告涉及$reason，需结合后续进展判断对经营和股东权益的实际影响。"
    }
    private fun neutral(plain: String): String =
        "影响待确认：公告内容需结合后续进展、实际执行和财务影响判断。${resolvedDetail(plain)}"

    private fun resolvedDetail(plain: String): String = when {
        plain.contains("全部解除") || plain.contains("解除冻结") || plain.contains("全部解除冻结") ->
            "相关限制或风险事项显示已解除，仍需确认是否有新增安排。"
        plain.contains("终止") -> "原安排已终止，需确认终止原因及后续替代安排。"
        else -> ""
    }

    private fun resolved(title: String, plain: String): Boolean =
        (title.contains("解除") || plain.contains("解除")) &&
            (plain.contains("全部解除") || plain.contains("解除冻结") || plain.contains("解除质押"))

    private fun proposalOnly(title: String, plain: String): Boolean =
        title.contains("拟") || title.contains("预案") || plain.contains("拟") ||
            plain.contains("尚未") || plain.contains("尚未实施")

    private fun cancelled(title: String, plain: String): Boolean =
        title.contains("终止") || plain.contains("终止")

    private fun mixedResolved(title: String, plain: String): Boolean =
        (plain.contains("解除") && plain.contains("质押")) && plain.contains("质押")
}
