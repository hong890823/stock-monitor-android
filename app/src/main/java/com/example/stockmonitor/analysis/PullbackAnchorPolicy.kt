package com.example.stockmonitor.analysis

import java.util.Locale
import kotlin.math.max

/**
 * 回踩锚位的强度分档：强势股给浅锚（MA5/突破位），中性维持 M20 口径，弱势不给加仓区间。
 *
 * 背景见 `docs/2026-11-24-divergence-execution-strategy.md` 模块 D：
 * 回踩区间单锚 M20 对强势股是深回踩口径，真实强势的票大概率回不到，
 * 加仓建议形同虚设。分档后强势股改锚 MA5/突破位，且所有建议价过可达性护栏。
 */
enum class StrengthTier(val label: String) {
    STRONG("强势浅回踩"),
    NEUTRAL("中性回踩"),
    WEAK("弱势不加"),
}

/** 分档后的回踩区间：锚位、上下沿和口径说明。弱势档不给区间。 */
data class PullbackAnchor(
    val tier: StrengthTier,
    val anchorPrice: Double?,
    val anchorLabel: String,
    val low: Double?,
    val high: Double?,
    /** 话术内嵌来源标签（「MA5」「突破位」「MA20」，不含内部语义缩写）；弱势口径为空。 */
    val refLabel: String = "",
) {
    fun bandText(): String =
        if (low != null && high != null) "$anchorLabel ${fmt(low)}～${fmt(high)}元" else anchorLabel

    /**
     * 操作建议「回踩」话术的参考片段（如「MA5（13.14）」「MA20（12.17）」）。
     * 标注只写来源、不写「浅锚」这类内部语义缩写（显示契约：每个标注要能被没读过
     * 设计文档的人直接读懂）。弱势档返回 null：不给回踩买点参考。20 日低点是破位口径
     * 的结构支撑，不是回踩锚——强势股真回踩到那里趋势已经结束
     * （海立 2026-11-19：现价 15.07 的「回踩支撑 11.04」案例）。
     */
    fun adviceRef(): String? {
        val p = anchorPrice?.takeIf { it.isFinite() && it > 0 } ?: return null
        if (tier == StrengthTier.WEAK || refLabel.isEmpty()) return null
        return "$refLabel（${fmt(p)}）"
    }

    /** 现价不在区间内时的等待口径：分档说明 + 深回踩可达性注记。 */
    fun offBandReason(price: Double, atrPct: Double?): String {
        val base = when (tier) {
            StrengthTier.WEAK ->
                "现价低于 M20 回踩口径（弱势档），不给加仓区间；站回 M20 并企稳前不新增资金"
            StrengthTier.STRONG ->
                "现价已离开强势浅回踩区间（${bandText()}），等浅回踩缩量企稳，不硬等 M20，不追价"
            StrengthTier.NEUTRAL ->
                "现价已离开回踩参考区间（${bandText()}），不追价"
        }
        val reach = high?.let { PullbackAnchorPolicy.unreachableNote(it, price, atrPct) }
        return if (reach != null) "$base。$reach" else base
    }

    /** 在区间内确认买点时补一句口径说明，强势档写明「等不到 M20 不硬等」。 */
    fun onBandNote(): String = when (tier) {
        StrengthTier.STRONG -> "强势浅回踩口径（$anchorLabel），等不到 M20 不硬等，按分批口径执行"
        else -> ""
    }
}

object PullbackAnchorPolicy {
    /** 强势档贴 MA5 容差：现价与近 5 日收盘跌破 MA5 超过该容差都不算「贴着 MA5 走」。 */
    const val STRONG_MA5_TOLERANCE_PCT = 2.0

    /** 中性档：现价距 M20 的窗口（窗口外若不满足强势条件，档外默认按中性保持现口径）。 */
    const val NEUTRAL_MA20_WINDOW_PCT = 3.0

