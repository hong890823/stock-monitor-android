package com.example.stockmonitor.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

object SecEdgarClient {

    private data class FactValue(
        val value: Double,
        val start: String?,
        val end: String,
        val filed: String,
    ) {
        val durationDays: Long?
            get() = if (start.isNullOrBlank()) null else daysBetween(start, end)
    }

    data class SecCompany(
        val cik: String,
        val ticker: String,
        val name: String,
    )

    fun resolveTicker(text: String, ticker: String): SecCompany? {
        val normalized = ticker.trim().uppercase()
        val root = JSONObject(text)
        val keys = root.keys()
        while (keys.hasNext()) {
            val row = root.optJSONObject(keys.next()) ?: continue
            if (!row.optString("ticker").equals(normalized, ignoreCase = true)) continue
            val cik = row.optLong("cik_str", 0L)
            if (cik <= 0L) continue
            return SecCompany(
                cik = cik.toString().padStart(10, '0'),
                ticker = row.optString("ticker"),
                name = row.optString("title").ifBlank { normalized },
            )
        }
        return null
    }

    fun searchCompanies(text: String, keyword: String): List<GlobalSecurity> {
        val normalized = keyword.trim().uppercase()
        if (normalized.isBlank()) return emptyList()
        val root = JSONObject(text)
        val results = mutableListOf<GlobalSecurity>()
        val keys = root.keys()
        while (keys.hasNext() && results.size < 12) {
            val row = root.optJSONObject(keys.next()) ?: continue
            val ticker = row.optString("ticker").trim()
            val title = row.optString("title").trim()
            if (ticker.isBlank() || title.isBlank()) continue
            if (!ticker.uppercase().contains(normalized) && !title.uppercase().contains(normalized)) continue
            results += GlobalSecurity(ticker, title, "US", "USD")
        }
        return results.distinctBy { it.symbol }
    }

    fun parseCompanyFacts(
        text: String,
        security: GlobalSecurity,
        price: Double?,
    ): GlobalFundamentals {
        val facts = JSONObject(text).optJSONObject("facts")
            ?: throw Http.HttpError("SEC Company Facts 返回为空")
        val taxonomy = facts.optJSONObject("us-gaap")
            ?: throw Http.HttpError("SEC 未返回 us-gaap 财务事实")

        val revenueTags = listOf(
            "RevenueFromContractWithCustomerExcludingAssessedTax",
            "Revenues",
            "SalesRevenueNet",
        )
        val incomePeriod = latestIncomeEnd(taxonomy, revenueTags)
        val revenue = ttmNumber(taxonomy, revenueTags, "USD", incomePeriod)
        val cost = ttmNumber(
            taxonomy,
            listOf("CostOfRevenue", "CostOfGoodsAndServicesSold"),
            "USD",
            incomePeriod,
        )
        val operatingIncome = ttmNumber(taxonomy, listOf("OperatingIncomeLoss"), "USD", incomePeriod)
        val netIncome = ttmNumber(taxonomy, listOf("NetIncomeLoss", "ProfitLoss"), "USD", incomePeriod)
        val operatingCashFlow = ttmNumber(
            taxonomy,
            listOf("NetCashProvidedByUsedInOperatingActivities"),
            "USD",
            incomePeriod,
        )
        val capex = ttmNumber(
            taxonomy,
            listOf("PaymentsToAcquirePropertyPlantAndEquipment"),
            "USD",
            incomePeriod,
        )
        val cash = latestInstantNumber(
            taxonomy,
            listOf("CashAndCashEquivalentsAtCarryingValue", "CashCashEquivalentsRestrictedCashAndRestrictedCashEquivalents"),
            "USD",
        )
        val debt = latestDebt(taxonomy)
        val equity = latestInstantNumber(
            taxonomy,
            listOf("StockholdersEquity", "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest"),
            "USD",
        )
        val shares = latestInstantNumber(taxonomy, listOf("EntityCommonStockSharesOutstanding"), "shares")
        val eps = if (netIncome != null && shares != null && shares > 0.0) {
            netIncome / shares
        } else {
            ttmNumber(taxonomy, listOf("EarningsPerShareDiluted", "EarningsPerShareBasic"), "USD/shares", incomePeriod)
        }
        val latestPeriod = incomePeriod
        val warnings = mutableListOf<String>()
        if (revenue == null) warnings += "营收"
        if (netIncome == null) warnings += "净利润"
        if (operatingCashFlow == null) warnings += "经营现金流"
        if (cash == null) warnings += "现金"
        if (debt == null) warnings += "债务"
        if (warnings.isNotEmpty()) warnings.replaceAll { "SEC 缺少${it}字段" }

        val marketCap = if (price != null && shares != null) price * shares else null
        val freeCashFlow = if (operatingCashFlow != null && capex != null) {
            operatingCashFlow - abs(capex)
        } else {
            null
        }
        val bookValuePerShare = if (equity != null && shares != null && shares > 0) equity / shares else null

        return GlobalFundamentals(
            security = security,
            price = price,
            currency = security.currency,
            marketCap = marketCap,
            revenue = revenue,
            grossMargin = if (revenue != null && cost != null && revenue != 0.0) (revenue - cost) / revenue else null,
            operatingMargin = if (revenue != null && operatingIncome != null && revenue != 0.0) operatingIncome / revenue else null,
            profitMargin = if (revenue != null && netIncome != null && revenue != 0.0) netIncome / revenue else null,
            roe = if (equity != null && netIncome != null && equity != 0.0) netIncome / equity else null,
            operatingCashFlow = operatingCashFlow,
            freeCashFlow = freeCashFlow,
            cash = cash,
            debt = debt,
            pe = if (price != null && eps != null && eps > 0.0) price / eps else null,
            pb = if (price != null && bookValuePerShare != null && bookValuePerShare > 0.0) price / bookValuePerShare else null,
            sector = null,
            industry = null,
            summary = null,
            latestPeriod = latestPeriod,
            dataSources = listOf("SEC Company Facts"),
            dataWarnings = warnings,
        )
    }

