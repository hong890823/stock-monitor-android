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
    /** 强度分档用的短均线；缺数据不判强势档，落中性保持 M20 口径（见 [PullbackAnchorPolicy]）。 */
    val ma5: Double? = null,
    val ma10: Double? = null,
    /** 近 10 日收盘（旧→新），用于强势档的 MA5 站稳计数；不足 9 根不判强势。 */
    val recentCloses: List<Double> = emptyList(),
)

enum class SwingStatus(val label: String) {
    READY("买点已确认"), BOTTOM_TRIAL("筑底轻仓试错"), WAIT("买点待确认"),
    T_ONLY("仅机动仓波段观察"), BLOCKED("暂不加仓"),
}

enum class SwingDiagnosticKind {
    ELIGIBLE,
    WAITING_CONFIRMATION,
    DATA_GAP,
    FUNDING_BLOCKED,
    RISK_BLOCKED,
    OBSERVE_ONLY,
    STRATEGY_BLOCKED,
}

data class SwingDiagnosis(
    val kind: SwingDiagnosticKind,
    val passedConditions: List<String> = emptyList(),
    val blockers: List<String> = emptyList(),
    val dataGaps: List<String> = emptyList(),
    val fundingIssues: List<String> = emptyList(),
) {
    fun summary(): String = when (kind) {
        SwingDiagnosticKind.ELIGIBLE ->
            "已通过条件：${passedConditions.joinToString("、").ifBlank { "多周期、基本面、趋势和日线入场条件" }}"
        SwingDiagnosticKind.WAITING_CONFIRMATION ->
            "等待确认：${blockers.joinToString("；").ifBlank { "入场条件尚未完成" }}"
        SwingDiagnosticKind.DATA_GAP ->
            "数据缺口：${dataGaps.joinToString("；").ifBlank { "证据不完整或已过期" }}"
        SwingDiagnosticKind.FUNDING_BLOCKED ->
            "资金问题：${fundingIssues.joinToString("；").ifBlank { "现金、仓位或风险预算不足" }}"
        SwingDiagnosticKind.RISK_BLOCKED ->
            "策略阻碍：${blockers.joinToString("；").ifBlank { "风险优先，暂不新增资金" }}"
        SwingDiagnosticKind.OBSERVE_ONLY ->
            "仅观察：${blockers.joinToString("；").ifBlank { "保留机动仓观察" }}"
        SwingDiagnosticKind.STRATEGY_BLOCKED ->
            "策略阻碍：${blockers.joinToString("；").ifBlank { "普通加仓条件未通过" }}"
    }

    companion object {
        fun from(decision: SwingDecision): SwingDiagnosis {
            val reason = decision.reason.trim()
            if (decision.addQuantity > 0.0) {
                return SwingDiagnosis(
                    kind = SwingDiagnosticKind.ELIGIBLE,
                    passedConditions = listOf("多周期背景", "基本面", "趋势", "日线回踩", "潜在盈亏比"),
                )
            }
            val funding = listOf("现金", "仓位", "风险余额", "最低申报", "最多聚焦", "资金优先")
                .filter { reason.contains(it) }
            if (funding.isNotEmpty()) {
                return SwingDiagnosis(
                    kind = SwingDiagnosticKind.FUNDING_BLOCKED,
                    fundingIssues = listOf(reason),
                )
            }
            val data = listOf("缺少", "缺失", "过期", "无法核实", "数据不足", "证据")
                .filter { reason.contains(it) }
            if (data.isNotEmpty()) {
                return SwingDiagnosis(
                    kind = SwingDiagnosticKind.DATA_GAP,
                    dataGaps = listOf(reason),
                )
            }
            if (decision.status == SwingStatus.T_ONLY) {
                return SwingDiagnosis(SwingDiagnosticKind.OBSERVE_ONLY, blockers = listOf(reason))
            }
            if (decision.status == SwingStatus.WAIT) {
                return SwingDiagnosis(SwingDiagnosticKind.WAITING_CONFIRMATION, blockers = listOf(reason))
            }
            val risk = listOf("风险", "高位", "退出", "深套", "封板", "偏弱", "破位", "不新增")
                .any { reason.contains(it) }
            return SwingDiagnosis(
                kind = if (risk) SwingDiagnosticKind.RISK_BLOCKED else SwingDiagnosticKind.STRATEGY_BLOCKED,
                blockers = listOf(reason),
            )
        }
    }
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
    /** 回踩锚位口径（如「强势浅回踩锚（MA5）」「MA20回踩口径」），供报告与补仓测算展示。 */
    val anchorLabel: String? = null,
) {
    val diagnosis: SwingDiagnosis get() = SwingDiagnosis.from(this)

    fun action(): String = if (addQuantity > 0) {
        "按分析价${money(price!!)}测算，本次最多追加${shares(addQuantity)}股，预计占用${money(estimatedCash)}元（含预留费用），" +
            "加后仓位${money(afterPositionPct!!)}%；分批执行，不一次打满（见分批口径）"
    } else "新增资金0股：${diagnosis.summary()}（$reason）"

    fun levels(): String = if (entryLow != null && entryHigh != null && riskPrice != null && target != null) {
        "参考区间${money(entryLow)}～${money(entryHigh)}" +
            (anchorLabel?.let { "（$it）" } ?: "") +
            "｜失效参考${money(riskPrice)}｜压力参考${money(target)}"
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
    val focusedRows: List<SwingDecision> get() = rows.filter { it.addQuantity > 0 }

    fun validatedFor(portfolio: Portfolio): SwingPlan {
        if (portfolioSnapshot == portfolio) return this
        val reason = "持仓或资金已变化，请重新分析"
        return copy(capitalIssue = reason, rows = rows.map {
            it.copy(addQuantity = 0.0, estimatedCash = 0.0, afterPositionPct = null, reason = reason)
        })
    }

    fun headline(): String {
        if (focusedRows.isEmpty()) return "本轮不建议新增买入，追加0股"
        val names = focusedRows.joinToString("、") { it.name }
        val quantity = focusedRows.sumOf { it.addQuantity }
        return "本轮重点${names}，合计最多追加${shares(quantity)}股（按分析价测算）"
    }

    fun reportLines(): List<String> = buildList {
        add("波段加仓优先级｜几天到几周｜$asOf")
        add(headline())
        capitalIssue?.let { add("资金检查：$it") }
        allocationCapital?.let { add("额度基数：${money(it)}元（配置总资金与已录入持仓市值加现金取较小值）") }
        focusedRows.forEachIndexed { index, row ->
            add("重点${index + 1}：${row.name}｜${row.action()}")
            add(row.levels())
        }
        if (focusedRows.isNotEmpty()) add(AddBatchPolicy.summary())
        rows.take(3).forEachIndexed { index, row ->
            add("${index + 1}. ${row.name}｜${row.status.label}｜${if (row.addQuantity > 0) "重点${focusedRows.indexOf(row) + 1}" else "追加0股"}：${row.reason}")
            add("诊断：${row.diagnosis.summary()}")
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
    const val MAX_FOCUS_POSITIONS = 3
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
            // 筑底轻仓试错同样是可以分配新资金的档位（只是额度压到初始仓位 25%）。
            val allocatable = decision.status == SwingStatus.READY ||
                decision.status == SwingStatus.BOTTOM_TRIAL
            if (!allocatable || input == null) decision
            else if (capitalIssue != null) decision.copy(reason = capitalIssue)
            else decision
        }.sortedWith(compareByDescending<SwingDecision> { it.addQuantity > 0 }
            .thenBy { it.status.ordinal }.thenByDescending { it.score }.thenBy { it.code })
        var remainingCash = availableCapital
        var focusCount = 0
        val rows = candidates.map { row ->
            val allocatable = row.status == SwingStatus.READY || row.status == SwingStatus.BOTTOM_TRIAL
            if (!allocatable || capitalIssue != null || focusCount >= MAX_FOCUS_POSITIONS) {
                if (allocatable && capitalIssue == null && focusCount >= MAX_FOCUS_POSITIONS) {
                    row.copy(
                        reason = "技术条件通过，本轮最多聚焦${MAX_FOCUS_POSITIONS}只，保留候补",
                    )
                } else {
                    row
                }
            } else {
                val position = holdings.first { it.code == row.code }
                val allocated = allocate(
                    decision = row,
                    position = position,
                    portfolio = portfolio.copy(totalCapital = allocationCapital!!),
                    cashBudget = remainingCash,
                )
                if (allocated.addQuantity > 0) {
                    focusCount += 1
                    remainingCash = (remainingCash - allocated.estimatedCash).coerceAtLeast(0.0)
                    allocated
                } else {
                    allocated
                }
            }
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
        // 只有“完全没有该股证据”才算组合敞口未知；停牌股行情日期过期时仍可用最后收盘价估算组合，
        // 只阻断该股自身的追加（assess 会给出“行情过期、停牌或价格无效”），不拖累其它持仓。
        if (holdings.any { evidence[it.code] == null }) return "缺少持仓现价，无法核实组合剩余资金"
        val holdingValue = holdings.sumOf { position ->
            val quote = evidence.getValue(position.code).quote
            if (quote.price.isFinite() && quote.price > 0) position.quantity * quote.price else 0.0
        }
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
        // 回踩区间按强度分档选锚：强势给 MA5/突破位浅锚，中性维持 M20 口径，弱势不给区间。
        val tier = PullbackAnchorPolicy.tierOf(
            price = quote.price,
            ma5 = input.ma5,
            ma10 = input.ma10,
            ma20 = input.ma20,
            recentCloses = input.recentCloses,
            trendWeak = entry.trendWeak,
            majorTrendDown = trendAssessment?.major == MajorTrend.DOWN,
        )
        val band = PullbackAnchorPolicy.bandOf(
            tier = tier,
            price = quote.price,
            ma5 = input.ma5,
            ma20 = input.ma20,
            priorHigh = entry.priorHigh,
            stop = stop,
            target = target,
            minRewardRisk = MIN_REWARD_RISK,
        )
        val low = band.low
        val high = band.high
        if (low == null || high == null || quote.price !in low..high) {
            return base.copy(status = SwingStatus.WAIT, reason = band.offBandReason(quote.price, input.atrPct))
        }
        val score = 60 + (if (cycles.monthTrend == SwingTrend.UP) 20 else 10) + (rewardRisk.coerceAtMost(4.0) * 5).toInt()
        val bandNote = band.onBandNote()
        return base.copy(status = if (bottoming) SwingStatus.BOTTOM_TRIAL else SwingStatus.READY, score = score,
            reason = (if (bottoming) "大趋势筑底，最多按初始仓位25%轻仓试错；" else "") +
                "${cycles.summary()}；日线回踩条件通过，潜在盈亏比${money(rewardRisk)}倍" +
                (if (bandNote.isNotEmpty()) "；$bandNote" else "") +
                if (input.sectorRegime == null) "；板块趋势暂缺" else "",
            price = quote.price, entryLow = low, entryHigh = high, riskPrice = stop, target = target, rewardRisk = rewardRisk,
            anchorLabel = band.anchorLabel)
    }

    /**
     * 限额与「加后仓位」都按额度基数（min(总资金, 市值+现金)）计算：浮亏时基数缩水、
     * 更保守，浮盈被钳回总资金、不会放大可用现金（见 unverifiedCapitalCannotEnlarge
     * TheAllocationBudget 用例）。与 AddPositionStrategy 的 totalCapital 口径差异是有意的。
     */
    private fun allocate(
        decision: SwingDecision,
        position: Position,
        portfolio: Portfolio,
        cashBudget: Double,
    ): SwingDecision {
        val price = decision.price!!
        val availableCapital = cashBudget.coerceAtLeast(0.0)
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
