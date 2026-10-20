package com.example.stockmonitor.analysis

import java.util.Locale

/** 技术分析产出的支撑/压力区间，只作为价格位置参考，不等同于基本面估值或目标价。 */
data class TechnicalReference(
    val support: Double?,
    val resistance: Double?,
    val entrySetup: EntrySetup? = null,
)

/** 基本面合理区间与技术参考区间的关系。所有价格必须来自同一只股票、同一单位。 */
data class ValuationRangeComparison(
    val fundamentalLow: Double?,
    val fundamentalHigh: Double?,
    val technicalLow: Double?,
    val technicalHigh: Double?,
    val overlapLow: Double?,
    val overlapHigh: Double?,
) {
    val hasFundamentalRange: Boolean
        get() = fundamentalLow != null && fundamentalHigh != null

    val hasTechnicalRange: Boolean
        get() = technicalLow != null && technicalHigh != null

    val hasOverlap: Boolean
        get() = overlapLow != null && overlapHigh != null

    val hasConflict: Boolean
        get() = hasFundamentalRange && hasTechnicalRange && !hasOverlap
}

/**
 * 基本面报告的文本渲染，排版风格与技术分析报告一致（等宽字体、一、二、三……分节）。
 *
 * 这里只做「把算好的数字讲清楚」，不做二次判断：结论、风险、估值锚都由 [Fundamentals] 给出，
 * 避免同一套逻辑在两处各写一遍、改一处漏一处。
 */
object FundamentalsReport {

    private const val INTERNAL_CONTROL_CAVEAT =
        "内控与合规：审计意见、股权质押、监管处罚这些信息在公告里而不在报表里，" +
            "上面这一段是财报上可观测的财务质量代理指标（商誉、其他应收款、存贷双高、扣非比例、现金流含量），" +
            "下面一节才是公告扫描结果。"

    private const val VALUATION_CAVEAT =
        "口径：当前估值以自身历史为主，行业估值只作对照；未来区间是情景参考，不是目标价。"

    private const val DISCLAIMER =
        "⚠️ 本报告基于公开财报与历史估值计算，数据来自东方财富接口；不构成投资建议。"

    fun format(
        name: String,
        code: String,
        price: Double,
        asOf: String,
        result: FundamentalResult,
        technical: TechnicalReference? = null,
    ): String {
        val latest = result.latest
        return buildString {
            appendLine("📊 $name（$code）基本面分析")
            appendLine("行情：${money(price)} 元｜最新报告期：${latest?.date ?: "—"}｜生成于 $asOf")
            appendLine()

            // 顶部只给概览，真正的综合结论放在报告最后（第十三节），关键数据在那里标红。
            appendLine("一、快速概览")
            appendLine("财务质量评分：${result.score}/100（${result.verdict}）")
            appendLine("结论：${headline(result)}")
            appendLine("股东结构：${shareholderSummary(result)}")
            appendLine(valuationText(result, price))
            appendLine(combinedValuationText(result, technical))
            appendLine()

            appendLine("二、有效利润（归母 vs 扣非）")
            if (latest != null) {
                appendLine(
                    "最近报告期（${latest.date}，年初至今累计）：营收 ${yi(latest.revenue)} 亿｜" +
                        "归母 ${yi(latest.netProfit)} 亿｜扣非 ${yi(latest.deductNetProfit)} 亿"
                )
            }
            appendLine(
                "归母 TTM ${result.ttmNetProfit?.let { "${yi(it)} 亿" } ?: "—"}" +
                    "｜扣非 TTM ${result.ttmDeductNetProfit?.let { "${yi(it)} 亿" } ?: "—"}"
            )
            appendLine("扣非占归母：${pct1(result.deductRatio?.times(100))}")
            appendLine("EPS-TTM ${numOrDash(result.eps)} 元｜BPS ${numOrDash(result.bps)} 元")
            appendLine("说明：归母含政府补助、处置资产等一次性损益，扣非剔掉这些，剩下的才是主营赚的钱。")
            appendLine("结论：${profitQualityConclusion(result)}")
            appendLine()

            appendLine("三、盈利能力")
            appendLine("毛利率 TTM ${pct1(result.grossMargin)}｜净利率 TTM ${pct1(result.netMargin)}")
            appendLine("ROE（TTM 归母净利 ÷ 期末归母净资产）：${pct1(result.roe?.times(100))}")
            appendLine(grossMarginTrend(result))
            appendLine("结论：${profitabilityConclusion(result)}")
            appendLine()

            appendLine("四、现金与偿债")
            appendLine("经营现金流 / 归母净利 TTM：${numOrDash(result.ocfToProfit)} 倍")
            appendLine("销售收现比 TTM：${numOrDash(result.cashToRevenue)}（含税口径下 1.0~1.13 属正常）")
            appendLine("净现金 / 归母净资产：${pct1(result.netCashRatio?.times(100))}")
            val balance = result.balance
            if (balance != null) {
                appendLine(
                    "货币资金 ${yi(balance.monetaryFunds)} 亿｜有息负债 ${yi(balance.interestBearingDebt)} 亿｜" +
                        "净现金 ${yi(balance.netCash)} 亿"
                )
                appendLine("资产负债率：${pct1(debtRatio(balance)?.times(100))}")
            }
            appendLine("结论：${solvencyConclusion(result)}")
            appendLine()

            appendLine("五、资产质量与内控代理指标")
            if (balance != null) {
                appendLine("商誉 ${yi(balance.goodwill)} 亿（占归母净资产 ${pct1(goodwillRatio(balance)?.times(100))}）")
                appendLine("其他应收款 ${yi(balance.otherReceivable)} 亿（占总资产 ${pct1(assetRatio(balance, balance.otherReceivable)?.times(100))}）")
                appendLine(
                    "应收+存货 ${yi(balance.accountsReceivable + balance.inventory)} 亿" +
                        "（占总资产 ${pct1(assetRatio(balance, balance.accountsReceivable + balance.inventory)?.times(100))}）"
                )
            } else {
                appendLine("资产负债表未取到，资产质量部分暂缺。")
            }
            appendLine(INTERNAL_CONTROL_CAVEAT)
            appendLine("结论：${assetQualityConclusion(result)}")
            appendLine()

            appendGovernance(this, result)
            appendMainBusiness(this, result)
            appendPeers(this, result)

            appendLine("九、单季趋势（最近 ${result.quarters.size} 个单季，金额单位：亿元）")
            if (result.quarters.isEmpty()) {
                appendLine("可用单季不足，趋势暂缺。")
            } else {
                result.quarters.forEach { quarter ->
                    appendLine(
                        "${quarter.label}：营收 ${yi(quarter.revenue)}（${yoy(quarter.revenueYoy)}）｜" +
                            "归母 ${yi(quarter.netProfit)}（${yoy(quarter.netProfitYoy)}）｜" +
                            "毛利率 ${pct1(quarter.grossMargin)}"
                    )
                }
                appendLine("说明：报告期披露的是年初至今的累计值，这里已经按同一自然年逐季差分还原成单季；同比只和去年同一季度比。")
            }
            appendLine("结论：${quarterConclusion(result)}")
            appendLine()

            appendValuation(this, result, price, technical)

            appendLine("十一、风险清单")
            if (result.risks.isEmpty()) {
                appendLine("未扫描到需要提示的财务信号。")
            } else {
                result.risks.forEach { appendLine("${mark(it.level)}${it.text}") }
            }
            appendLine("结论：${riskConclusion(result)}")

            if (result.notes.isNotEmpty()) {
                appendLine()
                appendLine("十二、口径提醒")
                result.notes.forEach { appendLine("※ $it") }
                appendLine("结论：以上是数据口径和研究边界，不是公司经营本身的变化；列在上面的项，相关数字要按说明打折看。")
            }
            appendLine()
            appendConclusion(this, result, price, technical)
            appendLine(DISCLAIMER)
        }
    }

