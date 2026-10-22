package com.example.stockmonitor.analysis

/** 标签色调，只表达性质：红＝资金进场确认，橙＝需要警惕，灰＝中性提示。 */
enum class Tone { UP, CAUTION, NEUTRAL }

/**
 * 量价配合信号。
 *
 * 量比单独看是二义的：同样「量比 2 倍」，在低位超跌时更可能是资金进场抄底，
 * 在已经涨起来的高位上则更可能是主力借量出货，所以必须配当日涨跌方向、价格位置一起看：
 *
 * 上涨侧——「放量上涨」在上涨初期与中继是确认，在上涨末期是风险；
 * 「放量滞涨」高位是出货、低位贴着区间低点磨是换手、低位但已经从低点弹起来是遇阻；
 * 「缩量上涨」途中是惜售（持有信号而非买点），
 * 低位是无量反弹（不是吸筹），高位是控盘加速赶顶。
 *
 * 下跌侧——四种组合都不是买点，只提示要不要继续盯；再按跌幅分「温和 / 急跌」两档：
 * 跌到 3% 以上时 A 股多半已经接近跌停，这时候「缩量」的含义会反过来
 * ——不是卖压衰竭，而是封死卖不掉、抛压一点没释放。
 */
enum class VolumePriceSignal(val label: String, val tone: Tone, val icon: String) {

    // ── 上涨侧 ──

    /** 放量上涨：位置不高，视为资金进场确认。 */
    EXPAND_UP("放量上涨", Tone.UP, "🔥"),

    /** 高位放量上涨：区间高位 + 累计涨幅已大，同样的放量上涨更可能是情绪高潮。 */
    EXPAND_UP_HIGH("高位放量上涨", Tone.CAUTION, "⚠️"),

    /** 放量滞涨（中位 / 位置未知）：有量但价格推不动，位置不明确时不替用户判断方向。 */
    EXPAND_FLAT("放量滞涨", Tone.NEUTRAL, ""),

    /**
     * 低位放量滞涨：价格在近 60 日区间下 1/3、且还没从区间低点反弹起来
     * （反弹 < [VolumePrice.REBOUND_MIN]），有量推不动多为底部换手、吸筹初期
     * （是否真有人接还要看价格能否确认）。标签自带位置，避免和末尾那段上涨后的「放量滞涨」混为一谈。
     */
    EXPAND_FLAT_LOW("低位放量滞涨", Tone.NEUTRAL, ""),

    /**
     * 反弹遇阻滞涨：同样是区间低位，但已经从低点弹起来一段（≥ [VolumePrice.REBOUND_MIN]，
     * 多数时候就贴在 20 日压力下面）。这时候放量推不动更像冲高遇阻、等突破确认，
     * 不能当底部吸筹讲。
     */
    EXPAND_FLAT_REBOUND("反弹遇阻滞涨", Tone.NEUTRAL, ""),

    /** 高位放量滞涨：高位有量却涨不动，通常是承接不住、借量出货。 */
    EXPAND_FLAT_HIGH("高位放量滞涨", Tone.CAUTION, "⚠️"),

    /** 缩量上涨：量减价升，筹码惜售，是「继续持有」信号，不是买点。 */
    SHRINK_UP("缩量上涨", Tone.NEUTRAL, ""),

    /** 缩量弱反弹（低位）：长期下跌后的缩量上涨多是无量反弹，抛压只是暂时枯竭。 */
    SHRINK_UP_LOW("缩量弱反弹", Tone.NEUTRAL, ""),

    /** 缩量加速（高位）：高度控盘的加速赶顶，行情最后一段，之后要防放量滞涨。 */
    SHRINK_UP_HIGH("缩量加速", Tone.CAUTION, "⚠️"),

    // ── 下跌侧 ──

    /** 放量下跌（温和）：多空分歧放大、有人在接；低位偏承接、高位偏出货，标签本身不区分位置。 */
    EXPAND_DOWN("放量下跌", Tone.NEUTRAL, ""),

    /** 放量急跌：恐慌盘宣泄、跌停被反复打开过，当天仍不能抄，但最值得盯次日确认。 */
    EXPAND_CRASH("放量急跌", Tone.CAUTION, "⚠️"),

    /** 缩量阴跌（温和）：没人接，趋势没走完，通常要继续磨到地量。 */
    SHRINK_DOWN("缩量阴跌", Tone.NEUTRAL, ""),

    /** 缩量急跌：大跌且无量，A 股里多为跌停封死，抛压未释放，基本可以先划掉。 */
    SHRINK_CRASH("缩量急跌", Tone.CAUTION, "⚠️"),

    /** 量能接近近期均值，或量比 / 涨跌幅缺失。 */
    NEUTRAL("", Tone.NEUTRAL, ""),
}

object VolumePrice {

    /**
     * 平盘带：涨跌幅落在 ±0.5% 内视为「价格没动」。
     * 放量 + 价格没动就是滞涨，所以这一档是「放量滞涨」的判据，同样用于报告里的「窄幅」判断。
     */
    const val FLAT_PCT = 0.5

