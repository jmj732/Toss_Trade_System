from __future__ import annotations

import math
import random
from datetime import date

import pytest
from research_fixtures import weekdays

from app.research.metrics import (
    PERIODS_PER_YEAR,
    annualize_sharpe,
    beta_alpha,
    cagr,
    compute_metrics,
    deflated_sharpe,
    drawdown_profile,
    expected_max_sharpe,
    monthly_returns,
    probabilistic_sharpe,
    sharpe_per_period,
    sortino_ratio,
)


def _noisy(seed: int, count: int, mean: float, sd: float = 0.01) -> list[float]:
    rng = random.Random(seed)
    return [mean + rng.gauss(0.0, sd) for _ in range(count)]


def test_cagr_of_constant_daily_return_over_one_year() -> None:
    # One year = 252 periods, so CAGR = 1.01 ** 252 - 1.
    assert cagr([0.01] * PERIODS_PER_YEAR) == pytest.approx(1.01**252 - 1.0, rel=1e-12)
    # Two years of the same daily return: CAGR is unchanged.
    assert cagr([0.01] * (2 * PERIODS_PER_YEAR)) == pytest.approx(1.01**252 - 1.0, rel=1e-12)


def test_max_drawdown_duration_and_recovery_on_tiny_series() -> None:
    # equity: 1.0, 1.1 (peak), 0.55 (trough), 1.1 (recovered at index 3)
    profile = drawdown_profile([1.0, 1.1, 0.55, 1.1])
    assert profile.max_drawdown == pytest.approx(0.55 / 1.1 - 1.0, abs=1e-15)
    assert profile.max_drawdown_duration_days == 2
    assert profile.longest_recovery_days == 2


def test_unrecovered_drawdown_has_no_recovery_length() -> None:
    profile = drawdown_profile([1.0, 0.9, 0.8])
    assert profile.max_drawdown == pytest.approx(-0.2)
    assert profile.max_drawdown_duration_days == 2
    assert profile.longest_recovery_days is None


def test_sharpe_matches_hand_value_on_two_observations() -> None:
    # mean 0.02, sample sd sqrt(0.0002) -> per-period Sharpe sqrt(2)
    assert sharpe_per_period([0.01, 0.03]) == pytest.approx(math.sqrt(2.0), abs=1e-12)
    assert annualize_sharpe(math.sqrt(2.0)) == pytest.approx(math.sqrt(2.0) * math.sqrt(252.0))


def test_sortino_uses_full_sample_downside_deviation() -> None:
    returns = [0.02, -0.01, 0.03, -0.02]
    downside = math.sqrt((0.01**2 + 0.02**2) / 4)
    expected = (sum(returns) / 4) / downside * math.sqrt(252.0)
    assert sortino_ratio(returns) == pytest.approx(expected, abs=1e-12)


def test_zero_volatility_and_tiny_samples_return_none_without_raising() -> None:
    flat = [0.001] * 10
    assert sharpe_per_period(flat) is None
    assert sortino_ratio(flat) is None
    tiny = compute_metrics(weekdays(date(2024, 1, 2), 1), [0.01])
    assert tiny["sharpe_annualized"] is None
    assert tiny["annualized_volatility"] is None
    assert tiny["probabilistic_sharpe_vs_zero"] is None
    empty = compute_metrics([], [])
    assert empty["total_return"] is None
    assert empty["max_drawdown"] is None


def test_probabilistic_sharpe_is_increasing_in_observed_sharpe() -> None:
    values = []
    for shift in (0.0, 0.002, 0.004, 0.008):
        values.append(probabilistic_sharpe(_noisy(3, 500, shift)))
    assert all(earlier < later for earlier, later in zip(values, values[1:]))


def test_deflated_sharpe_is_below_psr_zero_with_many_trials() -> None:
    returns = _noisy(5, 800, 0.002)
    psr_zero = probabilistic_sharpe(returns, 0.0)
    dsr_many = deflated_sharpe(returns, trial_variance=0.0004, n_trials=54)
    assert psr_zero is not None and dsr_many is not None
    assert dsr_many < psr_zero


def test_expected_max_sharpe_grows_with_trial_count_and_vanishes_for_one_trial() -> None:
    assert expected_max_sharpe(0.0004, 1) == 0.0
    assert expected_max_sharpe(0.0004, 2) < expected_max_sharpe(0.0004, 54) < expected_max_sharpe(0.0004, 1000)


def test_deflated_sharpe_needs_a_trial_variance() -> None:
    assert deflated_sharpe(_noisy(6, 300, 0.001), None, 54) is None
    assert deflated_sharpe(_noisy(6, 300, 0.001), -1.0, 54) is None


def test_psr_at_zero_benchmark_is_one_half_for_zero_mean_symmetric_returns() -> None:
    half = _noisy(4, 200, 0.0)
    symmetric = half + [-value for value in half]
    assert probabilistic_sharpe(symmetric, 0.0) == pytest.approx(0.5, abs=1e-12)


def test_monthly_returns_compound_within_each_month() -> None:
    dates = [date(2024, 1, 30), date(2024, 1, 31), date(2024, 2, 1)]
    monthly = monthly_returns(dates, [0.1, 0.1, -0.05])
    assert monthly["2024-01"] == pytest.approx(1.1 * 1.1 - 1.0)
    assert monthly["2024-02"] == pytest.approx(-0.05)


def test_beta_and_alpha_of_a_series_against_itself() -> None:
    series = _noisy(9, 200, 0.0005)
    beta, alpha = beta_alpha(series, series)
    assert beta == pytest.approx(1.0, abs=1e-12)
    assert alpha == pytest.approx(0.0, abs=1e-12)


def test_compute_metrics_reports_monthly_fraction_and_hit_rate() -> None:
    dates = weekdays(date(2024, 1, 2), 30)
    returns = [0.01 if index % 3 else -0.01 for index in range(30)]
    exposed = [index % 2 == 0 for index in range(30)]
    metrics = compute_metrics(dates, returns, hit_rate_exposed=exposed)
    assert metrics["observations"] == 30
    assert 0.0 <= metrics["positive_month_fraction"] <= 1.0
    expected_hits = [r for r, e in zip(returns, exposed) if e]
    assert metrics["hit_rate_in_market"] == pytest.approx(
        sum(1 for r in expected_hits if r > 0) / len(expected_hits)
    )
