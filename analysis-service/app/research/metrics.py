"""Performance and statistical metrics from daily returns.

Conventions: 252 trading periods per year, risk-free rate 0. Sharpe ratios are
per-period unless named ``annualized``. Skew is the biased moment ratio, and the
kurtosis used by PSR/DSR is raw (normal = 3), not excess. A metric that is
undefined for its input (too few observations, zero volatility, a non-positive
PSR denominator) is returned as None rather than raising.
"""

from __future__ import annotations

import math
import statistics
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date
from statistics import NormalDist
from typing import Any

PERIODS_PER_YEAR = 252
RISK_FREE_RATE = 0.0
EULER_MASCHERONI = 0.5772156649015329
_STANDARD_NORMAL = NormalDist()


def _finite_or_none(value: float | None) -> float | None:
    if value is None or not math.isfinite(value):
        return None
    return value


def sharpe_per_period(returns: Sequence[float]) -> float | None:
    if len(returns) < 2:
        return None
    sd = statistics.stdev(returns)
    if sd == 0.0:
        return None
    return statistics.fmean(returns) / sd


def annualize_sharpe(per_period: float | None) -> float | None:
    if per_period is None:
        return None
    return per_period * math.sqrt(PERIODS_PER_YEAR)


def skew_and_raw_kurtosis(returns: Sequence[float]) -> tuple[float, float] | None:
    """Biased skewness and raw (non-excess) kurtosis."""
    n = len(returns)
    if n < 4:
        return None
    mean = statistics.fmean(returns)
    m2 = sum((x - mean) ** 2 for x in returns) / n
    if m2 == 0.0:
        return None
    m3 = sum((x - mean) ** 3 for x in returns) / n
    m4 = sum((x - mean) ** 4 for x in returns) / n
    return m3 / m2**1.5, m4 / m2**2


def probabilistic_sharpe(returns: Sequence[float], sharpe_benchmark: float = 0.0) -> float | None:
    """PSR(SR*) of Bailey and Lopez de Prado (2012), per-period Sharpe units."""
    n = len(returns)
    sharpe = sharpe_per_period(returns)
    moments = skew_and_raw_kurtosis(returns)
    if sharpe is None or moments is None or n < 3:
        return None
    skew, kurt = moments
    radicand = 1.0 - skew * sharpe + (kurt - 1.0) / 4.0 * sharpe**2
    if radicand <= 0.0:
        return None
    z = (sharpe - sharpe_benchmark) * math.sqrt(n - 1) / math.sqrt(radicand)
    return _STANDARD_NORMAL.cdf(z)


def expected_max_sharpe(trial_variance: float, n_trials: int) -> float:
    """SR0: expected maximum per-period Sharpe across n_trials independent trials."""
    if n_trials <= 1 or trial_variance <= 0.0:
        return 0.0
    first = _STANDARD_NORMAL.inv_cdf(1.0 - 1.0 / n_trials)
    second = _STANDARD_NORMAL.inv_cdf(1.0 - 1.0 / (n_trials * math.e))
    return math.sqrt(trial_variance) * ((1.0 - EULER_MASCHERONI) * first + EULER_MASCHERONI * second)


def deflated_sharpe(returns: Sequence[float], trial_variance: float | None, n_trials: int) -> float | None:
    """DSR: PSR evaluated against SR0 given N trials and the cross-trial variance of SR."""
    if trial_variance is None or trial_variance < 0.0:
        return None
    return probabilistic_sharpe(returns, expected_max_sharpe(trial_variance, n_trials))


def equity_curve(returns: Sequence[float]) -> list[float]:
    curve = [1.0]
    for value in returns:
        curve.append(curve[-1] * (1.0 + value))
    return curve


@dataclass(frozen=True)
class Drawdown:
    max_drawdown: float
    max_drawdown_duration_days: int
    longest_recovery_days: int | None


def drawdown_profile(equity: Sequence[float]) -> Drawdown:
    """Max drawdown (negative fraction) and underwater durations in periods.

    An underwater episode runs from a peak to the first close at or above that
    peak, or to the end of the series when unrecovered. ``max_drawdown_duration_days``
    is the longest episode. ``longest_recovery_days`` is the longest episode that
    has recovered, or None when no episode has recovered.
    """
    if len(equity) < 2:
        return Drawdown(0.0, 0, None)
    peak = equity[0]
    peak_index = 0
    worst = 0.0
    episodes: list[tuple[int, bool]] = []
    underwater = False
    for index, value in enumerate(equity):
        if value >= peak:
            if underwater:
                episodes.append((index - peak_index, True))
                underwater = False
            peak = value
            peak_index = index
        else:
            underwater = True
            worst = min(worst, value / peak - 1.0)
    if underwater:
        episodes.append((len(equity) - 1 - peak_index, False))
    longest = max((duration for duration, _ in episodes), default=0)
    recovered = [duration for duration, done in episodes if done]
    return Drawdown(
        max_drawdown=worst,
        max_drawdown_duration_days=longest,
        longest_recovery_days=max(recovered) if recovered else None,
    )


