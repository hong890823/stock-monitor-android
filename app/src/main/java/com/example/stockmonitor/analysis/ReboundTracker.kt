package com.example.stockmonitor.analysis

import java.util.Locale

/** 反弹质量跟踪的阈值。下探/回升的主口径复用 [DipReboundPolicy]，这里只定跟踪与判定。 */
object ReboundTrackPolicy {

    /**
     * 有资格谈「反弹」的最低收复比例（占下探幅度）。比下探回升提醒的 2/3 低——
     * 确认的及时性靠「站稳时间 + 承接量能」补，不靠把收复门槛抬得老高。
     */
    const val RECOVER_OF_DIP = 0.5

    /** 收复幅度的绝对下限（百分点），浅坑的小抖动不进跟踪。 */
    const val RECOVER_MIN_PCT = 1.2

    /** 自日内低点起至少站稳这么久（分钟）才谈「企稳」。 */
    const val STABILIZE_MINUTES = 10.0

    /** 回升段成交节奏不低于下跌段的这个倍数才叫「承接有力」，与 [DipReboundPolicy.LEG_PACE_RATIO] 同口径。 */
    const val LEG_PACE_RATIO = DipReboundPolicy.LEG_PACE_RATIO

    /** 算不出分段节奏时退回累计量比口径的放量门槛，同 [DipReboundPolicy.VOLUME_CONFIRM_RATIO]。 */
    const val VOLUME_CONFIRM_RATIO = DipReboundPolicy.VOLUME_CONFIRM_RATIO

    /** 累计量比口径下，低于这个倍数算「缩量回升」——缩量反弹最像诱多，只给观察不给确认。 */
    const val VOLUME_SHRINK_RATIO = 1.0

    /** 自回升高点回撤超过这么多百分点（可被 ATR% 放大）且走弱，判「疑似诱多」。 */
    const val RETREAT_PCT = 0.8

    /** 回撤阈值按 ATR% 缩放的系数。 */
    const val RETREAT_ATR_MULT = 0.35

    /** 吐回超过回升果实的这个比例（连同回撤阈值一起命中）判「反弹失效」。 */
    const val GIVE_BACK_RATIO = 0.5

    /** 每个代码每天每类事件的上限，防 V 型来回抖动刷屏。 */
    const val MAX_CONFIRM_PER_DAY = 2
    const val MAX_CAUTION_PER_DAY = 2
    const val MAX_TRAP_PER_DAY = 3
    const val MAX_PRESSURE_PER_DAY = 2

    /** 价格推进效率 = 回升段涨速 ÷ 下跌段跌速；低于它算「推不动」，不能算有效反弹。 */
    const val PRICE_PACE_MIN = 0.5

    /** 成交节奏达到这个倍数而价格仍推不动，判「放量滞涨」——抛压/出货痕迹，不是承接。 */
    const val HEAVY_VOLUME_PACE = 1.5

    /** 放量滞涨判定价弱的效率上限（与 [PRICE_PACE_MIN] 拉开距离，中间地带降级为观察）。 */
    const val WEAK_PRICE_PACE = 0.4

    /** 回升高点停更超过这么久（分钟）且成交仍在放，中途就给滞涨警示，不等定性时刻。 */
    const val STALL_MINUTES = 5.0

    fun recoverNeedPct(dayLowPct: Double): Double =
        maxOf(RECOVER_MIN_PCT, -dayLowPct * RECOVER_OF_DIP)

    fun retreatNeedPct(atrPct: Double?): Double =
        if (atrPct != null && atrPct.isFinite() && atrPct > 0) {
            maxOf(RETREAT_PCT, atrPct * RETREAT_ATR_MULT)
        } else {
            RETREAT_PCT
        }
}

