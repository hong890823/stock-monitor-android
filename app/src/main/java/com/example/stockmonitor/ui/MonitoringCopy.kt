package com.example.stockmonitor.ui

import com.example.stockmonitor.analysis.MarketRiskPolicy
import com.example.stockmonitor.analysis.MarketVolumeBaseline
import com.example.stockmonitor.analysis.StockRiskPolicy
import com.example.stockmonitor.core.IntradayVolumeContext

/** 工具页监控卡片的简短说明，与监控策略常量保持同源。 */
object MonitoringCopy {
    data class Rule(val label: String, val text: String)

    const val SERVICE_REMINDER =
        "划掉最近任务卡片会立即停止后台监控，常驻通知同时消失；重新打开应用即恢复。"

    fun rules(dropAlertPct: Double = 5.0): List<Rule> = listOf(
        Rule(
            "监控范围",
            "持仓：跌幅、破位、买卖点和做T；大盘：风险；自选股和观察池：只提醒“买点已确认”，" +
                "不推送反弹、封板、炸板或突破风险",
        ),
        Rule(
            "触发限制",
            "仅交易时段；独立减仓信号须通过证据与数量门控；退出候选只发重大风险待复核，不自动清仓或改成减仓，暂停原计划未执行部分；" +
                "普通同类信号10分钟冷却，退出复核按风险证据变化去重。机会提醒每日最多8条，风险复核不占机会预算；" +
                "相邻刷新急跌阈值可在设置修改（当前 ${"%.1f".format(java.util.Locale.CHINA, dropAlertPct)}%）",
        ),
        Rule(
            "风险阈值",
            "只使用已完成、不重叠的5分钟K线；单指数连续两根 -0.3% 只观察，不推送；两指数同窗 -0.5% 且市场宽度偏弱，" +
                "或两指数日跌 -0.8% 并普跌/放量，升级市场风险；多指数 -0.8% 后下一根继续恶化升级系统性风险；" +
                "速度阈值按近期5分钟波动率归一；放量后下一根未止跌或未收复中点才算指数风险；持仓日内 " +
                "-${StockRiskPolicy.DAILY_WARNING_PCT.toInt()}% / -${StockRiskPolicy.DAILY_RISK_PCT.toInt()}%",
        ),
        Rule(
            "成交额参考",
            "近${MarketVolumeBaseline.MAXIMUM_DAYS}日同期中位数（至少${MarketVolumeBaseline.MINIMUM_DAYS}日）：" +
                "1.5 / 2 / 3倍 = 放量 / 明显 / 异常",
        ),
        Rule(
            "买入与接回量能",
            "大盘预估全天成交额须达到近5个完整交易日成交额中位数的" +
                "${IntradayVolumeContext.MARKET_EXPANDING_RATIO}倍；未放量或数据缺失时不抄底、不买入、不接回，" +
                "个股拉升只作反弹观察。大盘放量后仍须通过个股量价、15/30分钟与仓位检查。",
        ),
        Rule(
            "T+1与双周期",
            "交易批次区分今日可卖旧仓与今日买入锁定；卖出建议只用当日可卖股数。15分钟用于执行触发，30分钟用于方向过滤，" +
                "数据不完整或方向反向时仅观察；独立风险减仓按15分钟证据复核，不能据此授权清仓。",
        ),
        Rule(
            "龙头提示",
            "涉及非持仓板块龙头时标注“板块状态参考”，属于非买卖指令，只用于解释同板块持仓的做T节奏。",
        ),
        Rule(
            "重新武装",
            "指数均回到 -${MarketRiskPolicy.REARM_PCT}% 以上，解除市场风险提醒；单指数观察不生成通知",
        ),
        Rule(
            "刷新节奏",
            "每 15 秒刷新一次，工作日 08:30-16:00 请求新行情；板块每 30 分钟、指标选股与持仓分析每 30 分钟一个槽位；" +
                "盘面总结每天 16:00 / 18:00 / 21:00 生成；夜间 18:00-06:00 指标选股失败会每 90 分钟重试，最多 8 次。",
        ),
    )

    fun detailText(dropAlertPct: Double = 5.0): String =
        buildString {
            rules(dropAlertPct).forEach { rule ->
                appendLine("${rule.label}：${rule.text}")
            }
            appendLine()
            append(SERVICE_REMINDER)
        }
}
