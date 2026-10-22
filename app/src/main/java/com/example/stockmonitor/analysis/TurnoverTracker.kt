package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.Store
import com.example.stockmonitor.core.TurnoverCurve
import org.json.JSONObject
import java.util.Calendar

/**
 * 两市成交额的「同一时刻」对比。
 *
 * 原来用「今天半日累计」直接比「昨天全天总额」，盘中会恒定显示大幅缩水，没有意义。
 * 这里改成同口径比较：
 * - 交易时段内每隔约 15 分钟把当日累计成交额落盘，只保留最近 [MAX_DAYS] 个交易日；
 * - 「昨日同期」优先直接取分时曲线（当天即可用），取不到再退回本地采样；
 * - 全天预估按「昨日同期占昨日全天的比例」推算，比按分钟数线性外推更贴近真实节奏。
 *
 * **比较双方必须落在同一钟表时刻**：15 分钟槽位只决定「多久记一次」，取昨日同期一律用
 * 真实采样时刻 [Snapshot.sampleMinute] 去查，不能用向下取整后的槽位起点。成交额曲线早盘
 * 极陡且全天单调递增，拿实时累计去比槽位起点会把分母系统性压小：实测 09:52 采到的今日
 * 累计配昨日 09:45 同期，全天预估会从 1.4 万亿虚高到 1.8 万亿。服务在槽位中途启动、
 * 或 tick 因网络请求漂移时，采样落得越晚，虚高越明显。
 */
class TurnoverTracker(private val store: Store) {

    /** 同期数据的来源，用于界面提示可信度。 */
    enum class Source { CURVE, LOCAL, NONE }

    data class Snapshot(
        /** 采样时刻（HH:mm），界面标签用；就是真实捕获时间，不是槽位起点。 */
        val sampledAt: String,
        /** 真实采样时刻的当天分钟数，取昨日同期用它对齐。 */
        val sampleMinute: Int,
        /** 今日在该时刻的累计成交额（元） */
        val today: Double,
        /** 昨日同一时刻的累计成交额（元），取不到为 null */
        val previousSameTime: Double?,
        /** 昨日全天成交额（元），取不到为 null */
        val previousFullDay: Double?,
        /** 今日全天预估（元） */
        val predictedFullDay: Double?,
        val source: Source,
    )

    /**
     * 记录一次采样并生成对比结果。
     *
     * @param today 当前两市累计成交额（元）
     * @param previousCurve 外部取到的昨日两市合计曲线，可为 null
     */
    fun sample(today: Double, calendar: Calendar, previousCurve: TurnoverCurve?): Snapshot? {
        if (TradingCalendar.turnoverSlotMinute(calendar) == null) return null
        if (today <= 0) return null

        val sampleMinute = TimeUtils.minuteOfDay(calendar)
        val todayDate = TimeUtils.todayText()
        record(todayDate, sampleMinute, today)

        val local = loadCurve(previousDate(todayDate))
        val curve = when {
            previousCurve != null && !previousCurve.isEmpty -> previousCurve
            local != null && !local.isEmpty -> local
            else -> null
        }
        val source = when {
            previousCurve != null && !previousCurve.isEmpty -> Source.CURVE
            local != null && !local.isEmpty -> Source.LOCAL
            else -> Source.NONE
        }
        return buildSnapshot(today, sampleMinute, curve, source, calendar)
    }

    // ── 本地采样读写 ──

    /** 写入某个采样时刻的累计成交额，并裁剪掉过老的交易日。 */
    fun record(date: String, minuteOfDay: Int, amount: Double) {
        if (amount <= 0) return
        val all = store.turnoverSamples
        val day = all.optJSONObject(date) ?: JSONObject()
        day.put(minuteOfDay.toString(), amount)
        all.put(date, day)
        val dates = all.keys().asSequence().sorted().toMutableList()
        while (dates.size > MAX_DAYS) all.remove(dates.removeAt(0))
        store.turnoverSamples = all
    }

    /** 读取某个交易日已记录的采样，拼成曲线。 */
    fun loadCurve(date: String?): TurnoverCurve? {
        if (date == null) return null
        val day = store.turnoverSamples.optJSONObject(date) ?: return null
        val cumulative = linkedMapOf<Int, Double>()
        day.keys().forEach { key ->
            val minute = key.toIntOrNull() ?: return@forEach
            val amount = day.optDouble(key, 0.0)
            if (amount > 0) cumulative[minute] = amount
        }
        return if (cumulative.isEmpty()) null else TurnoverCurve(date, cumulative)
    }

    /** 最近一个早于今天的采样日期。 */
    fun previousDate(today: String): String? =
        store.turnoverSamples.keys().asSequence().filter { it < today }.maxOrNull()

