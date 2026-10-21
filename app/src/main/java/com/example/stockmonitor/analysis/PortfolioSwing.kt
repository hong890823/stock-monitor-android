package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.Portfolio
import com.example.stockmonitor.core.Position
import com.example.stockmonitor.core.Quote
import com.example.stockmonitor.core.availableCapitalIsManual
import com.example.stockmonitor.core.resolvedAvailableCapital
import java.util.Locale
import kotlin.math.floor

data class SwingEvidence(
    val quote: Quote,
    val cycles: MultiTimeframeSummary,
    val entry: EntrySetup,
    val ma20: Double,
    val resistance: Double,
    val atrPct: Double?,
    val health: FundamentalHealthSnapshot?,
    val sectorRegime: MarketRegime?,
    val positionRisk: String? = null,
    val exitStage: HoldingExitStage? = null,
    val trendAssessment: TrendAssessment? = null,
    val boxRange: BoxRangeAssessment? = null,
    val highPosition: HighPositionDecision? = null,
)

enum class SwingStatus(val label: String) {
    READY("买点已确认"), BOTTOM_TRIAL("筑底轻仓试错"), WAIT("买点待确认"),
    T_ONLY("仅机动仓波段观察"), BLOCKED("暂不加仓"),
}

data class SwingDecision(
    val code: String,
    val name: String,
    val status: SwingStatus,
    val score: Int,
    val reason: String,
    val cycles: MultiTimeframeSummary? = null,
    val price: Double? = null,
    val entryLow: Double? = null,
    val entryHigh: Double? = null,
    val riskPrice: Double? = null,
    val target: Double? = null,
    val rewardRisk: Double? = null,
    val addQuantity: Double = 0.0,
    val estimatedCash: Double = 0.0,
    val afterPositionPct: Double? = null,
) {
    fun action(): String = if (addQuantity > 0) {
        "按分析价${money(price!!)}测算，本次最多追加${shares(addQuantity)}股，预计占用${money(estimatedCash)}元（含预留费用），加后仓位${money(afterPositionPct!!)}%"
    } else "新增资金0股：$reason"

    fun levels(): String = if (entryLow != null && entryHigh != null && riskPrice != null && target != null) {
        "参考区间${money(entryLow)}～${money(entryHigh)}｜失效参考${money(riskPrice)}｜压力参考${money(target)}"
    } else "买点或有效风险价位未齐备，不给执行区间"
}

data class SwingPlan(
    val rows: List<SwingDecision>,
    val asOf: String,
    val capitalIssue: String?,
    val portfolioSnapshot: Portfolio? = null,
    val allocationCapital: Double? = null,
) {
    val preferredCode: String? get() = rows.firstOrNull { it.addQuantity > 0 }?.code

    fun validatedFor(portfolio: Portfolio): SwingPlan {
        if (portfolioSnapshot == portfolio) return this
        val reason = "持仓或资金已变化，请重新分析"
        return copy(capitalIssue = reason, rows = rows.map {
            it.copy(addQuantity = 0.0, estimatedCash = 0.0, afterPositionPct = null, reason = reason)
        })
    }

    fun headline(): String {
        val preferred = rows.firstOrNull { it.addQuantity > 0 }
        return if (preferred == null) "本轮不建议新增买入，追加0股" else
            "本轮优先${preferred.name}，最多追加${shares(preferred.addQuantity)}股（按分析价测算）"
    }

    fun reportLines(): List<String> = buildList {
        add("波段加仓优先级｜几天到几周｜$asOf")
        add(headline())
        capitalIssue?.let { add("资金检查：$it") }
        allocationCapital?.let { add("额度基数：${money(it)}元（配置总资金与已录入持仓市值加现金取较小值）") }
        rows.firstOrNull { it.addQuantity > 0 }?.let {
            add(it.action())
            add(it.levels())
        }
        rows.take(3).forEachIndexed { index, row ->
            add("${index + 1}. ${row.name}｜${row.status.label}｜${if (row.addQuantity > 0) "本轮首选" else "追加0股"}：${row.reason}")
        }
        if (rows.size > 3) add("其余${rows.size - 3}只见逐股依据，均未分配本轮追加资金")
        if (rows.any { it.status == SwingStatus.T_ONLY }) {
            add("深套股不因浮亏加仓；做T接回需有真实减出记录，当前不推算接回股数。")
        }
        add("仅比较现有持仓；技术条件不是收益保证，价格变化后须重评，风险价不保证成交。")
    }
}

