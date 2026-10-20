package com.example.stockmonitor.core

import com.example.stockmonitor.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Locale
import java.util.TimeZone

data class GlobalSecurity(
    val symbol: String,
    val name: String,
    val exchange: String,
    val currency: String,
)

data class GlobalFundamentals(
    val security: GlobalSecurity,
    val price: Double?,
    val currency: String,
    val marketCap: Double?,
    val revenue: Double?,
    val grossMargin: Double?,
    val operatingMargin: Double?,
    val profitMargin: Double?,
    val roe: Double?,
    val operatingCashFlow: Double?,
    val freeCashFlow: Double?,
    val cash: Double?,
    val debt: Double?,
    val pe: Double?,
    val pb: Double?,
    val sector: String?,
    val industry: String?,
    val summary: String?,
    val latestPeriod: String? = null,
    val dataSources: List<String> = emptyList(),
    val dataWarnings: List<String> = emptyList(),
    val financialCurrency: String = currency,
    val financialBasis: String = "TTM",
)

/** 港股/美股基础面数据适配层：搜索多源兜底，美股财务优先 SEC。 */
class GlobalFundamentalsClient {

    /**
     * 港美股历史日线，供技术分析复用。
     * Yahoo 的 chart 接口同时覆盖美股和带 .HK 后缀的港股；历史不足时由调用方降级为仅基本面。
     */
    fun kline(security: GlobalSecurity, length: Int = 240): List<DayBar> {
        val symbol = URLEncoder.encode(security.symbol, "UTF-8")
        val text = Http.retry(retries = 2, delayMs = 800, label = "Yahoo 港美股历史日线") {
            Http.getText(
                "https://query1.finance.yahoo.com/v8/finance/chart/$symbol?range=2y&interval=1d&events=history&includeAdjustedClose=true",
                Charsets.UTF_8,
                "https://finance.yahoo.com/",
                12_000,
            )
        }
        return parseYahooChartHistory(text).takeLast(length)
    }

    fun search(keyword: String): List<GlobalSecurity> {
        if (keyword.isBlank()) return emptyList()
        val url = "https://query1.finance.yahoo.com/v1/finance/search?q=" +
            URLEncoder.encode(keyword.trim(), "UTF-8") +
            "&quotesCount=12&newsCount=0&enableFuzzyQuery=true"
        return runCatching {
            val text = Http.retry(retries = 2, delayMs = 600, label = "Yahoo 港美股搜索") {
                Http.getText(url, Charsets.UTF_8, "https://finance.yahoo.com/", 8_000)
            }
            parseSearchResults(text).ifEmpty { throw Http.HttpError("Yahoo 搜索无结果") }
        }.getOrElse {
            searchSecCompanies(keyword).ifEmpty { fallbackSearchResults(keyword) }
        }
    }

    private fun searchSecCompanies(keyword: String): List<GlobalSecurity> {
        val text = runCatching {
            Http.getText(
                "https://www.sec.gov/files/company_tickers.json",
                Charsets.UTF_8,
                timeoutMs = 10_000,
                userAgent = SEC_USER_AGENT,
            )
        }.getOrNull() ?: return emptyList()
        return parseSecTickerSearchResults(text, keyword)
    }

    fun fetch(security: GlobalSecurity): GlobalFundamentals {
        val quoteResult = runCatching { fetchFromTwelveData(security) }
            .recoverCatching { fetchFromYahoo(security) }
            .recoverCatching { fetchFromTencentHongKongQuote(security) }
            .recoverCatching { fetchFromYahooQuote(security) }
            .recoverCatching { fetchFromYahooChart(security) }
            .let { result ->
                if (isUsSecurity(security)) {
                    result.recoverCatching { fetchFromNasdaq(security) }
                } else {
                    result
                }
            }
        val quote = quoteResult.getOrNull()
        val financialResult = runCatching {
            if (security.exchange.equals("HKEX", ignoreCase = true)) {
                fetchFromEastmoneyHongKongFinancials(security)
            } else {
                fetchFromYahooTimeseries(security)
            }
        }
            .recoverCatching { fetchFromTwelveDataFinancials(security) }
        val financial = financialResult.getOrNull()
        val combinedQuote = mergeFundamentals(quote, financial)

        if (!isUsSecurity(security)) {
            return combinedQuote ?: unavailableQuote(
                security,
                "港股数据暂不可用：行情 ${quoteResult.exceptionOrNull()?.message ?: "请求失败"}；" +
                    "财务 ${financialResult.exceptionOrNull()?.message ?: "请求失败"}",
            )
        }

        val secResult = runCatching { fetchFromSec(security, combinedQuote) }
        return secResult.getOrElse { secError ->
            combinedQuote?.copy(
                dataWarnings = combinedQuote.dataWarnings + "SEC 财务数据暂不可用：${secError.message ?: "请求失败"}",
            ) ?: throw Http.HttpError(
                "美股数据暂不可用：SEC ${secError.message ?: "请求失败"}；" +
                    "行情 ${quoteResult.exceptionOrNull()?.message ?: "请求失败"}",
            )
        }
    }

    private fun fetchFromSec(security: GlobalSecurity, quote: GlobalFundamentals?): GlobalFundamentals {
        val tickerText = Http.retry(retries = 2, delayMs = 800, label = "SEC 股票映射") {
            Http.getText(
                "https://www.sec.gov/files/company_tickers.json",
                Charsets.UTF_8,
                timeoutMs = 10_000,
                userAgent = SEC_USER_AGENT,
            )
        }
        val company = SecEdgarClient.resolveTicker(tickerText, security.symbol)
            ?: throw Http.HttpError("SEC 未找到 ${security.symbol} 的 CIK")
        val factsText = Http.retry(retries = 2, delayMs = 800, label = "SEC 公司财务") {
            Http.getText(
                "https://data.sec.gov/api/xbrl/companyfacts/CIK${company.cik}.json",
                Charsets.UTF_8,
                timeoutMs = 12_000,
                userAgent = SEC_USER_AGENT,
            )
        }
        val sec = SecEdgarClient.parseCompanyFacts(
            factsText,
            quote?.security ?: security.copy(name = company.name),
            quote?.price,
        )
        return sec.copy(
            marketCap = sec.marketCap ?: quote?.marketCap,
            sector = sec.sector ?: quote?.sector,
            industry = sec.industry ?: quote?.industry,
            summary = sec.summary ?: quote?.summary,
            dataSources = (sec.dataSources + (quote?.dataSources ?: emptyList())).distinct(),
            dataWarnings = (
                sec.dataWarnings +
                    (quote?.dataWarnings ?: listOf("行情数据暂不可用，市值和 PE/PB 可能无法计算"))
                ).distinct(),
        )
    }