/**
 * 反弹质量跟踪：把「下探回升」从一次性提醒变成闭环。
 *
 * 日内深砸后价格回升时，及时性与防诱多天然冲突——报早了是追高被套，报晚了错过窗口。
 * 这里的做法是**先跟踪再定性**：布防（砸出恐慌坑）后持续观察回升段，最后只给三种结论：
 *
 * - [Kind.CONFIRMED] 真反弹：站稳足够久 + 保住回升果实 + **价量配合**（价格推进不拖沓
 *   且量能不缩），给出**失效线**（日内低点）再谈小仓评估——有失效线的提示才叫「抓住」；
 * - [Kind.CAUTIOUS] 企稳观察：站稳了但缩量回升（诱多嫌疑），或成交不少价格却推不动
 *   （回升乏力），只提示观察、别追；
 * - [Kind.PRESSURE] 抛压过大：**放量滞涨**——回升段成交凶猛但价格推进效率极低，
 *   那是换手出货不是承接，高点停更且量还在放时中途就报，与诱多同级当风险；
 * - [Kind.TRAP] 诱多/失效：自回升高点回撤走弱，或吐回大半回升果实——
 *   别追、已接的逢高减，跌破失效线确认离场。
 *
 * 「有效反弹」的量价口径是 2×2：放量+推得动=承接，放量+推不动=抛压，缩量=诱多嫌疑。
 * 每条恐慌腿（创更深新低即换腿）每类结论各最多一次；再创新低重新布防。
 * 纯内存状态机，15 秒 tick 喂一次；`isConfirmed` 供接回 / 站回支撑的信号链当确认源。
 *
 * **分层约束**：跟踪结论只做盘中窗口提示与信号的时机确认，不写回、不影响个股整体趋势
 * 判定——趋势仍由日线口径的 [TrendAssessment] / [HoldingTContext] 说了算，且趋势闸门
 * （allowsTBuy / riskOnly）优先于任何反弹确认。提醒文案统一标注这一点，防止被读成趋势反转。
 */
class ReboundTracker {

    enum class Kind { CONFIRMED, CAUTIOUS, PRESSURE, TRAP }

    /**
     * 一轮检测输入。[cumulativeTurnover] 配合 [timestamp] 算回升段/下跌段的成交节奏，
     * [volumeRatio] 是累计口径的退路；[held] 区分持仓与观察票，决定行动文案。
     */
    data class Sample(
        val code: String,
        val name: String,
        val price: Double,
        val changePct: Double,
        val volumeRatio: Double? = null,
        val cumulativeTurnover: Double? = null,
        val timestamp: Long = 0L,
        val atrPct: Double? = null,
        val resistance: Double = 0.0,
        val held: Boolean = false,
    )

    data class Event(
        val code: String,
        val name: String,
        val kind: Kind,
        val title: String,
        val body: String,
        /** 承接有力的确认、以及失效警示给震动级提醒。 */
        val vibrate: Boolean,
        val held: Boolean,
    )

    private data class Leg(
        var dayLowPct: Double,
        var dayLowTs: Long,
        var dayLowCum: Double?,
        var firstTs: Long,
        var firstCum: Double?,
        var firstPct: Double,
        var legHighPct: Double,
    ) {
        val prices = ArrayDeque<Pair<Long, Double>>()
        var lastHighTs: Long = dayLowTs
        var settled = false
        var confirmedAlive = false
        var trapEmitted = false
        var pressureEmitted = false
    }

    private data class DayCounters(
        var confirm: Int = 0,
        var caution: Int = 0,
        var trap: Int = 0,
        var pressure: Int = 0,
    )

    private var tradingDay: String? = null
    private val legs = mutableMapOf<String, Leg>()
    private val counters = mutableMapOf<String, DayCounters>()

    fun update(samples: List<Sample>, day: String): List<Event> {
        if (tradingDay != day) {
            legs.clear()
            counters.clear()
            tradingDay = day
        }
        return samples.mapNotNull { evaluate(it) }
            .map { it.copy(body = it.body + SCOPE_NOTE) }
    }

