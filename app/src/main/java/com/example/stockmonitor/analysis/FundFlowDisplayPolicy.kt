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

    private fun formatMoney(value: Double): String {
        val absolute = abs(value)
        return when {
            absolute >= 1e8 -> String.format(Locale.CHINA, "%.2f亿", value / 1e8)
            absolute >= 1e4 -> String.format(Locale.CHINA, "%.1f万", value / 1e4)
            else -> String.format(Locale.CHINA, "%.0f元", value)
        }
    }
}
