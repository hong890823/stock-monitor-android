package com.example.stockmonitor.core

import com.example.stockmonitor.analysis.BalanceSheetSnapshot
import com.example.stockmonitor.analysis.Announcement
import com.example.stockmonitor.analysis.FinancialPeriod
import com.example.stockmonitor.analysis.Governance
import com.example.stockmonitor.analysis.MainBusinessItem
import com.example.stockmonitor.analysis.PeerContext
import com.example.stockmonitor.analysis.ShareholderAnalysis
import com.example.stockmonitor.analysis.ShareholderDisclosure
import com.example.stockmonitor.analysis.InstitutionalPosition
import com.example.stockmonitor.analysis.ValuationPoint
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 个股基本面数据包：三张表 + 估值序列 + 同业对比 + 主营构成 + 实时快照。
 *
 * [price] 与 [totalShares] 来自实时行情；行情不可达时会退回日线收盘价与估值表反推的股本。
 */
data class FundamentalPayload(
    val code: String,
    val name: String,
    val price: Double,
    val totalShares: Double,
    val periods: List<FinancialPeriod>,
    val balance: BalanceSheetSnapshot?,
    val valuations: List<ValuationPoint>,
    val peers: PeerContext?,
    val mainBusiness: List<MainBusinessItem>,
    val announcements: List<Announcement>,
    val shareholder: ShareholderAnalysis?,
    /** 东财 F10 的企业类型（如 证券 / 银行 / 保险）：金融企业报表结构不同，评分要换口径。 */
    val orgType: String? = null,
)

/**
 * 基本面数据来源，全部是东方财富的公开接口：
 *
 * - `RPT_F10_FINANCE_GINCOME` / `GCASHFLOW` / `GBALANCE`：利润表、现金流量表、资产负债表（按报告期）。
 *   三家表都返回**年初至今累计值**，差分还原单季交给 [com.example.stockmonitor.analysis.Fundamentals]。
 * - `RPT_VALUEANALYSIS_DET`：日频估值序列，覆盖 2018 年至今两千多个交易日；
 *   同一张表按 `BOARD_CODE` 过滤就是整个板块当天的估值快照，用来算板块中位与市值排名。
 * - `RPT_F10_FN_MAINOP`：主营业务分产品/分地区/分行业构成。
 * - `push2` 的 `stock/get`：现价、总股本、行业。
 *
 * 三张表里利润表和现金流量表是必需的（缺了现金流量表就算不出利润含金量），
 * 资产负债表、估值序列、同业、主营构成都是尽力而为：拿不到就降级，不编数字。
 */
class FundamentalsClient {
    private val referer = "https://emweb.securities.eastmoney.com/"

    fun fetch(code: String, name: String): FundamentalPayload {
        val secucode = secucodeOf(code)
        val plain = code.takeLast(6)

        val income = fetchF10(REPORT_INCOME, INCOME_COLUMNS, secucode, PERIOD_PAGE_SIZE)
        val cash = fetchF10(REPORT_CASHFLOW, CASHFLOW_COLUMNS, secucode, PERIOD_PAGE_SIZE)
        val periods = parsePeriods(income, cash)
        if (periods.isEmpty()) throw Http.HttpError("没有取到 $name 的财报数据")

        val balance = runCatching {
            parseBalance(fetchF10(REPORT_BALANCE, BALANCE_COLUMNS, secucode, 1))
        }.getOrNull()

        val snapshot = runCatching { fetchSnapshot(code) }.getOrNull()
        val (valuations, board) = runCatching { fetchValuations(plain) }.getOrDefault(emptyList<ValuationPoint>() to null)

        val peers = if (board != null) {
            runCatching { fetchPeers(plain, board.first, board.second) }.getOrNull()
        } else {
            null
        }

        val price = snapshot?.price?.takeIf { it > 0 }
            ?: runCatching { SinaClient().quote(code)?.price }.getOrNull()?.takeIf { it > 0 }
            ?: throw Http.HttpError("没有取到 $name 的当前价格")
        // 行情接口没给总股本时，用估值表的总市值除以现价反推，比直接放弃股本好。
        val shares = snapshot?.totalShares?.takeIf { it > 0 } ?: sharesFromMarketCap(peers?.marketCap, price)

        val mainBusiness = runCatching {
            parseMainBusiness(fetchF10(REPORT_MAINOP, MAINOP_COLUMNS, secucode, 40))
        }.getOrDefault(emptyList())
        val announcements = runCatching { fetchAnnouncements(plain) }.getOrDefault(emptyList())
        val shareholder = runCatching { fetchShareholderAnalysis(plain, price) }.getOrNull()

        return FundamentalPayload(
            code = code,
            name = name,
            price = price,
            totalShares = shares,
            periods = periods,
            balance = balance,
            valuations = valuations,
            peers = peers,
            mainBusiness = mainBusiness,
            announcements = announcements,
            shareholder = shareholder,
            orgType = parseOrgType(income),
        )
    }