object PortfolioSwing {
    const val MIN_REWARD_RISK = 2.0
    private const val FEE_RATE = 0.001
    private const val MIN_FEE = 5.0

    fun evaluate(
        portfolio: Portfolio,
        evidence: Map<String, SwingEvidence>,
        market: MarketState?,
        asOf: String,
        nowMs: Long,
    ): SwingPlan {
        val holdings = portfolio.positions.filter { it.quantity.isFinite() && it.quantity > 0 }
        val availableCapital = portfolio.resolvedAvailableCapital()
        val capitalIssue = capitalIssue(portfolio, holdings, evidence, asOf)
        val allocationCapital = if (capitalIssue == null) minOf(portfolio.totalCapital,
            holdings.sumOf { it.quantity * evidence.getValue(it.code).quote.price } + availableCapital) else null
        val candidates = holdings.map { position ->
            val input = evidence[position.code]
            val decision = assess(position, input, market, asOf, nowMs)
            if (decision.status != SwingStatus.READY || input == null) decision
            else if (capitalIssue != null) decision.copy(reason = capitalIssue)
            else allocate(decision, position, portfolio.copy(totalCapital = allocationCapital!!))
        }.sortedWith(compareByDescending<SwingDecision> { it.addQuantity > 0 }
            .thenBy { it.status.ordinal }.thenByDescending { it.score }.thenBy { it.code })
        var allocated = false
        val rows = candidates.map { row ->
            if (row.addQuantity <= 0) row
            else if (!allocated) {
                allocated = true
                row
            } else row.copy(addQuantity = 0.0, estimatedCash = 0.0, afterPositionPct = null,
                reason = "技术条件通过，本轮资金优先给首选，保留候补")
        }
        return SwingPlan(rows, asOf, capitalIssue, portfolio.copy(positions = portfolio.positions.toMutableList()), allocationCapital)
    }

    private fun capitalIssue(
        portfolio: Portfolio,
        holdings: List<Position>,
        evidence: Map<String, SwingEvidence>,
        asOf: String,
    ): String? {
        val availableCapital = portfolio.resolvedAvailableCapital()
        if (portfolio.positions.any { !it.quantity.isFinite() || it.quantity < 0 } ||
            holdings.distinctBy { it.code }.size != holdings.size) return "持仓股数异常或代码重复，请核对配置"
        if (!portfolio.totalCapital.isFinite() || portfolio.totalCapital <= 0 ||
            !availableCapital.isFinite() || availableCapital < 0) return "总资金或可用资金无效，不能推算股数"
        if (listOf(portfolio.initialEntryPct, portfolio.maxPositionPct).any { !it.isFinite() || it <= 0 || it > 100 }) {
            return "本次追加比例或单股仓位上限无效"
        }
        if (holdings.any { position ->
                val quote = evidence[position.code]?.quote
                quote == null || quote.quoteDate != asOf || !quote.price.isFinite() || quote.price <= 0
            }) return "缺少持仓现价，无法核实组合剩余资金"
        val holdingValue = holdings.sumOf { it.quantity * evidence.getValue(it.code).quote.price }
        if (!holdingValue.isFinite() ||
            portfolio.availableCapitalIsManual() && holdingValue + availableCapital > portfolio.totalCapital + 0.01) {
            return "持仓市值与可用资金超过总资金，请更新资金配置"
        }
        if (availableCapital <= 0) return "可用资金为0，不假设另有现金"
        return null
    }