    /** 卡片头部的一句话结论：先给档位，再点出最该盯的那一条。 */
    fun headline(result: FundamentalResult): String {
        val base = when {
            result.score >= 85 -> "财务质量扎实，利润、现金、资产三块都过得去。"
            result.score >= 70 -> "财务质量良好，没有明显硬伤。"
            result.score >= 55 -> "财务质量中性，有短板但不致命，要结合行业位置一起看。"
            result.score >= 40 -> "财务质量偏弱，短板已经写在报表上了。"
            else -> "财务质量较差，下面几条风险建议逐条核实。"
        }
        val severe = result.risks.firstOrNull { it.level == RiskLevel.ALERT }
        val mild = result.risks.firstOrNull { it.level == RiskLevel.WATCH }
        val tail = when {
            severe != null -> "最需要盯的是：${severe.text}。"
            mild != null -> "需要留意：${mild.text}。"
            else -> "暂未发现需要特别警惕的财务信号。"
        }
        val shareholder = result.shareholder?.let { "股东结构：${it.conclusion}" } ?: ""
        return base + tail + if (shareholder.isBlank()) "" else " $shareholder"
    }

    fun shareholderSummary(result: FundamentalResult): String {
        val shareholder = result.shareholder ?: return "暂无可比股东数据。"
        val holders = shareholder.retailHolderCount?.let { holderCount(it) } ?: "—"
        val change = shareholder.retailHolderChangePct?.let { "（${signedPct(it)}）" } ?: ""
        val flow = when {
            shareholder.institutionalNetFunds == null -> "公开披露机构持仓变动估算暂缺"
            shareholder.institutionalNetFunds > 0.0 -> "公开披露机构持仓变动估算净增约 ${yi(shareholder.institutionalNetFunds)} 亿"
            shareholder.institutionalNetFunds < 0.0 -> "公开披露机构持仓变动估算净减约 ${yi(-shareholder.institutionalNetFunds)} 亿"
            else -> "公开披露机构持仓变动估算无明显净变动"
        }
        return "散户/中小股东户数（总户数代理）$holders$change｜$flow｜${shareholder.conclusion}"
    }

    /** 合理估值的一句话。没有区间时说明为什么不出数字，而不是给个假的。 */
    fun valuationText(result: FundamentalResult, price: Double): String {
        val low = result.fairLow
        val high = result.fairHigh
        if (low == null || high == null || low <= 0 || high <= 0) {
            return "合理估值：暂不出区间（可用数据不足，原因见口径提醒）。"
        }
        val position = when {
            price > high -> "现价 ${money(price)} 元，高出区间上沿 ${pct1((price / high - 1) * 100)}，偏贵"
            // 低于下沿一半时再写「低 80%」这种数字没有意义，直接说明量级。
            price < low / 2 -> "现价 ${money(price)} 元，不足区间下沿的一半，偏便宜"
            price < low -> "现价 ${money(price)} 元，低于区间下沿 ${pct1((1 - price / low) * 100)}，偏便宜"
            else -> "现价 ${money(price)} 元，落在区间内"
        }
        return "合理估值：${money(low)} ~ ${money(high)} 元（$position）"
    }

    /**
     * 比较基本面合理区间与技术支撑/压力区间。
     * 不在无交集时拼接伪综合区间，避免把技术位包装成目标价。
     */
    fun combineValuationRanges(
        fairLow: Double?,
        fairHigh: Double?,
        technical: TechnicalReference?,
    ): ValuationRangeComparison {
        val fundamental = validRange(fairLow, fairHigh)
        val technicalRange = technical?.let { validRange(it.support, it.resistance) }
        val overlap = if (fundamental != null && technicalRange != null) {
            val low = maxOf(fundamental.first, technicalRange.first)
            val high = minOf(fundamental.second, technicalRange.second)
            if (low <= high) low to high else null
        } else {
            null
        }
        return ValuationRangeComparison(
            fundamentalLow = fundamental?.first,
            fundamentalHigh = fundamental?.second,
            technicalLow = technicalRange?.first,
            technicalHigh = technicalRange?.second,
            overlapLow = overlap?.first,
            overlapHigh = overlap?.second,
        )
    }

