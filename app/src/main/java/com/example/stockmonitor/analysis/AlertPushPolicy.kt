package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.GuardedActionLevel

/**
 * 投递分类（推送 / 仅记录）：一条提醒是系统推送，还是只留痕在提醒账本里回看。
 * 口径（2026-11-26，与 mac 端 alert_push_policy.py 用户确认版同源）：
 * - 处置指令级（可执行买卖、T+1 次日处置）→ 推送；
 * - 评估/观察级（仅观察、风险观察）→ 仅记录（delivery_status="record_only"）；
 * - 未知/缺信息默认推送（fail-open）。
 *
 * 本分类器是**附加门**：只决定投递方式，不放松任何执行守卫，也不抑制风险信号——
 * 风险事实（破位提示/高开低走）保留推送通道。用户调过弹窗口径的通道
 * （深跌回升恐慌坑、反弹跟踪）不走本分类器降级，保持推送。
 */
object AlertPushPolicy {

    enum class Delivery { PUSH, RECORD_ONLY }

    data class PushDecision(val delivery: Delivery, val reason: String) {
        val isPush: Boolean get() = delivery == Delivery.PUSH
    }

    /**
     * 盘中守卫结果的投递分类。
     * [riskFactPush]：破位提示 / 高开低走这类风险事实提示——只报告风险不生成卖量，
     * 但要送达（此前 RISK_FACT 被 ACTIONABLE 过滤吞掉的教训，见 MonitorService 注释）。
     */
    fun classify(
        level: GuardedActionLevel,
        riskFactPush: Boolean = false,
    ): PushDecision = when (level) {
        GuardedActionLevel.ACTIONABLE ->
            PushDecision(Delivery.PUSH, "处置指令级保留推送")
        GuardedActionLevel.NEXT_DAY ->
            PushDecision(Delivery.PUSH, "T+1次日处置指令保留推送")
        GuardedActionLevel.RISK_FACT ->
            if (riskFactPush) {
                PushDecision(Delivery.PUSH, "风险事实提示保留推送")
            } else {
                PushDecision(Delivery.RECORD_ONLY, "风险观察级只留痕")
            }
        GuardedActionLevel.OBSERVATION ->
            PushDecision(Delivery.RECORD_ONLY, "观察级只留痕，账本可回看")
    }

    /**
     * 支撑操作类买点（站回支撑 / 回踩接回 / 回踩买点）的投递分类：
     * 支撑被验证（[SupportValidityPolicy]）才推送，未验证只留痕。
     * [supportValid] 为 null（数据缺失/旧缓存）时 fail-open 保持推送。
     */
    fun classifySupportOp(supportValid: Boolean?): PushDecision = when (supportValid) {
        false -> PushDecision(Delivery.RECORD_ONLY, "支撑未验证的操作候选只留痕")
        else -> PushDecision(Delivery.PUSH, "支撑已验证或缺省放行")
    }
}