    private fun assess(position: Position, input: SwingEvidence?, market: MarketState?, asOf: String, nowMs: Long): SwingDecision {
        val base = SwingDecision(position.code, position.name, SwingStatus.BLOCKED, 0, "缺少多周期证据", input?.cycles)
        if (input == null) return base
        val quote = input.quote
        if (quote.code != position.code || quote.quoteDate != asOf || !quote.price.isFinite() || quote.price <= 0 ||
            !quote.volume.isFinite() || quote.volume <= 0 ||
            listOf(quote.prevClose, quote.open, quote.high, quote.low).any { !it.isFinite() || it <= 0 } ||
            quote.high < maxOf(quote.open, quote.price) || quote.low > minOf(quote.open, quote.price)) {
            return base.copy(reason = "行情过期、停牌或价格无效")
        }
        if (!position.cost.isFinite() || position.cost <= 0) return base.copy(reason = "持仓成本缺失，先核对深套与机动仓约束")
        if (input.exitStage == HoldingExitStage.EXIT) return base.copy(reason = "退出优先，不新增资金，不以做T替代退出")
        if (input.exitStage == HoldingExitStage.HIGH_REDUCE) return base.copy(reason = "高位转弱，优先减仓，暂停新增资金和做T接回")
        if (input.health?.health?.level == HealthLevel.ALERT) {
            return base.copy(reason = "基本面风险需核实，不新增资金，也不默认靠做T等待解套")
        }
        if (DeepLossPolicy.isDeepLoss(position.cost, quote.price, input.atrPct)) {
            return base.copy(status = SwingStatus.T_ONLY, reason = "深套不盲目补仓；保留机动仓波段评估，未企稳不接回")
        }
        input.positionRisk?.let { return base.copy(reason = it) }
        if (ObservationEligibility.isAtLimitUp(position.code, position.name, quote)) return base.copy(reason = "封板暂不参与，开板后重评")
        val trendAssessment = input.trendAssessment
        if (trendAssessment?.major == MajorTrend.DOWN || trendAssessment?.major == MajorTrend.MISSING) {
            return base.copy(reason = trendAssessment.reason)
        }
        val boxRange = input.boxRange
        if (boxRange != null && boxRange.state in setOf(
                BoxRangeState.BOX_RANGE,
                BoxRangeState.BREAKOUT_CANDIDATE,
                BoxRangeState.BREAKDOWN_RISK,
                BoxRangeState.BREAKDOWN_CONFIRMED,
                BoxRangeState.MISSING,
            )
        ) {
            return base.copy(reason = boxRange.summary)
        }
        if (input.highPosition?.riskPriority == true) {
            return base.copy(reason = "高位派发风险，停止新增资金并优先风险处置")
        }
        if (input.highPosition?.phase in setOf(
                HighPositionPhase.CONSOLIDATION,
                HighPositionPhase.SECOND_WAVE_CANDIDATE,
            )
        ) {
            return base.copy(reason = "高位整理或二波候选，等待突破确认，不新增资金")
        }
        val bottoming = trendAssessment?.major == MajorTrend.BOTTOMING
        if (bottoming && trendAssessment.allowsBottomReversal != true) {
            return base.copy(status = SwingStatus.WAIT, reason = "筑底证据尚未满足，不给试错资金")
        }
        val cycles = input.cycles
        cycles.issue?.let { return base.copy(reason = it) }
        if (cycles.asOf != asOf || cycles.ma250 == null || !cycles.ma250.isFinite() || cycles.ma250 <= 0) {
            return base.copy(reason = "长期证据过期或年线缺失")
        }
        val health = input.health
        if (health == null || nowMs - health.fetchedAtMs !in 0..FundamentalHealthPolicy.CACHE_TTL_MS) {
            return base.copy(reason = "基本面缺失或已过期，先更新核实")
        }
        if (health.health.level != HealthLevel.SOUND) return base.copy(reason = "基本面${health.health.level.label}，暂不投入新增资金")
        if (market?.ma20Pct == null || !market.ma20Pct.isFinite()) return base.copy(reason = "大盘趋势数据不足")
        if (market.regime in setOf(MarketRegime.WEAK, MarketRegime.COLLAPSE) ||
            input.sectorRegime in setOf(MarketRegime.WEAK, MarketRegime.COLLAPSE)) return base.copy(reason = "大盘或板块偏弱，暂不加仓")
        if (!bottoming && (cycles.weekTrend != SwingTrend.UP ||
                cycles.monthTrend !in setOf(SwingTrend.UP, SwingTrend.REPAIR) ||
                quote.price < cycles.ma250)
        ) return base.copy(status = SwingStatus.WAIT, reason = "${cycles.summary()}，长中期趋势尚未配合")
        val entry = input.entry
        if (entry.type != EntrySetupType.M20_PULLBACK || !entry.canBuy || !entry.confirmed ||
            !entry.volumeConfirmed || entry.trendWeak || entry.marketFilter == MarketFilter.HEADWIND) {
            return base.copy(status = SwingStatus.WAIT, score = 40, reason = "长中期背景通过，日线仍需缩量回踩企稳")
        }
        val stop = entry.stopPrice
        if (stop == null || !stop.isFinite() || stop <= 0 || stop >= quote.price || !input.ma20.isFinite() || input.ma20 <= 0) {
            return base.copy(reason = "缺少有效风险价位或回踩参考线")
        }
        val target = (listOf(input.resistance) + cycles.weeks.takeLast(52).map { it.high } +
            cycles.months.takeLast(24).map { it.high }).filter { it.isFinite() && it > quote.price }.minOrNull()
            ?: return base.copy(reason = "上方压力空间无法核实，不推算收益空间")
        val rewardRisk = (target - quote.price) / (quote.price - stop)
        if (rewardRisk < MIN_REWARD_RISK) return base.copy(status = SwingStatus.WAIT, reason = "距离上方压力太近，潜在盈亏比不足2倍")
        val low = maxOf(input.ma20 * (1 - TrendEntryStrategy.MA20_BREAK_TOLERANCE_PCT / 100), stop + 0.01)
        val high = minOf(input.ma20 * (1 + TrendEntryStrategy.PULLBACK_ABOVE_MA20_PCT / 100),
            (target + MIN_REWARD_RISK * stop) / (MIN_REWARD_RISK + 1))
        if (quote.price !in low..high) return base.copy(status = SwingStatus.WAIT, reason = "现价已离开回踩参考区间，不追价")
        val score = 60 + (if (cycles.monthTrend == SwingTrend.UP) 20 else 10) + (rewardRisk.coerceAtMost(4.0) * 5).toInt()
        return base.copy(status = if (bottoming) SwingStatus.BOTTOM_TRIAL else SwingStatus.READY, score = score,
            reason = (if (bottoming) "大趋势筑底，最多按初始仓位25%轻仓试错；" else "") +
                "${cycles.summary()}；日线回踩条件通过，潜在盈亏比${money(rewardRisk)}倍" +
                if (input.sectorRegime == null) "；板块趋势暂缺" else "",
            price = quote.price, entryLow = low, entryHigh = high, riskPrice = stop, target = target, rewardRisk = rewardRisk)
    }

