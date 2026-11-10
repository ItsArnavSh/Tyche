from __future__ import annotations

import unittest

import numpy as np
import pandas as pd

from backtest import indicators as ta


class IndicatorTests(unittest.TestCase):
    def test_sma(self):
        values = pd.Series([1.0, 2.0, 3.0, 4.0, 5.0])
        result = ta.sma(values, 3)
        self.assertTrue(np.isnan(result.iloc[0]))
        self.assertTrue(np.isnan(result.iloc[1]))
        self.assertEqual(result.iloc[2], 2.0)
        self.assertEqual(result.iloc[4], 4.0)

    def test_ema_seed_and_length(self):
        values = pd.Series(np.arange(1, 11, dtype=float))
        result = ta.ema(values, 3)
        self.assertEqual(len(result), len(values))
        self.assertTrue(np.isnan(result.iloc[1]))
        self.assertFalse(np.isnan(result.iloc[2]))
        self.assertGreater(result.iloc[-1], result.iloc[2])

    def test_rsi_extremes_and_flat(self):
        rising = pd.Series(np.arange(1, 50, dtype=float))
        falling = pd.Series(np.arange(50, 1, -1, dtype=float))
        flat = pd.Series(np.full(50, 100.0))
        self.assertAlmostEqual(float(ta.rsi(rising, 14).iloc[-1]), 100.0)
        self.assertAlmostEqual(float(ta.rsi(falling, 14).iloc[-1]), 0.0)
        self.assertAlmostEqual(float(ta.rsi(flat, 14).iloc[-1]), 50.0)
        mixed = ta.rsi(pd.Series(np.sin(np.linspace(0, 20, 100)) + 2), 14).dropna()
        self.assertTrue(((mixed >= 0) & (mixed <= 100)).all())

    def test_macd(self):
        values = pd.Series(np.linspace(100, 200, 100))
        line, signal, histogram = ta.macd(values, 5, 10, 4)
        self.assertTrue(np.allclose(line.dropna(), (ta.ema(values, 5) - ta.ema(values, 10)).dropna()))
        self.assertTrue(np.allclose(histogram.dropna(), (line - signal).dropna()))
        self.assertGreater(float(line.iloc[-1]), 0)

    def test_bollinger_ordering(self):
        values = pd.Series(np.random.default_rng(7).normal(100, 2, 100))
        middle, upper, lower = ta.bollinger(values, 20, 2)
        valid = middle.notna()
        self.assertTrue((upper[valid] >= middle[valid]).all())
        self.assertTrue((middle[valid] >= lower[valid]).all())

    def test_donchian_excludes_current_bar(self):
        high = pd.Series([1.0, 2.0, 3.0, 100.0, 4.0])
        low = pd.Series([0.5, 1.0, 2.0, 90.0, 3.0])
        upper, lower = ta.donchian(high, low, 3)
        self.assertEqual(upper.iloc[3], 3.0)  # max(1, 2, 3), not 100
        self.assertEqual(lower.iloc[3], 0.5)
        self.assertTrue(np.isnan(upper.iloc[2]))
        inclusive, _ = ta.donchian(high, low, 3, exclude_current=False)
        self.assertEqual(inclusive.iloc[3], 100.0)

    def test_atr_positive(self):
        frame = pd.DataFrame(
            {
                "high": [10, 11, 12, 13, 14],
                "low": [9, 9.5, 10, 10.5, 11],
                "close": [9.5, 10.5, 11, 12, 13],
            }
        )
        values = ta.atr(frame["high"], frame["low"], frame["close"], 3).dropna()
        self.assertTrue((values > 0).all())

    def test_crosses(self):
        left = pd.Series([1.0, 3.0, 2.0, 1.0])
        right = pd.Series([2.0, 2.0, 2.0, 2.0])
        self.assertEqual(ta.cross_above(left, right).tolist(), [False, True, False, False])
        self.assertEqual(ta.cross_below(left, right).tolist(), [False, False, False, True])

    def test_invalid_periods(self):
        values = pd.Series([1.0, 2.0, 3.0])
        with self.assertRaises(ValueError):
            ta.sma(values, 0)
        with self.assertRaises(ValueError):
            ta.macd(values, 10, 5)


if __name__ == "__main__":
    unittest.main()
