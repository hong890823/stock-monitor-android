# Today Headlines Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a cached, automatically refreshed “今日要闻” card above “盘面总结” with concise source-labelled market headlines.

**Architecture:** Fetch current-day public finance headlines from East Money with Sina fallback, classify and deduplicate them in a pure selector, cache the result in `Repo`/`Store`, and render a compact five-item card with a twenty-item detail report.

**Tech Stack:** Kotlin, Android programmatic UI, JUnit 4, org.json, East Money and Sina public news JSON/JSONP, existing `Http`, `MonitorService`, `Repo`, `Store`, and `ReportActivity`.

**Spec:** `docs/superpowers/specs/2026-10-22-today-headlines-design.md`

## Global Constraints

- Kotlin/JVM target 17; Android minSdk 26.
- Only current-local-day items can appear in “今日要闻”.
- Every headline displays its actual source and publication time.
- Do not infer bullish/bearish direction from neutral titles.
- Network refresh is background-only; UI render methods remain synchronous readers of cached state.
- Do not create Git commits; the user will review and test the working tree.

---

### Task 1: News model and source parsers

**Files:**
- Create: `app/src/main/java/com/example/stockmonitor/core/MarketNewsClient.kt`
- Test: `app/src/test/java/com/example/stockmonitor/core/MarketNewsParserTest.kt`

**Interfaces:**
- Consumes: `Http.getText`.
- Produces: `data class NewsItem(title, publishedAtMs, source, url, rawCategory)`, `enum class NewsSource { EASTMONEY, SINA }`, `MarketNewsClient.parseEastMoney(text: String): List<NewsItem>`, `MarketNewsClient.parseSina(text: String): List<NewsItem>`, and `MarketNewsClient.today(nowMs: Long): List<NewsItem>`.

- [ ] **Step 1: Write failing fixture tests for East Money and Sina response shapes, source attribution, timestamps, URL preservation, and malformed rows.**

```kotlin
@Test fun eastMoneyParserPreservesActualSource() {
    val items = MarketNewsClient.parseEastMoney(eastMoneyFixture)
    assertEquals("东方财富", items.first().source)
    assertEquals("政策标题", items.first().title)
}

@Test fun malformedRowsAreIgnored() {
    val items = MarketNewsClient.parseSina("""{"result":{"data":[{"title":"","url":"x"},{"title":"有效","ctime":1760000000,"media_name":"新浪财经"}]}}""")
    assertEquals(listOf("有效"), items.map { it.title })
}
```

- [ ] **Step 2: Run `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.core.MarketNewsParserTest` and verify failure.**
- [ ] **Step 3: Implement exact-source parsers, JSON/JSONP unwrapping, local-day filtering, and East-Money-first/Sina-fallback fetching. East Money uses `https://np-listapi.eastmoney.com/comm/web/getNewsByColumns?client=web&biz=web_news&column=350&order=1&needInteractData=0&page_index=1&page_size=50`; fallback uses `https://feed.mix.sina.com.cn/api/roll/get?pageid=153&lid=2516&k=&num=50&page=1`.**
- [ ] **Step 4: Run targeted parser tests.**

### Task 2: Headline selection and classification

**Files:**
- Create: `app/src/main/java/com/example/stockmonitor/analysis/Headlines.kt`
- Test: `app/src/test/java/com/example/stockmonitor/analysis/HeadlineSelectorTest.kt`

**Interfaces:**
- Consumes: `NewsItem`.
- Produces: `enum class NewsCategory { MACRO_POLICY, SECTOR_INDUSTRY, COMPANY_EVENT, MARKET_GENERAL }`, `data class SelectedHeadline(item, category)`, and `HeadlineSelector.select(items: List<NewsItem>, nowMs: Long, limit: Int): List<SelectedHeadline>`.

- [ ] **Step 1: Write failing tests for category priority, recency tie-breaks, duplicate removal, five-item primary limit, twenty-item detail limit, and neutral direction wording.**

```kotlin
@Test fun macroPolicyOutranksCompanyEventAtSimilarTime() {
    val selected = HeadlineSelector.select(listOf(companyNews, centralBankNews), nowMs, 5)
    assertEquals("央行部署", selected.first().item.title)
}

@Test fun duplicateNormalizedTitlesAreRemoved() {
    val selected = HeadlineSelector.select(listOf(first.copy(title = "  A股重要 政策 "), second), nowMs, 5)
    assertEquals(1, selected.size)
}
```

