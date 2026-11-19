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
    fun selectionListsUseObservationSpecificRankingWithoutChangingOtherBoardRankingLists() {
        val source = TestProjectFiles
            .source("com/example/stockmonitor/ui/ScreenFragment.kt")
            .readText()

        assertTrue(source.contains("StrongSectorRankingPolicy.rankByCombined("))
        assertTrue(source.contains("ObservationDisplayRankingPolicy.rank("))
        assertTrue(source.contains("private fun strongSectorRanking("))
        assertTrue(source.contains("候选排序"))
        assertTrue(source.contains("技术快照暂缺"))
        assertTrue(source.contains("板块只作同条件裁决"))
        assertTrue(source.contains("UiKit.DetailRow(\"板块标记\""))
        assertTrue(source.contains("UiKit.DetailRow(\"技术趋势\""))
        assertFalse(source.contains("item.score.coerceIn(0, 3) * 40 / 3"))
        assertFalse(source.contains("强势板块决定强势观察档位"))
    }

    @Test
    fun observationPoolUsesStrictStateGroupsAndStructureFirstOrdering() {
        val source = TestProjectFiles
            .source("com/example/stockmonitor/ui/ScreenFragment.kt")
            .readText()

        assertTrue(source.contains("\"状态总览\""))
        assertTrue(source.contains("\"状态顺序\""))
        assertTrue(source.contains("addText(\"1️⃣ ✅ 可参与候选（买点已确认）\")"))
        assertTrue(source.contains("addText(\"2️⃣ 🧭 待确认（尚不能直接参与）\")"))
        assertTrue(source.contains("⭐ 优先复参与：曾确认买点、结构仍有效，等待新的盘中确认"))
        assertTrue(source.contains("⏳ 其他待确认：回踩、量价或连续复评仍差最后确认"))
        assertTrue(source.contains("addText(\"3️⃣ 👀 观察中（继续跟踪结构演变）\")"))
        assertTrue(source.contains("addText(\"4️⃣ ⏸ 暂缓参与（封板或已明确标记暂缓）\")"))
        assertTrue(source.contains("addText(\"5️⃣ ⚠ 风险观察（只列有明确证据的个股风险）\")"))
        assertTrue(source.contains("val observationDetails = buildString"))
        assertTrue(source.contains("text = \"查看观察池详情 ›\""))
        assertTrue(source.contains("appendLine(\"策略路线\")"))
        assertTrue(source.contains("appendLine(\"晋级规则\")"))
        assertTrue(source.indexOf("title = \"✅ 可参与候选（") <
            source.indexOf("title = \"⭐ 优先复参与（"))
        assertTrue(source.indexOf("title = \"⭐ 优先复参与（") <
            source.indexOf("title = \"⏳ 其他待确认（"))
        assertTrue(source.indexOf("title = \"⏳ 其他待确认（") <
            source.indexOf("title = \"👀 观察中（"))
        assertTrue(source.indexOf("title = \"👀 观察中（") <
            source.indexOf("title = \"⏸ 暂缓参与（"))
        assertTrue(source.indexOf("title = \"⏸ 暂缓参与（") <
            source.indexOf("title = \"⚠ 风险观察（"))
        assertTrue(source.contains("android.graphics.drawable.GradientDrawable"))
        assertTrue(source.contains("title.substringBefore(\"（\")}列表"))
        assertTrue(source.contains("ObservationEligibility.prioritizedSections("))
        assertTrue(source.contains("⚠ 买点已确认，存在风险"))
        assertTrue(source.contains("⚠ 买点待确认，存在风险"))
        assertTrue(source.contains("风险消失后再复评"))
        assertFalse(source.contains("风险未解除"))
        assertFalse(source.contains("风险信号暂缺"))
        assertTrue(source.indexOf("val riskSummary = ObservationEligibility.riskSummary(item, currentFlow, flowHistory)") <
            source.indexOf("val statusLabel = when {"))
        assertTrue(source.indexOf("riskSummary != null -> UiKit.color(context, R.color.risk_alert)") <
            source.indexOf("item.stage == ObservationStage.BUY_CANDIDATE ->"))
        assertTrue(source.contains("\"晋级规则\""))
        assertTrue(source.contains("3日走弱/10日未确认淘汰"))
        assertTrue(source.contains("状态档位优先；同档按个股结构"))
        assertFalse(source.contains("🔥 强势观察（"))
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
        assertTrue(source.contains("ObservationDisplayRankingPolicy.rank("))
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
