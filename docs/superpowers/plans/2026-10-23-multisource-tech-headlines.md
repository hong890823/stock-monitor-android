# Multi-Source Tech Headlines Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend “今日要闻” from an East Money/Sina fallback feed into independently degrading investment and technology lanes with relevance-aware ranking.

**Architecture:** Add typed source profiles and RSS/Atom parsing beside the existing JSON parsers, merge partial source results, cluster near-duplicate stories, then score and select separate investment and technology lists. Persist the enriched selected items and render the two lanes from cached state.

**Tech Stack:** Kotlin, Android minSdk 26, JUnit 4, org.json, JDK/Android DOM XML parsing, existing Http, Coroutines, Store, Repo, MonitorService, and programmatic UI.

**Spec:** `docs/superpowers/specs/2026-10-22-today-headlines-design.md`

## Global Constraints

- Keep every current-day headline’s real source and publication time.
- One failed feed must not discard successful feeds or be attributed to another source.
- Do not infer bullish/bearish direction from neutral titles.
- UI render methods remain synchronous readers of cached state.
- Do not create Git commits; the user reviews the working tree.

---

### Task 1: Typed source model and RSS/Atom parser

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/core/MarketNewsClient.kt`
- Create: `app/src/main/java/com/example/stockmonitor/core/TechNewsFeedParser.kt`
- Test: `app/src/test/java/com/example/stockmonitor/core/TechNewsFeedParserTest.kt`

**Interfaces:**
- Produces `NewsTrack`, `NewsSourceProfile`, and `TechNewsFeedParser.parse(text, profile): List<NewsItem>`.
- Supports RSS 2.0 `item` and Atom `entry`, including title, link, publication timestamp, and source attribution.

- [ ] Write failing parser tests for RSS, Atom, malformed rows, source attribution, and timestamps.
- [ ] Run the tests and verify they fail because the parser does not exist.
- [ ] Implement typed source metadata and the XML parser.
- [ ] Run the focused tests until they pass.

### Task 2: Partial multi-source fetch

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/core/MarketNewsClient.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`
- Test: `app/src/test/java/com/example/stockmonitor/core/MarketNewsClientTest.kt`

**Interfaces:**
- Produces `NewsFetchResult(items: List<NewsItem>, failures: List<NewsSourceFailure>)`.
- `MarketNewsClient.today(nowMs): NewsFetchResult` merges East Money, Sina, InfoQ China, Jiqizhixin, 36Kr, and Techmeme while filtering to the Shanghai-local current day.
- Throws only when every source fails.

- [ ] Write failing tests for source metadata propagation and all-source failure semantics using pure result-merging helpers.
- [ ] Implement independent source attempts and partial-result merging.
- [ ] Update the monitor refresh path to retain partial source failures in task status.
- [ ] Run focused and existing news tests.

### Task 3: Relevance scoring, clustering, and two-lane selection

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/Headlines.kt`
- Create: `app/src/test/java/com/example/stockmonitor/analysis/HeadlineSelectorTest.kt`

**Interfaces:**
- Produces `NewsFocusContext`, `HeadlineScore`, and enriched `SelectedHeadline`.
- `HeadlineSelector.select(items, nowMs, limit, focusContext)` clusters near-duplicates, scores representatives, and reserves balanced investment/technology slots.

- [ ] Write failing tests for held/watch relevance, material tech events, promotional penalties, duplicate clustering, corroboration, freshness, and lane balance.
- [ ] Implement scoring and clustering in small pure functions.
- [ ] Run the focused selector suite until it passes.

### Task 4: Persistence and two-lane UI

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/core/MarketNewsJson.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/ui/ToolsFragment.kt`
- Test: `app/src/test/java/com/example/stockmonitor/core/MarketNewsJsonTest.kt`

- [ ] Write failing round-trip tests for track, source id, score, and supporting-source fields while accepting legacy cache JSON.
- [ ] Implement compatible persistence and build focus context from holdings, watchlist, and observation pool.
- [ ] Render `投资要闻` and `科技重点`, each with up to three cached items and a combined detail report.
- [ ] Run all news-related unit tests, then the full debug unit test suite.
