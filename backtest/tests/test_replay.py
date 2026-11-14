from __future__ import annotations

import unittest

import pandas as pd

from backtest.engine import Backtest, BacktestConfig
from backtest.tests import ScriptedStrategy, make_frame


def config(**overrides):
    defaults = dict(
        initial_cash=1000.0,
        max_positions=1,
        position_fraction=1.0,
        fee_bps=0.0,
        slippage_bps=0.0,
        stop_loss_pct=0.0,
        take_profit_pct=0.0,
        close_at_end=True,
    )
    defaults.update(overrides)
    return BacktestConfig(**defaults)


class ReplayWindowTests(unittest.TestCase):
    def test_replay_window_skips_warmup_bars(self):
        frame = make_frame([100, 100, 100, 100, 100, 100, 100, 100])
        strategy = ScriptedStrategy(entries=[0, 4], exits=[])
        result = Backtest(
            {"A": frame},
            strategy,
            config(),
            start=frame.index[4],
            end=frame.index[6],
            record_replay=True,
        ).run()
        self.assertEqual(list(result.equity.index), list(frame.index[4:6]))
        self.assertEqual(len(result.replay), 2)
        self.assertEqual(result.replay[0]["timestamp"], frame.index[4])
        self.assertEqual(result.replay[0]["positions"], [])
        types = [event["type"] for event in result.replay[1]["events"]]
        self.assertEqual(types, ["buy", "sell"])
        self.assertEqual(result.replay[1]["events"][0]["symbol"], "A")
        self.assertAlmostEqual(result.metrics["final_equity"], 1000.0)

    def test_replay_open_positions_are_snapshotted(self):
        frame = make_frame([100, 100, 100, 100, 100, 100, 100, 100])
        result = Backtest(
            {"A": frame},
            ScriptedStrategy(entries=[0, 4], exits=[]),
            config(close_at_end=False),
            start=frame.index[4],
            end=frame.index[7],
            record_replay=True,
        ).run()
        self.assertEqual(len(result.replay), 3)
        self.assertEqual(result.replay[0]["open_positions"], 0)
        self.assertEqual(result.replay[1]["open_positions"], 1)
        position = result.replay[1]["positions"][0]
        self.assertEqual(position["symbol"], "A")
        self.assertEqual(position["quantity"], 10)
        self.assertGreater(position["bars_held"], 0)

    def test_replay_without_window_is_empty(self):
        frame = make_frame([100, 100, 100, 100])
        result = Backtest({"A": frame}, ScriptedStrategy(entries=[0]), config()).run()
        self.assertEqual(result.replay, [])

    def test_window_with_too_few_bars_rejected(self):
        frame = make_frame([100, 100, 100, 100])
        with self.assertRaises(ValueError):
            Backtest(
                {"A": frame},
                ScriptedStrategy(entries=[0]),
                config(),
                start=frame.index[3],
                end=frame.index[3] + pd.Timedelta(days=1),
            ).run()

    def test_replay_bounds_accept_naive_timestamps(self):
        frame = make_frame([100, 100, 100, 100])
        result = Backtest(
            {"A": frame},
            ScriptedStrategy(entries=[0]),
            config(),
            start=pd.Timestamp("2024-01-03"),
            end=pd.Timestamp("2024-01-05"),
            record_replay=True,
        ).run()
        self.assertEqual(result.equity.index[0], frame.index[2])
        self.assertEqual(result.equity.index[-1], frame.index[3])


if __name__ == "__main__":
    unittest.main()
