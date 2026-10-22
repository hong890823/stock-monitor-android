package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.Quote
import java.util.Locale

enum class HoldingExitStage(val label: String) {
    WATCH("破位待确认"), REDUCE("确认破位·分批减仓"), HIGH_REDUCE("高位转弱·优先减仓"),
    EXIT("退出优先"), DEEP_T("深套风险观察"), NOTICE("破位风险提示")
}

data class HoldingExitDecision(val stage: HoldingExitStage, val action: String)

object HoldingExitPolicy {
    const val BREAK_TOLERANCE = 0.005

    /** 仓位占总资金低于这个比例算「轻仓」：亏了也伤不到账户，破位不必给命令式卖出。 */
    const val LIGHT_POSITION_PCT = 5.0

    data class Input(
        val price: Double,
        val support: Double,
        val quantity: Double,
        val cost: Double,
        val atrPct: Double? = null,
        val volumeRatio: Double? = null,
        val trendWeak: Boolean = false,
        val confirmed: Boolean = false,
        val exitPrice: Double? = null,
        val fundamentalAlert: Boolean = false,
        val synchronizedWeakness: Boolean = false,
        val gapDown: Boolean = false,
        val priceContext: PriceContext? = null,
        val changePct: Double? = null,
        val holdingTContext: HoldingTContext? = null,
        val holdingTObservation: HoldingTObservation? = null,
        /** 持仓市值占总资金的百分比；总资金没填时为 null，缓释不生效（行为同旧口径）。 */
        val positionPct: Double? = null,
    )

    fun exitReference(support: Double, atrPct: Double?): Double = support *
        (1 - maxOf(2.0, (atrPct?.takeIf { it.isFinite() && it > 0 } ?: 0.0) * 1.5) / 100)

    fun evaluate(input: Input): HoldingExitDecision? {
        if (!input.quantity.isFinite() || input.quantity <= 0 || !input.price.isFinite() || input.price <= 0 ||
            !input.support.isFinite() || input.support <= 0) return null
        val broken = input.price < input.support * (1 - BREAK_TOLERANCE)
        val high = input.priceContext?.location == PriceLocation.HIGH
        val highWeakness = if (high) highWeakness(input) else null
        if (!broken && highWeakness == null) return null
        val expanded = input.volumeRatio?.let { it.isFinite() && it >= VolumeRatio.EXPAND_THRESHOLD } == true
        val confirmed = broken && (input.confirmed || expanded || input.synchronizedWeakness || input.gapDown)
        val reference = (input.exitPrice ?: exitReference(input.support, input.atrPct))
            .takeIf { it.isFinite() && it > 0 && it < input.support }
        val structureFailed = input.trendWeak && reference != null && input.price < reference
        // 三项缓释：基本面没亮红灯 + 价格在区间低位 + 仓位轻——破位事实照报，
        // 但命令式卖出（减仓/清仓）换成「风险提示、去留自定」。基本面红灯、高位、重仓不缓释。
        val mitigated = broken && !input.fundamentalAlert &&
            input.priceContext?.location == PriceLocation.LOW &&
            input.positionPct != null && input.positionPct.isFinite() &&
            input.positionPct >= 0.0 && input.positionPct < LIGHT_POSITION_PCT
        val stage = when {
            !mitigated && confirmed && (structureFailed || input.fundamentalAlert) -> HoldingExitStage.EXIT
            broken && input.fundamentalAlert -> HoldingExitStage.WATCH
            (confirmed && high) || highWeakness != null -> HoldingExitStage.HIGH_REDUCE
            DeepLossPolicy.isDeepLoss(input.cost, input.price, input.atrPct) -> HoldingExitStage.DEEP_T
            confirmed && mitigated -> HoldingExitStage.NOTICE
            confirmed -> HoldingExitStage.REDUCE
            else -> HoldingExitStage.WATCH
        }
        val evidence = when {
            !confirmed && highWeakness != null -> highWeakness
            input.confirmed -> "连续有效行情未收回支撑"
            expanded -> "放量破位"
            input.gapDown -> "跳空跌破支撑且尚未收回"
            input.synchronizedWeakness -> "破位且大盘、板块同步走弱"
            else -> "首次跌破支撑，尚缺持续确认"
        }
        val referenceText = reference?.let { "自动风险参考 ${String.format(Locale.CHINA, "%.2f", it)}" } ?: "风险参考暂缺"
        val action = when (stage) {
            HoldingExitStage.WATCH -> "$evidence；暂不加仓，短时观察能否收回；确认未收回则分批减仓。$referenceText。" +
                (if (input.fundamentalAlert) "基本面红灯需核实，暂停做T接回；破位确认后优先退出。" else "")
            HoldingExitStage.REDUCE -> "$evidence：优先分批减仓降低风险，不必等待反弹；首次可评估减出约1/3可卖仓位。" +
                "剩余仓位若进一步失守${referenceText}且趋势转弱，升级为退出评估。"
            HoldingExitStage.HIGH_REDUCE -> "现价处于阶段高位，叠加$evidence；优先分批减仓，首次可评估减出约1/3可卖仓位，暂停加仓和做T接回。" +
                "高位本身不是清仓依据；剩余仓位按确认破位后的趋势失效或基本面风险评估退出。"
            HoldingExitStage.EXIT -> "$evidence；" +
                (if (structureFailed) "趋势转弱且失守$referenceText" else "叠加基本面红灯，需核实财务与公告风险") +
                "，退出优先：核对证据后以清仓退出为目标，可按流动性分笔执行，不再只减1/3或等反弹做T。"
            HoldingExitStage.DEEP_T -> "当前优先：$evidence；深套先不补仓，未触发退出条件前只评估机动仓滚动做T，反弹减出、企稳接回，尝试降低成本但不保证解套；" +
                "若持续破位叠加结构失效或基本面风险，优先退出，不因深套无限等待。$referenceText。"
            HoldingExitStage.NOTICE -> "$evidence；但基本面未亮红灯、价格处低位、仓位也轻——去留按你的风险偏好决定，不强制卖出。" +
                "若减，首次可评估减出约1/3可卖仓位；若留，盯${referenceText}，再失守且趋势转弱才按退出评估。"
        }
        return HoldingExitDecision(stage, action + "卖出以实际可卖数量为准，受T+1、跌停及流动性限制，不保证成交。")
    }

