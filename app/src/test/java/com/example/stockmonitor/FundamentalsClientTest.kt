package com.example.stockmonitor

import com.example.stockmonitor.core.FundamentalsClient
import com.example.stockmonitor.analysis.Announcement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 基本面接口的解析。字段名和口径都是实测抓下来的真实返回，锁住三件容易错的事：
 * 利润表和现金流量表要按报告期对齐、板块中位数不能用负 PE、公告标题要剥掉公司名前缀。
 */
class FundamentalsClientTest {

    @Test
    fun announcementPagingStopsOnceTheWindowIsCovered() {
        assertFalse(FundamentalsClient.announcementWindowCovered(emptyList()))
        // 华天科技那种公告密度：100 条只往回够 3 个月，窗口是半年，还得继续翻。
        assertFalse(
            FundamentalsClient.announcementWindowCovered(
                listOf(Announcement("2026-09-12", "a"), Announcement("2026-06-18", "b"))
            )
        )
        assertTrue(
            FundamentalsClient.announcementWindowCovered(
                listOf(Announcement("2026-09-12", "a"), Announcement("2026-03-01", "b"))
            )
        )
    }

    @Test
    fun shareholderRowsAreGroupedByDisclosureDateAndKeepInstitutionsOnly() {
        val counts = """{"result":{"data":[
            {"END_DATE":"2026-06-30 00:00:00","HOLDER_TOTAL_NUM":80000},
            {"END_DATE":"2026-03-31 00:00:00","HOLDER_TOTAL_NUM":100000}
        ]}}"""
        val positions = """{"result":{"data":[
            {"END_DATE":"2026-06-30 00:00:00","HOLDER_NAME":"甲基金","HOLDER_TYPE":"基金","HOLD_NUM":1200000},
            {"END_DATE":"2026-06-30 00:00:00","HOLDER_NAME":"张三","HOLDER_TYPE":"个人","HOLD_NUM":900000},
            {"END_DATE":"2026-03-31 00:00:00","HOLDER_NAME":"甲基金","HOLDER_TYPE":"基金","HOLD_NUM":1000000},
            {"END_DATE":"2026-03-31 00:00:00","HOLDER_NAME":"丙保险","HOLDER_TYPE":"保险","HOLD_NUM":500000}
        ]}}"""

        val disclosures = FundamentalsClient.parseShareholderDisclosures(counts, positions)

        assertEquals(listOf("2026-06-30", "2026-03-31"), disclosures.map { it.reportDate })
        assertEquals(80000L, disclosures[0].holderCount)
        assertEquals(listOf("甲基金"), disclosures[0].institutions.map { it.name })
        assertEquals(listOf("甲基金", "丙保险"), disclosures[1].institutions.map { it.name })
    }

    @Test
    fun sinaShareholderPageParserReadsLatestPeriodsAndInstitutionNames() {
        val html = """
            <a name="2026-06-30"></a>
            <td><strong>股东总数</strong></td><td colspan="4">833474</td>
            <table><tr><td>1</td><td><a>甲基金</a></td><td>1200000</td><td>1.2</td></tr>
            <tr><td>2</td><td>张三</td><td>900000</td><td>0.9</td></tr></table>
            <a name="2026-03-31"></a>
            <td><strong>股东总数</strong></td><td colspan="4">853087</td>
            <table><tr><td>1</td><td><a>甲基金</a></td><td>1000000</td><td>1.0</td></tr></table>
        """.trimIndent()

        val disclosures = FundamentalsClient.parseShareholderPage(html)

        assertEquals(listOf("2026-06-30", "2026-03-31"), disclosures.map { it.reportDate })
        assertEquals(833474L, disclosures[0].holderCount)
        assertEquals(listOf("甲基金"), disclosures[0].institutions.map { it.name })
    }