    /** 强势浅锚下沿容差；上沿沿用 M20 回踩的 [TrendEntryStrategy.PULLBACK_ABOVE_MA20_PCT]。 */
    const val STRONG_ANCHOR_BREAK_TOLERANCE_PCT = 1.0

    /** 近 5 日收盘跌破 MA5（含容差）的允许天数。 */
    const val MAX_MA5_BREAK_DAYS = 1

    /** 可达性护栏：建议价距现价超过 2.5×ATR% 视为深回踩口径，大概率到不了。 */
    const val REACH_ATR_MULT = 2.5

    /**
     * 强度分档。风险优先：弱势（破 M20 / 趋势转弱）最先，再判强势，其余中性。
     * MA5/MA10 或近 5 日收盘缺失时不猜强势，落中性保持现口径。
     */
    fun tierOf(
        price: Double,
        ma5: Double?,
        ma10: Double?,
        ma20: Double?,
        recentCloses: List<Double> = emptyList(),
        trendWeak: Boolean = false,
        majorTrendDown: Boolean = false,
    ): StrengthTier {
        if (!price.isFinite() || price <= 0 || ma20 == null || !ma20.isFinite() || ma20 <= 0) {
            return StrengthTier.NEUTRAL
        }
        if (trendWeak || majorTrendDown ||
            price < ma20 * (1 - TrendEntryStrategy.MA20_BREAK_TOLERANCE_PCT / 100)
        ) {
            return StrengthTier.WEAK
        }
        val aligned = ma5 != null && ma5.isFinite() && ma5 > 0 &&
            ma10 != null && ma10.isFinite() && ma10 > 0 && ma5 > ma10 && ma10 > ma20
        val nearMa5 = aligned && price >= ma5 * (1 - STRONG_MA5_TOLERANCE_PCT / 100)
        val breakDays = ma5BreakDays(recentCloses)
        return if (nearMa5 && breakDays != null && breakDays <= MAX_MA5_BREAK_DAYS) {
            StrengthTier.STRONG
        } else {
            StrengthTier.NEUTRAL
        }
    }

    /**
     * 近 5 日收盘各自跌破滚动 MA5（含 [STRONG_MA5_TOLERANCE_PCT] 容差）的天数。
     * 每个评估日都要拿它和它前面 4 日算滚动 MA5，所以至少要 9 根收盘；不足返回 null（不猜）。
     */
    fun ma5BreakDays(recentCloses: List<Double>): Int? {
        val closes = recentCloses.filter { it.isFinite() && it > 0 }
        if (closes.size < 9 || closes.size != recentCloses.size) return null
        var breaks = 0
        for (i in closes.size - 5 until closes.size) {
            val ma5 = closes.subList(i - 4, i + 1).average()
            if (closes[i] < ma5 * (1 - STRONG_MA5_TOLERANCE_PCT / 100)) breaks++
        }
        return breaks
    }

    /**
     * 分档 + 选锚一步到位。recentCloses 缺证据（不足 9 根）时 [tierOf] 不猜强势档，
     * 落中性保持 M20 口径。操作建议「回踩」话术与补仓锚位共用这一个入口。
     */
    fun anchorFor(
        price: Double,
        ma5: Double?,
        ma10: Double?,
        ma20: Double?,
        recentCloses: List<Double> = emptyList(),
        trendWeak: Boolean = false,
        majorTrendDown: Boolean = false,
        priorHigh: Double? = null,
    ): PullbackAnchor {
        val tier = tierOf(price, ma5, ma10, ma20, recentCloses, trendWeak, majorTrendDown)
        return anchorOf(tier, price, ma5, ma20, priorHigh)
    }