    /** 该代码当前是否「真反弹已企稳且未失效」；换腿或诱多/失效后回到 false。 */
    fun isConfirmed(code: String): Boolean = legs[code]?.confirmedAlive == true

    fun reset() {
        tradingDay = null
        legs.clear()
        counters.clear()
    }

    private fun evaluate(sample: Sample): Event? {
        if (!sample.price.isFinite() || sample.price <= 0) return null
        if (!sample.changePct.isFinite()) return null
        val leg = legs.getOrPut(sample.code) {
            Leg(
                dayLowPct = sample.changePct,
                dayLowTs = sample.timestamp,
                dayLowCum = sample.cumulativeTurnover,
                firstTs = sample.timestamp,
                firstCum = sample.cumulativeTurnover,
                firstPct = sample.changePct,
                legHighPct = sample.changePct,
            )
        }
        // 创出更深的低点 = 又一波恐慌，整条腿作废重新布防。
        if (sample.changePct < leg.dayLowPct) {
            val firstTs = leg.firstTs
            val firstCum = leg.firstCum
            val firstPct = leg.firstPct
            legs[sample.code] = Leg(
                dayLowPct = sample.changePct,
                dayLowTs = sample.timestamp,
                dayLowCum = sample.cumulativeTurnover,
                firstTs = firstTs,
                firstCum = firstCum,
                firstPct = firstPct,
                legHighPct = sample.changePct,
            )
            return null
        }
        if (sample.changePct > leg.legHighPct) {
            leg.legHighPct = sample.changePct
            leg.lastHighTs = sample.timestamp
        }
        rememberPrice(leg, sample)
        // 没砸出恐慌坑的日常波动不进跟踪（个股阈值按 ATR 缩放，与下探回升同口径）。
        val dipThreshold = DipReboundPolicy.dipThresholdPct(sample.code, stock = true, atrPct = sample.atrPct)
        if (leg.dayLowPct > dipThreshold) return null

        val recoveredNeed = ReboundTrackPolicy.recoverNeedPct(leg.dayLowPct)
        val peakRecovered = leg.legHighPct - leg.dayLowPct
        val recovered = sample.changePct - leg.dayLowPct
        val retreat = leg.legHighPct - sample.changePct
        val retreatNeed = ReboundTrackPolicy.retreatNeedPct(sample.atrPct)
        val falling = isFalling(leg.prices)
        val pace = legPaceRatio(sample, leg)
        val counter = counters.getOrPut(sample.code) { DayCounters() }

        // 诱多 / 失效优先于确认：先说「别追」，再谈「可以看」。
        if (!leg.trapEmitted && peakRecovered >= recoveredNeed &&
            retreat >= retreatNeed && (falling || recovered <= peakRecovered * (1.0 - ReboundTrackPolicy.GIVE_BACK_RATIO))
        ) {
            leg.trapEmitted = true
            leg.settled = true
            leg.confirmedAlive = false
            if (counter.trap >= ReboundTrackPolicy.MAX_TRAP_PER_DAY) return null
            counter.trap += 1
            return trapEvent(sample, leg, failed = recovered <= peakRecovered * (1.0 - ReboundTrackPolicy.GIVE_BACK_RATIO))
        }
        // 抛压在途：高点停更、成交还在放——那是换手出货不是承接。定性之前就先说「别按有效反弹追」。
        val stalled = sample.timestamp - leg.lastHighTs >= ReboundTrackPolicy.STALL_MINUTES * 60_000
        if (!leg.pressureEmitted && !leg.trapEmitted && peakRecovered >= recoveredNeed &&
            stalled && pace != null && pace >= ReboundTrackPolicy.HEAVY_VOLUME_PACE
        ) {
            leg.pressureEmitted = true
            leg.settled = true
            leg.confirmedAlive = false
            if (counter.pressure >= ReboundTrackPolicy.MAX_PRESSURE_PER_DAY) return null
            counter.pressure += 1
            return pressureEvent(sample, leg, pace)
        }
        if (leg.settled || leg.trapEmitted) return null

        val elapsedMinutes = (sample.timestamp - leg.dayLowTs) / 60_000.0
        if (recovered < recoveredNeed || elapsedMinutes < ReboundTrackPolicy.STABILIZE_MINUTES) return null
        if (falling) return null
        val pricePace = pricePaceRatio(sample, leg)
        val shrunk = isShrunk(pace, sample)
        // 量价 2×2 定性：放量+推不动是抛压不是承接；缩量与「推不动」都只给观察。
        val heavyStall = pace != null && pace >= ReboundTrackPolicy.HEAVY_VOLUME_PACE &&
            pricePace != null && pricePace <= ReboundTrackPolicy.WEAK_PRICE_PACE
        val sluggish = !shrunk && !heavyStall &&
            pricePace != null && pricePace < ReboundTrackPolicy.PRICE_PACE_MIN
        leg.settled = true
        return when {
            heavyStall -> {
                leg.pressureEmitted = true
                leg.confirmedAlive = false
                if (counter.pressure >= ReboundTrackPolicy.MAX_PRESSURE_PER_DAY) return null
                counter.pressure += 1
                pressureEvent(sample, leg, pace)
            }
            shrunk -> {
                if (counter.caution >= ReboundTrackPolicy.MAX_CAUTION_PER_DAY) return null
                counter.caution += 1
                cautionEvent(sample, leg, elapsedMinutes, sluggish = false)
            }
            sluggish -> {
                if (counter.caution >= ReboundTrackPolicy.MAX_CAUTION_PER_DAY) return null
                counter.caution += 1
                cautionEvent(sample, leg, elapsedMinutes, sluggish = true)
            }
            else -> {
                leg.confirmedAlive = true
                if (counter.confirm >= ReboundTrackPolicy.MAX_CONFIRM_PER_DAY) return null
                counter.confirm += 1
                val strong = pace != null && pace >= ReboundTrackPolicy.LEG_PACE_RATIO ||
                    pace == null && sample.volumeRatio != null &&
                    sample.volumeRatio >= ReboundTrackPolicy.VOLUME_CONFIRM_RATIO
                confirmEvent(sample, leg, elapsedMinutes, pace, strong)
            }
        }
    }

