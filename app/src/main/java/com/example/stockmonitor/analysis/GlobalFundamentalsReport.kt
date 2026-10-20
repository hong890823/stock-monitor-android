package com.example.stockmonitor.analysis

import com.example.stockmonitor.core.GlobalFundamentals
import java.util.Locale

data class GlobalValuationRange(
    val low: Double,
    val high: Double,
    val basis: String,
)

object GlobalFundamentalsReport {

    fun format(data: GlobalFundamentals, asOf: String, technical: TechnicalResult? = null): String {
        val quality = qualityConclusion(data)
        val valuation = valuationConclusion(data)
        val risks = riskItems(data)
        return buildString {
            appendLine("🌎 ${data.security.name}（${data.security.symbol}）港美股基本面分析")
            appendLine("市场：${data.security.exchange}｜币种：${currencyName(data.currency)}｜现价：${data.price?.let { formatPrice(it, data.currency) } ?: "暂缺"}｜生成于 $asOf")
            appendLine("数据来源：${data.dataSources.joinToString(" + ").ifBlank { "未识别" }}｜最新财务期间：${data.latestPeriod ?: "暂缺"}｜交易币种：${currencyName(data.currency)}｜财报币种：${currencyName(data.financialCurrency)}")
            appendLine("口径：${data.financialBasis}；资产负债表字段取最新期末值，缺失字段不补值。")
            if (data.dataWarnings.isNotEmpty()) {
                appendLine("数据完整度提醒：${data.dataWarnings.joinToString("；")}")
            }
            appendLine()
            appendLine("一、快速结论")
            appendLine("公司质量：$quality")
            appendLine("估值判断：$valuation")
            appendLine(fairValueSummary(data))
            appendLine("投资者应关注：${riskItems(data).joinToString("；").ifBlank { "暂无可识别的主要风险，仍需结合最新财报核对。" }}")
            appendLine()
            appendLine("二、盈利能力")
            appendLine("营收（${data.financialBasis}）：${moneyOrDash(data.revenue, data.financialCurrency)}")
            appendLine("毛利率（${data.financialBasis}）：${pct(data.grossMargin)}｜营业利润率（${data.financialBasis}）：${pct(data.operatingMargin)}｜净利率（${data.financialBasis}）：${pct(data.profitMargin)}")
            appendLine("ROE（${data.financialBasis} 净利润 ÷ 最新期末净资产）：${pct(data.roe)}")
            appendLine("结论：${profitabilityConclusion(data)}")
            appendLine()
            appendLine("三、现金流与资产负债")
            appendLine("经营现金流：${moneyOrDash(data.operatingCashFlow, data.financialCurrency)}｜自由现金流：${moneyOrDash(data.freeCashFlow, data.financialCurrency)}")
            appendLine("现金及等价物：${moneyOrDash(data.cash, data.financialCurrency)}｜总债务：${moneyOrDash(data.debt, data.financialCurrency)}")
            appendLine("结论：${solvencyConclusion(data)}")
            appendLine()
            appendLine("四、业务与竞争优势")
            appendLine("行业：${data.sector ?: "—"}｜细分行业：${data.industry ?: "—"}")
            appendLine("公司简介：${data.summary ?: "暂未取到公司业务简介。"}")
            appendLine("结论：业务壁垒需要结合客户集中度、研发投入、产品周期和竞争对手变化继续核对，不能只依据公司简介下结论。")
            appendLine()
            appendLine("五、估值")
            appendLine("市值：${moneyOrDash(data.marketCap, data.currency)}｜${data.financialBasis} PE：${num(data.pe)}｜PB：${num(data.pb)}")
            appendLine(fairValueSummary(data))
            appendLine("结论：$valuation")
            appendLine()
            appendTechnicalSection(this, technical, data.currency)
            appendLine("七、风险提示")
            if (risks.isEmpty()) appendLine("当前字段未识别出明确风险，但不代表没有风险。")
            risks.forEach { appendLine("· $it") }
            appendLine()
            appendLine("综合结论：$quality；$valuation。${fairValueSummary(data)}。${technicalConclusion(technical, data.currency)}港股/美股财报口径、币种和披露周期与 A 股不同，本报告只使用已取得的公开字段，缺失项目不会用 A 股数据补齐。")
            appendLine("⚠️ 本报告仅作公开信息整理和条件式分析，不构成投资建议。")
        }
    }

