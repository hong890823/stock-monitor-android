package com.example.stockmonitor.ui

import com.example.stockmonitor.analysis.MarketRiskPolicy
import com.example.stockmonitor.analysis.MarketVolumeBaseline
import com.example.stockmonitor.analysis.StockRiskPolicy

/** 工具页监控卡片的简短说明，与监控策略常量保持同源。 */
object MonitoringCopy {
    data class Rule(val label: String, val text: String)

    fun rules(dropAlertPct: Double = 5.0): List<Rule> = listOf(
        Rule(
            "监控范围",
            "持仓：跌幅、破位、买卖点和做T；大盘：风险；观察池：只提醒进入“买点已确认”",
        ),
        Rule(
            "触发限制",
            "仅交易时段；持仓破位立即提醒风险，确认后分批减仓，高位转弱优先减仓，结构失效时退出；同类信号10分钟冷却。机会提醒每日最多8条，风险升级不受此上限限制；" +
                "相邻刷新急跌阈值可在设置修改（当前 ${"%.1f".format(java.util.Locale.CHINA, dropAlertPct)}%）",
        ),
        Rule(
            "风险阈值",
            "大盘同步 -${MarketRiskPolicy.EARLY_WARNING_PCT}% 两轮；单指数 -${MarketRiskPolicy.RISK_WARNING_PCT}% / " +
                "-${MarketRiskPolicy.SEVERE_WARNING_PCT}%；5分钟 -0.3% / -0.5% / -0.8%；持仓日内 " +
                "-${StockRiskPolicy.DAILY_WARNING_PCT.toInt()}% / -${StockRiskPolicy.DAILY_RISK_PCT.toInt()}%",
        ),
        Rule(
            "成交额参考",
            "近${MarketVolumeBaseline.MAXIMUM_DAYS}日同期中位数（至少${MarketVolumeBaseline.MINIMUM_DAYS}日）：" +
                "1.5 / 2 / 3倍 = 放量 / 明显 / 异常",
        ),
        Rule(
            "T+1与双周期",
            "交易批次区分今日可卖旧仓与今日买入锁定；卖出建议只用当日可卖股数。15分钟用于执行触发，30分钟用于方向过滤，" +
                "数据不完整或方向反向时仅观察；风险卖出不等待30分钟过滤。",
        ),
        Rule(
            "龙头提示",
            "涉及非持仓板块龙头时标注“板块状态参考”，属于非买卖指令，只用于解释同板块持仓的做T节奏。",
        ),
        Rule(
            "重新武装",
            "指数均回到 -${MarketRiskPolicy.REARM_PCT}% 以上，只解除大盘风险提醒",
        ),
    )
}
