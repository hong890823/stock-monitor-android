#!/usr/bin/env python3
"""Backtest the current Android trend-entry rules on Sina daily bars.

The app's rule is evaluated at a historical day's close. Entry is the next
trading day's open, with the stop checked before the requested forward horizon.
This intentionally measures signal quality rather than inventing a take-profit
rule that does not exist in the app.
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
from dataclasses import dataclass
from typing import Iterable
from urllib.parse import urlencode
from urllib.request import Request, urlopen


BREAKOUT_VOLUME_RATIO = 1.3
PULLBACK_VOLUME_RATIO = 0.8
BREAKOUT_BUFFER_PCT = 0.5
MA20_BREAK_TOLERANCE_PCT = 0.5
PULLBACK_ABOVE_MA20_PCT = 2.0
RETEST_TOLERANCE_PCT = 0.5
INVALID_BREAK_PCT = 2.0
INVALID_CONFIRM_DAYS = 2
ATR_STOP_MULT = 1.5
MARKET_SLOPE_THRESHOLD_PCT = 0.1


@dataclass(frozen=True)
class Bar:
    date: str
    open: float
    high: float
    low: float
    close: float
    volume: float


@dataclass(frozen=True)
class Signal:
    code: str
    date: str
    kind: str
    close: float
    stop: float
    prior_high: float | None
    ma20: float
    volume_ratio: float
    market: str
    can_buy: bool


@dataclass(frozen=True)
class Trade:
    signal: Signal
    entry_date: str
    entry: float
    exit_date: str
    exit: float
    horizon: int
    stopped: bool

    @property
    def return_pct(self) -> float:
        return (self.exit / self.entry - 1.0) * 100.0


def fetch_bars(code: str, length: int) -> list[Bar]:
    query = urlencode({
        "symbol": code,
        "scale": "240",
        "ma": "no",
        "datalen": str(length),
    })
    url = "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/" \
        f"CN_MarketData.getKLineData?{query}"
    request = Request(url, headers={"User-Agent": "Mozilla/5.0", "Referer": "https://finance.sina.com.cn/"})
    with urlopen(request, timeout=20) as response:
        rows = json.loads(response.read().decode("utf-8"))
    bars = []
    for row in rows:
        try:
            bar = Bar(
                date=row["day"],
                open=float(row["open"]),
                high=float(row["high"]),
                low=float(row["low"]),
                close=float(row["close"]),
                volume=float(row["volume"]),
            )
        except (KeyError, TypeError, ValueError):
            continue
        if bar.close > 0 and bar.high > 0 and bar.low > 0:
            bars.append(bar)
    return bars


def percent_change(previous: float, current: float) -> float:
    return (current / previous - 1.0) * 100.0 if previous else 0.0


def mean(values: Iterable[float]) -> float:
    values = list(values)
    return statistics.fmean(values) if values else 0.0


def atr_series(bars: list[Bar]) -> list[float]:
    alpha = 1.0 / 14.0
    result: list[float] = []
    previous_atr = 0.0
    for index, bar in enumerate(bars):
        if index == 0:
            true_range = bar.high - bar.low
        else:
            previous_close = bars[index - 1].close
            true_range = max(
                bar.high - bar.low,
                abs(bar.high - previous_close),
                abs(bar.low - previous_close),
            )
        previous_atr = true_range if index == 0 else alpha * true_range + (1 - alpha) * previous_atr
        result.append(previous_atr)
    return result


def market_filter(bars: list[Bar]) -> str:
    if len(bars) < 30:
        return "中性"
    closes = [bar.close for bar in bars]
    ma20 = mean(closes[-20:])
    ma20_five_days_ago = mean(closes[-24:-4])
    slope = percent_change(ma20_five_days_ago, ma20)
    if closes[-1] > ma20 and slope >= MARKET_SLOPE_THRESHOLD_PCT:
        return "上行"
    if closes[-1] < ma20 and slope <= -MARKET_SLOPE_THRESHOLD_PCT:
        return "拖累"
    return "中性"


def breakout_candidate_at(bars: list[Bar], index: int) -> float | None:
    if index < 29 or index >= len(bars):
        return None
    closes = [bar.close for bar in bars]
    ma20 = mean(closes[index - 19:index + 1])
    ma20_five_days_ago = mean(closes[index - 24:index - 4])
    ma20_ten_days_ago = mean(closes[index - 29:index - 9])
    prior_high = max(bar.high for bar in bars[index - 20:index])
    volume_ratio = bars[index].volume / mean(bar.volume for bar in bars[index - 20:index])
    if (
        bars[index].close >= prior_high * (1.0 + BREAKOUT_BUFFER_PCT / 100.0)
        and volume_ratio >= BREAKOUT_VOLUME_RATIO
        and ma20 > ma20_five_days_ago
        and ma20_five_days_ago >= ma20_ten_days_ago
    ):
        return prior_high
    return None


def consecutive_closes_below_ma20(bars: list[Bar], index: int) -> int:
    count = 0
    for current in range(index, 18, -1):
        ma20 = mean(bar.close for bar in bars[current - 19:current + 1])
        if bars[current].close < ma20:
            count += 1
        else:
            break
    return count


def evaluate_signal(code: str, bars: list[Bar], benchmark: list[Bar], index: int, atr: float) -> Signal | None:
    if index < 29:
        return None
    history = bars[: index + 1]
    closes = [bar.close for bar in history]
    ma20 = mean(closes[-20:])
    ma20_five_days_ago = mean(closes[-24:-4])
    ma20_ten_days_ago = mean(closes[-29:-9])
    ma20_rising = ma20 > ma20_five_days_ago and ma20_five_days_ago >= ma20_ten_days_ago
    market_bars = [bar for bar in benchmark if bar.date <= history[-1].date]
    market = market_filter(market_bars)
    price = history[-1].close
    volume_ratio = history[-1].volume / mean(bar.volume for bar in history[-21:-1])
    current_candidate = breakout_candidate_at(bars, index)
    previous_prior_high = breakout_candidate_at(bars, index - 1)
    recent_candidate = next(
        (
            candidate
            for candidate_index in range(index - 1, max(28, index - 6), -1)
            if (candidate := breakout_candidate_at(bars, candidate_index)) is not None
        ),
        None,
    )
    trend_weak = (
        price < ma20 * (1.0 - INVALID_BREAK_PCT / 100.0)
        or consecutive_closes_below_ma20(bars, index) >= INVALID_CONFIRM_DAYS
    )
    failed_breakout = recent_candidate is not None and price < recent_candidate
    breakout_confirmed = (
        previous_prior_high is not None
        and price >= previous_prior_high
        and history[-1].low >= previous_prior_high * (1.0 - RETEST_TOLERANCE_PCT / 100.0)
    )
    breakout_watch = current_candidate is not None and not breakout_confirmed
    near_ma20 = (
        price >= ma20 * (1.0 - MA20_BREAK_TOLERANCE_PCT / 100.0)
        and price <= ma20 * (1.0 + PULLBACK_ABOVE_MA20_PCT / 100.0)
    )
    stabilized = price >= mean(closes[-5:]) or price >= history[-2].high or price > history[-1].open
    pullback_confirmed = (
        not trend_weak
        and not failed_breakout
        and ma20_rising
        and near_ma20
        and volume_ratio <= PULLBACK_VOLUME_RATIO
        and stabilized
    )
    if trend_weak or failed_breakout or market == "拖累" or not (breakout_confirmed or pullback_confirmed):
        return None
    if breakout_confirmed:
        kind = "突破"
        prior_high = previous_prior_high
        stop = price - ATR_STOP_MULT * atr
    else:
        kind = "回踩M20"
        prior_high = None
        stop = price - ATR_STOP_MULT * atr
    return Signal(
        code=code,
        date=history[-1].date,
        kind=kind,
        close=price,
        stop=stop,
        prior_high=prior_high,
        ma20=ma20,
        volume_ratio=volume_ratio,
        market=market,
        can_buy=kind == "回踩M20",
    )


def build_signals(code: str, bars: list[Bar], benchmark: list[Bar]) -> list[Signal]:
    atrs = atr_series(bars)
    signals: list[Signal] = []
    previous_kind: str | None = None
    for index in range(29, len(bars) - 1):
        signal = evaluate_signal(code, bars, benchmark, index, atrs[index])
        if signal is None:
            previous_kind = None
            continue
        if signal.kind != previous_kind:
            signals.append(signal)
        previous_kind = signal.kind
    return signals


def make_trade(signal: Signal, bars: list[Bar], horizon: int) -> Trade | None:
    if not signal.can_buy:
        return None
    signal_index = next((index for index, bar in enumerate(bars) if bar.date == signal.date), None)
    if signal_index is None or signal_index + horizon >= len(bars):
        return None
    entry_bar = bars[signal_index + 1]
    entry = entry_bar.open
    for offset in range(1, horizon + 1):
        bar = bars[signal_index + offset]
        if bar.open <= signal.stop:
            return Trade(signal, bar.date, entry, bar.date, bar.open, horizon, True)
        if bar.low <= signal.stop:
            return Trade(signal, entry_bar.date, entry, bar.date, signal.stop, horizon, True)
    exit_bar = bars[signal_index + horizon]
    return Trade(signal, entry_bar.date, entry, exit_bar.date, exit_bar.close, horizon, False)


def make_non_overlapping_trades(signals: list[Signal], data: dict[str, list[Bar]], horizon: int) -> list[Trade]:
    """Keep at most one simulated position per stock at a time."""
    selected: list[Trade] = []
    next_available: dict[str, str] = {}
    for signal in sorted(signals, key=lambda item: (item.date, item.code)):
        if signal.date < next_available.get(signal.code, ""):
            continue
        trade = make_trade(signal, data[signal.code], horizon)
        if trade is None:
            continue
        selected.append(trade)
        next_available[signal.code] = trade.exit_date
    return selected


def summarize(trades: list[Trade]) -> dict[str, float | int | None]:
    if not trades:
        return {"count": 0, "win_rate_pct": None, "mean_return_pct": None, "median_return_pct": None,
                "stop_rate_pct": None, "best_return_pct": None, "worst_return_pct": None}
    returns = [trade.return_pct for trade in trades]
    return {
        "count": len(trades),
        "win_rate_pct": round(sum(value > 0 for value in returns) / len(returns) * 100, 2),
        "mean_return_pct": round(mean(returns), 2),
        "median_return_pct": round(statistics.median(returns), 2),
        "stop_rate_pct": round(sum(trade.stopped for trade in trades) / len(trades) * 100, 2),
        "best_return_pct": round(max(returns), 2),
        "worst_return_pct": round(min(returns), 2),
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--length", type=int, default=520)
    parser.add_argument("--horizons", default="5,10,20")
    parser.add_argument("--sleep", type=float, default=0.15)
    parser.add_argument("codes", nargs="*", default=[
        "sh600000", "sh600036", "sh600519", "sh600089", "sh601318", "sh601398",
        "sh601857", "sh600900", "sh601012", "sh688981", "sz000001", "sz000333",
        "sz000651", "sz000858", "sz002594", "sz002415", "sz300059", "sz300122",
        "sz300750", "sz300760",
    ])
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    horizons = [int(item) for item in args.horizons.split(",") if item]
    benchmark = fetch_bars("sh000001", args.length)
    if len(benchmark) < 60:
        raise RuntimeError("沪指历史数据不足")
    all_signals: list[Signal] = []
    data: dict[str, list[Bar]] = {}
    errors: dict[str, str] = {}
    for code in args.codes:
        try:
            bars = fetch_bars(code, args.length)
            if len(bars) < 60:
                errors[code] = f"历史数据不足：{len(bars)}"
                continue
            data[code] = bars
            signals = build_signals(code, bars, benchmark)
            all_signals.extend(signals)
            print(f"{code}: {len(bars)} bars, {len(signals)} signals", file=sys.stderr)
        except Exception as error:  # noqa: BLE001
            errors[code] = str(error)
            print(f"{code}: ERROR {error}", file=sys.stderr)
        time.sleep(args.sleep)

    result: dict[str, object] = {
        "period": {
            "benchmark_start": benchmark[0].date,
            "benchmark_end": benchmark[-1].date,
            "requested_length": args.length,
        },
        "universe": list(data),
        "errors": errors,
        "signals": {
            "total": len(all_signals),
            "breakout": sum(signal.kind == "突破" for signal in all_signals),
            "pullback": sum(signal.kind == "回踩M20" for signal in all_signals),
            "buyable": sum(signal.can_buy for signal in all_signals),
        },
        "horizons": {},
    }
    for horizon in horizons:
        trades = [
            trade
            for signal in all_signals
            for trade in [make_trade(signal, data[signal.code], horizon)]
            if trade is not None
        ]
        by_kind = {
            kind: summarize([trade for trade in trades if trade.signal.kind == kind])
            for kind in ("突破", "回踩M20")
        }
        non_overlapping = make_non_overlapping_trades(all_signals, data, horizon)
        result["horizons"][str(horizon)] = {
            "all_signals": summarize(trades),
            "non_overlapping_per_stock": summarize(non_overlapping),
            "by_kind": by_kind,
        }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
