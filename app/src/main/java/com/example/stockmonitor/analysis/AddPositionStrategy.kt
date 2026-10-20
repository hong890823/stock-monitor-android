package com.example.stockmonitor.analysis

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/** 候选补仓价位：名字 + 价格，来自波段区间 / 支撑 / MA20 / 现价等现有分析。 */
data class AddLevel(val label: String, val price: Double)

/** 单个候选价位的补仓测算。 */
data class AddLevelPlan(
    val label: String,
    val price: Double,
    /**
     * 摊到目标成本所需的精确股数；买入价无法收敛到目标成本时为 null
     * （例如摊薄时买入价仍高于目标成本）。
     */
    val requiredShares: Double?,
    /** 按手取整后可执行的股数；不足一手为 0。 */
    val shares: Double,
    val cashNeeded: Double,
    /** 取整后的实际平均成本，与目标成本可能有零点几分偏差。 */
    val afterAverageCost: Double?,
    /** 补仓后该股占总资金比例（%）；未设置总资金时为 null。 */
    val afterPositionPct: Double?,
    val feasible: Boolean,
    val note: String,
)

/** 按整手反推入手价：补 n 手要在什么价以下买入才能摊到目标成本。 */
data class LotPlan(val lots: Int, val shares: Double, val buyPrice: Double)

data class AddPositionPlan(
    val holdingShares: Double,
    val averageCost: Double,
    val targetCost: Double,
    val currentPrice: Double?,
    val stopPrice: Double?,
    val levels: List<AddLevelPlan>,
    val lots: List<LotPlan>,
    val suggested: AddLevelPlan?,
    val warnings: List<String>,
    val calculatedAt: String,
)

enum class CostReductionTrend {
    UPTREND,
    RANGE,
    DOWNTREND,
    UNKNOWN,
}

enum class CostStrategyType(val label: String) {
    BUY_DIP("回落补仓"),
    SELL_REBUY("先卖后买"),
    REDUCE_RISK("分批减仓"),
}

enum class CostTradeAction(val label: String) {
    BUY("买入"),
    SELL("卖出"),
}

data class CostTradeLeg(
    val action: CostTradeAction,
    val label: String,
    val price: Double,
    val shares: Double,
    val amount: Double,
    val note: String,
)

data class CostReductionOption(
    val type: CostStrategyType,
    val title: String,
    val legs: List<CostTradeLeg>,
    val afterShares: Double,
    val afterCost: Double?,
    val estimatedFees: Double,
    val feasible: Boolean,
    val reason: String,
)

data class CostReductionMarket(
    val currentPrice: Double? = null,
    val support: Double? = null,
    val resistance: Double? = null,
    val ma20: Double? = null,
    val entryLow: Double? = null,
    val entryHigh: Double? = null,
    val stopPrice: Double? = null,
    val trend: CostReductionTrend = CostReductionTrend.UNKNOWN,
    val buyConfirmed: Boolean = false,
    val signal: String? = null,
    val marketNote: String? = null,
)

data class CostReductionPlan(
    val holdingShares: Double,
    val averageCost: Double,
    val targetCost: Double,
    val market: CostReductionMarket,
    val options: List<CostReductionOption>,
    val recommended: CostReductionOption?,
    val warnings: List<String>,
    val calculatedAt: String,
)

/**
 * 反向补仓测算：输入目标平均成本，结合现有分析价位回答「在什么价位补多少股」。
 *
 * 摊薄公式（买入 n 股 @ p 后成本 = 目标 t）：
 * `n = 持仓 × (现成本 − t) / (t − p)`。
 * 买入价必须比目标成本更低才能收敛；价格越低所需股数越少，所以候选价位全部来自
 * 支撑 / 回踩区间等**偏低**的分析参考，而不是随手编几个数字。
 */
object AddPositionStrategy {

    /** 补一手的股数；与 [DeepLossPolicy.LOT]、[TrendEntryStrategy.LOT] 同口径。 */
    const val LOT = 100.0

    /** 反推整手入手价时演示的手数。 */
    private val LOT_SAMPLES = listOf(1, 2, 3, 5)

    /** 建议价位的优先顺序：回踩区间最贴合项目买点口径，现价垫底。 */
    private val PREFERENCE = listOf("波段回踩区间下沿", "波段回踩区间上沿", "支撑位", "MA20回踩参考", "现价")

