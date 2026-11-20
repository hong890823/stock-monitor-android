package com.example.stockmonitor.analysis

import java.util.Locale

/**
 * 首板前/首板日的启动突破观察（观察级，不产生买入结论）。
 *
 * 判定链：现价站上前 20 个交易日收盘平台上沿 → 当日涨幅 >= 3% → 量比 >= 1.5 →
 * 动态 MA5 乖离 <= 8% → 连续站稳平台 15 分钟。涨停状态不累计站稳时间，
 * 跌破平台立即重置。同一股票同一交易日最多一次。
 */
class LaunchDiscoveryTracker {
    data class Sample(
        val code: String,
        val name: String,
        val tradingDay: String,
        val price: Double,
        val previousClose: Double,
        val platform: Double,
        val volumeRatio: Double?,
        val biasFromMa5Pct: Double?,
        val timestamp: Long,
        val limitUp: Boolean,
    )

    data class Alert(
        val code: String,
        val name: String,
        val title: String,
        val body: String,
        val platform: Double,
        val price: Double,
    )

    private data class State(
        val tradingDay: String,
        val firstHitAtMs: Long,
        var alerted: Boolean = false,
    )

    private val states = mutableMapOf<String, State>()

    fun update(sample: Sample): Alert? {
        if (sample.timestamp <= 0L ||
            listOf(sample.price, sample.previousClose, sample.platform).any { !it.isFinite() || it <= 0.0 }
        ) {
            states.remove(sample.code)
            return null
        }
        val state = states[sample.code]?.takeIf { it.tradingDay == sample.tradingDay }
        // 封板时无法确认“可买入的站稳”，但已累计的时间保留，炸板后继续计时。
        if (sample.limitUp) return null
        if (sample.price < sample.platform) {
            states.remove(sample.code)
            return null
        }
        val current = state ?: State(sample.tradingDay, sample.timestamp).also { states[sample.code] = it }
        if (current.alerted) return null

        val dayChangePct = (sample.price / sample.previousClose - 1.0) * 100.0
        val volumeRatio = sample.volumeRatio?.takeIf { it.isFinite() }
        val bias = sample.biasFromMa5Pct?.takeIf { it.isFinite() }
        if (dayChangePct < MIN_DAY_CHANGE_PCT) return null
        if (volumeRatio == null || volumeRatio < MIN_VOLUME_RATIO) return null
        if (bias != null && bias > MAX_MA5_BIAS_PCT) return null
        if (sample.timestamp - current.firstHitAtMs < HOLD_CONFIRM_MS) return null

        current.alerted = true
        return Alert(
            code = sample.code,
            name = sample.name,
            title = "启动突破观察",
            body = String.format(
                Locale.CHINA,
                "%s｜站上20日平台 %.2f 并持续15分钟，现价 %.2f（%+.2f%%），量比 %s，MA5乖离 %s；跌破 %.2f 视为失效，观察级提示，不等同买入确认。",
                sample.name,
                sample.platform,
                sample.price,
                dayChangePct,
                String.format(Locale.CHINA, "%.1f", volumeRatio),
                bias?.let { String.format(Locale.CHINA, "%.1f%%", it) } ?: "暂缺",
                sample.platform,
            ),
            platform = sample.platform,
            price = sample.price,
        )
    }

    /** 推送预算不足时回滚“已提醒”，下一次扫描可以重试。 */
    fun rearm(code: String) {
        states[code]?.alerted = false
    }

    fun clear() = states.clear()

    companion object {
        const val MIN_DAY_CHANGE_PCT = 3.0
        const val MIN_VOLUME_RATIO = 1.5
        const val MAX_MA5_BIAS_PCT = 8.0
        const val HOLD_CONFIRM_MS = 15 * 60 * 1000L
    }
}
