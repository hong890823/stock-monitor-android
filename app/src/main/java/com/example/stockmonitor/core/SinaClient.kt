package com.example.stockmonitor.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.Charset

private fun String.finiteDoubleOrNull(): Double? =
    toDoubleOrNull()?.takeIf { it.isFinite() }

/**
 * 新浪财经接口。沿用 macOS 版使用的同一批地址与解析规则：
 * hq.sinajs.cn 实时行情、suggest3 搜索、行业板块、活跃股榜单。
 *
 * 例外是日线 K 线（[kline]）：主源已经换成腾讯的前复权接口，新浪日线只在腾讯失败时兜底，
 * 原因见 [kline] 的注释。
 */
class SinaClient {
    private val gbk: Charset = Charset.forName("GBK")
    private val referer = "https://finance.sina.com.cn"

    /** 单只实时行情。 */
    fun quote(code: String): Quote? = quotes(listOf(code))[code]

    /** 批量实时行情，一批最多 800 只，等价于 _sina_batch_quote。 */
    fun quotes(codes: List<String>): Map<String, Quote> {
        if (codes.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, Quote>()
        for (chunk in codes.chunked(800)) {
            val text = Http.getText(
                "https://hq.sinajs.cn/list=" + chunk.joinToString(","),
                gbk,
                referer,
                15_000,
            )
            for (line in text.lineSequence()) {
                val match = Regex("hq_str_(\\w+)=\"([^\"]*)\"").find(line) ?: continue
                val payload = match.groupValues[2]
                if (payload.isBlank()) continue
                val parts = payload.split(",")
                if (parts.size < 10) continue
                val prevClose = parts[2].finiteDoubleOrNull() ?: continue
                val rawPrice = parts[3].finiteDoubleOrNull() ?: continue
                if (prevClose <= 0) continue
                // 集合竞价前接口会返回 0，此时用昨收兜底，避免出现 -100% 的假跌。
                val price = if (rawPrice > 0) rawPrice else prevClose
                result[match.groupValues[1]] = Quote(
                    code = match.groupValues[1],
                    name = parts[0],
                    price = price,
                    prevClose = prevClose,
                    open = parts[1].finiteDoubleOrNull()?.takeIf { it > 0 } ?: price,
                    high = parts[4].finiteDoubleOrNull()?.takeIf { it > 0 } ?: price,
                    low = parts[5].finiteDoubleOrNull()?.takeIf { it > 0 } ?: price,
                    volume = parts[8].finiteDoubleOrNull()?.takeIf { it >= 0 } ?: 0.0,
                    turnover = parts[9].finiteDoubleOrNull()?.takeIf { it >= 0 } ?: 0.0,
                    quoteDate = parts.getOrNull(30)?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) },
                    buy1Volume = parts.getOrNull(10)?.finiteDoubleOrNull()?.takeIf { it >= 0 },
                )
            }
        }
        return result
    }

    /** 名称 / 拼音 / 代码搜索，返回 (代码, 名称)。 */
    fun search(keyword: String): List<Pair<String, String>> {
        if (keyword.isBlank()) return emptyList()
        val url = "https://suggest3.sinajs.cn/suggest/type=11,12&key=" +
            URLEncoder.encode(keyword, "UTF-8")
        val text = Http.getText(url, gbk, referer, 8_000)
        val payload = text.split("\"").getOrNull(1) ?: return emptyList()
        val results = mutableListOf<Pair<String, String>>()
        for (item in payload.split(";")) {
            val parts = item.split(",")
            if (parts.size < 4) continue
            // 新浪返回格式：按名称搜索时 parts[0] 是名称，按代码搜索时 parts[0] 是代码；
            // parts[4] 始终是股票名称，优先使用它。
            val name = parts.getOrNull(4)?.takeIf { it.isNotBlank() } ?: parts[0]
            val code = parts[2]
            if (name.isBlank() || code.isBlank()) continue
            val marketCode = parts[3]
            val full = when {
                marketCode.length >= 8 &&
                    (marketCode.startsWith("sh") || marketCode.startsWith("sz")) ->
                    marketCode.substring(0, 2) + code
                parts[1] == "11" -> "sh$code"
                else -> "sz$code"
            }
            results.add(full to name)
        }
        return results
    }

    /**
     * 日线 K 线，对应 macOS 版的 get_stock_analysis_history；那一版拿的是 akshare 前复权日线。
     *
     * 主源是腾讯的**前复权**序列（qfq）。未复权在除权日会留下一个假跳空，把区间位置、均线、
     * 支撑压力、MACD 一起带偏：中国神华 sh601088 于 2026-07-13 除权，未复权口径下除权之前
     * 每一天都比复权价高 1.03 元（约 2.5%），60 日区间位置因此从「高位」掉成「中位」，
     * 量价结论跟着翻面。前复权还有个附带好处：最后一根就是当前真实价，与实时行情天然接得上。
     * 指数没有复权概念，腾讯对指数返回的也是未复权序列（字段名不同），解析时自动区分。
     *
     * 腾讯取不到时退回新浪未复权，避免列表空白；但那份数据是未复权口径，
     * 只有除权股会与复权口径有偏差（未除权的股票两个源逐日完全一致）。
     */
    fun kline(code: String, length: Int = 240): List<DayBar> {
        val fromTencent = runCatching { fetchTencentQfq(code, length) }.getOrNull()
        if (!fromTencent.isNullOrEmpty()) return fromTencent
        return sinaKline(code, length)
    }

    fun swingKline(code: String): List<DayBar> {
        val url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=$code,day,,,1200,qfq"
        val text = Http.getText(url, Charsets.UTF_8, "https://gu.qq.com/", 15_000)
        return parseSwingHistory(text, code).orEmpty()
    }

    /**
     * 盘中分钟K线。15分钟用于执行触发，30分钟用于方向过滤；
     * 两者都按新浪时间戳排序，最后一根可能是未收盘的实时柱，由调用方用 upsert 更新。
     */
    fun intradayKline(code: String, minutes: Int, length: Int): List<IntradayBar> {
        require(minutes == 15 || minutes == 30) { "分钟K线仅支持15或30分钟" }
        require(length in 2..600) { "分钟K线数量必须在2到600之间" }
        val url = "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/" +
            "CN_MarketData.getKLineData?symbol=$code&scale=$minutes&ma=no&datalen=$length"
        val text = Http.retry(retries = 2, delayMs = 500, label = "${minutes}分钟K线") {
            Http.getText(url, Charsets.UTF_8, "https://finance.sina.com.cn/", 10_000)
        }
        return parseIntradayBars(text, code, minutes)
    }

    /** 腾讯前复权日线；接口正常但没有这只股票（退市、北交所等）时返回 null。 */
    private fun fetchTencentQfq(code: String, length: Int): List<DayBar>? {
        val url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get" +
            "?param=$code,day,,,$length,qfq"
        val text = Http.retry(retries = 3, delayMs = 800, label = "腾讯日线") {
            Http.getText(url, Charsets.UTF_8, "https://gu.qq.com/", 15_000)
        }
        return parseTencentQfq(text, code)
    }

    /** 新浪未复权日线，仅在腾讯取不到时兜底。 */
    private fun sinaKline(code: String, length: Int): List<DayBar> {
        val url = "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/" +
            "CN_MarketData.getKLineData?symbol=$code&scale=240&ma=no&datalen=$length"
        val text = Http.getText(url, Charsets.UTF_8, "https://finance.sina.com.cn/", 10_000)
        val array = runCatching { JSONArray(text) }
            .getOrElse { throw Http.HttpError("新浪日线数据解析失败") }
        val bars = ArrayList<DayBar>(array.length())
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val close = row.optString("close").finiteDoubleOrNull() ?: continue
            val high = row.optString("high").finiteDoubleOrNull() ?: continue
            val low = row.optString("low").finiteDoubleOrNull() ?: continue
            bars.add(
                DayBar(
                    date = row.optString("day"),
                    open = row.optString("open").finiteDoubleOrNull() ?: close,
                    high = high,
                    low = low,
                    close = close,
                    volume = row.optString("volume").finiteDoubleOrNull()?.takeIf { it >= 0 } ?: 0.0,
                )
            )
        }
        return bars
    }

    /** 行业板块，等价于 get_sector_data。 */
    fun sectors(): SectorData {
        val text = Http.getText(
            "https://vip.stock.finance.sina.com.cn/q/view/newSinaHy.php",
            gbk,
            referer,
            12_000,
        )
        val all = mutableListOf<SectorItem>()
        for (match in Regex("\"([^\"]+)\":\"([^\"]+)\"").findAll(text)) {
            val parts = match.groupValues[2].split(",")
            if (parts.size < 13) continue
            val name = parts[1]
            val count = parts[2].toIntOrNull() ?: 0
            if (name == "次新股" || count < 30) continue
            all.add(
                SectorItem(
                    name = name,
                    change = parts[5].finiteDoubleOrNull() ?: 0.0,
                    count = count,
                    volume = parts[7].finiteDoubleOrNull()?.takeIf { it >= 0 } ?: 0.0,
                    lead = parts[12],
                    leadPct = parts[9],
                )
            )
        }
        return SectorData(
            top = all.filter { it.change > 0 }.sortedByDescending { it.change }.take(SECTOR_COUNT),
            bottom = all.filter { it.change < 0 }.sortedBy { it.change }.take(SECTOR_COUNT),
            all = all,
        )
    }

    /** 概念板块全量统计，等价于 _fetch_concept_summary_once。 */
    fun conceptSummary(): ConceptSummary {
        val text = Http.getText(
            "https://money.finance.sina.com.cn/q/view/newFLJK.php?param=class",
            gbk,
            referer,
            10_000,
        )
        val jsonText = Regex("=\\s*(\\{.*\\})").find(text)?.groupValues?.get(1)
            ?: throw Http.HttpError("概念板块数据解析失败")
        val json = JSONObject(jsonText)
        val sectors = mutableListOf<ConceptItem>()
        var rising = 0
        var falling = 0
        for (key in json.keys()) {
            val parts = json.optString(key).split(",")
            if (parts.size < 13) continue
            val change = parts[5].finiteDoubleOrNull() ?: 0.0
            sectors.add(
                ConceptItem(
                    name = parts[1],
                    code = key,
                    change = change,
                    volume = parts[7].finiteDoubleOrNull()?.takeIf { it >= 0 } ?: 0.0,
                    lead = parts[12],
                    leadPct = parts[9],
                )
            )
            if (change > 0) rising++ else if (change < 0) falling++
        }
        if (sectors.isEmpty()) throw Http.HttpError("概念板块数据为空")
        sectors.sortByDescending { it.change }
        return ConceptSummary(sectors, rising, falling)
    }

    /** 按成交量排序的活跃股，等价于 get_indicator_spot_data。 */
    fun activeStocks(limit: Int = 200): List<ActiveStock> {
        val pageSize = 80
        val pages = maxOf(1, (limit + pageSize - 1) / pageSize)
        val rows = mutableListOf<ActiveStock>()
        for (page in 1..pages) {
            val array = fetchActiveStockPage(page, pageSize)
            if (array.length() == 0) break
            rows += parseActiveStockPage(array)
            if (rows.size >= limit) break
            Thread.sleep(400)
        }
        return rows.take(limit)
    }

    /** 全部沪深 A 股，用于需要全市场覆盖的日线策略（例如513形态）。 */
    fun allAStocks(): List<ActiveStock> {
        val pageSize = 80
        val rows = LinkedHashMap<String, ActiveStock>()
        for (page in 1..MAX_ACTIVE_STOCK_PAGES) {
            val array = fetchActiveStockPage(page, pageSize)
            if (array.length() == 0) break
            parseActiveStockPage(array).forEach { rows[it.code] = it }
            if (array.length() < pageSize) break
            Thread.sleep(400)
        }
        return rows.values.toList()
    }

    private fun fetchActiveStockPage(page: Int, pageSize: Int): JSONArray {
        val url = "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/" +
            "Market_Center.getHQNodeData?page=$page&num=$pageSize&sort=volume&asc=0&node=hs_a"
        return Http.retry(retries = 3, delayMs = 1_500, label = "活跃股") {
            JSONArray(Http.getText(url, Charsets.UTF_8, referer, 15_000))
        }
    }

    private fun parseActiveStockPage(array: JSONArray): List<ActiveStock> {
        val codePattern = Regex("^(sh|sz)\\d{6}$")
        val rows = mutableListOf<ActiveStock>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val symbol = item.optString("symbol").lowercase()
            val name = item.optString("name")
            val trade = item.optString("trade").finiteDoubleOrNull() ?: 0.0
            val volume = item.optString("volume").finiteDoubleOrNull() ?: 0.0
            if (volume <= 0 || trade <= 0) continue
            if (!codePattern.matches(symbol)) continue
            if (name.contains("ST") || name.contains("退")) continue
            rows.add(
                ActiveStock(
                    code = symbol,
                    name = name,
                    prevClose = item.optString("settlement").finiteDoubleOrNull() ?: 0.0,
                    open = item.optString("open").finiteDoubleOrNull() ?: 0.0,
                    high = item.optString("high").finiteDoubleOrNull() ?: 0.0,
                    low = item.optString("low").finiteDoubleOrNull() ?: 0.0,
                    price = trade,
                    volume = volume,
                )
            )
        }
        return rows
    }

    /** 全市场涨跌停扫描，等价于 get_limit_stocks。 */
    fun limitStocks(): LimitData {
        val up = mutableListOf<LimitStock>()
        val down = mutableListOf<LimitStock>()
        for (range in CODE_RANGES) {
            val prefix = range.first
            val start = range.second
            val end = range.third
            var index = start
            while (index < end) {
                val codes = (index until minOf(index + 800, end)).map {
                    prefix + String.format("%06d", it)
                }
                index += 800
                val quotes = runCatching { quotes(codes) }.getOrNull() ?: continue
                for (quote in quotes.values) {
                    val name = quote.name
                    if (name.contains("ST") || name.contains("退") || name.startsWith("N ")) continue
                    val limit = limitPctFor(quote.code, quote.name)
                    val stock = LimitStock(quote.code, name, quote.price, quote.prevClose, quote.changePct)
                    if (quote.changePct >= limit - 0.15) up.add(stock)
                    else if (quote.changePct <= -limit + 0.15) down.add(stock)
                }
            }
        }
        return LimitData(up.sortedByDescending { it.pct }, down.sortedBy { it.pct })
    }

    companion object {
        private const val MAX_ACTIVE_STOCK_PAGES = 100
        const val SECTOR_COUNT = 5

        /** 腾讯日线的成交量单位是「手」，乘以这个系数换算成与新浪实时行情一致的「股」。 */
        const val VOLUME_LOT = 100.0

        /**
         * 解析腾讯 fqkline 的返回。
         *
         * 结构：`{"code":0,"data":{"<代码>":{"qfqday":[[日期,开,收,高,低,量], ...]}}}`。
         * 列顺序是「开、收、高、低、量」，和新浪的字段名不一样，别按位置硬猜。
         * 个股给 `qfqday`（前复权），指数给 `day`（无复权概念），两者列格式相同。
         */
        internal fun parseTencentQfq(payload: String, code: String): List<DayBar>? {
            val node = JSONObject(payload).optJSONObject("data")?.optJSONObject(code) ?: return null
            val array = node.optJSONArray("qfqday") ?: node.optJSONArray("day") ?: return null
            val bars = ArrayList<DayBar>(array.length())
            for (i in 0 until array.length()) {
                val row = array.optJSONArray(i) ?: continue
                if (row.length() < 6) continue
            val close = row.optString(2).finiteDoubleOrNull() ?: continue
            val high = row.optString(3).finiteDoubleOrNull() ?: continue
            val low = row.optString(4).finiteDoubleOrNull() ?: continue
                bars.add(
                    DayBar(
                        date = row.optString(0),
                        open = row.optString(1).finiteDoubleOrNull() ?: close,
                        high = high,
                        low = low,
                        close = close,
                        volume = (row.optString(5).finiteDoubleOrNull()?.takeIf { it >= 0 } ?: 0.0) * VOLUME_LOT,
                    )
                )
            }
            return bars.ifEmpty { null }
        }

        internal fun parseSwingHistory(payload: String, code: String): List<DayBar>? {
            val node = JSONObject(payload).optJSONObject("data")?.optJSONObject(code) ?: return null
            val source = node.optJSONArray("qfqday") ?: return null
            for (index in 0 until source.length()) {
                val row = source.optJSONArray(index) ?: return null
                if ((1..4).any { column ->
                        row.optString(column).finiteDoubleOrNull()?.let { it > 0 } != true
                    } || row.optString(5).finiteDoubleOrNull()?.let { it >= 0 } != true) return null
            }
            val parsed = parseTencentQfq(payload, code) ?: return null
            return parsed.takeIf { it.size == source.length() }
        }

        /** 新浪 A 股代码范围（批量扫描用） */
        val CODE_RANGES = listOf(
            Triple("sh", 600000, 606000),
            Triple("sh", 688000, 690000),
            Triple("sz", 0, 4000),
            Triple("sz", 300000, 302000),
        )

        /**
         * 涨跌停阈值：主板（含 ST 和星号 ST）10%，创业板/科创板（含风险警示）20%，北交所 30%。
         * 新股上市前若处于无涨跌幅限制阶段，不由此方法判断。
         *
         * 代码收 `sh600522` 这种带前缀的，也收东财 `clist` 返回的裸 6 位码（`600522`），
         * 统一取末 6 位判定；不能用 `substring(2)`——裸码 `300750` 会被截成 `0750`，
         * 把创业板的 20% 错判成 10%。
         */
        fun limitPctFor(code: String, name: String? = null): Double {
            val number = code.takeLast(6)
            return when {
                number.startsWith("8") || number.startsWith("4") || number.startsWith("920") -> 30.0
                number.startsWith("30") || number.startsWith("688") -> 20.0
                name?.contains("ST", ignoreCase = true) == true -> 10.0
                else -> 10.0
            }
        }
    }
}