    private fun allocate(decision: SwingDecision, position: Position, portfolio: Portfolio): SwingDecision {
        val price = decision.price!!
        val availableCapital = portfolio.resolvedAvailableCapital()
        val headroom = portfolio.totalCapital * portfolio.maxPositionPct / 100 - position.quantity * price
        val limits = mutableListOf(
            (availableCapital - MIN_FEE).coerceAtLeast(0.0) / (price * (1 + FEE_RATE)),
            headroom / price,
            portfolio.totalCapital * portfolio.initialEntryPct / 100 / price,
            (portfolio.totalCapital * TrendEntryStrategy.RISK_PER_TRADE_PCT / 100 - MIN_FEE).coerceAtLeast(0.0) /
                (price - decision.riskPrice!! + price * FEE_RATE),
        )
        if (decision.status == SwingStatus.BOTTOM_TRIAL) {
            limits += portfolio.totalCapital * portfolio.initialEntryPct * 0.25 / 100 / price
        }
        val raw = limits.minOrNull()!!
        val quantity = roundQuantity(position.code, raw)
        if (quantity <= 0) return decision.copy(reason = if (headroom <= 0) "单股仓位已到上限，不再追加" else
            "现金、仓位或风险余额不足最低申报数量，或市场暂不支持数量计算")
        val amount = quantity * price
        return decision.copy(addQuantity = quantity, estimatedCash = amount + maxOf(MIN_FEE, amount * FEE_RATE),
            afterPositionPct = (position.quantity + quantity) * price / portfolio.totalCapital * 100)
    }

    fun roundQuantity(code: String, raw: Double): Double {
        if (!raw.isFinite() || raw <= 0) return 0.0
        return when {
            code.startsWith("sh688") || code.startsWith("sh689") -> floor(raw).takeIf { it >= 200 } ?: 0.0
            code.startsWith("bj") -> floor(raw).takeIf { it >= 100 } ?: 0.0
            code.startsWith("sh6") || code.startsWith("sz0") || code.startsWith("sz3") -> floor(raw / 100) * 100
            else -> 0.0
        }
    }
}

private fun money(value: Double): String = String.format(Locale.CHINA, "%.2f", value)
private fun shares(value: Double): String = String.format(Locale.CHINA, "%.0f", value)
