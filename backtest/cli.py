"""Command-line interface for the backtester.

Examples::

    python -m backtest --strategy sma --period 5y
    python -m backtest --symbols RELIANCE.NS,TCS.NS --strategy rsi \
        --param period=14 --param oversold=25 --cash 200000
    python -m backtest --source csv --csv-dir ./data --symbols RELIANCE.NS
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from dataclasses import asdict
from pathlib import Path
from typing import Any, Sequence

import pandas as pd

from .data import DataError, load_prices
from .engine import Backtest, BacktestConfig
from .strategies import STRATEGIES, build_strategy

DEFAULT_SYMBOLS = "RELIANCE.NS,TCS.NS,HDFCBANK.NS,INFY.NS,ICICIBANK.NS"


def parse_params(values: Sequence[str] | None) -> dict[str, Any]:
    params: dict[str, Any] = {}
    for item in values or []:
        if "=" not in item:
            raise ValueError(f"--param must be key=value, got {item!r}")
        key, raw = item.split("=", 1)
        key = key.strip()
        raw = raw.strip()
        if not key:
            raise ValueError(f"empty parameter name in {item!r}")
        params[key] = _coerce(raw)
    return params


def _coerce(value: str) -> Any:
    lowered = value.lower()
    if lowered in {"true", "false"}:
        return lowered == "true"
    try:
        return int(value)
    except ValueError:
        pass
    try:
        return float(value)
    except ValueError:
        return value


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m backtest",
        description="Backtest a strategy on real Indian market data.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--symbols", default=DEFAULT_SYMBOLS, help="comma-separated NSE symbols")
    parser.add_argument("--period", default="2y", help="Yahoo range, e.g. 1y, 2y, 5y, 10y, max")
    parser.add_argument("--interval", default="1d", help="Yahoo interval, e.g. 1d, 60m, 15m")
    parser.add_argument("--source", choices=["yahoo", "yfinance", "csv"], default="yahoo")
    parser.add_argument("--csv-dir", help="directory with <symbol>.csv files (csv source)")
    parser.add_argument(
        "--strategy",
        default="sma",
        choices=sorted(set(STRATEGIES)),
        help="strategy to run",
    )
    parser.add_argument(
        "--param",
        action="append",
        default=[],
        metavar="KEY=VALUE",
        help="strategy parameter, repeatable",
    )
    parser.add_argument("--cash", type=float, default=100_000.0, help="initial cash")
    parser.add_argument("--max-positions", type=int, default=5)
    parser.add_argument("--position-fraction", type=float, default=0.20)
    parser.add_argument("--fee-bps", type=float, default=5.0, help="fees per side in basis points")
    parser.add_argument("--slippage-bps", type=float, default=5.0, help="slippage per side in basis points")
    parser.add_argument("--stop-loss", type=float, default=0.07, help="stop loss (0 disables)")
    parser.add_argument("--take-profit", type=float, default=0.0, help="take profit (0 disables)")
    parser.add_argument("--max-hold-bars", type=int, default=0, help="maximum bars held (0 disables)")
    parser.add_argument(
        "--cache-dir",
        default="~/.cache/tyche-backtest",
        help="price cache directory; empty string disables caching",
    )
    parser.add_argument("--cache-ttl-hours", type=float, default=12.0)
    parser.add_argument("--timeout", type=float, default=15.0)
    parser.add_argument("--retries", type=int, default=3)
    parser.add_argument("--json-out", help="write metrics and trades as JSON")
    parser.add_argument("--trades-out", help="write the trade blotter as CSV")
    parser.add_argument("--equity-out", help="write the equity curve as CSV")
    parser.add_argument("--quiet", action="store_true", help="only print the metric table")
    return parser


def _symbols(raw: str) -> list[str]:
    symbols = [item.strip() for item in raw.split(",") if item.strip()]
    if not symbols:
        raise ValueError("--symbols must contain at least one symbol")
    return symbols


def _fmt_money(value: float) -> str:
    if not math.isfinite(value):
        return "n/a"
    return f"{value:,.2f}"


def _fmt_pct(value: float) -> str:
    if not math.isfinite(value):
        return "n/a"
    return f"{value * 100:,.2f}%"


def _fmt_number(value: float) -> str:
    if not math.isfinite(value):
        return "inf" if value > 0 else "n/a"
    return f"{value:.2f}"


def _print_report(result, symbols: list[str]) -> None:
    metrics = result.metrics
    print()
    print("=" * 68)
    print(f"Strategy : {result.strategy}")
    print(f"Symbols  : {', '.join(symbols)}")
    print(f"Period   : {result.equity.index[0].date()} -> {result.equity.index[-1].date()} "
          f"({len(result.equity)} bars)")
    print("=" * 68)
    rows = [
        ("Initial cash", _fmt_money(metrics["initial_cash"])),
        ("Final equity", _fmt_money(metrics["final_equity"])),
        ("Total return", _fmt_pct(metrics["total_return"])),
        ("CAGR", _fmt_pct(metrics["cagr"])),
        ("Annualised volatility", _fmt_pct(metrics["annualized_volatility"])),
        ("Sharpe", _fmt_number(metrics["sharpe"])),
        ("Sortino", _fmt_number(metrics["sortino"])),
        ("Max drawdown", _fmt_pct(-metrics["max_drawdown"])),
        ("Max DD duration", f"{int(metrics['max_drawdown_duration'])} bars"),
        ("Calmar", _fmt_number(metrics["calmar"])),
        ("Exposure", _fmt_pct(metrics["exposure"])),
        ("Trades", f"{int(metrics['total_trades'])}"),
        ("Win rate", _fmt_pct(metrics["win_rate"])),
        ("Profit factor", _fmt_number(metrics["profit_factor"])),
        ("Expectancy / trade", _fmt_money(metrics["expectancy"])),
        ("Avg trade return", _fmt_pct(metrics["avg_trade_return"])),
        ("Avg bars held", _fmt_number(metrics["avg_bars_held"])),
        ("Best trade", _fmt_money(metrics["best_trade"])),
        ("Worst trade", _fmt_money(metrics["worst_trade"])),
    ]
    for label, value in rows:
        print(f"{label:<24}{value:>20}")

    if result.trades:
        frame = result.trades_frame()
        print("-" * 68)
        print("Per-symbol trades")
        grouped = frame.groupby("symbol").agg(
            trades=("pnl", "size"),
            pnl=("pnl", "sum"),
            win_rate=("pnl", lambda values: float((values > 0).mean())),
        )
        for symbol, row in grouped.sort_values("pnl", ascending=False).iterrows():
            print(
                f"  {symbol:<16} trades={int(row['trades']):>3} "
                f"P&L={_fmt_money(float(row['pnl'])):>14} "
                f"win_rate={_fmt_pct(float(row['win_rate']))}"
            )
        print("-" * 68)
        print("Last trades")
        for record in result.trades[-10:]:
            print(
                f"  {record.symbol:<14} {record.entry_date.date()} -> {record.exit_date.date()} "
                f"qty={record.quantity:<5} entry={record.entry_price:>9.2f} "
                f"exit={record.exit_price:>9.2f} pnl={_fmt_money(record.pnl):>11} "
                f"{record.reason}"
            )
    print("=" * 68)


def _sanitize(value: Any) -> Any:
    if isinstance(value, float):
        return value if math.isfinite(value) else None
    if isinstance(value, dict):
        return {key: _sanitize(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_sanitize(item) for item in value]
    if isinstance(value, (pd.Timestamp,)):
        return value.isoformat()
    return value


def run(args: argparse.Namespace) -> int:
    symbols = _symbols(args.symbols)
    params = parse_params(args.param)
    strategy = build_strategy(args.strategy, params)
    config = BacktestConfig(
        initial_cash=args.cash,
        max_positions=args.max_positions,
        position_fraction=args.position_fraction,
        fee_bps=args.fee_bps,
        slippage_bps=args.slippage_bps,
        stop_loss_pct=args.stop_loss,
        take_profit_pct=args.take_profit,
        max_hold_bars=args.max_hold_bars,
    )
    cache_dir = Path(args.cache_dir).expanduser() if args.cache_dir else None
    if not args.quiet:
        print(f"Loading {len(symbols)} symbol(s) from {args.source} ({args.period}, {args.interval})...")
    prices = load_prices(
        symbols,
        source=args.source,
        period=args.period,
        interval=args.interval,
        csv_dir=args.csv_dir,
        cache_dir=cache_dir,
        cache_ttl=args.cache_ttl_hours * 3600,
        timeout=args.timeout,
        retries=args.retries,
    )
    if not args.quiet:
        for symbol, frame in prices.items():
            print(f"  {symbol:<18} {len(frame):>5} bars  {frame.index[0].date()} -> {frame.index[-1].date()}")

    result = Backtest(prices, strategy, config).run()
    _print_report(result, list(prices))

    if args.json_out:
        payload = {
            "strategy": result.strategy,
            "symbols": list(prices),
            "metrics": result.metrics,
            "config": asdict(config),
            "trades": [trade.to_dict() for trade in result.trades],
        }
        Path(args.json_out).expanduser().write_text(
            json.dumps(_sanitize(payload), indent=2, allow_nan=False) + "\n",
            encoding="utf-8",
        )
        if not args.quiet:
            print(f"Wrote JSON report to {args.json_out}")
    if args.trades_out:
        result.trades_frame().to_csv(Path(args.trades_out).expanduser(), index=False)
        if not args.quiet:
            print(f"Wrote trade blotter to {args.trades_out}")
    if args.equity_out:
        result.equity.to_csv(Path(args.equity_out).expanduser())
        if not args.quiet:
            print(f"Wrote equity curve to {args.equity_out}")
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return run(args)
    except (DataError, ValueError, OSError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
