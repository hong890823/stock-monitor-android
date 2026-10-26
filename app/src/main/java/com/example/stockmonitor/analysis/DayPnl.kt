package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.Position
import com.example.stockmonitor.core.Quote
import com.example.stockmonitor.core.TradeBook
import java.util.Locale

/**
 * 持仓当日盈亏：较**昨收**的日内浮动，与较**成本**的浮盈浮亏是两件事，不要混用
 * （后者见 [DeepLossPolicy.floatingPnlPct] 与 [TradeCalculator]）。
 *
 * 口径（与券商 App 一致）：
 * - 昨日已持有部分 = (现价 − 昨收) × 昨日数量
 * - 今日新增部分 = (现价 − 今日买入成本) × 今日买入数量
 * - 组合今日盈亏 = Σ 单股今日盈亏
 * - 组合今日收益率 = 组合今日盈亏 ÷ Σ(昨收 × 昨日数量)，**分母用昨日持仓市值**。
 *   用今日市值当分母的话，涨得多的日子分母跟着变大，收益率会被系统性压低。
 *
 * 缺行情（停牌 / 未拉到）或昨收无效的股票**不计入分子也不计入分母**，只计入 [Summary.missing]，
 * 界面与报告如实标出只数，不拿 0 填充成「没亏没赚」。
 * 数量为 0 的观察股没有仓位，不进明细也不进合计。
 */
object DayPnl {

    /** 今日新增买入批次；[unitCost] 是这些股份的加权买入成本。 */
    data class Acquisition(
        val quantity: Double,
        val unitCost: Double,
    )

    /** 一只持仓的当日盈亏；[pnl] 为空表示这只算不出来（无行情 / 昨收无效）。 */
    data class Row(
        val code: String,
        val name: String,
        val quantity: Double,
        val price: Double,
        val prevClose: Double,
        /** 今日盈亏金额（元）；算不出来时为 null。 */
        val pnl: Double?,
        /** 个股当日涨跌幅（百分数），与 [Quote.changePct] 同口径；算不出来时为 null。 */
        val changePct: Double?,
        /** 该股昨日仍持有部分的市值；今日新增股份不伪装成昨日仓位，算不出来时为 0。 */
        val prevMarketValue: Double,
    )

    data class Summary(
        val rows: List<Row>,
        /** 有行情持仓的今日盈亏合计（元）；一只都算不出来时为 0.0。 */
        val totalPnl: Double,
        /** 组合今日收益率（%）= totalPnl ÷ [prevMarketValue]；一只都算不出来时为 null。 */
        val totalPct: Double?,
        /** 昨日持仓市值合计（元），即收益率的分母。 */
        val prevMarketValue: Double,
        /** 今日持仓市值合计（元）。 */
        val marketValue: Double,
        /** 算不出来的持仓只数（无行情 / 昨收无效）。 */
        val missing: Int,
    ) {
        /** 算得出今日盈亏的只数。 */
        val counted: Int get() = rows.count { it.pnl != null }
    }

    fun acquisitions(book: TradeBook, date: String): Map<String, Acquisition> =
        book.lots
            .filter {
                it.buyDate == date &&
                    it.quantity.isFinite() && it.quantity > 0 &&
                    it.unitCost.isFinite() && it.unitCost > 0
            }
            .groupBy { it.code }
            .mapNotNull { (code, lots) ->
                val quantity = lots.sumOf { it.quantity }
                if (quantity <= 0) return@mapNotNull null
                code to Acquisition(
                    quantity = quantity,
                    unitCost = lots.sumOf { it.quantity * it.unitCost } / quantity,
                )
            }
            .toMap()

    fun summarize(
        positions: List<Position>,
        quotes: Map<String, Quote>,
        todayAcquisitions: Map<String, Acquisition> = emptyMap(),
    ): Summary {
        val rows = mutableListOf<Row>()
        var totalPnl = 0.0
        var prevMarketValue = 0.0
        var marketValue = 0.0
        var missing = 0
        for (position in positions) {
            if (position.quantity <= 0) continue
            val quote = quotes[position.code]
            val price = quote?.price ?: Double.NaN
            val prevClose = quote?.prevClose ?: Double.NaN
            // 停牌、集合竞价前拿不到有效价、或昨收为 0（新股/停牌复牌）都算不出来，只标缺。
            if (quote == null || !price.isFinite() || !prevClose.isFinite() || price <= 0 || prevClose <= 0) {
                missing++
                rows.add(
                    Row(
                        code = position.code,
                        name = position.name,
                        quantity = position.quantity,
                        price = price,
                        prevClose = prevClose,
                        pnl = null,
                        changePct = null,
                        prevMarketValue = 0.0,
                    )
                )
                continue
            }
            val acquisition = todayAcquisitions[position.code]
            val acquiredQuantity = acquisition?.quantity
                ?.takeIf { it.isFinite() && it > 0 }
                ?.coerceAtMost(position.quantity)
                ?: 0.0
            val acquiredUnitCost = acquisition?.unitCost
                ?.takeIf { it.isFinite() && it > 0 }
                ?: position.cost
            val priorQuantity = position.quantity - acquiredQuantity
            val pnl = (price - prevClose) * priorQuantity +
                (price - acquiredUnitCost) * acquiredQuantity
            val prevValue = prevClose * priorQuantity
            totalPnl += pnl
            prevMarketValue += prevValue
            marketValue += price * position.quantity
            rows.add(
                Row(
                    code = position.code,
                    name = position.name,
                    quantity = position.quantity,
                    price = price,
                    prevClose = prevClose,
                    pnl = pnl,
                    changePct = quote.changePct,
                    prevMarketValue = prevValue,
                )
            )
        }
        return Summary(
            rows = rows,
            totalPnl = totalPnl,
            totalPct = if (prevMarketValue > 0) totalPnl / prevMarketValue * 100 else null,
            prevMarketValue = prevMarketValue,
            marketValue = marketValue,
            missing = missing,
        )
    }

    /** 金额：带符号、千分位两位小数，如 `+1,234.56` / `-88.00`。 */
    fun money(value: Double): String = String.format(Locale.CHINA, "%+,.2f", value)

    /** 百分比：带符号两位小数，如 `+1.23%`。 */
    fun pct(value: Double): String = String.format(Locale.CHINA, "%+.2f%%", value)

    /** 市值：千分位整数元，与「总资金」同一套写法。 */
    fun amount(value: Double): String = String.format(Locale.CHINA, "%,.0f", value)

    /**
     * 合计一行文案，界面卡片与持仓报告共用。
     * 一只都算不出来时返回 null——不印 `+0.00 元` 假装今天没亏没赚。
     */
    fun summaryText(summary: Summary): String? {
        val totalPct = summary.totalPct ?: return null
        return "今日盈亏 ${money(summary.totalPnl)} 元（较昨日市值 ${pct(totalPct)}）" +
            "｜持仓市值 ${amount(summary.marketValue)} 元" +
            "｜昨日市值 ${amount(summary.prevMarketValue)} 元"
    }
}
