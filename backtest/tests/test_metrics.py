from __future__ import annotations

import math
import unittest
from types import SimpleNamespace

import pandas as pd

from backtest.metrics import compute_metrics


def trade(pnl, return_pct=0.0, bars=1):
    return SimpleNamespace(pnl=pnl, return_pct=return_pct, bars_held=bars)


class MetricsTests(unittest.TestCase):
    def test_return_drawdown_and_risk(self):
        equity = pd.DataFrame(
            {
                "equity": [110.0, 99.0, 120.0],
                "cash": [50.0, 40.0, 120.0],
                "positions_value": [60.0, 59.0, 0.0],
                "exposure": [0.5, 0.5, 0.2],
            },
            index=pd.date_range("2024-01-01", periods=3, freq="D"),
        )
        metrics = compute_metrics(equity, [], initial_cash=100.0)
        self.assertAlmostEqual(metrics["total_return"], 0.2)
        self.assertAlmostEqual(metrics["max_drawdown"], 0.1)
        self.assertAlmostEqual(metrics["exposure"], 0.4)
        self.assertEqual(metrics["total_trades"], 0)
        self.assertGreater(metrics["annualized_volatility"], 0)

    def test_trade_statistics(self):
        equity = pd.DataFrame(
            {"equity": [100.0, 110.0, 120.0]},
            index=pd.date_range("2024-01-01", periods=3, freq="D"),
        )
        trades = [trade(100, 0.10, 5), trade(-50, -0.05, 3), trade(0, 0.0, 1)]
        metrics = compute_metrics(equity, trades, initial_cash=100.0)
        self.assertEqual(metrics["total_trades"], 3)
        self.assertEqual(metrics["wins"], 1)
        self.assertEqual(metrics["losses"], 1)
        self.assertEqual(metrics["breakeven"], 1)
        self.assertAlmostEqual(metrics["win_rate"], 1 / 3)
        self.assertAlmostEqual(metrics["profit_factor"], 2.0)
        self.assertAlmostEqual(metrics["gross_profit"], 100.0)
        self.assertAlmostEqual(metrics["gross_loss"], 50.0)
        self.assertAlmostEqual(metrics["expectancy"], 50.0 / 3)
        self.assertAlmostEqual(metrics["avg_bars_held"], 3.0)
        self.assertEqual(metrics["best_trade"], 100.0)
        self.assertEqual(metrics["worst_trade"], -50.0)

    def test_only_wins_profit_factor_is_infinite(self):
        equity = pd.DataFrame(
            {"equity": [100.0, 150.0]},
            index=pd.date_range("2024-01-01", periods=2, freq="D"),
        )
        metrics = compute_metrics(equity, [trade(50)], initial_cash=100.0)
        self.assertTrue(math.isinf(metrics["profit_factor"]))
        self.assertAlmostEqual(metrics["win_rate"], 1.0)

    def test_empty_equity(self):
        metrics = compute_metrics(pd.Series(dtype=float), [], initial_cash=1000.0)
        self.assertEqual(metrics["final_equity"], 1000.0)
        self.assertEqual(metrics["total_return"], 0.0)
        self.assertEqual(metrics["total_trades"], 0)

    def test_drawdown_duration(self):
        equity = pd.Series(
            [100.0, 90.0, 80.0, 95.0, 100.0, 110.0],
            index=pd.date_range("2024-01-01", periods=6, freq="D"),
        )
        metrics = compute_metrics(equity, [], initial_cash=100.0)
        self.assertEqual(metrics["max_drawdown_duration"], 3)

    def test_invalid_inputs(self):
        with self.assertRaises(ValueError):
            compute_metrics(pd.Series([100.0]), [], initial_cash=0)
        with self.assertRaises(ValueError):
            compute_metrics(pd.DataFrame({"cash": [100.0]}), [], initial_cash=100)


if __name__ == "__main__":
    unittest.main()