    fun combinedValuationText(result: FundamentalResult, technical: TechnicalReference?): String {
        val comparison = combineValuationRanges(result.fairLow, result.fairHigh, technical)
        val fundamental = if (comparison.hasFundamentalRange) {
            "基本面区间 ${money(comparison.fundamentalLow!!)} ~ ${money(comparison.fundamentalHigh!!)} 元"
        } else {
            "基本面合理区间暂缺"
        }
        val technicalText = if (comparison.hasTechnicalRange) {
            "技术参考区间：${money(comparison.technicalLow!!)} ~ ${money(comparison.technicalHigh!!)} 元"
        } else {
            "技术参考区间暂缺"
        }
        val comparisonText = when {
            comparison.hasOverlap ->
                "综合判断：$fundamental；$technicalText；交集 " +
                    "${money(comparison.overlapLow!!)} ~ ${money(comparison.overlapHigh!!)} 元，" +
                    "两者方向较一致。"
            comparison.hasConflict ->
                "综合判断：$fundamental；$technicalText；两者无交集，" +
                    "基本面与技术面存在分歧，不强行合并为一个价格区间。"
            comparison.hasFundamentalRange ->
                "综合判断：$fundamental；技术参考区间暂缺，基本面区间仍可单独参考。"
            comparison.hasTechnicalRange ->
                "综合判断：$technicalText；基本面合理区间暂缺，技术位只用于观察价格位置。"
            else ->
                "综合判断：基本面合理区间与技术参考区间均暂缺。"
        }
        return comparisonText + technicalTimingSuffix(result, technical)
    }

    private fun technicalTimingSuffix(result: FundamentalResult, technical: TechnicalReference?): String {
        val setup = technical?.entrySetup ?: return ""
        val stop = setup.stopPrice?.let { "，止损参考 ${money(it)} 元" } ?: ""
        val fundamentalTiming = when {
            setup.canBuy && result.score >= 70 -> "基本面与技术择时同向，可关注但仍按止损和仓位执行"
            setup.canBuy && result.score < 55 -> "技术结构成立但基本面偏弱，按交易性机会对待，不宜重仓"
            setup.confirmed && !setup.canBuy -> "技术结构成立但当前只适合观察，不把突破直接当作买入依据"
            !setup.confirmed && result.score >= 70 -> "基本面尚可，但当前没有被确认的入场结构，等待比追价更重要"
            else -> "基本面和技术择时暂未形成共同支持"
        }
        return "；技术择时：${setup.conciseText()}$stop；综合判断：$fundamentalTiming"
    }

    private fun validRange(low: Double?, high: Double?): Pair<Double, Double>? {
        if (low == null || high == null || low <= 0 || high <= 0 || low > high) return null
        return low to high
    }

    private fun grossMarginTrend(result: FundamentalResult): String {
        val margins = result.quarters.mapNotNull { it.grossMargin }
        if (margins.size < 4) return "单季毛利率样本不足，看不出趋势。"
        val recent = margins.takeLast(2).average()
        val before = margins.dropLast(2).takeLast(2).average()
        val delta = recent - before
        val direction = when {
            delta >= 1.5 -> "毛利率在回升"
            delta <= -1.5 -> "毛利率在下滑"
            else -> "毛利率基本走平"
        }
        return "单季毛利率近两季均值 ${pct1(recent)}，前两季 ${pct1(before)}，$direction。"
    }

    private fun appendMainBusiness(builder: StringBuilder, result: FundamentalResult) {
        builder.appendLine("七、主营业务构成（最近一期）")
        val products = result.mainBusiness
        if (products.isEmpty()) {
            builder.appendLine("接口未返回主营构成，暂缺。")
        } else {
            val shown = products.filter { it.ratio >= 0.01 }.take(5)
            shown.forEach { item ->
                val margin = item.grossMargin?.let { pct1(it * 100) } ?: "—"
                builder.appendLine("${item.name}：${yi(item.income)} 亿（占 ${pct1(item.ratio * 100)}）｜毛利率 $margin")
            }
            val rest = products.filter { it.ratio < 0.01 }
            if (rest.isNotEmpty()) {
                builder.appendLine("其余 ${rest.size} 项合计占 ${pct1(rest.sumOf { it.ratio } * 100)}（单项均不足 1%，略）")
            }
        }
        builder.appendLine("结论：${mainBusinessConclusion(result)}")
        builder.appendLine()
    }

    private fun appendPeers(builder: StringBuilder, result: FundamentalResult) {
        builder.appendLine("八、行业地位与同业估值")
        val peers = result.peers
        if (peers == null || peers.sampleSize == 0) {
            builder.appendLine("同业对比：未取到同板块数据。")
        } else {
            val rank = peers.marketCapRank
            val rankText = if (rank != null && peers.total > 0) {
                "市值 ${yi(peers.marketCap ?: 0.0)} 亿，板块内第 $rank/${peers.total} 名" +
                    "（前 ${pct1(rank.toDouble() / peers.total * 100)}）"
            } else {
                "市值 ${yi(peers.marketCap ?: 0.0)} 亿，板块内排名暂缺"
            }
            builder.appendLine("${peers.boardName}板块共 ${peers.total} 只｜$rankText")
            builder.appendLine(
                "板块 PE 中位 ${numOrDash(peers.peMedian)} 倍（按正 PE 的 ${peers.sampleSize} 只统计）" +
                    "｜自身 PE-TTM ${peText(result.currentPe)}"
            )
            builder.appendLine(
                "板块 PB 中位 ${numOrDash(peers.pbMedian)} 倍｜自身 PB-MRQ ${numOrDash(result.currentPb)} 倍"
            )
            builder.appendLine("说明：市值排名反映规模与市场关注度，不等于竞争力；估值高于/低于同业中位需要结合成长性看。")
        }
        builder.appendLine("结论：${peerConclusion(result)}")
        builder.appendLine()
    }

    private fun appendValuation(
        builder: StringBuilder,
        result: FundamentalResult,
        price: Double,
        technical: TechnicalReference?,
    ) {
        builder.appendLine("十、合理估值")
        builder.appendLine(currentValuationSummary(result, price))
        builder.appendLine("估值对比：${selfValuationHeadline(result)}｜${peerValuationHeadline(result)}")
        builder.appendLine(combinedValuationText(result, technical))
        builder.appendLine("下一财报期：" + nextQuarterOutlook(result))
        builder.appendLine("未来预期：${futureValuationSummary(result)}")
        builder.appendLine(VALUATION_CAVEAT)
        builder.appendLine("结论：${valuationHeadline(result, price)}")
        builder.appendLine()
    }