    private fun fetchFromYahooTimeseries(security: GlobalSecurity): GlobalFundamentals {
        val symbol = URLEncoder.encode(security.symbol, "UTF-8")
        val types = YAHOO_FINANCIAL_TYPES.joinToString(",")
        val url = "https://query1.finance.yahoo.com/ws/fundamentals-timeseries/v1/finance/timeseries/" +
            "$symbol?symbol=$symbol&type=$types&period1=0&period2=4102444800"
        val text = Http.retry(retries = 2, delayMs = 800, label = "Yahoo 港美股财务时间序列") {
            Http.getText(url, Charsets.UTF_8, "https://finance.yahoo.com/", 12_000)
        }
        return parseYahooTimeseriesFinancials(text, security)
    }

    private fun fetchFromTwelveDataFinancials(security: GlobalSecurity): GlobalFundamentals {
        val apiKey = BuildConfig.TWELVE_DATA_API_KEY.trim()
        if (apiKey.isBlank()) throw Http.HttpError("未配置 Twelve Data API Key")
        val symbol = security.symbol.substringBefore('.')
        val exchange = if (security.exchange == "HKEX") "&exchange=HKEX" else ""
        fun request(endpoint: String): String {
            val url = "https://api.twelvedata.com/$endpoint?symbol=" +
                URLEncoder.encode(symbol, "UTF-8") + exchange +
                "&apikey=" + URLEncoder.encode(apiKey, "UTF-8")
            return Http.retry(retries = 2, delayMs = 800, label = "Twelve Data $endpoint") {
                Http.getText(url, Charsets.UTF_8, timeoutMs = 12_000)
            }
        }
        return parseTwelveDataFinancials(
            request("income_statement"),
            request("balance_sheet"),
            request("cash_flow"),
            security,
        )
    }

    private fun fetchFromTwelveData(security: GlobalSecurity): GlobalFundamentals {
        val apiKey = BuildConfig.TWELVE_DATA_API_KEY.trim()
        if (apiKey.isBlank()) throw Http.HttpError("未配置 Twelve Data API Key")
        val symbol = if (security.exchange == "HKEX") {
            "${security.symbol.substringBefore('.').padStart(4, '0')}:XHKG"
        } else {
            security.symbol
        }
        val url = "https://api.twelvedata.com/quote?symbol=" +
            URLEncoder.encode(symbol, "UTF-8") + "&apikey=" + URLEncoder.encode(apiKey, "UTF-8")
        val text = Http.retry(retries = 2, delayMs = 700, label = "Twelve Data 行情") {
            Http.getText(url, Charsets.UTF_8, timeoutMs = 10_000)
        }
        return parseTwelveDataQuote(text, security)
    }

    private fun mergeFundamentals(
        quote: GlobalFundamentals?,
        financial: GlobalFundamentals?,
    ): GlobalFundamentals? {
        if (quote == null) return financial
        if (financial == null) return quote
        val warnings = (quote.dataWarnings + financial.dataWarnings)
            .filterNot {
                it.contains("财务字段暂缺") ||
                    it.contains("等待财务数据源") ||
                    it.contains("财务字段等待财报源补充")
            }
            .distinct()
        return quote.copy(
            revenue = quote.revenue ?: financial.revenue,
            grossMargin = quote.grossMargin ?: financial.grossMargin,
            operatingMargin = quote.operatingMargin ?: financial.operatingMargin,
            profitMargin = quote.profitMargin ?: financial.profitMargin,
            roe = quote.roe ?: financial.roe,
            operatingCashFlow = quote.operatingCashFlow ?: financial.operatingCashFlow,
            freeCashFlow = quote.freeCashFlow ?: financial.freeCashFlow,
            cash = quote.cash ?: financial.cash,
            debt = quote.debt ?: financial.debt,
            latestPeriod = quote.latestPeriod ?: financial.latestPeriod,
            dataSources = (quote.dataSources + financial.dataSources).distinct(),
            dataWarnings = warnings,
            financialCurrency = quote.financialCurrency.takeIf { it.isNotBlank() && it != quote.currency }
                ?: financial.financialCurrency,
            financialBasis = if (quote.financialBasis != "TTM") quote.financialBasis else financial.financialBasis,
        )
    }

    private fun fetchFromTencentHongKongQuote(security: GlobalSecurity): GlobalFundamentals {
        val code = security.symbol.substringBefore('.').padStart(5, '0')
        val text = Http.retry(retries = 2, delayMs = 500, label = "腾讯港股行情") {
            Http.getText(
                "https://qt.gtimg.cn/q=hk$code",
                Charset.forName("GBK"),
                "https://gu.qq.com/",
                8_000,
            )
        }
        return parseTencentHongKongQuote(text, security)
    }