- [ ] **Step 2: Run `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.analysis.HeadlineSelectorTest` and verify failure.**
- [ ] **Step 3: Implement keyword classification, deterministic normalization/deduplication, category/recency ranking, and stable limits.**
- [ ] **Step 4: Run targeted selector tests.**

### Task 3: Cache and background refresh state

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/monitor/Repo.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/core/Store.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`
- Create: `app/src/main/java/com/example/stockmonitor/monitor/TodayHeadlinesState.kt`
- Test: `app/src/test/java/com/example/stockmonitor/TodayHeadlinesStateTest.kt`

**Interfaces:**
- Consumes: `MarketNewsClient.today`, `HeadlineSelector.select`.
- Produces: `TodayHeadlinesState.shouldRefresh(nowMs: Long): Boolean`, `Repo.newsTask: BackgroundTaskState`, `Repo.todayHeadlines: List<SelectedHeadline>`, `Repo.newsUpdatedAtMs: Long`, persisted `Store.lastNewsJson`/`lastNewsUpdatedAtMs`, and `MonitorService.ACTION_NEWS`.

- [ ] **Step 1: Write failing tests for duplicate-task suppression, last-valid-cache retention on failure, restart round-trip, and the 30-minute refresh interval.**

```kotlin
@Test fun failedRefreshRetainsLastValidHeadlines() {
    state.setCached(selected, updatedAtMs = oneHourAgo)
    state.refreshResult = failure
    state.refresh(nowMs)
    assertEquals(selected, state.headlines)
    assertEquals(oneHourAgo, state.updatedAtMs)
}

@Test fun refreshWithinThirtyMinutesIsSkipped() {
    state.setCached(selected, updatedAtMs = nowMs - 29 * 60_000)
    assertFalse(state.shouldRefresh(nowMs))
}
```

- [ ] **Step 2: Run `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.TodayHeadlinesStateTest` and verify failure.**
- [ ] **Step 3: Add task state, JSON persistence, 08:30-22:00 scheduling, and stale-cache retention.**
- [ ] **Step 4: Run targeted state tests and existing Store/MonitorService tests.**

### Task 4: Tools card and full report

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/ui/ToolsFragment.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/ToolAnalysisCards.kt`
- Test: `app/src/test/java/com/example/stockmonitor/TodayHeadlinesCardTest.kt`

**Interfaces:**
- Consumes: `Repo.newsTask`, `Repo.todayHeadlines`, `Repo.newsUpdatedAtMs`, `Store.lastNewsJson`, and `ReportActivity.open`.
- Produces: a “今日要闻” card immediately before “盘面总结”, five compact rows, refresh action, and a twenty-item “查看全部要闻” report.

- [ ] **Step 1: Write failing structural tests for card order, five-row primary content, source/time labels, refresh action, detail action, stale timestamp, and empty state.**

```kotlin
@Test fun todayHeadlinesCardPrecedesMarketSummary() {
    val layout = renderToolsLayout(news = selected)
    assertTrue(layout.indexOf("今日要闻") < layout.indexOf("盘面总结"))
}

@Test fun cardShowsSourceAndPublicationTime() {
    val text = renderToolsLayout(news = selected)
    assertTrue(text.contains("东方财富"))
    assertTrue(text.contains("10:25"))
}
```

- [ ] **Step 2: Run `./gradlew :app:testDebugUnitTest --tests com.example.stockmonitor.TodayHeadlinesCardTest` and verify failure.**
- [ ] **Step 3: Add the card, cached empty/stale states, refresh controls, and the existing report-page detail flow.**
- [ ] **Step 4: Run targeted tests and the ToolsFragment tests.**

### Task 5: Documentation and full verification

**Files:**
- Modify: `README.md`
- Test: full unit suite and debug assembly

**Interfaces:**
- Consumes: all news selection, refresh, cache, and UI behavior.
- Produces: documented data sources and refresh policy.

- [ ] **Step 1: Document that headlines are source-labelled public data, classification is heuristic, and no direction is inferred from neutral titles.**
- [ ] **Step 2: Run `./gradlew :app:testDebugUnitTest assembleDebug` and require zero failures/errors.**
- [ ] **Step 3: Run `git diff --check` and verify the “今日要闻” card is above “盘面总结” without overlapping or excessive height.**
