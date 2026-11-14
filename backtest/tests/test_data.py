from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import pandas as pd

from backtest.data import (
    DataError,
    fetch_yahoo,
    fetch_yahoo_range,
    load_csv,
    load_prices,
    normalize_symbol,
    parse_yahoo_chart,
)


def chart_payload(timestamps, closes):
    length = len(timestamps)
    return {
        "chart": {
            "result": [
                {
                    "meta": {"regularMarketPrice": closes[-1]},
                    "timestamp": timestamps,
                    "indicators": {
                        "quote": [
                            {
                                "open": [None if close is None else close - 1 for close in closes],
                                "high": [None if close is None else close + 2 for close in closes],
                                "low": [None if close is None else close - 2 for close in closes],
                                "close": closes,
                                "volume": [1000] * length,
                            }
                        ]
                    },
                }
            ],
            "error": None,
        }
    }


class DummyResponse:
    def __init__(self, status_code=200, payload=None, text=""):
        self.status_code = status_code
        self._payload = payload
        self.text = text

    def json(self):
        if self._payload is None:
            raise ValueError("no json")
        return self._payload


class DataTests(unittest.TestCase):
    def test_normalize_symbol(self):
        self.assertEqual(normalize_symbol("RELIANCE"), "RELIANCE.NS")
        self.assertEqual(normalize_symbol("RELIANCE.NS"), "RELIANCE.NS")
        self.assertEqual(normalize_symbol("NSE:INFY"), "INFY.NS")
        self.assertEqual(normalize_symbol("^NSEI"), "^NSEI")
        self.assertEqual(normalize_symbol(""), "")

    def test_parse_yahoo_chart_drops_invalid_rows(self):
        payload = chart_payload([1700000000, 1700000060, 1700000120], [100.0, None, 102.0])
        frame = parse_yahoo_chart(payload, "RELIANCE.NS")
        self.assertEqual(len(frame), 2)
        self.assertEqual(frame["close"].tolist(), [100.0, 102.0])
        self.assertEqual(str(frame.index.tz), "Asia/Kolkata")
        self.assertTrue((frame["high"] >= frame[["open", "close"]].max(axis=1)).all())
        self.assertTrue((frame["low"] <= frame[["open", "close"]].min(axis=1)).all())

    def test_parse_yahoo_chart_errors(self):
        with self.assertRaises(DataError):
            parse_yahoo_chart({"chart": {"result": [], "error": None}}, "X")
        with self.assertRaises(DataError):
            parse_yahoo_chart({"chart": {"result": None, "error": {"description": "boom"}}}, "X")

    def test_fetch_yahoo_builds_suffixed_url(self):
        payload = chart_payload([1700000000, 1700000060], [100.0, 101.0])
        with patch("backtest.data.requests.get", return_value=DummyResponse(payload=payload)) as getter:
            frame = fetch_yahoo("RELIANCE", period="1mo", interval="1d")
        self.assertEqual(len(frame), 2)
        url = getter.call_args.args[0]
        self.assertIn("RELIANCE.NS", url)
        self.assertIn("interval=1d", url)
        self.assertIn("range=1mo", url)

    def test_fetch_yahoo_retries_server_errors(self):
        payload = chart_payload([1700000000, 1700000060], [100.0, 101.0])
        responses = [DummyResponse(status_code=429, text="slow down"), DummyResponse(payload=payload)]
        with patch("backtest.data.requests.get", side_effect=responses), patch("backtest.data.time.sleep"):
            frame = fetch_yahoo("TCS", retries=2)
        self.assertEqual(len(frame), 2)

    def test_fetch_yahoo_fails_fast_on_client_error(self):
        with patch("backtest.data.requests.get", return_value=DummyResponse(status_code=404, text="nope")):
            with self.assertRaises(DataError):
                fetch_yahoo("NOPE", retries=2)

    def test_load_csv(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "RELIANCE.NS.csv"
            path.write_text(
                "date,open,high,low,close,volume\n"
                "2024-01-01,100,102,99,101,1000\n"
                "2024-01-02,101,103,100,102,1200\n",
                encoding="utf-8",
            )
            frame = load_csv(path)
            self.assertEqual(len(frame), 2)
            self.assertEqual(frame.index.name, "timestamp")
            self.assertEqual(str(frame.index.tz), "Asia/Kolkata")
            self.assertEqual(frame["close"].tolist(), [101.0, 102.0])

    def test_load_csv_missing_columns(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bad.csv"
            path.write_text("date,close\n2024-01-01,100\n", encoding="utf-8")
            with self.assertRaises(DataError):
                load_csv(path)

    def test_load_prices_uses_csv_then_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source"
            cache = Path(directory) / "cache"
            source.mkdir()
            csv_path = source / "RELIANCE.NS.csv"
            csv_path.write_text(
                "date,open,high,low,close,volume\n"
                "2024-01-01,100,102,99,101,1000\n"
                "2024-01-02,101,103,100,102,1200\n",
                encoding="utf-8",
            )
            prices = load_prices(
                ["RELIANCE.NS"], source="csv", csv_dir=source, cache_dir=cache
            )
            self.assertEqual(list(prices), ["RELIANCE.NS"])
            self.assertTrue(any(cache.iterdir()))

            # The cache alone must satisfy the second call.
            csv_path.unlink()
            cached = load_prices(
                ["RELIANCE.NS"], source="csv", csv_dir=source, cache_dir=cache
            )
            self.assertEqual(len(cached["RELIANCE.NS"]), 2)

    def test_load_prices_all_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(DataError):
                load_prices(["NOPE"], source="csv", csv_dir=directory)


if __name__ == "__main__":
    unittest.main()


class YahooRangeTests(unittest.TestCase):
    def test_fetch_yahoo_range_uses_period_bounds_and_filters(self):
        # Three 5-minute bars across two sessions.  Only the middle session
        # should survive the inclusive-start/exclusive-end filter.
        stamps = [
            int(pd.Timestamp("2024-01-01 15:20", tz="Asia/Kolkata").timestamp()),
            int(pd.Timestamp("2024-01-02 09:15", tz="Asia/Kolkata").timestamp()),
            int(pd.Timestamp("2024-01-02 09:20", tz="Asia/Kolkata").timestamp()),
            int(pd.Timestamp("2024-01-03 09:15", tz="Asia/Kolkata").timestamp()),
        ]
        payload = chart_payload(stamps, [100.0, 101.0, 102.0, 103.0])
        with patch("backtest.data.requests.get", return_value=DummyResponse(payload=payload)) as getter:
            frame = fetch_yahoo_range(
                "RELIANCE",
                start="2024-01-02",
                end="2024-01-03",
                interval="5m",
            )
        self.assertEqual(frame["close"].tolist(), [101.0, 102.0])
        self.assertEqual(str(frame.index.tz), "Asia/Kolkata")
        url = getter.call_args.args[0]
        self.assertIn("period1=", url)
        self.assertIn("period2=", url)
        self.assertIn("interval=5m", url)
        self.assertNotIn("range=", url)

    def test_fetch_yahoo_range_rejects_empty_range(self):
        payload = chart_payload(
            [int(pd.Timestamp("2024-01-02 09:15", tz="Asia/Kolkata").timestamp())],
            [100.0],
        )
        with patch("backtest.data.requests.get", return_value=DummyResponse(payload=payload)):
            with self.assertRaises(DataError):
                fetch_yahoo_range(
                    "RELIANCE",
                    start="2024-01-05",
                    end="2024-01-06",
                )

    def test_fetch_yahoo_range_validates_order(self):
        with self.assertRaises(ValueError):
            fetch_yahoo_range("RELIANCE", start="2024-01-03", end="2024-01-02")
