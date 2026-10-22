package com.example.stockmonitor.ui

import android.content.Context
import com.example.stockmonitor.R
import com.example.stockmonitor.analysis.PortfolioAnalysis
import com.example.stockmonitor.core.Store
import com.example.stockmonitor.monitor.MonitorService
import com.example.stockmonitor.monitor.Repo

internal fun portfolioBriefCard(context: Context, store: Store): UiKit.Card = UiKit.card(
    context,
    "今日要点",
    if (Repo.portfolioTask.isActive) "进行中…" else "立即分析",
) {
    if (!Repo.portfolioTask.isActive) MonitorService.send(context, MonitorService.ACTION_PORTFOLIO)
}.apply {
    val results = Repo.portfolioResults
    val portfolio = store.portfolio
    val plan = Repo.portfolioSwing?.validatedFor(portfolio)
    // 事件后蓄势等值得盯的线索置顶高亮（醒目色 + 大一号），别埋在个股报告里。
    PortfolioAnalysis.todayBriefHighlights(results).forEach {
        addText(it, 13f, UiKit.color(context, R.color.warn))
    }
    PortfolioAnalysis.todayBrief(results, portfolio, Repo.marketState, plan, Repo.quotes)
        .forEach { addText(it, 12f) }
    if (results.isEmpty()) {
        addText("暂无个股分析结果，最值得操作的股票需等分析完成后判断。", 11.5f)
    } else {
        plan?.capitalIssue?.let { issue -> addText(issue, 11.5f) }
    }
}
