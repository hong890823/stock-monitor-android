package com.example.stockmonitor.ui

import android.content.Context
import com.example.stockmonitor.R
import com.example.stockmonitor.analysis.DayPnl
import com.example.stockmonitor.analysis.PortfolioAnalysis
import com.example.stockmonitor.analysis.PortfolioSwing
import com.example.stockmonitor.analysis.TimeUtils
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
    PortfolioAnalysis.todayBrief(
        results = results,
        portfolio = portfolio,
        market = Repo.marketState,
        swingPlan = plan,
        quotes = Repo.quotes,
        tradeBook = store.tradeBook,
        todayAcquisitions = DayPnl.acquisitions(store.tradeBook, TimeUtils.todayText()),
        technicalTrends = Repo.technicalTrendSnapshots,
    )
        .forEach { addText(it, 12f) }
    if (results.isEmpty()) {
        // 等待态文案由 todayBrief 统一给出（账户盈亏照算 + 一句「等分析」），不再另加重复提示。
        if (Repo.portfolioTask.phase == com.example.stockmonitor.monitor.TaskPhase.FAILURE) {
            addText("上次分析失败：${Repo.portfolioTask.displayText}", 11.5f, UiKit.color(context, R.color.warn))
        }
    } else {
        plan?.capitalIssue?.let { issue -> addText(issue, 11.5f) }
        if (plan?.focusedRows?.isNotEmpty() == true) {
            addText("本轮重点改善 ${plan.focusedRows.size} 只持仓（最多${PortfolioSwing.MAX_FOCUS_POSITIONS}只）", 11.5f)
        }
    }
}
