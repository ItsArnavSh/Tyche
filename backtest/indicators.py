"""Technical indicators used by the backtester.

All functions operate on pandas Series and return Series aligned to the input
index.  Indicators never look at future values: rolling windows only use data
up to and including the current bar, and channel indicators are explicitly
shifted by one bar so a breakout cannot be tested against the bar that creates
it.
"""

from __future__ import annotations

import numpy as np
import pandas as pd


def sma(series: pd.Series, period: int) -> pd.Series:
    if period <= 0:
        raise ValueError("period must be positive")
    return series.rolling(window=period, min_periods=period).mean()


def ema(series: pd.Series, period: int) -> pd.Series:
    if period <= 0:
        raise ValueError("period must be positive")
    return series.ewm(span=period, adjust=False, min_periods=period).mean()


def rsi(series: pd.Series, period: int = 14) -> pd.Series:
    """Wilder's Relative Strength Index in [0, 100]."""
    if period <= 0:
        raise ValueError("period must be positive")
    delta = series.diff()
    gain = delta.clip(lower=0.0)
    loss = (-delta).clip(lower=0.0)

    avg_gain = gain.ewm(alpha=1.0 / period, adjust=False, min_periods=period).mean()
    avg_loss = loss.ewm(alpha=1.0 / period, adjust=False, min_periods=period).mean()

    rs = avg_gain / avg_loss.replace(0.0, np.nan)
    result = 100.0 - (100.0 / (1.0 + rs))
    # Only gains -> 100; only losses -> 0; perfectly flat -> neutral 50.
    result = result.where(avg_loss != 0.0, 100.0)
    result = result.where(~((avg_gain == 0.0) & (avg_loss != 0.0)), 0.0)
    result = result.where(~((avg_gain == 0.0) & (avg_loss == 0.0)), 50.0)
    return result


def macd(
    series: pd.Series,
    fast: int = 12,
    slow: int = 26,
    signal: int = 9,
) -> tuple[pd.Series, pd.Series, pd.Series]:
    if fast >= slow:
        raise ValueError("fast period must be smaller than slow period")
    line = ema(series, fast) - ema(series, slow)
    signal_line = line.ewm(span=signal, adjust=False, min_periods=signal).mean()
    histogram = line - signal_line
    return line, signal_line, histogram


def bollinger(
    series: pd.Series,
    period: int = 20,
    num_std: float = 2.0,
) -> tuple[pd.Series, pd.Series, pd.Series]:
    middle = sma(series, period)
    deviation = series.rolling(window=period, min_periods=period).std(ddof=0)
    upper = middle + num_std * deviation
    lower = middle - num_std * deviation
    return middle, upper, lower


def true_range(high: pd.Series, low: pd.Series, close: pd.Series) -> pd.Series:
    previous_close = close.shift(1)
    ranges = pd.concat(
        [
            high - low,
            (high - previous_close).abs(),
            (low - previous_close).abs(),
        ],
        axis=1,
    )
    return ranges.max(axis=1)


def atr(high: pd.Series, low: pd.Series, close: pd.Series, period: int = 14) -> pd.Series:
    tr = true_range(high, low, close)
    return tr.ewm(alpha=1.0 / period, adjust=False, min_periods=period).mean()


def donchian(
    high: pd.Series,
    low: pd.Series,
    period: int = 20,
    exclude_current: bool = True,
) -> tuple[pd.Series, pd.Series]:
    """Return (upper, lower) channels.

    By default the channels use bars strictly before the current bar.  Testing
    ``close > upper`` against a channel that includes today's high would be a
    look-ahead bug (a breakout would always be possible), so the shift is
    deliberate and covered by tests.
    """
    upper = high.rolling(window=period, min_periods=period).max()
    lower = low.rolling(window=period, min_periods=period).min()
    if exclude_current:
        upper = upper.shift(1)
        lower = lower.shift(1)
    return upper, lower


def stochastic(
    high: pd.Series,
    low: pd.Series,
    close: pd.Series,
    period: int = 14,
    smooth: int = 3,
) -> tuple[pd.Series, pd.Series]:
    lowest = low.rolling(period, min_periods=period).min()
    highest = high.rolling(period, min_periods=period).max()
    span = (highest - lowest).replace(0.0, np.nan)
    k = 100.0 * (close - lowest) / span
    d = k.rolling(smooth, min_periods=smooth).mean()
    return k, d


def cci(high: pd.Series, low: pd.Series, close: pd.Series, period: int = 20) -> pd.Series:
    typical = (high + low + close) / 3.0
    mean = typical.rolling(period, min_periods=period).mean()
    mad = typical.rolling(period, min_periods=period).apply(
        lambda window: np.mean(np.abs(window - window.mean())), raw=True
    )
    return (typical - mean) / (0.015 * mad.replace(0.0, np.nan))


def vwap(high: pd.Series, low: pd.Series, close: pd.Series, volume: pd.Series) -> pd.Series:
    typical = (high + low + close) / 3.0
    cumulative_volume = volume.cumsum().replace(0, np.nan)
    return (typical * volume).cumsum() / cumulative_volume


def obv(close: pd.Series, volume: pd.Series) -> pd.Series:
    direction = np.sign(close.diff()).fillna(0.0)
    return (direction * volume).cumsum()


def cross_above(left: pd.Series, right: pd.Series) -> pd.Series:
    return (left > right) & (left.shift(1) <= right.shift(1))


def cross_below(left: pd.Series, right: pd.Series) -> pd.Series:
    return (left < right) & (left.shift(1) >= right.shift(1))
