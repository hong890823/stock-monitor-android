package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.FundFlow
import java.time.LocalDate
import java.util.Locale

/**
 * 基于当日或最近有效缓存的资金流，归纳可能的主力意图。
 *
 * 没有同日有效资金流时保留最近一次有效结论，直到新的有效快照到达。
 * 缓存模式不会把旧资金流与新行情涨跌幅组合为当日价格确认。
 */
enum class MainForceDirection(val label: String) {
    INFLOW_CONFIRMED("主力进场"),
    INFLOW_ABSORPTION("主力承接"),
    OUTFLOW_CONFIRMED("主力出逃"),
    OUTFLOW_UNCONFIRMED("主力减仓"),
    TURNING_STRONG("主力回流"),
    TURNING_WEAK("主力派发"),
    DIVERGENT("资金分歧"),
    MIXED("主力观望"),
    INSUFFICIENT("样本不足"),
}

enum class MainForceConfidence(val label: String) {
    HIGH("证据较完整"),
    LIMITED("证据有限"),
    LOW("仅作观察"),
    CACHED("缓存待刷新"),
}

data class MainForceDirectionAssessment(
    val direction: MainForceDirection,
    val confidence: MainForceConfidence,
    val currentMainNetPct: Double?,
    val recent3AvgMainNetPct: Double?,
    val recent5AvgMainNetPct: Double?,
    val recent3PositiveDays: Int,
    val recent5PositiveDays: Int,
    /** 超大单与大单净额之和，仅作构成展示，不是独立确认。 */
    val largeOrderNetYuan: Double?,
    val largeOrderNetPct: Double?,
    val evidence: String,
    val summary: String,
    val reason: String,
)

object MainForceDirectionPolicy {
    private const val PRICE_CONFIRM_PCT = FundFlowPolicy.MIN_CHANGE_PCT
    private const val MAIN_DIRECTION_PCT = FundFlowPolicy.MIN_MAIN_NET_PCT