    /**
     * 报告的最后一节：看完要记住什么。
     *
     * 前面的章节是「怎么算出来的」，这一节只挑决策要用的几个数，并且标红，不用再回头翻。
     */
    private fun appendConclusion(
        builder: StringBuilder,
        result: FundamentalResult,
        price: Double,
        technical: TechnicalReference?,
    ) {
        builder.appendLine("十三、综合结论（红色为关键数据）")
        builder.appendLine(
            "· 财务质量：${red("${result.score}/100")}（${result.verdict}）" +
                "｜扣非占归母 ${red(pct1(result.deductRatio?.times(100)))}"
        )
        builder.appendLine(
            "· 现金与偿债：经营现金流/归母净利 ${red(numOrDash(result.ocfToProfit))} 倍" +
                "｜ROE ${red(pct1(result.roe?.times(100)))}" +
                "｜净现金/净资产 ${red(pct1(result.netCashRatio?.times(100)))}"
        )
        val low = result.fairLow
        val high = result.fairHigh
        if (low != null && high != null && low > 0 && high > 0) {
            builder.appendLine(
                "· 估值：合理区间 ${red("${money(low)} ~ ${money(high)} 元")}" +
                    "｜现价 ${red("${money(price)} 元")}（${positionWord(price, low, high)}）"
            )
        } else {
            builder.appendLine("· 估值：可用数据不足，暂不出区间（原因见上一节）")
        }
        builder.appendLine("· ${combinedValuationText(result, technical)}")
        result.governance.firstOrNull { !it.assessment.isNullOrBlank() }?.let {
            builder.appendLine("· 公告影响：${it.assessment}")
        }
        result.shareholder?.let {
            builder.appendLine("· 股东结构：${red(shareholderSummary(result))}")
        }
        builder.appendLine("· 估值判断：${valuationComparisonShort(result)}")
        val worst = result.risks.firstOrNull { it.level == RiskLevel.ALERT }
            ?: result.risks.firstOrNull { it.level == RiskLevel.WATCH }
        worst?.let { builder.appendLine("· 最该盯的：${mark(it.level)}${it.text}") }
        builder.appendLine("· 一句话：${oneLine(result, price, low, high)}")
        builder.appendLine()
    }

    /** 现价相对合理区间的位置；百分比超过 100% 没有意义，用文字描述。 */
    private fun positionWord(price: Double, low: Double, high: Double): String = when {
        price > high -> "高出区间上沿 ${pct1((price / high - 1) * 100)}"
        price < low / 2 -> "不足区间下沿的一半"
        price < low -> "低于区间下沿 ${pct1((1 - price / low) * 100)}"
        price >= (low + high) / 2 -> "落在区间上半段"
        else -> "落在区间下半段"
    }

    /** 一句话结论：质量在前，估值在后，条件式表述，不替用户做买卖决定。 */
    private fun oneLine(result: FundamentalResult, price: Double, low: Double?, high: Double?): String {
        val quality = when {
            result.score >= 70 -> "财务质量没问题"
            result.score >= 55 -> "财务质量中性"
            result.score >= 40 -> "财务质量偏弱"
            else -> "财务质量较差"
        }
        val valuation = when {
            low == null || high == null || low <= 0 || high <= 0 -> "估值区间暂缺，先看盈利质量"
            price > high -> "估值已经不便宜，除非盈利再上台阶"
            price >= (low + high) / 2 -> "估值在区间上半段，安全边际有限"
            price >= low -> "估值在区间下半段，不算贵"
            else -> "估值低于区间下沿，但要先确认盈利下滑不是结构性的"
        }
        return "$quality，$valuation；动手前先看最需要盯的那条风险有没有改善。"
    }

    private fun red(value: String): String = ReportMarkup.red(value)

    /** 有效利润的结论：扣非占比决定这份利润能不能直接拿来做估值。 */
    private fun profitQualityConclusion(result: FundamentalResult): String {
        val ratio = result.deductRatio ?: return "扣非数据缺失，判断不了利润含金量。"
        val gap = if (result.ttmNetProfit != null && result.ttmDeductNetProfit != null) {
            "近 12 个月归母 ${yi(result.ttmNetProfit)} 亿、扣非 ${yi(result.ttmDeductNetProfit)} 亿，" +
                "两者相差 ${yi(kotlin.math.abs(result.ttmNetProfit - result.ttmDeductNetProfit))} 亿。"
        } else {
            ""
        }
        val verdict = when {
            ratio >= 0.95 -> "利润几乎全部来自主营，含金量高，用归母估值的可信度也高。"
            ratio >= 0.85 -> "扣非占比高，一次性损益很少，利润质量没问题。"
            ratio >= 0.70 -> "扣非占比尚可，但已有一部分利润不是主营赚的，估值时最好按扣非口径打个折。"
            else -> "主营贡献不到七成，含金量偏差；用归母净利算出来的 PE 会比实际便宜，建议按扣非重算。"
        }
        return verdict + gap
    }

    /** 盈利能力的结论：先看赚得多不多（利润率），再看赚得划不划算（ROE），最后看趋势。 */
    private fun profitabilityConclusion(result: FundamentalResult): String {
        val margin = if (result.grossMargin != null && result.netMargin != null) {
            "毛利率 ${pct1(result.grossMargin)}、净利率 ${pct1(result.netMargin)}，" + when {
                result.netMargin >= 20 -> "净利率较高，产品有议价能力。"
                result.netMargin >= 8 -> "净利率属制造业常见水平，靠规模或周转赚钱。"
                result.netMargin >= 0 -> "净利率偏薄，成本或价格稍有波动就会吃掉利润。"
                else -> "净利率为负，当期主营是亏的。"
            }
        } else {
            ""
        }
        val roe = result.roe
        val roeWord = when {
            roe == null -> "ROE 取不到，判断不了股东回报水平。"
            roe >= 0.15 -> "ROE ${pct1(roe * 100)}，股东回报优秀。"
            roe >= 0.08 -> "ROE ${pct1(roe * 100)}，股东回报中规中矩。"
            roe >= 0.0 -> "ROE 只有 ${pct1(roe * 100)}，股东回报偏弱。"
            else -> "ROE 为负（${pct1(roe * 100)}），净资产在亏钱。"
        }
        return margin + roeWord + grossMarginTrend(result)
    }