    private fun latestDebt(taxonomy: JSONObject): Double? {
        latestInstantNumber(taxonomy, listOf("LongTermDebt", "LongTermDebtAndFinanceLeaseObligations"), "USD")?.let { return it }
        val current = latestInstantNumber(taxonomy, listOf("LongTermDebtCurrent", "ShortTermBorrowings"), "USD") ?: 0.0
        val nonCurrent = latestInstantNumber(taxonomy, listOf("LongTermDebtNoncurrent"), "USD") ?: 0.0
        return if (current == 0.0 && nonCurrent == 0.0) null else current + nonCurrent
    }

    private fun ttmNumber(
        taxonomy: JSONObject,
        tags: List<String>,
        unit: String,
        targetEnd: String?,
    ): Double? {
        val records = factValues(taxonomy, tags, unit)
        if (records.isEmpty()) return null
        val eligible = records.filter { targetEnd == null || it.end <= targetEnd }
        if (eligible.isEmpty()) return null

        val target = targetEnd ?: eligible.maxOf { it.end }
        val annual = eligible
            .filter { it.end < target && (it.durationDays ?: 0L) >= 300L }
            .maxWithOrNull(compareBy<FactValue> { it.end }.thenBy { it.filed })
        val interim = eligible
            .filter {
                it.end <= target &&
                    (it.durationDays ?: 0L) in 120L..300L
            }
            .maxWithOrNull(compareBy<FactValue> { it.end }.thenBy { it.filed })
        if (annual != null && interim != null) {
            val priorTarget = runCatching { LocalDate.parse(interim.end).minusYears(1) }.getOrNull()
            val priorInterim = priorTarget?.let { targetDate ->
                eligible
                    .filter { candidate ->
                        val candidateDate = runCatching { LocalDate.parse(candidate.end) }.getOrNull() ?: return@filter false
                        val distance = abs(ChronoUnit.DAYS.between(candidateDate, targetDate))
                        (candidate.durationDays ?: 0L) in 120L..300L && distance <= 45L
                    }
                    .minWithOrNull(compareBy<FactValue> {
                        val candidateDate = LocalDate.parse(it.end)
                        abs(ChronoUnit.DAYS.between(candidateDate, targetDate))
                    }.thenByDescending { it.filed })
            }
            if (priorInterim != null) {
                return annual.value + interim.value - priorInterim.value
            }
        }

        val quarters = eligible
            .filter { (it.durationDays ?: 0L) in 70L..120L }
            .groupBy { it.end }
            .values
            .mapNotNull { sameEnd -> sameEnd.maxWithOrNull(compareBy<FactValue> { it.filed }) }
            .sortedBy { it.end }
            .takeLast(4)
        if (quarters.size == 4) return quarters.sumOf { it.value }

        return eligible.maxWithOrNull(compareBy<FactValue> { it.end }.thenBy { it.filed })?.value
    }

    private fun latestInstantNumber(taxonomy: JSONObject, tags: List<String>, unit: String): Double? {
        val records = factValues(taxonomy, tags, unit)
        return records.maxWithOrNull(compareBy<FactValue> { it.end }.thenBy { it.filed })?.value
    }

    private fun factValues(taxonomy: JSONObject, tags: List<String>, unit: String): List<FactValue> {
        val allValues = buildList {
            for (tag in tags) {
            val fact = taxonomy.optJSONObject(tag) ?: continue
            val units = fact.optJSONObject("units") ?: continue
            val values = units.optJSONArray(unit) ?: firstArray(units) ?: continue
                for (index in 0 until values.length()) {
                    val row = values.optJSONObject(index) ?: continue
                    val value = row.optDouble("val", Double.NaN)
                    val end = row.optString("end").trim()
                    if (!value.isFinite() || end.isBlank()) continue
                    add(
                        FactValue(
                            value = value,
                            start = row.optString("start").takeIf { it.isNotBlank() },
                            end = end,
                            filed = row.optString("filed").ifBlank { end },
                        )
                    )
                }
            }
        }
        return allValues.distinctBy { listOf(it.value, it.start, it.end, it.filed) }
    }

    private fun latestIncomeEnd(taxonomy: JSONObject, tags: List<String>): String? =
        factValues(taxonomy, tags, "USD")
            .filter { it.start != null && (it.durationDays ?: 0L) >= 70L }
            .maxWithOrNull(compareBy<FactValue> { it.end }.thenBy { it.filed })
            ?.end

    private fun daysBetween(start: String, end: String): Long? =
        runCatching { ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end)) + 1L }.getOrNull()

    private fun firstArray(units: JSONObject): JSONArray? {
        val keys = units.keys()
        while (keys.hasNext()) {
            val array = units.optJSONArray(keys.next())
            if (array != null) return array
        }
        return null
    }
}