    private fun sharesFromMarketCap(marketCap: Double?, price: Double): Double {
        if (marketCap == null || marketCap <= 0 || price <= 0) return 0.0
        return marketCap / price
    }

    /** 只拉公告清单（事件后蓄势识别用）：比完整基本面轻一个量级，按需补拉走这里。 */
    fun announcements(code: String): List<Announcement> =
        runCatching { fetchAnnouncements(code.takeLast(6)) }.getOrDefault(emptyList())

    data class Snapshot(val name: String, val price: Double, val totalShares: Double)

    /** 实时快照：现价、总股本。不带 fltt=2 的整数会被放大 100 倍，必须带上。 */
    private fun fetchSnapshot(code: String): Snapshot? {
        val url = "https://push2.eastmoney.com/api/qt/stock/get?secid=${secidOf(code)}&fltt=2" +
            "&fields=f43,f58,f84,f127"
        val text = Http.retry(retries = 2, delayMs = 800, label = "基本面快照") {
            Http.getText(url, Charsets.UTF_8, "https://quote.eastmoney.com/", 8_000)
        }
        val data = JSONObject(text).optJSONObject("data") ?: return null
        val price = data.optDouble("f43", Double.NaN)
        val shares = data.optDouble("f84", Double.NaN)
        if (price.isNaN() || price <= 0) return null
        return Snapshot(
            name = data.optString("f58"),
            price = price,
            totalShares = if (shares.isNaN()) 0.0 else shares,
        )
    }

    private fun fetchF10(
        report: String,
        columns: String,
        secucode: String,
        pageSize: Int,
        sortColumn: String = "REPORT_DATE",
    ): String {
        val url = "https://datacenter.eastmoney.com/securities/api/data/v1/get?reportName=$report" +
            "&columns=$columns&filter=(SECUCODE%3D%22$secucode%22)&pageSize=$pageSize" +
            "&sortColumns=$sortColumn&sortTypes=-1&source=HSF10&client=PC"
        return Http.retry(retries = 2, delayMs = 800, label = report) {
            Http.getText(url, Charsets.UTF_8, referer, 20_000)
        }
    }

    private fun fetchShareholderAnalysis(plain: String, price: Double): ShareholderAnalysis? {
        val url = "https://vip.stock.finance.sina.com.cn/corp/go.php/vCI_StockHolder/stockid/$plain.phtml"
        val html = Http.retry(retries = 2, delayMs = 800, label = "股东结构") {
            Http.getText(url, Charset.forName("GB18030"), "https://vip.stock.finance.sina.com.cn/", 20_000)
        }
        val disclosures = parseShareholderPage(html)
        return ShareholderAnalysis.from(
            latest = disclosures.getOrNull(0),
            previous = disclosures.getOrNull(1),
            currentPrice = price,
        )
    }