    private fun incomeJson(): String =
        """{"result":{"data":[""" +
            """{"SECUCODE":"002185.SZ","REPORT_DATE":"2026-06-30 00:00:00",""" +
            """"TOTAL_OPERATE_INCOME":10510627614.2,"OPERATE_COST":9003214126.52,""" +
            """"PARENT_NETPROFIT":813402689.67,"DEDUCT_PARENT_NETPROFIT":250268388.02},""" +
            """{"SECUCODE":"002185.SZ","REPORT_DATE":"2026-03-31 00:00:00",""" +
            """"TOTAL_OPERATE_INCOME":4799534766.23,"OPERATE_COST":4256029755.08,""" +
            """"PARENT_NETPROFIT":86786407.33,"DEDUCT_PARENT_NETPROFIT":11409181.13},""" +
            """{"SECUCODE":"002185.SZ","REPORT_DATE":"2025-12-31 00:00:00",""" +
            """"TOTAL_OPERATE_INCOME":17213915293.79,"OPERATE_COST":14931570294.39,""" +
            """"PARENT_NETPROFIT":710508576.43,"DEDUCT_PARENT_NETPROFIT":200439267.39}""" +
            """],"count":82},"success":true}"""

    private fun cashflowJson(): String =
        """{"result":{"data":[""" +
            """{"REPORT_DATE":"2026-06-30 00:00:00","NETCASH_OPERATE":1234.5,""" +
            """"SALES_SERVICES":9876.5,"CONSTRUCT_LONG_ASSET":321.0},""" +
            """{"REPORT_DATE":"2026-03-31 00:00:00","NETCASH_OPERATE":-500.0,""" +
            """"SALES_SERVICES":4000.0,"CONSTRUCT_LONG_ASSET":100.0}""" +
            """],"count":82},"success":true}"""

    @Test
    fun periodsJoinTheTwoStatementsByReportDateAndKeepCashMissingOnes() {
        val periods = FundamentalsClient.parsePeriods(incomeJson(), cashflowJson())

        // 2025 年报在现金流量表里没有：保留利润表口径，现金三项记 null——
        // 「缺表」不能记成 0 现金流，也不能把整个报告期丢掉（金融企业现金流量表整表为空）。
        assertEquals(3, periods.size)
        assertEquals("2025-12-31", periods[0].date)
        assertEquals(17213915293.79, periods[0].revenue, 1e-6)
        assertNull(periods[0].operatingCashFlow)
        assertNull(periods[0].salesCash)
        assertEquals("2026-03-31", periods[1].date)
        assertEquals(4799534766.23, periods[1].revenue, 1e-6)
        assertEquals(-500.0, periods[1].operatingCashFlow!!, 1e-6)
        assertEquals("2026-06-30", periods[2].date)
        assertEquals(9876.5, periods[2].salesCash!!, 1e-6)
        assertEquals(321.0, periods[2].capex!!, 1e-6)
    }

    @Test
    fun periodsSurviveWhenCashflowTableIsEmptyAndOrgTypeIsReadable() {
        // 券商股（ORG_TYPE=证券）在 F10 上没有现金流量表/资产负债表：利润表照出，现金流全 null。
        val income = """{"result":{"data":[{"SECUCODE":"600999.SH","REPORT_DATE":"2026-06-30 00:00:00",""" +
            """"ORG_TYPE":"证券","TOTAL_OPERATE_INCOME":21901578682.9,"OPERATE_COST":null,""" +
            """"PARENT_NETPROFIT":10624328413.44,"DEDUCT_PARENT_NETPROFIT":10626293415.35}]},"success":true}"""

        val periods = FundamentalsClient.parsePeriods(income, """{"result":null,"success":false,"code":9201}""")

        assertEquals(1, periods.size)
        assertEquals(21901578682.9, periods[0].revenue, 1e-6)
        assertNull(periods[0].operatingCashFlow)
        assertNull(periods[0].salesCash)
        assertEquals("证券", FundamentalsClient.parseOrgType(income))
    }

