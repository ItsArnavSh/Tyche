"""Performance metrics for backtest results."""

from __future__ import annotations

import math
from typing import Iterable

import numpy as np
import pandas as pd


def _empty_metrics(initial_cash: float) -> dict[str, float]:
    return {
        "initial_cash": float(initial_cash),
        "final_equity": float(initial_cash),
        "total_return": 0.0,
        "cagr": 0.0,
        "annualized_volatility": 0.0,
        "sharpe": 0.0,
        "sortino": 0.0,
        "max_drawdown": 0.0,
        "max_drawdown_duration": 0,
        "calmar": 0.0,
        "exposure": 0.0,
        "total_trades": 0,
        "wins": 0,
        "losses": 0,
        "breakeven": 0,
        "win_rate": 0.0,
        "profit_factor": 0.0,
        "gross_profit": 0.0,
        "gross_loss": 0.0,
        "avg_trade_pnl": 0.0,
        "avg_trade_return": 0.0,
        "expectancy": 0.0,
        "avg_bars_held": 0.0,
        "best_trade": 0.0,
        "worst_trade": 0.0,
    }


def compute_metrics(
    equity: pd.DataFrame | pd.Series,
    trades: Iterable[object],
    *,
    initial_cash: float,
    periods_per_year: int = 252,
    risk_free_rate: float = 0.0,
) -> dict[str, float]:
    """Compute return, risk and trade statistics.

    ``trades`` may be :class:`backtest.engine.Trade` objects or any object with
    ``pnl``, ``return_pct`` and ``bars_held`` attributes.
    """
    if initial_cash <= 0:
        raise ValueError("initial_cash must be positive")
    if isinstance(equity, pd.DataFrame):
        if "equity" not in equity.columns:
            raise ValueError("equity frame must contain an 'equity' column")
        curve = equity["equity"].astype(float).sort_index()
        exposure = equity["exposure"].astype(float) if "exposure" in equity.columns else None
    else:
        curve = equity.astype(float).sort_index()
        exposure = None

    trade_list = list(trades)
    if curve.empty:
        metrics = _empty_metrics(initial_cash)
        _add_trade_metrics(metrics, trade_list, initial_cash)
        return metrics

    values = np.concatenate(([float(initial_cash)], curve.to_numpy(dtype=float)))
    final_equity = float(values[-1])
    total_return = final_equity / initial_cash - 1.0

    bar_count = max(1, len(values) - 1)
    growth = final_equity / initial_cash
    cagr = growth ** (periods_per_year / bar_count) - 1.0 if growth > 0 else -1.0

    with np.errstate(divide="ignore", invalid="ignore"):
        returns = np.diff(values) / values[:-1]
    returns = returns[np.isfinite(returns)]
    if returns.size > 1:
        mean_return = float(np.mean(returns))
        std_return = float(np.std(returns, ddof=1))
        volatility = std_return * math.sqrt(periods_per_year)
        excess = mean_return - risk_free_rate / periods_per_year
        sharpe = excess / std_return * math.sqrt(periods_per_year) if std_return > 0 else 0.0
        downside = returns[returns < 0]
        downside_std = float(np.std(downside, ddof=0)) if downside.size else 0.0
        sortino = excess / downside_std * math.sqrt(periods_per_year) if downside_std > 0 else 0.0
    else:
        volatility = 0.0
        sharpe = 0.0
        sortino = 0.0

    running_max = np.maximum.accumulate(values)
    drawdowns = values / running_max - 1.0
    max_drawdown = float(-np.min(drawdowns))
    max_drawdown_duration = _max_drawdown_duration(values)
    calmar = cagr / max_drawdown if max_drawdown > 0 else 0.0

    metrics: dict[str, float] = {
        "initial_cash": float(initial_cash),
        "final_equity": final_equity,
        "total_return": float(total_return),
        "cagr": float(cagr),
        "annualized_volatility": float(volatility),
        "sharpe": float(sharpe),
        "sortino": float(sortino),
        "max_drawdown": max_drawdown,
        "max_drawdown_duration": int(max_drawdown_duration),
        "calmar": float(calmar),
        "exposure": float(exposure.mean()) if exposure is not None and not exposure.empty else 0.0,
    }
    _add_trade_metrics(metrics, trade_list, initial_cash)
    return metrics


def _add_trade_metrics(metrics: dict[str, float], trades: list[object], initial_cash: float) -> None:
    pnls = np.array([float(getattr(trade, "pnl", 0.0)) for trade in trades], dtype=float)
    returns = np.array([float(getattr(trade, "return_pct", 0.0)) for trade in trades], dtype=float)
    bars = np.array([float(getattr(trade, "bars_held", 0.0)) for trade in trades], dtype=float)
    total = int(pnls.size)
    wins = int(np.sum(pnls > 0))
    losses = int(np.sum(pnls < 0))
    breakeven = total - wins - losses
    gross_profit = float(np.sum(pnls[pnls > 0]))
    gross_loss = float(-np.sum(pnls[pnls < 0]))
    if gross_loss > 0:
        profit_factor = gross_profit / gross_loss
    elif gross_profit > 0:
        profit_factor = math.inf
    else:
        profit_factor = 0.0
    win_rate = wins / total if total else 0.0
    avg_win = float(np.mean(pnls[pnls > 0])) if wins else 0.0
    avg_loss = float(np.mean(pnls[pnls < 0])) if losses else 0.0
    loss_rate = losses / total if total else 0.0
    metrics.update(
        {
            "total_trades": total,
            "wins": wins,
            "losses": losses,
            "breakeven": breakeven,
            "win_rate": float(win_rate),
            "profit_factor": float(profit_factor),
            "gross_profit": gross_profit,
            "gross_loss": gross_loss,
            "avg_trade_pnl": float(np.mean(pnls)) if total else 0.0,
            "avg_trade_return": float(np.mean(returns)) if total else 0.0,
            "expectancy": float(win_rate * avg_win + loss_rate * avg_loss),
            "avg_bars_held": float(np.mean(bars)) if total else 0.0,
            "best_trade": float(np.max(pnls)) if total else 0.0,
            "worst_trade": float(np.min(pnls)) if total else 0.0,
        }
    )
    _ = initial_cash


def _max_drawdown_duration(values: np.ndarray) -> int:
    peak = values[0]
    peak_index = 0
    longest = 0
    for index, value in enumerate(values):
        if value >= peak:
            peak = value
            peak_index = index
        else:
            longest = max(longest, index - peak_index)
    return longest
