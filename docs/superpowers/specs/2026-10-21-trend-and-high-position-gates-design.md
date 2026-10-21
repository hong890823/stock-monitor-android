# Trend And High-Position Gates Design

## Goal

Add a shared trend decision layer so every buy, sell, T-trade, observation-pool promotion,
position-sizing recommendation, and intraday notification uses the same short-term trend,
major trend, and high-position structure rules.

The system must not present a high-level sideways pattern as a guaranteed future outcome.
It classifies observable evidence as risk, consolidation, second-wave candidacy, or
confirmation, and applies conservative action gates.

## Decision Model

### Short-Term Trend

`ShortTermTrend` has these states:

- `UP`: price is at or above MA5, MA5 > MA10 > MA20, and MA20 is rising.
- `EXTENDED`: five-day gain is at least `max(6%, 3 * ATR%)` and at least three of the last
  five close-to-close transitions are rising.
- `REBOUND`: price is above the previous close and MA5 is rising from its prior value,
  but the full moving-average alignment is not yet present.
- `DOWN`: price is below MA20 by more than 2%, or two consecutive closes are below the
  corresponding MA20 by more than 0.5%.
- `RANGE`: none of the above.
- `MISSING`: required clean, completed daily history is unavailable.

`HoldingTTrend` remains the calculation source for compatibility. A mapper converts it to
the shared state so existing reports do not silently change meaning.

### Major Trend

`MajorTrend` has these states:

- `UP`: completed weekly and monthly averages are rising, with the monthly state `UP` or
  `REPAIR`, and price is not below MA250.
- `DOWN`: weekly and monthly states are both `WEAK`, or price is below MA250 while both
  larger-timeframe states are `WEAK`.
- `BOTTOMING`: the larger timeframe was weak or price is below MA250, but the last five
  completed trading days made no new low, MA20 is flat or rising, price is at or above
  MA20, and weekly trend is no longer `WEAK`.
- `REPAIR`: one larger timeframe is weak or price is below MA250, but the bottoming
  evidence is incomplete.
- `MISSING`: the required history is missing or internally inconsistent.

The five-day no-new-low window uses completed daily bars only. The current live bar may
modify intraday alerts but cannot create a major-trend state by itself.

### High-Position Structure

`HighPositionPhase` is independent of the two trend axes:

- `NONE`: not in a high-position range.
- `EXTENDED_WATCH`: price is in the high range but the evidence is not yet sufficient.
- `DISTRIBUTION_RISK`: high range plus repeated failed progress, elevated volume without
  price progress, weakening closes, lower highs/lows, or a short-term support break.
- `CONSOLIDATION`: high range with a narrow price box, contracting pullback volume, rising
  lows, and no confirmed support break.
- `SECOND_WAVE_CANDIDATE`: consolidation plus a volume-supported rebound, higher lows,
  strong short-term direction, and a healthy market/sector environment.
- `SECOND_WAVE_CONFIRMED`: candidate plus a close above the range high by 0.5%, volume
  ratio at least 1.3, and the 15/30-minute gate allows execution.
- `UNCERTAIN`: evidence conflicts or required data is missing.

The first observation of a high-level sideways range is `EXTENDED_WATCH` or `UNCERTAIN`.
The system must not call it a second-wave buy before `SECOND_WAVE_CONFIRMED`.

## Action Gates

Risk sell, break-support, confirmed breakdown, and fundamental-risk alerts are always
allowed and bypass ordinary buy/T gates.

Normal buy, breakout, ordinary add-position, T-buy/rebuy, and T-sell are allowed only
when the major trend is `UP` and the short-term trend is `UP`, `EXTENDED`, or an explicitly
approved `REBOUND`. The 30-minute divergence/support-hold pattern is only a confirmation
layer for these allowed states; it never independently authorizes a trade.

When major trend is `DOWN`:

- normal buy, breakout, add-position, T-buy/rebuy, and ordinary T-sell are disabled;
- holdings receive risk/reduce/exit guidance;
- a temporary short-term rebound is labeled as a downtrend rebound, not an uptrend.

When major trend is `BOTTOMING`:

- only `BOTTOM_REVERSAL` observation or a light trial is allowed;
- trial size is capped at 25% of the configured initial position;
- normal add-position, breakout, T-buy/rebuy, and ordinary T-sell remain disabled until
  the major trend becomes `UP`;
- bottoming requires the five-day no-new-low rule and the other major-trend evidence.

When the high-position phase is `DISTRIBUTION_RISK`:

- no buy or T-buy;
- holdings prioritize reduce/exit risk signals;
- ordinary T-sell is not issued as a fresh opportunity.

When the high-position phase is `CONSOLIDATION` or `SECOND_WAVE_CANDIDATE`:

- no fresh buy or T-buy;
- a base position may be held;
- only a confirmed range breakout can become `SECOND_WAVE_CONFIRMED`.

## Data And Calculation Boundaries

Create a pure `TrendAssessmentPolicy` and `HighPositionPolicy`. They consume completed
daily history, `MultiTimeframeSummary`, `PricePosition`, ATR, volume ratio, chip estimate,
recent intraday bars, market regime, and sector regime. They do not perform network calls
or send notifications.

`IntradayTactics`, `ObservationPool`, `StockAnalysis`, `PortfolioSwing`, and
`TradeNotification` consume one shared `TrendAssessment`. Existing `HoldingTTrend`,
`SwingTrend`, `EntrySetup`, `MarketRegime`, and `SectorContext` calculations remain
available as evidence inputs.

The chip estimate is volume-weighted and is not real broker-level ownership. It is only
supporting evidence for high-position classification and must retain the existing caveat.

## Notification Semantics

Notifications state the trend and high-position state before the action:

- `大趋势向下·短期反弹`: no buy action.
- `大趋势筑底·底部反转观察`: light trial only, capped at 25% of initial position.
- `高位整理蓄势`: hold base and wait for range confirmation.
- `高位派发风险`: reduce/exit priority, no ordinary opportunity language.
- `二波突破确认`: only after range-high breakout with volume and dual-timeframe approval.

Unknown or conflicting evidence must produce a neutral “继续观察” message, never a
“will rise” or “will fall” prediction.

## Testing Requirements

Unit tests cover trend-state thresholds, bottoming evidence, high-position transitions,
trade eligibility matrices, position-size caps, notification copy, and regression cases
where market/sector weakness overrides an individual bullish pattern.

