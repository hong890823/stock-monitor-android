package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

// ── 参数（可调） ─────────────────────────────────────────────────────────────

/** 分型判定：±2 根 K 线内最高/最低才算阶段高/低点。取 3 会在锯齿行情里漏掉末端高点。 */
const val SWING_WINDOW = 2

/** 两个摆动点最小间隔，滤掉毛刺。 */
const val MIN_PEAK_GAP = 3

/** 窗口极值出现在最近 3 根内 → 视为进行中高/低点（确认窗未走完）。 */
const val FORMING_BARS = 3

/** 搜寻前后高/低点的窗口（日线通常取 240 根，窗口 120 覆盖「本轮」行情）。 */
const val LOOKBACK = 120

/** 有效日线不足此数 → 数据不足，不硬判。 */
const val MIN_BARS = 30

/**
 * OBV 量价背离判定：股价创阶段新高/新低时，OBV 有没有同步确认。
 *
 * 经典定义（两种背离）：
 * 1. 顶背离：股价创阶段新高，但本轮 OBV 峰值低于前期 OBV 峰值 —— 价升量能没跟上，
 *    上涨靠存量博弈，后劲偏弱，是风险预警；
 * 2. 底背离：股价创阶段新低，但本轮 OBV 谷值未再创新低 —— 下跌中资金不再持续出逃，
 *    有企稳可能。
 *
 * 判定比的是 **OBV 自己的峰（谷）值**，不是价格高点当日的 OBV 读数：真实数据上两种口径
 * 会给出相反结论，「OBV 高点低于前期高点」的原意是比 OBV 曲线的两个高点。
 *
 * 「OBV 近 20 日净变化为正」只说明区间资金总体流向，**不等于** OBV 跟上了最新股价高点，
 * 所以对照点会把两段行情的 OBV 峰（谷）值原样给出，用户可在行情软件里逐点核对。
 *
 * 口径与 macOS 版 `obv_divergence.py` 逐字一致（文案、参数、判定顺序），两端结论可对照。
 */
enum class DivergenceState(val label: String) {
    TOP("顶背离"),
    BOTTOM("底背离"),
    SYNC("量价同步"),
    NEUTRAL("暂无背离"),
    CONFLICT("信号打架"),
    INSUFFICIENT("数据不足"),
}

/**
 * 阶段高/低点对照点：让用户能在行情软件里逐点核对。
 *
 * [price]/[date] 是价格高（低）点本身；[obv]/[obvDate] 是该段行情的 OBV 峰（谷）值
 * ——判定比的就是这组数。
 */
data class DivergencePoint(
    val index: Int,
    val date: String,
    val price: Double,
    val obv: Double,
    val obvDate: String,
)

data class DivergenceResult(
    val state: DivergenceState,
    /** 极值是否进行中（确认窗未走完），此时判定行会追加「（高点进行中…）」。 */
    val forming: Boolean,
    val scoreDelta: Int,
    val prior: DivergencePoint?,
    val current: DivergencePoint?,
    /** 核对条件2：本轮 OBV 峰值是否 ≥ 前期 OBV 峰值。 */
    val obvConfirmedNewHigh: Boolean?,
    /** 核对条件2（低点侧）：本轮 OBV 谷值是否 ≤ 前期 OBV 谷值。 */
    val obvConfirmedNewLow: Boolean?,
    val reason: String,
    val meaning: String,
    /** 一行式结论，进 signals / 技术摘要。 */
    val short: String,
    val method: String,
)

object ObvDivergence {

    /** 经典累计 OBV：涨加量、跌减量、平盘不变。 */
    fun computeObv(closes: List<Double>, volumes: List<Double>): List<Double> {
        val obv = ArrayList<Double>(closes.size)
        var running = 0.0
        for (i in closes.indices) {
            if (i > 0) {
                when {
                    closes[i] > closes[i - 1] -> running += volumes[i]
                    closes[i] < closes[i - 1] -> running -= volumes[i]
                }
            }
            obv.add(running)
        }
        return obv
    }

