package com.example.stockmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenListUiTest {
    @Test
    fun selectionListsUseExpandableRowsAndSectionControls() {
        val source = TestProjectFiles
            .source("com/example/stockmonitor/ui/ScreenFragment.kt")
            .readText()

        assertTrue(source.contains("UiKit.expandableQuoteRow("))
        assertTrue(source.contains("QuoteRowExpansionState.toggleAll(rowKeys)"))
        assertTrue(source.contains("val pageSize = ObservationEligibility.PAGE_SIZE"))
        assertTrue(source.contains("fun showPage(targetPage: Int, scrollToFirstItem: Boolean = false)"))
        assertTrue(source.contains("showPage(target, scrollToFirstItem = true)"))
        assertTrue(source.contains("pageContent.removeAllViews()"))
        assertTrue(source.contains("strongRankingCache.clear()"))
        assertTrue(source.contains("pageKey = \"observation:"))
        assertTrue(source.contains("pageKey = \"indicator:strict\""))
        assertTrue(source.contains("pageKey = \"indicator:relaxed\""))
        assertTrue(source.contains("pageKey = \"deep:513\""))
        assertTrue(source.contains("pageKey = \"deep:trend\""))
        assertFalse(source.contains("MaterialAlertDialogBuilder("))
        assertFalse(source.contains("result.fiveOneThree.take(100)"))
    }

    @Test
    fun selectionDetailsKeepEvidenceAndMetricsStructured() {
        val source = TestProjectFiles
            .source("com/example/stockmonitor/ui/ScreenFragment.kt")
            .readText()

        assertTrue(source.contains("private fun screenDetails(hit: ScreenHit)"))
        assertTrue(source.contains("private fun fiveOneThreeDetails(hit: FiveOneThreeHit)"))
        assertTrue(source.contains("private fun trendDetails(hit: TrendScreenHit)"))
        assertTrue(source.contains("UiKit.DetailRow(\"支持证据\""))
        assertTrue(source.contains("UiKit.DetailRow(\"失效条件\""))
        assertTrue(source.contains("UiKit.DetailRow(\"价格位置\""))
        assertTrue(source.contains("UiKit.DetailRow(\"大盘过滤\""))
    }

    @Test
    fun selectionListsRankStrongSectorAndCoreRoleBonusFirst() {
        val source = TestProjectFiles
            .source("com/example/stockmonitor/ui/ScreenFragment.kt")
            .readText()

        assertTrue(source.contains("StrongSectorRankingPolicy.rankByCombined("))
        assertTrue(source.contains("private fun strongSectorRanking("))
        assertTrue(source.contains("综合质量分 ${'$'}overallScore/100"))
        assertFalse(source.contains("技术评分"))
        assertTrue(source.contains("🔥 持续主线："))
        assertTrue(source.contains("⚡ 单日强势："))
        assertTrue(source.contains("UiKit.DetailRow(\"板块标记\""))
        assertTrue(source.contains("UiKit.DetailRow(\"评分构成\""))
        assertTrue(source.contains("UiKit.DetailRow(\"技术趋势\""))
        assertTrue(source.contains("UiKit.DetailRow(\"主线持续\""))
        assertTrue(source.contains("/35（原始${'$'}{item.technicalScore}/100）"))
        assertTrue(source.contains("强势板块决定强势观察档位"))
        assertFalse(source.contains("只改变组内顺序，不参与晋级"))
        assertFalse(source.contains("强势板块+"))
        assertFalse(source.contains("强势板块加分"))
        assertFalse(source.contains("主线加分"))
    }

    @Test
    fun observationPoolPrioritizesStrongWatchThenUsesClearStatusGroups() {
        val source = TestProjectFiles
            .source("com/example/stockmonitor/ui/ScreenFragment.kt")
            .readText()

        assertTrue(source.contains("\"状态总览\""))
        assertTrue(source.contains("\"强弱顺序\""))
        assertTrue(source.contains("由强到弱：1️⃣ ✅ 可参与候选（买点已确认）→ 2️⃣ 🔥 强势观察（强而未确认）→ 3️⃣ 🕐 等待确认（条件未齐）→ 4️⃣ ⏸ 其他观察（偏弱/风险）"))
        assertTrue(source.contains("val observationDetails = buildString"))
        assertTrue(source.contains("text = \"查看观察池详情 ›\""))
        assertTrue(source.contains("appendLine(\"策略路线\")"))
        assertTrue(source.contains("appendLine(\"晋级规则\")"))
        assertTrue(source.indexOf("title = \"✅ 可参与候选（") <
            source.indexOf("title = \"🔥 强势观察（"))
        assertTrue(source.indexOf("title = \"🔥 强势观察（") <
            source.indexOf("title = \"🕐 等待确认（"))
        assertTrue(source.indexOf("title = \"🕐 等待确认（") <
            source.indexOf("title = \"⏸ 其他暂缓 / 封板 / 观察（"))
        assertTrue(source.contains("ObservationEligibility.sections("))
        assertTrue(source.contains("\"晋级规则\""))
        assertTrue(source.contains("3日走弱/10日未确认淘汰"))
        assertFalse(source.contains("阅读顺序："))
        assertFalse(source.contains("路线含义："))
        assertTrue(source.contains("下一步："))
        assertTrue(source.contains("资金流："))
        assertTrue(source.contains("flowWindow.summary"))
        assertFalse(source.contains("fun addStrategyGroup("))
        assertEquals(1, Regex("UiKit\\.DetailRow\\(\"入池日期\"").findAll(source).count())
    }

    @Test
    fun scanRulesMoveOutOfPrimaryListCards() {
        val source = TestProjectFiles
            .source("com/example/stockmonitor/ui/ScreenFragment.kt")
            .readText()
        val copy = TestProjectFiles
            .source("com/example/stockmonitor/ui/ScreenHelpCopy.kt")
            .readText()

        assertTrue(source.contains("ScreenHelpCopy.INDICATOR_SCAN_SUMMARY"))
        assertTrue(source.contains("ScreenHelpCopy.DEEP_SCAN_SUMMARY"))
        assertTrue(source.contains("ScreenHelpCopy.indicatorScanDetailText()"))
        assertTrue(source.contains("ScreenHelpCopy.deepScanDetailText()"))
        assertFalse(source.contains("DIF/DEA 零轴上方金叉"))
        assertFalse(source.contains("按 5 连阳 → 倍量阳 → 3 天缩量的顺序扫描"))
        assertTrue(copy.contains("fun observationDetailText()"))
        assertTrue(copy.contains("fun indicatorScanDetailText()"))
        assertTrue(copy.contains("fun deepScanDetailText()"))
    }

    @Test
    fun selectionCardsSharePersistedExactLimitBandFilter() {
        val source = TestProjectFiles
            .source("com/example/stockmonitor/ui/ScreenFragment.kt")
            .readText()

        assertTrue(source.contains("LimitUpFilterPolicy.Option"))
        assertTrue(source.contains("MaterialButtonToggleGroup"))
        assertTrue(source.contains("\"screen_limit_filter\""))
        assertTrue(source.contains("filterLimitUpRows"))
        assertTrue(source.contains("\"10cm\""))
        assertTrue(source.contains("\"20cm\""))
        assertTrue(source.contains("\"30cm\""))
    }

    @Test
    fun selectionRenderingPrecomputesRankingInputsAndCoalescesRebuilds() {
        val source = TestProjectFiles
            .source("com/example/stockmonitor/ui/ScreenFragment.kt")
            .readText()

        assertTrue(source.contains("private data class SelectionRankingContext"))
        assertTrue(source.contains("private fun selectionRankingContext()"))
        assertTrue(source.contains("strongSectorRanking(rankingContext"))
        assertTrue(source.contains(".strongWatch"))
        assertTrue(source.contains("private var renderPending"))
        assertTrue(source.contains("view?.post"))
        assertTrue(source.contains("turnoverRankByCode"))
        assertTrue(source.contains("changeRankPct"))
        assertTrue(source.contains("turnoverRankPct"))
        assertFalse(source.contains("rankPercentile(peers.map { it.change }"))
        assertFalse(source.contains("rankPercentile(peers.map { it.volume }"))
    }

    @Test
    fun selectionScrollingDefersRebuildsAndLazilyBuildsHiddenDetails() {
        val source = TestProjectFiles
            .source("com/example/stockmonitor/ui/ScreenFragment.kt")
            .readText()
        val uiKit = TestProjectFiles
            .source("com/example/stockmonitor/ui/UiKit.kt")
            .readText()

        assertTrue(source.contains("private var selectionScrolling"))
        assertTrue(source.contains("private val selectionScrollIdleRunnable"))
        assertTrue(source.contains("setOnScrollChangeListener"))
        assertTrue(source.contains("removeCallbacks(selectionScrollIdleRunnable)"))
        assertTrue(source.contains("postDelayed(selectionScrollIdleRunnable"))
        assertTrue(source.contains("if (selectionScrolling) return"))
        assertTrue(uiKit.contains("if (expanded && detailsView.childCount == 0)"))
        assertTrue(uiKit.contains("details.forEach { detail ->"))
    }

    @Test
    fun freshAppLaunchRestartsSelectionPagination() {
        val source = TestProjectFiles
            .source("com/example/stockmonitor/ui/MainActivity.kt")
            .readText()

        assertTrue(source.contains("resetSelectionPagination(freshLaunch = savedInstanceState == null)"))
        assertTrue(source.contains("getSharedPreferences(\"screen_list_pages\", Context.MODE_PRIVATE)"))
        assertTrue(source.contains(".edit().clear().apply()"))
        assertTrue(source.contains("Process.getStartElapsedRealtime()"))
        assertTrue(source.contains("override fun onDestroy()"))
        assertTrue(source.contains("if (isFinishing)"))
    }

    @Test
    fun tabSwitchingReusesAttachedFragmentsAndStopsHiddenRendering() {
        val activity = TestProjectFiles
            .source("com/example/stockmonitor/ui/MainActivity.kt")
            .readText()
        val baseFragment = TestProjectFiles
            .source("com/example/stockmonitor/ui/BaseFragment.kt")
            .readText()

        assertFalse(activity.contains(".replace(R.id.fragment_container, fragment)"))
        assertTrue(activity.contains("transaction.hide(it)"))
        assertTrue(activity.contains("transaction.show(fragment)"))
        assertTrue(activity.contains("transaction.add(R.id.fragment_container, fragment)"))
        assertTrue(activity.contains("(fragment as? BaseFragment)?.renderIfStale()"))
        assertTrue(baseFragment.contains("view?.isShown == true"))
        assertTrue(baseFragment.contains("private var dataDirty"))
        assertTrue(baseFragment.contains("fun renderIfStale()"))
        assertTrue(
            TestProjectFiles
                .source("com/example/stockmonitor/ui/ScreenFragment.kt")
                .readText()
                .contains("if (!selectionScrolling && isAdded && view?.isShown == true) renderNow()"),
        )
    }

    @Test
    fun selectionInitialRenderSurvivesHiddenAttachAndDeferredVisibility() {
        val screenFragment = TestProjectFiles
            .source("com/example/stockmonitor/ui/ScreenFragment.kt")
            .readText()
        val baseFragment = TestProjectFiles
            .source("com/example/stockmonitor/ui/BaseFragment.kt")
            .readText()

        assertFalse(
            screenFragment.contains(
                "override fun render() {\n        if (view?.isShown != true) return",
            ),
        )
        assertTrue(screenFragment.contains("view?.post {"))
        assertTrue(screenFragment.contains("markRenderDirty()"))
        assertTrue(baseFragment.contains("protected fun markRenderDirty()"))
    }

    @Test
    fun selectionScrollIdleDoesNotRebuildCleanContent() {
        val source = TestProjectFiles
            .source("com/example/stockmonitor/ui/ScreenFragment.kt")
            .readText()

        assertFalse(source.contains("selectionScrolling = false\n        render()"))
        assertTrue(source.contains("selectionScrolling = false\n        renderIfStale()"))
    }
}