    /** 现金与偿债的结论：利润有没有变成钱、账上还不还得起债，这两条分开说。 */
    private fun solvencyConclusion(result: FundamentalResult): String {
        val ocf = result.ocfToProfit
        val cash = when {
            ocf == null -> "经营现金流/归母净利取不到，这一项跳过。"
            ocf >= 1.0 -> "经营现金流是归母净利的 ${numOrDash(ocf)} 倍，利润落袋，不靠赊账撑收入。"
            ocf >= 0.8 -> "经营现金流是归母净利的 ${numOrDash(ocf)} 倍，基本跟得上利润。"
            ocf >= 0.5 -> "经营现金流只有归母净利的 ${numOrDash(ocf)} 倍，回款偏慢，要盯应收账款。"
            else -> "经营现金流远低于归母净利（${numOrDash(ocf)} 倍），利润还没变成钱，这是最该盯的一条。"
        }
        val ratio = result.netCashRatio
        val netCash = when {
            ratio == null -> "净现金取不到，偿债能力判断不了。"
            ratio >= 0.20 -> "净现金占净资产 ${pct1(ratio * 100)}，账上很宽裕。"
            ratio >= 0.0 -> "净现金占净资产 ${pct1(ratio * 100)}，现金略多于有息负债，安全。"
            ratio >= -0.50 -> "净负债占净资产 ${pct1(-ratio * 100)}，有杠杆但还扛得住。"
            else -> "净负债已占净资产 ${pct1(-ratio * 100)}，偿债压力不小。"
        }
        val debt = result.balance?.let { debtRatio(it) }
        val debtText = if (debt != null) String.format(Locale.CHINA, "资产负债率 %.1f%%，", debt * 100) else ""
        return debtText + cash + netCash
    }

    /** 资产质量的结论：商誉、其他应收款、应收+存货三项，都是「看着是资产、可能是雷」的地方。 */
    private fun assetQualityConclusion(result: FundamentalResult): String {
        val balance = result.balance ?: return "资产负债表没取到，这一节无法下结论。"
        val parts = mutableListOf<String>()
        goodwillRatio(balance)?.let { value ->
            parts.add(
                when {
                    value >= 0.30 -> "商誉占净资产 ${pct1(value * 100)}，一旦减值就直接吃掉利润，要盯并购标的的业绩承诺。"
                    value >= 0.10 -> "商誉占净资产 ${pct1(value * 100)}，有减值敞口但还不算突出。"
                    else -> "商誉占净资产 ${pct1(value * 100)}，可以忽略。"
                }
            )
        }
        assetRatio(balance, balance.otherReceivable)?.let { value ->
            parts.add(
                when {
                    value >= 0.10 -> "其他应收款占总资产 ${pct1(value * 100)}，规模异常，通常是资金被关联方占用。"
                    value >= 0.05 -> "其他应收款占总资产 ${pct1(value * 100)}，占比偏高，值得翻一下明细。"
                    else -> "其他应收款占总资产 ${pct1(value * 100)}，正常。"
                }
            )
        }
        assetRatio(balance, balance.accountsReceivable + balance.inventory)?.let { value ->
            parts.add(
                when {
                    value >= 0.50 -> "应收加存货占总资产 ${pct1(value * 100)}，资产很重，跌价或收不回来都要计提。"
                    value >= 0.30 -> "应收加存货占总资产 ${pct1(value * 100)}，偏重，但属经营常态。"
                    else -> "应收加存货占总资产 ${pct1(value * 100)}，占比不高。"
                }
            )
        }
        return if (parts.isEmpty()) {
            "报表里能用的代理指标都取不到，这一节只能看后面的公告扫描。"
        } else {
            parts.joinToString("")
        }
    }

    /** 公告扫描的结论：正文只用于内部判断，报告展示对股东影响的结论。 */
    private fun governanceConclusion(result: FundamentalResult): String {
        if (result.announcementsScanned == 0) {
            return "公告没取到，治理风险判断不了——是「没看到」，不是「没问题」。"
        }
        if (result.governance.isEmpty()) {
            return "已扫描 ${result.announcementsScanned} 条公告，暂未识别出需要重点提示的事项；" +
                "正文获取或识别不完整时，仍需以公告原文为准。"
        }
        val alerts = result.governance.count { it.level == RiskLevel.ALERT }
        val watches = result.governance.count { it.level == RiskLevel.WATCH }
        val good = result.governance.count { it.level == RiskLevel.GOOD }
        val direction = when {
            alerts > 0 && good > 0 -> "利好与风险并存，风险事项优先核实"
            alerts > 0 -> "整体偏利空，风险事项优先核实"
            watches > 0 && good > 0 -> "利好与风险并存，继续跟踪事项进展"
            watches > 0 -> "以风险提示为主，暂不能视为利空已经落地"
            good > 0 -> "以正面事项为主，但仍需关注执行情况"
            else -> "暂未形成明确方向判断"
        }
        val assessments = result.governance.mapNotNull { it.assessment }.take(4)
        val detail = if (assessments.isEmpty()) {
            "重点公告正文暂未取到，建议打开公告原文核对"
        } else {
            assessments.joinToString("；")
        }
        return "共扫描 ${result.announcementsScanned} 条公告，整理出 ${result.governance.size} 类重点事项" +
            "（⛔ $alerts、⚠️ $watches、✅ $good）；$direction。$detail。"
    }

    /** 主营构成的结论：最大一块占多少，业务是集中还是分散，有没有亏着做的业务。 */
    private fun mainBusinessConclusion(result: FundamentalResult): String {
        val products = result.mainBusiness.filter { it.ratio >= 0.01 }
        val top = products.firstOrNull()
            ?: return "主营构成没取到，看不出业务结构，也就判断不了它是靠什么赚钱的。"
        val margin = top.grossMargin?.let { pct1(it * 100) } ?: "—"
        val head = "最大一块是「${top.name}」，占收入 ${pct1(top.ratio * 100)}、毛利率 $margin；"
        val concentration = when {
            top.ratio >= 0.70 -> "收入高度依赖单一业务，这块业务的景气度会直接决定业绩。"
            top.ratio >= 0.40 -> "收入以这一块为主、其余为补充，属于有主次的业务结构。"
            else -> "收入比较分散，没有绝对主力，抗单一业务波动，但也不容易有超额弹性。"
        }
        val losing = products.filter { (it.grossMargin ?: 0.0) < 0 }
        val losingText = if (losing.isEmpty()) {
            ""
        } else {
            "另外「${losing.first().name}」等 ${losing.size} 项毛利率为负，是亏着做的业务，要确认是不是战略投入。"
        }
        return head + concentration + losingText
    }

