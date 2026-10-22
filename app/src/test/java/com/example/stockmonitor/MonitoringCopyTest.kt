package com.example.stockmonitor

import com.example.stockmonitor.ui.MonitoringCopy
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
        assertTrue(text.contains("观察池：只提醒进入“买点已确认”"))
        assertTrue(text.contains("交易时段"))
        assertTrue(text.contains("破位立即提醒"))
        assertTrue(text.contains("同类信号10分钟冷却"))
        assertTrue(text.contains("机会提醒每日最多8条"))
        assertTrue(text.contains("风险升级不受此上限限制"))
        assertTrue(text.contains("当前 0.8%"))
        assertTrue(text.contains("大盘同步 -0.5%"))
        assertTrue(text.contains("-0.8% / -1.5%"))
        assertTrue(text.contains("5分钟 -0.3% / -0.5% / -0.8%"))
        assertTrue(text.contains("近20日同期中位数（至少10日"))
        assertTrue(text.contains("1.5 / 2 / 3倍"))
        assertTrue(text.contains("只解除大盘风险提醒"))
    }
}
