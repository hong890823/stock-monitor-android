package com.example.stockmonitor.analysis

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 时间与交易时段判断，规则与 macOS 版一致。 */
object TimeUtils {
    private val shanghaiTimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    fun nowCalendar(): Calendar = Calendar.getInstance(shanghaiTimeZone)

    fun nowText(): String = format("yyyy-MM-dd HH:mm", Date())

    /** 只到分钟的短时间，通知栏用。 */
    fun clockText(): String = format("HH:mm", Date())

    fun todayText(): String = format("yyyy-MM-dd", Date())

    fun dateText(calendar: Calendar): String = format("yyyy-MM-dd", calendar.time)

    fun dateTimeText(date: Date): String = format("yyyy-MM-dd HH:mm:ss", date)

    fun parseDateTime(value: String): Date? = runCatching {
        formatter("yyyy-MM-dd HH:mm:ss").parse(value)
    }.getOrNull()

    fun hour(calendar: Calendar = nowCalendar()): Int = calendar.get(Calendar.HOUR_OF_DAY)

    fun minuteOfDay(calendar: Calendar = nowCalendar()): Int =
        calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

    fun isWeekend(calendar: Calendar = nowCalendar()): Boolean =
        calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY ||
            calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY

    private fun format(pattern: String, date: Date): String =
        formatter(pattern).format(date)

    private fun formatter(pattern: String): SimpleDateFormat =
        SimpleDateFormat(pattern, Locale.CHINA).apply { timeZone = shanghaiTimeZone }
}

/**
 * A 股交易时段：
 * - 行情刷新窗口：工作日 08:30 - 16:00（开盘前 1 小时 ~ 休市后 1 小时）
 * - 集合竞价/连续竞价：09:30-11:30、13:00-15:00
 * - 指标选股槽位：09:30-11:30 与 13:00-16:00 每 30 分钟一个槽位
 */
/**
 * 一轮 tick 要做的动作，对应 macOS 版 _fetch_data 的两个分支：
 * 交易时段拉行情并执行全部定时任务；非交易时段跳过行情，但定时任务照跑。
 */
enum class TickPlan {
    FETCH_AND_TASKS,
    OFF_HOURS_TASKS,
}

object TradingCalendar {

    fun inRefreshWindow(calendar: Calendar = TimeUtils.nowCalendar()): Boolean {
        if (TimeUtils.isWeekend(calendar)) return false
        val minutes = TimeUtils.minuteOfDay(calendar)
        return minutes in (8 * 60 + 30)..(16 * 60)
    }

    /**
     * 与 macOS 版一致：首次加载不受时间限制，手动刷新（force）也不受限制；
     * 其余情况只在工作日 08:30-16:00 真正请求行情。
     */
    fun tickPlan(
        firstLoadDone: Boolean,
        force: Boolean,
        calendar: Calendar = TimeUtils.nowCalendar(),
    ): TickPlan = if (force || !firstLoadDone || inRefreshWindow(calendar)) {
        TickPlan.FETCH_AND_TASKS
    } else {
        TickPlan.OFF_HOURS_TASKS
    }

    fun isMarketOpen(calendar: Calendar = TimeUtils.nowCalendar()): Boolean {
        if (TimeUtils.isWeekend(calendar)) return false
        val minutes = TimeUtils.minuteOfDay(calendar)
        return minutes in 570..690 || minutes in 780..900
    }

    /** 跌幅预警只在盘中推送，避免非交易时段用旧数据误报。 */
    fun inMarketHoursForAlert(calendar: Calendar = TimeUtils.nowCalendar()): Boolean = isMarketOpen(calendar)

    fun tradingMinutesElapsed(calendar: Calendar = TimeUtils.nowCalendar()): Int {
        val minutes = TimeUtils.minuteOfDay(calendar)
        return when {
            minutes < 570 -> 0
            minutes <= 690 -> minutes - 570
            minutes < 780 -> 120
            minutes <= 900 -> 120 + (minutes - 780)
            else -> 240
        }
    }

    /**
     * 成交额采样槽位：交易时段内向下取整到 15 分钟，返回当天分钟数；非交易时段返回 null。
     */
    fun turnoverSlotMinute(calendar: Calendar = TimeUtils.nowCalendar()): Int? {
        if (!isMarketOpen(calendar)) return null
        return (TimeUtils.minuteOfDay(calendar) / 15) * 15
    }

    /** 把当天分钟数格式化成 HH:mm。 */
    fun slotText(slotMinute: Int): String =
        String.format(Locale.CHINA, "%02d:%02d", slotMinute / 60, slotMinute % 60)

    /** 盘中半小时槽位；非交易时段返回 null。 */
    fun intradaySlot(calendar: Calendar = TimeUtils.nowCalendar()): String? {
        if (TimeUtils.isWeekend(calendar)) return null
        val minutes = TimeUtils.minuteOfDay(calendar)
        val slotMinute = when {
            minutes in 570 until 690 -> 570 + ((minutes - 570) / 30) * 30
            minutes in 780..960 -> 780 + ((minutes - 780) / 30) * 30
            else -> return null
        }
        return "${TimeUtils.todayText()}-${String.format(Locale.CHINA, "%04d", slotMinute)}"
    }
}