    /**
     * 判定量价背离。
     *
     * @param currentPrice 实时价（可选）。仅参与「是否创新高/新低」判断，OBV 对照仍取日线，
     *   避免实时价改动历史摆动结构。
     */
    fun evaluate(bars: List<DayBar>, currentPrice: Double? = null): DivergenceResult {
        val n = bars.size
        val method = methodText()
        if (n < MIN_BARS) {
            return DivergenceResult(
                state = DivergenceState.INSUFFICIENT,
                forming = false,
                scoreDelta = 0,
                prior = null,
                current = null,
                obvConfirmedNewHigh = null,
                obvConfirmedNewLow = null,
                reason = "数据不足 —— 有效日线不足，无法判定背离",
                meaning = "",
                short = "量价背离：${DivergenceState.INSUFFICIENT.label}",
                method = method,
            )
        }

        val closes = bars.map { it.close }
        val highs = bars.map { it.high }
        val lows = bars.map { it.low }
        val dates = bars.map { it.date }
        val obv = computeObv(closes, bars.map { it.volume })
        val start = max(0, n - LOOKBACK)

        val nowHigh = if (currentPrice != null) max(highs[n - 1], currentPrice) else highs[n - 1]
        val nowLow = if (currentPrice != null) min(lows[n - 1], currentPrice) else lows[n - 1]

        val (p1h, p2h, formingH) = pickPair(highs, true, start, n, nowHigh)
        val (p1l, p2l, formingL) = pickPair(lows, false, start, n, nowLow)

        fun priceAt(p: Int, isHighSide: Boolean): Double = when {
            p == n - 1 && currentPrice != null && isHighSide -> nowHigh
            p == n - 1 && currentPrice != null && !isHighSide -> nowLow
            isHighSide -> highs[p]
            else -> lows[p]
        }

        // ── 顶背离：股价创新高，但本轮 OBV 峰值低于前期 OBV 峰值 ──
        val swingsLow = swingPoints(lows, false, start, n)
        val swingsHigh = swingPoints(highs, true, start, n)
        var topDiv = false
        var topSync = false
        var topNew = false
        var topReason = ""
        var topV1 = 0.0
        var topI1 = -1
        var topV2 = 0.0
        var topI2 = -1
        if (p1h != null) {
            topNew = priceAt(p2h, true) > priceAt(p1h, true)
            if (topNew) {
                val ext = legObvExtreme(obv, lows, highs, p1h, p2h, start, true, swingsLow)
                if (ext != null) {
                    topV1 = ext.v1; topI1 = ext.i1; topV2 = ext.v2; topI2 = ext.i2
                    if (topV2 > topV1) {
                        topSync = true
                        topReason = "量价同步 —— 股价创新高的同时 OBV 峰值同步创新高"
                    } else {
                        topDiv = true
                        topReason = "顶背离 —— 股价创阶段新高，但本轮 OBV 峰值低于前期 OBV 峰值"
                    }
                }
            }
        }

        // ── 底背离：股价创新低，但本轮 OBV 谷值未再创新低 ──
        var botDiv = false
        var botSync = false
        var botNew = false
        var botReason = ""
        var botV1 = 0.0
        var botI1 = -1
        var botV2 = 0.0
        var botI2 = -1
        if (p1l != null) {
            botNew = priceAt(p2l, false) < priceAt(p1l, false)
            if (botNew) {
                val ext = legObvExtreme(obv, lows, highs, p1l, p2l, start, false, swingsHigh)
                if (ext != null) {
                    botV1 = ext.v1; botI1 = ext.i1; botV2 = ext.v2; botI2 = ext.i2
                    if (botV2 < botV1) {
                        botSync = true
                        botReason = "量价同步 —— 股价创新低的同时 OBV 谷值同步创新低"
                    } else {
                        botDiv = true
                        botReason = "底背离 —— 股价创阶段新低，但本轮 OBV 谷值未再创新低"
                    }
                }
            }
        }

        // ── 核对条件2：OBV 有没有同步创出本轮新高/新低 ──
        val obvNewHigh: Boolean? = if (topI2 < 0) null else topV2 > topV1
        val obvNewLow: Boolean? = if (botI2 < 0) null else botV2 < botV1

        // ── 组合判定 ──
        val forming = formingH || formingL
        var priorPt: Int? = null
        var currentPt: Int? = null
        var sideHigh = true
        var v1 = 0.0
        var i1 = -1
        var v2 = 0.0
        var i2 = -1

        val state: DivergenceState
        val reason: String
        val meaning: String
        val short: String
        val scoreDelta: Int

        when {
            topDiv && botDiv -> {
                state = DivergenceState.CONFLICT
                reason = "信号打架 —— 顶背离与底背离条件同时出现"
                meaning = "多空量价信号矛盾（高点侧 OBV 峰值 ${obvNum(topV2)} 未跟上前期 ${obvNum(topV1)}，" +
                    "低点侧谷值 ${obvNum(botV2)} 也未再创新低），暂不作方向判断"
                short = "量价背离：信号打架（顶背离与底背离同时出现）"
                scoreDelta = 0
            }
            topDiv -> {
                state = DivergenceState.TOP
                reason = topReason
                // 只写「价升量能未跟上」是定义，不是分析；把背离幅度写出来，读者才核对得了。
                meaning = "价升量能未跟上：本轮 OBV 峰值 ${obvNum(topV2)} 比前期 ${obvNum(topV1)} 低 " +
                    "${obvGapPct(topV1, topV2)}，上涨主要靠存量博弈，后劲偏弱，属风险预警（不等于立刻见顶）"
                short = "量价背离：顶背离（股价创新高 OBV 未同步）"
                scoreDelta = -1
                priorPt = p1h; currentPt = p2h; sideHigh = true
                v1 = topV1; i1 = topI1; v2 = topV2; i2 = topI2
            }
            botDiv -> {
                state = DivergenceState.BOTTOM
                reason = botReason
                meaning = "下跌中资金不再持续出逃：本轮 OBV 谷值 ${obvNum(botV2)} 比前期 ${obvNum(botV1)} 高 " +
                    "${obvGapPct(botV1, botV2)}，有企稳可能（不等于反转）"
                short = "量价背离：底背离（股价创新低 OBV 未再创新低）"
                scoreDelta = 1
                priorPt = p1l; currentPt = p2l; sideHigh = false
                v1 = botV1; i1 = botI1; v2 = botV2; i2 = botI2
            }
            topSync -> {
                state = DivergenceState.SYNC
                reason = topReason
                meaning = "量能配合上涨，暂无顶背离迹象"
                short = "量价背离：量价同步（价涨量能跟上）"
                scoreDelta = 0
                priorPt = p1h; currentPt = p2h; sideHigh = true
                v1 = topV1; i1 = topI1; v2 = topV2; i2 = topI2
            }
            botSync -> {
                state = DivergenceState.SYNC
                reason = botReason
                meaning = "量价一致走低，暂无底背离迹象"
                short = "量价背离：量价同步（价跌量能跟随）"
                scoreDelta = 0
                priorPt = p1l; currentPt = p2l; sideHigh = false
                v1 = botV1; i1 = botI1; v2 = botV2; i2 = botI2
            }
            else -> {
                state = DivergenceState.NEUTRAL
                scoreDelta = 0
                val (r, m) = when {
                    p1h == null && p1l == null ->
                        "暂无背离 —— 缺少可比的前期高/低点，无法判定背离" to
                            "背离需要两个可比的阶段高/低点；当前不构成判定前提"
                    !topNew && !botNew ->
                        "暂无背离 —— 股价未创阶段新高/新低" to
                            "背离只在创新高/新低时才谈；当前不构成判定前提"
                    else ->
                        "暂无背离 —— 量价未出现背离组合" to
                            "价与 OBV 同向，暂无背离信号"
                }
                reason = r
                meaning = m
                short = "量价背离：暂无背离"
            }
        }

        val reasonText = if (forming && state != DivergenceState.INSUFFICIENT && state != DivergenceState.NEUTRAL) {
            reason + "（高点进行中，尚未走完确认窗）"
        } else {
            reason
        }

        var prior: DivergencePoint? = null
        var current: DivergencePoint? = null
        if (priorPt != null && currentPt != null) {
            prior = point(priorPt, priceAt(priorPt, sideHigh), if (i1 >= 0) v1 else obv[priorPt], if (i1 >= 0) i1 else priorPt, dates, n)
            current = point(currentPt, priceAt(currentPt, sideHigh), if (i2 >= 0) v2 else obv[currentPt], if (i2 >= 0) i2 else currentPt, dates, n)
        }

        return DivergenceResult(
            state = state,
            forming = forming,
            scoreDelta = scoreDelta,
            prior = prior,
            current = current,
            obvConfirmedNewHigh = obvNewHigh,
            obvConfirmedNewLow = obvNewLow,
            reason = reasonText,
            meaning = meaning,
            short = short,
            method = method,
        )
    }

