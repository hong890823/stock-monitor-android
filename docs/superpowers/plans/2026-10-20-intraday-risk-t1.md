# Intraday Risk And T+1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add trade-lot T+1 eligibility, risk-first intraday notifications, and 15-minute execution plus 30-minute filtering.

**Architecture:** A pure Kotlin trade ledger derives sellable quantities from recorded transactions and legacy positions. Minute-bar policy gates non-risk actions, while risk actions bypass normal throttles. The monitor service connects quotes, ledger state, minute bars, signal evaluation, and notification delivery.

**Tech Stack:** Kotlin, Android SharedPreferences, org.json, JUnit 4, existing Sina quote/K-line client.

**Spec:** `docs/superpowers/specs/2026-10-20-intraday-risk-t1.md`

## Current State

- Tasks 1-7 are implemented. The trade ledger, persisted transaction UI, sellable-share caps,
  immediate risk-sell delivery, 15/30-minute gate, and copy/documentation updates are integrated.
- Final verification passes: 660 unit tests with 0 failures/errors/skips, plus `assembleDebug --offline`.

## Global Constraints

- A-share buys cannot be sold on the same trading day.
- Legacy positions are sellable after migration and are never invented as same-day buys.
- Sell suggestions must not exceed `todaySellable`.
- Risk sell alerts are immediate and bypass `intradaySignalEnabled`, `SIGNAL_COOLDOWN_MS`, and `SIGNAL_MAX_PER_DAY`.
- Non-risk buy/exit/T alerts require complete 15/30-minute data and no opposing 30-minute direction.
- Notification copy must distinguish legacy/sellable shares from same-day locked shares.
- No new third-party dependency.

## Revised Execution Order

The task numbers below remain stable for reference, but execution order changes to keep the feature usable and safety-critical work first:

1. Finish Task 1 verification and Task 2 verification.
2. Execute Task 6 first: transaction application policy plus the transaction-entry UI, so users can actually record same-day buys and sells.
3. Execute Task 3: cap every sell-side suggestion with `TradeAvailability`.
4. Execute Task 4: make risk-sell delivery immediate and independent of ordinary signal throttles.
5. Execute Task 5: add 15/30-minute confirmation only after T+1 and risk timing are safe.
6. Execute Task 7 last: align user-facing copy and README with the verified behavior.

Rationale: keeping the UI after dual-timeframe work would leave the new T+1 ledger unable to receive real transactions, and delaying risk-alert integration would preserve the timing defect called out in the conversation.

## File Structure

- `app/src/main/java/com/example/stockmonitor/core/TradeLots.kt`: immutable trade, lot, availability, and FIFO ledger policy.
- `app/src/main/java/com/example/stockmonitor/core/TradeLotJson.kt`: persistence codec for the complete `TradeBook` (records plus open lots).
- `app/src/main/java/com/example/stockmonitor/core/IntradayBars.kt`: bar model, integrity checks, and 15/30-minute policy.
- `app/src/main/java/com/example/stockmonitor/core/Store.kt`: persist `TradeBook` as `trade_book`, preserving both transaction history and open lots.
- `app/src/main/java/com/example/stockmonitor/analysis/TradeNotification.kt`: cap copy by sellable quantity and clarify expiry text.
- `app/src/main/java/com/example/stockmonitor/monitor/Notifier.kt`: dedicated risk-sell delivery.
- `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`: ledger loading, minute-bar refresh, risk-first dispatch, and dual-timeframe gate.
- `app/src/main/java/com/example/stockmonitor/ui/Dialogs.kt`: record buy/sell transactions safely.
- `app/src/main/java/com/example/stockmonitor/ui/PortfolioFragment.kt`: show availability and open transaction entry.
- Tests: `TradeLotsTest.kt`, `TradeLotJsonTest.kt`, `IntradayBarsTest.kt`, plus updates to `TradeNotificationTest.kt`, `IntradayTacticsTest.kt`, and `Store`-adjacent behavior tests.

---

### Task 1: Trade Lot Ledger

**Files:**
- Create: `app/src/main/java/com/example/stockmonitor/core/TradeLots.kt`
- Test: `app/src/test/java/com/example/stockmonitor/core/TradeLotsTest.kt`

**Interfaces:**
- Consumes: legacy `Position` values and explicit `TradeRecord` entries.
- Produces: `TradeLotLedger.availability(book, positions, date): List<TradeAvailability>`, `TradeLotLedger.availability(book, positions, date, code): TradeAvailability`, `TradeLotLedger.migrate(positions): TradeBook`, `TradeLotLedger.buy`, `TradeLotLedger.sell`, and `TradeRecord`.

- [x] **Step 1: Write failing tests for legacy migration, same-day lock, FIFO selling, and oversized sells.**
- [x] **Step 2: Run `./gradlew testDebugUnitTest --tests com.example.stockmonitor.core.TradeLotsTest` and verify failure.**
- [x] **Step 3: Implement validated records, FIFO lots, legacy lots, and availability calculation.**
- [x] **Step 4: Run the same tests and verify pass.**
- [x] **Step 5: Run the full unit suite.**