def cagr(returns: Sequence[float]) -> float | None:
    if not returns:
        return None
    final = equity_curve(returns)[-1]
    if final <= 0.0:
        return None
    years = len(returns) / PERIODS_PER_YEAR
    return final ** (1.0 / years) - 1.0


def annualized_volatility(returns: Sequence[float]) -> float | None:
    if len(returns) < 2:
        return None
    return statistics.stdev(returns) * math.sqrt(PERIODS_PER_YEAR)


def sortino_ratio(returns: Sequence[float]) -> float | None:
    """Mean over downside deviation (all observations, target 0), annualized."""
    if len(returns) < 2:
        return None
    downside = math.sqrt(sum(min(value, 0.0) ** 2 for value in returns) / len(returns))
    if downside == 0.0:
        return None
    return statistics.fmean(returns) / downside * math.sqrt(PERIODS_PER_YEAR)


def monthly_returns(dates: Sequence[date], returns: Sequence[float]) -> dict[str, float]:
    """Compounded return per calendar month, keyed YYYY-MM."""
    buckets: dict[str, float] = {}
    for day, value in zip(dates, returns):
        key = f"{day.year:04d}-{day.month:02d}"
        buckets[key] = (1.0 + buckets.get(key, 0.0)) * (1.0 + value) - 1.0
    return buckets


def beta_alpha(returns: Sequence[float], benchmark: Sequence[float]) -> tuple[float | None, float | None]:
    """OLS beta and annualized alpha against the benchmark on the same dates."""
    if len(returns) != len(benchmark) or len(returns) < 2:
        return None, None
    bench_var = statistics.variance(benchmark)
    if bench_var == 0.0:
        return None, None
    mean_r = statistics.fmean(returns)
    mean_b = statistics.fmean(benchmark)
    covariance = sum((r - mean_r) * (b - mean_b) for r, b in zip(returns, benchmark)) / (len(returns) - 1)
    beta = covariance / bench_var
    alpha = (mean_r - RISK_FREE_RATE - beta * (mean_b - RISK_FREE_RATE)) * PERIODS_PER_YEAR
    return beta, alpha


def information_ratio(returns: Sequence[float], benchmark: Sequence[float]) -> float | None:
    if len(returns) != len(benchmark):
        return None
    active = [r - b for r, b in zip(returns, benchmark)]
    return annualize_sharpe(sharpe_per_period(active))


def compute_metrics(
    dates: Sequence[date],
    returns: Sequence[float],
    benchmark: Sequence[float] | None = None,
    *,
    hit_rate_exposed: Sequence[bool] | None = None,
) -> dict[str, Any]:
    """Descriptive metrics for one return series. Undefined values are None."""
    count = len(returns)
    equity = equity_curve(returns)
    drawdown = drawdown_profile(equity) if count else Drawdown(0.0, 0, None)
    sharpe = sharpe_per_period(returns)
    growth = cagr(returns)
    max_dd = drawdown.max_drawdown if count else None
    calmar = growth / abs(max_dd) if growth is not None and max_dd and max_dd < 0.0 else None
    moments = skew_and_raw_kurtosis(returns)
    monthly = monthly_returns(dates, returns)
    positive_months = sum(1 for value in monthly.values() if value > 0.0)
    beta, alpha = beta_alpha(returns, benchmark) if benchmark is not None else (None, None)
    exposed_hits = None
    if hit_rate_exposed is not None:
        flags = [hit for hit, active in zip(returns, hit_rate_exposed) if active]
        exposed_hits = sum(1 for value in flags if value > 0.0) / len(flags) if flags else None
    sortino = sortino_ratio(returns)
    return {
        "observations": count,
        "total_return": equity[-1] - 1.0 if count else None,
        "cagr": _finite_or_none(growth),
        "annualized_volatility": _finite_or_none(annualized_volatility(returns)),
        "sharpe_per_period": _finite_or_none(sharpe),
        "sharpe_annualized": _finite_or_none(annualize_sharpe(sharpe)),
        "sortino_annualized": _finite_or_none(sortino),
        "calmar": _finite_or_none(calmar),
        "max_drawdown": _finite_or_none(max_dd),
        "max_drawdown_duration_days": drawdown.max_drawdown_duration_days if count else None,
        "longest_recovery_days": drawdown.longest_recovery_days if count else None,
        "monthly_returns": monthly,
        "positive_month_fraction": positive_months / len(monthly) if monthly else None,
        "skew": None if moments is None else moments[0],
        "excess_kurtosis": None if moments is None else moments[1] - 3.0,
        "beta": _finite_or_none(beta),
        "alpha_annualized": _finite_or_none(alpha),
        "information_ratio": _finite_or_none(
            None if benchmark is None else information_ratio(returns, benchmark)
        ),
        "hit_rate_in_market": _finite_or_none(exposed_hits),
        "probabilistic_sharpe_vs_zero": _finite_or_none(probabilistic_sharpe(returns)),
    }