    /**
     * 日频估值序列 + 所属板块。
     *
     * 一天一行，两千多行一次就能拉完（pageSize 上限实测够用）；分页只是防止接口调小上限。
     */
    private fun fetchValuations(plain: String): Pair<List<ValuationPoint>, Pair<String, String>?> {
        val all = mutableListOf<ValuationPoint>()
        var board: Pair<String, String>? = null
        var count = Int.MAX_VALUE
        var page = 1
        while (page <= MAX_VALUATION_PAGES && all.size < count) {
            val text = Http.retry(retries = 2, delayMs = 800, label = "估值序列") {
                Http.getText(valuationUrl(plain, page), Charsets.UTF_8, referer, 20_000)
            }
            val rows = parseValuations(text)
            if (rows.isEmpty()) break
            if (board == null) board = parseBoard(text)
            all += rows
            count = totalCount(text)
            page++
        }
        return all.sortedBy { it.date } to board
    }

    private fun fetchPeers(plain: String, boardCode: String, boardName: String): PeerContext? {
        val latest = latestValuationDate(plain) ?: return null
        val url = "https://datacenter-web.eastmoney.com/api/data/v1/get?reportName=RPT_VALUEANALYSIS_DET" +
            "&columns=SECURITY_CODE,PE_TTM,PB_MRQ,TOTAL_MARKET_CAP" +
            "&filter=(BOARD_CODE%3D%22$boardCode%22)(TRADE_DATE%3D%27$latest%27)" +
            "&pageSize=500&page=1&sortColumns=TOTAL_MARKET_CAP&sortTypes=-1"
        val text = Http.retry(retries = 2, delayMs = 800, label = "同业对比") {
            Http.getText(url, Charsets.UTF_8, referer, 20_000)
        }
        return parsePeers(text, plain, boardName)
    }

    /** 板块快照只取最新一个交易日，先问一次这只票的最新估值日期。 */
    private fun latestValuationDate(plain: String): String? {
        val text = Http.getText(valuationUrl(plain, 1, pageSize = 1), Charsets.UTF_8, referer, 12_000)
        val rows = dataArray(text) ?: return null
        val date = rows.optJSONObject(0)?.optString("TRADE_DATE").orEmpty()
        return date.take(10).takeIf { it.length == 10 }
    }

    /**
     * 公告列表，按时间倒序。治理风险（质押、处罚、问询）只在公告里，财报看不到。
     *
     * 只取第一页是不够的：公告密集的公司 100 条只覆盖三四周，而治理窗口是半年，
     * 那样报告里的「近 180 天」就是虚的。所以按页往回翻，直到最旧一条越过窗口或翻到上限。
     */
    private fun fetchAnnouncements(plain: String): List<Announcement> {
        val all = mutableListOf<Announcement>()
        for (page in 1..MAX_ANNOUNCEMENT_PAGES) {
            val url = "https://np-anotice-stock.eastmoney.com/api/security/ann?sr=-1" +
                "&page_size=$ANNOUNCEMENT_PAGE_SIZE&page_index=$page&ann_type=A&client_source=web" +
                "&stock_list=$plain&f_node=0&s_node=0"
            val text = Http.retry(retries = 2, delayMs = 800, label = "公告") {
                Http.getText(url, Charsets.UTF_8, "https://data.eastmoney.com/", 15_000)
            }
            val rows = parseAnnouncements(text)
            if (rows.isEmpty()) break
            all += rows
            // 没填满一页说明已经翻到头了；覆盖满窗口就不用再往回翻。
            if (rows.size < ANNOUNCEMENT_PAGE_SIZE) break
            if (announcementWindowCovered(all)) break
        }
        val unique = all.distinctBy { it.date to it.title }.sortedByDescending { it.date }
        val referenceDate = unique.maxOfOrNull { it.date }.orEmpty()
        val detailKeys = Governance.scan(unique, referenceDate)
            .map { it.date to it.title }
            .toSet()
        return unique.map { item ->
            if ((item.date to item.title) in detailKeys) {
                item.copy(content = fetchAnnouncementContent(item))
            } else {
                item
            }
        }
    }

