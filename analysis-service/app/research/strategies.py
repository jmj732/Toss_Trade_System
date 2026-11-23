"""Causal strategies and the fixed parameter grids.

Each strategy sees the market only through ``PrefixMap``, which exposes bars up to
and including day t. Its output at day t is a function of that prefix, the params
and the strategy state threaded from day t-1 (a function of an earlier prefix).
The state threading replaces a full replay per day, which would be O(n^2).

Grid constants below are the trial universe. They are declared before any
real-data run. Changing them changes the trial count (N) used by the DSR.
"""

from __future__ import annotations

import itertools
from abc import ABC, abstractmethod
from collections.abc import Iterator, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import date
from typing import Any

from app.research.data import Bar

MOMENTUM_UNIVERSE: tuple[str, ...] = ("SPY", "QQQ", "IWM", "EFA", "EEM", "TLT", "IEF", "GLD")
BENCHMARK_SYMBOL = "SPY"
SMA_LONG_WINDOW = 200

MOMENTUM_GRID: Mapping[str, tuple[Any, ...]] = {
    "lookback": (126, 252),
    "skip": (0, 21),
    "top_k": (1, 2, 3),
    "abs_filter": (True, False),
}
TREND_SMA_GRID: tuple[dict[str, Any], ...] = tuple(
    {"mode": "sma", "n": n} for n in (100, 150, 200)
)
TREND_DONCHIAN_GRID: tuple[dict[str, Any], ...] = tuple(
    {"mode": "donchian", "n": n, "m": m} for n, m in ((55, 20), (100, 50), (200, 100))
)
MEAN_REVERSION_GRID: Mapping[str, tuple[Any, ...]] = {
    "rsi_n": (2, 3),
    "threshold": (5, 10, 20),
    "max_hold": (5, 10),
    "regime_filter": (True, False),
}

Weights = dict[str, float]


class HistoryView(Sequence[Bar]):
    """Read-only view of ``bars[: visible]``. Access past the visible end raises."""

    __slots__ = ("_bars", "_visible")

    def __init__(self, bars: Sequence[Bar], visible: int) -> None:
        self._bars = bars
        self._visible = visible

    def __len__(self) -> int:
        return self._visible

    def __getitem__(self, key: Any) -> Any:  # type: ignore[override]
        if isinstance(key, slice):
            return [self._bars[i] for i in range(*key.indices(self._visible))]
        index = key + self._visible if key < 0 else key
        if not 0 <= index < self._visible:
            raise IndexError(f"index {key} outside visible history of {self._visible} bars")
        return self._bars[index]


class PrefixMap(Mapping[str, HistoryView]):
    """Symbol -> HistoryView for days up to and including ``last_index``."""

    __slots__ = ("_bars", "_visible")

    def __init__(self, bars: Mapping[str, Sequence[Bar]], last_index: int) -> None:
        self._bars = bars
        self._visible = last_index + 1

    def __getitem__(self, symbol: str) -> HistoryView:
        return HistoryView(self._bars[symbol], self._visible)

    def __iter__(self) -> Iterator[str]:
        return iter(self._bars)

    def __len__(self) -> int:
        return len(self._bars)


def _closes(bars: Sequence[Bar]) -> list[float]:
    return [bar.close for bar in bars]


def _sma(bars: Sequence[Bar], window: int) -> float | None:
    if len(bars) < window:
        return None
    return sum(_closes(bars[len(bars) - window :])) / window


@dataclass
class WilderRsi:
    """Wilder RSI. Seeded with the simple average of the first ``period`` changes."""

    period: int
    previous_close: float | None = None
    seed_gains: list[float] = field(default_factory=list)
    seed_losses: list[float] = field(default_factory=list)
    avg_gain: float = 0.0
    avg_loss: float = 0.0
    seeded: bool = False

    def update(self, close: float) -> float | None:
        if self.previous_close is None:
            self.previous_close = close
            return None
        change = close - self.previous_close
        self.previous_close = close
        gain, loss = max(change, 0.0), max(-change, 0.0)
        if not self.seeded:
            self.seed_gains.append(gain)
            self.seed_losses.append(loss)
            if len(self.seed_gains) < self.period:
                return None
            self.avg_gain = sum(self.seed_gains) / self.period
            self.avg_loss = sum(self.seed_losses) / self.period
            self.seeded = True
        else:
            self.avg_gain = (self.avg_gain * (self.period - 1) + gain) / self.period
            self.avg_loss = (self.avg_loss * (self.period - 1) + loss) / self.period
        return self.value()

    def value(self) -> float:
        if self.avg_gain + self.avg_loss == 0.0:
            return 50.0
        if self.avg_loss == 0.0:
            return 100.0
        return 100.0 - 100.0 / (1.0 + self.avg_gain / self.avg_loss)


