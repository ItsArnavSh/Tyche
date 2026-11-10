from __future__ import annotations

import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

from backtest.cli import main, parse_params
from backtest.tests import trending_frame


class CliTests(unittest.TestCase):
    def test_parse_params(self):
        self.assertEqual(
            parse_params(["fast=5", "slow=20", "threshold=0.5", "flag=true", "name=x"]),
            {"fast": 5, "slow": 20, "threshold": 0.5, "flag": True, "name": "x"},
        )
        with self.assertRaises(ValueError):
            parse_params(["broken"])

    def test_cli_end_to_end_on_csv(self):
        frame = trending_frame(160)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            frame.to_csv(root / "TEST.NS.csv", index_label="timestamp")
            json_path = root / "report.json"
            trades_path = root / "trades.csv"
            equity_path = root / "equity.csv"
            output = io.StringIO()
            with redirect_stdout(output):
                code = main(
                    [
                        "--source", "csv",
                        "--csv-dir", str(root),
                        "--symbols", "TEST.NS",
                        "--strategy", "sma",
                        "--param", "fast=3",
                        "--param", "slow=8",
                        "--cash", "100000",
                        "--cache-dir", "",
                        "--json-out", str(json_path),
                        "--trades-out", str(trades_path),
                        "--equity-out", str(equity_path),
                    ]
                )
            self.assertEqual(code, 0)
            text = output.getvalue()
            self.assertIn("Total return", text)
            self.assertIn("Strategy : sma(fast=3, slow=8)", text)
            self.assertTrue(json_path.exists())
            payload = json.loads(json_path.read_text(encoding="utf-8"))
            self.assertIn("metrics", payload)
            self.assertIn("trades", payload)
            self.assertEqual(payload["symbols"], ["TEST.NS"])
            self.assertTrue(trades_path.exists())
            self.assertTrue(equity_path.exists())

    def test_cli_reports_missing_csv(self):
        with tempfile.TemporaryDirectory() as directory:
            stderr = io.StringIO()
            from contextlib import redirect_stderr

            with redirect_stderr(stderr):
                code = main(
                    [
                        "--source", "csv",
                        "--csv-dir", directory,
                        "--symbols", "NOPE.NS",
                        "--cache-dir", "",
                        "--quiet",
                    ]
                )
            self.assertEqual(code, 1)
            self.assertIn("error:", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
