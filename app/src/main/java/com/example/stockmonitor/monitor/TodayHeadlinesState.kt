package com.example.stockmonitor.monitor

import com.example.stockmonitor.analysis.SelectedHeadline
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class TodayHeadlinesState(
    val items: List<SelectedHeadline> = emptyList(),
    val updatedAtMs: Long = 0L,
    val lastAttemptAtMs: Long = 0L,
    val loading: Boolean = false,
    val error: String? = null,
) {
    fun shouldRefresh(nowMs: Long, force: Boolean = false): Boolean {
        if (loading) return false
        if (force) return true
        if (!inRefreshWindow(nowMs)) return false
        return lastAttemptAtMs <= 0L || nowMs - lastAttemptAtMs >= REFRESH_INTERVAL_MS
    }

    fun displayItems(nowMs: Long): List<SelectedHeadline> {
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }
        val today = formatter.format(nowMs)
        return items.filter { formatter.format(it.item.publishedAtMs) == today }
    }

    companion object {
        const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L

        fun inRefreshWindow(nowMs: Long): Boolean {
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
                timeInMillis = nowMs
            }
            val minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
            return minutes in (8 * 60 + 30)..(22 * 60)
        }
    }
}