    /**
     * 跌幅到这个量级就算「急跌」：主板（±10% 限制）多半已接近跌停，量比的含义会翻转。
     * 创业板 / 科创板是 ±20% 限制，3% 只算普通回落，这一档对它们偏敏感。
     */
    private const val CRASH_PCT = 3.0

    /**
     * 「低位」里再分两档的门槛：从区间低点反弹到这个幅度以上，就不再按「底部换手」讲。
     * 10% 的取法：贴着区间低点磨底时现价一般离低点只有几个点，
     * 而一次像样的反弹（例如涨停 + 惯性）都会超过这个数，所以能干净地把两种状态分开。
     */
    const val REBOUND_MIN = 0.10

    /**
     * 量价判定。[context] 传 null 表示拿不到日线（位置未知），退回不带位置的保守文案。
     */
    fun of(volumeRatio: Double?, changePct: Double, context: PriceContext? = null): VolumePriceSignal {
        if (volumeRatio == null || changePct.isNaN()) return VolumePriceSignal.NEUTRAL
        val location = context?.location
        val rebound = context?.reboundFromLow
        val flat = kotlin.math.abs(changePct) <= FLAT_PCT
        val crash = changePct <= -CRASH_PCT
        return when {
            volumeRatio >= VolumeRatio.EXPAND_THRESHOLD -> when {
                crash -> VolumePriceSignal.EXPAND_CRASH
                // 高位＝借量出货；低位再分「还在底部磨」（换手）与「已从低点反弹」（遇阻）；
                // 中位与位置未知时不替用户判断方向。
                flat -> when (location) {
                    PriceLocation.HIGH -> VolumePriceSignal.EXPAND_FLAT_HIGH
                    PriceLocation.LOW -> if ((rebound ?: 0.0) >= REBOUND_MIN) {
                        VolumePriceSignal.EXPAND_FLAT_REBOUND
                    } else {
                        VolumePriceSignal.EXPAND_FLAT_LOW
                    }
                    else -> VolumePriceSignal.EXPAND_FLAT
                }
                changePct > 0 -> if (location == PriceLocation.HIGH) {
                    VolumePriceSignal.EXPAND_UP_HIGH
                } else {
                    VolumePriceSignal.EXPAND_UP
                }
                else -> VolumePriceSignal.EXPAND_DOWN
            }
            volumeRatio <= VolumeRatio.SHRINK_THRESHOLD -> when {
                crash -> VolumePriceSignal.SHRINK_CRASH
                flat -> VolumePriceSignal.NEUTRAL
                changePct > 0 -> when (location) {
                    PriceLocation.LOW -> VolumePriceSignal.SHRINK_UP_LOW
                    PriceLocation.HIGH -> VolumePriceSignal.SHRINK_UP_HIGH
                    else -> VolumePriceSignal.SHRINK_UP
                }
                else -> VolumePriceSignal.SHRINK_DOWN
            }
            else -> VolumePriceSignal.NEUTRAL
        }
    }

    /** 带图标的标签文本，纯文字场景（报告）用；有图标才加空格，避免多出一个空格。 */
    fun tag(signal: VolumePriceSignal): String =
        if (signal.icon.isEmpty()) signal.label else "${signal.icon} ${signal.label}"

    /**
     * 界面上的标签说明：量比要和当日涨跌、价格位置一起看才有意义。
     * 颜色只表示提示性质，不构成买卖建议。
     */
    val helpText: String = """
        量价标签：量比需结合当日涨跌、价格位置和市场环境判断。红色表示偏积极，橙色表示偏谨慎，灰色表示中性；颜色和标签均不构成买卖建议。

        上涨侧
        · 放量上涨：成交活跃且价格上涨，可能有增量资金参与；仍需结合位置，处于阶段高位时需防范冲高回落。
        · 高位放量上涨：处于近 60 日区间上 1/3 且 60 日累计涨幅 ≥20%，上涨伴随放量，需警惕情绪过热。
        · 放量滞涨：量比 ≥1.3 且涨跌幅在 ±0.5% 内；位置不明时只作观察，不判断方向。
        · 低位放量滞涨：位于区间下 1/3 且距离低点反弹不足 10%，可能有换手，但不代表趋势已经反转。
        · 反弹遇阻滞涨：位于区间下 1/3 且已从低点反弹 ≥10%，放量未推动价格，需等待突破确认。
        · 高位放量滞涨：阶段高位放量但价格未继续推进，需警惕承接减弱或抛压增加。
        · 缩量上涨：可能反映抛压较轻，不等同于买入信号。
        · 缩量弱反弹：区间低位缩量上涨，可能是反弹动力不足。
        · 缩量加速：阶段高位缩量上涨，需警惕上涨持续性。

        下跌侧
        · 量价组合不单独构成买入或卖出依据，仅用于观察供需变化。
        · 缩量阴跌：成交偏弱且价格下行，趋势可能尚未稳定。
        · 缩量急跌：跌幅达到 3% 以上且成交偏弱，可能受流动性或跌停影响，不宜仅按“抛压衰竭”解读。
        · 放量下跌：成交放大且价格下行，可能是分歧和承接增加，也可能是抛压释放，需结合位置。
        · 放量急跌：跌幅达到 3% 以上且成交放大，短期风险较高，是否企稳仍需后续价格确认。
    """.trimIndent()
}