    /**
     * 用 [date] 当天已落盘的最后一次采样重建快照（只读不写）。
     * 进程重启后恢复「较昨日同期」用——采样时刻取当日最后一条，与服务被杀前的显示对齐。
     */
    fun restoreSnapshot(date: String): Snapshot? {
        val day = store.turnoverSamples.optJSONObject(date) ?: return null
        val lastMinute = day.keys().asSequence().mapNotNull { it.toIntOrNull() }.maxOrNull() ?: return null
        val amount = day.optDouble(lastMinute.toString(), 0.0)
        if (amount <= 0) return null
        val local = loadCurve(previousDate(date))?.takeIf { !it.isEmpty }
        return buildSnapshot(
            today = amount,
            sampleMinute = lastMinute,
            curve = local,
            source = if (local != null) Source.LOCAL else Source.NONE,
        )
    }

    /** 最近已记录交易日的全天成交额（亿元），按时间从旧到新返回。 */
    fun recentFullDayTurnoversYi(beforeDate: String, limit: Int = 5): List<Double> =
        fullDayHistoryYi(store.turnoverSamples, beforeDate, limit)

    /**
     * 当前采样时刻相对最近历史同期中位数的倍数，历史不足 10 天时不返回结果。
     * 历史侧同样按 [sampleMinute] 取数，与当前实时累计对齐。
     */
    fun sameTimeRatio(today: String, sampleMinute: Int, current: Double): Double? {
        val baseline = MarketVolumeBaseline()
        store.turnoverSamples.keys().forEach { date ->
            val curve = loadCurve(date) ?: return@forEach
            baseline.record(date, sampleMinute, curve.at(sampleMinute) ?: 0.0)
        }
        return baseline.ratio(today, sampleMinute, current)
    }

    companion object {
        /**
         * 用真实采样时刻拼快照。做成纯函数是为了能脱离 [Store] 单测「同一时刻对齐」这条不变式。
         */
        internal fun buildSnapshot(
            today: Double,
            sampleMinute: Int,
            curve: TurnoverCurve?,
            source: Source,
            calendar: Calendar = TimeUtils.nowCalendar(),
        ): Snapshot {
            // 分母取昨日在同一分钟的累计，而不是向下取整到 15 分钟槽位起点的那一个。
            val previousSameTime = curve?.at(sampleMinute)?.takeIf { it > 0 }
            // 半日截断的曲线末值不是全天额，不能当预估基数，否则预估会整体偏低。
            val previousFullDay = curve?.takeIf { it.isCompleteDay }?.fullDay?.takeIf { it > 0 }

            return Snapshot(
                sampledAt = TradingCalendar.slotText(sampleMinute),
                sampleMinute = sampleMinute,
                today = today,
                previousSameTime = previousSameTime,
                previousFullDay = previousFullDay,
                predictedFullDay = predict(today, sampleMinute, previousSameTime, previousFullDay, calendar),
                source = source,
            )
        }

        /**
         * 全天预估：有昨日同期时按比例推算；否则退回按分钟数线性外推。
         * 开盘头几分钟和昨日同期占比过低时不出数，避免给出离谱数字。
         */
        private fun predict(
            today: Double,
            sampleMinute: Int,
            previousSameTime: Double?,
            previousFullDay: Double?,
            calendar: Calendar,
        ): Double? {
            if (sampleMinute < OPEN_GUARD_MINUTE) return null
            if (previousSameTime != null && previousFullDay != null &&
                previousSameTime >= previousFullDay * MIN_SHARE
            ) {
                return today / previousSameTime * previousFullDay
            }
            return MarketSummary.predictDailyTurnover(today, calendar).first
        }

        /**
         * 只有采到收盘之后的那一天才算全天：末条采样的分钟数要 ≥ [TurnoverCurve.CLOSE_MINUTE]，
         * 否则当作半日截断留空，免得半日额混进历史把基线和预估一起带偏。
         */
        internal fun fullDayHistoryYi(samples: JSONObject, beforeDate: String, limit: Int): List<Double> =
            samples.keys().asSequence().filter { it < beforeDate }.sorted().toList()
                .takeLast(limit.coerceAtLeast(1)).map { date ->
                    val day = samples.optJSONObject(date) ?: return@map Double.NaN
                    val closeMinute = day.keys().asSequence()
                        .mapNotNull { it.toIntOrNull() }
                        .filter { it >= TurnoverCurve.CLOSE_MINUTE }
                        .maxOrNull() ?: return@map Double.NaN
                    day.optDouble(closeMinute.toString(), Double.NaN)
                        .takeIf { it.isFinite() && it > 0 }?.div(100_000_000.0) ?: Double.NaN
                }

        /** 保留最近 20 个交易日，满足同一时刻成交额基线所需的至少 10 天历史。 */
        const val MAX_DAYS = MarketVolumeBaseline.MAXIMUM_DAYS

        /** 09:40 前不预估，开盘几分钟成交额波动太大。 */
        private const val OPEN_GUARD_MINUTE = 580

        /** 昨日同期至少要占到昨日全天的 2%，否则按比例外推会失真。 */
        private const val MIN_SHARE = 0.02
    }
}
