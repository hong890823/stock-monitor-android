package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.DayBar

/**
 * 支撑有效性判定：支撑价位是否「真实有效」。
 * 口径（2026-11-26，与 mac 端 support_validity.py 用户确认版一致）：
 * 近 [window] 根已完成日线内触及 ≥[MIN_TOUCHES] 次且从未收破，
 * 或锚点处于强势档（[StrengthTier.STRONG]，上行锚本身就是有效证据）。
 * 不满足时，补仓 / 接回 / 站回 / 回踩买点类操作提醒只留痕不推送（见 [AlertPushPolicy]）。
 *
 * - touch：bar.low <= support × (1 + 容差)（触及含等于）
 * - closed_below：bar.close < support × (1 - 容差)（收破严格小于，恰好收在线上不算）
 * - 容差默认 0.5%，与 [HoldingExitPolicy] 的破位容差同口径
 * - 数据缺失一律保守判无效（"数据不足"）
 */
object SupportValidityPolicy {

    const val TOUCH_TOLERANCE_PCT = 0.5
    const val BREAK_TOLERANCE_PCT = 0.5
    const val MIN_TOUCHES = 2

    const val VIA_ANCHOR_STRONG = "anchor_strong"
    const val VIA_TOUCH_RULE = "touch_rule"
    const val VIA_NONE = "none"

    data class SupportValidity(
        val valid: Boolean,
        /** anchor_strong | touch_rule | none */
        val via: String,
        val touches: Int,
        val closedBelow: Boolean,
        val reason: String,
    )

    fun evaluate(
        support: Double,
        bars: List<DayBar>,
        anchorStrong: Boolean = false,
        window: Int = 20,
    ): SupportValidity {
        if (!support.isFinite() || support <= 0) {
            return SupportValidity(false, VIA_NONE, 0, false, "数据不足：缺少有效支撑价")
        }
        // 强势档锚点本身就是「真实有效」证据，不再要求触及次数。
        if (anchorStrong) {
            return SupportValidity(true, VIA_ANCHOR_STRONG, 0, false, "强势档锚点")
        }
        val rows = bars.takeLast(maxOf(1, window)).mapNotNull { bar ->
            val low = bar.low
            val close = bar.close
            if (!low.isFinite() || !close.isFinite() || low <= 0 || close <= 0) null else low to close
        }
        if (rows.size < 2) {
            return SupportValidity(false, VIA_NONE, 0, false, "数据不足：可评估K线不足2根")
        }
        val touchLine = support * (1 + TOUCH_TOLERANCE_PCT / 100)
        val breakLine = support * (1 - BREAK_TOLERANCE_PCT / 100)
        // 边界含/不含用 ε 保护：low 恰好在触及线上要算触及，close 恰好在破位线上不算收破。
        val touchEps = 1e-9 * maxOf(1.0, kotlin.math.abs(touchLine))
        val breakEps = 1e-9 * maxOf(1.0, kotlin.math.abs(breakLine))
        val touches = rows.count { (low, _) -> low <= touchLine + touchEps }
        val closedBelow = rows.any { (_, close) -> close < breakLine - breakEps }
        return when {
            closedBelow -> SupportValidity(false, VIA_NONE, touches, true, "曾收破支撑")
            touches >= MIN_TOUCHES ->
                SupportValidity(true, VIA_TOUCH_RULE, touches, false, "近${rows.size}根K线触及${touches}次未收破")
            else -> SupportValidity(false, VIA_NONE, touches, false, "触及不足${MIN_TOUCHES}次")
        }
    }
}