    fun assess(
        changePct: Double?,
        quoteDate: String?,
        history: List<FundFlow>,
    ): MainForceDirectionAssessment {
        // 过滤盘前/休市的零成交占位值；同日多次刷新仅使用该日最后一条有效快照。
        val samples = latestUsableSnapshotPerDate(history)
        val flow = samples.lastOrNull()
            ?: return unavailable("没有可用资金流缓存", null, FundFlowWindowPolicy.assess(emptyList()), samples)
        val window = FundFlowWindowPolicy.assess(samples)
        val isCurrent = datePart(flow.tradingDate) == datePart(quoteDate)
        val mainNetPct = flow.mainNetPct?.takeIf { it.isFinite() }
        val amountReliable = flow.amountYuan?.let {
            it.isFinite() && it >= FundFlowPolicy.MIN_RELIABLE_AMOUNT_YUAN
        } == true
        val largeOrderNetYuan = largeOrderNetYuan(flow)
        val largeOrderNetPct = largeOrderNetPct(flow)

        // 同日资金流字段不完整时不能用上一日缓存伪装成今天；直接等待同日完整快照。
        if (isCurrent && (mainNetPct == null || !amountReliable)) {
            return unavailable(
                if (mainNetPct == null) "当日主力净占比暂缺" else "当日成交额不足 ${formatAmount(FundFlowPolicy.MIN_RELIABLE_AMOUNT_YUAN)}",
                flow,
                window,
                samples,
            )
        }
        if (mainNetPct == null) return unavailable("最近有效缓存缺少主力净占比", flow, window, samples)

        val recent3 = samples.takeLast(FundFlowWindowPolicy.SHORT_WINDOW_DAYS)
        val continuousRecent3 = recent3.size == FundFlowWindowPolicy.SHORT_WINDOW_DAYS &&
            areConsecutiveWeekdays(recent3.mapNotNull { parseDate(it.tradingDate) })
        val mainInflow = mainNetPct >= MAIN_DIRECTION_PCT
        val mainOutflow = mainNetPct <= -MAIN_DIRECTION_PCT
        val persistentInflow = continuousRecent3 && window.tone == FundFlowWindowTone.PERSISTENT_INFLOW
        val persistentOutflow = continuousRecent3 && window.tone == FundFlowWindowTone.PERSISTENT_OUTFLOW
        val price = changePct?.takeIf { isCurrent && it.isFinite() }

        val direction = if (isCurrent) {
            directionWithCurrentPrice(
                persistentInflow,
                persistentOutflow,
                mainInflow,
                mainOutflow,
                price?.let { it > PRICE_CONFIRM_PCT } == true,
                price?.let { it < -PRICE_CONFIRM_PCT } == true,
            )
        } else {
            directionFromCache(persistentInflow, persistentOutflow, mainInflow, mainOutflow)
        }
        val evidence = evidence(window, samples, flow, largeOrderNetYuan, largeOrderNetPct, cached = !isCurrent)
        val confidence = when {
            !isCurrent -> MainForceConfidence.CACHED
            !continuousRecent3 || window.lowConfidence || price == null -> MainForceConfidence.LOW
            samples.size >= FundFlowWindowPolicy.LONG_WINDOW_DAYS &&
                areConsecutiveWeekdays(samples.takeLast(FundFlowWindowPolicy.LONG_WINDOW_DAYS).mapNotNull { parseDate(it.tradingDate) }) &&
                direction in setOf(MainForceDirection.INFLOW_CONFIRMED, MainForceDirection.OUTFLOW_CONFIRMED) ->
                MainForceConfidence.HIGH
            else -> MainForceConfidence.LIMITED
        }
        return MainForceDirectionAssessment(
            direction = direction,
            confidence = confidence,
            currentMainNetPct = mainNetPct,
            recent3AvgMainNetPct = window.recent3AvgMainNetPct,
            recent5AvgMainNetPct = window.recent5AvgMainNetPct,
            recent3PositiveDays = window.recent3PositiveDays,
            recent5PositiveDays = window.recent5PositiveDays,
            largeOrderNetYuan = largeOrderNetYuan,
            largeOrderNetPct = largeOrderNetPct,
            evidence = evidence,
            summary = "${direction.label}｜${confidence.label}",
            reason = "$evidence。${if (isCurrent) priceText(price) else "未取得同日资金流，当前价格不参与判断"}。${interpretation(direction)} 资金流按订单规模归类，不代表机构身份，也不构成交易指令。",
        )
    }

    private fun directionWithCurrentPrice(
        persistentInflow: Boolean,
        persistentOutflow: Boolean,
        mainInflow: Boolean,
        mainOutflow: Boolean,
        priceUp: Boolean,
        priceDown: Boolean,
    ): MainForceDirection = when {
        persistentInflow && mainInflow && priceUp -> MainForceDirection.INFLOW_CONFIRMED
        persistentInflow && mainInflow && priceDown -> MainForceDirection.INFLOW_ABSORPTION
        persistentInflow && mainOutflow -> MainForceDirection.TURNING_WEAK
        persistentOutflow && mainOutflow && priceDown -> MainForceDirection.OUTFLOW_CONFIRMED
        persistentOutflow && mainOutflow -> MainForceDirection.OUTFLOW_UNCONFIRMED
        persistentOutflow && mainInflow && priceUp -> MainForceDirection.TURNING_STRONG
        persistentOutflow && mainInflow -> MainForceDirection.DIVERGENT
        mainInflow && priceUp -> MainForceDirection.TURNING_STRONG
        mainOutflow && priceDown -> MainForceDirection.TURNING_WEAK
        (mainInflow && priceDown) || (mainOutflow && priceUp) -> MainForceDirection.DIVERGENT
        else -> MainForceDirection.MIXED
    }

    private fun directionFromCache(
        persistentInflow: Boolean,
        persistentOutflow: Boolean,
        mainInflow: Boolean,
        mainOutflow: Boolean,
    ): MainForceDirection = when {
        persistentInflow && mainInflow -> MainForceDirection.INFLOW_CONFIRMED
        persistentInflow && mainOutflow -> MainForceDirection.TURNING_WEAK
        persistentOutflow && mainOutflow -> MainForceDirection.OUTFLOW_CONFIRMED
        persistentOutflow && mainInflow -> MainForceDirection.TURNING_STRONG
        mainInflow -> MainForceDirection.TURNING_STRONG
        mainOutflow -> MainForceDirection.TURNING_WEAK
        else -> MainForceDirection.MIXED
    }