    private const val COST_FEE_RATE = 0.001
    private const val COST_MIN_FEE = 5.0

    fun plan(
        code: String,
        holdingShares: Double,
        averageCost: Double,
        targetCost: Double,
        levels: List<AddLevel> = emptyList(),
        currentPrice: Double? = null,
        totalCapital: Double = 0.0,
        maxPositionPct: Double = 0.0,
        availableCapital: Double? = null,
        stopPrice: Double? = null,
    ): AddPositionPlan {
        if (listOf(holdingShares, averageCost, targetCost).any { !it.isFinite() } ||
            currentPrice?.isFinite() == false || stopPrice?.isFinite() == false ||
            availableCapital?.isFinite() == false ||
            holdingShares <= 0 || averageCost <= 0 || targetCost <= 0 ||
            currentPrice != null && currentPrice <= 0 ||
            stopPrice != null && stopPrice <= 0 ||
            availableCapital != null && availableCapital < 0
        ) {
            throw IllegalArgumentException("持仓股数、平均成本和目标成本必须是大于0的有效数字")
        }

        val levelPlans = levels.map { level -> assessLevel(code, level, holdingShares, averageCost, targetCost, totalCapital, maxPositionPct, availableCapital, stopPrice) }
        val lots = LOT_SAMPLES.map { n ->
            val shares = n * LOT
            val price = (targetCost * (holdingShares + shares) - holdingShares * averageCost) / shares
            LotPlan(n, shares, price)
        }.filter { it.buyPrice.isFinite() && it.buyPrice > 0 }

        val warnings = buildList {
            if (currentPrice != null && DeepLossPolicy.isDeepLoss(averageCost, currentPrice)) {
                add(
                    "浮亏已达深套口径（阈值 ${String.format(Locale.CHINA, "%.1f", DeepLossPolicy.deepLossThresholdPct(null))}%，" +
                        "有 ATR 时按波动率缩放）。项目口径：深套不盲目为摊成本补仓，" +
                        "等缩量止跌或站回支撑后再小仓补（约为初始仓位的一半），再创新低则取消补仓。"
                )
            }
            when {
                abs(targetCost - averageCost) < 1e-9 -> add("目标成本等于当前平均成本，没有摊薄空间，无需补仓。")
                targetCost > averageCost -> add("目标成本高于当前平均成本，属于加仓抬高成本，不是摊薄；盈亏平衡价会同步上移。")
            }
            stopPrice?.let {
                add("失效参考 ${money(it)} 元：候选买入价跌破该价说明结构走坏，补仓容易接飞刀，先按止损口径处理。")
            }
            if (availableCapital != null) add("本次按可用资金 ${money(availableCapital)} 元约束股数。")
            add("未计入交易费用（佣金 / 印花税 / 过户费），实际补后成本会略高。")
        }

        val suggested = levelPlans
            .filter { it.feasible && it.requiredShares != null }
            .minByOrNull { PREFERENCE.indexOf(it.label).let { i -> if (i < 0) Int.MAX_VALUE else i } }

        return AddPositionPlan(
            holdingShares = holdingShares,
            averageCost = averageCost,
            targetCost = targetCost,
            currentPrice = currentPrice,
            stopPrice = stopPrice,
            levels = levelPlans,
            lots = lots,
            suggested = suggested,
            warnings = warnings,
            calculatedAt = TimeUtils.nowText(),
        )
    }

