package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.RowSnapshot

/**
 * 分析环境：**大盘状态 + 板块上下文 + 昨日快照**。
 *
 * 三者共同决定「同一根 K 线该怎么读」——「回踩支撑」在多头是买点，在塌陷是接飞刀；
 * 没有这个上下文，技术面就是个股自洽的孤岛，熊市里会给出正好相反的建议。
 *
 * 拿不到就用 [NONE]：**不改写建议**，只在文案里写明「环境暂缺」，不猜。
 */
data class EnvContext(
    /** 大盘状态（[MarketRegime] 判定）。 */
    val market: MarketState,
    /** 板块上下文；批次二接入前恒为 null。 */
    val sector: SectorContext? = null,
    /** 昨日快照，用来写「较昨日」；第一天跑没有就为 null。 */
    val prev: RowSnapshot? = null,
    /** 北向、成交额和时点因素；只作证据补充，不单独改写信号。 */
    val marketRisk: MarketRiskContext? = null,
) {
    /** 环境是否处于需要改写建议的收缩状态（大盘或板块任一塌陷）。 */
    val isCollapse: Boolean
        get() = market.isCollapse || sector?.state?.isCollapse == true

    /** 大盘或板块是否已转弱（含塌陷）。 */
    val isWeak: Boolean
        get() = market.regime == MarketRegime.WEAK || market.regime == MarketRegime.COLLAPSE ||
            sector?.state?.let { it.regime == MarketRegime.WEAK || it.regime == MarketRegime.COLLAPSE } == true

    companion object {
        /** 没有环境数据时的中性默认。 */
        val NONE = EnvContext(
            market = MarketState(
                regime = MarketRegime.NEUTRAL,
                reason = "市场状态暂缺",
                changePct = null,
                ma20Pct = null,
                ma60Pct = null,
                change5d = null,
                change20d = null,
            ),
        )
    }
}