    /**
     * 港美股缺少 A 股那种统一的历史估值分位接口，因此这里不伪造历史分位。
     * 有盈利时以当前 TTM PE 反推每股盈利，并按盈利质量给出参考 PE 档位；PE 不可用时再用 PB 兜底。
     */
    fun fairValueRange(data: GlobalFundamentals): GlobalValuationRange? {
        val price = data.price?.takeIf { it > 0 } ?: return null
        val pe = data.pe?.takeIf { it > 0 }
        if (pe != null) {
            val (lowMultiple, highMultiple) = peBand(data)
            val qualityNotes = buildList {
                data.profitMargin?.let { add("净利率 ${pct(it)}") }
                data.roe?.let { add("ROE ${pct(it)}") }
                if (data.freeCashFlow != null) add("自由现金流${if (data.freeCashFlow >= 0) "为正" else "为负"}")
                if (data.cash != null && data.debt != null) add(if (data.cash >= data.debt) "现金覆盖债务" else "债务高于现金")
            }
            return GlobalValuationRange(
                low = price * lowMultiple / pe,
                high = price * highMultiple / pe,
                basis = "TTM PE 参考 ${multiple(lowMultiple)}~${multiple(highMultiple)} 倍" +
                    if (qualityNotes.isEmpty()) "" else "（${qualityNotes.joinToString("、")}）",
            )
        }
        val pb = data.pb?.takeIf { it > 0 } ?: return null
        val roe = data.roe ?: return null
        val (lowMultiple, highMultiple) = pbBand(roe)
        return GlobalValuationRange(
            low = price * lowMultiple / pb,
            high = price * highMultiple / pb,
            basis = "PB 参考 ${multiple(lowMultiple)}~${multiple(highMultiple)} 倍（ROE ${pct(roe)}）",
        )
    }

    fun fairValueSummary(data: GlobalFundamentals): String {
        val range = fairValueRange(data) ?: return "基本面合理估值：暂不出区间（缺少可用的 PE/PB 估值锚或当前价格）。"
        val position = data.price?.let { price ->
            when {
                price > range.high -> "现价 ${formatPrice(price, data.currency)} 高于区间上沿"
                price < range.low -> "现价 ${formatPrice(price, data.currency)} 低于区间下沿"
                else -> "现价 ${formatPrice(price, data.currency)} 位于区间内"
            }
        } ?: "现价暂缺"
        return "基本面合理估值：${formatPrice(range.low, data.currency)} ~ ${formatPrice(range.high, data.currency)}" +
            "（$position；估值范围依据：${range.basis}；不是历史分位或目标价）"
    }

    private fun peBand(data: GlobalFundamentals): Pair<Double, Double> {
        val margin = data.profitMargin ?: 0.0
        val roe = data.roe ?: 0.0
        val highQuality = margin >= 0.20 && roe >= 0.15 && (data.freeCashFlow == null || data.freeCashFlow >= 0.0)
        val quality = margin >= 0.10 || roe >= 0.10
        var band = when {
            highQuality -> 20.0 to 35.0
            quality -> 14.0 to 28.0
            else -> 10.0 to 20.0
        }
        if (data.freeCashFlow != null && data.freeCashFlow < 0.0) {
            band = band.first * 0.90 to band.second * 0.95
        }
        if (data.cash != null && data.debt != null && data.debt > data.cash) {
            band = band.first * 0.90 to band.second * 0.95
        }
        return band
    }

    private fun pbBand(roe: Double): Pair<Double, Double> = when {
        roe >= 0.20 -> 2.0 to 5.0
        roe >= 0.12 -> 1.5 to 3.5
        roe >= 0.08 -> 1.0 to 2.5
        else -> 0.7 to 1.8
    }

