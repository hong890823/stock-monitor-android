package com.example.stockmonitor.analysis

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * 高开低走 / 冲高回落识别（指数与持仓共用口径）。
 *
 * 只回答“今天是否出现值得提醒的高开回落”，不生成任何交易指令。交易侧仍由
 * TradeActionGuard 复核 T+1、可卖数量、分仓与 15/30 分钟证据。
 *
 * 口径：
 * - 高开：开盘价高于昨收；
 * - 回落：现价较当日最高价回撤；
 * - 高开低走：高开之后现价跌破开盘价（不是“只要从高点回落”就报）；
 * - 持仓还允许“低开冲高回落”：日内最高相对昨收至少 +1.5%，
 *   回撤达到阈值且跌回开盘价下方或转跌。
 */
data class GapFadeFinding(
    val kind: String,
    val severity: String,
    val gapUpPct: Double,
    val highGainPct: Double,
    val fadePct: Double,
    val gainPct: Double,
    val priceVsOpenPct: Double,
    val brokeOpen: Boolean,
    val brokePrevClose: Boolean,
    val pattern: String,
)

object GapFadePolicy {
    const val INDEX_MIN_GAP_UP_PCT = 0.25
    const val INDEX_MIN_FADE_PCT = 0.45
    const val INDEX_DANGER_FADE_PCT = 1.0
    const val INDEX_DANGER_GAIN_PCT = -0.20

    const val HOLDING_MIN_GAP_UP_PCT = 0.50
    const val HOLDING_MIN_HIGH_GAIN_PCT = 1.50
    const val HOLDING_MIN_FADE_PCT = 1.00
    const val HOLDING_ATR_FADE_MULTIPLIER = 0.60
    const val HOLDING_MAX_FADE_THRESHOLD = 3.00

    const val MIN_SESSION_MINUTES = 10

    fun evaluate(
        openPrice: Double?,
        previousClose: Double?,
        highPrice: Double?,
        price: Double,
        atrPct: Double? = null,
        sessionMinutes: Int? = null,
        holding: Boolean,
    ): GapFadeFinding? {
        if (openPrice == null || previousClose == null || highPrice == null) return null
        if (!openPrice.isFinite() || !previousClose.isFinite() || !highPrice.isFinite() || !price.isFinite()) {
            return null
        }
        if (openPrice <= 0.0 || previousClose <= 0.0 || highPrice <= 0.0 || price <= 0.0) return null
        if (highPrice < max(openPrice, price)) return null
        if (sessionMinutes != null && sessionMinutes < MIN_SESSION_MINUTES) return null

        val gapUpPct = (openPrice / previousClose - 1.0) * 100.0
        val highGainPct = (highPrice / previousClose - 1.0) * 100.0
        val gainPct = (price / previousClose - 1.0) * 100.0
        val fadePct = (highPrice - price) / highPrice * 100.0
        val priceVsOpenPct = (price / openPrice - 1.0) * 100.0
        val brokeOpen = price < openPrice
        val brokePrevClose = price < previousClose

        if (holding) {
            val gapOrHigh = gapUpPct >= HOLDING_MIN_GAP_UP_PCT || highGainPct >= HOLDING_MIN_HIGH_GAIN_PCT
            val atr = atrPct?.takeIf { it.isFinite() && it > 0.0 }
            val fadeThreshold = max(
                HOLDING_MIN_FADE_PCT,
                if (atr == null) 0.0 else min(HOLDING_MAX_FADE_THRESHOLD, atr * HOLDING_ATR_FADE_MULTIPLIER),
            )
            if (!gapOrHigh || fadePct + 1e-9 < fadeThreshold) return null
            if (!brokeOpen && gainPct > 0.0) return null
            return GapFadeFinding(
                kind = "holding",
                severity = if (gainPct <= 0.0) "danger" else "warning",
                gapUpPct = round2(gapUpPct),
                highGainPct = round2(highGainPct),
                fadePct = round2(fadePct),
                gainPct = round2(gainPct),
                priceVsOpenPct = round2(priceVsOpenPct),
                brokeOpen = brokeOpen,
                brokePrevClose = brokePrevClose,
                pattern = if (gapUpPct >= HOLDING_MIN_GAP_UP_PCT) "高开低走" else "冲高回落",
            )
        }

        if (gapUpPct + 1e-9 < INDEX_MIN_GAP_UP_PCT) return null
        if (fadePct + 1e-9 < INDEX_MIN_FADE_PCT || !brokeOpen) return null
        return GapFadeFinding(
            kind = "index",
            severity = if (gainPct <= INDEX_DANGER_GAIN_PCT || fadePct >= INDEX_DANGER_FADE_PCT) {
                "danger"
            } else {
                "warning"
            },
            gapUpPct = round2(gapUpPct),
            highGainPct = round2(highGainPct),
            fadePct = round2(fadePct),
            gainPct = round2(gainPct),
            priceVsOpenPct = round2(priceVsOpenPct),
            brokeOpen = true,
            brokePrevClose = brokePrevClose,
            pattern = "高开低走",
        )
    }

    fun indexTitle(name: String): String = "📉 $name 高开低走"

    fun indexReason(finding: GapFadeFinding): String = String.format(
        Locale.CHINA,
        "高开%+.2f%%（相对昨收），日内最高%+.2f%%，现价%+.2f%%，较日高回落%.2f%%，已跌破开盘价；高开买盘未承接",
        finding.gapUpPct,
        finding.highGainPct,
        finding.gainPct,
        finding.fadePct,
    )

    fun indexBody(finding: GapFadeFinding): String = String.format(
        Locale.CHINA,
        "高开%+.2f%%→现价%+.2f%%｜较日高回落%.2f%%｜注意指数回落向持仓传导；不追高、不在下跌途中补仓，先核对风险位",
        finding.gapUpPct,
        finding.gainPct,
        finding.fadePct,
    )

    fun holdingTitle(name: String, finding: GapFadeFinding): String = "📉 $name ${finding.pattern}"

    fun holdingBody(finding: GapFadeFinding): String = String.format(
        Locale.CHINA,
        "开盘%+.2f%%（相对昨收），日内最高%+.2f%%，现价%+.2f%%，较日内高点回落%.2f%%，%s。" +
            "日内涨幅正在回吐，建议评估做T或减仓：今日新增仓位受A股T+1限制，可卖数量以交易记录为准；" +
            "本提醒不生成股数、不自动下单，未成交不接回。",
        finding.gapUpPct,
        finding.highGainPct,
        finding.gainPct,
        finding.fadePct,
        if (finding.brokeOpen) "已跌破开盘价" else "已回吐至转跌",
    )

    fun holdingNotification(finding: GapFadeFinding): String = String.format(
        Locale.CHINA,
        "高开%+.2f%%→现价%+.2f%%｜较日高回落%.2f%%｜评估做T/减仓（T+1）",
        finding.gapUpPct,
        finding.gainPct,
        finding.fadePct,
    )

    private fun round2(value: Double): Double = round(value * 100.0) / 100.0
}