    /** 只为标题筛选出的重点公告请求正文，避免一次基本面分析请求数百条详情。 */
    private fun fetchAnnouncementContent(item: Announcement): String? {
        val artCode = item.artCode ?: return null
        val pages = mutableListOf<String>()
        for (page in 1..MAX_ANNOUNCEMENT_DETAIL_PAGES) {
            val content = runCatching {
                val url = "https://np-cnotice-wap.eastmoney.com/api/content/ann/rich?" +
                    "client_source=wap&page_index=$page&is_rich=1&art_code=$artCode&business=WapGonggao"
                val text = Http.retry(retries = 1, label = "公告正文") {
                    Http.getText(url, Charsets.UTF_8, "https://data.eastmoney.com/", 12_000)
                }
                parseAnnouncementContent(text)
            }.getOrNull()
            if (content.isNullOrBlank()) {
                if (pages.isNotEmpty()) break
                continue
            }
            pages += content
        }
        return pages.joinToString("\n")
            .takeIf { it.isNotBlank() }
    }

    private fun valuationUrl(plain: String, page: Int, pageSize: Int = VALUATION_PAGE_SIZE): String =
        "https://datacenter-web.eastmoney.com/api/data/v1/get?reportName=RPT_VALUEANALYSIS_DET" +
            "&columns=TRADE_DATE,PE_TTM,PB_MRQ,TOTAL_MARKET_CAP,BOARD_CODE,BOARD_NAME" +
            "&filter=(SECURITY_CODE%3D%22$plain%22)&pageSize=$pageSize&page=$page" +
            "&sortColumns=TRADE_DATE&sortTypes=-1"

    companion object {
        private const val REPORT_INCOME = "RPT_F10_FINANCE_GINCOME"
        private const val REPORT_CASHFLOW = "RPT_F10_FINANCE_GCASHFLOW"
        private const val REPORT_BALANCE = "RPT_F10_FINANCE_GBALANCE"
        private const val REPORT_MAINOP = "RPT_F10_FN_MAINOP"

        private const val INCOME_COLUMNS =
            "SECUCODE,REPORT_DATE,ORG_TYPE,TOTAL_OPERATE_INCOME,OPERATE_COST,PARENT_NETPROFIT,DEDUCT_PARENT_NETPROFIT"
        private const val CASHFLOW_COLUMNS =
            "SECUCODE,REPORT_DATE,NETCASH_OPERATE,SALES_SERVICES,CONSTRUCT_LONG_ASSET"
        private const val BALANCE_COLUMNS = "SECUCODE,REPORT_DATE,MONETARYFUNDS,NOTE_ACCOUNTS_RECE," +
            "ACCOUNTS_RECE,TOTAL_OTHER_RECE,OTHER_RECE,INVENTORY,GOODWILL,SHORT_LOAN,LONG_LOAN," +
            "BOND_PAYABLE,LEASE_LIAB,TOTAL_ASSETS,TOTAL_LIABILITIES,TOTAL_PARENT_EQUITY,MINORITY_EQUITY"
        private const val MAINOP_COLUMNS =
            "SECUCODE,REPORT_DATE,MAINOP_TYPE,ITEM_NAME,MAIN_BUSINESS_INCOME,MBI_RATIO,GROSS_RPOFIT_RATIO"

        /** 12 个报告期够算 8 个单季 + 同比；多要两期防止接口漏期。 */
        private const val PERIOD_PAGE_SIZE = 16
        /** 公告每页条数；翻页直到覆盖 [Governance.WINDOW_DAYS] 那么长的窗口。 */
        private const val ANNOUNCEMENT_PAGE_SIZE = 100
        /** 翻页上限：覆盖半年窗口最多用到的页数，防止接口异常时无限翻。 */
        private const val MAX_ANNOUNCEMENT_PAGES = 4
        /** 单条公告正文的分页上限；第一页常是目录，正文从后续页返回。 */
        private const val MAX_ANNOUNCEMENT_DETAIL_PAGES = 8
        private const val VALUATION_PAGE_SIZE = 3000
        private const val MAX_VALUATION_PAGES = 3

        fun secucodeOf(code: String): String = code.takeLast(6) + "." + marketSuffix(code)

        fun secidOf(code: String): String = (if (code.startsWith("sh")) "1." else "0.") + code.takeLast(6)

        private fun marketSuffix(code: String): String = when {
            code.startsWith("sh") -> "SH"
            code.startsWith("bj") -> "BJ"
            else -> "SZ"
        }

        private fun shortDate(raw: String): String = raw.take(10)

        private fun totalCount(text: String): Int =
            runCatching { JSONObject(text).optJSONObject("result")?.optInt("count", 0) ?: 0 }.getOrDefault(0)

        private fun dataArray(text: String): JSONArray? =
            runCatching { JSONObject(text).optJSONObject("result")?.optJSONArray("data") }.getOrNull()

        private fun median(values: List<Double>): Double? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
        }