    @Test
    fun balancePrefersTheCombinedReceivableAndOtherReceivableLines() {
        val text = """{"result":{"data":[{"REPORT_DATE":"2026-06-30 00:00:00",""" +
            """"MONETARYFUNDS":2250000000.0,"NOTE_ACCOUNTS_RECE":3300000000.0,""" +
            """"ACCOUNTS_RECE":3100000000.0,"TOTAL_OTHER_RECE":88000000.0,""" +
            """"OTHER_RECE":70000000.0,"INVENTORY":1500000000.0,"GOODWILL":320000000.0,""" +
            """"SHORT_LOAN":1000000000.0,"LONG_LOAN":2000000000.0,"BOND_PAYABLE":0,""" +
            """"LEASE_LIAB":5000000.0,"TOTAL_ASSETS":36000000000.0,""" +
            """"TOTAL_LIABILITIES":18000000000.0,"TOTAL_PARENT_EQUITY":17000000000.0,""" +
            """"MINORITY_EQUITY":1000000000.0}]},"success":true}"""

        val balance = FundamentalsClient.parseBalance(text)!!

        assertEquals(3300000000.0, balance.accountsReceivable, 1e-6)
        assertEquals(88000000.0, balance.otherReceivable, 1e-6)
        assertEquals(3005000000.0, balance.interestBearingDebt, 1e-6)
        assertEquals(2250000000.0 - 3005000000.0, balance.netCash, 1e-6)
    }

    @Test
    fun balanceFallsBackToTheSingleReceivableLineWhenTheCombinedOneIsMissing() {
        val text = """{"result":{"data":[{"REPORT_DATE":"2026-06-30 00:00:00",""" +
            """"ACCOUNTS_RECE":3100000000.0,"OTHER_RECE":70000000.0}]},"success":true}"""

        val balance = FundamentalsClient.parseBalance(text)!!

        assertEquals(3100000000.0, balance.accountsReceivable, 1e-6)
        assertEquals(70000000.0, balance.otherReceivable, 1e-6)
    }

    @Test
    fun peerMediansIgnoreLossMakingCompanies() {
        val text = """{"result":{"data":[""" +
            """{"SECURITY_CODE":"688825","PE_TTM":48.0,"PB_MRQ":29.5,"TOTAL_MARKET_CAP":3979000.0},""" +
            """{"SECURITY_CODE":"688981","PE_TTM":144.0,"PB_MRQ":6.0,"TOTAL_MARKET_CAP":1039000.0},""" +
            """{"SECURITY_CODE":"000001","PE_TTM":-20.0,"PB_MRQ":3.0,"TOTAL_MARKET_CAP":700000.0},""" +
            """{"SECURITY_CODE":"002185","PE_TTM":45.44,"PB_MRQ":3.21,"TOTAL_MARKET_CAP":611000.0}""" +
            """],"count":186},"success":true}"""

        val peers = FundamentalsClient.parsePeers(text, "002185", "半导体")!!

        assertEquals("半导体", peers.boardName)
        assertEquals(186, peers.total)
        assertEquals(3, peers.sampleSize)
        assertEquals(4, peers.marketCapRank)
        assertEquals(48.0, peers.peMedian!!, 1e-6)
        assertEquals(4.605, peers.pbMedian!!, 1e-6)
        assertEquals(611000.0, peers.marketCap!!, 1e-6)
    }

    @Test
    fun announcementTitlesLoseTheCompanyPrefix() {
        val text = """{"data":{"list":[""" +
            """{"art_code":"AN202609120001","notice_date":"2026-09-12 00:00:00","title":"华天科技:关于开展外汇套期保值业务的公告"},""" +
            """{"notice_date":"2026-08-28 00:00:00",""" +
            """"title":"3-1独立财务顾问报告(注册稿)(天水华天科技股份有限公司)"}""" +
            """]},"success":true}"""

        val items = FundamentalsClient.parseAnnouncements(text)

        assertEquals(2, items.size)
        assertEquals("关于开展外汇套期保值业务的公告", items[0].title)
        assertEquals("2026-09-12", items[0].date)
        assertEquals("AN202609120001", items[0].artCode)
        assertEquals("3-1独立财务顾问报告(注册稿)(天水华天科技股份有限公司)", items[1].title)
    }

