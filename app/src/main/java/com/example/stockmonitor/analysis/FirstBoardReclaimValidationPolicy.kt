package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.FirstBoardReclaimObservation
import java.util.Locale

/**
 * 首板承接转强卡片的展示层校验（2026-11-26）：触发记录只是线索，卡片只列
 * 「当前有参与价值」的股票，没价值参与买入的过滤掉。
 *
 * 三个维度（任一不过即过滤，记录本身保留全量，过滤只影响卡片展示）：
 * 1. 时效：只把触发当日与次一交易日当「可参与」，更早的仅作复核不再混列；
 * 2. 守稳与承接质量：下探深度在合理带（太浅没洗盘、太深形同破位），
 *    且现价仍守住开盘价（收复成果），跌回去形态就走弱了；
 * 3. 资金持续性：最新主力资金未在持续流出（触发时的承接验证不能保证现在还在）。
 *
 * 缺数据按「无法验证参与价值」处理（过滤并注明），不在缺数据时放行。
 */
object FirstBoardReclaimValidationPolicy {

    /** 承接下探深度的合理带（相对开盘价 %）：太浅没洗盘，太深形同破位。 */
    const val MIN_DIP_PCT = -8.0
    const val MAX_DIP_PCT = -0.5

    /** 参与时效（触发后的交易日数）：0=当日、1=次日可参与，更早仅复核。 */
    const val MAX_PARTICIPATE_TRADING_DAYS = 1

    /** 资金持续流出阈值（当日主力净流出占成交额 %），与 [BuyPointFundFlowPolicy] 出货口径同尺。 */
    const val OUTFLOW_PCT: Double = BuyPointFundFlowPolicy.DISTRIBUTION_PCT

    enum class Category(val label: String) {
        TIMELINESS("过时效"),
        WEAKENED("形态走弱或承接不足"),
        OUTFLOW("资金流出"),
    }

    data class Verdict(
        val participate: Boolean,
        /** 未通过时的过滤类别；通过为 null。 */
        val category: Category? = null,
        val reason: String = "",
    )

    fun evaluate(
        record: FirstBoardReclaimObservation,
        currentPrice: Double?,
        today: String,
        tradingDates: List<String> = emptyList(),
        latestMainNetPct: Double? = null,
    ): Verdict {
        // 1) 时效
        val days = FirstBoardReclaimPolicy.tradingDaysAfter(record.tradingDay, today, tradingDates)
        if (days != null && days > MAX_PARTICIPATE_TRADING_DAYS) {
            return Verdict(
                false,
                Category.TIMELINESS,
                "触发于第${days}个交易日前，仅作复核",
            )
        }
        // 2) 承接质量：下探深度
        if (!(record.open > 0.0 && record.low > 0.0)) {
            return Verdict(false, Category.WEAKENED, "下探/开盘数据暂缺，无法验证承接")
        }
        val dipPct = (record.low / record.open - 1.0) * 100.0
        if (dipPct <= MIN_DIP_PCT) {
            return Verdict(false, Category.WEAKENED, String.format(Locale.CHINA, "下探过深 %.1f%%，形同破位", dipPct))
        }
        if (dipPct >= MAX_DIP_PCT) {
            return Verdict(false, Category.WEAKENED, String.format(Locale.CHINA, "下探仅 %.1f%%，洗盘有限", dipPct))
        }
        // 3) 守稳：现价仍守住开盘价
        val price = currentPrice?.takeIf { it.isFinite() && it > 0.0 }
            ?: return Verdict(false, Category.WEAKENED, "现价缺失，无法验证守稳")
        if (price < record.open) {
            return Verdict(
                false,
                Category.WEAKENED,
                String.format(Locale.CHINA, "现价 %.2f 已跌回开盘价 %.2f 下方", price, record.open),
            )
        }
        // 4) 资金持续性：最新主力资金未在持续流出
        val flow = latestMainNetPct?.takeIf { it.isFinite() }
        if (flow != null && flow <= -OUTFLOW_PCT) {
            return Verdict(
                false,
                Category.OUTFLOW,
                String.format(Locale.CHINA, "主力净流出 %.2f%%，出货形态", flow),
            )
        }
        return Verdict(true)
    }
}
