package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.ObservationEvidence
import com.example.stockmonitor.core.ObservationSignal
import com.example.stockmonitor.core.ObservationStrategy
import java.util.Locale

object ObservationEvidenceFactory {
    fun from(signal: ObservationSignal): ObservationEvidence {
        val supporting = mutableListOf<String>()
        val counter = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val invalidators = mutableListOf(
            "连续3个交易日走弱将淘汰",
            "观察10个交易日未确认将淘汰",
        )

        when (signal.strategy) {
            ObservationStrategy.PRE_BREAKOUT -> {
                if (signal.hasScreenSignal) supporting += "已有选股信号"
                if (signal.setupScore >= 2) supporting += "趋势/突破条件达到${signal.setupScore}项"
                if (signal.confirmedEntry) supporting += "趋势入场已确认"
                if (signal.canBuy && !signal.limitUpBlocked && !signal.limitUpShrinkAcceleration) {
                    supporting += "价格与量能满足介入条件"
                }
                if (signal.setupScore == 0) counter += "尚未形成有效趋势入场结构"
            }

            ObservationStrategy.LEADER -> {
                if (signal.recentLimitUpDays > 0) supporting += "近5日涨停${signal.recentLimitUpDays}次"
                if (signal.relativeStrengthPct != null && signal.relativeStrengthPct >= 0) {
                    supporting += String.format(Locale.CHINA, "相对大盘%+.1f%%", signal.relativeStrengthPct)
                }
                if (signal.volumeRatio != null && signal.volumeRatio >= 1.0) {
                    supporting += String.format(Locale.CHINA, "量比%.2f", signal.volumeRatio)
                }
                if (signal.recentLimitUpDays == 0) counter += "近5日没有涨停持续性"
                if (signal.strongScore < 3) counter += "强势资格或回踩企稳条件尚未全部满足"
                if (signal.confirmedEntry) supporting += "回踩技术条件满足，仍需连续复评；不是资金流入证明"
            }
        }

        if (signal.relativeStrengthPct != null && signal.relativeStrengthPct >= 0) {
            supporting += String.format(Locale.CHINA, "相对大盘%+.1f%%", signal.relativeStrengthPct)
        }
        if (signal.volumeRatio != null && signal.volumeRatio >= 1.0) {
            supporting += String.format(Locale.CHINA, "量比%.2f", signal.volumeRatio)
        }

        if (signal.trendWeak) counter += "当前趋势走弱"
        if (signal.riskFlag) counter += "出现高位分歧风险"
        if (signal.limitUpBlocked) counter += "当前涨停封板，暂不介入；开板后重新确认"
        if (signal.limitUpShrinkAcceleration) {
            counter += ObservationEligibility.SHRINK_WAIT_REASON
        }
        if (signal.changePct < 0) {
            counter += String.format(Locale.CHINA, "当日涨跌幅%+.1f%%", signal.changePct)
        }
        if (signal.relativeStrengthPct == null) missing += "相对大盘强度"
        if (signal.volumeRatio == null) missing += "量比"
        if (signal.stopPrice == null) missing += "止损参考"
        signal.stopPrice?.let {
            invalidators += String.format(Locale.CHINA, "跌破止损参考%.2f需重新评估", it)
        }
        return ObservationEvidence(
            supporting = supporting.distinct(),
            counter = counter.distinct(),
            missing = missing.distinct(),
            invalidators = invalidators.distinct(),
        )
    }
}