    /**
     * 降低账面持仓成本的买卖策略。成本采用“原始成本基础－已实现盈亏－费用”的摊薄口径，
     * 所以同股数的高抛低吸会降低持仓成本；单纯追加买入也仍单独给出。
     */
    fun costReductionPlan(
        code: String = "",
        holdingShares: Double,
        averageCost: Double,
        targetCost: Double,
        market: CostReductionMarket,
        extraBuyLevels: List<AddLevel> = emptyList(),
        totalCapital: Double = 0.0,
        maxPositionPct: Double = 0.0,
        availableCapital: Double? = null,
    ): CostReductionPlan {
        if (listOf(holdingShares, averageCost, targetCost).any { !it.isFinite() } ||
            holdingShares <= 0 || averageCost <= 0 || targetCost <= 0
        ) {
            throw IllegalArgumentException("持仓股数、当前成本和目标成本必须是大于0的有效数字")
        }
        if (targetCost >= averageCost) {
            throw IllegalArgumentException("降低成本策略的目标成本必须低于当前持仓成本")
        }
        if (availableCapital?.isFinite() == false || availableCapital != null && availableCapital < 0) {
            throw IllegalArgumentException("可用资金必须是大于等于0的有效数字")
        }

        val buyLevels = buildList {
            addAll(extraBuyLevels)
            market.entryLow?.let { add(AddLevel("波段回踩区间下沿", it)) }
            market.support?.let { add(AddLevel("支撑位", it)) }
            market.ma20?.let { add(AddLevel("MA20回踩参考", it)) }
            market.currentPrice?.let { add(AddLevel("现价", it)) }
        }.filter { it.price.isFinite() && it.price > 0 }
            .distinctBy { String.format(Locale.CHINA, "%.2f", it.price) }

        val buyOptions = buyLevels.mapNotNull {
            buyDipOption(code, holdingShares, averageCost, targetCost, it, market, totalCapital, maxPositionPct, availableCapital)
        }
        val sellLevels = buildList {
            market.resistance?.let { add(AddLevel("阻力位", it)) }
            market.entryHigh?.let { add(AddLevel("波段区间上沿", it)) }
            market.currentPrice?.let { add(AddLevel("现价", it)) }
        }.filter { it.price.isFinite() && it.price > 0 }
            .distinctBy { String.format(Locale.CHINA, "%.2f", it.price) }
        val sellRebuyOptions = sellLevels.flatMap { sell ->
            buyLevels.mapNotNull { buy ->
                sellRebuyOption(code, holdingShares, averageCost, targetCost, sell, buy, market, availableCapital)
            }
        }
        val reduceOption = reduceRiskOption(code, holdingShares, market)

        val validBuy = buyOptions.filter { it.feasible }
            .minByOrNull { option ->
                PREFERENCE.indexOf(option.legs.first().label).let { index ->
                    if (index < 0) Int.MAX_VALUE else index
                }
            }
        val validSellRebuy = sellRebuyOptions.filter { it.feasible }
            .maxByOrNull { it.legs[0].price - it.legs[1].price }
        val recommended = when (market.trend) {
            CostReductionTrend.UPTREND -> validBuy ?: validSellRebuy
            CostReductionTrend.RANGE -> validSellRebuy ?: validBuy
            CostReductionTrend.UNKNOWN -> validSellRebuy ?: validBuy
            CostReductionTrend.DOWNTREND -> reduceOption
        }

        val warnings = buildList {
            if (market.trend == CostReductionTrend.DOWNTREND) {
                add("趋势和信号偏弱：不盲目为摊成本补仓，优先减仓或等待重新站回支撑/MA20确认。")
            }
            if (market.stopPrice != null && buyLevels.any { it.price < market.stopPrice }) {
                add("有候选价低于失效参考 ${money(market.stopPrice)} 元，接回前须重新确认止跌，不能只按目标成本成交。")
            }
            if (market.marketNote?.isNotBlank() == true) add("项目分析：${market.marketNote}")
            add("成本按原始持仓成本减去已实现盈亏和费用的摊薄口径计算，与券商“买入均价”显示可能不同。")
            add("价格和趋势变化后必须重测；先卖后买存在卖飞、跳空及 T+1 流动性风险。")
            add("未精确模拟佣金、印花税、过户费和滑点；仅作执行参考，不构成投资建议。")
        }

        return CostReductionPlan(
            holdingShares = holdingShares,
            averageCost = averageCost,
            targetCost = targetCost,
            market = market,
            options = listOfNotNull(validSellRebuy, validBuy, reduceOption),
            recommended = recommended,
            warnings = warnings,
            calculatedAt = TimeUtils.nowText(),
        )
    }

