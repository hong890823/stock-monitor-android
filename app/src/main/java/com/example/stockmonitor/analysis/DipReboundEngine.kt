package com.example.stockmonitor.analysis

import java.util.Locale

/** 下探回升（V 型收复）提醒的阈值。大盘与个股分开定，个股要砸得更深才算数。 */
object DipReboundPolicy {

    /** 大盘指数：日内低点较昨收跌到这个幅度才算「下探够深」（上证 4000 点时约 28 点）。 */
    const val INDEX_DIP_PCT = -0.7

    /** 创业板波动本来就大，-0.7% 只是日常噪声，阈值更深。 */
    const val CHINEXT_DIP_PCT = -1.2

    /** 大盘自低点至少回升这么多（百分点）。 */
    const val INDEX_REBOUND_PCT = 0.5

    /** 持仓个股：日内低点较昨收跌到这个幅度才算被砸（无 ATR 时的兜底）。 */
    const val STOCK_DIP_PCT = -3.0

    /** 个股下探阈值按 ATR% 缩放的系数：约 1.2 个 ATR 日的砸盘才算被砸。 */
    const val STOCK_DIP_ATR_MULT = 1.2

    /** 缩放后的个股下探阈值上下限。 */
    const val STOCK_DIP_MIN_PCT = -5.0
    const val STOCK_DIP_MAX_PCT = -2.0

    /** 个股回升门槛 = 下探幅度的这个比例（默认下探 -3% 时正好是 2%）。 */
    const val STOCK_REBOUND_OF_DIP = 2.0 / 3.0

    /** 个股自低点至少回升这么多（百分点）；无 ATR 时的兜底。 */
    const val STOCK_REBOUND_PCT = 2.0

    /** 无论大盘还是个股，至少收复日内跌幅的这个比例，防止跌 4% 只反弹 0.5% 就喊反转。 */
    const val RECOVER_RATIO = 0.5

    /**
     * 「资金承接」的判定：回升段每分钟成交额 ≥ 下跌段的这个倍数。
     * 用回升段而不是全天累计——上午恐慌砸盘的量会把缩量反弹也抬成「放量收复」。
     */
    const val LEG_PACE_RATIO = 1.1

    /** 退回累计量比口径时的放量门槛（两市成交额 / 量比相对同期的倍数）。 */
    const val VOLUME_CONFIRM_RATIO = 1.3

    /** 每个代码每天最多提醒几次，防止深 V 来回刷屏。 */
    const val MAX_PER_DAY = 3

    fun dipThresholdPct(code: String, stock: Boolean, atrPct: Double? = null): Double = when {
        stock && atrPct != null && atrPct.isFinite() && atrPct > 0 ->
            (-STOCK_DIP_ATR_MULT * atrPct).coerceIn(STOCK_DIP_MIN_PCT, STOCK_DIP_MAX_PCT)
        stock -> STOCK_DIP_PCT
        code == "sz399006" -> CHINEXT_DIP_PCT
        else -> INDEX_DIP_PCT
    }

    fun reboundThresholdPct(stock: Boolean, dipThresholdPct: Double? = null): Double =
        if (stock && dipThresholdPct != null && dipThresholdPct < 0) {
            -dipThresholdPct * STOCK_REBOUND_OF_DIP
        } else if (stock) {
            STOCK_REBOUND_PCT
        } else {
            INDEX_REBOUND_PCT
        }
}

/**
 * 下探回升提醒：日内跌出恐慌坑后被买回来——尤其是回升段量能不缩的收复——是「抄底资金」
 * 进场的典型痕迹，也是深套持仓补仓 / 做 T 的观察窗口。
 *
 * 已有的预警只管「跌下去」（MarketAlertEngine 大盘、StockRiskPolicy 个股），这里补上
 * 对称的另一半。触发看**跃迁**不看状态：一段下探只提醒一次，只有创出更深的新低（又
 * 一波恐慌）才重新武装，避免 V 型底部来回抖动时反复震动；再叠加每日限次。
 *
 * 大盘与个股阈值分开：个股按 ATR% 缩放（题材股要砸得更深才算被砸），否则日常波动
 * 会把提醒打成刷屏。「放量」看回升段成交节奏，不看全天累计——后者会被上午的恐慌量污染。
 */