    private fun unavailable(
        reason: String,
        flow: FundFlow?,
        window: FundFlowWindowAssessment,
        samples: List<FundFlow>,
    ): MainForceDirectionAssessment {
        val largeOrderNetYuan = flow?.let(::largeOrderNetYuan)
        val largeOrderNetPct = flow?.let(::largeOrderNetPct)
        val facts = flow?.let { evidence(window, samples, it, largeOrderNetYuan, largeOrderNetPct, cached = true) }
        return MainForceDirectionAssessment(
            direction = MainForceDirection.INSUFFICIENT,
            confidence = MainForceConfidence.LOW,
            currentMainNetPct = flow?.mainNetPct?.takeIf { it.isFinite() },
            recent3AvgMainNetPct = window.recent3AvgMainNetPct,
            recent5AvgMainNetPct = window.recent5AvgMainNetPct,
            recent3PositiveDays = window.recent3PositiveDays,
            recent5PositiveDays = window.recent5PositiveDays,
            largeOrderNetYuan = largeOrderNetYuan,
            largeOrderNetPct = largeOrderNetPct,
            evidence = facts ?: reason,
            summary = "样本不足｜仅作观察",
            reason = "主力意图暂不判断：$reason。${facts?.let { "$it。" }.orEmpty()}资金流按订单规模归类，不代表机构身份，也不是交易指令。",
        )
    }

    private fun latestUsableSnapshotPerDate(history: List<FundFlow>): List<FundFlow> =
        history.filter { flow ->
            datePart(flow.tradingDate) != null &&
                flow.mainNetPct?.isFinite() == true &&
                flow.amountYuan?.let { it.isFinite() && it > 0.0 } == true
        }.groupBy { datePart(it.tradingDate) }
            .mapNotNull { (_, sameDay) -> sameDay.maxByOrNull { it.asOfMs } }
            .sortedBy { datePart(it.tradingDate) }
            .takeLast(FundFlowWindowPolicy.LONG_WINDOW_DAYS)

    /** 周末可自然跳过；长假断档只降低持续性可信度，不清掉最后一次有效方向。 */
    private fun areConsecutiveWeekdays(dates: List<LocalDate>): Boolean {
        if (dates.size < FundFlowWindowPolicy.SHORT_WINDOW_DAYS) return false
        return dates.zipWithNext().all { (previous, current) -> nextWeekday(previous) == current }
    }

    private fun nextWeekday(from: LocalDate): LocalDate {
        var next = from.plusDays(1)
        while (next.dayOfWeek.value >= 6) next = next.plusDays(1)
        return next
    }

    private fun evidence(
        window: FundFlowWindowAssessment,
        samples: List<FundFlow>,
        flow: FundFlow,
        largeOrderNetYuan: Double?,
        largeOrderNetPct: Double?,
        cached: Boolean,
    ): String = buildString {
        if (cached) append("最近有效缓存｜")
        append("主力：${formatNetFlow(flow.mainNetYuan, flow.mainNetPct)}")
        append(
            "｜近3日：${windowFlowText(samples, 3, window.recent3AvgMainNetPct, window.recent3PositiveDays)}",
        )
        append(
            "｜近5日：${windowFlowText(samples, 5, window.recent5AvgMainNetPct, window.recent5PositiveDays)}",
        )
        if (largeOrderNetYuan != null && largeOrderNetPct != null) {
            append("｜超大+大单构成：${formatNetFlow(largeOrderNetYuan, largeOrderNetPct)}")
        } else {
            append("｜超大/大单构成暂缺")
        }
    }