    fun formatCostReductionReport(plan: CostReductionPlan, stockLabel: String? = null): String {
        val label = stockLabel ?: "未指定股票"
        val gap = (plan.targetCost / plan.averageCost - 1) * 100
        val lines = mutableListOf(
            "🎯 降低持仓成本买卖策略",
            "对象：$label",
            "计算时间：${plan.calculatedAt}",
            "",
            "一、目标与行情",
            "当前持仓：${formatShares(plan.holdingShares)}股 @ ${money(plan.averageCost)}元",
            "目标持仓成本：${money(plan.targetCost)}元（较当前 ${String.format(Locale.CHINA, "%+.2f", gap)}%）",
            "趋势判断：${trendLabel(plan.market.trend)}",
        )
        plan.market.signal?.let { lines.add("个股信号：$it") }
        plan.market.marketNote?.let { lines.add("分析依据：$it") }
        listOf(
            "支撑位" to plan.market.support,
            "阻力位" to plan.market.resistance,
            "MA20" to plan.market.ma20,
            "波段回踩下沿" to plan.market.entryLow,
            "波段区间上沿" to plan.market.entryHigh,
            "失效参考" to plan.market.stopPrice,
        ).forEach { (name, value) -> value?.let { lines.add("$name：${money(it)}元") } }

        lines.add("")
        lines.add("二、优先策略")
        plan.recommended?.let { lines.addAll(optionLines(it)) }
            ?: lines.add("当前没有兼顾趋势、价位和风险的可执行组合；等待支撑确认或反弹到阻力位后再测算。")

        val alternatives = plan.options.filter { it != plan.recommended }
        if (alternatives.isNotEmpty()) {
            lines.add("")
            lines.add("三、备选策略")
            alternatives.forEach { lines.addAll(optionLines(it)) }
        }

        lines.add("")
        lines.add("四、执行规则")
        if (plan.recommended?.type == CostStrategyType.SELL_REBUY) {
            lines.add("先卖后买：只在冲高接近阻力/区间上沿时先减，不在下跌途中追卖；接回价必须低于卖出价并留足价差。")
            lines.add("当天接回的股份受 T+1 限制，次日才能再次卖出；若卖飞不追高，按新行情重新测算。")
        } else if (plan.recommended?.type == CostStrategyType.BUY_DIP) {
            lines.add("回落补仓：只等缩量止跌或重新站回支撑确认，分批执行；不得为了精确凑目标成本追价。")
        } else {
            lines.add("风险减仓：反弹无力或继续破位时分批减仓，不因目标成本而逆势补仓。")
        }
        lines.add("任一交易后都按实际成交股数、费用和成交价重新计算，不使用预测价冒充成交价。")

        lines.add("")
        lines.add("五、风险与口径")
        plan.warnings.forEach { lines.add("· $it") }
        return lines.joinToString("\n")
    }

    private fun buyDipOption(
        code: String,
        holdingShares: Double,
        averageCost: Double,
        targetCost: Double,
        level: AddLevel,
        market: CostReductionMarket,
        totalCapital: Double,
        maxPositionPct: Double,
        availableCapital: Double?,
    ): CostReductionOption? {
        val denominator = targetCost - level.price - COST_FEE_RATE * level.price
        if (!level.price.isFinite() || level.price <= 0 || denominator <= 0) return null
        val raw = holdingShares * (averageCost - targetCost) / denominator
        val shares = roundShares(code, raw)
        if (shares <= 0) return null
        val amount = shares * level.price
        val fees = estimateFee(amount)
        val afterShares = holdingShares + shares
        val afterCost = (holdingShares * averageCost + amount + fees) / afterShares
        val afterPositionPct = if (totalCapital > 0) afterShares * level.price / totalCapital * 100 else null
        val belowStop = market.stopPrice != null && level.price < market.stopPrice
        val cashBlocked = availableCapital != null && amount + fees > availableCapital
        val positionBlocked = maxPositionPct > 0 && afterPositionPct != null && afterPositionPct > maxPositionPct
        val trendBlocked = market.trend == CostReductionTrend.DOWNTREND
        val feasible = !belowStop && !cashBlocked && !positionBlocked && !trendBlocked
        val notes = buildList {
            if (shares < raw) add("按申报单位取整，实际成本会略高于目标")
            if (belowStop) add("低于失效参考，不执行")
            if (cashBlocked) add("占用资金超过可用现金")
            if (positionBlocked) add("补后仓位超过单股上限")
            if (trendBlocked) add("下跌趋势不补仓")
            if (isNotEmpty()) add("需等缩量止跌/站回支撑确认后再分批")
        }
        val leg = CostTradeLeg(
            action = CostTradeAction.BUY,
            label = level.label,
            price = level.price,
            shares = shares,
            amount = amount + fees,
            note = notes.joinToString("；").ifEmpty { "等回落企稳后分批买入" },
        )
        return CostReductionOption(
            type = CostStrategyType.BUY_DIP,
            title = "在${level.label}分批补仓",
            legs = listOf(leg),
            afterShares = afterShares,
            afterCost = afterCost,
            estimatedFees = fees,
            feasible = feasible,
            reason = leg.note,
        )
    }

