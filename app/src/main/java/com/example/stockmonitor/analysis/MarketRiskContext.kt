package com.example.stockmonitor.analysis

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class MarketTimingContext(
    val date: String,
    val weekday: String,
    val isFriday: Boolean,
    val isMonthEndWindow: Boolean,
    val isQuarterEndWindow: Boolean,
    val reportSeason: String?,
    val holidayNote: String? = null,
    val withdrawalNote: String? = null,
    val lastTradingDate: String? = null,
) {
    fun aiText(): String = buildString {
        append("日期 $date（$weekday）")
        if (isFriday) append("；周五")
        if (isMonthEndWindow) append("；月末后三个日历日")
        if (isQuarterEndWindow) append("；季度末后三个日历日")
        lastTradingDate?.let { append("；最近交易日 $it") }
        reportSeason?.let { append("；$it") }
        append("。日期因素只作风险提示，不代表必然下跌")
        append("；${holidayNote ?: "节假日数据暂缺"}")
        append("；${withdrawalNote ?: "出金/资金可取规则暂缺"}")
    }

    fun toJson(): JSONObject = JSONObject()
        .put("date", date)
        .put("weekday", weekday)
        .put("is_friday", isFriday)
        .put("is_month_end_window", isMonthEndWindow)
        .put("is_quarter_end_window", isQuarterEndWindow)
        .put("report_season", reportSeason ?: JSONObject.NULL)
        .put("holiday_note", holidayNote ?: "节假日数据暂缺")
        .put("withdrawal_note", withdrawalNote ?: "出金/资金可取规则暂缺")
        .put("last_trading_date", lastTradingDate ?: JSONObject.NULL)
        .put("text", aiText())

    companion object {
        fun from(
            calendar: Calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")),
            tradingDates: List<String> = emptyList(),
        ): MarketTimingContext {
            val day = calendar.get(Calendar.DAY_OF_MONTH)
            val lastDay = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
            val month = calendar.get(Calendar.MONTH) + 1
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
                timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            }.format(calendar.time)
            val weekday = when (calendar.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> "周一"
                Calendar.TUESDAY -> "周二"
                Calendar.WEDNESDAY -> "周三"
                Calendar.THURSDAY -> "周四"
                Calendar.FRIDAY -> "周五"
                Calendar.SATURDAY -> "周六"
                else -> "周日"
            }
            val reportSeason = when (month) {
                4 -> "年报/一季报常见披露窗口"
                8 -> "中报常见披露窗口"
                10 -> "三季报常见披露窗口"
                else -> null
            }
            val normalizedTradingDates = tradingDates
                .map { it.take(10) }
                .filter { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && it <= date }
                .distinct()
                .sorted()
            val lastTradingDate = normalizedTradingDates.lastOrNull()
            val referenceIsRecent = lastTradingDate?.let { daysBetween(it, date) <= 3 } == true
            val monthEndCalendar = calendar.clone() as Calendar
            monthEndCalendar.set(Calendar.DAY_OF_MONTH, lastDay)
            val calendarDaysToMonthEnd = ((monthEndCalendar.timeInMillis - calendar.timeInMillis) /
                (24L * 60L * 60L * 1000L)).coerceAtLeast(0L)
            val lastTradingMonth = lastTradingDate?.substring(0, 7)
            val monthEndWindow = if (lastTradingDate != null) {
                referenceIsRecent && lastTradingMonth == date.substring(0, 7) && calendarDaysToMonthEnd <= 4
            } else {
                day >= lastDay - 2
            }
            val quarterEndWindow = if (lastTradingDate != null) {
                referenceIsRecent && month in listOf(3, 6, 9, 12) &&
                    lastTradingMonth == date.substring(0, 7) && calendarDaysToMonthEnd <= 4
            } else {
                month in listOf(3, 6, 9, 12) && day >= lastDay - 2
            }
            return MarketTimingContext(
                date = date,
                weekday = weekday,
                isFriday = calendar.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY,
                isMonthEndWindow = monthEndWindow,
                isQuarterEndWindow = quarterEndWindow,
                reportSeason = reportSeason,
                lastTradingDate = lastTradingDate,
            )
        }

        private fun daysBetween(start: String, end: String): Long {
            val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
                timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            }
            val startMs = formatter.parse(start)?.time ?: return Long.MAX_VALUE
            val endMs = formatter.parse(end)?.time ?: return Long.MAX_VALUE
            return ((endMs - startMs) / (24L * 60L * 60L * 1000L)).coerceAtLeast(0L)
        }
    }
}

