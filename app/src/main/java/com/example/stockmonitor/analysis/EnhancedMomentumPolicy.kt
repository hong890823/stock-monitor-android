package com.example.stockmonitor.analysis

import java.util.Locale
import kotlin.math.abs

/**
 * 增强动量证据（2026-11-26 纳入）：MACD 红绿柱形态、TTM Squeeze 波动率压缩、
 * Chaikin 资金流（CMF）、Aroon 趋势时钟。
 *
 * 口径：四者都作为**证据层**进入展示与报告，不单独计分——动量维度已有 MACD/RSI/KDJ/
 * ADX/OBV 共 20 分，再给同族指标加权会挤占维度多样性（红绿柱与 DIF/DEA 是同一条公式）。
 * 每个指标回答一个现有维度看不见的问题：红绿柱=动能节奏，Squeeze=蓄势能量，
 * CMF=量价持续性（比裸 OBV 稳），Aroon=距新高/新低的时间（比 ADX 早）。
 */
object EnhancedMomentumPolicy {

    data class MomentumDetail(
        /** 如「红柱 3 连放大」。 */
        val macdBarLabel: String,
        /** 如「波动率压缩 8 根」/「压缩 2 根前向上释放」/「无波动率压缩」。 */
        val squeezeLabel: String,
        /** 如「CMF +0.12（承接偏强）」。 */
        val cmfLabel: String,
        /** 如「Aroon 82/18（上行趋势）」。 */
        val aroonLabel: String,
    ) {
        val summaryLine: String
            get() = "$macdBarLabel｜$squeezeLabel｜$cmfLabel｜$aroonLabel"
    }

    fun evaluate(
        macdHist: List<Double>,
        high: List<Double>,
        low: List<Double>,
        close: List<Double>,
        volume: List<Double>,
    ): MomentumDetail = MomentumDetail(
        macdBarLabel = macdBarState(macdHist) ?: "红绿柱暂缺",
        squeezeLabel = squeezeState(high, low, close) ?: "波动率压缩暂缺",
        cmfLabel = cmfLabel(high, low, close, volume) ?: "CMF 暂缺",
        aroonLabel = aroonLabel(high, low) ?: "Aroon 暂缺",
    )

    /** MACD 红绿柱形态：红/绿柱的连续放大或缩短（按柱体绝对值的单调段计数）。 */
    fun macdBarState(hist: List<Double>): String? {
        val values = hist.takeLast(8).filter { it.isFinite() }
        if (values.size < 2) return null
        val last = values.last()
        val previous = values[values.size - 2]
        if (last == 0.0 && previous == 0.0) return null
        val positive = last > 0
        val growing = abs(last) > abs(previous)
        var streak = 1
        for (i in values.size - 2 downTo 1) {
            val current = values[i]
            val before = values[i - 1]
            val sameSide = (current > 0) == (before > 0)
            val sameMove = (abs(current) > abs(before)) == growing
            if (sameSide && sameMove) streak++ else break
        }
        return when {
            positive && growing -> "红柱 $streak 连放大"
            positive && !growing -> "红柱缩短"
            !positive && !growing -> "绿柱 $streak 连缩短"
            else -> "绿柱放大"
        }
    }

    /**
     * TTM Squeeze：BOLL(20,2) 收进 Keltner(20,1.5) 内 = 波动率压缩（蓄势）；
     * 压缩结束后的方向释放是启动信号。返回描述文案，数据不足为 null。
     */
    fun squeezeState(high: List<Double>, low: List<Double>, close: List<Double>): String? {
        if (close.size < 25 || high.size < close.size || low.size < close.size) return null
        val boll = Indicators.bollinger(close)
        val keltner = Indicators.keltner(high, low, close)
        val squeezed = BooleanArray(close.size) { i ->
            boll.upper[i].isFinite() && keltner.upper[i].isFinite() &&
                boll.upper[i] < keltner.upper[i] && boll.lower[i] > keltner.lower[i]
        }
        val last = close.size - 1
        if (squeezed[last]) {
            var bars = 1
            var i = last - 1
            while (i >= 0 && squeezed[i]) {
                bars++
                i--
            }
            return "波动率压缩 $bars 根"
        }
        var released = 0
        var i = last
        while (i >= 0 && !squeezed[i]) {
            released++
            i--
        }
        if (i >= 0 && released <= 3) {
            val releaseUp = close[last] > close[i]
            return "压缩 ${released} 根前${if (releaseUp) "向上" else "向下"}释放"
        }
        return "无波动率压缩"
    }

    /** CMF(20) 文案：>0.05 承接偏强、<-0.05 抛压偏重，其余中性。 */
    fun cmfLabel(high: List<Double>, low: List<Double>, close: List<Double>, volume: List<Double>): String? {
        val value = Indicators.cmf(high, low, close, volume) ?: return null
        val tone = when {
            value > 0.05 -> "承接偏强"
            value < -0.05 -> "抛压偏重"
            else -> "中性"
        }
        return String.format(Locale.CHINA, "CMF %+.2f（%s）", value, tone)
    }

    /** Aroon(25) 文案：>70/<30 判趋势方向，双低判震荡。 */
    fun aroonLabel(high: List<Double>, low: List<Double>): String? {
        val series = Indicators.aroon(high, low) ?: return null
        val up = series.up.lastOrNull { it.isFinite() } ?: return null
        val down = series.down.lastOrNull { it.isFinite() } ?: return null
        val tone = when {
            up >= 70 && down <= 30 -> "上行趋势"
            down >= 70 && up <= 30 -> "下行趋势"
            up < 50 && down < 50 -> "震荡无方向"
            up > down -> "偏上行"
            else -> "偏下行"
        }
        return String.format(Locale.CHINA, "Aroon %.0f/%.0f（%s）", up, down, tone)
    }
}