        /**
         * 利润表和现金流量表按报告期对齐拼成一条记录。
         *
         * 现金流缺失的报告期也保留，现金三项记 null 而不是 0——金融企业（银行/券商/保险）
         * 的现金流量表在 F10 接口上就是空的，整表缺失时利润表照样能出营收/利润口径；
         * 「缺表」不能记成「现金流为 0」，否则「利润含金量」会被算成最差，把没数据说成报表烂。
         */
        fun parsePeriods(income: String, cash: String): List<FinancialPeriod> {
            val incomeRows = dataArray(income) ?: return emptyList()
            val cashRows = dataArray(cash)
            val cashByDate = HashMap<String, JSONObject>()
            if (cashRows != null) {
                for (i in 0 until cashRows.length()) {
                    val row = cashRows.optJSONObject(i) ?: continue
                    cashByDate[shortDate(row.optString("REPORT_DATE"))] = row
                }
            }
            val periods = mutableListOf<FinancialPeriod>()
            for (i in 0 until incomeRows.length()) {
                val row = incomeRows.optJSONObject(i) ?: continue
                val date = shortDate(row.optString("REPORT_DATE"))
                if (date.length != 10) continue
                val cashRow = cashByDate[date]
                periods.add(
                    FinancialPeriod(
                        date = date,
                        revenue = row.optDouble("TOTAL_OPERATE_INCOME", 0.0),
                        operateCost = row.optDouble("OPERATE_COST", 0.0),
                        netProfit = row.optDouble("PARENT_NETPROFIT", 0.0),
                        deductNetProfit = row.optDouble("DEDUCT_PARENT_NETPROFIT", 0.0),
                        operatingCashFlow = cashRow?.optDouble("NETCASH_OPERATE", 0.0),
                        salesCash = cashRow?.optDouble("SALES_SERVICES", 0.0),
                        capex = cashRow?.optDouble("CONSTRUCT_LONG_ASSET", 0.0),
                    )
                )
            }
            return periods.sortedBy { it.date }
        }

        /** 企业类型（证券/银行/保险…）；金融企业的报表结构不同，评分口径要区分。 */
        fun parseOrgType(income: String): String? =
            dataArray(income)?.optJSONObject(0)?.optString("ORG_TYPE")?.takeIf { it.isNotBlank() }

