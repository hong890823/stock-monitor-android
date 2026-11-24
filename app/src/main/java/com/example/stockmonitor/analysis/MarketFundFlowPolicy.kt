package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.SectorFlow
import java.util.Locale
import kotlin.math.abs

/**
 * 盘中「资金环境」v1：只读提示，不改写任何买卖信号、不做 T 份额。
 *
 * 大盘口径暂时用**行业板块主力净流入合计**（东方财富 `m:90+t:2` 的 f62/f6）近似全市场：
 * 行业板块覆盖大部分 A 股，但仍是估算值，不是精确的全市场净流。个股口径用当日主力净占比。
 *
 * 方向判定用「净流入 / 板块成交额」的加权占比，而不是绝对金额——绝对金额会随盘中时点和
 * 市场总成交额漂移，早盘 50 亿和尾盘 50 亿不是同一件事。
 */
enum class MarketFundFlowTone(val label: String) {
    INFLOW("净流入"),
    OUTFLOW("净流出"),
    NEUTRAL("中性"),
    UNAVAILABLE("数据不足"),
}

data class MarketFundFlowSnapshot(
    val tone: MarketFundFlowTone,
    val netInflowYuan: Double?,
    val totalAmountYuan: Double?,
    val netRatioPct: Double?,
    val boardCount: Int,
    val inflowBoardCount: Int,
    val asOfMs: Long,
) {
    val inflowBoardRatio: Double?
        get() = if (boardCount > 0) inflowBoardCount.toDouble() / boardCount else null
}

/** 已确认的资金环境状态；[changed] 只在确认档位发生跃迁时为 true，用于低频通知。 */
data class MarketFundFlowState(
    val tone: MarketFundFlowTone,
    val snapshot: MarketFundFlowSnapshot,
    val previousTone: MarketFundFlowTone,
    val changed: Boolean,
    /** 本轮快照缺数据，沿用上一次确认档位。 */
    val stale: Boolean = false,
)

enum class HoldingFundFlowSignal {
    ALIGNED_INFLOW,
    ALIGNED_OUTFLOW,
    MARKET_OUT_STOCK_IN,
    MARKET_IN_STOCK_OUT,
    MARKET_ONLY,
    UNAVAILABLE,
}

data class HoldingFundFlowAdvice(
    val signal: HoldingFundFlowSignal,
    val text: String,
)

object MarketFundFlowPolicy {
    /** 有效板块太少时，合计没有代表性，直接判「数据不足」。 */
    const val MIN_BOARDS = 10

    /** 早盘板块合计成交额过低时，占比会被单笔大单放大，不做方向判断。 */
    const val MIN_TOTAL_AMOUNT_YUAN = 20_000_000_000.0

    /** 进入「流入 / 流出」档所需的加权净占比（成交额口径）。 */
    const val ENTER_NET_RATIO_PCT = 2.0

    /** 板块扩散确认：流入档要求多数板块流入，流出档要求多数板块流出。 */
    const val INFLOW_BOARD_RATIO = 0.55
    const val OUTFLOW_BOARD_RATIO = 0.45

    /** 个股资金方向沿用现有主力净占比门槛，避免两套口径。 */
    const val STOCK_MAIN_NET_PCT = FundFlowPolicy.MIN_MAIN_NET_PCT

    fun assess(
        flows: Collection<SectorFlow>,
        asOfMs: Long = System.currentTimeMillis(),
    ): MarketFundFlowSnapshot {
        val valid = flows.filter { flow ->
            flow.mainNetInflow?.let { it.isFinite() } == true &&
                flow.amountYuan?.let { it.isFinite() && it > 0.0 } == true
        }
        val netInflow = valid.mapNotNull { it.mainNetInflow }.sum()
        val totalAmount = valid.mapNotNull { it.amountYuan }.sum()
        val inflowBoards = valid.count { (it.mainNetInflow ?: 0.0) > 0.0 }
        val ratio = if (totalAmount >= MIN_TOTAL_AMOUNT_YUAN) {
            netInflow / totalAmount * 100.0
        } else {
            null
        }
        val boardRatio = if (valid.size >= MIN_BOARDS) {
            inflowBoards.toDouble() / valid.size
        } else {
            null
        }
        val tone = when {
            ratio == null || boardRatio == null -> MarketFundFlowTone.UNAVAILABLE
            ratio >= ENTER_NET_RATIO_PCT && boardRatio >= INFLOW_BOARD_RATIO ->
                MarketFundFlowTone.INFLOW
            ratio <= -ENTER_NET_RATIO_PCT && boardRatio <= OUTFLOW_BOARD_RATIO ->
                MarketFundFlowTone.OUTFLOW
            else -> MarketFundFlowTone.NEUTRAL
        }
        return MarketFundFlowSnapshot(
            tone = tone,
            netInflowYuan = netInflow.takeIf { valid.isNotEmpty() },
            totalAmountYuan = totalAmount.takeIf { it > 0.0 },
            netRatioPct = ratio,
            boardCount = valid.size,
            inflowBoardCount = inflowBoards,
            asOfMs = asOfMs,
        )
    }

