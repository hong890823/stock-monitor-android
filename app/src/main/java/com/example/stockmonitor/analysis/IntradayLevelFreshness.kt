package com.example.stockmonitor.analysis

/**
 * 盘中信号是否可以使用某份持仓分析结果的支撑/MA20/入场结构。
 *
 * 30 分钟槽位分析可能跨日残留（服务通宵存活），而盘中判定只校验了实时行情日期。
 * 用昨天的支撑判断今天的破位/加仓会给出错误价位，必须等当天的分析结果。
 */
object IntradayLevelFreshness {
    fun usable(analysisDate: String?, today: String): Boolean =
        !analysisDate.isNullOrBlank() && analysisDate == today
}