        fun parseBalance(text: String): BalanceSheetSnapshot? {
            val row = dataArray(text)?.optJSONObject(0) ?: return null
            val receivable = row.optDouble("NOTE_ACCOUNTS_RECE", Double.NaN).takeIf { !it.isNaN() }
                ?: row.optDouble("ACCOUNTS_RECE", 0.0)
            val otherReceivable = row.optDouble("TOTAL_OTHER_RECE", Double.NaN).takeIf { !it.isNaN() }
                ?: row.optDouble("OTHER_RECE", 0.0)
            return BalanceSheetSnapshot(
                date = shortDate(row.optString("REPORT_DATE")),
                monetaryFunds = row.optDouble("MONETARYFUNDS", 0.0),
                accountsReceivable = receivable,
                otherReceivable = otherReceivable,
                inventory = row.optDouble("INVENTORY", 0.0),
                goodwill = row.optDouble("GOODWILL", 0.0),
                shortLoan = row.optDouble("SHORT_LOAN", 0.0),
                longLoan = row.optDouble("LONG_LOAN", 0.0),
                bondPayable = row.optDouble("BOND_PAYABLE", 0.0),
                leaseLiability = row.optDouble("LEASE_LIAB", 0.0),
                totalAssets = row.optDouble("TOTAL_ASSETS", 0.0),
                totalLiabilities = row.optDouble("TOTAL_LIABILITIES", 0.0),
                parentEquity = row.optDouble("TOTAL_PARENT_EQUITY", 0.0),
                minorityEquity = row.optDouble("MINORITY_EQUITY", 0.0),
            )
        }