    @Test
    fun announcementDetailParserAcceptsHtmlBodyFields() {
        val text = """{"data":{"notice_content":"<p>募集资金用于项目建设。</p>"},"success":true}"""

        assertEquals("<p>募集资金用于项目建设。</p>", FundamentalsClient.parseAnnouncementContent(text))
    }

    @Test
    fun announcementDetailParserExtractsRichPagedBodyInsteadOfOnlyDirectoryTitles() {
        val text = """{"data":{"notice_content":"{\"titles\":[{\"content\":\"目录\"}],\"page2\":\"<p>募集资金用于项目建设，并补充流动资金。</p>\"}"},"success":1}"""

        val content = FundamentalsClient.parseAnnouncementContent(text)!!

        assertTrue(content.contains("募集资金用于项目建设"))
        assertFalse(content.contains("目录"))
    }

    @Test
    fun brokenPayloadsDegradeInsteadOfThrowing() {
        assertTrue(FundamentalsClient.parsePeriods("{}", "{}").isEmpty())
        assertTrue(FundamentalsClient.parseAnnouncements("not json").isEmpty())
        assertTrue(FundamentalsClient.parseMainBusiness("""{"result":{"data":[]}}""").isEmpty())
        assertNull(FundamentalsClient.parseBalance("""{"result":{"data":[]}}"""))
        assertNull(FundamentalsClient.parseBoard("""{"result":{"data":[]}}"""))
    }

    @Test
    fun valuationSeriesKeepsNegativePeAsIsSoPercentilesCanFilterIt() {
        val text = """{"result":{"data":[""" +
            """{"TRADE_DATE":"2026-09-23 00:00:00","PE_TTM":47.1,"PB_MRQ":3.21,"BOARD_CODE":"016077","BOARD_NAME":"半导体"},""" +
            """{"TRADE_DATE":"2026-09-22 00:00:00","PE_TTM":null,"PB_MRQ":null,"BOARD_CODE":"016077","BOARD_NAME":"半导体"}""" +
            """]},"success":true}"""

        val points = FundamentalsClient.parseValuations(text)

        assertEquals(2, points.size)
        assertEquals("2026-09-23", points[0].date)
        assertEquals(47.1, points[0].peTtm!!, 1e-6)
        assertNull(points[1].peTtm)
        assertEquals("016077" to "半导体", FundamentalsClient.parseBoard(text))
    }

    @Test
    fun mainBusinessParsesProductLinesWithTheirOwnGrossMargin() {
        val text = """{"result":{"data":[""" +
            """{"REPORT_DATE":"2026-06-30 00:00:00","MAINOP_TYPE":"1","ITEM_NAME":"集成电路",""" +
            """"MAIN_BUSINESS_INCOME":10510568360.78,"MBI_RATIO":0.999994,"GROSS_RPOFIT_RATIO":0.14346},""" +
            """{"REPORT_DATE":"2026-06-30 00:00:00","MAINOP_TYPE":"1","ITEM_NAME":"LED",""" +
            """"MAIN_BUSINESS_INCOME":59253.42,"MBI_RATIO":0.000006,"GROSS_RPOFIT_RATIO":-7.275679}""" +
            """]},"success":true}"""

        val items = FundamentalsClient.parseMainBusiness(text)

        assertEquals(2, items.size)
        assertEquals(1, items[0].type)
        assertEquals(0.14346, items[0].grossMargin!!, 1e-9)
        assertEquals(-7.275679, items[1].grossMargin!!, 1e-9)
    }

    @Test
    fun marketCodeMappingCoversShanghaiShenzhenAndBeijing() {
        assertEquals("002185.SZ", FundamentalsClient.secucodeOf("sz002185"))
        assertEquals("600519.SH", FundamentalsClient.secucodeOf("sh600519"))
        assertEquals("430047.BJ", FundamentalsClient.secucodeOf("bj430047"))
        assertEquals("0.002185", FundamentalsClient.secidOf("sz002185"))
        assertEquals("1.600519", FundamentalsClient.secidOf("sh600519"))
    }
}