    private fun windowFlowText(
        samples: List<FundFlow>,
        days: Int,
        averagePct: Double?,
        positiveDays: Int,
    ): String {
        val recent = samples.takeLast(days)
        val netAmounts = recent.mapNotNull { it.mainNetYuan?.takeIf { value -> value.isFinite() } }
        val amountText = if (recent.isNotEmpty() && netAmounts.size == recent.size) {
            formatWindowNetAmount(netAmounts.sum())
        } else {
            "金额暂缺"
        }
        val inflowDays = if (recent.isEmpty()) 0 else positiveDays.coerceIn(0, recent.size)
        return "$amountText，平均占成交额 ${formatPct(averagePct)}（$inflowDays/${recent.size}天流入）"
    }

    private fun formatWindowNetAmount(value: Double): String = when {
        value > 0.0 -> "累计净流入 ${formatFlowAmount(value)}"
        value < 0.0 -> "累计净流出 ${formatFlowAmount(value)}"
        else -> "累计净流向 0"
    }

    private fun interpretation(direction: MainForceDirection): String = when (direction) {
        MainForceDirection.INFLOW_CONFIRMED -> "资金流入趋势与最近主力净流入一致。"
        MainForceDirection.INFLOW_ABSORPTION -> "多日资金仍偏流入但价格下跌，只能视为承接，尚未得到价格确认。"
        MainForceDirection.OUTFLOW_CONFIRMED -> "资金流出趋势与最近主力净流出一致。"
        MainForceDirection.OUTFLOW_UNCONFIRMED -> "多日和当日资金偏流出，但价格尚未同步走弱，继续观察。"
        MainForceDirection.TURNING_STRONG -> "最近主力资金回流，但多日趋势尚未充分反转，等待后续采样确认。"
        MainForceDirection.TURNING_WEAK -> "最近主力资金转弱，警惕短线资金派发。"
        MainForceDirection.DIVERGENT -> "价格与当日资金方向相反，不能按单一指标判断趋势。"
        MainForceDirection.MIXED -> "当日与多日资金没有形成一致方向。"
        MainForceDirection.INSUFFICIENT -> "样本或成交额不足，暂不判断。"
    }

    private fun largeOrderNetYuan(flow: FundFlow): Double? {
        val superLarge = flow.superLargeNetYuan?.takeIf { it.isFinite() } ?: return null
        val large = flow.largeNetYuan?.takeIf { it.isFinite() } ?: return null
        return superLarge + large
    }

    private fun largeOrderNetPct(flow: FundFlow): Double? {
        val amount = flow.amountYuan?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val net = largeOrderNetYuan(flow) ?: return null
        return net / amount * 100.0
    }

    private fun datePart(value: String?): String? =
        value?.substringBefore(' ')?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }

    private fun parseDate(value: String?): LocalDate? = datePart(value)?.let { text ->
        runCatching { LocalDate.parse(text) }.getOrNull()
    }

    private fun formatNetFlow(amountYuan: Double?, pct: Double?): String {
        val amount = amountYuan?.takeIf { it.isFinite() }
        val direction = when {
            amount == null -> "净额暂缺"
            amount > 0.0 -> "净流入 ${formatFlowAmount(amount)}"
            amount < 0.0 -> "净流出 ${formatFlowAmount(amount)}"
            else -> "净流向 0"
        }
        return "$direction（${formatPct(pct)}）"
    }

    private fun priceText(changePct: Double?): String =
        changePct?.let { "当日价格 ${formatPct(it)}" } ?: "当日价格暂缺"

    private fun formatFlowAmount(value: Double): String {
        val absolute = kotlin.math.abs(value)
        return when {
            absolute >= 100_000_000.0 -> String.format(Locale.CHINA, "%.2f亿", absolute / 100_000_000.0)
            absolute >= 10_000.0 -> String.format(Locale.CHINA, "%.0f万", absolute / 10_000.0)
            else -> String.format(Locale.CHINA, "%.0f元", absolute)
        }
    }

    private fun formatPct(value: Double?): String =
        value?.takeIf { it.isFinite() }?.let { String.format(Locale.CHINA, "%+.2f%%", it) } ?: "暂缺"

    private fun formatAmount(value: Double): String =
        if (value >= 100_000_000.0) String.format(Locale.CHINA, "%.0f亿元", value / 100_000_000.0)
        else String.format(Locale.CHINA, "%.0f万元", value / 10_000.0)
}
