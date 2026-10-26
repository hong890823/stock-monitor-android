package com.example.stockmonitor

import com.example.stockmonitor.ui.MonitoringCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MonitoringCopyTest {
    @Test
    fun secondaryDetailsCombineRulesAndServiceReminder() {
        val method =
            MonitoringCopy::class.java.methods.singleOrNull {
                it.name == "detailText" && it.parameterCount == 1
            }
        assertTrue("监控说明应提供二级弹窗文案", method != null)

        val text = method!!.invoke(MonitoringCopy, 0.8) as String
        MonitoringCopy.rules(0.8).forEach { rule ->
            assertTrue(text.contains("${rule.label}：${rule.text}"))
        }
        assertTrue(text.contains("划掉最近任务卡片会立即停止后台监控"))
        assertTrue(text.contains(String.format(Locale.CHINA, "当前 %.1f%%", 0.8)))
    }

    @Test
    fun compactRulesDescribeActualAlertScopeAndLimits() {
        val text = MonitoringCopy.rules(0.8).joinToString("\n") { "${it.label}：${it.text}" }
        assertTrue(text.contains("持仓：跌幅、破位、买卖点和做T"))
        assertTrue(text.contains("自选股和观察池：只提醒“买点已确认”"))
        assertTrue(text.contains("不推送反弹、封板、炸板或突破风险"))
        assertTrue(text.contains("交易时段"))
        assertTrue(text.contains("破位立即提醒"))
        assertTrue(text.contains("同类信号10分钟冷却"))
        assertTrue(text.contains("机会提醒每日最多8条"))
        assertTrue(text.contains("风险升级不受此上限限制"))
        assertTrue(text.contains("当前 0.8%"))
        assertTrue(text.contains("已完成、不重叠的5分钟K线"))
        assertTrue(text.contains("单指数连续两根 -0.3% 只观察，不推送"))
        assertTrue(text.contains("两指数同窗 -0.5% 且市场宽度偏弱"))
        assertTrue(text.contains("多指数 -0.8% 后下一根继续恶化升级系统性风险"))
        assertTrue(text.contains("按近期5分钟波动率归一"))
        assertTrue(text.contains("放量后下一根未止跌或未收复中点"))
        assertTrue(text.contains("近20日同期中位数（至少10日"))
        assertTrue(text.contains("1.5 / 2 / 3倍"))
        assertTrue(text.contains("解除市场风险提醒"))
    }

    @Test
    fun refreshScheduleIsTheLastMonitoringRule() {
        val rule = MonitoringCopy.rules(0.8).last()

        assertEquals("刷新节奏", rule.label)
        assertTrue(rule.text.contains("每 15 秒刷新一次"))
        assertTrue(rule.text.contains("工作日 08:30-16:00 请求新行情"))
        assertTrue(rule.text.contains("盘面总结每天 16:00 / 18:00 / 21:00 生成"))
        assertTrue(rule.text.contains("每 90 分钟重试，最多 8 次"))
    }
}
