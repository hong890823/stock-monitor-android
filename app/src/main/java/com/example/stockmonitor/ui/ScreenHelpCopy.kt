package com.example.stockmonitor.ui

import com.example.stockmonitor.analysis.VolumePrice

object ScreenHelpCopy {
    const val PRIMARY_SUMMARY =
        "筛选结果基于预设技术条件和前复权数据，仅供观察，不构成买卖建议。"

    const val OBSERVATION_SUMMARY =
        "全池盘中复评；资金流只参与候选池晋级，不改变盘中提醒。"

    const val INDICATOR_SCAN_SUMMARY =
        "严格策略看三项指标共振，宽松预警看指标改善；命中结果统一进入持续观察池的“启动前潜力”列表。"

    const val DEEP_SCAN_SUMMARY =
        "513看量价形态阶段，趋势策略看回踩企稳或突破后守稳；命中结果统一进入持续观察池的“启动前潜力”列表，需后续确认。"

    private const val DATA_BASIS =
        "扫描使用腾讯前复权日线。除权股的历史价格经过复权处理，支撑位、压力位可能不同于当时真实成交价。"

    private const val RESULT_SCOPE =
        "筛选和排序仅用于缩小观察范围；仍需结合基本面、市场环境、仓位和止损纪律判断。"

    private const val OBSERVATION_RULES =
        "全池盘中复评；连续3个交易日走弱或观察10个交易日未确认则淘汰。" +
            "封板中的涨停持续观察不计超时，开板后通常需连续两轮复评；只有三板以上且封板质量偏弱，或同时存在明确趋势/环境风险时才需第三轮；每轮间隔15～45分钟。" +
            "炸板后若守住涨停高位线、平台低点不破并连续承接满5分钟，可按独立高位承接结构确认，跌破平台低点后重新计时。" +
            "513形态来源按3个实际复评交易日计时，周末和节假日不消耗；有其他来源时只移除513来源，不淘汰整只股票。" +
            "启动前潜力主要看趋势入场与风险门槛，不强制强势龙头式多轮确认。" +
            "资金流只影响候选池晋级，不影响盘中提醒。"

    private const val INDICATOR_RULES =
        "严格策略：DIF/DEA 零轴上方金叉 + RSI < 30 + KDJ 低位金叉且 K < 30，三项同时满足。\n" +
            "宽松预警：MACD 柱体转正 / 负区收窄且DIF上升 / 正区放大之一满足，" +
            "再满足 RSI < 45 或 K>D 且 K < 50；排除M20明显破位。"

    private const val DEEP_SCAN_RULES =
        "513按 5 连阳 → 倍量阳 → 3 天缩量的顺序扫描，只使用最近完整交易日，" +
            "不把盘中未完成K线直接判为确认，确认超过3个交易日的形态不再列出；" +
            "要求20日平均成交额不低于5000万元，避免把流动性不足的形态列为可执行短线候选；" +
            "同阶段优先60日区间位置更低的标的，技术分与指标两档、趋势精选同一口径。\n" +
            "趋势精选只保留缩量回踩企稳或突破后次日守住的结构，回踩确认优先；" +
            "突破确认仅作观察，买点仍需持续观察池复核；命中项最多展示20只，按回踩确认、突破确认和量能质量排序。"

    fun detailText(): String =
        buildString {
            appendLine("数据口径")
            appendLine(DATA_BASIS)
            appendLine()
            appendLine("结果边界")
            appendLine(RESULT_SCOPE)
            appendLine()
            appendLine("量价标签")
            append(VolumePrice.helpText)
        }

    fun observationDetailText(): String =
        buildString {
            appendLine("观察与淘汰")
            appendLine(OBSERVATION_RULES)
            appendLine()
            appendLine("结果边界")
            append(RESULT_SCOPE)
        }

    fun indicatorScanDetailText(): String =
        buildString {
            appendLine("筛选条件")
            appendLine(INDICATOR_RULES)
            appendLine()
            appendLine("结果边界")
            append(RESULT_SCOPE)
        }

    fun deepScanDetailText(): String =
        buildString {
            appendLine("扫描条件")
            appendLine(DEEP_SCAN_RULES)
            appendLine()
            appendLine("结果边界")
            append(RESULT_SCOPE)
        }
}