    /**
     * 单项组合：只负责「怎么说」，不产出买卖指令。
     * 个股资金缺失时只陈述大盘方向，不硬凑组合。
     */
    fun holdingAdvice(
        state: MarketFundFlowState?,
        stockMainNetPct: Double?,
    ): HoldingFundFlowAdvice? {
        val marketTone = state?.tone ?: MarketFundFlowTone.UNAVAILABLE
        if (marketTone == MarketFundFlowTone.UNAVAILABLE) return null
        val stockPct = stockMainNetPct?.takeIf { it.isFinite() }
        val stockTone = when {
            stockPct == null -> MarketFundFlowTone.UNAVAILABLE
            stockPct >= STOCK_MAIN_NET_PCT -> MarketFundFlowTone.INFLOW
            stockPct <= -STOCK_MAIN_NET_PCT -> MarketFundFlowTone.OUTFLOW
            else -> MarketFundFlowTone.NEUTRAL
        }
        val marketText = "大盘资金${marketTone.label}" +
            if (state?.stale == true) "（沿用上一轮）" else ""
        val stockPctText = stockPct?.let { pctAbs(abs(it)) }
        val stockText = when (stockTone) {
            MarketFundFlowTone.INFLOW ->
                "个股主力净流入 ${stockPctText.orEmpty()}"
            MarketFundFlowTone.OUTFLOW ->
                "个股主力净流出 ${stockPctText.orEmpty()}"
            MarketFundFlowTone.NEUTRAL ->
                "个股资金基本持平${stockPctText?.let { "（$it）" }.orEmpty()}"
            MarketFundFlowTone.UNAVAILABLE -> "个股资金暂缺"
        }
        val signal = when {
            marketTone == MarketFundFlowTone.INFLOW && stockTone == MarketFundFlowTone.INFLOW ->
                HoldingFundFlowSignal.ALIGNED_INFLOW
            marketTone == MarketFundFlowTone.OUTFLOW && stockTone == MarketFundFlowTone.OUTFLOW ->
                HoldingFundFlowSignal.ALIGNED_OUTFLOW
            marketTone == MarketFundFlowTone.OUTFLOW && stockTone == MarketFundFlowTone.INFLOW ->
                HoldingFundFlowSignal.MARKET_OUT_STOCK_IN
            marketTone == MarketFundFlowTone.INFLOW && stockTone == MarketFundFlowTone.OUTFLOW ->
                HoldingFundFlowSignal.MARKET_IN_STOCK_OUT
            else -> HoldingFundFlowSignal.MARKET_ONLY
        }
        val action = when (signal) {
            HoldingFundFlowSignal.ALIGNED_INFLOW ->
                "同向流入，可正常持有、回踩再接；仍需价格与技术面确认，不据此追高。"
            HoldingFundFlowSignal.ALIGNED_OUTFLOW ->
                "同向流出，反弹先当减仓波对待；不追、不补，等资金回流或价格重新站稳。"
            HoldingFundFlowSignal.MARKET_OUT_STOCK_IN ->
                "个股逆势承接，但大盘未稳，只观察不追；等大盘资金转向再确认。"
            HoldingFundFlowSignal.MARKET_IN_STOCK_OUT ->
                "个股跑输大盘资金，反弹先减、不做逆势补仓；做 T 份额也不放大。"
            HoldingFundFlowSignal.MARKET_ONLY ->
                "方向组合不完整，只作辅助，不单独改变操作。"
            HoldingFundFlowSignal.UNAVAILABLE -> ""
        }
        return HoldingFundFlowAdvice(signal, "$marketText；$stockText。$action")
    }