    /** 行业地位的结论：规模排名说明关注度，估值对比说明贵不贵，两条分开说。 */
    private fun peerConclusion(result: FundamentalResult): String {
        val peers = result.peers ?: return "没取到同板块数据，行业地位只能自己横向找可比公司。"
        if (peers.sampleSize == 0) return "同板块估值样本为空，这一节无法对比。"
        val rank = peers.marketCapRank
        val rankText = if (rank != null && peers.total > 0) {
            val percentile = rank.toDouble() / peers.total * 100
            when {
                percentile <= 10 -> "市值排板块第 $rank/${peers.total}，属龙头量级，估值通常带一点龙头溢价。"
                percentile <= 30 -> "市值排板块第 $rank/${peers.total}，在头部梯队，规模上不吃亏。"
                else -> "市值排板块第 $rank/${peers.total}，在板块后半段，注意流动性和关注度都偏低。"
            }
        } else {
            "市值排名暂缺。"
        }
        val pe = when {
            result.currentPe == null || result.currentPe <= 0 -> "自身 PE 不适用（亏损），没法按 PE 和同业比。"
            peers.peMedian == null -> "板块 PE 中位缺失，PE 没法对比。"
            result.currentPe > peers.peMedian * 1.2 -> String.format(
                Locale.CHINA,
                "自身 PE %.2f 倍比板块中位 %.2f 倍贵 %.0f%%，需要更高的成长性来支撑。",
                result.currentPe, peers.peMedian, (result.currentPe / peers.peMedian - 1) * 100,
            )
            result.currentPe < peers.peMedian * 0.8 -> String.format(
                Locale.CHINA,
                "自身 PE %.2f 倍比板块中位 %.2f 倍便宜 %.0f%%，也可能是市场对它的成长性打折。",
                result.currentPe, peers.peMedian, (1 - result.currentPe / peers.peMedian) * 100,
            )
            else -> String.format(
                Locale.CHINA,
                "自身 PE %.2f 倍与板块中位 %.2f 倍接近，估值上没有明显的溢价或折价。",
                result.currentPe, peers.peMedian,
            )
        }
        return rankText + pe
    }

    /** 单季趋势的结论：营收和利润的同比方向是否一致，增速谁快谁慢要分开说。 */
    private fun quarterConclusion(result: FundamentalResult): String {
        val revenueYoy = result.quarters.mapNotNull { it.revenueYoy }.takeLast(4)
        val profitYoy = result.quarters.mapNotNull { it.netProfitYoy }.takeLast(4)
        if (revenueYoy.size < 2 || profitYoy.size < 2) return "可比单季不足，趋势暂不下结论。"
        val revenueWord = yoyDirection(revenueYoy, "营收")
        val profitWord = yoyDirection(profitYoy, "利润")
        val nuance = if (profitYoy.count { it > 0 } == 0 && revenueYoy.count { it > 0 } > 0) {
            "增收不增利，要查成本和费用。"
        } else {
            val gap = median(profitYoy) - median(revenueYoy)
            when {
                gap > 3 -> "利润增速快于收入。"
                gap < -3 -> "利润增速慢于收入。"
                else -> "利润和收入的增速基本同步。"
            }
        }
        return revenueWord + profitWord + nuance
    }

    /** 同比方向的描述，营收和利润共用；「几季里几季正增长」比中位数更直白。 */
    private fun yoyDirection(values: List<Double>, subject: String): String {
        val up = values.count { it > 0 }
        return when {
            up == values.size -> "近 ${values.size} 个单季${subject}全部同比正增长，"
            up == 0 -> "近 ${values.size} 个单季${subject}全部同比下滑，"
            up * 2 > values.size -> "近 ${values.size} 个单季里 $up 季${subject}同比正增长，"
            else -> "近 ${values.size} 个单季里只有 $up 季${subject}同比正增长，"
        }
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }

    private data class ForwardValuation(
        val bearLow: Double,
        val bearHigh: Double,
        val baseLow: Double,
        val baseHigh: Double,
        val bullLow: Double,
        val bullHigh: Double,
        val basis: String,
    )

    private fun currentValuationSummary(result: FundamentalResult, price: Double): String {
        val low = result.fairLow
        val high = result.fairHigh
        if (low == null || high == null || low <= 0 || high <= 0) {
            return "现状估值：数据不足，暂不出区间"
        }
        val position = when {
            price > high -> "高于区间上沿"
            price < low -> "低于区间下沿"
            else -> "处于区间内"
        }
        return "现状估值：${money(low)} ~ ${money(high)} 元｜现价 ${money(price)} 元（$position）"
    }

    private fun selfValuationHeadline(result: FundamentalResult): String {
        val position = when {
            result.pePercentile != null && result.pePercentile >= 70 -> "相对自身历史偏贵"
            result.pbPercentile != null && result.pbPercentile >= 70 -> "相对自身历史偏贵"
            result.pePercentile != null && result.pePercentile <= 30 &&
                (result.pbPercentile == null || result.pbPercentile <= 30) -> "相对自身历史偏低"
            result.pbPercentile != null && result.pbPercentile <= 30 -> "相对自身历史偏低"
            result.pePercentile != null || result.pbPercentile != null -> "相对自身历史居中"
            else -> "相对自身历史暂无法判断"
        }
        return position
    }

    private fun selfValuationPosition(result: FundamentalResult): String {
        val metrics = mutableListOf<String>()
        result.pePercentile?.let { metrics += "PE ${pct1(it)} 分位" }
        result.pbPercentile?.let { metrics += "PB ${pct1(it)} 分位" }
        val position = when {
            result.pePercentile != null && result.pePercentile >= 70 -> "偏贵"
            result.pbPercentile != null && result.pbPercentile >= 70 -> "偏贵"
            result.pePercentile != null && result.pePercentile <= 30 &&
                (result.pbPercentile == null || result.pbPercentile <= 30) -> "偏低"
            result.pbPercentile != null && result.pbPercentile <= 30 -> "偏低"
            metrics.isNotEmpty() -> "居中"
            else -> "暂无法判断"
        }
        val detail = if (metrics.isEmpty()) "无有效 PE/PB 分位" else metrics.joinToString("｜")
        return "相对自身历史：$detail，$position"
    }

    private fun peerValuationPosition(result: FundamentalResult): String {
        val peers = result.peers ?: return "相对行业可比：暂无数据"
        val metrics = mutableListOf<String>()
        relativeToPeer(result.currentPe, peers.peMedian, "PE")?.let(metrics::add)
        relativeToPeer(result.currentPb, peers.pbMedian, "PB")?.let(metrics::add)
        return if (metrics.isEmpty()) {
            "相对行业可比：暂无有效 PE/PB 对比"
        } else {
            "相对行业可比：${metrics.joinToString("｜")}"
        }
    }

