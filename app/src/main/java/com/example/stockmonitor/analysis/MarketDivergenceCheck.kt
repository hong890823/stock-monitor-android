package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar
import java.util.Locale

/**
 * 大盘分歧日体检与次日证伪状态机。
 *
 * 来源：docs/2026-11-24-divergence-execution-strategy.md 模块 A/B——
 * 「利好兑现的是筹码，不是行情」：高开冲高回落的分歧日不读作反弹终结，
 * 先体检（资金腿承接力），再用次日一根 K 线证伪（中阳=洗盘，否则降级）。
 *
 * 边界（硬约束）：恐慌坑（[DipReboundEngine] / [ReboundTracker] 深跌回升口径）不动，
 * 分歧日是高开回落，两个状态机不串口。政策腿不可直接观测，恒置未知不猜。
 */
enum class DivergencePhase(val label: String) {
    /** 近 5 日无进行中的分歧周期：常规规则，不出分歧提示。 */
    NONE("常规"),

    /** 分歧日刚过，今天是证伪观测日：盯一根 K 线定方向。 */
    WATCH("分歧观察"),

    /** 观测窗口内出了中阳：分歧定性为洗盘，反弹延续，常规加仓/持有规则恢复。 */
    REBOUND_HOLD("洗盘确认"),

    /** 窗口内无中阳（或次日明确弱势）：降低仓位预期，冻结新增，只提示不命令。 */
    DOWNGRADE("证伪降级"),
}

/** 分歧日的资金腿（承接力）分档；政策腿恒为不可观测。 */
enum class FundLeg(val label: String) {
    ABSORB_STRONG("承接强：放量平稳消化抛压"),
    ABSORB_NORMAL("承接一般：兑现盘主导"),
    WEAK("承接弱：抛压未被消化"),
}

/**
 * 证伪中阳口径，两候选做噪声天数对比后二选一（[MidCandleRule.ALPHA] 暂为默认）：
 * α（宽松）：收盘涨幅 ≥ +0.5% 且收盘价 > 分歧日实体中点；
 * β（严格）：收盘涨幅 ≥ +1% 且收复分歧日开盘价。
 */
enum class MidCandleRule { ALPHA, BETA }

/** 单日指数体检输入。[turnoverRatioPct] 优先成交额环比；无成交额时调用方传成交量环比近似。 */
data class IndexDayStats(
    val date: String,
    val openPct: Double,
    val highPct: Double,
    val closePct: Double,
    val turnoverRatioPct: Double,
    val open: Double,
    val close: Double,
    /** 前 5 个已完成日收盘均值，用于「结构未破坏」检查。 */
    val prev5CloseAvg: Double,
)

data class DivergenceDayReport(
    val date: String,
    val isDivergenceDay: Boolean,
    /** 资金腿；非分歧日为 null。 */
    val fundLeg: FundLeg? = null,
    /** 政策腿恒为不可观测，只留位不赋值。 */
    val policyLegUnknown: Boolean = true,
    val evidence: List<String> = emptyList(),
)

/** 当前分歧周期状态（供矩阵文案与报告引用）。 */
data class DivergenceCycleState(
    val phase: DivergencePhase,
    /** 触发本轮的分歧日；[DivergencePhase.NONE] 时为 null。 */
    val divergenceDate: String? = null,
    val detail: String = "",
)

object MarketDivergenceCheck {
    /** 高开：开盘涨幅 ≥ +0.4%。 */
    const val GAP_UP_OPEN_PCT = 0.4

    /** 冲高回落：最高涨幅 ≥ +1.5%。 */
    const val FAKEOUT_HIGH_PCT = 1.5

    /** 冲高回落：收盘涨幅 ≤ 高开幅度的 60%。 */
    const val FAKEOUT_CLOSE_RATIO = 0.6

    /** 放量换手：成交额（量）环比 ≥ +5%。 */
    const val TURNOVER_EXPAND_PCT = 5.0

    /** 承接强的收盘涨幅下限；之下到 [ABSORB_NORMAL_FLOOR_PCT] 为承接一般。 */
    const val ABSORB_STRONG_FLOOR_PCT = -0.2