        /** 单日估值。PE / PB 为负或缺失时保留 null，负 PE 不能参与历史分位。 */
        fun parseValuations(text: String): List<ValuationPoint> {
            val rows = dataArray(text) ?: return emptyList()
            val points = mutableListOf<ValuationPoint>()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val date = shortDate(row.optString("TRADE_DATE"))
                if (date.length != 10) continue
                points.add(
                    ValuationPoint(
                        date = date,
                        peTtm = row.optDouble("PE_TTM", Double.NaN).takeIf { !it.isNaN() },
                        pbMrq = row.optDouble("PB_MRQ", Double.NaN).takeIf { !it.isNaN() },
                    )
                )
            }
            return points
        }

        /** 表里的板块字段（取第一条，即最新一天）。 */
        fun parseBoard(text: String): Pair<String, String>? {
            val row = dataArray(text)?.optJSONObject(0) ?: return null
            val code = row.optString("BOARD_CODE")
            val name = row.optString("BOARD_NAME")
            if (code.isBlank()) return null
            return code to name
        }

        /**
         * 板块快照：市值排名 + PE/PB 中位。
         *
         * 接口已按总市值降序返回，所以行号就是排名。中位数只统计正 PE——亏损股算出来的
         * 负 PE 会把中位数拉低，看着像「板块很便宜」。
         */
        fun parsePeers(text: String, plain: String, boardName: String): PeerContext? {
            val rows = dataArray(text) ?: return null
            if (rows.length() == 0) return null
            val pe = mutableListOf<Double>()
            val pb = mutableListOf<Double>()
            var rank: Int? = null
            var marketCap: Double? = null
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val rowPe = row.optDouble("PE_TTM", Double.NaN)
                if (!rowPe.isNaN() && rowPe > 0) pe.add(rowPe)
                val rowPb = row.optDouble("PB_MRQ", Double.NaN)
                if (!rowPb.isNaN() && rowPb > 0) pb.add(rowPb)
                if (row.optString("SECURITY_CODE") == plain) {
                    rank = i + 1
                    marketCap = row.optDouble("TOTAL_MARKET_CAP", Double.NaN).takeIf { !it.isNaN() }
                }
            }
            return PeerContext(
                boardName = boardName,
                total = totalCount(text).coerceAtLeast(rows.length()),
                sampleSize = pe.size,
                marketCapRank = rank,
                peMedian = median(pe),
                pbMedian = median(pb),
                marketCap = marketCap,
            )
        }

        fun parseMainBusiness(text: String): List<MainBusinessItem> {
            val rows = dataArray(text) ?: return emptyList()
            val items = mutableListOf<MainBusinessItem>()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val date = shortDate(row.optString("REPORT_DATE"))
                val name = row.optString("ITEM_NAME")
                if (date.length != 10 || name.isBlank()) continue
                items.add(
                    MainBusinessItem(
                        date = date,
                        type = row.optString("MAINOP_TYPE").toIntOrNull() ?: 0,
                        name = name,
                        income = row.optDouble("MAIN_BUSINESS_INCOME", 0.0),
                        ratio = row.optDouble("MBI_RATIO", 0.0),
                        grossMargin = row.optDouble("GROSS_RPOFIT_RATIO", Double.NaN).takeIf { !it.isNaN() },
                    )
                )
            }
            return items
        }

        fun parseShareholderDisclosures(holderCountText: String, positionsText: String): List<ShareholderDisclosure> {
            val counts = (dataArray(holderCountText) ?: JSONArray()).let { rows ->
                buildMap {
                    for (index in 0 until rows.length()) {
                        val row = rows.optJSONObject(index) ?: continue
                        val date = holderDate(row) ?: continue
                        val count = firstNumber(row, "HOLDER_TOTAL_NUM", "HOLDER_NUM", "TOTAL_HOLDER_NUM")
                        put(date, count?.toLong())
                    }
                }
            }
            val positionsByDate = buildMap<String, MutableList<InstitutionalPosition>> {
                val rows = dataArray(positionsText) ?: JSONArray()
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    val date = holderDate(row) ?: continue
                    val name = sequenceOf("HOLDER_NAME", "HOLDERNAME", "SHAREHOLDER_NAME")
                        .map { row.optString(it).trim() }
                        .firstOrNull { it.isNotBlank() }
                        ?: continue
                    if (!isInstitution(row, name)) continue
                    val shares = firstNumber(row, "HOLD_NUM", "HOLDNUM", "HOLD_NUM_NEW", "FREE_HOLDNUM") ?: continue
                    getOrPut(date) { mutableListOf() }.add(InstitutionalPosition(name, shares))
                }
            }
            return (counts.keys + positionsByDate.keys)
                .distinct()
                .sortedDescending()
                .map { date ->
                    ShareholderDisclosure(
                        reportDate = date,
                        holderCount = counts[date],
                        institutions = positionsByDate[date].orEmpty(),
                    )
                }
        }

        fun parseShareholderPage(html: String): List<ShareholderDisclosure> {
            val anchorPattern = Regex("""<a\s+name=[\"'](\d{4}-\d{2}-\d{2})[\"']\s*>\s*</a>""", RegexOption.IGNORE_CASE)
            val anchors = anchorPattern.findAll(html).toList()
            if (anchors.isEmpty()) return emptyList()
            return anchors.mapIndexedNotNull { index, anchor ->
                val end = anchors.getOrNull(index + 1)?.range?.first ?: html.length
                val block = html.substring(anchor.range.first, end)
                val date = anchor.groupValues[1]
                val holderCount = Regex("股东总数.*?<td[^>]*>\\s*([0-9,]+)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(block)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.replace(",", "")
                    ?.toLongOrNull()
                val institutions = Regex("<tr\\b[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .findAll(block)
                    .mapNotNull { rowMatch ->
                        val cells = Regex("<td\\b[^>]*>(.*?)</td>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                            .findAll(rowMatch.groupValues[1])
                            .map { htmlText(it.groupValues[1]) }
                            .toList()
                        if (cells.size < 3 || cells[0].toIntOrNull() == null) return@mapNotNull null
                        val shares = cells[2].replace(",", "").replace(Regex("[^0-9.]"), "").toDoubleOrNull()
                            ?: return@mapNotNull null
                        val name = cells[1].trim()
                        if (!isInstitutionName(name)) return@mapNotNull null
                        InstitutionalPosition(name, shares)
                    }
                    .toList()
                ShareholderDisclosure(date, holderCount, institutions)
            }
        }

        private fun htmlText(raw: String): String = raw
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()

        private fun isInstitutionName(name: String): Boolean {
            val keywords = listOf(
                "基金", "证券", "保险", "信托", "资管", "社保", "QFII", "银行", "投资",
                "资产管理", "结算", "有限公司", "股份有限公司", "集团",
            )
            return keywords.any { name.contains(it, ignoreCase = true) }
        }

        private fun holderDate(row: JSONObject): String? = sequenceOf("END_DATE", "REPORT_DATE", "HOLDER_DATE", "STATISTICS_DATE")
            .map { shortDate(row.optString(it)) }
            .firstOrNull { it.length == 10 }

        private fun firstNumber(row: JSONObject, vararg keys: String): Double? = keys.asSequence()
            .map { key -> row.optDouble(key, Double.NaN) }
            .firstOrNull { !it.isNaN() }

        private fun isInstitution(row: JSONObject, name: String): Boolean {
            val type = row.optString("HOLDER_TYPE").trim()
            if (type.contains("个人") || type.contains("自然人")) return false
            val keywords = listOf("基金", "证券", "保险", "信托", "资管", "社保", "QFII", "银行", "投资", "资产管理")
            return keywords.any { keyword -> type.contains(keyword, ignoreCase = true) || name.contains(keyword, ignoreCase = true) }
        }

        fun parseAnnouncements(text: String): List<Announcement> {
            val rows = runCatching {
                JSONObject(text).optJSONObject("data")?.optJSONArray("list")
            }.getOrNull() ?: return emptyList()
            val items = mutableListOf<Announcement>()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val date = row.optString("notice_date").take(10)
                val title = cleanTitle(row.optString("title"))
                if (date.length != 10 || title.isBlank()) continue
                val artCode = sequenceOf("art_code", "artCode", "notice_id", "id")
                    .map { row.optString(it) }
                    .firstOrNull { it.isNotBlank() }
                items.add(Announcement(date, title, artCode = artCode))
            }
            return items
        }

        fun parseAnnouncementContent(text: String): String? {
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
            return extractContent(findContent(root))
        }

        private fun findContent(value: Any?): String? {
            when (value) {
                is JSONObject -> {
                    val contentKeys = listOf(
                        "notice_content",
                        "notice_content_html",
                        "content",
                        "content_html",
                        "body",
                    )
                    contentKeys.firstNotNullOfOrNull { key ->
                        value.optString(key).takeIf { it.length >= 8 }
                    }?.let { return it }
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        findContent(value.opt(keys.next()))?.let { return it }
                    }
                }
                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        findContent(value.opt(index))?.let { return it }
                    }
                }
            }
            return null
        }

        private fun extractContent(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val rich = runCatching { JSONObject(raw) }.getOrNull()
            if (rich != null) {
                val pages = rich.keys().asSequence()
                    .filter { it.matches(Regex("page\\d+")) }
                    .sortedBy { it.removePrefix("page").toIntOrNull() ?: Int.MAX_VALUE }
                    .mapNotNull { key -> rich.optString(key).takeIf { it.isNotBlank() } }
                    .toList()
                return pages.takeIf { it.isNotEmpty() }?.joinToString("\n")
            }
            return raw
        }

        /**
         * 已抓到的公告是否覆盖满治理窗口：最早一条比「最新一条往回 180 天」还早，就算覆盖到了。
         * 单独提出来是为了不联网也能验证翻页停止条件。
         */
        fun announcementWindowCovered(items: List<Announcement>): Boolean {
            val newest = items.maxOfOrNull { it.date } ?: return false
            val oldest = items.minOfOrNull { it.date } ?: return false
            return daysBetween(oldest, newest) >= Governance.WINDOW_DAYS
        }

        internal fun daysBetween(from: String, to: String): Long = runCatching {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            val start = format.parse(from) ?: return 0L
            val end = format.parse(to) ?: return 0L
            (end.time - start.time) / 86_400_000L
        }.getOrDefault(0L)

        /** 公告标题都带「公司名:」前缀，去掉更好读；正常标题里的冒号不动。 */
        private fun cleanTitle(raw: String): String {
            val title = raw.trim()
            val colon = title.indexOfFirst { it == ':' || it == '：' }
            if (colon <= 0 || colon > 12) return title
            return title.substring(colon + 1).trim()
        }
    }
}