    private fun methodText(): String =
        "±${SWING_WINDOW}根K线分型找阶段高低点、${LOOKBACK}日窗口、" +
            "对照前后两段行情的OBV峰/谷值；本程序内部口径"

    private fun point(index: Int, price: Double, obvVal: Double, obvIndex: Int, dates: List<String>, n: Int): DivergencePoint =
        DivergencePoint(
            index = index,
            date = dateOf(index, dates, n),
            price = price,
            obv = obvVal,
            obvDate = dateOf(obvIndex, dates, n),
        )

    private fun dateOf(i: Int, dates: List<String>, n: Int): String {
        if (i < dates.size) return dates[i]
        val back = n - 1 - i
        return when (back) {
            0 -> "今日"
            1 -> "昨日"
            else -> "${back}个交易日前"
        }
    }

    /**
     * 分型极值点（全局下标）：ext[i] 是 [i±SWING_WINDOW] 区间最高/最低，间距 ≥ MIN_PEAK_GAP。
     * 并列时保留更极者（进不了就丢掉后来的平点）。
     */
    private fun swingPoints(ext: List<Double>, isHigh: Boolean, start: Int, end: Int): List<Int> {
        val pts = ArrayList<Int>()
        var i = max(start, SWING_WINDOW)
        while (i < end - SWING_WINDOW) {
            val seg = ext.subList(i - SWING_WINDOW, i + SWING_WINDOW + 1)
            val isExtreme = if (isHigh) ext[i] == seg.max() else ext[i] == seg.min()
            if (isExtreme) {
                if (pts.isEmpty() || i - pts.last() >= MIN_PEAK_GAP) {
                    pts.add(i)
                } else {
                    val better = if (isHigh) ext[i] > ext[pts.last()] else ext[i] < ext[pts.last()]
                    if (better) pts[pts.size - 1] = i
                }
            }
            i++
        }
        return pts
    }

