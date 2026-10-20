package com.example.stockmonitor

import com.example.stockmonitor.analysis.BoardChange
import com.example.stockmonitor.analysis.BoardTag
import com.example.stockmonitor.analysis.SectorContext
import com.example.stockmonitor.core.BoardTagCache
import com.example.stockmonitor.core.DayBar
import com.example.stockmonitor.core.EastMoneyClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 板块上下文的口径锁：
 * - **行业筛选**：F10 的标签混着地域（贵州板块）、风格（消费风格）、规模（大盘股）、
 *   属性（行业龙头）和来源（东方财富热股），把它们当行业去比涨跌会得出完全错的结论；
 * - **修饰语**：个股强/板块弱、个股弱/板块强、板块塌陷是三种不同结论，不能说成一句话。
 */
class SectorContextTest {

    private fun tags(vararg pairs: Triple<String, String?, Int>): List<BoardTag> =
        pairs.map { (name, code, rank) -> BoardTag(name, code, rank) }

    @Test
    fun industryFilterDropsRegionStyleAndSizeTags() {
        // 茅台 F10 的真实返回形态：前三个是行业，后面是地域/风格/规模/属性/来源。
        assertFalse(SectorContext.isIndustry("贵州板块"))
        assertFalse(SectorContext.isIndustry("消费风格"))
        assertFalse(SectorContext.isIndustry("大盘股"))
        assertFalse(SectorContext.isIndustry("权重股"))
        assertFalse(SectorContext.isIndustry("行业龙头"))
        assertFalse(SectorContext.isIndustry("东方财富热股"))
        assertFalse(SectorContext.isIndustry("融资融券"))

        assertTrue(SectorContext.isIndustry("食品饮料"))
        assertTrue(SectorContext.isIndustry("白酒Ⅱ"))
        assertTrue(SectorContext.isIndustry("半导体"))
        assertTrue(SectorContext.isIndustry("银行"))
    }

    @Test
    fun industryPicksTheCoarsestRealIndustryByRank() {
        val context = SectorContext.of(
            tags(
                Triple("食品饮料", "BK0438", 1),
                Triple("白酒Ⅱ", "BK1277", 2),
                Triple("贵州板块", null, 4),
                Triple("大盘股", null, 6),
            ),
        )

        // rank 最小的**行业**是主行业；地域/规模再靠前也轮不到它们。
        assertEquals("食品饮料", context.industry?.name)
        assertEquals(3, context.tags.size) // 前三个标签留作上下文
    }

    @Test
    fun missingTagsMeansMissingNotGuess() {
        val context = SectorContext.of(emptyList())

        assertNull(context.industry)
        assertNull(context.interpret(3.0))
        assertEquals("暂缺", context.source)
    }

    @Test
    fun sectorCollapseWarnsAgainstStockGoingItAlone() {
        val bars = (0 until 80).map { i ->
            DayBar("2026-01-01", 20.0, 20.0, 20.0, 20.0 - 0.15 * i, 1_000.0)
        }
        val context = SectorContext.of(
            tags(Triple("半导体", "BK0438", 1)),
            boardBars = bars,
        )

        assertTrue(context.isCollapse)
        val note = context.interpret(1.0)
        assertNotNull(note)
        assertTrue(note!!.contains("塌陷"))
        assertTrue(note.contains("独善其身"))
    }

    @Test
    fun strongStockInWeakSectorIsFlaggedAsUnsustainable() {
        // 板块跌 3%、个股涨 1% → 差 4 个百分点，属独立行情。
        val context = SectorContext.of(
            tags(Triple("半导体", "BK0438", 1)),
            boardChangePct = -3.0,
        )

        val note = context.interpret(1.0)
        assertNotNull(note)
        assertTrue(note!!.contains("独立行情"))
        assertTrue(note.contains("持续性存疑"))
    }

    @Test
    fun weakStockInStrongSectorIsFlaggedAsLagging() {
        val context = SectorContext.of(
            tags(Triple("半导体", "BK0438", 1)),
            boardChangePct = 3.0,
        )

        val note = context.interpret(-1.0)
        assertNotNull(note)
        assertTrue(note!!.contains("掉队"))
        assertTrue(note.contains("个股自己"))
    }

    @Test
    fun movingInSyncGivesNoModifier() {
        // 同涨同跌不该硬塞一句解释——没有信息量的话就是噪音。
        val context = SectorContext.of(
            tags(Triple("半导体", "BK0438", 1)),
            boardChangePct = 1.0,
        )

        assertNull(context.interpret(1.5))
    }

    @Test
    fun noSectorChangeMeansNoRelativeCall() {
        // 拿不到板块涨跌就不判「强于/弱于板块」——不猜。
        val context = SectorContext.of(tags(Triple("半导体", "BK0438", 1)))

        assertNull(context.changePct)
        assertNull(context.interpret(3.0))
    }