    /** 承接一般的收盘涨幅下限；更低判承接弱。 */
    const val ABSORB_NORMAL_FLOOR_PCT = -1.2

    /** 明确弱势日（阴线/中阴）：收盘涨幅 ≤ -0.5%，D+1 出现即提前降级。 */
    const val WEAK_DAY_CLOSE_PCT = -0.5

    /** 中阳口径 α：收盘涨幅 ≥ +0.5%。 */
    const val ALPHA_CLOSE_PCT = 0.5

    /** 中阳口径 β：收盘涨幅 ≥ +1%。 */
    const val BETA_CLOSE_PCT = 1.0

    /**
     * 分歧周期回看窗口（已完成日）：分歧日距今超过 4 个交易日即过期回常规。
     * 证伪窗口是 D+1、D+2 两天，解析结果（洗盘/降级）持续到 D+5。
     */
    const val MAX_LOOKBACK_DAYS = 5

    /** 分歧日判定：高开 + 冲高回落 + 放量 + 结构未破坏，四条全满足。 */
    fun detect(day: IndexDayStats): DivergenceDayReport {
        val gapUp = day.openPct.isFinite() && day.openPct >= GAP_UP_OPEN_PCT
        val fakeout = day.highPct.isFinite() && day.highPct >= FAKEOUT_HIGH_PCT &&
            day.closePct.isFinite() && day.closePct <= day.openPct * FAKEOUT_CLOSE_RATIO
        val expanded = day.turnoverRatioPct.isFinite() && day.turnoverRatioPct >= TURNOVER_EXPAND_PCT
        val structureHolds = day.close.isFinite() && day.prev5CloseAvg.isFinite() &&
            day.prev5CloseAvg > 0 && day.close > day.prev5CloseAvg
        val isDivergenceDay = gapUp && fakeout && expanded && structureHolds
        if (!isDivergenceDay) return DivergenceDayReport(day.date, isDivergenceDay = false)
        val fundLeg = when {
            !day.closePct.isFinite() -> FundLeg.ABSORB_NORMAL
            day.closePct >= ABSORB_STRONG_FLOOR_PCT -> FundLeg.ABSORB_STRONG
            day.closePct >= ABSORB_NORMAL_FLOOR_PCT -> FundLeg.ABSORB_NORMAL
            else -> FundLeg.WEAK
        }
        val evidence = listOf(
            "高开 ${pct(day.openPct)} 最高 ${pct(day.highPct)} 收 ${pct(day.closePct)}（高开兑现回落）",
            "量能环比 ${pct(day.turnoverRatioPct)}，${fundLeg.label}",
            "收盘仍在前5日均线上方，结构未破坏",
        )
        return DivergenceDayReport(day.date, isDivergenceDay = true, fundLeg = fundLeg, evidence = evidence)
    }

    /** 次日中阳判定：两口径见 [MidCandleRule]。[divergence] 是分歧日当天。 */
    fun isMidCandle(day: IndexDayStats, divergence: IndexDayStats, rule: MidCandleRule): Boolean {
        if (!day.closePct.isFinite()) return false
        return when (rule) {
            MidCandleRule.ALPHA ->
                day.closePct >= ALPHA_CLOSE_PCT &&
                    day.close.isFinite() && divergence.close.isFinite() && divergence.open.isFinite() &&
                    day.close > (divergence.open + divergence.close) / 2
            MidCandleRule.BETA ->
                day.closePct >= BETA_CLOSE_PCT &&
                    day.close.isFinite() && divergence.open.isFinite() &&
                    day.close >= divergence.open
        }
    }

    /** 明确弱势日（阴线/中阴）：收盘涨幅 ≤ [WEAK_DAY_CLOSE_PCT]。 */
    fun isWeakDay(day: IndexDayStats): Boolean = day.closePct.isFinite() && day.closePct <= WEAK_DAY_CLOSE_PCT