data class NorthboundFlowSummary(
    val latestDate: String,
    val latestNet: Double,
    val fiveDayNet: Double?,
    val twentyDayNet: Double?,
    val source: String,
) {
    val isOutflowFiveDays: Boolean get() = fiveDayNet != null && fiveDayNet < 0.0

    fun aiText(): String {
        val direction = when {
            latestNet < 0 && isOutflowFiveDays -> "最新与近5日均为净流出"
            latestNet > 0 && fiveDayNet != null && fiveDayNet > 0 -> "最新与近5日均为净流入"
            else -> "短期方向分化或接近中性"
        }
        return "北向（截至 $latestDate）：最新净${signed(latestNet)}亿元，近5日${signed(fiveDayNet)}亿元，" +
            "近20日${signed(twentyDayNet)}亿元；$direction。来源：$source"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("latest_date", latestDate)
        .put("latest_net_billion", latestNet)
        .put("five_day_net_billion", fiveDayNet ?: JSONObject.NULL)
        .put("twenty_day_net_billion", twentyDayNet ?: JSONObject.NULL)
        .put("source", source)
        .put("text", aiText())

    private fun signed(value: Double?): String = value?.let { String.format(Locale.CHINA, "%+.2f", it) } ?: "暂缺"

    companion object {
        fun from(points: List<NorthboundFlowPoint>, source: String): NorthboundFlowSummary? {
            val ordered = points.sortedBy { it.date }
            val latest = ordered.lastOrNull() ?: return null
            return NorthboundFlowSummary(
                latestDate = latest.date,
                latestNet = latest.netBillion,
                fiveDayNet = ordered.takeLast(5).takeIf { it.size >= 5 }?.sumOf { it.netBillion },
                twentyDayNet = ordered.takeLast(20).takeIf { it.size >= 20 }?.sumOf { it.netBillion },
                source = source,
            )
        }
    }
}

data class NorthboundFlowPoint(
    val date: String,
    val netBillion: Double,
)

data class TurnoverContext(
    val today: Double?,
    val previousSameTime: Double?,
    val predictedFullDay: Double?,
    val sameTimeRatio: Double? = null,
) {
    val sameTimeChangePct: Double?
        get() = if (today != null && previousSameTime != null && previousSameTime > 0) {
            (today / previousSameTime - 1.0) * 100.0
        } else {
            null
        }

    val isContracting: Boolean
        get() = sameTimeChangePct?.let { it <= -10.0 } == true

    fun aiText(): String = buildString {
        val change = sameTimeChangePct
        append("成交额：今日${money(today)}亿元")
        append("，较昨日同期${change?.let { String.format(Locale.CHINA, "%+.1f%%", it) } ?: "暂缺"}")
        append("，全天预估${money(predictedFullDay)}亿元")
        append("，近20日同期中位数${sameTimeRatio?.let { String.format(Locale.CHINA, "%.2f倍", it) } ?: "暂缺"}")
        if (isContracting) append("；同期量能收缩")
    }

    fun toJson(): JSONObject = JSONObject()
        .put("today_billion", billion(today) ?: JSONObject.NULL)
        .put("previous_same_time_billion", billion(previousSameTime) ?: JSONObject.NULL)
        .put("predicted_full_day_billion", billion(predictedFullDay) ?: JSONObject.NULL)
        .put("same_time_ratio_20d", sameTimeRatio ?: JSONObject.NULL)
        .put("same_time_change_pct", sameTimeChangePct ?: JSONObject.NULL)
        .put("is_contracting", isContracting)
        .put("text", aiText())

    private fun money(value: Double?): String = value?.let { String.format(Locale.CHINA, "%.2f", it / 1e8) } ?: "暂缺"
    private fun billion(value: Double?): Double? = value?.takeIf { it > 0 }?.div(1e8)
}

data class MarketRiskContext(
    val timing: MarketTimingContext,
    val northbound: NorthboundFlowSummary? = null,
    val turnover: TurnoverContext? = null,
    val recentMarketText: String? = null,
    val dataGaps: List<String> = emptyList(),
    val generatedAtMs: Long = 0L,
) {
    fun isFresh(
        today: String = TimeUtils.todayText(),
        nowMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long = 15 * 60 * 1000L,
    ): Boolean = timing.date == today && generatedAtMs > 0 && nowMs - generatedAtMs in 0..maxAgeMs

    fun portfolioNote(): String? {
        val outflow = northbound?.isOutflowFiveDays == true
        val turnoverWeak = turnover?.isContracting == true || turnover?.sameTimeRatio?.let { it <= 0.90 } == true
        if (!outflow || !turnoverWeak) return null
        val evidence = buildList {
            if (outflow) add("北向近5日净流出 ${String.format(Locale.CHINA, "%.2f", northbound?.fiveDayNet ?: 0.0)} 亿元")
            if (turnover?.isContracting == true) {
                add("成交额较昨日同期收缩 ${String.format(Locale.CHINA, "%.1f", -(turnover.sameTimeChangePct ?: 0.0))}%")
            }
            turnover?.sameTimeRatio?.takeIf { it <= 0.90 }?.let {
                add("成交额为近20日同期中位数的 ${String.format(Locale.CHINA, "%.2f", it)} 倍")
            }
        }
        val timing = listOfNotNull(
            timing.isFriday.takeIf { it }?.let { "周五" },
            timing.isMonthEndWindow.takeIf { it }?.let { "月末" },
            timing.isQuarterEndWindow.takeIf { it }?.let { "季度末" },
            timing.reportSeason,
        ).joinToString("、")
        return "${evidence.joinToString("；")}；${if (timing.isBlank()) "当前时点" else "临近$timing"}，降低追涨/补仓优先级；日期因素仅作风险提示。"
    }

    fun aiText(): String = buildString {
        append(timing.aiText())
        append("。")
        append(northbound?.aiText() ?: "北向资金数据暂缺。")
        append(" ")
        append(turnover?.aiText() ?: "成交额快照暂缺。")
        recentMarketText?.takeIf { it.isNotBlank() }?.let { append(" 近期市场：$it") }
        if (dataGaps.isNotEmpty()) append(" 数据缺口：${dataGaps.joinToString("；")}")
    }

    fun toJson(): JSONObject = JSONObject()
        .put("timing", timing.toJson())
        .put("northbound", northbound?.toJson() ?: JSONObject.NULL)
        .put("turnover", turnover?.toJson() ?: JSONObject.NULL)
        .put("recent_market", recentMarketText ?: JSONObject.NULL)
        .put("data_gaps", JSONArray(dataGaps))
        .put("generated_at_ms", generatedAtMs.takeIf { it > 0 } ?: JSONObject.NULL)
        .put("generated_at", generatedAtMs.takeIf { it > 0 }?.let { TimeUtils.dateTimeText(Date(it)) } ?: JSONObject.NULL)
        .put("text", aiText())
}