    // ── 接口解析 ────────────────────────────────────────────────────────────

    @Test
    fun parseSsbkReadsBoardNameAndRank() {
        val payload = """
            {"ssbk":[
              {"SECUCODE":"600519.SH","SECURITY_CODE":"600519","SECURITY_NAME_ABBR":"贵州茅台",
               "BOARD_CODE":"438","BOARD_NAME":"食品饮料","IS_PRECISE":"0","BOARD_RANK":1},
              {"SECUCODE":"600519.SH","SECURITY_CODE":"600519","SECURITY_NAME_ABBR":"贵州茅台",
               "BOARD_CODE":"1277","BOARD_NAME":"白酒Ⅱ","IS_PRECISE":null,"BOARD_RANK":2},
              {"SECUCODE":"600519.SH","SECURITY_CODE":"600519","SECURITY_NAME_ABBR":"贵州茅台",
               "BOARD_CODE":"173","BOARD_NAME":"贵州板块","IS_PRECISE":"0","BOARD_RANK":4}
            ]}
        """.trimIndent()

        val parsed = EastMoneyClient.parseSsbk(payload)

        assertEquals(3, parsed.size)
        // 标签名取 BOARD_NAME，不是 SECURITY_NAME_ABBR（那是个股简称）。
        assertEquals("食品饮料", parsed[0].name)
        assertEquals("BK0438", parsed[0].boardCode)
        assertEquals(1, parsed[0].rank)
    }

    @Test
    fun parseBoardKlineReadsCloseFromTheThirdColumn() {
        val payload = """
            {"data":{"klines":[
              "2026-10-10,10.00,10.50,10.80,9.90,123456,1.2e9",
              "2026-10-11,10.50,10.20,10.60,10.10,234567,2.3e9"
            ]}}
        """.trimIndent()

        val bars = EastMoneyClient.parseBoardKline(payload)

        assertEquals(2, bars.size)
        // 行格式是 date,open,close,high,low,volume,amount —— close 在第 2 位，不是第 3 位。
        assertEquals(10.50, bars[0].close, 1e-9)
        assertEquals(10.20, bars[1].close, 1e-9)
        assertEquals("2026-10-11", bars[1].date)
    }

    @Test
    fun toBoardSecidZeroPadsTheBoardCode() {
        assertEquals("BK0438", EastMoneyClient.toBoardSecid("438"))
        assertEquals("BK1277", EastMoneyClient.toBoardSecid("1277"))
        assertNull(EastMoneyClient.toBoardSecid(""))
        assertNull(EastMoneyClient.toBoardSecid("白酒"))
    }

    @Test
    fun boardBarsAreRequiredBeforeJudgingBoardTrend() {
        // 日线不够就只给归属和当日涨跌，不判板块趋势——板块没历史是判不出破位的。
        val shortBars = (0 until 10).map { i -> DayBar("2026-01-01", 10.0, 10.0, 10.0, 10.0 + i, 1_000.0) }
        val context = SectorContext.of(
            tags(Triple("半导体", "BK0438", 1)),
            boardBars = shortBars,
            boardChangePct = 1.5,
        )

        assertNull(context.state)
        assertEquals(1.5, context.changePct!!, 1e-9)
    }

    @Test
    fun changeSourceIsLabelledSoThePathIsDiagnosable() {
        // 板块日线拉不到时会退回按名称匹配的行情。报告里标出来源，
        // 用户把报告贴回来，一眼就知道哪条路通了——这次就是靠它定位到「归属通了、日线没通」。
        val fromKline = SectorContext.of(tags(Triple("电力设备", "BK0438", 1)), boardChangePct = -1.2)
        val fromFallback = SectorContext.of(
            tags(Triple("电力设备", "BK0438", 1)),
            boardChangePct = -1.2,
            changeSource = "行业板块行情（按名称匹配）",
        )
        val nothing = SectorContext.of(tags(Triple("电力设备", "BK0438", 1)))

        assertEquals("板块日线", fromKline.changeSource)
        assertEquals("行业板块行情（按名称匹配）", fromFallback.changeSource)
        // 两条路都拿不到就写「暂缺」，不能装作有数据。
        assertEquals("暂缺", nothing.changeSource)
        assertNull(nothing.changePct)
    }

