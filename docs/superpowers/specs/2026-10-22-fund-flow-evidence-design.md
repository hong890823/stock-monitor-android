# Fund Flow Evidence Design

## Window And Scope

- Keep at most five trading-day snapshots per A-share stock.
- Use current-day, recent 3-day, and recent 5-day views. The 3-day window is the main persistence signal; the 5-day view is context.
- Fewer than three valid daily samples remain in “样本积累中” and do not change scores or promotion.
- Holdings and watchlist reports use the same rolling-window evidence for analysis and risk wording only.
- Observation-pool promotion may add at most one point or temporarily defer promotion. It cannot skip price, trend, position, seal-quality, opening-count, or confirmation gates.
- Fund flow never enters `IntradayTactics`, buy/sell alerts, T alerts, stop-loss alerts, or sellable-quantity calculations.

## Goal

Add conservative order-size fund-flow evidence to limit-up/down reporting, stock analysis,
holding/watchlist display, and observation-pool promotion. Fund flow is an auxiliary signal
and must never independently create a buy, sell, or elimination decision.

## Data Model

Create a shared `FundFlow` snapshot:

- `code`, `name`, `asOfMs`
- `amountYuan`
- `mainNetYuan`, `mainNetPct`
- `superLargeNetYuan`, `superLargeNetPct`
- `largeNetYuan`, `largeNetPct`
- `mediumNetYuan`, `mediumNetPct`
- `smallNetYuan`, `smallNetPct`

All values are nullable and remain `null` when the source omits or returns `-` for a field.
The wording must say “大单/超大单主动买卖净流向” or “主力净流向（按订单规模归类）”.
It must not claim that the values identify institutions, tycoons, or the “main force”.

## Assessment

`FundFlowPolicy.assess(changePct: Double?, flow: FundFlow?)` returns one of:

- `SUPPORTIVE`: price is up more than 0.5% and main net ratio is at least +3%.
- `DIVERGENT`: price is up more than 0.5% and main net ratio is at most -3%.
- `RISK`: price is down more than 0.5% and main net ratio is at most -3%.
- `ABSORPTION_WATCH`: price is down more than 0.5% and main net ratio is at least +3%.
- `NEUTRAL`: all other valid combinations.
- `UNAVAILABLE`: flow or price is missing/non-finite.

`abs(mainNetPct) >= 10%` is marked strong, but does not change the action boundary. Samples
with `amountYuan < 5,000,000` are marked low-confidence and cannot affect promotion gates.

## Strategy Rules

- `SUPPORTIVE` adds at most one point to an existing analysis score.
- `SUPPORTIVE` may increase observation-pool confirmation, but cannot skip trend, risk,
  stop-price, entry-confirmation, or limit-up gates.
- `DIVERGENT`, `RISK`, and `ABSORPTION_WATCH` can delay observation-pool promotion for that
  evaluation. They do not eliminate a candidate or increment `weakDays`.
- Existing risk sell, break-support, confirmed-breakdown, and fundamental-risk signals remain
  active. Fund-flow text may reinforce them but cannot cancel them.
- `IntradayTactics` deliberately does not consume fund-flow evidence. Intraday buy/sell/T-trade
  reminders remain governed only by price, volume, trend, position, and risk rules, so an
  order-size statistic can never suppress or create a time-sensitive alert.
- Missing flow data leaves existing scores and transitions unchanged.
- In a sealed limit-up state, seal amount, seal ratios, opening count, and seal times have
  priority. Fund flow is weak evidence until the board opens.

## Integration

- `EastMoneyFundFlowClient.flows(codes: List<String>): Map<String, FundFlow>` batches up to
  50 A-share codes and parses East Money fields `f6`, `f62`, `f184`, `f66/f69`, `f72/f75`,
  `f78/f81`, and `f84/f87`.
- `MonitorService` refreshes flows for holdings, watchlist items, and eligible limit stocks
  after quote refresh. Flows are cached in `Repo` and remain nullable.
- `StockAnalysis.analyze` accepts optional `FundFlow`, exposes `fundFlowAssessment` and
  `fundFlowNote`, and changes score by no more than one point.
- `ObservationSignal` accepts `FundFlowAssessment?`. `ObservationPool` may add one point for
  supportive evidence and blocks new promotion for divergent/risk/low-confidence evidence.
- Limit reports display main net amount and ratio after the seal-quality metrics; fund flow is
  a secondary ordering key and cannot outrank seal quality.

## Data Quality And Copy

- Missing or stale values render as `—`.
- Low-confidence samples are labelled “样本偏小”.
- Reports show source and snapshot time.
- No wording may treat order-size classification as verified institutional activity.
- API failure keeps the last cached snapshot visible with its original timestamp.