class Strategy(ABC):
    """Base class. Subclasses implement ``step``; the engine threads ``state``."""

    family: str = ""
    rebalance: str = "daily"
    universe: tuple[str, ...] = ()

    @abstractmethod
    def config(self) -> dict[str, Any]:
        """Parameters that identify this trial."""

    def config_id(self) -> str:
        params = self.config()
        parts = ",".join(f"{key}={params[key]}" for key in sorted(params))
        return f"{self.family}[{parts}]"

    @abstractmethod
    def warmup_bars(self) -> int:
        """Bars of history needed before the first meaningful decision."""

    def initial_state(self) -> Any:
        return None

    @abstractmethod
    def step(
        self, history: Mapping[str, HistoryView], state: Any, decision_day: bool
    ) -> tuple[Weights | None, Any]:
        """Return (target weights or None, next state) using only ``history``."""


class MomentumStrategy(Strategy):
    """Cross-asset relative momentum with a skip window and optional absolute filter."""

    family = "momentum"
    rebalance = "monthly"

    def __init__(
        self,
        lookback: int,
        skip: int,
        top_k: int,
        abs_filter: bool,
        universe: tuple[str, ...] = MOMENTUM_UNIVERSE,
    ) -> None:
        self.lookback = lookback
        self.skip = skip
        self.top_k = top_k
        self.abs_filter = abs_filter
        self.universe = universe

    def config(self) -> dict[str, Any]:
        return {"lookback": self.lookback, "skip": self.skip, "top_k": self.top_k, "abs_filter": self.abs_filter}

    def warmup_bars(self) -> int:
        return self.lookback + self.skip + 1

    def _lookback_return(self, bars: HistoryView) -> float | None:
        if len(bars) < self.warmup_bars():
            return None
        end = bars[len(bars) - 1 - self.skip].close
        start = bars[len(bars) - 1 - self.skip - self.lookback].close
        return end / start - 1.0

    def step(
        self, history: Mapping[str, HistoryView], state: Any, decision_day: bool
    ) -> tuple[Weights | None, Any]:
        if not decision_day:
            return None, state
        ranked: list[tuple[float, int, str]] = []
        for order, symbol in enumerate(self.universe):
            score = self._lookback_return(history[symbol])
            if score is not None:
                ranked.append((-score, order, symbol))
        ranked.sort()
        chosen = [(-neg_score, symbol) for neg_score, _, symbol in ranked[: self.top_k]]
        if self.abs_filter:
            chosen = [(score, symbol) for score, symbol in chosen if score > 0.0]
        weight = 1.0 / self.top_k
        return {symbol: weight for _, symbol in chosen}, state


class TrendStrategy(Strategy):
    """Single-asset trend: SMA regime (``sma``) or Donchian breakout (``donchian``)."""

    family = "trend"
    rebalance = "daily"
    universe = (BENCHMARK_SYMBOL,)

    def __init__(self, mode: str, n: int, m: int | None = None) -> None:
        if mode not in ("sma", "donchian"):
            raise ValueError(f"unknown trend mode {mode!r}")
        if mode == "donchian" and (m is None or m >= n):
            raise ValueError("donchian requires an exit window m smaller than the entry window n")
        self.mode = mode
        self.n = n
        self.m = m

    def config(self) -> dict[str, Any]:
        if self.mode == "sma":
            return {"mode": self.mode, "n": self.n}
        return {"mode": self.mode, "n": self.n, "m": self.m}

    def warmup_bars(self) -> int:
        return self.n + 1 if self.mode == "donchian" else self.n

    def initial_state(self) -> bool:
        return False

    def step(
        self, history: Mapping[str, HistoryView], state: Any, decision_day: bool
    ) -> tuple[Weights | None, Any]:
        bars = history[BENCHMARK_SYMBOL]
        close = bars[len(bars) - 1].close
        if self.mode == "sma":
            average = _sma(bars, self.n)
            long_now = average is not None and close > average
            return ({BENCHMARK_SYMBOL: 1.0} if long_now else {}), state
        in_position = bool(state)
        if not in_position and len(bars) > self.n:
            prior_high = max(_closes(bars[len(bars) - 1 - self.n : len(bars) - 1]))
            in_position = close > prior_high
        elif in_position and len(bars) > self.m:
            prior_low = min(_closes(bars[len(bars) - 1 - self.m : len(bars) - 1]))
            in_position = not close < prior_low
        return ({BENCHMARK_SYMBOL: 1.0} if in_position else {}), in_position