### Task 2: Persistence Compatibility

**Files:**
- Create: `app/src/main/java/com/example/stockmonitor/core/TradeLotJson.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/core/Store.kt`
- Test: `app/src/test/java/com/example/stockmonitor/core/TradeLotJsonTest.kt`

**Interfaces:**
- Produces: `TradeLotJson.encode(TradeBook): String`, `TradeLotJson.decode(String?): TradeBook`, and `Store.tradeBook`.

- [x] **Step 1: Write failing codec and default-empty Store-compatible tests.**
- [x] **Step 2: Run the targeted tests and verify failure.**
- [x] **Step 3: Implement tolerant decoding and a `trade_book` preference key.**
- [x] **Step 4: Run targeted and full tests.**

### Task 3: Sellable Quantity Notifications

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/TradeNotification.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/IntradayTactics.kt`
- Test: `app/src/test/java/com/example/stockmonitor/TradeNotificationTest.kt`

**Interfaces:**
- Consumes: `TradeAvailability`.
- Produces: `TradeNotification.sellQuantity(total, sellable, exit)`, updated `IntradayTactics.Input.sellableQuantity`, and expiry copy.

- [x] **Step 1: Write failing tests proving 500 legacy plus 100 same-day shares only permits 500 to be sold.**
- [x] **Step 2: Run targeted tests and verify failure.**
- [x] **Step 3: Cap all sale sizing at today's sellable quantity and add locked-share copy.**
- [x] **Step 4: Run targeted and full tests.**

### Task 4: Immediate Risk-Sell Channel

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/monitor/Notifier.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/monitor/MonitorService.kt`
- Test: `app/src/test/java/com/example/stockmonitor/TradeNotificationTest.kt`

**Interfaces:**
- Produces: `Notifier.riskSell(context, id, title, text)` and risk-first dispatch rules.

- [x] **Step 1: Write tests for risk-copy priority, same-day-only risk copy, and sellable-share caps.**
- [x] **Step 2: Run targeted tests and verify failure.**
- [x] **Step 3: Deliver risk signals even when ordinary intraday alerts are disabled, bypassing cooldown and daily caps.**
- [x] **Step 4: Run targeted and full tests.**

### Task 5: Minute Bars And Dual-Timeframe Gate

**Files:**
- Create: `app/src/main/java/com/example/stockmonitor/core/IntradayBars.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/core/SinaClient.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/analysis/IntradayTactics.kt`
- Test: `app/src/test/java/com/example/stockmonitor/core/IntradayBarsTest.kt`

**Interfaces:**
- Produces: `SinaClient.intradayKline(code, minutes, length)`, `IntradayBarWindow.complete`, `MinuteTrend`, and `DualTimeframeDecision`.

- [x] **Step 1: Write failing tests for parsing, integrity, aligned direction, opposing direction, and risk bypass.**
- [x] **Step 2: Run targeted tests and verify failure.**
- [x] **Step 3: Implement 15/30-minute parsing, live-bar upsert, integrity checks, and policy decisions.**
- [x] **Step 4: Run targeted and full tests.**

### Task 6: Transaction Entry UI

**Files:**
- Modify: `app/src/main/java/com/example/stockmonitor/ui/Dialogs.kt`
- Modify: `app/src/main/java/com/example/stockmonitor/ui/PortfolioFragment.kt`
- Test: pure policy coverage in `TradeLotsTest.kt`

**Interfaces:**
- Consumes: `Store.tradeBook`, `Store.portfolio`, and `TradeLotLedger`.
- Produces: one explicit buy/sell transaction flow that updates both records and portfolio totals.

- [x] **Step 1: Add failing policy tests for applying a buy and an allowed/disallowed sell to a portfolio.**
- [x] **Step 2: Run targeted tests and verify failure.**
- [x] **Step 3: Implement transaction application policy.**
- [x] **Step 4: Add the holdings-page button and dialog with date/side/quantity/price validation.**
- [x] **Step 5: Show `今日可卖 / 今日买入锁定 / 下一交易日可卖` beside holdings.**
- [x] **Step 6: Run full unit tests and `assembleDebug --offline`.**

### Task 7: Copy And Documentation

**Files:**
- Modify: `README.md`
- Modify: `app/src/main/java/com/example/stockmonitor/ui/MonitoringCopy.kt`
- Test: `app/src/test/java/com/example/stockmonitor/TradeNotificationTest.kt`

- [x] **Step 1: Write failing copy tests for “板块状态参考” and “补仓窗口失效并停止执行”.**
- [x] **Step 2: Run targeted tests and verify failure.**
- [x] **Step 3: Update leader-status, expiry, T+1, and dual-timeframe wording.**
- [x] **Step 4: Run full tests and `assembleDebug --offline`.**