    private fun rememberPrice(leg: Leg, sample: Sample) {
        leg.prices.addLast(sample.timestamp to sample.price)
        // 只留最近 3 分钟，走弱/走强判定用窗口内最后几笔（与 HoldingTTracker 同口径）。
        while (leg.prices.isNotEmpty() && sample.timestamp - leg.prices.first().first > 180_000) {
            leg.prices.removeFirst()
        }
    }

    private fun isFalling(prices: ArrayDeque<Pair<Long, Double>>): Boolean {
        if (prices.size < 3) return false
        // 跨度看最后 3 笔本身（与 HoldingTTracker 同口径），不是整个缓冲，
        // 否则密集采样下 1 分钟内的抖动也会被当成走弱。
        val window = prices.takeLast(3)
        val values = window.map { it.second }
        val span = window.last().first - window.first().first
        return span >= 60_000 && values.zipWithNext().all { (previous, current) -> current <= previous } &&
            values.last() < values.first()
    }

    /** 与 [DipReboundEngine] 同口径：回升段每分钟成交额 ÷ 下跌段每分钟成交额；算不出返回 null。 */
    private fun legPaceRatio(sample: Sample, leg: Leg): Double? {
        val nowCum = sample.cumulativeTurnover ?: return null
        val lowCum = leg.dayLowCum ?: return null
        val firstCum = leg.firstCum ?: 0.0
        val legMinutes = (sample.timestamp - leg.dayLowTs) / 60_000.0
        val beforeMinutes = (leg.dayLowTs - leg.firstTs) / 60_000.0
        if (legMinutes <= 0.5 || beforeMinutes <= 0.5) return null
        val legPace = (nowCum - lowCum).coerceAtLeast(0.0) / legMinutes
        val beforePace = (lowCum - firstCum).coerceAtLeast(0.0) / beforeMinutes
        if (beforePace <= 0.0) return null
        return legPace / beforePace
    }

