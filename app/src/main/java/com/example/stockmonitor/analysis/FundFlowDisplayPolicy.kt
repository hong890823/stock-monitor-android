package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.FundFlow
import java.util.Locale
import kotlin.math.abs

/** 把资金流字段翻译成用户一眼能懂的“流入/流出”文案。 */
object FundFlowDisplayPolicy {
    fun current(flow: FundFlow?): String {
        val pct = flow?.mainNetPct?.takeIf { it.isFinite() }
            ?: return "今日主力资金数据暂缺"
        val direction = when {
            pct > 0.0 -> "净流入"
            pct < 0.0 -> "净流出"
            else -> "基本持平"
        }
        val amountText = flow.mainNetYuan?.takeIf { it.isFinite() }?.let { formatMoney(abs(it)) }
        val pctText = String.format(Locale.CHINA, "%+.2f%%", pct)
        return if (amountText == null) {
            "今日主力资金$direction（占成交额 $pctText）"
        } else {
            "今日主力资金$direction $amountText（占成交额 $pctText）"
        }
    }

    fun multiDay(flows: List<FundFlow>): String {
        val window = FundFlowWindowPolicy.assess(flows)
        if (window.tone == FundFlowWindowTone.UNAVAILABLE) return "多日资金流数据暂缺"
        if (window.tone == FundFlowWindowTone.ACCUMULATING) {
            return "多日资金流样本不足（已有${window.sampleCount}/3个交易日），暂不判断流入还是流出"
        }
        val days = minOf(window.sampleCount, FundFlowWindowPolicy.SHORT_WINDOW_DAYS)
        val inflowDays = window.recent3PositiveDays
        val outflowDays = days - inflowDays
        val average = window.recent3AvgMainNetPct
            ?.takeIf { it.isFinite() }
            ?.let { String.format(Locale.CHINA, "%+.2f%%", it) }
            ?: "暂缺"
        val direction = when (window.tone) {
            FundFlowWindowTone.PERSISTENT_INFLOW -> "整体持续流入"
            FundFlowWindowTone.PERSISTENT_OUTFLOW -> "整体持续流出"
            FundFlowWindowTone.MIXED -> "方向不一致，暂不加减分"
            else -> "暂不判断方向"
        }
        return "最近3个交易日${inflowDays}天净流入、${outflowDays}天净流出，$direction" +
            "（平均占成交额 $average）"
    }

    /**
     * 风险列表使用的资金流证据：优先写清今日金额与占比，持续流出时再补近3日合计金额。
     * 旧候选只有泛化原因时，UI 可用当前行情重新补齐这条证据。
     */
    fun riskEvidence(currentFlow: FundFlow?, flowHistory: List<FundFlow>): String? {
        val currentPct = currentFlow?.mainNetPct?.takeIf { it.isFinite() }
        val currentAmount = currentFlow?.mainNetYuan?.takeIf { it.isFinite() }
        val todayText = when {
            currentPct != null && currentPct < 0.0 -> buildString {
                append("今日主力资金净流出")
                currentAmount?.let { append(" ${formatMoney(abs(it))}") }
                append("（占成交额 ${signedPercent(currentPct)}）")
            }
            currentPct == null && currentAmount != null && currentAmount < 0.0 ->
                "今日主力资金净流出 ${formatMoney(abs(currentAmount))}"
            else -> null
        }

        val history = flowHistory
            .filter { it.mainNetPct?.isFinite() == true }
            .sortedBy { it.asOfMs }
            .takeLast(FundFlowWindowPolicy.LONG_WINDOW_DAYS)
        val window = FundFlowWindowPolicy.assess(history)
        val recent3 = history.takeLast(FundFlowWindowPolicy.SHORT_WINDOW_DAYS)
        val persistentText = if (window.tone == FundFlowWindowTone.PERSISTENT_OUTFLOW && recent3.isNotEmpty()) {
            val outflowDays = recent3.count { (it.mainNetPct ?: 0.0) < 0.0 }
            val totalAmount = recent3
                .map { flow -> flow.mainNetYuan?.takeIf { it.isFinite() } }
                .takeIf { values -> values.all { it != null } }
                ?.filterNotNull()
                ?.sum()
            buildString {
                append("近3日${outflowDays}/${recent3.size}天净流出")
                totalAmount?.takeIf { it < 0.0 }?.let {
                    append("，合计 ${formatMoney(abs(it))}")
                }
                append("（平均占成交额 ${signedPercent(window.recent3AvgMainNetPct)}）")
            }
        } else {
            null
        }
        return listOfNotNull(todayText, persistentText)
            .distinct()
            .joinToString("；")
            .ifBlank { null }
    }

