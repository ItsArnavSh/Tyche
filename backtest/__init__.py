"""Tyche's offline research toolkit.

The live system (Java core + Go gateway) trades autonomously.  This package is
the complementary research layer: it downloads real Indian market data and runs
honest, next-bar-executed backtests so strategy changes can be evaluated before
they reach the live signal pipeline.
"""

from .data import fetch_yahoo, fetch_yahoo_range, load_prices, normalize_symbol
from .engine import Backtest, BacktestConfig, BacktestResult, Trade
from .metrics import compute_metrics
from .strategies import STRATEGIES, Strategy, build_strategy

__all__ = [
    "Backtest",
    "BacktestConfig",
    "BacktestResult",
    "STRATEGIES",
    "Strategy",
    "Trade",
    "build_strategy",
    "compute_metrics",
    "fetch_yahoo",
    "fetch_yahoo_range",
    "load_prices",
    "normalize_symbol",
]

__version__ = "1.0.0"
