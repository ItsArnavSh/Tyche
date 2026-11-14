from __future__ import annotations

import json
import threading
import unittest
import urllib.error
import urllib.request

import pandas as pd

from backtest.data import DataError
from history.cache import MemoryFrameCache
from history.server import HistoryServer
from history.service import HistoryApp, fetch_day, parse_date, run_replay


def session_frame(target: str = "2024-01-03", *, bars_per_day: int = 8) -> pd.DataFrame:
    """Two warmup sessions plus one target session of 5-minute bars."""
    target_day = parse_date(target)
    closes: list[float] = []
    stamps: list[pd.Timestamp] = []
    warmup = [110, 111, 112, 113, 114, 115, 116, 117]
    target_closes = [100, 100, 100, 101, 102, 103, 104, 105]
    for day_offset in (-2, -1, 0):
        day = target_day + pd.Timedelta(days=day_offset)
        values = warmup if day_offset < 0 else target_closes
        for index, close in enumerate(values[:bars_per_day]):
            stamps.append(day + pd.Timedelta(hours=9, minutes=15 + 5 * index))
            closes.append(float(close))
    frame = pd.DataFrame(
        {
            "open": closes,
            "high": [value + 1 for value in closes],
            "low": [value - 1 for value in closes],
            "close": closes,
            "volume": [1_000] * len(closes),
        },
        index=pd.DatetimeIndex(stamps, name="timestamp"),
    )
    return frame


class FakeFetcher:
    def __init__(self, frame: pd.DataFrame | None = None) -> None:
        self.frame = frame or session_frame()
        self.calls: list[str] = []

    def __call__(self, symbol, **kwargs):
        self.calls.append(symbol)
        if symbol.startswith("BAD"):
            raise DataError("symbol not found")
        return self.frame.copy()


class ServiceTests(unittest.TestCase):
    def test_fetch_day_uses_cache_on_second_call(self):
        cache = MemoryFrameCache()
        fetcher = FakeFetcher()
        first = fetch_day(
            date="2024-01-03",
            symbols="AAA,BBB",
            interval="5m",
            warmup_days=2,
            cache=cache,
            fetcher=fetcher,
        )
        self.assertEqual(sorted(first.frames), ["AAA.NS", "BBB.NS"])
        self.assertEqual(first.cache.hits, 0)
        self.assertEqual(first.cache.misses, 2)
        self.assertEqual(first.cache.stored, 2)
        self.assertEqual(first.summary()["target_bars"]["AAA.NS"], 8)
        self.assertEqual(len(fetcher.calls), 2)

        second = fetch_day(
            date="2024-01-03",
            symbols="AAA,BBB",
            interval="5m",
            warmup_days=2,
            cache=cache,
            fetcher=fetcher,
        )
        self.assertEqual(second.cache.hits, 2)
        self.assertEqual(second.cache.misses, 0)
        self.assertEqual(second.cache.stored, 0)
        self.assertEqual(len(fetcher.calls), 2)  # cache hit avoided the network

    def test_fetch_day_keeps_partial_results_and_reports_failures(self):
        dataset = fetch_day(
            date="2024-01-03",
            symbols="AAA,BAD",
            interval="5m",
            warmup_days=2,
            cache=MemoryFrameCache(),
            fetcher=FakeFetcher(),
        )
        self.assertEqual(sorted(dataset.frames), ["AAA.NS"])
        self.assertIn("BAD.NS", dataset.failures)

    def test_fetch_day_all_failures_raises(self):
        with self.assertRaises(DataError):
            fetch_day(
                date="2024-01-03",
                symbols="BAD",
                interval="5m",
                warmup_days=2,
                cache=MemoryFrameCache(),
                fetcher=FakeFetcher(),
            )

    def test_replay_only_emits_target_session(self):
        dataset = fetch_day(
            date="2024-01-03",
            symbols="AAA",
            interval="5m",
            warmup_days=2,
            cache=MemoryFrameCache(),
            fetcher=FakeFetcher(),
        )
        result = run_replay(
            dataset,
            {
                "strategy": "sma",
                "params": {"fast": 2, "slow": 3},
                "cash": 1000,
                "max_positions": 1,
                "position_fraction": 1.0,
                "fee_bps": 0,
                "slippage_bps": 0,
                "stop_loss_pct": 0,
                "take_profit_pct": 0,
                "max_hold_bars": 0,
                "close_at_end": True,
            },
        )
        self.assertEqual(result["bars"], 8)
        self.assertEqual(len(result["replay"]), 8)
        self.assertTrue(result["first_bar"].startswith("2024-01-03T09:15:00+05:30"))
        self.assertTrue(result["last_bar"].startswith("2024-01-03T09:50:00+05:30"))
        self.assertEqual(result["config"]["initial_cash"], 1000.0)
        for record in result["replay"]:
            self.assertIn("equity", record)
            self.assertIn("positions", record)
            self.assertIn("events", record)
        self.assertEqual(
            result["metrics"]["final_equity"],
            result["replay"][-1]["equity"],
        )

    def test_app_fetch_and_strategies(self):
        app = HistoryApp(cache=MemoryFrameCache(), fetcher=FakeFetcher())
        response = app.fetch({"date": "2024-01-03", "symbols": "AAA", "warmup_days": 2})
        self.assertEqual(response["dataset"]["date"], "2024-01-03")
        self.assertEqual(response["dataset"]["cache"]["backend"], "memory")
        names = {item["name"] for item in app.strategies()["strategies"]}
        self.assertIn("sma", names)
        self.assertIn("rsi", names)

    def test_http_replay_endpoint(self):
        app = HistoryApp(cache=MemoryFrameCache(), fetcher=FakeFetcher())
        server = HistoryServer(("127.0.0.1", 0), app)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            body = json.dumps(
                {
                    "date": "2024-01-03",
                    "symbols": "AAA",
                    "warmup_days": 2,
                    "strategy": "sma",
                    "params": {"fast": 2, "slow": 3},
                }
            ).encode("utf-8")
            request = urllib.request.Request(
                f"http://127.0.0.1:{server.server_port}/api/history/replay",
                data=body,
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with urllib.request.urlopen(request, timeout=10) as response:
                payload = json.loads(response.read().decode("utf-8"))
                self.assertEqual(response.status, 200)
                self.assertEqual(payload["bars"], 8)
                self.assertIn("replay", payload)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

    def test_bad_request_returns_json_error(self):
        app = HistoryApp(cache=MemoryFrameCache(), fetcher=FakeFetcher())
        server = HistoryServer(("127.0.0.1", 0), app)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            request = urllib.request.Request(
                f"http://127.0.0.1:{server.server_port}/api/history/replay",
                data=b"{}",
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with self.assertRaises(urllib.error.HTTPError) as error:
                urllib.request.urlopen(request, timeout=10)
            self.assertEqual(error.exception.code, 400)
            payload = json.loads(error.exception.read().decode("utf-8"))
            error.exception.close()
            self.assertIn("date", payload["error"])
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)


if __name__ == "__main__":
    unittest.main()