    /**
     * 价格推进效率：回升段每分钟涨的百分点 ÷ 下跌段每分钟跌的百分点。
     * 与 [legPaceRatio] 交叉才分得清「承接」（量价齐进）和「抛压」（放量推不动）；
     * 下跌段基线太薄（如跳空低开直接见底）时算不出，返回 null，不参与抛压判定。
     */
    private fun pricePaceRatio(sample: Sample, leg: Leg): Double? {
        val beforeMinutes = (leg.dayLowTs - leg.firstTs) / 60_000.0
        val legMinutes = (sample.timestamp - leg.dayLowTs) / 60_000.0
        if (legMinutes <= 0.5 || beforeMinutes <= 0.5) return null
        val drop = leg.firstPct - leg.dayLowPct
        if (drop <= 0.05) return null
        val reboundSpeed = (sample.changePct - leg.dayLowPct) / legMinutes
        return reboundSpeed / (drop / beforeMinutes)
    }

    private fun pricesOf(sample: Sample, leg: Leg): Triple<Double, Double, Double> {
        val prevClose = sample.price / (1 + sample.changePct / 100.0)
        val lowPrice = prevClose * (1 + leg.dayLowPct / 100.0)
        val highPrice = prevClose * (1 + leg.legHighPct / 100.0)
        return Triple(prevClose, lowPrice, highPrice)
    }

    private fun confirmEvent(
        sample: Sample,
        leg: Leg,
        elapsedMinutes: Double,
        pace: Double?,
        strong: Boolean,
    ): Event {
        val (_, lowPrice, _) = pricesOf(sample, leg)
        val volumeText = volumeText(pace, sample.volumeRatio, strong)
        val action = if (sample.held) {
            "可小仓评估做T/补仓"
        } else {
            "可小仓试错、别追高"
        }
        return Event(
            code = sample.code,
            name = sample.name,
            kind = Kind.CONFIRMED,
            title = "✅ ${sample.name}·反弹企稳确认",
            body = "日内低 ${fmtPrice(lowPrice)}（${fmtPct(leg.dayLowPct)}）回升至 " +
                "${fmtPrice(sample.price)}（${fmtPct(sample.changePct)}），已站稳 ${elapsedMinutes.toInt()} 分钟，" +
                volumeText + "｜$action；失效线 ${fmtPrice(lowPrice)}，跌破取消。",
            vibrate = strong,
            held = sample.held,
        )
    }

    private fun cautionEvent(sample: Sample, leg: Leg, elapsedMinutes: Double, sluggish: Boolean): Event {
        val (_, lowPrice, _) = pricesOf(sample, leg)
        val reason = if (sluggish) {
            "但成交不少、价格却推不动——抛压仍在消化，别按有效反弹追"
        } else {
            "但回升段量能偏弱——缩量反弹最像诱多，先别追"
        }
        return Event(
            code = sample.code,
            name = sample.name,
            kind = Kind.CAUTIOUS,
            title = if (sluggish) "🔍 ${sample.name}·反弹观察（回升乏力）"
            else "🔍 ${sample.name}·反弹企稳观察（缩量）",
            body = "日内低 ${fmtPrice(lowPrice)}（${fmtPct(leg.dayLowPct)}）回升至 " +
                "${fmtPrice(sample.price)}，已站稳 ${elapsedMinutes.toInt()} 分钟，$reason；" +
                "放量站稳或缩量回踩不破 ${fmtPrice(lowPrice)} 再评估。",
            vibrate = false,
            held = sample.held,
        )
    }