    /**
     * 分档锚位（不带区间）：[bandOf] 与话术片段 [PullbackAnchor.adviceRef] 共用同一选锚逻辑。
     * 弱势档不给锚位：回踩买点和加仓区间都免谈。
     */
    fun anchorOf(
        tier: StrengthTier,
        price: Double,
        ma5: Double?,
        ma20: Double?,
        priorHigh: Double?,
    ): PullbackAnchor {
        if (tier == StrengthTier.WEAK || ma20 == null || !ma20.isFinite() || ma20 <= 0) {
            return PullbackAnchor(tier, null, "弱势口径", null, null)
        }
        val anchor: Double
        val label: String
        val refLabel: String
        if (tier == StrengthTier.STRONG && ma5 != null && ma5.isFinite() && ma5 > 0) {
            // 突破位只在现价还贴着它时才是支撑（回踩不破）；跌远了就退回 MA5。
            val candidates = listOfNotNull(ma5, priorHigh)
                .filter { it.isFinite() && it > 0 && it <= price * (1 + STRONG_MA5_TOLERANCE_PCT / 100) }
            anchor = candidates.maxOrNull() ?: ma5
            label = if (anchor == priorHigh) "强势浅回踩锚（突破位）" else "强势浅回踩锚（MA5）"
            refLabel = if (anchor == priorHigh) "突破位" else "MA5"
        } else {
            anchor = ma20
            label = "MA20回踩口径"
            refLabel = "MA20"
        }
        return PullbackAnchor(tier, anchor, label, null, null, refLabel)
    }

    /**
     * 分档回踩区间。统一叠两个既有约束：下沿不低于失效参考 +0.01（不接飞刀），
     * 上沿不超过盈亏比 2 的入手价（沿用 [PortfolioSwing.MIN_REWARD_RISK] 口径由调用方传入）。
     */
    fun bandOf(
        tier: StrengthTier,
        price: Double,
        ma5: Double?,
        ma20: Double?,
        priorHigh: Double?,
        stop: Double?,
        target: Double?,
        minRewardRisk: Double,
    ): PullbackAnchor {
        val base = anchorOf(tier, price, ma5, ma20, priorHigh)
        val anchor = base.anchorPrice ?: return base
        val breakTolerance = if (tier == StrengthTier.STRONG) {
            STRONG_ANCHOR_BREAK_TOLERANCE_PCT
        } else {
            TrendEntryStrategy.MA20_BREAK_TOLERANCE_PCT
        }
        var low = anchor * (1 - breakTolerance / 100)
        var high = anchor * (1 + TrendEntryStrategy.PULLBACK_ABOVE_MA20_PCT / 100)
        if (stop != null && stop.isFinite() && stop > 0) low = max(low, stop + 0.01)
        if (target != null && target.isFinite() && stop != null && stop.isFinite() && stop > 0 && minRewardRisk > 0) {
            high = minOf(high, (target + minRewardRisk * stop) / (minRewardRisk + 1))
        }
        return base.copy(low = low, high = high)
    }

    /**
     * 可达性护栏：建议价低于现价的幅度超过 2.5×ATR% 时，深回踩大概率到不了。
     * 没有 ATR 数据不猜（返回 null）；建议价不低于现价时可达（返回 null）。
     * 「3×近20日日均振幅」的并列腿等日均振幅字段接入后再加，当前只用 ATR。
     */
    fun unreachableNote(suggestedPrice: Double, currentPrice: Double, atrPct: Double?): String? {
        if (!suggestedPrice.isFinite() || !currentPrice.isFinite() || suggestedPrice <= 0 || currentPrice <= 0) return null
        if (suggestedPrice >= currentPrice) return null
        if (atrPct == null || !atrPct.isFinite() || atrPct <= 0) return null
        val gapPct = (currentPrice - suggestedPrice) / currentPrice * 100
        val reachPct = REACH_ATR_MULT * atrPct
        return if (gapPct > reachPct) {
            "参考价 ${fmt(suggestedPrice)} 元距现价 " +
                "${fmtPct(gapPct)}，超过 2.5×ATR 波动口径 ${fmtPct(reachPct)}，" +
                "深回踩口径大概率到不了；参考浅锚或等分歧日分批"
        } else {
            null
        }
    }
}

private fun fmt(value: Double): String = String.format(Locale.CHINA, "%.2f", value)

private fun fmtPct(value: Double): String = String.format(Locale.CHINA, "%.1f%%", value)
