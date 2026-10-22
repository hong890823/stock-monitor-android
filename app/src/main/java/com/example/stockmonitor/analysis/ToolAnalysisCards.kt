package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.AiAnalysisResult
import com.example.stockmonitor.core.GlobalFundamentals

/** 工具页四张分析结果卡片。由 AnalysisService 写入 Repo，ToolsFragment 只读渲染。 */
data class TechnicalCard(
    val result: TechnicalResult,
    val summary: TechnicalSummary,
    val report: String,
)

data class FundamentalCard(
    val name: String,
    val code: String,
    val price: Double,
    val result: FundamentalResult,
    val technical: TechnicalResult?,
    val report: String,
)

data class GlobalCard(
    val data: GlobalFundamentals,
    val technical: TechnicalResult?,
    val report: String,
)

data class AiCard(
    val name: String,
    val code: String,
    val market: String,
    val result: AiAnalysisResult,
    val report: String,
    val title: String,
)
