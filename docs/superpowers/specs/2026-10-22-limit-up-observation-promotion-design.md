# Limit-Up Observation Promotion Design

## A-Share Trading Constraint

A-shares are T+1. Shares bought today cannot be sold today. The app uses “底仓做T” for rolling previously held sellable shares and always caps suggested sell quantities by actual sellable quantity.

## Goal

Allow qualifying limit-up stocks to enter the observation candidate pool, remain under
continuous observation while sealed, and promote only after the board opens and fresh entry
conditions are confirmed.

## Admission

Create `LimitCandidatePolicy` with `eligible(stock: LimitStock): Boolean`. A limit-up stock is
eligible when its East Money pool data is fresh and all of the following hold:

- `limitStreak >= 1`
- `openCount <= 1`
- at least one quality metric is strong: `sealToFloatPct >= 0.5` or
  `sealToTurnoverPct >= 10.0`

There is no fixed candidate count. All eligible stocks enter and pagination handles volume.

## State Machine

Add `ObservationStrategy.LIMIT_UP_CONTINUATION`.

- A sealed eligible stock is inserted as `OBSERVING`, `limitUpBlocked = true`, with reason
  “涨停封板，持续观察；开板后重新确认，不执行买入”.
- A sealed continuous candidate is exempt from `MAX_OBSERVED_DAYS` timeout while it remains
  actively sealed or continues a limit streak.
- Entry confirmation rounds are always zero while sealed and reset when the stock re-seals.
- After opening, the candidate uses time-separated fresh evaluations at a 15–45 minute interval.
  Cumulative quote volume is not used as an increasing-volume condition because it grows by
  construction. Three-board-and-above candidates require three rounds.
- `NEAR_ENTRY` requires ordinary trend/risk gates and non-limit-up price conditions.
- `BUY_CANDIDATE` additionally requires two fresh confirmation rounds and supportive or
  neutral fund-flow evidence.
- Fund flow can delay promotion but cannot create promotion.
- Promotion is delayed while the broad market is in a risk zone, limit-down breadth is clearly
  dominant, or all known associated boards are retreating. Missing environment data stays neutral.
- Reliable seal-quality scores adjust the bounded promotion score; weak quality on a
  three-board-and-above candidate blocks normal promotion.
- Re-sealing returns the candidate to continuous observation and resets confirmation rounds.
- Once no longer sealed, normal `MAX_WEAK_DAYS` and `MAX_OBSERVED_DAYS` rules resume.

## UI

The observation screen gains a “涨停持续观察” section before other observing items. It shows
code/name, streak, seal quality, current state, fund-flow tone, and the next confirmation
condition. A sealed stock never appears in buy or near-entry sections.

## Persistence

`ObservationCandidate` adds `continuousObservation: Boolean = false`. Existing saved candidates
default to false, so stored data remains compatible. JSON round-tripping includes the new field.