    private fun pressureEvent(sample: Sample, leg: Leg, pace: Double?): Event {
        val (_, lowPrice, highPrice) = pricesOf(sample, leg)
        val volumeText = if (pace != null) {
            String.format(Locale.CHINA, "回升段成交节奏约为下跌段的 %.1f 倍", pace.coerceAtMost(9.9))
        } else {
            "回升段成交明显放大"
        }
        return Event(
            code = sample.code,
            name = sample.name,
            kind = Kind.PRESSURE,
            title = "⚠️ ${sample.name}·回升滞涨（抛压偏重）",
            body = "自低点回升后停在 ${fmtPrice(sample.price)}（高点 ${fmtPrice(highPrice)}），$volumeText，" +
                "但价格推不动——换手出货比承接更像，别按有效反弹追，持仓重的逢高减；" +
                "失效线 ${fmtPrice(lowPrice)}。",
            vibrate = sample.held,
            held = sample.held,
        )
    }

    private fun trapEvent(sample: Sample, leg: Leg, failed: Boolean): Event {
        val (_, lowPrice, highPrice) = pricesOf(sample, leg)
        return if (failed) {
            Event(
                code = sample.code,
                name = sample.name,
                kind = Kind.TRAP,
                title = "⚠️ ${sample.name}·反弹失效",
                body = "已吐回大半回升果实（高点 ${fmtPrice(highPrice)} → 现价 ${fmtPrice(sample.price)}）" +
                    "｜别接飞刀，已接的按失效线 ${fmtPrice(lowPrice)} 处理。",
                vibrate = sample.held,
                held = sample.held,
            )
        } else {
            val retreat = leg.legHighPct - sample.changePct
            val atResistance = sample.resistance > 0 && highPrice >= sample.resistance * 0.98
            Event(
                code = sample.code,
                name = sample.name,
                kind = Kind.TRAP,
                title = if (atResistance) "⚠️ ${sample.name}·冲压力回落（疑似诱多）"
                else "⚠️ ${sample.name}·反弹回落（疑似诱多）",
                body = "自回升高点 ${fmtPrice(highPrice)} 回撤 ${fmtPct(retreat)} 且走弱" +
                    "｜别追，已接的逢高减；跌破 ${fmtPrice(lowPrice)} 确认失效。",
                vibrate = sample.held,
                held = sample.held,
            )
        }
    }

    private fun isShrunk(pace: Double?, sample: Sample): Boolean = when {
        pace != null -> pace < ReboundTrackPolicy.VOLUME_SHRINK_RATIO
        sample.volumeRatio != null -> sample.volumeRatio < ReboundTrackPolicy.VOLUME_SHRINK_RATIO
        else -> true
    }

    private fun volumeText(pace: Double?, volumeRatio: Double?, strong: Boolean): String = when {
        pace != null -> String.format(
            Locale.CHINA,
            "回升段成交节奏约为下跌段的 %.1f 倍（%s）",
            pace.coerceAtMost(9.9),
            if (strong) "承接有力" else "承接一般",
        )
        volumeRatio != null -> String.format(
            Locale.CHINA,
            "量比 %.2f（累计口径，%s）",
            volumeRatio,
            if (strong) "放量回升" else "量能一般",
        )
        else -> "量能数据暂缺"
    }

    private fun fmtPrice(value: Double): String =
        if (value >= 100) String.format(Locale.CHINA, "%.0f", value)
        else String.format(Locale.CHINA, "%.2f", value)

    private fun fmtPct(value: Double): String = String.format(Locale.CHINA, "%+.1f%%", value)

    private companion object {
        /** 每条提醒都带的分层声明：盘中窗口不改整体趋势判断。 */
        const val SCOPE_NOTE = "（仅为盘中窗口，不改趋势判断）"
    }
}
