package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.AiAnalysisResult

object AiAnalysisReport {
    fun title(result: AiAnalysisResult, name: String, code: String): String {
        val scopeLabel = when (result.analysisScope.lowercase()) {
            "stock" -> "个股 AI 研究分析"
            "sector" -> "板块研究 AI 分析"
            "market" -> "市场研究 AI 分析"
            "strategy" -> "策略研究 AI 分析"
            else -> "AI 研究分析"
        }
        val subject = buildList {
            name.trim().takeIf { it.isNotBlank() }?.let(::add)
            code.trim().takeIf { it.isNotBlank() }?.let { add("（$it）") }
        }.joinToString("")
        return "🤖 $scopeLabel${if (subject.isEmpty()) "" else "｜$subject"}"
    }

    fun format(result: AiAnalysisResult, name: String, code: String, asOf: String): String = buildString {
        appendLine(title(result, name, code))
        val sourceLine = if (result.sources.isEmpty()) {
            "数据来源：本次工具返回数据；未提供可追溯外部来源"
        } else {
            "数据来源：${result.sources.take(2).joinToString("、")}"
        }
        appendLine("生成于：$asOf｜$sourceLine")
        appendLine()
        appendLine("一、精要摘要")
        appendLine("核心结论：${result.answer}")
        appendValue("操作建议", result.operationAdvice)
        result.risks.firstOrNull()?.let { appendLine("主要风险：$it") }
        appendValue("可信度", result.confidence)
        val hiddenStockFields = if (result.analysisScope.lowercase() == "stock") {
            emptySet()
        } else {
            setOf("技术面", "基本面", "持仓影响", "估值", "竞争力")
        }
        val evidence = listOf(
            "技术面" to result.trend,
            "基本面" to result.fundamentals,
            "持仓影响" to result.portfolio,
            "板块环境" to result.sectorContext,
            "盘面环境" to result.marketContext,
            "市场风险" to result.marketRisk,
            "估值" to ReportMarkup.highlightValuationNumbers(result.valuation),
            "竞争力" to result.moat,
        ).filter { it.first !in hiddenStockFields && it.second.isNotBlank() && it.second != "不适用" }
        if (evidence.isNotEmpty()) {
            appendLine()
            appendLine("二、关键依据")
            evidence.forEach { (label, value) -> appendLine("$label：$value") }
        }
        val lists = listOf(
            "主要风险" to result.risks,
            "催化因素" to result.catalysts,
            "观察重点" to result.watchItems,
            "情景判断" to result.scenarios,
        ).filter { it.second.isNotEmpty() }
        if (lists.isNotEmpty()) {
            appendLine()
            appendLine("三、风险与观察")
            lists.forEach { (title, items) -> appendList(title, items, maxItems = 3) }
        }
        if (result.sources.isNotEmpty()) {
            appendLine()
            appendLine("四、资料来源")
            result.sources.take(5).forEach { appendLine("· $it") }
        }
        appendLine()
        appendLine("⚠️ AI分析只基于本次取得的数据和资料，不构成投资建议；资料缺失时结论可信度会下降。")
    }

    private fun StringBuilder.appendValue(label: String, value: String) {
        if (value.isNotBlank() && value != "不适用") appendLine("$label：$value")
    }

    private fun StringBuilder.appendList(title: String, items: List<String>, maxItems: Int) {
        appendLine()
        appendLine(title)
        items.take(maxItems).forEach { appendLine("· $it") }
    }
}