/**
 * 指标选股夜间调度，对应 indicator_refresh.py：
 * 18:00 至次日 06:00 为一个窗口，成功一次即停；失败后 90 分钟重试，最多 8 次。
 */
class IndicatorRefreshScheduler(private val store: com.example.stockmonitor.core.Store) {

    private fun state(): MutableMap<String, Any> {
        val json = store.indicatorState
        val map = mutableMapOf<String, Any>()
        json.optString("window").takeIf { it.isNotBlank() }?.let { map["window"] = it }
        json.optString("success_at").takeIf { it.isNotBlank() }?.let { map["success_at"] = it }
        json.optString("next_attempt_at").takeIf { it.isNotBlank() }?.let { map["next_attempt_at"] = it }
        json.optString("last_error").takeIf { it.isNotBlank() }?.let { map["last_error"] = it }
        if (json.has("attempt_count")) map["attempt_count"] = json.optInt("attempt_count", 0)
        return map
    }

    private fun save(map: Map<String, Any>) {
        val json = org.json.JSONObject()
        map.forEach { (key, value) -> runCatching { json.put(key, value) } }
        store.indicatorState = json
    }

    private fun windowDate(calendar: Calendar): String? {
        val hour = TimeUtils.hour(calendar)
        return when {
            hour >= 18 -> TimeUtils.dateText(calendar)
            hour < 6 -> {
                val copy = calendar.clone() as Calendar
                copy.add(Calendar.DAY_OF_YEAR, -1)
                TimeUtils.dateText(copy)
            }
            else -> null
        }
    }

    private fun syncWindow(map: MutableMap<String, Any>, window: String) {
        if (map["window"] != window) {
            map["window"] = window
            map.remove("success_at")
            map.remove("next_attempt_at")
            map.remove("last_error")
            map["attempt_count"] = 0
        }
    }

    /** 是否在夜间调度窗口（18:00–次日 06:00）内；白天不打扰扫描状态。 */
    fun inWindow(calendar: Calendar = TimeUtils.nowCalendar()): Boolean = windowDate(calendar) != null

    fun canAttempt(calendar: Calendar = TimeUtils.nowCalendar()): Boolean {
        val window = windowDate(calendar) ?: return false
        val map = state()
        syncWindow(map, window)
        save(map)
        val attemptCount = map["attempt_count"] as? Int ?: 0
        if (map["success_at"] != null || attemptCount >= MAX_ATTEMPTS) return false
        val next = map["next_attempt_at"] as? String ?: return true
        return !calendar.time.before(parse(next) ?: return true)
    }

    fun beginAttempt(calendar: Calendar = TimeUtils.nowCalendar()) {
        val map = state()
        syncWindow(map, windowDate(calendar) ?: return)
        map["attempt_count"] = ((map["attempt_count"] as? Int) ?: 0) + 1
        map.remove("next_attempt_at")
        map.remove("last_error")
        save(map)
    }

    fun recordSuccess(calendar: Calendar = TimeUtils.nowCalendar()) {
        val map = state()
        map["window"] = windowDate(calendar) ?: TimeUtils.todayText()
        map["success_at"] = TimeUtils.dateTimeText(calendar.time)
        map.remove("next_attempt_at")
        map.remove("last_error")
        save(map)
    }

    fun recordFailure(error: String, calendar: Calendar = TimeUtils.nowCalendar()) {
        val map = state()
        map["window"] = windowDate(calendar) ?: TimeUtils.todayText()
        map["last_error"] = error.trim().take(160).ifBlank { "未知错误" }
        val attemptCount = map["attempt_count"] as? Int ?: 0
        if (attemptCount < MAX_ATTEMPTS) {
            val retry = (calendar.clone() as Calendar).apply { add(Calendar.MINUTE, 90) }
            map["next_attempt_at"] = TimeUtils.dateTimeText(retry.time)
        } else {
            map.remove("next_attempt_at")
        }
        save(map)
    }

    fun lastSuccessAt(): String? = state()["success_at"] as? String

    fun lastError(): String? = state()["last_error"] as? String

    fun status(calendar: Calendar = TimeUtils.nowCalendar()): String {
        val window = windowDate(calendar) ?: return "日间暂停，等待今晚 18:00"
        val map = state()
        syncWindow(map, window)
        save(map)
        if (map["success_at"] != null) return "本窗口已成功刷新"
        val attemptCount = map["attempt_count"] as? Int ?: 0
        if (attemptCount >= MAX_ATTEMPTS) return "本窗口已尝试 $MAX_ATTEMPTS 次，等待下一晚"
        val next = map["next_attempt_at"] as? String
        if (next != null) {
            val nextDate = parse(next)
            if (nextDate != null && calendar.time.before(nextDate)) {
                val text = SimpleDateFormat("HH:mm", Locale.CHINA).apply {
                    timeZone = TimeZone.getTimeZone("Asia/Shanghai")
                }.format(nextDate)
                return "上次失败，$text 重试（$attemptCount/$MAX_ATTEMPTS）"
            }
        }
        return if (attemptCount > 0) "等待重试（$attemptCount/$MAX_ATTEMPTS）" else "等待本窗口刷新"
    }

    private fun parse(value: String): Date? = TimeUtils.parseDateTime(value)

    companion object {
        const val MAX_ATTEMPTS = 8
    }
}