class DipReboundEngine {

    /**
     * 一轮检测输入。[cumulativeTurnover] 是当日累计成交额（元），配合 [timestamp] 算
     * 回升段的成交节奏；[volumeRatio] 是同期累计倍数，只作算不出节奏时的退路。
     */
    data class Sample(
        val code: String,
        val name: String,
        val price: Double,
        val changePct: Double,
        val volumeRatio: Double?,
        val cumulativeTurnover: Double? = null,
        val timestamp: Long = 0L,
        val atrPct: Double? = null,
        val stock: Boolean,
    )

    data class Alert(
        val code: String,
        val title: String,
        val body: String,
        /** 回升段量能不缩才给强提醒（震动）。 */
        val vibrate: Boolean,
        val stock: Boolean,
    )

    private data class State(
        var dayLowPct: Double,
        var dayLowTimestamp: Long? = null,
        var dayLowCumulative: Double? = null,
        var firstTimestamp: Long? = null,
        var firstCumulative: Double? = null,
        /** 触发过提醒的低点；只有更深的低点才重新武装。 */
        var emittedLowPct: Double? = null,
        var emittedCount: Int = 0,
    )

    private var tradingDay: String? = null
    private val states = mutableMapOf<String, State>()

    fun update(samples: List<Sample>, day: String): List<Alert> {
        if (tradingDay != day) {
            states.clear()
            tradingDay = day
        }
        return samples.mapNotNull { evaluate(it) }
    }

    fun reset() {
        tradingDay = null
        states.clear()
    }

    private fun evaluate(sample: Sample): Alert? {
        if (!sample.price.isFinite() || sample.price <= 0) return null
        if (!sample.changePct.isFinite()) return null
        val state = states.getOrPut(sample.code) { State(dayLowPct = sample.changePct) }
        if (state.firstTimestamp == null) {
            state.firstTimestamp = sample.timestamp
            state.firstCumulative = sample.cumulativeTurnover
        }
        if (sample.changePct < state.dayLowPct) {
            state.dayLowPct = sample.changePct
            state.dayLowTimestamp = sample.timestamp
            state.dayLowCumulative = sample.cumulativeTurnover
        }
        val dayLowPct = state.dayLowPct
        val dipThreshold = DipReboundPolicy.dipThresholdPct(sample.code, sample.stock, sample.atrPct)
        if (dayLowPct > dipThreshold) return null
        val recovered = sample.changePct - dayLowPct
        if (recovered < DipReboundPolicy.reboundThresholdPct(sample.stock, dipThreshold)) return null
        if (recovered < (0.0 - dayLowPct) * DipReboundPolicy.RECOVER_RATIO) return null
        val emittedLow = state.emittedLowPct
        if (emittedLow != null && dayLowPct >= emittedLow) return null
        if (state.emittedCount >= DipReboundPolicy.MAX_PER_DAY) return null
        state.emittedLowPct = dayLowPct
        state.emittedCount += 1
        val legPace = legPaceRatio(sample, state)
        return buildAlert(sample, dayLowPct, recovered, legPace)
    }

