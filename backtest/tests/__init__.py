"""Shared fixtures for backtest tests."""

from __future__ import annotations

import numpy as np
import pandas as pd

from backtest.strategies import Signals, Strategy


def make_frame(
    closes,
    *,
    opens=None,
    highs=None,
    lows=None,
    volumes=None,
    start="2024-01-01",
) -> pd.DataFrame:
    closes = np.asarray(closes, dtype=float)
    length = len(closes)
    index = pd.date_range(start, periods=length, freq="D", tz="Asia/Kolkata")
    opens = np.asarray(opens, dtype=float) if opens is not None else closes.copy()
    highs = np.asarray(highs, dtype=float) if highs is not None else np.maximum(opens, closes)
    lows = np.asarray(lows, dtype=float) if lows is not None else np.minimum(opens, closes)
    volumes = np.asarray(volumes, dtype=float) if volumes is not None else np.full(length, 1_000.0)
    return pd.DataFrame(
        {"open": opens, "high": highs, "low": lows, "close": closes, "volume": volumes},
        index=index,
    )


def trending_frame(days: int = 120, start_price: float = 100.0) -> pd.DataFrame:
    x = np.linspace(0, 6 * np.pi, days)
    closes = start_price + 10 * np.sin(x) + np.linspace(0, 25, days)
    opens = np.concatenate(([closes[0]], closes[:-1]))
    highs = np.maximum(opens, closes) * 1.005
    lows = np.minimum(opens, closes) * 0.995
    return make_frame(closes, opens=opens, highs=highs, lows=lows)


class ScriptedStrategy(Strategy):
    """Deterministic strategy used to test engine mechanics."""

    name = "scripted"

    def __init__(self, entries, exits=None, score=None):
        super().__init__()
        self.entries = set(entries)
        self.exits = set(exits or [])
        self.score_values = score

    def signals(self, frame: pd.DataFrame) -> Signals:
        length = len(frame)
        entry = pd.Series([index in self.entries for index in range(length)], index=frame.index)
        exit_ = pd.Series([index in self.exits for index in range(length)], index=frame.index)
        if self.score_values is None:
            score = pd.Series(1.0, index=frame.index)
        else:
            score = pd.Series(self.score_values, index=frame.index)
        return Signals(entry=entry, exit=exit_, score=score)