    /**
     * 分歧周期状态机，输入为**已完成**日线（旧→新，建议含最近 [MAX_LOOKBACK_DAYS] 个交易日）。
     *
     * - 无分歧日 → 常规；
     * - 分歧日 D 是最后一个已完成日（今天 = D+1）→ 观测；
     * - 今天 = D+2：D+1 中阳 → 洗盘确认；D+1 明确弱势 → 降级；中性 → 继续观测（最后一天）；
     * - 今天 ≥ D+3：窗口内任一中阳 → 洗盘确认，否则降级（宁降勿拖）；
     * - 解析结果持续到 D+5，之后回常规；期间新的分歧日重开周期。
     */
    fun phaseOf(completed: List<IndexDayStats>, rule: MidCandleRule = MidCandleRule.ALPHA): DivergenceCycleState {
        val valid = completed.filter { it.date.isNotBlank() }
        if (valid.isEmpty()) return DivergenceCycleState(DivergencePhase.NONE)
        val n = valid.lastIndex
        val divergenceIndex = (n downTo maxOf(0, n - MAX_LOOKBACK_DAYS + 1))
            .firstOrNull { detect(valid[it]).isDivergenceDay }
            ?: return DivergenceCycleState(DivergencePhase.NONE)
        val divergence = valid[divergenceIndex]
        val daysAfter = n - divergenceIndex
        // 中阳只认 D+1、D+2 证伪窗口内；窗口外的反弹不追溯改写已解析的周期。
        val midAfter = (divergenceIndex + 1..minOf(n, divergenceIndex + 2))
            .filter { isMidCandle(valid[it], divergence, rule) }
        val state = { phase: DivergencePhase, detail: String ->
            DivergenceCycleState(phase, divergence.date, detail)
        }
        return when {
            // 解析结果持续到 D+5（今天 ≤ D+5 ⇔ 最后已完成日 ≤ D+4）；超出回常规。
            daysAfter >= MAX_LOOKBACK_DAYS -> DivergenceCycleState(DivergencePhase.NONE)
            midAfter.isNotEmpty() ->
                state(DivergencePhase.REBOUND_HOLD, "观测窗口内 ${valid[midAfter.first()].date} 收中阳，分歧定性为洗盘")
            daysAfter == 0 ->
                state(DivergencePhase.WATCH, "${divergence.date} 分歧体检通过，今天盯一根 K 线定方向")
            daysAfter == 1 && isWeakDay(valid[n]) ->
                state(DivergencePhase.DOWNGRADE, "次日 ${valid[n].date} 收盘 ${pct(valid[n].closePct)} 明确弱势，证伪降级")
            daysAfter == 1 ->
                state(DivergencePhase.WATCH, "次日未出中阳也未明确弱势，今天为最后观测日")
            else ->
                state(DivergencePhase.DOWNGRADE, "观测窗口内未收中阳，按宁降勿拖口径降级")
        }
    }

    /**
     * 从指数日线构造体检输入。涨跌幅、环比均按相邻收盘/成交量推算，
     * 所以 [IndexDayStats.turnoverRatioPct] 这里填的是**成交量环比**近似——
     * 成交额数据接入后由调用方传成交额环比（见字段 KDoc），不改判定阈值。
     */
    fun fromBars(bars: List<DayBar>): List<IndexDayStats> {
        if (bars.size < 2) return emptyList()
        return bars.mapIndexedNotNull { index, bar ->
            val prev = bars.getOrNull(index - 1) ?: return@mapIndexedNotNull null
            val closes = bars.subList(maxOf(0, index - 5), index).map { it.close }
            if (closes.size < 5 || prev.close <= 0 || prev.volume <= 0 ||
                listOf(bar.open, bar.high, bar.close, bar.volume).any { !it.isFinite() || it < 0 }
            ) {
                return@mapIndexedNotNull null
            }
            IndexDayStats(
                date = bar.date,
                openPct = (bar.open / prev.close - 1) * 100,
                highPct = (bar.high / prev.close - 1) * 100,
                closePct = (bar.close / prev.close - 1) * 100,
                turnoverRatioPct = (bar.volume / prev.volume - 1) * 100,
                open = bar.open,
                close = bar.close,
                prev5CloseAvg = closes.average(),
            )
        }
    }

    private fun pct(value: Double): String = String.format(Locale.CHINA, "%+.1f%%", value)
}