    fun nextQuarterOutlook(result: FundamentalResult): String {
        val growth = result.quarters.mapNotNull { it.netProfitYoy }.takeLast(4)
        if (growth.isEmpty()) {
            return "下一财报期趋势预判：盈利同比数据不足，暂不外推；本报告未接入可核对的业绩预告字段。"
        }
        val average = growth.average()
        val majority = (growth.size + 1) / 2
        val positive = growth.count { it >= 0 }
        val negative = growth.count { it < 0 }
        val direction = when {
            positive >= majority && average >= 8 -> "利润趋势偏支持"
            negative >= majority && average <= -8 -> "利润趋势偏承压"
            else -> "利润趋势基本稳定"
        }
        val cash = result.ocfToProfit?.let { "经营现金流/归母净利 ${numOrDash(it)} 倍" } ?: "经营现金流数据不足"
        val quality = result.deductRatio?.let { "扣非占归母 ${pct1(it * 100)}" } ?: "扣非数据不足"
        return "下一财报期趋势预判（历史同比外推，近${growth.size}季净利同比均值 ${signedPct(average)}）：$direction｜$cash｜$quality；本报告未接入可核对的业绩预告字段，以上不等同业绩预告。"
    }

    fun futureValuationSummary(result: FundamentalResult): String {
        val forward = forwardValuation(result) ?: return "数据不足，暂不外推未来估值。"
        return "未来 6~9 个月：中性 ${money(forward.baseLow)}~${money(forward.baseHigh)} 元" +
            "｜悲观 ${money(forward.bearLow)}~${money(forward.bearHigh)} 元" +
            "｜乐观 ${money(forward.bullLow)}~${money(forward.bullHigh)} 元"
    }

    private fun forwardValuation(result: FundamentalResult): ForwardValuation? {
        val low = result.fairLow
        val high = result.fairHigh
        if (low == null || high == null || low <= 0 || high <= 0) return null

        val growth = result.quarters.mapNotNull { it.netProfitYoy }.takeLast(4)
        val averageGrowth = growth.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val growthFactor = (1 + averageGrowth / 100 * 0.5).coerceIn(0.80, 1.20)
        val cashFactor = when {
            result.ocfToProfit == null -> 1.0
            result.ocfToProfit < 0.8 -> 0.94
            result.ocfToProfit >= 1.2 -> 1.04
            else -> 1.0
        }
        val qualityFactor = when {
            result.deductRatio == null -> 1.0
            result.deductRatio < 0.70 -> 0.95
            result.deductRatio >= 0.90 -> 1.03
            else -> 1.0
        }
        val balanceFactor = if (result.netCashRatio != null && result.netCashRatio < 0) 0.95 else 1.0
        val factor = (growthFactor * cashFactor * qualityFactor * balanceFactor).coerceIn(0.65, 1.35)
        val baseLow = low * factor
        val baseHigh = high * factor
        val basis = buildList {
            add("利润趋势修正 ${pct1((growthFactor - 1) * 100)}")
            add("现金流修正 ${pct1((cashFactor - 1) * 100)}")
            add("扣非质量修正 ${pct1((qualityFactor - 1) * 100)}")
            if (balanceFactor < 1.0) add("净负债状态下调")
        }.joinToString("｜")
        return ForwardValuation(
            bearLow = baseLow * 0.85,
            bearHigh = baseHigh * 0.85,
            baseLow = baseLow,
            baseHigh = baseHigh,
            bullLow = baseLow * 1.15,
            bullHigh = baseHigh * 1.15,
            basis = basis,
        )
    }

    private fun valuationHeadline(result: FundamentalResult, price: Double): String {
        val low = result.fairLow
        val high = result.fairHigh
        if (low == null || high == null || low <= 0 || high <= 0) {
            return "当前估值数据不足；${nextQuarterOutlook(result)}"
        }
        val position = when {
            price > high -> "当前价高于自身历史区间"
            price < low -> "当前价低于自身历史区间"
            else -> "当前价处于自身历史区间内"
        }
        val forward = forwardValuation(result)
        val future = if (forward == null) {
            "未来估值暂缺"
        } else {
            "未来中性区间 ${money(forward.baseLow)}~${money(forward.baseHigh)} 元"
        }
        return "$position；${peerValuationHeadline(result)}；${nextQuarterDirection(result)}；$future"
    }

    private fun peerValuationHeadline(result: FundamentalResult): String {
        val peers = result.peers ?: return "行业可比数据不足"
        val pe = relativeToPeer(result.currentPe, peers.peMedian, "PE")
        val pb = relativeToPeer(result.currentPb, peers.pbMedian, "PB")
        return when {
            pe?.contains("低于") == true || pb?.contains("低于") == true -> "相对行业可比有折价"
            pe?.contains("高于") == true || pb?.contains("高于") == true -> "相对行业可比有溢价"
            pe != null || pb != null -> "相对行业可比接近"
            else -> "行业可比数据不足"
        }
    }

    fun nextQuarterDirection(result: FundamentalResult): String =
        when {
            result.quarters.mapNotNull { it.netProfitYoy }.takeLast(4).isEmpty() -> "下一财报期趋势数据不足"
            nextQuarterOutlook(result).startsWith("利润趋势偏支持") -> "下一财报期利润趋势偏支持"
            nextQuarterOutlook(result).startsWith("利润趋势偏承压") -> "下一财报期利润趋势偏承压"
            else -> "下一财报期利润趋势基本稳定"
        }

    private fun valuationComparisonShort(result: FundamentalResult): String {
        return "${selfValuationPosition(result)}；${peerValuationPosition(result)}；综合以自身历史估值为主。"
    }

    private fun relativeToPeer(current: Double?, median: Double?, metric: String): String? {
        if (current == null || median == null || current <= 0 || median <= 0) return null
        val difference = (current / median - 1) * 100
        return when {
            difference <= -5 -> "$metric ${numOrDash(current)} 倍，低于行业中位 ${numOrDash(median)} 倍（折价 ${pct1(-difference)}）"
            difference >= 5 -> "$metric ${numOrDash(current)} 倍，高于行业中位 ${numOrDash(median)} 倍（溢价 ${pct1(difference)}）"
            else -> "$metric ${numOrDash(current)} 倍，接近行业中位 ${numOrDash(median)} 倍"
        }
    }