    private fun sellRebuyOption(
        code: String,
        holdingShares: Double,
        averageCost: Double,
        targetCost: Double,
        sellLevel: AddLevel,
        buyLevel: AddLevel,
        market: CostReductionMarket,
        availableCapital: Double?,
    ): CostReductionOption? {
        val netSpread = sellLevel.price - buyLevel.price -
            COST_FEE_RATE * (sellLevel.price + buyLevel.price)
        if (netSpread <= 0 || sellLevel.price <= buyLevel.price) return null
        val raw = holdingShares * (averageCost - targetCost) / netSpread
        val shares = minOf(roundShares(code, raw), holdingShares)
        if (shares <= 0) return null
        val sellAmount = shares * sellLevel.price
        val buyAmount = shares * buyLevel.price
        val fees = estimateFee(sellAmount + buyAmount)
        val afterCost = averageCost - ((sellAmount - buyAmount) - fees) / holdingShares
        val belowStop = market.stopPrice != null && buyLevel.price < market.stopPrice
        val cashNeeded = maxOf(0.0, buyAmount + fees - sellAmount)
        val cashBlocked = availableCapital != null && cashNeeded > availableCapital
        val trendBlocked = market.trend == CostReductionTrend.DOWNTREND
        val feasible = !belowStop && !cashBlocked && !trendBlocked
        val notes = buildList {
            if (shares < raw) add("按申报单位取整，实际摊薄会略少于目标")
            if (belowStop) add("接回价低于失效参考，暂不接回")
            if (cashBlocked) add("先卖后仍需补充现金超过可用资金")
            if (trendBlocked) add("下跌趋势暂停做T接回")
            add("卖出后等回踩确认再接回，不挂单追成交")
        }
        val sellLeg = CostTradeLeg(
            action = CostTradeAction.SELL,
            label = sellLevel.label,
            price = sellLevel.price,
            shares = shares,
            amount = sellAmount,
            note = "先冲高减出${formatShares(shares)}股",
        )
        val buyLeg = CostTradeLeg(
            action = CostTradeAction.BUY,
            label = buyLevel.label,
            price = buyLevel.price,
            shares = shares,
            amount = buyAmount + fees,
            note = "回踩确认后接回${formatShares(shares)}股",
        )
        return CostReductionOption(
            type = CostStrategyType.SELL_REBUY,
            title = "在${sellLevel.label}卖出、${buyLevel.label}接回",
            legs = listOf(sellLeg, buyLeg),
            afterShares = holdingShares,
            afterCost = afterCost,
            estimatedFees = fees,
            feasible = feasible,
            reason = notes.joinToString("；"),
        )
    }

    private fun reduceRiskOption(
        code: String,
        holdingShares: Double,
        market: CostReductionMarket,
    ): CostReductionOption {
        val shares = roundShares(code, holdingShares / 3).coerceAtMost(holdingShares)
        val price = market.currentPrice ?: market.resistance ?: market.entryHigh ?: return CostReductionOption(
            type = CostStrategyType.REDUCE_RISK,
            title = "等待反弹确认后分批减仓",
            legs = emptyList(),
            afterShares = holdingShares,
            afterCost = null,
            estimatedFees = 0.0,
            feasible = false,
            reason = "缺少可执行卖出参考价",
        )
        val amount = shares * price
        val fees = estimateFee(amount)
        val leg = CostTradeLeg(
            action = CostTradeAction.SELL,
            label = if (market.currentPrice != null) "当前价/反弹确认" else "压力参考",
            price = price,
            shares = shares,
            amount = amount,
            note = "首次约减出可卖仓位的1/3，剩余按破位和趋势确认继续处理",
        )
        return CostReductionOption(
            type = CostStrategyType.REDUCE_RISK,
            title = "分批减仓降低风险，不强行接回",
            legs = listOf(leg),
            afterShares = holdingShares - shares,
            afterCost = null,
            estimatedFees = fees,
            feasible = shares > 0,
            reason = "趋势偏弱时，控制风险比精确摊到目标成本优先",
        )
    }

