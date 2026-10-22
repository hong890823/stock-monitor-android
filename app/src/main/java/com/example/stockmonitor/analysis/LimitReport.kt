package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.LimitData
import com.example.stockmonitor.core.LimitStock
import com.example.stockmonitor.core.FundFlow
import java.util.Locale
import kotlin.math.abs

object LimitReport {

    fun format(data: LimitData): String = buildString {
        appendLine("涨跌停全量列表｜数据来源：${data.source.label}")
        append(formatUp(data))
        appendLine()
        append(formatDown(data))
    }

    fun formatUp(data: LimitData): String = buildString {
        appendLine(
            "涨停（${data.up.size}只）｜数据来源：${data.source.label}｜" +
                "封板质量仅用于涨停观察排序，不代表买入信号",
        )
        appendStocks(data.up)
    }

    fun formatDown(data: LimitData): String = buildString {
        appendLine("跌停（${data.down.size}只）｜数据来源：${data.source.label}")
        appendStocks(data.down)
    }

    fun stockLine(stock: LimitStock): String {
        val boards = stock.boards.take(3).takeIf { it.isNotEmpty() }
            ?.joinToString("、")?.let { "｜$it" }.orEmpty()
        return String.format(
            Locale.CHINA,
            "%s（%s） %+.2f%%%s｜%s",
            stock.name,
            stock.code,
            stock.pct,
            boards,
            metricLine(stock),
        )
    }

    fun metricLine(stock: LimitStock): String {
        val openLabel = if (stock.pct >= 0) "炸板" else "开板"
        val streak = stock.limitStreak?.takeIf { it > 0 }?.let { "｜${it}连板" }.orEmpty()
        val industry = stock.industry?.takeIf { it.isNotBlank() }?.let { "｜$it" }.orEmpty()
        val sealMetrics = listOf(
            "封单 ${formatMoney(stock.sealAmountYuan)}",
            "封单/成交 ${formatPercent(stock.sealToTurnoverPct)}",
            "封单/流通市值 ${formatPercent(stock.sealToFloatPct)}",
            "$openLabel ${stock.openCount?.toString() ?: "—"}次",
            "首封 ${shortTime(stock.firstSealTime)}",
            "末封 ${shortTime(stock.lastSealTime)}",
        ).joinToString("｜")
        val quality =
            if (stock.sealQualityScore != null) {
                "｜封板质量 ${stock.sealQualityScore}｜数据覆盖 ${stock.sealQualityCoveragePct}%"
            } else {
                ""
            }
        return sealMetrics + streak + industry + quality + "｜" + fundFlowLine(stock)
    }

    fun detailRows(stock: LimitStock): List<Pair<String, String>> = listOf(
        "封板质量" to buildString {
            append(stock.sealQualityScore?.let { "${it}分" } ?: "—")
            stock.sealQualityCoveragePct?.let { append("（数据覆盖 $it%）") }
        },
        "封单金额" to formatMoney(stock.sealAmountYuan),
        "封单/成交" to formatPercent(stock.sealToTurnoverPct),
        "封单/流通市值" to formatPercent(stock.sealToFloatPct),
        "开板" to buildString {
            append(stock.openCount?.toString() ?: "—")
            if (stock.openCount != null) {
                append("次")
                if (stock.pct >= 0) append("（炸板）")
            }
        },
        "首封" to shortTime(stock.firstSealTime),
        "末封" to shortTime(stock.lastSealTime),
        "连板" to (stock.limitStreak?.takeIf { it > 0 }?.let { "${it}连板" } ?: "—"),
        "概念" to stock.boards.joinToString("、").ifBlank { "—" },
        "行业" to (stock.industry?.takeIf { it.isNotBlank() } ?: "—"),
        "资金流" to fundFlowSummary(stock.fundFlow),
    )

    fun fundFlowLine(stock: LimitStock): String {
        val flow = stock.fundFlow
        return fundFlowSummary(flow, prefix = "主力净流向") + "｜按订单规模归类，不代表机构身份" +
            if (lowConfidence(flow)) "｜样本偏小" else ""
    }

    fun fundFlowSummary(flow: FundFlow?, prefix: String = "资金流"): String {
        if (flow == null || flow.mainNetPct == null || !flow.mainNetPct.isFinite()) {
            return "$prefix —"
        }
        val pct = formatSignedPercent(flow.mainNetPct)
        val amount = formatMoney(flow.mainNetYuan)
        return "$prefix $pct（$amount）"
    }

    private fun lowConfidence(flow: FundFlow?): Boolean {
        if (flow == null) return false
        val lowConfidence = flow.amountYuan == null || !flow.amountYuan.isFinite() ||
            flow.amountYuan < FundFlowPolicy.MIN_RELIABLE_AMOUNT_YUAN
        return lowConfidence
    }

    private fun StringBuilder.appendStocks(stocks: List<LimitStock>) {
        if (stocks.isEmpty()) {
            appendLine("暂无")
            return
        }
        stocks.forEachIndexed { index, stock ->
            appendLine("${index + 1}. ${stockLine(stock)}")
        }
    }

    private fun formatMoney(value: Double?): String = when {
        value == null || !value.isFinite() -> "—"
        abs(value) >= 1e8 -> String.format(Locale.CHINA, "%.2f亿", value / 1e8)
        abs(value) >= 1e4 -> String.format(Locale.CHINA, "%.1f万", value / 1e4)
        else -> String.format(Locale.CHINA, "%.0f元", value)
    }

    private fun formatPercent(value: Double?): String =
        if (value == null || !value.isFinite()) "—" else String.format(Locale.CHINA, "%.2f%%", value)

    private fun formatSignedPercent(value: Double?): String =
        if (value == null || !value.isFinite()) "—" else String.format(Locale.CHINA, "%+.2f%%", value)

    private fun shortTime(value: String?): String =
        value?.takeIf { it.length >= 5 }?.substring(0, 5) ?: "—"
}