    /**
     * 候选池单行资金流：最新快照与多日窗口共用一行，避免“今日/近3日”重复出现。
     * 返回内容只用于展示，不改变资金流评分或晋级状态。
     */
    fun candidateSummary(flow: FundFlow?, window: FundFlowWindowAssessment): String = buildList {
        currentSnapshot(flow)?.let(::add)
        val recent3Samples = minOf(window.sampleCount, FundFlowWindowPolicy.SHORT_WINDOW_DAYS)
        when (window.tone) {
            FundFlowWindowTone.UNAVAILABLE -> Unit
            FundFlowWindowTone.ACCUMULATING ->
                add("近3日仅${window.sampleCount}/${FundFlowWindowPolicy.SHORT_WINDOW_DAYS}天有有效样本，暂不判断方向")
            FundFlowWindowTone.PERSISTENT_INFLOW,
            FundFlowWindowTone.PERSISTENT_OUTFLOW,
            FundFlowWindowTone.MIXED,
            -> {
                val threeDayText = when (window.tone) {
                    FundFlowWindowTone.PERSISTENT_OUTFLOW -> {
                        val outflowDays = recent3Samples - window.recent3PositiveDays
                        "近3日 $outflowDays/${recent3Samples}天净流出"
                    }
                    FundFlowWindowTone.MIXED -> {
                        val outflowDays = recent3Samples - window.recent3PositiveDays
                        "近3日 ${window.recent3PositiveDays}天净流入、${outflowDays}天净流出"
                    }
                    else -> "近3日 ${window.recent3PositiveDays}/${recent3Samples}天净流入"
                }
                add(threeDayText + "（均值 ${signedPercent(window.recent3AvgMainNetPct)}）")

                val fiveDayFlowDays = if (window.tone == FundFlowWindowTone.PERSISTENT_OUTFLOW) {
                    window.sampleCount - window.recent5PositiveDays
                } else {
                    window.recent5PositiveDays
                }
                val fiveDayDirection = if (window.tone == FundFlowWindowTone.PERSISTENT_OUTFLOW) {
                    "净流出"
                } else {
                    "净流入"
                }
                add("近5日 $fiveDayFlowDays/${window.sampleCount}天$fiveDayDirection")
                add(candidateConclusion(window))
            }
        }
    }.joinToString("｜").ifBlank { "暂无有效资金流数据" }

    /**
     * 统一给自选与持仓页展示的主力动向摘要。
     * 此项只归纳当前、近 3/5 日和较大订单资金流，不改变原有评分、选股或提醒逻辑。
     */
    fun likelyMainForceDirection(
        changePct: Double?,
        quoteDate: String?,
        flows: List<FundFlow>,
    ): String = mainForceDirection(changePct, quoteDate, flows).summary

    fun mainForceDirection(
        changePct: Double?,
        quoteDate: String?,
        flows: List<FundFlow>,
    ): MainForceDirectionAssessment =
        MainForceDirectionPolicy.assess(changePct, quoteDate, flows)

    private fun currentSnapshot(flow: FundFlow?): String? {
        val pct = flow?.mainNetPct?.takeIf { it.isFinite() }
        val amount = flow?.mainNetYuan?.takeIf { it.isFinite() }
        if (pct == null && amount == null) return null
        val direction = when {
            pct != null && pct > 0.0 -> "净流入"
            pct != null && pct < 0.0 -> "净流出"
            pct != null -> "基本持平"
            amount != null && amount > 0.0 -> "净流入"
            amount != null && amount < 0.0 -> "净流出"
            else -> "基本持平"
        }
        val amountText = amount?.let { formatMoney(abs(it)) }
        val pctText = pct?.let { String.format(Locale.CHINA, "%+.2f%%", it) } ?: "占比暂缺"
        return buildString {
            append("最新主力$direction")
            if (amountText != null) append(" $amountText")
            append("（$pctText）")
        }
    }

    private fun candidateConclusion(window: FundFlowWindowAssessment): String {
        val conclusion = when (window.tone) {
            FundFlowWindowTone.PERSISTENT_INFLOW ->
                if (window.strong) "流入较强，仅作加分参考" else "持续流入，仅作加分参考"
            FundFlowWindowTone.PERSISTENT_OUTFLOW -> "持续流出，暂缓晋级"
            FundFlowWindowTone.MIXED -> "方向不一致，保持中性"
            FundFlowWindowTone.ACCUMULATING,
            FundFlowWindowTone.UNAVAILABLE,
            -> ""
        }
        return if (window.lowConfidence && conclusion.isNotBlank()) {
            "$conclusion（成交额样本偏小）"
        } else {
            conclusion
        }
    }

    private fun signedPercent(value: Double?): String =
        value?.takeIf { it.isFinite() }?.let { String.format(Locale.CHINA, "%+.2f%%", it) } ?: "暂缺"

    private fun formatMoney(value: Double): String {
        val absolute = abs(value)
        return when {
            absolute >= 1e8 -> String.format(Locale.CHINA, "%.2f亿", value / 1e8)
            absolute >= 1e4 -> String.format(Locale.CHINA, "%.1f万", value / 1e4)
            else -> String.format(Locale.CHINA, "%.0f元", value)
        }
    }
}
