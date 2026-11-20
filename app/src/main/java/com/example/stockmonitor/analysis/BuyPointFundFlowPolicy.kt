package com.example.stockmonitor.analysis

import java.util.Locale

/**
 * 买点类提醒的“资金承接”过滤。
 *
 * 价格形态转强（首板次日收回开盘价、观察池买点确认）只说明价格条件满足；
 * 如果主力资金同日持续净流出、近 5/15 分钟也没有大单承接，更可能是借反弹出货。
 * 此时不推送提醒（只保留状态/待发），等资金转为承接后再报。
 *
 * 数据口径：资金流按主动成交订单规模估算，不代表机构身份；数据缺失时不硬判出货，
 * 只有“价格仍在均价下方 + 反弹缩量”这种无承接组合才降级。
 */
object BuyPointFundFlowPolicy {
    /** 判定“主力在出货”的单窗口净流出阈值（%）。 */
    const val DISTRIBUTION_PCT = 3.0

    /** 判定“有人承接”的近端净流入阈值（%）。 */
    const val ABSORPTION_PCT = 3.0

    /** 现价低于当日成交均价的容忍度（%）。 */
    const val VWAP_TOLERANCE_PCT = 0.2

    /** 反弹缩量的量比阈值。 */
    const val WEAK_VOLUME_RATIO = 1.0

    data class Input(
        val price: Double,
        /** 当日成交均价 = 成交额 ÷ 成交量；取不到为 null。 */
        val vwap: Double?,
        val volumeRatio: Double?,
        val dayMainNetPct: Double?,
        val recent5MainNetPct: Double?,
        val recent15MainNetPct: Double?,
        /** 仅在非缓存口径下使用；缓存方向不能用来否定今天的行情。 */
        val mainForceDirection: MainForceDirection?,
    )

    data class Decision(
        val allow: Boolean,
        val reason: String,
        val evidence: String,
    )

    fun assess(input: Input): Decision {
        val day = finite(input.dayMainNetPct)
        val recent5 = finite(input.recent5MainNetPct)
        val recent15 = finite(input.recent15MainNetPct)
        val hasFlow = day != null || recent5 != null || recent15 != null

        val absorbing = (recent5 != null && recent5 >= ABSORPTION_PCT) ||
            (recent15 != null && recent15 >= ABSORPTION_PCT)
        val flowDistributing = (day != null && day <= -DISTRIBUTION_PCT) ||
            (recent5 != null && recent5 <= -DISTRIBUTION_PCT)
        val directionDistributing = input.mainForceDirection in setOf(
            MainForceDirection.OUTFLOW_CONFIRMED,
            MainForceDirection.OUTFLOW_UNCONFIRMED,
            MainForceDirection.TURNING_WEAK,
        )
        val distributing = flowDistributing || directionDistributing
        val vwapWeak = input.vwap != null && input.vwap.isFinite() && input.vwap > 0.0 &&
            input.price < input.vwap * (1.0 - VWAP_TOLERANCE_PCT / 100.0)
        val shrinking = input.volumeRatio != null && input.volumeRatio.isFinite() &&
            input.volumeRatio < WEAK_VOLUME_RATIO

        val decision = when {
            distributing && !absorbing -> Decision(
                allow = false,
                reason = "主力净流出且近5/15分钟没有大单承接，疑似借反弹出货",
                evidence = evidence(day, recent5, recent15),
            )
            vwapWeak && !absorbing && shrinking -> Decision(
                allow = false,
                reason = "现价仍在当日成交均价下方且反弹缩量，反弹未获资金承接",
                evidence = evidence(day, recent5, recent15),
            )
            distributing -> Decision(
                allow = true,
                reason = "主力仍有净流出，但近5/15分钟出现大单承接",
                evidence = evidence(day, recent5, recent15),
            )
            absorbing -> Decision(
                allow = true,
                reason = "近5/15分钟大单净流入，反弹有承接",
                evidence = evidence(day, recent5, recent15),
            )
            !hasFlow -> Decision(
                allow = true,
                reason = "资金数据暂缺，仅按量价条件观察",
                evidence = "资金数据暂缺",
            )
            else -> Decision(
                allow = true,
                reason = "资金方向中性",
                evidence = evidence(day, recent5, recent15),
            )
        }
        return decision
    }

    private fun evidence(day: Double?, recent5: Double?, recent15: Double?): String = listOfNotNull(
        day?.let { "当日主力${pct(it)}" },
        recent5?.let { "近5分钟${pct(it)}" },
        recent15?.let { "近15分钟${pct(it)}" },
    ).joinToString("｜").ifBlank { "资金数据暂缺" }

    private fun pct(value: Double): String =
        String.format(Locale.CHINA, "%+.1f%%", value)

    private fun finite(value: Double?): Double? = value?.takeIf { it.isFinite() }
}