    private data class Pair(val p1: Int?, val p2: Int, val forming: Boolean)

    /**
     * 返回 (p1, p2, forming)：p2 = 本轮高/低点，p1 = 其前的上一个高/低点。
     *
     * 三档出口：
     * 1. 近 FORMING_BARS 根的极值超过了最近确认摆动点 → 正在创新高/新低，
     *    p2 取该极值（forming=true，确认窗未走完），p1 = 最近确认摆动点；
     * 2. 否则 p2 = 最近确认摆动点，p1 = 上一个确认摆动点（经典双高点对照）；
     * 3. 摆动点不足（如单边行情没有回撤）→ p2 退化为窗口极值，
     *    p1 退化为 p2 之前的窗口极值；仍不足则 p1 为空。
     *
     * nowLevel 只覆盖末根价格，用于实时价参与「是否创新高」判断；
     * 确认摆动点仍按日线原始值判定，避免实时价改动历史摆动结构。
     */
    private fun pickPair(ext: List<Double>, isHigh: Boolean, start: Int, end: Int, nowLevel: Double): Pair {
        val confirmed = swingPoints(ext, isHigh, start, end)
        val levels = ext.toMutableList()
        levels[end - 1] = nowLevel

        fun extreme(lo: Int, hi: Int): Int {
            // 取 [lo, hi) 的极值下标，并列取更近的
            var best = lo
            var i = lo + 1
            while (i < hi) {
                if (isHigh) {
                    if (levels[i] >= levels[best]) best = i
                } else {
                    if (levels[i] <= levels[best]) best = i
                }
                i++
            }
            return best
        }

        val recentStart = max(start, end - FORMING_BARS)
        val rIdx = extreme(recentStart, end)
        val lastSwing = confirmed.lastOrNull()
        val beyond = lastSwing != null && if (isHigh) {
            levels[rIdx] > levels[lastSwing]
        } else {
            levels[rIdx] < levels[lastSwing]
        }

        if (beyond) {
            return Pair(lastSwing, rIdx, true)
        }
        if (confirmed.size >= 2) {
            return Pair(confirmed[confirmed.size - 2], confirmed[confirmed.size - 1], false)
        }

        val p2 = extreme(start, end)
        val forming = p2 >= end - FORMING_BARS
        val endPrior = p2 - MIN_PEAK_GAP + 1
        val p1 = if (endPrior > start) extreme(start, endPrior) else null
        return Pair(p1, p2, forming)
    }