    private fun multiple(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun appendTechnicalSection(builder: StringBuilder, technical: TechnicalResult?, currency: String) {
        builder.appendLine("六、技术面与 K 线")
        if (technical == null) {
            builder.appendLine("历史 K 线暂缺，无法计算均线、量价、支撑压力和成交密集区；基本面部分仍可单独参考。")
            builder.appendLine()
            return
        }
        val chip = technical.chip
        val unit = currencyName(currency)
        val chipRange = if (chip.chip70Lower != null && chip.chip70Upper != null) {
            "${price(chip.chip70Lower)} ~ ${price(chip.chip70Upper)} $unit"
        } else {
            "暂缺"
        }
        val peaks = chip.peaks.joinToString("、") {
            "${price(it.first)}附近(${String.format(Locale.US, "%.1f", it.second)}%)"
        }.ifBlank { "暂无明显密集区" }
        val rsi = technical.rsi?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
        builder.appendLine("数据截至：${technical.asOf}｜有效日线：${technical.rows} 个交易日｜近一日涨跌：${pct(technical.priceChange / 100.0)}")
        builder.appendLine("趋势：MA5 ${price(technical.ma5)} $unit｜MA20 ${price(technical.ma20)} $unit｜MA60 ${price(technical.ma60)} $unit｜${technical.action}")
        builder.appendLine("量价：量比 ${technical.volumeRatio?.let { String.format(Locale.US, "%.2f", it) } ?: "—"}｜OBV近20日 ${String.format(Locale.US, "%+.0f", technical.obvChange)}｜${technical.divergence.short}｜${technical.volumeNote ?: "量价数据暂缺"}")
        builder.appendLine("MACD：DIF ${decimal(technical.macd.dif, 3)}｜DEA ${decimal(technical.macd.dea, 3)}｜柱体 ${decimal(technical.macd.hist, 3)}；RSI $rsi")
        builder.appendLine("波动：ATR14 ${price(technical.volatility.atr)} $unit｜布林带 ${price(technical.volatility.bollLower)} ~ ${price(technical.volatility.bollUpper)} $unit")
        builder.appendLine("技术支撑/压力：支撑 ${price(technical.support)} $unit｜压力 ${price(technical.resistance)} $unit")
        builder.appendLine("技术入场结构：${technical.entrySetup.conciseText()}" +
            (technical.entrySetup.stopPrice?.let { "｜止损参考 ${price(it)} $unit" } ?: ""))
        builder.appendLine("成交密集区估算：$chipRange｜集中度 ${chip.chip70WidthPct?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—"}（${chip.concentration}）")
        builder.appendLine("价格位于：${chip.pricePosition}｜估算成本中枢：${chip.averageCost?.let { "${price(it)} $unit" } ?: "暂缺"}｜密集区：$peaks $unit")
        builder.appendLine("结论：${chip.interpretation}${chip.controlCaution}技术支撑/压力和成交密集区只用于观察价格位置，不是目标价，也不是券商真实持仓筹码分布。")
        builder.appendLine()
    }

    private fun technicalConclusion(technical: TechnicalResult?, currency: String): String = when {
        technical == null -> "技术面数据暂缺；"
        else -> "技术面${technical.action}，${technical.entrySetup.conciseText()}，支撑 ${price(technical.support)} ${currencyName(currency)}、压力 ${price(technical.resistance)} ${currencyName(currency)}；"
    }

    private fun price(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun decimal(value: Double, digits: Int): String = String.format(Locale.US, "%.${digits}f", value)

    /**
     * 公司质量结论。
     *
     * **结论必须带上做出判断所依据的数字**——只写「盈利质量较好」是教科书话，换一只股还是这句；
     * 写清「毛利率 62.0%、净利率 30.0%」，读者才能自己核对这个判断合不合理。
     */
    private fun qualityConclusion(data: GlobalFundamentals): String {
        val facts = listOfNotNull(
            data.grossMargin?.let { "毛利率 ${pct(it)}" },
            data.profitMargin?.let { "净利率 ${pct(it)}" },
            data.roe?.let { "ROE ${pct(it)}" },
            data.freeCashFlow?.let { "自由现金流 ${moneyOrDash(it, data.financialCurrency)}" },
        ).joinToString("、")
        val verdict = when {
            positive(data.profitMargin) && positive(data.freeCashFlow) && netCash(data) ->
                "盈利与现金创造都为正、现金覆盖债务，质量较好"
            positive(data.profitMargin) && positive(data.operatingCashFlow) ->
                "主营盈利和经营现金流为正，但资本开支与负债要单独看"
            positive(data.profitMargin) ->
                "有盈利，但现金流或资产负债字段不完整，结论要保守"
            else -> "公开字段不足或盈利质量偏弱，不宜仅凭本报告下结论"
        }
        return if (facts.isEmpty()) verdict else "$facts；$verdict"
    }

    /**
     * 估值结论。同上——「当前 PE 不高」不说数字，PE 3 和 PE 19 会读出同一句话。
     */
    private fun valuationConclusion(data: GlobalFundamentals): String {
        val usablePe = data.pe?.takeIf { it > 0.0 }
        val usablePb = data.pb?.takeIf { it > 0.0 }
        val anchor = listOfNotNull(
            data.pe?.let { if (it > 0.0) "PE ${num(it)}" else "PE ${num(it)}（亏损，不适用）" },
            data.pb?.let { if (it > 0.0) "PB ${num(it)}" else "PB ${num(it)}（无效）" },
        ).joinToString("、")
        val verdict = when {
            usablePe == null && usablePb == null -> "PE/PB 暂缺，不能给出可靠估值判断"
            usablePe != null && usablePe <= 20.0 -> "按 PE 看不贵，但仍需结合增长速度、周期位置和现金流验证"
            usablePe != null && usablePe <= 35.0 -> "处在成长股常见的中高区间，合理性取决于未来利润增速"
            usablePb != null && usablePb <= 3.0 -> "按 PB 看不贵，但仍需结合盈利质量、行业属性和现金流验证"
            usablePb != null && usablePb <= 5.0 -> "PB 处在中高区间，合理性取决于盈利质量和资本回报"
            else -> "估值偏高，股价已包含较高增长预期，业绩不及预期时估值可能收缩"
        }
        return if (anchor.isEmpty()) verdict else "$anchor——$verdict"
    }

    /** 盈利能力结论：阈值判档 + **把落在哪一档的数字写出来**。 */
    private fun profitabilityConclusion(data: GlobalFundamentals): String {
        val facts = listOfNotNull(
            data.grossMargin?.let { "毛利率 ${pct(it)}" },
            data.operatingMargin?.let { "营业利润率 ${pct(it)}" },
            data.profitMargin?.let { "净利率 ${pct(it)}" },
            data.roe?.let { "ROE ${pct(it)}" },
        ).joinToString("、")
        val verdict = when {
            data.grossMargin != null && data.grossMargin >= 0.5 &&
                data.profitMargin != null && data.profitMargin >= 0.2 ->
                "毛利率与净利率都高，具备较强定价或平台能力"
            positive(data.profitMargin) ->
                "能盈利，但利润率水平要与同行和自身历史比才算数"
            else -> "盈利字段不足或利润率偏弱，别把收入增长当成股东回报"
        }
        return if (facts.isEmpty()) verdict else "$facts；$verdict"
    }

    /** 偿债结论：现金 vs 债务的具体数字是判断的全部依据，不写出来这句就无从核对。 */
    private fun solvencyConclusion(data: GlobalFundamentals): String {
        val facts = listOfNotNull(
            data.cash?.let { "现金 ${moneyOrDash(it, data.financialCurrency)}" },
            data.debt?.let { "总债务 ${moneyOrDash(it, data.financialCurrency)}" },
            data.operatingCashFlow?.let { "经营现金流 ${moneyOrDash(it, data.financialCurrency)}" },
            data.freeCashFlow?.let { "自由现金流 ${moneyOrDash(it, data.financialCurrency)}" },
        ).joinToString("、")
        val verdict = when {
            netCash(data) && positive(data.freeCashFlow) -> {
                // 现金是债务的几倍，是这条判断的全部依据——不写出来就没法核对。
                val ratio = if (data.cash != null && data.debt != null && data.debt > 0) {
                    "（现金是债务的 ${String.format("%.2f", data.cash / data.debt)} 倍）"
                } else {
                    ""
                }
                "现金覆盖债务、自由现金流为正，安全边际较好$ratio"
            }
            positive(data.operatingCashFlow) ->
                "经营现金流为正，但现金与债务结构要结合完整资产负债表核对"
            else -> "现金流或债务数据不理想/不完整，要重点关注偿债与资本开支压力"
        }
        return if (facts.isEmpty()) verdict else "$facts；$verdict"
    }

    private fun riskItems(data: GlobalFundamentals): List<String> = buildList {
        if (data.pe != null && data.pe > 35.0) add("估值偏高，依赖未来高增长兑现")
        if (data.freeCashFlow != null && data.freeCashFlow < 0.0) add("自由现金流为负，需核对资本开支和营运资金占用")
        if (data.debt != null && data.cash != null && data.debt > data.cash) add("债务高于现金，利率和再融资环境会影响安全边际")
        if (data.summary.isNullOrBlank()) add("公司业务简介暂缺，竞争优势判断不完整")
        if (data.dataWarnings.isNotEmpty()) add("部分数据未取得，以上结论不能替代完整财报核对")
    }

    private fun positive(value: Double?): Boolean = value != null && value > 0.0
    private fun netCash(data: GlobalFundamentals): Boolean = data.cash != null && data.debt != null && data.cash >= data.debt
    private fun pct(value: Double?): String = value?.let { String.format(Locale.US, "%.1f%%", it * 100) } ?: "—"
    private fun num(value: Double?): String = value?.let { String.format(Locale.US, "%.1f 倍", it) } ?: "—"
    fun formatPrice(value: Double, currency: String): String =
        String.format(Locale.US, "%.2f%s", value, currencyName(currency))

    private fun moneyOrDash(value: Double?, currency: String): String =
        value?.let { formatAmount(it, currency) } ?: "—"

    fun formatAmount(value: Double, currency: String): String {
        val absolute = kotlin.math.abs(value)
        val unit = currencyName(currency)
        return when {
            absolute >= 1_000_000_000_000.0 -> String.format(Locale.US, "%.2f万亿%s", value / 1_000_000_000_000.0, unit)
            absolute >= 100_000_000.0 -> String.format(Locale.US, "%.2f亿%s", value / 100_000_000.0, unit)
            absolute >= 10_000.0 -> String.format(Locale.US, "%.2f万%s", value / 10_000.0, unit)
            else -> String.format(Locale.US, "%.2f%s", value, unit)
        }
    }

    private fun currencyName(currency: String): String = when (currency.uppercase(Locale.US)) {
        "USD" -> "美元"
        "HKD" -> "港元"
        "CNY", "RMB" -> "人民币"
        "EUR" -> "欧元"
        "GBP" -> "英镑"
        else -> currency.ifBlank { "货币单位" }
    }
}