    /** 风险清单的结论：先给条数分布，再说这些信号该怎么用。 */
    private fun riskConclusion(result: FundamentalResult): String {
        if (result.risks.isEmpty()) return "本期没有扫到需要提示的财务信号，这一节是空的。"
        val alerts = result.risks.count { it.level == RiskLevel.ALERT }
        val watches = result.risks.count { it.level == RiskLevel.WATCH }
        val good = result.risks.count { it.level == RiskLevel.GOOD }
        val base = "本期共 ${result.risks.size} 条（⛔ $alerts 条、⚠️ $watches 条、✅ $good 条）。"
        return base + when {
            alerts > 0 -> "有 ⛔ 级信号，这些项在买入前应当先核实清楚。"
            watches > 0 -> "没有 ⛔ 级信号，但 ⚠️ 项会在行业下行时放大，建议列进跟踪清单。"
            else -> "没有需要警惕的信号。"
        }
    }

    private fun mark(level: RiskLevel): String = when (level) {
        RiskLevel.GOOD -> "✅ "
        RiskLevel.WATCH -> "⚠️ "
        RiskLevel.ALERT -> "⛔ "
    }

    /** 公告扫描结果：关键词只用于内部筛选，报告只展示对股东影响的判断。 */
    private fun appendGovernance(builder: StringBuilder, result: FundamentalResult) {
        builder.appendLine("六、公告与治理提示（窗口 ${Governance.WINDOW_DAYS} 天）")
        if (result.announcementsScanned == 0) {
            builder.appendLine("公告接口未返回数据，这一节暂缺（不是因为没问题）。")
            appendShareholder(builder, result)
            builder.appendLine("结论：${governanceConclusion(result)}")
            builder.appendLine()
            return
        }
        val from = result.announcementFrom
        val to = result.announcementTo
        val covered = if (from != null && to != null) "，实际覆盖 $from ~ $to" else ""
        builder.appendLine("已扫描最近 ${result.announcementsScanned} 条公告$covered。")
        if (result.governance.isEmpty()) {
            builder.appendLine("这些公告中暂未识别出需要重点提示的事项。")
            builder.appendLine("注意：正文获取或识别不完整时，不能把这段内容当成「没有风险」的证明。")
        } else {
            result.governance.forEach {
                val repeat = if (it.count > 1) "（同类共 ${it.count} 条，只列最新一条）" else ""
                builder.appendLine("${mark(it.level)}${it.date} ${it.title}$repeat")
            }
        }
        appendShareholder(builder, result)
        builder.appendLine("结论：${governanceConclusion(result)}")
        builder.appendLine()
    }

    private fun appendShareholder(builder: StringBuilder, result: FundamentalResult) {
        val shareholder = result.shareholder ?: run {
            builder.appendLine("股东结构：暂未取到可比披露数据。")
            return
        }
        builder.appendLine(
            "股东结构（${shareholder.latestReportDate ?: "最新披露"}）：" +
                "散户/中小股东户数（按公开总股东户数代理）${shareholder.retailHolderCount?.let { holderCount(it) } ?: "—"}" +
                "，较上期 ${shareholder.retailHolderChangePct?.let { signedPct(it) } ?: "—"}"
        )
        val flow = when {
            shareholder.institutionalNetFunds == null -> "机构资金流向暂缺"
            shareholder.institutionalNetFunds > 0.0 -> "机构资金净流入约 ${yi(shareholder.institutionalNetFunds)} 亿"
            shareholder.institutionalNetFunds < 0.0 -> "机构资金净流出约 ${yi(-shareholder.institutionalNetFunds)} 亿"
            else -> "机构资金无明显净变动"
        }
        builder.appendLine("$flow（按公开披露机构持仓变动 × 当前价估算，不代表盘中真实机构交易）")
        builder.appendLine("机构流入：${shareholder.inflows.take(6).joinToString("、") { it.name }.ifBlank { "—" }}")
        builder.appendLine("机构流出：${shareholder.outflows.take(6).joinToString("、") { it.name }.ifBlank { "—" }}")
        builder.appendLine("结论：${shareholder.conclusion}")
        builder.appendLine("说明：公开数据通常只披露总股东户数和部分机构持仓，机构进出不等同于全部机构的真实交易。")
    }

    fun debtRatio(balance: BalanceSheetSnapshot): Double? =
        if (balance.totalAssets > 0) balance.totalLiabilities / balance.totalAssets else null

    fun goodwillRatio(balance: BalanceSheetSnapshot): Double? =
        if (balance.parentEquity > 0) balance.goodwill / balance.parentEquity else null

    fun assetRatio(balance: BalanceSheetSnapshot, value: Double): Double? =
        if (balance.totalAssets > 0) value / balance.totalAssets else null

    fun money(value: Double): String = String.format(Locale.CHINA, "%.2f", value)

    /** 金额统一折成「亿」，个股的绝对金额基本都是亿级。 */
    fun yi(value: Double): String = String.format(Locale.CHINA, "%.2f", value / 100_000_000)

    fun holderCount(value: Long): String = if (value >= 10_000) {
        String.format(Locale.CHINA, "%.2f万户", value / 10_000.0)
    } else {
        "${value}户"
    }

    fun numOrDash(value: Double?): String = if (value == null) "—" else String.format(Locale.CHINA, "%.2f", value)

    /**
     * PE 要带单位说明。亏损股的 PE 是负的，「-12.30 倍」看着像个很低的估值，
     * 实际上这时候 PE 根本没有意义，必须写出来。
     */
    fun peText(value: Double?): String = when {
        value == null -> "—"
        value <= 0 -> "亏损（PE 不适用）"
        else -> "${String.format(Locale.CHINA, "%.2f", value)} 倍"
    }

    fun pct1(value: Double?): String = if (value == null) "—" else String.format(Locale.CHINA, "%.1f%%", value)

    /** 带符号的百分比，同比/环比用；没有可比基期时给「—」而不是 0%。 */
    fun signedPct(value: Double?): String =
        if (value == null) "—" else String.format(Locale.CHINA, "%+.1f%%", value)

    private fun yoy(value: Double?): String = "同比 ${signedPct(value)}"
}