    private fun fetchFromEastmoneyHongKongFinancials(security: GlobalSecurity): GlobalFundamentals {
        val code = security.symbol.substringBefore('.').padStart(5, '0')
        val baseUrl = "https://datacenter.eastmoney.com/securities/api/data/v1/get"
        fun request(reportName: String, columns: String, filter: String): String {
            val query = listOf(
                "reportName" to reportName,
                "columns" to columns,
                "quoteColumns" to "",
                "filter" to filter,
                "pageNumber" to "1",
                "pageSize" to "",
                "sortTypes" to "-1,1",
                "sortColumns" to "REPORT_DATE,STD_ITEM_CODE",
                "source" to "F10",
                "client" to "PC",
                "v" to "01975982096513973",
            ).joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
            }
            return Http.retry(retries = 2, delayMs = 600, label = "东方财富港股 $reportName") {
                Http.getText("$baseUrl?$query", Charsets.UTF_8, "https://quote.eastmoney.com/", 12_000)
            }
        }
        val filter = "(SECUCODE=\"$code.HK\")"
        val columns = "SECUCODE,SECURITY_CODE,SECURITY_NAME_ABBR,ORG_CODE,REPORT_DATE,DATE_TYPE_CODE,FISCAL_YEAR,START_DATE,STD_ITEM_CODE,STD_ITEM_NAME,AMOUNT"
        val balanceColumns = "SECUCODE,SECURITY_CODE,SECURITY_NAME_ABBR,ORG_CODE,REPORT_DATE,DATE_TYPE_CODE,FISCAL_YEAR,STD_ITEM_CODE,STD_ITEM_NAME,AMOUNT"
        return parseEastmoneyHongKongFinancials(
            incomeText = request("RPT_HKF10_FN_INCOME_PC", columns, filter),
            balanceText = request("RPT_HKF10_FN_BALANCE_PC", balanceColumns, filter),
            cashFlowText = request("RPT_HKF10_FN_CASHFLOW_PC", columns, filter),
            security = security,
        )
    }

    private fun fetchFromYahoo(security: GlobalSecurity): GlobalFundamentals {
        val modules = listOf("price", "summaryDetail", "financialData", "assetProfile").joinToString(",")
        val symbol = URLEncoder.encode(security.symbol, "UTF-8")
        val url = "https://query2.finance.yahoo.com/v10/finance/quoteSummary/$symbol?modules=$modules"
        val text = Http.retry(retries = 2, delayMs = 800, label = "Yahoo 港美股基本面") {
            Http.getText(url, Charsets.UTF_8, "https://finance.yahoo.com/", 10_000)
        }
        return parseFundamentals(text, security.symbol, security.name, security)
    }

    private fun fetchFromYahooQuote(security: GlobalSecurity): GlobalFundamentals {
        val symbol = URLEncoder.encode(security.symbol, "UTF-8")
        val text = Http.retry(retries = 2, delayMs = 700, label = "Yahoo 港美股行情摘要") {
            Http.getText(
                "https://query1.finance.yahoo.com/v7/finance/quote?symbols=$symbol",
                Charsets.UTF_8,
                "https://finance.yahoo.com/",
                10_000,
            )
        }
        return parseYahooQuote(text, security)
    }

    private fun fetchFromNasdaq(security: GlobalSecurity): GlobalFundamentals {
        val symbol = URLEncoder.encode(security.symbol.substringBefore('.'), "UTF-8")
        val infoUrl = "https://api.nasdaq.com/api/quote/$symbol/info?assetclass=stocks"
        val summaryUrl = "https://api.nasdaq.com/api/quote/$symbol/summary?assetclass=stocks"
        val info = Http.retry(retries = 2, delayMs = 600, label = "Nasdaq 港美股行情") {
            Http.getText(infoUrl, Charsets.UTF_8, "https://www.nasdaq.com/", 10_000)
        }
        val summary = Http.retry(retries = 2, delayMs = 600, label = "Nasdaq 港美股摘要") {
            Http.getText(summaryUrl, Charsets.UTF_8, "https://www.nasdaq.com/", 10_000)
        }
        return parseNasdaqFundamentals(info, summary, security)
    }

    private fun fetchFromYahooChart(security: GlobalSecurity): GlobalFundamentals {
        val symbol = URLEncoder.encode(security.symbol, "UTF-8")
        val text = Http.retry(retries = 2, delayMs = 700, label = "Yahoo 港美股行情兜底") {
            Http.getText(
                "https://query1.finance.yahoo.com/v8/finance/chart/$symbol?range=5d&interval=1d",
                Charsets.UTF_8,
                "https://finance.yahoo.com/",
                10_000,
            )
        }
        return parseYahooChartQuote(text, security)
    }

    private fun unavailableQuote(security: GlobalSecurity, warning: String): GlobalFundamentals =
        GlobalFundamentals(
            security = security,
            price = null,
            currency = security.currency,
            marketCap = null,
            revenue = null,
            grossMargin = null,
            operatingMargin = null,
            profitMargin = null,
            roe = null,
            operatingCashFlow = null,
            freeCashFlow = null,
            cash = null,
            debt = null,
            pe = null,
            pb = null,
            sector = null,
            industry = null,
            summary = null,
            dataSources = emptyList(),
            dataWarnings = listOf(warning, "所有可用港股行情源均未返回有效数据，暂不能形成有意义的财务结论"),
        )

    companion object {
        fun fallbackSearchResults(keyword: String): List<GlobalSecurity> {
            val query = keyword.trim().lowercase(Locale.US)
            val matches = when (query) {
                "英伟达", "nvidia", "nvda" -> listOf(GlobalSecurity("NVDA", "NVIDIA Corporation", "NASDAQ", "USD"))
                "微软", "microsoft", "msft" -> listOf(GlobalSecurity("MSFT", "Microsoft Corporation", "NASDAQ", "USD"))
                "苹果", "apple", "aapl" -> listOf(GlobalSecurity("AAPL", "Apple Inc.", "NASDAQ", "USD"))
                "特斯拉", "tesla", "tsla" -> listOf(GlobalSecurity("TSLA", "Tesla, Inc.", "NASDAQ", "USD"))
                "谷歌", "google", "alphabet", "goog", "googl" -> listOf(GlobalSecurity("GOOGL", "Alphabet Inc.", "NASDAQ", "USD"))
                "亚马逊", "amazon", "amzn" -> listOf(GlobalSecurity("AMZN", "Amazon.com, Inc.", "NASDAQ", "USD"))
                "台积电", "tsm", "taiwan semiconductor" -> listOf(GlobalSecurity("TSM", "Taiwan Semiconductor Manufacturing Company Limited", "NYSE", "USD"))
                "腾讯", "tencent", "00700", "0700", "00700.hk", "0700.hk" -> listOf(GlobalSecurity("0700.HK", "Tencent Holdings Limited", "HKEX", "HKD"))
                "小米", "小米集团", "xiaomi", "xiaomi corporation", "1810", "01810", "1810.hk", "01810.hk" -> listOf(GlobalSecurity("1810.HK", "Xiaomi Corporation", "HKEX", "HKD"))
                else -> emptyList()
            }
            return matches
        }

        fun fallbackSearchInText(text: String): List<GlobalSecurity> {
            val query = text.trim().lowercase(Locale.US)
            val aliases = listOf(
                listOf("英伟达", "nvidia", "nvda") to GlobalSecurity("NVDA", "NVIDIA Corporation", "NASDAQ", "USD"),
                listOf("微软", "microsoft", "msft") to GlobalSecurity("MSFT", "Microsoft Corporation", "NASDAQ", "USD"),
                listOf("苹果", "apple", "aapl") to GlobalSecurity("AAPL", "Apple Inc.", "NASDAQ", "USD"),
                listOf("特斯拉", "tesla", "tsla") to GlobalSecurity("TSLA", "Tesla, Inc.", "NASDAQ", "USD"),
                listOf("腾讯", "tencent", "00700", "0700") to GlobalSecurity("0700.HK", "Tencent Holdings Limited", "HKEX", "HKD"),
                listOf("小米", "小米集团", "xiaomi", "1810", "01810") to GlobalSecurity("1810.HK", "Xiaomi Corporation", "HKEX", "HKD"),
            )
            return aliases.firstOrNull { (names, _) -> names.any(query::contains) }
                ?.second
                ?.let(::listOf)
                ?: emptyList()
        }

        fun parseSearchResults(text: String): List<GlobalSecurity> {
            val quotes = runCatching { JSONObject(text).optJSONArray("quotes") }.getOrNull() ?: return emptyList()
            val results = mutableListOf<GlobalSecurity>()
            for (index in 0 until quotes.length()) {
                val row = quotes.optJSONObject(index) ?: continue
                if (row.optString("quoteType").uppercase(Locale.US) != "EQUITY") continue
                val symbol = row.optString("symbol").trim()
                val name = row.optString("longname").ifBlank { row.optString("shortname") }.trim()
                if (symbol.isBlank() || name.isBlank()) continue
                val exchange = normalizeExchange(row.optString("exchange"))
                val currency = row.optString("currency").ifBlank { currencyOf(exchange) }
                results += GlobalSecurity(symbol, name, exchange, currency)
            }
            return results.distinctBy { it.symbol }
        }

        fun parseYahooTimeseriesFinancials(
            text: String,
            security: GlobalSecurity,
        ): GlobalFundamentals {
            val result = JSONObject(text).optJSONObject("timeseries")
                ?.optJSONArray("result")
                ?: throw Http.HttpError("Yahoo 财务时间序列返回为空")
            val values = buildMap<String, FinancialPoint> {
                for (index in 0 until result.length()) {
                    val row = result.optJSONObject(index) ?: continue
                    val type = YAHOO_FINANCIAL_TYPES.firstOrNull { row.has(it) } ?: continue
                    val points = row.optJSONArray(type) ?: continue
                    val point = latestFinancialPoint(points) ?: continue
                    put(type, point)
                }
            }
            if (values.isEmpty()) throw Http.HttpError("Yahoo 未返回可用财务字段")

            val revenue = values["trailingTotalRevenue"]?.value
            val grossProfit = values["trailingGrossProfit"]?.value
            val operatingIncome = values["trailingOperatingIncome"]?.value
            val netIncome = values["trailingNetIncome"]?.value
            val operatingCashFlow = values["trailingOperatingCashFlow"]?.value
            val freeCashFlow = values["trailingFreeCashFlow"]?.value
            val capitalExpenditure = values["trailingCapitalExpenditure"]?.value
            val cash = values["trailingCashAndCashEquivalents"]?.value
                ?: values["trailingCashCashEquivalentsAndShortTermInvestments"]?.value
            val debt = values["trailingTotalDebt"]?.value
            val equity = values["trailingStockholdersEquity"]?.value
            val latestPeriod = values.values.mapNotNull { it.asOfDate }.maxOrNull()
            val financialCurrency = values.values.mapNotNull { it.currency }.firstOrNull()
                ?: security.currency
            val actualFreeCashFlow = freeCashFlow
                ?: if (operatingCashFlow != null && capitalExpenditure != null) {
                    operatingCashFlow - kotlin.math.abs(capitalExpenditure)
                } else {
                    null
                }
            val warnings = buildList {
                if (revenue == null) add("收入字段暂缺")
                if (grossProfit == null) add("毛利字段暂缺")
                if (operatingIncome == null) add("营业利润字段暂缺")
                if (netIncome == null) add("净利润字段暂缺")
                if (operatingCashFlow == null) add("经营现金流字段暂缺")
                if (cash == null || debt == null) add("现金或债务字段暂缺")
            }
            return GlobalFundamentals(
                security = security,
                price = null,
                currency = security.currency,
                marketCap = null,
                revenue = revenue,
                grossMargin = ratio(grossProfit, revenue),
                operatingMargin = ratio(operatingIncome, revenue),
                profitMargin = ratio(netIncome, revenue),
                roe = ratio(netIncome, equity),
                operatingCashFlow = operatingCashFlow,
                freeCashFlow = actualFreeCashFlow,
                cash = cash,
                debt = debt,
                pe = null,
                pb = null,
                sector = null,
                industry = null,
                summary = null,
                latestPeriod = latestPeriod,
                dataSources = listOf("Yahoo 财务时间序列"),
                dataWarnings = warnings,
                financialCurrency = financialCurrency,
            )
        }

        fun parseTencentHongKongQuote(
            text: String,
            security: GlobalSecurity,
        ): GlobalFundamentals {
            val payload = text.substringAfter('"', "").substringBeforeLast('"', "")
            val fields = payload.split('~')
            fun field(index: Int): String? = fields.getOrNull(index)?.trim()?.takeIf { it.isNotBlank() }
            val price = field(3)?.toDoubleOrNull()
                ?: throw Http.HttpError("腾讯港股行情没有返回 ${security.name} 的价格")
            val actualSecurity = security.copy(
                symbol = security.symbol.substringBefore('.').padStart(5, '0') + ".HK",
                name = field(1) ?: security.name,
                exchange = "HKEX",
                currency = field(75) ?: security.currency,
            )
            val shares = field(69)?.toDoubleOrNull()
            return GlobalFundamentals(
                security = actualSecurity,
                price = price,
                currency = actualSecurity.currency,
                marketCap = if (shares != null) price * shares else null,
                revenue = null,
                grossMargin = null,
                operatingMargin = null,
                profitMargin = null,
                roe = null,
                operatingCashFlow = null,
                freeCashFlow = null,
                cash = null,
                debt = null,
                pe = field(39)?.toDoubleOrNull(),
                pb = field(50)?.toDoubleOrNull(),
                sector = null,
                industry = null,
                summary = null,
                dataSources = listOf("腾讯港股行情"),
                dataWarnings = listOf("行情已取得，财务字段等待财报源补充"),
            )
        }

        fun parseEastmoneyHongKongFinancials(
            incomeText: String,
            balanceText: String,
            cashFlowText: String,
            security: GlobalSecurity,
        ): GlobalFundamentals {
            val income = parseEastmoneyRows(incomeText)
            val balance = parseEastmoneyRows(balanceText)
            val cashFlow = parseEastmoneyRows(cashFlowText)
            if (income.isEmpty() && balance.isEmpty() && cashFlow.isEmpty()) {
                throw Http.HttpError("东方财富港股财报返回为空")
            }
            val latestDate = (income + balance + cashFlow).map { it.reportDate }.maxOrNull()
            val latestIncome = income.filter { it.reportDate == latestDate }
            val latestBalance = balance.filter { it.reportDate == latestDate }
            val latestCashFlow = cashFlow.filter { it.reportDate == latestDate }
            val revenue = amount(latestIncome, "营业额", "营运收入")
            val grossProfit = amount(latestIncome, "毛利")
            val operatingIncome = amount(latestIncome, "经营溢利")
            val netIncome = amount(latestIncome, "股东应占溢利", "除税后溢利", "持续经营业务税后利润")
            val operatingCashFlow = amount(latestCashFlow, "经营业务现金净额")
            val capex = amount(latestCashFlow, "购建固定资产")
            val cash = amount(latestBalance, "现金及等价物")
            val equity = amount(latestBalance, "股东权益")
            val debt = amount(latestBalance, "借款总额", "总借款", "银行借款", "有息负债")
            val financialCurrency = "CNY"
            val warnings = buildList {
                if (latestDate == null) add("财报期间暂缺")
                if (revenue == null) add("收入字段暂缺")
                if (grossProfit == null) add("毛利字段暂缺")
                if (operatingIncome == null) add("营业利润字段暂缺")
                if (netIncome == null) add("净利润字段暂缺")
                if (operatingCashFlow == null) add("经营现金流字段暂缺")
                if (cash == null) add("现金字段暂缺")
                if (debt == null) add("公司财报未单列可识别的有息债务字段")
                add("港股财报取最新公开报告期，当前不与完整 TTM 混用")
            }
            return GlobalFundamentals(
                security = security,
                price = null,
                currency = security.currency,
                marketCap = null,
                revenue = revenue,
                grossMargin = ratio(grossProfit, revenue),
                operatingMargin = ratio(operatingIncome, revenue),
                profitMargin = ratio(netIncome, revenue),
                roe = ratio(netIncome, equity),
                operatingCashFlow = operatingCashFlow,
                freeCashFlow = if (operatingCashFlow != null && capex != null) {
                    operatingCashFlow - kotlin.math.abs(capex)
                } else {
                    null
                },
                cash = cash,
                debt = debt,
                pe = null,
                pb = null,
                sector = null,
                industry = null,
                summary = null,
                latestPeriod = latestDate,
                dataSources = listOf("东方财富港股财报"),
                dataWarnings = warnings,
                financialCurrency = financialCurrency,
                financialBasis = "最新报告期",
            )
        }

        private data class EastmoneyRow(
            val reportDate: String,
            val name: String,
            val amount: Double?,
        )

        private fun parseEastmoneyRows(text: String): List<EastmoneyRow> {
            val root = JSONObject(text)
            if (!root.optBoolean("success", true)) {
                throw Http.HttpError(root.optString("message").ifBlank { "东方财富财报请求失败" })
            }
            val rows = root.optJSONObject("result")?.optJSONArray("data") ?: return emptyList()
            return buildList {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    val date = row.optString("REPORT_DATE").take(10)
                    val name = row.optString("STD_ITEM_NAME").trim()
                    if (date.isBlank() || name.isBlank()) continue
                    val amount = when (val value = row.opt("AMOUNT")) {
                        is Number -> value.toDouble()
                        is String -> value.replace(",", "").toDoubleOrNull()
                        else -> null
                    }
                    add(EastmoneyRow(date, name, amount))
                }
            }
        }

        private fun amount(rows: List<EastmoneyRow>, vararg names: String): Double? =
            names.firstNotNullOfOrNull { name -> rows.firstOrNull { it.name == name }?.amount }

        fun parseTwelveDataFinancials(
            incomeText: String,
            balanceText: String,
            cashFlowText: String,
            security: GlobalSecurity,
        ): GlobalFundamentals {
            val income = twelveDataRows(incomeText).firstOrNull()
                ?: throw Http.HttpError("Twelve Data 损益表返回为空")
            val balance = twelveDataRows(balanceText).firstOrNull()
                ?: throw Http.HttpError("Twelve Data 资产负债表返回为空")
            val cashFlow = twelveDataRows(cashFlowText).firstOrNull()
                ?: throw Http.HttpError("Twelve Data 现金流量表返回为空")
            val revenue = number(income, "total_revenue", "revenues", "revenue", "sales")
            val grossProfit = number(income, "gross_profit")
            val operatingIncome = number(income, "operating_income", "operating_income_loss")
            val netIncome = number(income, "net_income", "net_income_common_stockholders")
            val operatingCashFlow = number(
                cashFlow,
                "operating_cash_flow",
                "cash_flow_from_operating_activities",
                "net_cash_provided_by_operating_activities",
            )
            val capitalExpenditure = number(cashFlow, "capital_expenditures", "capital_expenditure")
            val freeCashFlow = number(cashFlow, "free_cash_flow")
                ?: if (operatingCashFlow != null && capitalExpenditure != null) {
                    operatingCashFlow - kotlin.math.abs(capitalExpenditure)
                } else {
                    null
                }
            val cash = number(
                balance,
                "cash_and_equivalents",
                "cash_and_cash_equivalents",
                "cash_and_short_term_investments",
                "total_cash",
            )
            val debt = number(balance, "total_debt", "long_term_debt", "short_term_debt")
            val equity = number(balance, "stockholders_equity", "total_stockholder_equity", "total_equity")
            val latestPeriod = listOf(income, balance, cashFlow)
                .mapNotNull { string(it, "fiscal_date", "period_end_date", "date") }
                .maxOrNull()
            val financialCurrency = listOf(income, balance, cashFlow)
                .mapNotNull { string(it, "reported_currency", "currency") }
                .firstOrNull()
                ?: security.currency
            val warnings = buildList {
                if (revenue == null) add("收入字段暂缺")
                if (grossProfit == null) add("毛利字段暂缺")
                if (operatingIncome == null) add("营业利润字段暂缺")
                if (netIncome == null) add("净利润字段暂缺")
                if (operatingCashFlow == null) add("经营现金流字段暂缺")
                if (cash == null || debt == null) add("现金或债务字段暂缺")
            }
            return GlobalFundamentals(
                security = security,
                price = null,
                currency = security.currency,
                marketCap = null,
                revenue = revenue,
                grossMargin = ratio(grossProfit, revenue),
                operatingMargin = ratio(operatingIncome, revenue),
                profitMargin = ratio(netIncome, revenue),
                roe = ratio(netIncome, equity),
                operatingCashFlow = operatingCashFlow,
                freeCashFlow = freeCashFlow,
                cash = cash,
                debt = debt,
                pe = null,
                pb = null,
                sector = null,
                industry = null,
                summary = null,
                latestPeriod = latestPeriod,
                dataSources = listOf("Twelve Data 财务报表"),
                dataWarnings = warnings,
                financialCurrency = financialCurrency,
            )
        }

        private data class FinancialPoint(
            val value: Double,
            val asOfDate: String?,
            val currency: String?,
        )

        private fun latestFinancialPoint(points: JSONArray): FinancialPoint? {
            var latest: FinancialPoint? = null
            for (index in 0 until points.length()) {
                val point = points.optJSONObject(index) ?: continue
                val value = number(point.opt("reportedValue"), "raw", "value")
                    ?: number(point, "raw", "value")
                    ?: continue
                val asOfDate = string(point, "asOfDate", "date", "periodEndDate")
                if (latest == null || (asOfDate ?: "") > (latest.asOfDate ?: "")) {
                    latest = FinancialPoint(
                        value = value,
                        asOfDate = asOfDate,
                        currency = string(point, "currencyCode", "currency"),
                    )
                }
            }
            return latest
        }

        private fun twelveDataRows(text: String): List<JSONObject> {
            val root = JSONObject(text)
            if (root.optString("status").equals("error", ignoreCase = true)) {
                throw Http.HttpError(root.optString("message").ifBlank { "Twelve Data 返回错误" })
            }
            val rows = root.optJSONArray("data") ?: throw Http.HttpError("Twelve Data 财务表返回为空")
            return buildList {
                for (index in 0 until rows.length()) {
                    rows.optJSONObject(index)?.let(::add)
                }
            }
        }

        private fun number(value: Any?, vararg keys: String): Double? {
            return when (value) {
                is JSONObject -> keys.firstNotNullOfOrNull { key -> number(value, key) }
                is Number -> value.toDouble().takeIf(Double::isFinite)
                is String -> value.replace(",", "").trim().toDoubleOrNull()
                else -> null
            }
        }

        private fun number(row: JSONObject, key: String): Double? {
            val value = row.opt(key)
            return when (value) {
                is Number -> value.toDouble().takeIf(Double::isFinite)
                is String -> value.replace(",", "").trim().toDoubleOrNull()
                is JSONObject -> number(value, "raw", "value")
                else -> null
            }
        }

        private fun string(row: JSONObject, vararg keys: String): String? =
            keys.firstNotNullOfOrNull { key ->
                row.optString(key).trim().takeIf { it.isNotBlank() }
            }

        private fun ratio(numerator: Double?, denominator: Double?): Double? =
            if (numerator != null && denominator != null && denominator != 0.0) {
                numerator / denominator
            } else {
                null
            }

        fun parseFundamentals(
            text: String,
            symbol: String,
            name: String,
            security: GlobalSecurity = GlobalSecurity(symbol, name, "", "USD"),
        ): GlobalFundamentals {
            val root = JSONObject(text).optJSONObject("quoteSummary")
                ?.optJSONArray("result")?.optJSONObject(0)
                ?: throw Http.HttpError("港美股基本面返回为空")
            val price = raw(root.obj("price"), "regularMarketPrice")
                ?: raw(root.obj("price"), "regularMarketPreviousClose")
                ?: throw Http.HttpError("没有取到 $name 的当前价格")
            val summary = root.obj("summaryDetail")
            val financial = root.obj("financialData")
            val profile = root.obj("assetProfile")
            val actualSecurity = security.copy(
                symbol = symbol,
                name = name,
                currency = security.currency.ifBlank { root.obj("price")?.optString("currency", "USD") ?: "USD" },
            )
            return GlobalFundamentals(
                security = actualSecurity,
                price = price,
                currency = actualSecurity.currency,
                marketCap = raw(summary, "marketCap"),
                revenue = raw(financial, "totalRevenue"),
                grossMargin = raw(financial, "grossMargins"),
                operatingMargin = raw(financial, "operatingMargins"),
                profitMargin = raw(financial, "profitMargins"),
                roe = raw(financial, "returnOnEquity"),
                operatingCashFlow = raw(financial, "operatingCashflow"),
                freeCashFlow = raw(financial, "freeCashflow"),
                cash = raw(financial, "totalCash"),
                debt = raw(financial, "totalDebt"),
                pe = raw(summary, "trailingPE"),
                pb = raw(summary, "priceToBook"),
                sector = profile?.optString("sector")?.takeIf { it.isNotBlank() },
                industry = profile?.optString("industry")?.takeIf { it.isNotBlank() },
                summary = profile?.optString("longBusinessSummary")?.takeIf { it.isNotBlank() },
                dataSources = listOf("Yahoo Finance"),
            )
        }

        fun parseNasdaqFundamentals(
            infoText: String,
            summaryText: String,
            security: GlobalSecurity,
        ): GlobalFundamentals {
            val info = JSONObject(infoText).optJSONObject("data")
                ?: throw Http.HttpError("Nasdaq行情返回为空")
            val summary = JSONObject(summaryText).optJSONObject("data")
                ?.optJSONObject("summaryData")
                ?: throw Http.HttpError("Nasdaq摘要返回为空")
            val primary = info.optJSONObject("primaryData")
            val price = parseDisplayNumber(primary?.optString("lastSalePrice"))
                ?: parseDisplayNumber(summary.optJSONObject("PreviousClose")?.optString("value"))
                ?: throw Http.HttpError("没有取到 ${security.name} 的当前价格")
            val actualSecurity = security.copy(
                symbol = info.optString("symbol").ifBlank { security.symbol },
                name = info.optString("companyName")
                    .removeSuffix(" Common Stock")
                    .ifBlank { security.name },
                exchange = normalizeExchange(info.optString("exchange")).ifBlank { security.exchange },
                currency = security.currency.ifBlank { "USD" },
            )
            return GlobalFundamentals(
                security = actualSecurity,
                price = price,
                currency = actualSecurity.currency,
                marketCap = parseDisplayNumber(summary.optJSONObject("MarketCap")?.optString("value")),
                revenue = null,
                grossMargin = null,
                operatingMargin = null,
                profitMargin = null,
                roe = null,
                operatingCashFlow = null,
                freeCashFlow = null,
                cash = null,
                debt = null,
                pe = null,
                pb = null,
                sector = summary.optJSONObject("Sector")?.optString("value")?.takeIf { it.isNotBlank() },
                industry = summary.optJSONObject("Industry")?.optString("value")?.takeIf { it.isNotBlank() },
                summary = null,
                dataSources = listOf("Nasdaq 行情"),
                dataWarnings = listOf("Nasdaq 当前仅提供行情、行业和市值字段，财务字段暂缺"),
            )
        }

        fun parseYahooQuote(
            text: String,
            security: GlobalSecurity,
        ): GlobalFundamentals {
            val row = JSONObject(text).optJSONObject("quoteResponse")
                ?.optJSONArray("result")?.optJSONObject(0)
                ?: throw Http.HttpError("Yahoo 行情摘要返回为空")
            val price = raw(row, "regularMarketPrice")
                ?: raw(row, "regularMarketPreviousClose")
                ?: throw Http.HttpError("没有取到 ${security.name} 的当前价格")
            val exchange = normalizeExchange(row.optString("exchange")).ifBlank { security.exchange }
            val actualSecurity = security.copy(
                symbol = row.optString("symbol").ifBlank { security.symbol },
                name = row.optString("longName").ifBlank { row.optString("shortName") }.ifBlank { security.name },
                exchange = exchange,
                currency = security.currency.ifBlank { row.optString("currency").ifBlank { currencyOf(exchange) } },
            )
            return GlobalFundamentals(
                security = actualSecurity,
                price = price,
                currency = actualSecurity.currency,
                marketCap = raw(row, "marketCap"),
                revenue = null,
                grossMargin = null,
                operatingMargin = null,
                profitMargin = null,
                roe = null,
                operatingCashFlow = null,
                freeCashFlow = null,
                cash = null,
                debt = null,
                pe = raw(row, "trailingPE"),
                pb = raw(row, "priceToBook"),
                sector = null,
                industry = null,
                summary = null,
                dataSources = listOf("Yahoo 行情摘要"),
                dataWarnings = listOf("当前仅取得行情和估值字段，财务字段暂缺"),
            )
        }

        fun parseYahooChartQuote(
            text: String,
            security: GlobalSecurity,
        ): GlobalFundamentals {
            val meta = JSONObject(text).optJSONObject("chart")
                ?.optJSONArray("result")?.optJSONObject(0)?.optJSONObject("meta")
                ?: throw Http.HttpError("Yahoo 行情返回为空")
            val price = parseDisplayNumber(meta.optString("regularMarketPrice"))
                ?: parseDisplayNumber(meta.optString("previousClose"))
                ?: throw Http.HttpError("没有取到 ${security.name} 的当前价格")
            val exchange = normalizeExchange(meta.optString("exchangeName")).ifBlank { security.exchange }
            val actualSecurity = security.copy(
                symbol = meta.optString("symbol").ifBlank { security.symbol },
                name = meta.optString("shortName").ifBlank { security.name },
                exchange = exchange,
                currency = security.currency.ifBlank { meta.optString("currency").ifBlank { currencyOf(exchange) } },
            )
            return GlobalFundamentals(
                security = actualSecurity,
                price = price,
                currency = actualSecurity.currency,
                marketCap = null,
                revenue = null,
                grossMargin = null,
                operatingMargin = null,
                profitMargin = null,
                roe = null,
                operatingCashFlow = null,
                freeCashFlow = null,
                cash = null,
                debt = null,
                pe = null,
                pb = null,
                sector = null,
                industry = null,
                summary = null,
                dataSources = listOf("Yahoo 行情"),
                dataWarnings = listOf("当前仅取得行情数据，财务字段暂缺；请稍后重试或补充可用财务数据源"),
            )
        }

        /** 解析 Yahoo chart 的日线 OHLCV；空值行会被丢弃，日期统一为 UTC 的交易日字符串。 */
        fun parseYahooChartHistory(text: String): List<DayBar> {
            val result = JSONObject(text).optJSONObject("chart")
                ?.optJSONArray("result")?.optJSONObject(0)
                ?: throw Http.HttpError("Yahoo 历史行情返回为空")
            val timestamps = result.optJSONArray("timestamp")
                ?: throw Http.HttpError("Yahoo 历史行情缺少日期")
            val quote = result.optJSONObject("indicators")
                ?.optJSONArray("quote")?.optJSONObject(0)
                ?: throw Http.HttpError("Yahoo 历史行情缺少 OHLCV")
            val opens = quote.optJSONArray("open")
            val highs = quote.optJSONArray("high")
            val lows = quote.optJSONArray("low")
            val closes = quote.optJSONArray("close")
            val volumes = quote.optJSONArray("volume")
            if (opens == null || highs == null || lows == null || closes == null || volumes == null) {
                throw Http.HttpError("Yahoo 历史行情字段不完整")
            }
            val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val bars = mutableListOf<DayBar>()
            for (index in 0 until timestamps.length()) {
                val timestamp = timestamps.optLong(index, 0L)
                val open = opens.optDouble(index, Double.NaN)
                val high = highs.optDouble(index, Double.NaN)
                val low = lows.optDouble(index, Double.NaN)
                val close = closes.optDouble(index, Double.NaN)
                val volume = volumes.optDouble(index, Double.NaN)
                if (timestamp <= 0 || !open.isFinite() || !high.isFinite() || !low.isFinite() ||
                    !close.isFinite() || !volume.isFinite() || close <= 0
                ) continue
                bars += DayBar(
                    date = formatter.format(Date(timestamp * 1_000L)),
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = volume,
                )
            }
            if (bars.isEmpty()) throw Http.HttpError("Yahoo 历史行情没有有效日线")
            return bars.sortedBy { it.date }
        }

        fun parseTwelveDataQuote(text: String, security: GlobalSecurity): GlobalFundamentals {
            val root = JSONObject(text)
            if (root.optString("status").equals("error", ignoreCase = true)) {
                throw Http.HttpError(root.optString("message").ifBlank { "Twelve Data 返回错误" })
            }
            val price = root.optString("close").toDoubleOrNull()
                ?: root.optString("previous_close").toDoubleOrNull()
                ?: throw Http.HttpError("Twelve Data 没有返回 ${security.symbol} 的价格")
            return GlobalFundamentals(
                security = security,
                price = price,
                currency = security.currency,
                marketCap = root.optString("market_cap").toDoubleOrNull(),
                revenue = null,
                grossMargin = null,
                operatingMargin = null,
                profitMargin = null,
                roe = null,
                operatingCashFlow = null,
                freeCashFlow = null,
                cash = null,
                debt = null,
                pe = root.optString("pe").toDoubleOrNull(),
                pb = root.optString("pb").toDoubleOrNull(),
                sector = null,
                industry = null,
                summary = null,
                dataSources = listOf("Twelve Data 行情"),
                dataWarnings = listOf("行情源未提供完整财务字段，等待财务数据源补充"),
            )
        }

        fun parseSecTickerResults(text: String, ticker: String): SecEdgarClient.SecCompany? =
            SecEdgarClient.resolveTicker(text, ticker)

        fun parseSecTickerSearchResults(text: String, keyword: String): List<GlobalSecurity> =
            SecEdgarClient.searchCompanies(text, keyword)

        fun parseSecFacts(
            text: String,
            security: GlobalSecurity,
            price: Double?,
        ): GlobalFundamentals = SecEdgarClient.parseCompanyFacts(text, security, price)

        private fun parseDisplayNumber(value: String?): Double? {
            if (value.isNullOrBlank()) return null
            val normalized = value.replace(",", "").replace("$", "").trim()
            return normalized.toDoubleOrNull()
        }

        private fun JSONObject.obj(key: String): JSONObject? = optJSONObject(key)

        private fun raw(parent: JSONObject?, key: String): Double? =
            parent?.optJSONObject(key)?.optDouble("raw", Double.NaN)?.takeIf { it.isFinite() }

        private fun normalizeExchange(value: String): String = when (value.uppercase(Locale.US)) {
            "NMS", "NAS", "NCM" -> "NASDAQ"
            "NYQ", "NYS", "ASE" -> "NYSE"
            "HKG" -> "HKEX"
            else -> value.ifBlank { "海外市场" }
        }

        private fun currencyOf(exchange: String): String = when (exchange) {
            "HKEX" -> "HKD"
            else -> "USD"
        }

        private fun isUsSecurity(security: GlobalSecurity): Boolean =
            security.exchange.equals("US", ignoreCase = true) ||
            security.exchange.equals("NASDAQ", ignoreCase = true) ||
                security.exchange.equals("NYSE", ignoreCase = true) ||
                security.exchange.equals("AMEX", ignoreCase = true)

        private const val SEC_USER_AGENT = "StockMonitorAndroid/1.0 (public-data-client)"
        private val YAHOO_FINANCIAL_TYPES = listOf(
            "trailingTotalRevenue",
            "trailingGrossProfit",
            "trailingOperatingIncome",
            "trailingNetIncome",
            "trailingOperatingCashFlow",
            "trailingFreeCashFlow",
            "trailingCapitalExpenditure",
            "trailingCashAndCashEquivalents",
            "trailingCashCashEquivalentsAndShortTermInvestments",
            "trailingTotalDebt",
            "trailingStockholdersEquity",
        )
    }
}