    /** 两段 OBV 峰（谷）值对照：[v1]/[i1] 前期段，[v2]/[i2] 本轮段。 */
    private data class LegExtreme(val v1: Double, val i1: Int, val v2: Double, val i2: Int)

    /**
     * 两波行情各自的 OBV 峰（谷）值对照。
     *
     * 本轮段起点 = 两个价格高（低）点之间的回撤低（反弹高）点；
     * 前期段起点 = 前一个相反方向摆动点，找不到则用窗口起点。
     */
    private fun legObvExtreme(
        obv: List<Double>,
        lows: List<Double>,
        highs: List<Double>,
        p1: Int,
        p2: Int,
        start: Int,
        isHigh: Boolean,
        swingsOpposite: List<Int>,
    ): LegExtreme? {
        val leg2Start = if (isHigh) {
            (p1..p2).minBy { lows[it] }
        } else {
            (p1..p2).maxBy { highs[it] }
        }
        val prior = swingsOpposite.filter { it < p1 }
        val leg1Start = prior.lastOrNull() ?: start
        val seg1 = obv.subList(leg1Start, p1 + 1)
        val seg2 = obv.subList(leg2Start, p2 + 1)
        if (seg1.isEmpty() || seg2.isEmpty()) return null
        val i1 = leg1Start + if (isHigh) seg1.indices.maxBy { seg1[it] } else seg1.indices.minBy { seg1[it] }
        val i2 = leg2Start + if (isHigh) seg2.indices.maxBy { seg2[it] } else seg2.indices.minBy { seg2[it] }
        return LegExtreme(seg1[i1 - leg1Start], i1, seg2[i2 - leg2Start], i2)
    }

    /** OBV 是累计成交量，量级很大，按千分位取整才读得动。 */
    private fun obvNum(value: Double): String = String.format(java.util.Locale.CHINA, "%,.0f", value)

    /**
     * 背离幅度（%）。只说「没跟上」是定义，说不出**差多少**就没法判断这个背离值不值得当回事。
     */
    private fun obvGapPct(prior: Double, current: Double): String {
        if (!prior.isFinite() || !current.isFinite() || prior == 0.0) return "—"
        return String.format(
            java.util.Locale.CHINA,
            "%.1f%%",
            kotlin.math.abs(current - prior) / kotlin.math.abs(prior) * 100,
        )
    }
}
