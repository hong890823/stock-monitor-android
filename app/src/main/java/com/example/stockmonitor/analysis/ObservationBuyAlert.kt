package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationCandidate
import com.example.stockmonitor.core.ObservationAlertState
import com.example.stockmonitor.core.ObservationStage
import com.example.stockmonitor.core.ObservationStrategy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ObservationBuyAlert {
    fun alertKey(candidate: ObservationCandidate): String = candidate.code

    /** 非持仓股的盘中提醒只允许这里的真实“买点已确认”状态。 */
    fun canNotify(candidate: ObservationCandidate): Boolean =
        candidate.stage == ObservationStage.BUY_CANDIDATE &&
            !ObservationEligibility.isLimitUpDeferred(candidate, null) &&
            !ObservationEligibility.isDeferredOrRisk(candidate)

    fun shouldNotify(
        previous: ObservationCandidate,
        current: ObservationCandidate,
    ): Boolean = previous.stage != ObservationStage.BUY_CANDIDATE &&
        canNotify(current) &&
        (current.strategy == ObservationStrategy.PRE_BREAKOUT ||
            current.dailyBaseEntryConfirmed ||
            current.structureQuality == com.example.stockmonitor.core.ObservationStructureQuality.POST_LIMIT_HIGH_HOLD ||
            current.entryConfirmationRounds >= ObservationPool.requiredConfirmationRounds(current))

    fun title(candidate: ObservationCandidate, executionReady: Boolean = true): String =
        if (executionReady) {
            "🟢 ${ObservationStage.BUY_CANDIDATE.label}｜${candidate.name}"
        } else {
            "🟡 买点结构确认·执行条件待满足｜${candidate.name}"
        }

    fun body(candidate: ObservationCandidate): String = buildString {
        append(candidate.code)
        append("｜")
        append(
            if (WatchlistBuyCandidatePool.SOURCE in candidate.allSources) {
                "自选股"
            } else {
                candidate.strategy.label
            },
        )
        append("｜现价")
        append(String.format(Locale.CHINA, "%.2f", candidate.lastPrice))
        append("｜")
        append(candidate.reason.ifBlank { "${ObservationStage.BUY_CANDIDATE.label}，请核对执行价格与仓位" })
        candidate.stopPrice?.let {
            append("｜止损参考")
            append(String.format(Locale.CHINA, "%.2f", it))
        }
    }

    fun body(
        candidate: ObservationCandidate,
        guardedText: String,
        executionReady: Boolean = true,
    ): String = buildString {
        append(candidate.code)
        append("｜")
        append(
            if (WatchlistBuyCandidatePool.SOURCE in candidate.allSources) {
                "自选股"
            } else {
                candidate.strategy.label
            },
        )
        append("｜现价")
        append(String.format(Locale.CHINA, "%.2f", candidate.lastPrice))
        append("｜")
        append(candidate.reason.ifBlank { "买点结构已确认" })
        append("｜")
        append(
            if (executionReady) {
                guardedText
            } else {
                "买点结构已确认，但执行条件待满足：${guardedText.removePrefix("仅观察：")}"
            },
        )
        candidate.stopPrice?.let {
            append("｜失效参考")
            append(String.format(Locale.CHINA, "%.2f", it))
        }
    }

    /** 把通知链路结果写回候选详情，避免只在前台列表显示“可参与”却无法解释是否真的发出。 */
    fun deliveryText(candidate: ObservationCandidate): String {
        val time = candidate.alertUpdatedAtMs.takeIf { it > 0L }?.let {
            SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(it))
        }
        val detail = candidate.alertDetail.trim()
        return when (candidate.alertState) {
            ObservationAlertState.UNKNOWN -> "尚无通知记录"
            ObservationAlertState.PENDING -> buildString {
                append("待发送")
                time?.let { append("｜").append(it) }
                if (detail.isNotBlank()) append("｜").append(detail)
            }
            ObservationAlertState.SENT_ACTIONABLE -> buildString {
                append("已通知（执行核验通过）")
                time?.let { append("｜").append(it) }
                if (detail.isNotBlank()) append("｜").append(detail)
            }
            ObservationAlertState.SENT_CAUTION -> buildString {
                append("已通知（买点结构确认，执行条件待满足）")
                time?.let { append("｜").append(it) }
                if (detail.isNotBlank()) append("｜").append(detail)
            }
            ObservationAlertState.SENT_EARLIER -> buildString {
                append("已通知（今日已有同股买点提醒）")
                time?.let { append("｜").append(it) }
                if (detail.isNotBlank()) append("｜").append(detail)
            }
            ObservationAlertState.BLOCKED -> buildString {
                append("未通知")
                time?.let { append("｜").append(it) }
                append("：").append(detail.ifBlank { "原因暂缺" })
            }
        }
    }
}
