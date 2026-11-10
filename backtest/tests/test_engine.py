from __future__ import annotations

import unittest

import pandas as pd

from backtest.engine import Backtest, BacktestConfig
from backtest.tests import ScriptedStrategy, make_frame


def flat_frame(length=4, price=100.0, **overrides):
    closes = [price] * length
    opens = [price] * length
    highs = [price + 1] * length
    lows = [price - 1] * length
    opens = overrides.get("opens", opens)
    highs = overrides.get("highs", highs)
    lows = overrides.get("lows", lows)
    closes = overrides.get("closes", closes)
    return make_frame(closes, opens=opens, highs=highs, lows=lows)


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


class EngineTests(unittest.TestCase):
    def assert_reconciles(self, result, initial_cash):
        realised = sum(trade.pnl for trade in result.trades)
        self.assertAlmostEqual(result.metrics["final_equity"], initial_cash + realised, places=6)
        self.assertTrue((result.equity["cash"] >= -1e-9).all())

    def test_entry_executes_at_next_open_not_current_close(self):
        frame = make_frame(
            [100, 200, 200, 200],
            opens=[50, 300, 300, 300],
            highs=[310, 310, 310, 310],
            lows=[40, 190, 190, 190],
        )
        strategy = ScriptedStrategy(entries=[0], exits=[1])
        result = Backtest({"A": frame}, strategy, config()).run()
        self.assertEqual(len(result.trades), 1)
        trade = result.trades[0]
        self.assertEqual(trade.quantity, 3)  # 1000 // 300
        self.assertEqual(trade.entry_price, 300.0)
        self.assertEqual(trade.exit_price, 300.0)
        self.assertEqual(trade.entry_date, frame.index[1])
        self.assertEqual(trade.exit_date, frame.index[2])
        self.assert_reconciles(result, 1000)

    def test_stop_loss_fills_intraday(self):
        frame = flat_frame(
            closes=[100, 100, 100, 100],
            opens=[100, 100, 100, 100],
            highs=[101, 101, 101, 101],
            lows=[80, 90, 100, 100],
        )
        result = Backtest({"A": frame}, ScriptedStrategy(entries=[0]), config(stop_loss_pct=0.05)).run()
        self.assertEqual(len(result.trades), 1)
        trade = result.trades[0]
        self.assertEqual(trade.reason, "stop_loss")
        self.assertAlmostEqual(trade.exit_price, 95.0)
        self.assertAlmostEqual(trade.pnl, -50.0)
        self.assertAlmostEqual(result.metrics["final_equity"], 950.0)
        self.assert_reconciles(result, 1000)

    def test_take_profit_fills_intraday(self):
        frame = flat_frame(
            highs=[101, 120, 101, 101],
            lows=[99, 99, 99, 99],
        )
        result = Backtest(
            {"A": frame}, ScriptedStrategy(entries=[0]), config(take_profit_pct=0.10)
        ).run()
        trade = result.trades[0]
        self.assertEqual(trade.reason, "take_profit")
        self.assertAlmostEqual(trade.exit_price, 110.0)
        self.assertAlmostEqual(trade.pnl, 100.0)
        self.assertAlmostEqual(result.metrics["final_equity"], 1100.0)
        self.assert_reconciles(result, 1000)

    def test_stop_wins_when_both_levels_touched(self):
        frame = flat_frame(
            highs=[101, 120, 101, 101],
            lows=[99, 80, 99, 99],
        )
        result = Backtest(
            {"A": frame},
            ScriptedStrategy(entries=[0]),
            config(stop_loss_pct=0.05, take_profit_pct=0.10),
        ).run()
        trade = result.trades[0]
        self.assertEqual(trade.reason, "stop_loss")
        self.assertAlmostEqual(trade.exit_price, 95.0)

    def test_max_positions_and_score_ranking(self):
        prices = {
            "A": flat_frame(),
            "B": flat_frame(),
            "C": flat_frame(),
        }
        strategy = ScriptedStrategy(entries=[0])
        # Per-symbol scores are unavailable; use equal scores and assert the
        # cap holds, then a second run uses a custom per-symbol strategy.
        result = Backtest(prices, strategy, config(max_positions=2, position_fraction=0.4)).run()
        self.assertLessEqual(int(result.equity["open_positions"].max()), 2)
        self.assertLessEqual(len({trade.symbol for trade in result.trades}), 2)
        self.assert_reconciles(result, 1000)

    def test_max_positions_ranks_by_score(self):
        class ScoredStrategy(ScriptedStrategy):
            def signals(self, frame):
                signals = super().signals(frame)
                start_price = float(frame["close"].iloc[0])
                if start_price == 100.0:
                    signals.score[:] = 0.9
                elif start_price == 101.0:
                    signals.score[:] = 0.5
                else:
                    signals.score[:] = 0.8
                return signals

        prices = {
            "A": flat_frame(price=100.0),
            "B": flat_frame(price=101.0),
            "C": flat_frame(price=102.0),
        }
        result = Backtest(prices, ScoredStrategy(entries=[0]), config(max_positions=2, position_fraction=0.4)).run()
        traded = {trade.symbol for trade in result.trades}
        self.assertEqual(traded, {"A", "C"})

    def test_fees_and_slippage_reduce_returns(self):
        frame = make_frame(
            [100, 110, 120, 120],
            opens=[100, 100, 120, 120],
            highs=[121, 121, 121, 121],
            lows=[99, 99, 119, 119],
        )
        free = Backtest({"A": frame}, ScriptedStrategy(entries=[0], exits=[1]), config()).run()
        costly = Backtest(
            {"A": frame},
            ScriptedStrategy(entries=[0], exits=[1]),
            config(fee_bps=100, slippage_bps=100),
        ).run()
        self.assertGreater(free.metrics["final_equity"], costly.metrics["final_equity"])
        self.assertGreater(costly.metrics["final_equity"], 1000)
        self.assertAlmostEqual(costly.metrics["final_equity"], 1000 + costly.trades[0].pnl, places=6)

    def test_max_hold_bars(self):
        frame = flat_frame(length=6)
        result = Backtest(
            {"A": frame},
            ScriptedStrategy(entries=[0]),
            config(max_hold_bars=2),
        ).run()
        trade = result.trades[0]
        self.assertEqual(trade.reason, "max_hold")
        self.assertEqual(trade.bars_held, 2)
        self.assertEqual(trade.exit_date, frame.index[3])

    def test_close_at_end_can_be_disabled(self):
        frame = flat_frame()
        result = Backtest(
            {"A": frame},
            ScriptedStrategy(entries=[0]),
            config(close_at_end=False),
        ).run()
        self.assertEqual(result.trades, [])
        self.assertEqual(int(result.equity.iloc[-1]["open_positions"]), 1)
        self.assertAlmostEqual(result.metrics["final_equity"], 1000.0)

    def test_end_of_backtest_liquidation(self):
        frame = flat_frame()
        result = Backtest({"A": frame}, ScriptedStrategy(entries=[0]), config()).run()
        self.assertEqual(len(result.trades), 1)
        self.assertEqual(result.trades[0].reason, "end_of_backtest")
        self.assertAlmostEqual(result.metrics["final_equity"], 1000.0)

    def test_cash_is_never_overdrawn(self):
        prices = {symbol: flat_frame() for symbol in ("A", "B")}
        result = Backtest(
            prices,
            ScriptedStrategy(entries=[0]),
            config(max_positions=2, position_fraction=1.0),
        ).run()
        self.assertTrue((result.equity["cash"] >= -1e-9).all())
        self.assert_reconciles(result, 1000)

    def test_missing_bars_are_tolerated(self):
        frame_a = make_frame(
            [100, 100, 100, 100],
            start="2024-01-01",
        )
        frame_b = make_frame(
            [200, 200, 200],
            start="2024-01-02",
        )
        result = Backtest(
            {"A": frame_a, "B": frame_b},
            ScriptedStrategy(entries=[0]),
            config(max_positions=2, position_fraction=0.4),
        ).run()
        self.assertGreaterEqual(len(result.equity), 4)
        self.assertTrue((result.equity["cash"] >= -1e-9).all())

    def test_invalid_inputs(self):
        with self.assertRaises(ValueError):
            Backtest({}, ScriptedStrategy(entries=[]), config())
        with self.assertRaises(ValueError):
            Backtest({"A": pd.DataFrame()}, ScriptedStrategy(entries=[]), config()).run()
        with self.assertRaises(ValueError):
            BacktestConfig(initial_cash=0)
        with self.assertRaises(ValueError):
            BacktestConfig(max_positions=0)
        with self.assertRaises(ValueError):
            BacktestConfig(position_fraction=0)
        with self.assertRaises(ValueError):
            BacktestConfig(stop_loss_pct=1)
        with self.assertRaises(ValueError):
            BacktestConfig(fee_bps=-1)

    def test_one_bar_symbol_rejected(self):
        frame = make_frame([100])
        with self.assertRaises(ValueError):
            Backtest({"A": frame}, ScriptedStrategy(entries=[]), config()).run()


if __name__ == "__main__":
    unittest.main()