    /**
     * 回升段每分钟成交额 ÷ 下跌段（今日起点到低点）每分钟成交额；数据不够返回 null，
     * 调用方退回累计量比口径。
     */
    private fun legPaceRatio(sample: Sample, state: State): Double? {
        val lowTs = state.dayLowTimestamp ?: return null
        val lowCum = state.dayLowCumulative ?: return null
        val nowCum = sample.cumulativeTurnover ?: return null
        val firstTs = state.firstTimestamp ?: return null
        val firstCum = state.firstCumulative ?: 0.0
        val legMinutes = (sample.timestamp - lowTs) / 60_000.0
        val beforeMinutes = (lowTs - firstTs) / 60_000.0
        if (legMinutes <= 0.5 || beforeMinutes <= 0.5) return null
        val legPace = (nowCum - lowCum).coerceAtLeast(0.0) / legMinutes
        val beforePace = (lowCum - firstCum).coerceAtLeast(0.0) / beforeMinutes
        // 没有下跌段基线（如开在低点附近才启动）：当作算不出，退回累计量比口径，
        // 而不是任何成交都判「放量」。
        if (beforePace <= 0.0) return null
        return legPace / beforePace
    }

    private fun buildAlert(
        sample: Sample,
        dayLowPct: Double,
        recovered: Double,
        legPaceRatio: Double?,
    ): Alert {
        val strong = legPaceRatio != null && legPaceRatio >= DipReboundPolicy.LEG_PACE_RATIO ||
            legPaceRatio == null && sample.volumeRatio != null &&
            sample.volumeRatio >= DipReboundPolicy.VOLUME_CONFIRM_RATIO
        val prevClose = sample.price / (1 + sample.changePct / 100.0)
        val lowPrice = prevClose * (1 + dayLowPct / 100.0)
        val head = "${sample.name}日内最低 " +
            String.format(Locale.CHINA, "%.2f", lowPrice) +
            String.format(Locale.CHINA, "（%+.1f%%）", dayLowPct) +
            String.format(Locale.CHINA, "，现价 %.2f（%+.1f%%）自低点回升 %.1f%%", sample.price, sample.changePct, recovered)
        val volumeText = volumeText(sample, legPaceRatio, strong)
        val title = when {
            sample.stock && strong -> "💰 ${sample.name}·深跌回升（放量）"
            sample.stock -> "📈 ${sample.name}·深跌回升"
            strong -> "💰 ${sample.name}·下探回升（放量）"
            else -> "📈 ${sample.name}·下探回升"
        }
        val takeaway = when {
            // 补仓 / 做 T 是有前提的：个股以收回支撑、企稳为准，不是指数一拉就冲进去。
            strong && sample.stock ->
                "回升段有资金承接迹象——等个股收回支撑并企稳后，再评估小仓补仓或做T；" +
                    "仍以收回支撑、企稳为准，再创新低则取消。"
            strong ->
                "回升段有资金承接迹象——持仓先等个股收回支撑并企稳，再评估补仓或做T。"
            sample.stock ->
                "回升段量能偏弱，先按超跌反弹观察，补仓仍等企稳确认。"
            else ->
                "回升段量能偏弱，先按超跌反弹观察；深套持仓若要补仓仍等个股企稳确认。"
        }
        return Alert(
            code = sample.code,
            title = title,
            body = "$head；$volumeText。$takeaway",
            vibrate = strong,
            stock = sample.stock,
        )
    }

    private fun volumeText(sample: Sample, legPaceRatio: Double?, strong: Boolean): String = when {
        legPaceRatio != null -> String.format(
            Locale.CHINA,
            "回升段成交节奏约为下跌段的 %.1f 倍，%s",
            legPaceRatio.coerceAtMost(9.9),
            if (strong) "量能不缩" else "量能缩了",
        )
        sample.stock && sample.volumeRatio != null -> String.format(
            Locale.CHINA,
            "量比 %.2f（累计口径），%s",
            sample.volumeRatio,
            if (strong) "放量回升" else "量能一般",
        )
        sample.stock -> "量比暂缺"
        sample.volumeRatio != null -> String.format(
            Locale.CHINA,
            "两市成交额约为近 20 日同期中位数的 %s 倍（累计口径），%s",
            MarketVolumeBaseline.formatRatio(sample.volumeRatio),
            if (strong) "放量收复" else "量能一般",
        )
        else -> "成交额数据暂缺"
    }
}