    private fun optionLines(option: CostReductionOption): List<String> = buildList {
        add("${option.title}｜${if (option.feasible) "可执行" else "当前不执行"}")
        option.legs.forEach { leg ->
            add("· ${leg.action.label} ${formatShares(leg.shares)}股 @ ${money(leg.price)}元｜${leg.label}｜${leg.note}")
        }
        option.afterCost?.let { add("预计持仓成本：${money(it)}元（持有 ${formatShares(option.afterShares)}股）") }
            ?: add("预计剩余持仓：${formatShares(option.afterShares)}股")
        add("预计费用：约 ${money(option.estimatedFees)}元｜${option.reason}")
    }

    private fun trendLabel(trend: CostReductionTrend): String = when (trend) {
        CostReductionTrend.UPTREND -> "偏强/上升趋势"
        CostReductionTrend.RANGE -> "震荡或趋势待确认"
        CostReductionTrend.DOWNTREND -> "偏弱/下降趋势"
        CostReductionTrend.UNKNOWN -> "分析数据不足"
    }

    private fun estimateFee(amount: Double): Double =
        if (!amount.isFinite() || amount <= 0) 0.0 else maxOf(COST_MIN_FEE, amount * COST_FEE_RATE)

    private fun assessLevel(
        code: String,
        level: AddLevel,
        holdingShares: Double,
        averageCost: Double,
        targetCost: Double,
        totalCapital: Double,
        maxPositionPct: Double,
        availableCapital: Double?,
        stopPrice: Double?,
    ): AddLevelPlan {
        val price = level.price
        fun result(required: Double?, shares: Double, cash: Double, after: Double?, pct: Double?, feasible: Boolean, note: String) =
            AddLevelPlan(level.label, price, required, shares, cash, after, pct, feasible, note)

        if (!price.isFinite() || price <= 0) return result(null, 0.0, 0.0, null, null, false, "价格无效，跳过")
        if (abs(price - targetCost) < 1e-9) {
            return result(null, 0.0, 0.0, null, null, false, "买入价等于目标成本，只能无限逼近，无法真正达到")
        }
        // n = H(C−T)/(T−p) 要为正，(T−C) 与 (T−p) 必须反号：摊薄时买价须低于目标，抬高时须高于目标。
        val converges = (targetCost - averageCost) * (targetCost - price) < 0
        if (!converges) {
            // 收敛不到目标时成本只能向买入价靠拢，买入价本身就是摊薄/抬升的极限。
            val note = when {
                abs(targetCost - averageCost) < 1e-9 -> "目标成本等于现成本，无需补仓"
                targetCost < averageCost && price >= averageCost -> "买入价不低于现成本，无法摊薄"
                targetCost < averageCost -> "买入价仍高于目标成本，最多摊薄到接近 ${money(price)} 元"
                else -> "买入价低于目标成本，无法把成本抬到目标"
            }
            return result(null, 0.0, 0.0, null, null, false, note)
        }

        val raw = holdingShares * (averageCost - targetCost) / (targetCost - price)
        val shares = roundShares(code, raw)
        if (shares <= 0) return result(raw, 0.0, 0.0, null, null, false, "需补不足一手（${formatShares(raw)} 股），暂无法执行")
        val cash = shares * price
        val after = (holdingShares * averageCost + shares * price) / (holdingShares + shares)
        val pct = if (totalCapital > 0) (holdingShares + shares) * price / totalCapital * 100 else null
        val notes = mutableListOf<String>()
        if (shares < raw - 1e-9) notes.add("按手取整后补后成本 ${money(after)} 元，与目标略有偏差")
        stopPrice?.takeIf { price < it }?.let { notes.add("低于失效参考 ${money(it)} 元，接刀风险") }
        if (availableCapital != null && cash > availableCapital) notes.add("占用 ${money(cash)} 元超过可用资金，需缩量或分批")
        if (maxPositionPct > 0 && pct != null && pct > maxPositionPct) {
            notes.add(
                "补后仓位 ${String.format(Locale.CHINA, "%.1f", pct)}% 超过单股上限 " +
                    "${String.format(Locale.CHINA, "%.1f", maxPositionPct)}%"
            )
        }
        val feasible = (availableCapital == null || cash <= availableCapital) &&
            !(maxPositionPct > 0 && pct != null && pct > maxPositionPct)
        return result(raw, shares, cash, after, pct, feasible, notes.joinToString("；").ifEmpty { "可执行" })
    }

