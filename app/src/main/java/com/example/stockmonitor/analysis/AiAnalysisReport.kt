package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.AiAnalysisResult

object AiAnalysisReport {
    fun format(result: AiAnalysisResult, name: String, code: String, asOf: String): String = buildString {
        appendLine("🤖 $name（$code）AI 综合分析")
        appendLine("生成于：$asOf｜数据来源：程序缓存 + 网络资料（如已取得）")
        appendLine()
        appendLine("一、精要摘要")
        appendLine("核心结论：${result.answer}")
        appendLine("操作建议：${result.operationAdvice}")
        appendLine("主要风险：${result.risks.firstOrNull() ?: "暂未识别，仍需结合原始数据核对"}")
        appendLine("可信度：${result.confidence}")
        appendLine()
        appendLine("二、关键依据")
        appendLine("技术面：${result.trend}")
        appendLine("基本面：${result.fundamentals}")
        appendLine("持仓影响：${result.portfolio}")
        appendLine("盘面环境：${result.marketContext}")
        appendLine("市场风险：${result.marketRisk}")
        appendLine("估值：${ReportMarkup.highlightValuationNumbers(result.valuation)}")
        appendLine("竞争力：${result.moat}")
        appendLine()
        appendLine("三、风险与观察")
        appendList("主要风险", result.risks, maxItems = 3)
        appendList("催化因素", result.catalysts, maxItems = 3)
        appendList("观察重点", result.watchItems, maxItems = 3)
        appendList("情景判断", result.scenarios, maxItems = 2)
        appendList("资料来源", result.sources, maxItems = 5)
        appendLine()
        appendLine("⚠️ AI分析只基于本次取得的数据和资料，不构成投资建议；资料缺失时结论可信度会下降。")
    }

    private fun StringBuilder.appendList(title: String, items: List<String>, maxItems: Int) {
        appendLine()
        appendLine(title)
        if (items.isEmpty()) appendLine("· 暂无足够资料")
        items.take(maxItems).forEach { appendLine("· $it") }
    }
}