    private fun highWeakness(input: Input): String? {
        val context = input.holdingTContext ?: return null
        val observed = input.holdingTObservation ?: return null
        val change = input.changePct?.takeIf { it.isFinite() } ?: return null
        val ratio = input.volumeRatio?.takeIf { it.isFinite() && it > 0 } ?: return null
        if (context.trend != HoldingTTrend.EXTENDED || !context.atrPct.isFinite() || context.atrPct <= 0 ||
            !context.gain5Pct.isFinite() || context.gain5Pct < maxOf(6.0, context.atrPct * 3) ||
            !observed.falling || observed.rising || !observed.retreatPct.isFinite() ||
            !observed.low.isFinite() || !observed.high.isFinite() || observed.low <= 0 ||
            observed.high <= observed.low || input.price !in observed.low..observed.high) return null
        return when {
            ratio >= VolumeRatio.EXPAND_THRESHOLD && kotlin.math.abs(change) <= 1.0 &&
                observed.retreatPct >= maxOf(0.5, context.atrPct * 0.25) -> "连续拉升后放量滞涨，且连续行情已确认回落"
            observed.retreatPct >= maxOf(1.0, context.atrPct * 0.5) -> "连续拉升后冲高回落，且连续行情已确认转弱"
            else -> null
        }
    }
}

data class HoldingBreakObservation(val support: Double, val exitPrice: Double, val confirmed: Boolean)

class HoldingBreakTracker {
    private data class Episode(val day: String, val support: Double, val exitPrice: Double,
        var firstAt: Long, var lastAt: Long, var volume: Double, var confirmed: Boolean = false)
    private val episodes = mutableMapOf<String, Episode>()

    @Synchronized fun clear() = episodes.clear()
    @Synchronized fun retainCodes(codes: Set<String>) { episodes.keys.retainAll(codes) }

    @Synchronized fun snapshot(code: String, quote: Quote, today: String, nowMs: Long): HoldingBreakObservation? {
        val episode = episodes[code] ?: return null
        if (quote.quoteDate != today || episode.day != today ||
            !quote.price.isFinite() || quote.price <= 0 || quote.price >= episode.support) return null
        val confirmed = episode.confirmed && nowMs - episode.lastAt in 0..90_000 &&
            quote.volume.isFinite() && quote.volume >= episode.volume
        return HoldingBreakObservation(episode.support, episode.exitPrice, confirmed)
    }

    @Synchronized fun observe(quote: Quote, support: Double, atrPct: Double?, today: String, nowMs: Long): HoldingBreakObservation? {
        if (quote.quoteDate != today || !quote.price.isFinite() || quote.price <= 0) {
            episodes.remove(quote.code)
            return null
        }
        var episode = episodes[quote.code]?.takeIf { it.day == today }
        val anchor = episode?.support ?: support
        if (!anchor.isFinite() || anchor <= 0 || quote.price >= anchor) {
            episodes.remove(quote.code)
            return null
        }
        if (episode == null) {
            if (quote.price >= anchor * (1 - HoldingExitPolicy.BREAK_TOLERANCE)) return null
            episode = Episode(today, anchor, HoldingExitPolicy.exitReference(anchor, atrPct), nowMs, nowMs, quote.volume)
            episodes[quote.code] = episode
        } else if (!quote.volume.isFinite() || quote.volume <= 0 || !episode.volume.isFinite() || episode.volume <= 0 ||
            quote.volume < episode.volume || nowMs - episode.lastAt !in 0..90_000 ||
            quote.price >= anchor * (1 - HoldingExitPolicy.BREAK_TOLERANCE)) {
            episode.firstAt = nowMs
            episode.lastAt = nowMs
            episode.volume = quote.volume
            episode.confirmed = false
        } else if (quote.volume > episode.volume && nowMs > episode.lastAt) {
            episode.confirmed = episode.confirmed || nowMs - episode.firstAt >= 30_000
            episode.lastAt = nowMs
            episode.volume = quote.volume
        }
        return HoldingBreakObservation(anchor, episode.exitPrice, episode.confirmed && nowMs - episode.lastAt in 0..90_000)
    }
}