    @Test
    fun missingChangeDistinguishesColdCacheFromNameMismatch() {
        // 「行业板块行情未加载」（30 分钟一轮的缓存还没热）和「行业板块里无同名项」
        // （东财与新浪的叫法不一致）是两个完全不同的问题，混成一句「暂缺」就没法接着修。
        val cold = SectorContext.of(
            tags(Triple("电力设备", "BK0438", 1)),
            changeSource = "新浪行业板块未加载",
        )
        val noMatch = SectorContext.of(
            tags(Triple("白酒Ⅱ", "BK1277", 2)),
            changeSource = "新浪行业板块里无同名项（两套分类体系不同）",
        )

        assertEquals("新浪行业板块未加载", cold.changeSource)
        assertEquals("新浪行业板块里无同名项（两套分类体系不同）", noMatch.changeSource)
        // 调用方给的诊断不能被「有值/无值」的默认逻辑抹掉。
        assertNull(cold.changePct)
        assertNull(noMatch.changePct)
    }

    @Test
    fun fallbackChangeStillDrivesTheRelativeCall() {
        // 没有板块日线（判不了板块趋势）也不影响「个股 vs 板块」——只要有当日涨跌就够。
        val context = SectorContext.of(
            tags(Triple("电力设备", "BK0438", 1)),
            boardChangePct = -3.0,
            changeSource = "行业板块行情（按名称匹配）",
        )

        assertNull(context.state) // 没日线，不判板块趋势
        val note = context.interpret(1.0)
        assertNotNull(note)
        assertTrue(note!!.contains("独立行情"))
    }

    // ── 兜底匹配：按全部行业标签逐个试，不只用主行业 ─────────────────────────

    @Test
    fun matchBoardChangeTriesEveryCandidateNotJustThePrimary() {
        // F10 的 rank-1 是申万一级（电力设备），东财行业板块是更细的东财行业（电网设备）。
        // 只拿 rank-1 去匹配会全军覆没——实测特变电工就是这样丢掉涨跌的。
        val boards = mapOf("电网设备" to -1.2, "光伏设备" to 0.8)
        val hit = BoardTagCache.matchBoardChange(boards, listOf("电力设备", "输变电设备", "电网设备"))

        assertEquals(-1.2, hit.pct!!, 1e-9)
        // 命中的不是主行业时要说清是哪一级，别让人以为「电力设备」这个板块今天跌了 1.2%。
        assertEquals("东财行业板块「电网设备」", hit.source)
    }

    @Test
    fun matchBoardChangeFallsBackToContainsAcrossGranularity() {
        val boards = mapOf("电网设备" to -1.2)
        val hit = BoardTagCache.matchBoardChange(boards, listOf("电网"))

        assertEquals(-1.2, hit.pct!!, 1e-9)
        assertTrue(hit.source.contains("按包含匹配"))
    }

    @Test
    fun matchBoardChangeSaysWhichPathBroke() {
        // 接口不可达和通了但对不上是两个问题。
        val unreachable = BoardTagCache.matchBoardChange(emptyMap(), listOf("电力设备"))
        val noMatch = BoardTagCache.matchBoardChange(mapOf("酿酒行业" to 1.0), listOf("电力设备", "电网设备"))

        assertNull(unreachable.pct)
        // 空列表也要带「试过哪些标签」，不能只说「不可达」就完了。
        assertTrue(unreachable.source.contains("东财行业板块接口不可达或返回空"))
        assertTrue(unreachable.source.contains("试过：电力设备"))
        assertNull(noMatch.pct)
        // 诊断要同时说清两件事：试过哪些标签（F10 给了哪几级），以及列表里实际有什么
        // （万一分支压根不是行业）。漏掉任何一个，下一轮还是只能猜。
        assertTrue(noMatch.source.contains("试过：电力设备、电网设备"))
        assertTrue(noMatch.source.contains("列表前几项：酿酒行业"))
    }

    @Test
    fun fallbackFailureDoesNotSwallowTheEastMoneyDiagnostic() {
        // 走到新浪兜底时，东财那条的诊断不能被覆盖掉——上次就是这样白跑了一轮。
        val boards = mapOf("酿酒行业" to 1.0)
        val hit = BoardTagCache.matchBoardChange(boards, listOf("电力设备")) { name ->
            BoardChange(null, "新浪行业板块里无同名项（两套分类体系不同）")
        }

        assertNull(hit.pct)
        assertTrue(hit.source.contains("东财行业板块里无同名项"))
        assertTrue(hit.source.contains("试过：电力设备"))
        assertTrue(hit.source.contains("列表前几项：酿酒行业"))
        // 新浪那条的失败也要一起带出来。
        assertTrue(hit.source.contains("新浪行业板块里无同名项"))
    }

    @Test
    fun sinaFallbackTriesAllCandidateNames() {
        val tried = mutableListOf<String>()
        val hit = BoardTagCache.matchBoardChange(emptyMap(), listOf("电力设备", "电网设备")) { name ->
            tried += name
            if (name == "电网设备") BoardChange(-1.5, "新浪行业板块")
            else BoardChange(null, "新浪行业板块无匹配")
        }

        assertEquals(listOf("电力设备", "电网设备"), tried)
        assertEquals(-1.5, hit.pct!!, 1e-9)
    }
}
