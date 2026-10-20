package com.example.stockmonitor

import com.example.stockmonitor.core.GlobalFundamentalsClient
import com.example.stockmonitor.core.GlobalFundamentals
import com.example.stockmonitor.core.GlobalSecurity
import com.example.stockmonitor.analysis.GlobalFundamentalsReport
import com.example.stockmonitor.analysis.StockAnalysis
import com.example.stockmonitor.core.DayBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalFundamentalsClientTest {

    @Test
    fun searchParserKeepsStocksAndMapsExchangeCurrency() {
        val results = GlobalFundamentalsClient.parseSearchResults(
            """{"quotes":[
                {"symbol":"NVDA","shortname":"NVIDIA Corporation","quoteType":"EQUITY","exchange":"NMS","currency":"USD"},
                {"symbol":"NVDA.MX","shortname":"NVIDIA Corp","quoteType":"EQUITY","exchange":"MEX","currency":"MXN"},
                {"symbol":"NVDL","shortname":"GraniteShares 2x Long NVDA Daily ETF","quoteType":"ETF","exchange":"NMS","currency":"USD"}
            ]}"""
        )

        assertEquals(2, results.size)
        assertEquals("NVDA", results[0].symbol)
        assertEquals("NASDAQ", results[0].exchange)
        assertEquals("USD", results[0].currency)
        assertTrue(results.any { it.symbol == "NVDA.MX" })
    }

    @Test
    fun fundamentalsParserReadsFinancialsAndUsesMissingFieldsAsNull() {
        val payload = """
            {
              "quoteSummary":{"result":[{"price":{"regularMarketPrice":{"raw":225.07}},
              "summaryDetail":{"marketCap":{"raw":5420000000000},"trailingPE":{"raw":28.1},"priceToBook":{"raw":23.7}},
              "financialData":{"totalRevenue":{"raw":196000000000},"grossMargins":{"raw":0.75},"operatingMargins":{"raw":0.62},"profitMargins":{"raw":0.49},"returnOnEquity":{"raw":1.03},"operatingCashflow":{"raw":74421000000},"freeCashflow":{"raw":70000000000},"totalCash":{"raw":56586000000},"totalDebt":{"raw":33366000000}},
              "assetProfile":{"sector":"Technology","industry":"Semiconductors","longBusinessSummary":"AI computing platform."}
              }]}
            }
        """.trimIndent()

        val result = GlobalFundamentalsClient.parseFundamentals(payload, "NVDA", "NVIDIA Corporation")

        assertEquals(225.07, result.price!!, 1e-9)
        assertEquals(196_000_000_000.0, result.revenue!!, 1e-9)
        assertEquals(0.75, result.grossMargin!!, 1e-9)
        assertEquals(74_421_000_000.0, result.operatingCashFlow!!, 1e-9)
        assertEquals(28.1, result.pe!!, 1e-9)
        assertEquals("USD", result.currency)
        assertTrue(result.summary!!.contains("AI computing platform"))
    }

    @Test
    fun nasdaqFallbackParserReadsPriceMarketCapAndIndustry() {
        val info = """{"data":{"symbol":"NVDA","companyName":"NVIDIA Corporation Common Stock","exchange":"NASDAQ-GS","primaryData":{"lastSalePrice":"$228.86"}}}"""
        val summary = """{"data":{"summaryData":{"Sector":{"value":"Technology"},"Industry":{"value":"Semiconductors"},"MarketCap":{"value":"5,515,526,000,000"}}}}"""

        val result = GlobalFundamentalsClient.parseNasdaqFundamentals(
            info,
            summary,
            GlobalSecurity("NVDA", "NVIDIA Corporation", "NASDAQ", "USD"),
        )

        assertEquals(228.86, result.price!!, 1e-9)
        assertEquals(5_515_526_000_000.0, result.marketCap!!, 1e-3)
        assertEquals("Technology", result.sector)
        assertEquals("Semiconductors", result.industry)
    }

    @Test
    fun yahooChartFallbackParserReadsHongKongQuote() {
        val result = GlobalFundamentalsClient.parseYahooChartQuote(
            """
                {"chart":{"result":[{"meta":{
                  "symbol":"1810.HK",
                  "shortName":"XIAOMI-W",
                  "exchangeName":"HKG",
                  "currency":"HKD",
                  "regularMarketPrice":18.42,
                  "previousClose":18.10
                }}]}}
            """.trimIndent(),
            GlobalSecurity("1810.HK", "Xiaomi Corporation", "HKEX", "HKD"),
        )

        assertEquals(18.42, result.price!!, 1e-9)
        assertEquals("HKD", result.currency)
        assertEquals("HKEX", result.security.exchange)
        assertTrue(result.dataWarnings.any { it.contains("财务字段暂缺") })
    }

    @Test
    fun yahooChartHistoryParserKeepsDailyOhlcvRows() {
        val bars = GlobalFundamentalsClient.parseYahooChartHistory(
            """
                {"chart":{"result":[{
                  "timestamp":[1726650000,1726736400],
                  "indicators":{
                    "quote":[{"open":[18.0,18.4],"high":[18.6,18.9],"low":[17.8,18.1],"close":[18.3,18.7],"volume":[1200,1500]}]
                  }
                }]}}
            """.trimIndent(),
        )

        assertEquals(2, bars.size)
        assertEquals(18.0, bars[0].open, 1e-9)
        assertEquals(18.6, bars[0].high, 1e-9)
        assertEquals(18.3, bars[0].close, 1e-9)
        assertEquals(1500.0, bars[1].volume, 1e-9)
        assertTrue(bars[0].date < bars[1].date)
    }

    @Test
    fun yahooQuoteParserReadsHongKongPriceAndValuation() {
        val result = GlobalFundamentalsClient.parseYahooQuote(
            """
                {"quoteResponse":{"result":[{
                  "symbol":"1810.HK",
                  "shortName":"XIAOMI-W",
                  "fullExchangeName":"Hong Kong",
                  "currency":"HKD",
                  "regularMarketPrice":{"raw":18.42},
                  "marketCap":{"raw":460000000000},
                  "trailingPE":{"raw":15.2},
                  "priceToBook":{"raw":1.8}
                }]}}
            """.trimIndent(),
            GlobalSecurity("1810.HK", "Xiaomi Corporation", "HKEX", "HKD"),
        )

        assertEquals(18.42, result.price!!, 1e-9)
        assertEquals(460_000_000_000.0, result.marketCap!!, 1e-3)
        assertEquals(15.2, result.pe!!, 1e-9)
        assertEquals("HKD", result.currency)
    }

    @Test
    fun yahooTimeseriesParserReadsHongKongFinancialsAndFinancialCurrency() {
        val result = GlobalFundamentalsClient.parseYahooTimeseriesFinancials(
            """
            {
              "timeseries": {
                "result": [
                  {"meta":{"type":["trailingTotalRevenue"]},"trailingTotalRevenue":[{"asOfDate":"2026-06-30","reportedValue":{"raw":420000000000},"currencyCode":"CNY"}]},
                  {"meta":{"type":["trailingGrossProfit"]},"trailingGrossProfit":[{"asOfDate":"2026-06-30","reportedValue":{"raw":90000000000},"currencyCode":"CNY"}]},
                  {"meta":{"type":["trailingOperatingIncome"]},"trailingOperatingIncome":[{"asOfDate":"2026-06-30","reportedValue":{"raw":30000000000},"currencyCode":"CNY"}]},
                  {"meta":{"type":["trailingNetIncome"]},"trailingNetIncome":[{"asOfDate":"2026-06-30","reportedValue":{"raw":25000000000},"currencyCode":"CNY"}]},
                  {"meta":{"type":["trailingOperatingCashFlow"]},"trailingOperatingCashFlow":[{"asOfDate":"2026-06-30","reportedValue":{"raw":50000000000},"currencyCode":"CNY"}]},
                  {"meta":{"type":["trailingFreeCashFlow"]},"trailingFreeCashFlow":[{"asOfDate":"2026-06-30","reportedValue":{"raw":40000000000},"currencyCode":"CNY"}]},
                  {"meta":{"type":["trailingTotalAssets"]},"trailingTotalAssets":[{"asOfDate":"2026-06-30","reportedValue":{"raw":700000000000},"currencyCode":"CNY"}]},
                  {"meta":{"type":["trailingTotalDebt"]},"trailingTotalDebt":[{"asOfDate":"2026-06-30","reportedValue":{"raw":100000000000},"currencyCode":"CNY"}]},
                  {"meta":{"type":["trailingStockholdersEquity"]},"trailingStockholdersEquity":[{"asOfDate":"2026-06-30","reportedValue":{"raw":200000000000},"currencyCode":"CNY"}]}
                ]
              }
            }
            """.trimIndent(),
            GlobalSecurity("1810.HK", "Xiaomi Corporation", "HKEX", "HKD"),
        )

        assertEquals(420_000_000_000.0, result.revenue!!, 1e-9)
        assertEquals(900_000_000_00.0 / 420_000_000_000.0, result.grossMargin!!, 1e-12)
        assertEquals(30_000_000_000.0 / 420_000_000_000.0, result.operatingMargin!!, 1e-12)
        assertEquals(25_000_000_000.0 / 420_000_000_000.0, result.profitMargin!!, 1e-12)
        assertEquals(50_000_000_000.0, result.operatingCashFlow!!, 1e-9)
        assertEquals(40_000_000_000.0, result.freeCashFlow!!, 1e-9)
        assertEquals(100_000_000_000.0, result.debt!!, 1e-9)
        assertEquals(25_000_000_000.0, result.roe!! * 200_000_000_000.0, 1e-1)
        assertEquals("CNY", result.financialCurrency)
        assertEquals("2026-06-30", result.latestPeriod)
    }

    @Test
    fun tencentHongKongQuoteParserReadsPriceAndValuation() {
        val result = GlobalFundamentalsClient.parseTencentHongKongQuote(
            "v_hk01810=\"100~小米集团-W~01810~25.200~25.860~25.900~146204949.0~0~0~25.200~0~0~0~0~0~0~0~0~0~25.200~0~0~0~0~0~0~0~0~0~146204949.0~2026/09/29 16:08:07~-0.660~-2.55~25.900~25.040~25.200~146204949.0~3689621375.700~0~14.08~~0~0~3.33~5375.4091~6491.5768~XIAOMI-W~0.00~56.550~21.300~1.39~7.55~0~0~0~0~0~17.09~2.10~0.69~200~-35.88~-7.35~GP~12.05~6.30~-4.91~-8.56~9.09~25760225247.00~21330988619.00~19.87~0.000~25.236~-34.98~HKD~1~50\";",
            GlobalSecurity("1810.HK", "Xiaomi Corporation", "HKEX", "HKD"),
        )

        assertEquals(25.2, result.price!!, 1e-9)
        assertEquals(14.08, result.pe!!, 1e-9)
        assertEquals(1.39, result.pb!!, 1e-9)
        assertEquals("HKD", result.currency)
        assertEquals("HKEX", result.security.exchange)
    }

    @Test
    fun eastmoneyHongKongFinancialParserReadsChineseStatementFields() {
        val income = """
            {"success":true,"result":{"data":[
              {"REPORT_DATE":"2026-06-30 00:00:00","STD_ITEM_NAME":"营业额","AMOUNT":208063227000},
              {"REPORT_DATE":"2026-06-30 00:00:00","STD_ITEM_NAME":"毛利","AMOUNT":43419038000},
              {"REPORT_DATE":"2026-06-30 00:00:00","STD_ITEM_NAME":"经营溢利","AMOUNT":16182813000},
              {"REPORT_DATE":"2026-06-30 00:00:00","STD_ITEM_NAME":"股东应占溢利","AMOUNT":14185525000}
            ]}}
        """.trimIndent()
        val balance = """
            {"success":true,"result":{"data":[
              {"REPORT_DATE":"2026-06-30 00:00:00","STD_ITEM_NAME":"现金及等价物","AMOUNT":37252369000},
              {"REPORT_DATE":"2026-06-30 00:00:00","STD_ITEM_NAME":"股东权益","AMOUNT":269699912000}
            ]}}
        """.trimIndent()
        val cashFlow = """
            {"success":true,"result":{"data":[
              {"REPORT_DATE":"2026-06-30 00:00:00","STD_ITEM_NAME":"经营业务现金净额","AMOUNT":2050234000},
              {"REPORT_DATE":"2026-06-30 00:00:00","STD_ITEM_NAME":"购建固定资产","AMOUNT":3773539000}
            ]}}
        """.trimIndent()

        val result = GlobalFundamentalsClient.parseEastmoneyHongKongFinancials(
            income,
            balance,
            cashFlow,
            GlobalSecurity("1810.HK", "Xiaomi Corporation", "HKEX", "HKD"),
        )

        assertEquals(208_063_227_000.0, result.revenue!!, 1e-9)
        assertEquals(43_419_038_000.0 / 208_063_227_000.0, result.grossMargin!!, 1e-12)
        assertEquals(16_182_813_000.0 / 208_063_227_000.0, result.operatingMargin!!, 1e-12)
        assertEquals(14_185_525_000.0 / 208_063_227_000.0, result.profitMargin!!, 1e-12)
        assertEquals(2_050_234_000.0, result.operatingCashFlow!!, 1e-9)
        assertEquals("CNY", result.financialCurrency)
        assertEquals("最新报告期", result.financialBasis)
        assertTrue(result.dataWarnings.any { it.contains("有息债务") })
    }

    @Test
    fun fallbackSearchResolvesNvidiaWithoutYahooSearch() {
        val results = GlobalFundamentalsClient.fallbackSearchResults("英伟达")

        assertEquals(1, results.size)
        assertEquals("NVDA", results.single().symbol)
        assertEquals("NASDAQ", results.single().exchange)
    }

    @Test
    fun secTickerSearchMatchesUsStockByPartialText() {
        val results = GlobalFundamentalsClient.parseSecTickerSearchResults(
            """{"0":{"cik_str":1045810,"ticker":"NVDA","title":"NVIDIA CORP"},"1":{"cik_str":320193,"ticker":"AAPL","title":"Apple Inc."}}""",
            "NVID",
        )

        assertEquals(1, results.size)
        assertEquals("NVDA", results.single().symbol)
        assertEquals("US", results.single().exchange)
    }

    @Test
    fun fallbackSearchResolvesTencentForHongKong() {
        val results = GlobalFundamentalsClient.fallbackSearchResults("腾讯")

        assertEquals("0700.HK", results.single().symbol)
        assertEquals("HKEX", results.single().exchange)
        assertEquals("HKD", results.single().currency)
    }

    @Test
    fun fallbackSearchResolvesXiaomiForHongKong() {
        val results = GlobalFundamentalsClient.fallbackSearchResults("小米集团")

        assertEquals("1810.HK", results.single().symbol)
        assertEquals("HKEX", results.single().exchange)
        assertEquals("HKD", results.single().currency)
    }

    @Test
    fun fallbackSearchFindsXiaomiInsideNaturalLanguage() {
        val results = GlobalFundamentalsClient.fallbackSearchInText("小米集团最近基本面怎么样？")

        assertEquals("1810.HK", results.single().symbol)
    }

    @Test
    fun secTickerParserFindsCompanyCik() {
        val result = GlobalFundamentalsClient.parseSecTickerResults(
            """{"0":{"cik_str":1045810,"ticker":"NVDA","title":"NVIDIA CORP"}}""",
            "NVDA",
        )

        assertEquals("0001045810", result!!.cik)
        assertEquals("NVIDIA CORP", result.name)
    }

    @Test
    fun secFactsParserBuildsMarginsCashFlowAndValuation() {
        val payload = """
            {
              "entityName":"NVIDIA CORP",
              "facts":{"us-gaap":{
                "RevenueFromContractWithCustomerExcludingAssessedTax":{"units":{"USD":[{"filed":"2026-02-20","start":"2025-01-01","end":"2025-12-31","val":1000}]}},
                "CostOfRevenue":{"units":{"USD":[{"filed":"2026-02-20","start":"2025-01-01","end":"2025-12-31","val":400}]}},
                "OperatingIncomeLoss":{"units":{"USD":[{"filed":"2026-02-20","start":"2025-01-01","end":"2025-12-31","val":400}]}},
                "NetIncomeLoss":{"units":{"USD":[{"filed":"2026-02-20","start":"2025-01-01","end":"2025-12-31","val":200}]}},
                "NetCashProvidedByUsedInOperatingActivities":{"units":{"USD":[{"filed":"2026-02-20","start":"2025-01-01","end":"2025-12-31","val":400}]}},
                "PaymentsToAcquirePropertyPlantAndEquipment":{"units":{"USD":[{"filed":"2026-02-20","start":"2025-01-01","end":"2025-12-31","val":150}]}},
                "CashAndCashEquivalentsAtCarryingValue":{"units":{"USD":[{"filed":"2026-02-20","end":"2025-12-31","val":300}]}},
                "LongTermDebtNoncurrent":{"units":{"USD":[{"filed":"2026-02-20","end":"2025-12-31","val":100}]}},
                "StockholdersEquity":{"units":{"USD":[{"filed":"2026-02-20","end":"2025-12-31","val":200}]}},
                "EntityCommonStockSharesOutstanding":{"units":{"shares":[{"filed":"2026-02-20","end":"2025-12-31","val":100}]}},
                "EarningsPerShareDiluted":{"units":{"USD/shares":[{"filed":"2026-02-20","start":"2025-01-01","end":"2025-12-31","val":2.0}]}}
              }}
            }
        """.trimIndent()

        val result = GlobalFundamentalsClient.parseSecFacts(
            payload,
            GlobalSecurity("NVDA", "NVIDIA Corporation", "NASDAQ", "USD"),
            100.0,
        )

        assertEquals(1000.0, result.revenue!!, 1e-9)
        assertEquals(0.6, result.grossMargin!!, 1e-9)
        assertEquals(0.4, result.operatingMargin!!, 1e-9)
        assertEquals(0.2, result.profitMargin!!, 1e-9)
        assertEquals(250.0, result.freeCashFlow!!, 1e-9)
        assertEquals(10_000.0, result.marketCap!!, 1e-9)
        assertEquals(50.0, result.pe!!, 1e-9)
        assertEquals(50.0, result.pb!!, 1e-9)
        assertEquals("SEC Company Facts", result.dataSources.single())
        assertEquals("2025-12-31", result.latestPeriod)
    }

    @Test
    fun secFactsParserAlignsIncomeStatementFactsToOneTtmPeriod() {
        val payload = """
            {
              "entityName":"NVIDIA CORP",
              "facts":{"us-gaap":{
                "RevenueFromContractWithCustomerExcludingAssessedTax":{"units":{"USD":[
                  {"filed":"2022-03-18","start":"2021-02-01","end":"2022-01-30","val":100}
                ]}},
                "Revenues":{"units":{"USD":[
                  {"filed":"2026-02-01","start":"2025-01-01","end":"2025-12-31","val":1000},
                  {"filed":"2025-08-01","start":"2025-01-01","end":"2025-06-30","val":400},
                  {"filed":"2026-08-01","start":"2026-01-01","end":"2026-06-30","val":700}
                ]}},
                "CostOfRevenue":{"units":{"USD":[
                  {"filed":"2026-02-01","start":"2025-01-01","end":"2025-12-31","val":800},
                  {"filed":"2025-08-01","start":"2025-01-01","end":"2025-06-30","val":160},
                  {"filed":"2026-07-01","start":"2026-01-01","end":"2026-06-30","val":300}
                ]}},
                "OperatingIncomeLoss":{"units":{"USD":[
                  {"filed":"2026-02-01","start":"2025-01-01","end":"2025-12-31","val":200},
                  {"filed":"2025-08-01","start":"2025-01-01","end":"2025-06-30","val":80},
                  {"filed":"2026-08-01","start":"2026-01-01","end":"2026-06-30","val":160}
                ]}},
                "NetIncomeLoss":{"units":{"USD":[
                  {"filed":"2026-02-01","start":"2025-01-01","end":"2025-12-31","val":100},
                  {"filed":"2025-08-01","start":"2025-01-01","end":"2025-06-30","val":50},
                  {"filed":"2026-08-01","start":"2026-01-01","end":"2026-06-30","val":100}
                ]}},
                "NetCashProvidedByUsedInOperatingActivities":{"units":{"USD":[{"filed":"2026-08-01","start":"2026-01-01","end":"2026-06-30","val":240}]}},
                "PaymentsToAcquirePropertyPlantAndEquipment":{"units":{"USD":[{"filed":"2026-08-01","start":"2026-01-01","end":"2026-06-30","val":40}]}},
                "CashAndCashEquivalentsAtCarryingValue":{"units":{"USD":[{"filed":"2026-08-01","end":"2026-06-30","val":300}]}},
                "LongTermDebtNoncurrent":{"units":{"USD":[{"filed":"2026-08-01","end":"2026-06-30","val":100}]}},
                "StockholdersEquity":{"units":{"USD":[{"filed":"2026-08-01","end":"2026-06-30","val":200}]}},
                "EntityCommonStockSharesOutstanding":{"units":{"shares":[{"filed":"2026-08-01","end":"2026-06-30","val":100}]}}
              }}
            }
        """.trimIndent()

        val result = GlobalFundamentalsClient.parseSecFacts(
            payload,
            GlobalSecurity("NVDA", "NVIDIA Corporation", "NASDAQ", "USD"),
            100.0,
        )

        assertEquals(1300.0, result.revenue!!, 1e-9)
        assertEquals(0.2769230769, result.grossMargin!!, 1e-9)
        assertEquals(0.2153846153, result.operatingMargin!!, 1e-9)
        assertEquals(0.1153846153, result.profitMargin!!, 1e-9)
        assertEquals("2026-06-30", result.latestPeriod)
    }

    @Test
    fun globalReportShowsSourcePeriodAndMissingDataWarning() {
        val report = GlobalFundamentalsReport.format(
            GlobalFundamentalsClient.parseNasdaqFundamentals(
                """{"data":{"symbol":"NVDA","companyName":"NVIDIA Corporation Common Stock","exchange":"NASDAQ-GS","primaryData":{"lastSalePrice":"$228.86"}}}""",
                """{"data":{"summaryData":{"Sector":{"value":"Technology"},"Industry":{"value":"Semiconductors"},"MarketCap":{"value":"5,515,526,000,000"}}}}""",
                GlobalSecurity("NVDA", "NVIDIA Corporation", "NASDAQ", "USD"),
            ),
            "2026-09-29 12:00",
        )

        assertTrue(report.contains("数据来源：Nasdaq 行情"))
        assertTrue(report.contains("数据完整度提醒"))
        assertTrue(report.contains("财务字段暂缺"))
        assertTrue(report.contains("TTM"))
        assertTrue(report.contains("资产负债表字段取最新期末值"))
    }

    @Test
    fun globalReportFormatsLargeUsdAmountsWithReadableUnits() {
        val report = GlobalFundamentalsReport.format(
            GlobalFundamentals(
                security = GlobalSecurity("NVDA", "NVIDIA Corporation", "NASDAQ", "USD"),
                price = 225.07,
                currency = "USD",
                marketCap = 5_500_000_000_000.0,
                revenue = 196_000_000_000.0,
                grossMargin = 0.75,
                operatingMargin = 0.62,
                profitMargin = 0.49,
                roe = 1.03,
                operatingCashFlow = 74_421_000_000.0,
                freeCashFlow = 70_000_000_000.0,
                cash = 56_586_000_000.0,
                debt = 33_366_000_000.0,
                pe = 28.1,
                pb = 23.7,
                sector = "Technology",
                industry = "Semiconductors",
                summary = "AI computing platform.",
                latestPeriod = "2026-06-30",
                dataSources = listOf("SEC Company Facts"),
            ),
            "2026-09-29 17:00",
        )

        assertTrue(report.contains("5.50万亿美元"))
        assertTrue(report.contains("1960.00亿美元"))
        assertTrue(report.contains("744.21亿美元"))
        assertTrue(report.contains("基本面合理估值："))
        assertTrue(report.contains("美元"))
        assertTrue(report.contains("估值范围依据"))
    }

    @Test
    fun globalReportExplainsWhenFairValueRangeCannotBeCalculated() {
        val report = GlobalFundamentalsReport.format(
            GlobalFundamentals(
                security = GlobalSecurity("TEST", "测试公司", "NASDAQ", "USD"),
                price = 20.0,
                currency = "USD",
                marketCap = null,
                revenue = 100_000_000.0,
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
            ),
            "2026-09-30 12:00",
        )

        assertTrue(report.contains("基本面合理估值：暂不出区间"))
        assertTrue(report.contains("缺少可用的 PE/PB 估值锚"))
    }

    @Test
    fun globalReportIncludesTechnicalKlineAndEstimatedChipSectionWhenAvailable() {
        val bars = javaClass.classLoader!!.getResourceAsStream("kline_sh600089.csv")!!
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split(",")
                DayBar(parts[0], parts[1].toDouble(), parts[2].toDouble(), parts[3].toDouble(), parts[4].toDouble(), parts[5].toDouble())
            }
        val technical = StockAnalysis.analyze(bars, "1810.HK", "小米集团")
        val report = GlobalFundamentalsReport.format(
            GlobalFundamentals(
                security = GlobalSecurity("1810.HK", "小米集团", "HKEX", "HKD"),
                price = technical.price,
                currency = "HKD",
                marketCap = null,
                revenue = 100_000_000.0,
                grossMargin = 0.2,
                operatingMargin = 0.1,
                profitMargin = 0.08,
                roe = 0.12,
                operatingCashFlow = 10_000_000.0,
                freeCashFlow = 8_000_000.0,
                cash = 20_000_000.0,
                debt = 10_000_000.0,
                pe = 18.0,
                pb = 2.0,
                sector = "Technology",
                industry = "Consumer Electronics",
                summary = "测试公司",
            ),
            "2026-09-30 12:00",
            technical,
        )

        assertTrue(report.contains("技术面与 K 线") )
        assertTrue(report.contains("MA5"))
        assertTrue(report.contains("MACD"))
        assertTrue(report.contains("技术支撑/压力"))
        assertTrue(report.contains("成交密集区估算"))
        assertTrue(report.contains("不是券商真实持仓筹码"))
    }
}
