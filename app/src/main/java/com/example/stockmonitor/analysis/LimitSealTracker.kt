package com.example.stockmonitor.analysis

import java.util.Locale

/** 封板 / 炸板提醒的阈值。 */
object LimitSealPolicy {

    /** 每个代码每天最多报几次封板 / 炸板，防反复开板刷屏。 */
    const val MAX_SEAL_PER_DAY = 3
    const val MAX_BREAK_PER_DAY = 3

    /** 封单金额（元）到这个量级文案才报具体数字，太小的封单报了也没参考价值。 */
    const val MIN_SEAL_AMOUNT = 1e7
}

/**
 * 持仓 / 自选的封板与炸板提醒。
 *
 * 封板（触涨停价）是持仓事件里最值得立刻知道的一件：封单强度决定敢不敢拿、
 * 炸板风险有多大；此前封板/炸板只用于板块龙头的做T闸门与页面展示，持仓封板
 * 本身从不推送，只能靠盯盘发现。炸板回落同理——它把「还封着」的假设打掉，
 * 冲高回落的做T/减仓口径随即恢复适用。
 *
 * 触发看跃迁：封板↔未封各报一次，反复开板受日限约束。封单取行情买一量×涨停价，
 * 数据缺失时只报封板事实不编数字。分层约束：盘中窗口提示，不改趋势判断。
 */
class LimitSealTracker {

    enum class Kind { SEAL, BREAK }

    /**
     * 一轮检测输入。[buy1Volume] 是买一挂单量（股），配合涨停价折算封单金额；
     * 行情缓存恢复等场景拿不到就传 null。
     */
    data class Sample(
        val code: String,
        val name: String,
        val price: Double,
        val prevClose: Double,
        val limitPct: Double,
        val buy1Volume: Double? = null,
        val timestamp: Long = 0L,
        val held: Boolean = false,
    )

    data class Event(
        val code: String,
        val name: String,
        val kind: Kind,
        val title: String,
        val body: String,
        val vibrate: Boolean,
        val held: Boolean,
    )

    private data class State(
        var sealed: Boolean = false,
        var sealCount: Int = 0,
        var breakCount: Int = 0,
        var sealedPrice: Double = 0.0,
    )

    private var tradingDay: String? = null
    private val states = mutableMapOf<String, State>()

    fun update(samples: List<Sample>, day: String): List<Event> {
        if (tradingDay != day) {
            states.clear()
            tradingDay = day
        }
        return samples.mapNotNull { evaluate(it) }
    }

    /** 该代码当前是否封在涨停上。 */
    fun isSealed(code: String): Boolean = states[code]?.sealed == true

    fun reset() {
        tradingDay = null
        states.clear()
    }

    private fun evaluate(sample: Sample): Event? {
        if (!sample.price.isFinite() || sample.price <= 0) return null
        if (!sample.prevClose.isFinite() || sample.prevClose <= 0) return null
        if (!sample.limitPct.isFinite() || sample.limitPct <= 0) return null
        val state = states.getOrPut(sample.code) { State() }
        val limitPrice = Math.round(sample.prevClose * (1 + sample.limitPct / 100.0) * 100) / 100.0
        val sealedNow = sample.price >= limitPrice - 0.005
        return when {
            sealedNow && !state.sealed -> {
                state.sealed = true
                state.sealedPrice = limitPrice
                if (state.sealCount >= LimitSealPolicy.MAX_SEAL_PER_DAY) return null
                state.sealCount += 1
                sealEvent(sample, limitPrice)
            }
            !sealedNow && state.sealed -> {
                state.sealed = false
                if (state.breakCount >= LimitSealPolicy.MAX_BREAK_PER_DAY) return null
                state.breakCount += 1
                breakEvent(sample, state.sealedPrice)
            }
            else -> null
        }
    }

    private fun sealEvent(sample: Sample, limitPrice: Double): Event {
        val changePct = (sample.price / sample.prevClose - 1) * 100
        val sealAmount = sample.buy1Volume?.takeIf { it.isFinite() && it > 0 }?.let { it * limitPrice }
        val sealText = if (sealAmount != null && sealAmount >= LimitSealPolicy.MIN_SEAL_AMOUNT) {
            String.format(Locale.CHINA, "，封单约 %.1f 亿", sealAmount / 1e8)
        } else {
            ""
        }
        return Event(
            code = sample.code,
            name = sample.name,
            kind = Kind.SEAL,
            title = "🔒 ${sample.name}·封板",
            body = "现价 ${fmtPrice(limitPrice)} 触及涨停（${fmtPct(changePct)}）$sealText｜" +
                (if (sample.held) {
                    "封住则持仓待明日，冲高做T暂停；炸板回落再按做T/减仓口径处理。"
                } else {
                    "封住别追板排单；炸板回落再观察。"
                }) + SCOPE_NOTE,
            vibrate = true,
            held = sample.held,
        )
    }

    private fun breakEvent(sample: Sample, sealedPrice: Double): Event {
        val changePct = (sample.price / sample.prevClose - 1) * 100
        return Event(
            code = sample.code,
            name = sample.name,
            kind = Kind.BREAK,
            title = "🔓 ${sample.name}·炸板回落",
            body = "自涨停 ${fmtPrice(sealedPrice)} 回落至 ${fmtPrice(sample.price)}（${fmtPct(changePct)}）｜" +
                (if (sample.held) {
                    "封板打开，冲高回落的做T/减仓口径恢复适用，别当还封着。"
                } else {
                    "封板打开，回封前别追。"
                }) + SCOPE_NOTE,
            vibrate = sample.held,
            held = sample.held,
        )
    }

    private fun fmtPrice(value: Double): String =
        if (value >= 100) String.format(Locale.CHINA, "%.0f", value)
        else String.format(Locale.CHINA, "%.2f", value)

    private fun fmtPct(value: Double): String = String.format(Locale.CHINA, "%+.1f%%", value)

    private companion object {
        const val SCOPE_NOTE = "（仅为盘中窗口，不改趋势判断）"
    }
}