@dataclass
class MeanReversionState:
    rsi: WilderRsi
    holding: bool = False
    days_held: int = 0


class MeanReversionStrategy(Strategy):
    """Buy when RSI(n) is below a threshold; exit on close > SMA(5) or after max_hold days."""

    family = "mean_reversion"
    rebalance = "daily"
    universe = (BENCHMARK_SYMBOL,)

    def __init__(self, rsi_n: int, threshold: float, max_hold: int, regime_filter: bool) -> None:
        self.rsi_n = rsi_n
        self.threshold = threshold
        self.max_hold = max_hold
        self.regime_filter = regime_filter

    def config(self) -> dict[str, Any]:
        return {
            "rsi_n": self.rsi_n,
            "threshold": self.threshold,
            "max_hold": self.max_hold,
            "regime_filter": self.regime_filter,
        }

    def warmup_bars(self) -> int:
        return SMA_LONG_WINDOW if self.regime_filter else self.rsi_n + 1

    def initial_state(self) -> MeanReversionState:
        return MeanReversionState(rsi=WilderRsi(period=self.rsi_n))

    def step(
        self, history: Mapping[str, HistoryView], state: Any, decision_day: bool
    ) -> tuple[Weights | None, Any]:
        bars = history[BENCHMARK_SYMBOL]
        close = bars[len(bars) - 1].close
        rsi = state.rsi.update(close)
        sma_short = _sma(bars, 5)
        if state.holding:
            state.days_held += 1
            exit_now = state.days_held >= self.max_hold or (sma_short is not None and close > sma_short)
            state.holding = not exit_now
        elif rsi is not None and rsi < self.threshold:
            regime_ok = True
            if self.regime_filter:
                sma_long = _sma(bars, SMA_LONG_WINDOW)
                regime_ok = sma_long is not None and close > sma_long
            if regime_ok:
                state.holding = True
                state.days_held = 0
        return ({BENCHMARK_SYMBOL: 1.0} if state.holding else {}), state


def build_strategies(available_symbols: Sequence[str]) -> list[Strategy]:
    """All 54 grid configurations in fixed grid order, restricted to available symbols."""
    available = set(available_symbols)
    universe = tuple(symbol for symbol in MOMENTUM_UNIVERSE if symbol in available)
    strategies: list[Strategy] = []
    keys = list(MOMENTUM_GRID)
    for values in itertools.product(*(MOMENTUM_GRID[key] for key in keys)):
        params = dict(zip(keys, values))
        strategies.append(MomentumStrategy(universe=universe, **params))
    for params in TREND_SMA_GRID + TREND_DONCHIAN_GRID:
        strategies.append(TrendStrategy(**params))
    keys = list(MEAN_REVERSION_GRID)
    for values in itertools.product(*(MEAN_REVERSION_GRID[key] for key in keys)):
        strategies.append(MeanReversionStrategy(**dict(zip(keys, values))))
    return strategies


def decision_schedule(rebalance: str, dates: Sequence[date]) -> frozenset[int]:
    """Indices t whose close produces a decision executed at the open of t+1.

    The trading calendar is treated as known in advance. Only prices are causal.
    """
    last = len(dates) - 1
    if rebalance == "daily":
        return frozenset(range(last))
    if rebalance == "monthly":
        return frozenset(
            t
            for t in range(last)
            if (dates[t + 1].year, dates[t + 1].month) != (dates[t].year, dates[t].month)
        )
    raise ValueError(f"unknown rebalance policy {rebalance!r}")


def compute_decisions(
    strategy: Strategy, bars: Mapping[str, Sequence[Bar]], dates: Sequence[date]
) -> dict[int, Weights]:
    """Decisions keyed by decision index t (close of t, executed at open of t+1)."""
    schedule = decision_schedule(strategy.rebalance, dates)
    state = strategy.initial_state()
    decisions: dict[int, Weights] = {}
    for t in range(len(dates) - 1):
        weights, state = strategy.step(PrefixMap(bars, t), state, t in schedule)
        if weights is None:
            continue
        validate_weights(weights, strategy.universe)
        decisions[t] = weights
    return decisions


def validate_weights(weights: Mapping[str, float], universe: Sequence[str]) -> None:
    total = 0.0
    for symbol, weight in weights.items():
        if symbol not in universe:
            raise ValueError(f"weight for {symbol} outside universe")
        if not 0.0 <= weight <= 1.0:
            raise ValueError(f"weight {weight} for {symbol} outside [0, 1]")
        total += weight
    if total > 1.0 + 1e-12:
        raise ValueError(f"weights sum to {total} > 1")