    fun marketSummary(snapshot: MarketFundFlowSnapshot): String {
        if (snapshot.tone == MarketFundFlowTone.UNAVAILABLE) {
            return "行业板块资金数据不足（有效板块 ${snapshot.boardCount} 个），本轮不判断大盘资金方向"
        }
        val net = snapshot.netInflowYuan ?: 0.0
        val direction = if (net >= 0.0) "净流入" else "净流出"
        val ratio = snapshot.netRatioPct?.let { "，占板块成交额 ${pctAbs(abs(it))}" }.orEmpty()
        val boards = "，${snapshot.inflowBoardCount}/${snapshot.boardCount} 个板块流入"
        return "行业板块合计主力$direction ${amountYi(abs(net))}$ratio$boards"
    }

    fun notificationTitle(state: MarketFundFlowState): String = when (state.tone) {
        MarketFundFlowTone.INFLOW ->
            if (state.previousTone == MarketFundFlowTone.OUTFLOW) "💧 大盘资金转为净流入" else "💧 大盘资金转入流入"
        MarketFundFlowTone.OUTFLOW ->
            if (state.previousTone == MarketFundFlowTone.INFLOW) "💧 大盘资金转为净流出" else "💧 大盘资金转入流出"
        MarketFundFlowTone.NEUTRAL -> "💧 大盘资金转为中性"
        MarketFundFlowTone.UNAVAILABLE -> "💧 大盘资金数据不足"
    }

    private fun pct(value: Double): String = String.format(Locale.CHINA, "%+.2f%%", value)

    private fun pctAbs(value: Double): String = String.format(Locale.CHINA, "%.2f%%", value)

    private fun amountYi(value: Double): String =
        String.format(Locale.CHINA, "%.2f亿元", value / 100_000_000.0)
}

/**
 * 资金环境确认器：方向档位需要连续 [confirmations] 次同向快照才切换，避免盘中单笔大单来回翻转。
 * 数据缺失时保留上一次确认档位并标记 stale，不把「接口失败」误报成「资金转中性」。
 */
class MarketFundFlowTracker(
    private val confirmations: Int = 2,
) {
    private var tradeDay: String? = null
    private var confirmedTone: MarketFundFlowTone? = null
    private var pendingTone: MarketFundFlowTone? = null
    private var pendingCount = 0

    fun update(day: String, snapshot: MarketFundFlowSnapshot): MarketFundFlowState {
        if (tradeDay != day) {
            tradeDay = day
            confirmedTone = null
            pendingTone = null
            pendingCount = 0
        }
        val raw = snapshot.tone
        val current = confirmedTone
        if (raw == MarketFundFlowTone.UNAVAILABLE) {
            return MarketFundFlowState(
                tone = current ?: MarketFundFlowTone.UNAVAILABLE,
                snapshot = snapshot,
                previousTone = current ?: MarketFundFlowTone.UNAVAILABLE,
                changed = false,
                stale = current != null,
            )
        }
        if (current == null) {
            confirmedTone = raw
            pendingTone = null
            pendingCount = 0
            return MarketFundFlowState(
                tone = raw,
                snapshot = snapshot,
                previousTone = MarketFundFlowTone.UNAVAILABLE,
                changed = false,
            )
        }
        if (raw == current) {
            pendingTone = null
            pendingCount = 0
            return MarketFundFlowState(
                tone = current,
                snapshot = snapshot,
                previousTone = current,
                changed = false,
            )
        }
        if (raw == pendingTone) {
            pendingCount += 1
        } else {
            pendingTone = raw
            pendingCount = 1
        }
        if (pendingCount >= confirmations) {
            confirmedTone = raw
            pendingTone = null
            pendingCount = 0
            return MarketFundFlowState(
                tone = raw,
                snapshot = snapshot,
                previousTone = current,
                changed = true,
            )
        }
        return MarketFundFlowState(
            tone = current,
            snapshot = snapshot,
            previousTone = current,
            changed = false,
        )
    }
}