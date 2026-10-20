package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.LimitData
import com.example.stockmonitor.core.LimitStock
import java.util.Locale

object LimitReport {

    fun format(data: LimitData): String = buildString {
        appendLine("涨跌停全量列表")
        appendLine("涨停（${data.up.size}只）")
        appendStocks(data.up)
        appendLine()
        appendLine("跌停（${data.down.size}只）")
        appendStocks(data.down)
    }

    private fun StringBuilder.appendStocks(stocks: List<LimitStock>) {
        if (stocks.isEmpty()) {
            appendLine("暂无")
            return
        }
        stocks.forEachIndexed { index, stock ->
            val boards = stock.boards.take(3).takeIf { it.isNotEmpty() }
                ?.joinToString("、")?.let { "｜$it" }.orEmpty()
            appendLine(
                String.format(
                    Locale.CHINA,
                    "%d. %s（%s） %+.2f%%%s",
                    index + 1,
                    stock.name,
                    stock.code,
                    stock.pct,
                    boards,
                )
            )
        }
    }
}