    /** 取整口径与 [PortfolioSwing.roundQuantity] 一致；未识别代码时按整手 100 股向下取整。 */
    fun roundShares(code: String, raw: Double): Double {
        if (!raw.isFinite() || raw <= 0) return 0.0
        val normalized = code.lowercase()
        return when {
            normalized.startsWith("sh688") || normalized.startsWith("sh689") -> floor(raw).takeIf { it >= 200 } ?: 0.0
            normalized.startsWith("bj") -> floor(raw).takeIf { it >= 100 } ?: 0.0
            normalized.startsWith("sh6") || normalized.startsWith("sz0") || normalized.startsWith("sz3") -> floor(raw / LOT) * LOT
            else -> floor(raw / LOT) * LOT
        }
    }

    /** 补仓策略报告，版式与 [TradeCalculator.formatReport] 保持一致。 */
    fun formatReport(plan: AddPositionPlan, stockLabel: String? = null, analysisNote: String? = null): String {
        val label = stockLabel ?: "未指定股票"
        val gapPct = (plan.targetCost / plan.averageCost - 1) * 100
        val lines = mutableListOf(
            "🎯 补仓策略（目标成本）",
            "对象：$label",
            "计算时间：${plan.calculatedAt}",
            "",
            "一、目标",
            "当前持仓：${formatShares(plan.holdingShares)}股 @ ${money(plan.averageCost)}元",
            "目标平均成本：${money(plan.targetCost)}元（较当前 ${String.format(Locale.CHINA, "%+.2f", gapPct)}%）",
        )
        plan.currentPrice?.let { lines.add("当前价格：${money(it)}元") }

        lines.add("")
        lines.add("二、按分析价位测算（买入价越低，所需股数越少）")
        if (plan.levels.isEmpty()) {
            lines.add("没有可用的候选价位；填入股票后会取支撑 / MA20 / 波段回踩区间作为参考。")
        } else {
            plan.levels.forEachIndexed { index, level ->
                val head = "${index + 1}. ${level.label} ${money(level.price)}元"
                if (level.requiredShares == null) {
                    lines.add("$head｜${level.note}")
                } else {
                    lines.add(
                        "$head｜补 ${formatShares(level.shares)}股｜占用 ${money(level.cashNeeded)}元｜" +
                            "补后成本 ${level.afterAverageCost?.let { money(it) } ?: "—"}" +
                            (
                                level.afterPositionPct?.let {
                                    "｜仓位 ${String.format(Locale.CHINA, "%.1f", it)}%"
                                } ?: ""
                            )
                    )
                    lines.add("   ${level.note}")
                }
            }
            plan.suggested?.let {
                lines.add("建议价位：${it.label} ${money(it.price)}元，补 ${formatShares(it.shares)}股")
            } ?: lines.add("建议价位：当前候选价位均不可执行，参考下方整手反推或下调目标成本。")
        }

        if (plan.lots.isNotEmpty()) {
            lines.add("")
            lines.add("三、按整手反推入手价（补到目标成本）")
            plan.lots.forEach { lot ->
                lines.add("补 ${lot.lots}手（${formatShares(lot.shares)}股）需买入价 ≤ ${money(lot.buyPrice)}元")
            }
            lines.add("入手价高于上述值就摊不到目标成本；分批补仓时按加权重新测算。")
        }

        lines.add("")
        lines.add("四、风险与约束")
        plan.warnings.forEach { lines.add("· $it") }
        analysisNote?.let { lines.add("分析来源：$it") }
        lines.add("")
        lines.add("说明：本计算不写回持仓配置；价格变化后须重测，候选价位来自盘中分析快照。")
        lines.add("⚠️ 仅作计算参考，不构成投资建议。")
        return lines.joinToString("\n")
    }

    private fun money(value: Double): String = String.format(Locale.CHINA, "%.2f", value)

    private fun formatShares(value: Double): String =
        if (value == floor(value)) String.format(Locale.CHINA, "%.0f", value)
        else String.format(Locale.CHINA, "%.2f", value)
}
