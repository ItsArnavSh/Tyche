from __future__ import annotations

import unittest

import numpy as np
import pandas as pd

from backtest.strategies import (
    BollingerReversion,
    DonchianBreakout,
    MacdTrend,
    RsiReversion,
    SmaCross,
    build_strategy,
)
from backtest.tests import make_frame, trending_frame


class StrategyTests(unittest.TestCase):
    def assert_valid_signals(self, strategy, frame, minimum_bars=20):
        signals = strategy.signals(frame)
        self.assertEqual(len(signals.entry), len(frame))
        self.assertEqual(len(signals.exit), len(frame))
        self.assertTrue(signals.entry.dtype == bool)
        self.assertTrue(signals.exit.dtype == bool)
        self.assertFalse(signals.score.isna().any())
        self.assertTrue(((signals.score >= 0) & (signals.score <= 1)).all())
        self.assertFalse(signals.entry.iloc[:minimum_bars].any())

    def test_sma_cross_detects_trend_changes(self):
        closes = [100.0] * 40 + list(np.linspace(101, 120, 15)) + list(np.linspace(119, 90, 25))
        frame = make_frame(closes)
        strategy = SmaCross(fast=3, slow=10)
        signals = strategy.signals(frame)
        self.assertGreater(int(signals.entry.sum()), 0)
        self.assertGreater(int(signals.exit.sum()), 0)
        self.assertTrue(signals.entry.iloc[40:55].any())
        self.assertTrue(signals.exit.iloc[55:].any())
        self.assertFalse(signals.entry.iloc[:9].any())

    def test_donchian_breakout_excludes_current_high(self):
        closes = [100.0] * 40 + [120.0] + [120.0] * 9
        frame = make_frame(closes, highs=[price + 1 for price in closes], lows=[price - 1 for price in closes])
        signals = DonchianBreakout(period=5, exit_period=3).signals(frame)
        self.assertTrue(signals.entry.iloc[40])
        self.assertFalse(signals.entry.iloc[:40].any())

    def test_rsi_reversion_entry_after_oversold(self):
        closes = list(np.linspace(200, 100, 40)) + list(np.linspace(101, 160, 30))
        frame = make_frame(closes)
        signals = RsiReversion(period=14, oversold=35, overbought=70).signals(frame)
        self.assertGreater(int(signals.entry.sum()), 0)
        self.assertTrue(signals.entry.iloc[35:65].any())

    def test_all_strategies_return_aligned_signals(self):
        frame = trending_frame(160)
        cases = [
            (SmaCross(fast=5, slow=20), 20),
            (RsiReversion(period=14), 14),
            (MacdTrend(fast=6, slow=15, signal_period=5), 15),
            (BollingerReversion(period=20), 20),
            (DonchianBreakout(period=20, exit_period=10), 20),
        ]
        for strategy, warmup in cases:
            with self.subTest(strategy=strategy.describe()):
                self.assert_valid_signals(strategy, frame, minimum_bars=warmup - 1)

    def test_indicator_does_not_mutate_input(self):
        frame = trending_frame(80)
        original = frame.copy(deep=True)
        SmaCross(fast=5, slow=20).signals(frame)
        pd.testing.assert_frame_equal(frame, original)

    def test_build_strategy_and_validation(self):
        strategy = build_strategy("sma_cross", {"fast": 4, "slow": 12})
        self.assertIsInstance(strategy, SmaCross)
        self.assertIn("fast=4", strategy.describe())
        with self.assertRaises(ValueError):
            build_strategy("does-not-exist")
        with self.assertRaises(ValueError):
            build_strategy("sma", {"fast": 10, "slow": 5})
        with self.assertRaises(ValueError):
            build_strategy("sma", {"nonsense": 1})
        with self.assertRaises(ValueError):
            RsiReversion(oversold=80, overbought=20)
        with self.assertRaises(ValueError):
            MacdTrend(fast=30, slow=10)

    def test_missing_columns(self):
        with self.assertRaises(ValueError):
            SmaCross().signals(pd.DataFrame({"close": [1, 2, 3]}))


if __name__ == "__main__":
    unittest.main()
