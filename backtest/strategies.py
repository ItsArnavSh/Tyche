"""Signal generators.

A strategy receives a DataFrame with lowercase OHLCV columns and returns a
:class:`Signals` object.  ``entry`` and ``exit`` are decisions made at the
close of each bar; the engine executes them at the *next* bar's open.  This
separation is what prevents look-ahead bias.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, fields
from typing import Any

import numpy as np
import pandas as pd

from . import indicators as ta


@dataclass
class Signals:
    entry: pd.Series
    exit: pd.Series
    score: pd.Series

    def __post_init__(self) -> None:
        self.entry = self.entry.fillna(False).astype(bool)
        self.exit = self.exit.fillna(False).astype(bool)
        self.score = self.score.fillna(0.0).astype(float).clip(lower=0.0, upper=1.0)


class Strategy(ABC):
    """Base class for long-only, close-signal strategies."""

    name: str = "strategy"

    def __init__(self, **params: Any) -> None:
        self.params = dict(params)

    @abstractmethod
    def signals(self, frame: pd.DataFrame) -> Signals:
        raise NotImplementedError

    def describe(self) -> str:
        if not self.params:
            return self.name
        rendered = ", ".join(f"{key}={value}" for key, value in sorted(self.params.items()))
        return f"{self.name}({rendered})"

    @staticmethod
    def _require(frame: pd.DataFrame, columns: tuple[str, ...] = ("open", "high", "low", "close")) -> None:
        missing = [column for column in columns if column not in frame.columns]
        if missing:
            raise ValueError(f"price frame is missing columns: {', '.join(missing)}")
        if frame.empty:
            raise ValueError("price frame is empty")


@dataclass
class SmaCross(Strategy):
    """Classic fast/slow simple moving-average crossover."""

    fast: int = 20
    slow: int = 50
    name: str = "sma"

    def __init__(self, fast: int = 20, slow: int = 50) -> None:
        if fast <= 0 or slow <= 0:
            raise ValueError("SMA periods must be positive")
        if fast >= slow:
            raise ValueError("fast SMA period must be smaller than slow period")
        super().__init__(fast=fast, slow=slow)
        self.fast = fast
        self.slow = slow

    def signals(self, frame: pd.DataFrame) -> Signals:
        self._require(frame)
        close = frame["close"].astype(float)
        fast = ta.sma(close, self.fast)
        slow = ta.sma(close, self.slow)
        spread = ((fast - slow) / slow.replace(0.0, np.nan)).clip(lower=0.0, upper=1.0)
        return Signals(
            entry=ta.cross_above(fast, slow),
            exit=ta.cross_below(fast, slow),
            score=spread * 10.0,
        )


@dataclass
class RsiReversion(Strategy):
    """Buy when RSI recovers from oversold, exit when it becomes overbought."""

    period: int = 14
    oversold: float = 30.0
    overbought: float = 70.0
    name: str = "rsi"

    def __init__(self, period: int = 14, oversold: float = 30.0, overbought: float = 70.0) -> None:
        if period <= 1:
            raise ValueError("RSI period must be > 1")
        if not 0 < oversold < overbought < 100:
            raise ValueError("RSI thresholds must satisfy 0 < oversold < overbought < 100")
        super().__init__(period=period, oversold=oversold, overbought=overbought)
        self.period = period
        self.oversold = oversold
        self.overbought = overbought

    def signals(self, frame: pd.DataFrame) -> Signals:
        self._require(frame, ("close",))
        values = ta.rsi(frame["close"].astype(float), self.period)
        entry = ta.cross_above(values, pd.Series(self.oversold, index=values.index))
        exit_ = ta.cross_above(values, pd.Series(self.overbought, index=values.index))
        score = (1.0 - values / 100.0).clip(lower=0.0, upper=1.0)
        return Signals(entry=entry, exit=exit_, score=score)


@dataclass
class MacdTrend(Strategy):
    """Buy when MACD crosses above its signal line, exit on the reverse cross."""

    fast: int = 12
    slow: int = 26
    signal_period: int = 9
    name: str = "macd"

    def __init__(self, fast: int = 12, slow: int = 26, signal_period: int = 9) -> None:
        if fast <= 0 or slow <= 0 or signal_period <= 0:
            raise ValueError("MACD periods must be positive")
        if fast >= slow:
            raise ValueError("MACD fast period must be smaller than slow period")
        super().__init__(fast=fast, slow=slow, signal_period=signal_period)
        self.fast = fast
        self.slow = slow
        self.signal_period = signal_period

    def signals(self, frame: pd.DataFrame) -> Signals:
        self._require(frame, ("close",))
        close = frame["close"].astype(float)
        line, signal_line, histogram = ta.macd(close, self.fast, self.slow, self.signal_period)
        scale = histogram.abs().rolling(50, min_periods=5).mean().replace(0.0, np.nan)
        score = (histogram.abs() / scale).clip(lower=0.0, upper=1.0)
        return Signals(
            entry=ta.cross_above(line, signal_line),
            exit=ta.cross_below(line, signal_line),
            score=score,
        )


@dataclass
class BollingerReversion(Strategy):
    """Buy a close below the lower band; exit at the middle band."""

    period: int = 20
    num_std: float = 2.0
    name: str = "bollinger"

    def __init__(self, period: int = 20, num_std: float = 2.0) -> None:
        if period <= 1:
            raise ValueError("Bollinger period must be > 1")
        if num_std <= 0:
            raise ValueError("Bollinger standard deviations must be positive")
        super().__init__(period=period, num_std=num_std)
        self.period = period
        self.num_std = num_std

    def signals(self, frame: pd.DataFrame) -> Signals:
        self._require(frame, ("close",))
        close = frame["close"].astype(float)
        middle, upper, lower = ta.bollinger(close, self.period, self.num_std)
        entry = (close < lower) & (close.shift(1) >= lower.shift(1))
        exit_ = ta.cross_above(close, middle)
        width = (upper - middle).replace(0.0, np.nan)
        score = ((middle - close) / width).clip(lower=0.0, upper=1.0)
        return Signals(entry=entry, exit=exit_, score=score)


@dataclass
class DonchianBreakout(Strategy):
    """Turtle-style breakout above the prior N-bar high, exiting on an M-bar low."""

    period: int = 20
    exit_period: int = 10
    name: str = "donchian"

    def __init__(self, period: int = 20, exit_period: int = 10) -> None:
        if period <= 1 or exit_period <= 1:
            raise ValueError("Donchian periods must be > 1")
        super().__init__(period=period, exit_period=exit_period)
        self.period = period
        self.exit_period = exit_period

    def signals(self, frame: pd.DataFrame) -> Signals:
        self._require(frame, ("high", "low", "close"))
        close = frame["close"].astype(float)
        upper, _ = ta.donchian(frame["high"].astype(float), frame["low"].astype(float), self.period)
        _, lower_exit = ta.donchian(
            frame["high"].astype(float), frame["low"].astype(float), self.exit_period
        )
        entry = close > upper
        exit_ = close < lower_exit
        score = ((close / upper.replace(0.0, np.nan)) - 1.0).clip(lower=0.0) * 20.0
        return Signals(entry=entry, exit=exit_, score=score)


STRATEGIES: dict[str, type[Strategy]] = {
    "sma": SmaCross,
    "sma_cross": SmaCross,
    "rsi": RsiReversion,
    "rsi_reversion": RsiReversion,
    "macd": MacdTrend,
    "macd_trend": MacdTrend,
    "bollinger": BollingerReversion,
    "bollinger_reversion": BollingerReversion,
    "donchian": DonchianBreakout,
    "donchian_breakout": DonchianBreakout,
}


def build_strategy(name: str, params: dict[str, Any] | None = None) -> Strategy:
    key = (name or "").strip().lower()
    if key not in STRATEGIES:
        available = ", ".join(sorted(set(STRATEGIES)))
        raise ValueError(f"unknown strategy {name!r}; available: {available}")
    strategy_cls = STRATEGIES[key]
    supplied = dict(params or {})
    allowed = {field.name for field in fields(strategy_cls) if field.name != "name"}
    unknown = set(supplied) - allowed
    if unknown:
        raise ValueError(
            f"unknown parameter(s) for {name}: {', '.join(sorted(unknown))}; "
            f"expected: {', '.join(sorted(allowed))}"
        )
    return strategy_cls(**supplied)
