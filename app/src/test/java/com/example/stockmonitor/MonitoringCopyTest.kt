package com.example.stockmonitor

import com.example.stockmonitor.ui.MonitoringCopy
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringCopyTest {
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
