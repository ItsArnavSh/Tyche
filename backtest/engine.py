"""Portfolio backtest engine with next-bar execution.

Design rules:

* A strategy decision made at the close of bar ``t`` is executed at the open of
  bar ``t + 1``.  Nothing in the engine reads a future row.
* Stops and targets are intraday exits evaluated against the current bar's
  high/low.  When both would trigger, the stop is assumed to fill first
  (conservative).
* Every fill pays configurable slippage and proportional fees.
* Cash is shared across symbols; a ticker can only have one open position and
  the portfolio never holds more than ``max_positions`` names.
"""

from __future__ import annotations

import math
from dataclasses import asdict, dataclass, field
from typing import Any

import numpy as np
import pandas as pd

from .metrics import compute_metrics
from .strategies import Signals, Strategy


@dataclass
class BacktestConfig:
    initial_cash: float = 100_000.0
    max_positions: int = 5
    position_fraction: float = 0.20
    fee_bps: float = 5.0
    slippage_bps: float = 5.0
    stop_loss_pct: float = 0.07
    take_profit_pct: float = 0.0
    max_hold_bars: int = 0
    close_at_end: bool = True
    periods_per_year: int = 252
    risk_free_rate: float = 0.0

    def __post_init__(self) -> None:
        if self.initial_cash <= 0:
            raise ValueError("initial_cash must be positive")
        if self.max_positions <= 0:
            raise ValueError("max_positions must be positive")
        if not 0 < self.position_fraction <= 1:
            raise ValueError("position_fraction must be in (0, 1]")
        if self.fee_bps < 0 or self.slippage_bps < 0:
            raise ValueError("fees and slippage cannot be negative")
        if not 0 <= self.stop_loss_pct < 1:
            raise ValueError("stop_loss_pct must be in [0, 1)")
        if self.take_profit_pct < 0:
            raise ValueError("take_profit_pct cannot be negative")
        if self.max_hold_bars < 0:
            raise ValueError("max_hold_bars cannot be negative")

    @property
    def fee_rate(self) -> float:
        return self.fee_bps / 10_000.0

    @property
    def slippage_rate(self) -> float:
        return self.slippage_bps / 10_000.0


@dataclass
class Position:
    symbol: str
    quantity: int
    entry_price: float
    entry_date: pd.Timestamp
    entry_fee: float
    stop_price: float | None
    target_price: float | None
    bars_held: int = 0
    last_price: float = 0.0

    @property
    def cost_basis(self) -> float:
        return self.entry_price * self.quantity + self.entry_fee


@dataclass
class Trade:
    symbol: str
    entry_date: pd.Timestamp
    exit_date: pd.Timestamp
    quantity: int
    entry_price: float
    exit_price: float
    fees: float
    pnl: float
    return_pct: float
    reason: str
    bars_held: int

    def to_dict(self) -> dict[str, Any]:
        row = asdict(self)
        row["entry_date"] = self.entry_date.isoformat()
        row["exit_date"] = self.exit_date.isoformat()
        return row


@dataclass
class BacktestResult:
    equity: pd.DataFrame
    trades: list[Trade]
    metrics: dict[str, float]
    strategy: str
    symbols: list[str]
    config: BacktestConfig = field(repr=False)
    # Per-bar portfolio snapshots for the History replay UI.  Empty unless
    # ``record_replay=True`` was passed to :class:`Backtest`.
    replay: list[dict[str, Any]] = field(default_factory=list)

    def trades_frame(self) -> pd.DataFrame:
        if not self.trades:
            return pd.DataFrame(
                columns=[
                    "symbol", "entry_date", "exit_date", "quantity", "entry_price",
                    "exit_price", "fees", "pnl", "return_pct", "reason", "bars_held",
                ]
            )
        return pd.DataFrame([trade.to_dict() for trade in self.trades])


@dataclass
class _SymbolData:
    frame: pd.DataFrame
    index: pd.Index
    positions: dict[pd.Timestamp, int]
    open: np.ndarray
    high: np.ndarray
    low: np.ndarray
    close: np.ndarray
    entry: np.ndarray
    exit: np.ndarray
    score: np.ndarray


class Backtest:
    def __init__(
        self,
        prices: dict[str, pd.DataFrame],
        strategy: Strategy,
        config: BacktestConfig | None = None,
        *,
        start: Any | None = None,
        end: Any | None = None,
        record_replay: bool = False,
    ) -> None:
        if not prices:
            raise ValueError("prices must contain at least one symbol")
        self.prices = {symbol: frame.sort_index() for symbol, frame in prices.items()}
        self.strategy = strategy
        self.config = config or BacktestConfig()
        # ``start`` is inclusive and ``end`` is exclusive.  Signals are still
        # computed over the full warmup frame; only execution is windowed.
        self.start = start
        self.end = end
        self.record_replay = record_replay
        self._events: list[dict[str, Any]] = []
        self._replay: list[dict[str, Any]] = []

    def run(self) -> BacktestResult:
        self._events = []
        self._replay = []
        data = self._prepare()
        calendar = pd.DatetimeIndex(
            sorted(set().union(*(set(item.index) for item in data.values())))
        )
        if self.start is not None:
            start = self._bound(self.start)
            calendar = calendar[calendar >= start]
        if self.end is not None:
            end = self._bound(self.end)
            calendar = calendar[calendar < end]
        if len(calendar) < 2:
            raise ValueError("need at least two bars in the replay window")

        cfg = self.config
        cash = float(cfg.initial_cash)
        positions: dict[str, Position] = {}
        pending_buys: dict[str, float] = {}
        pending_sells: dict[str, str] = {}
        trades: list[Trade] = []
        last_close: dict[str, float] = {}
        records: list[dict[str, Any]] = []
        event_cursor = 0

        for date in calendar:
            available = {symbol: item for symbol, item in data.items() if date in item.positions}
            if not available:
                continue

            # 1. Execute decisions made at the previous close.
            cash = self._execute_sells(date, available, pending_sells, positions, trades, cash)
            cash = self._execute_buys(
                date, available, pending_buys, positions, last_close, cash
            )

            # 2. Intraday risk exits for positions that have a bar today.
            for symbol in sorted(list(positions)):
                if symbol not in available:
                    continue
                position = positions[symbol]
                bar = available[symbol]
                row = bar.positions[date]
                open_price = float(bar.open[row])
                high = float(bar.high[row])
                low = float(bar.low[row])
                exit_raw: float | None = None
                reason = ""
                if position.stop_price is not None and low <= position.stop_price:
                    exit_raw = min(open_price, position.stop_price)
                    reason = "stop_loss"
                elif position.target_price is not None and high >= position.target_price:
                    exit_raw = max(open_price, position.target_price)
                    reason = "take_profit"
                if exit_raw is not None:
                    cash = self._close_position(
                        positions, trades, symbol, date, exit_raw, reason, cash
                    )

            # 3. Bar bookkeeping and close marks.
            for symbol, bar in available.items():
                row = bar.positions[date]
                last_close[symbol] = float(bar.close[row])
                if symbol in positions:
                    positions[symbol].bars_held += 1
                    positions[symbol].last_price = last_close[symbol]

            # 4. Signals at the close; queue orders for the next available bar.
            for symbol, bar in available.items():
                row = bar.positions[date]
                position = positions.get(symbol)
                if position is not None:
                    if symbol in pending_sells:
                        continue
                    if cfg.max_hold_bars and position.bars_held >= cfg.max_hold_bars:
                        pending_sells[symbol] = "max_hold"
                        continue
                    if bool(bar.exit[row]):
                        pending_sells[symbol] = "signal_exit"
                    continue
                if symbol in pending_buys or symbol in pending_sells:
                    continue
                if bool(bar.entry[row]):
                    pending_buys[symbol] = float(bar.score[row])

            # 5. Mark the portfolio at the close.
            positions_value = sum(
                position.quantity * last_close.get(symbol, position.entry_price)
                for symbol, position in positions.items()
            )
            equity = cash + positions_value
            record: dict[str, Any] = {
                "timestamp": date,
                "equity": equity,
                "cash": cash,
                "positions_value": positions_value,
                "exposure": positions_value / equity if equity > 0 else 0.0,
                "open_positions": len(positions),
            }
            if self.record_replay:
                record["positions"] = [
                    self._position_snapshot(position)
                    for position in (positions[name] for name in sorted(positions))
                ]
                record["events"] = self._events[event_cursor:]
                event_cursor = len(self._events)
            records.append(record)

        # Liquidate leftovers so metrics and cash reconcile with realised P&L.
        if positions and cfg.close_at_end:
            final_date = calendar[-1]
            for symbol in sorted(list(positions)):
                price = last_close.get(symbol, positions[symbol].entry_price)
                cash = self._close_position(
                    positions, trades, symbol, final_date, price, "end_of_backtest", cash
                )
            if records:
                records[-1]["cash"] = cash
                records[-1]["positions_value"] = 0.0
                records[-1]["equity"] = cash
                records[-1]["exposure"] = 0.0
                records[-1]["open_positions"] = 0
                if self.record_replay:
                    records[-1]["positions"] = []
                    records[-1]["events"] = (
                        records[-1].get("events", []) + self._events[event_cursor:]
                    )

        equity_frame = pd.DataFrame.from_records(records).set_index("timestamp")
        equity_frame.index.name = "timestamp"
        if self.record_replay:
            self._replay = records
        metrics = compute_metrics(
            equity_frame,
            trades,
            initial_cash=cfg.initial_cash,
            periods_per_year=cfg.periods_per_year,
            risk_free_rate=cfg.risk_free_rate,
        )
        return BacktestResult(
            equity=equity_frame,
            trades=trades,
            metrics=metrics,
            strategy=self.strategy.describe(),
            symbols=sorted(self.prices),
            config=cfg,
            replay=self._replay if self.record_replay else [],
        )

    # ------------------------------------------------------------------ helpers

    def _bound(self, value: Any) -> pd.Timestamp:
        stamp = pd.Timestamp(value)
        sample = next(iter(self.prices.values()))
        if isinstance(sample.index, pd.DatetimeIndex) and sample.index.tz is not None:
            if stamp.tzinfo is None:
                return stamp.tz_localize(sample.index.tz)
            return stamp.tz_convert(sample.index.tz)
        if stamp.tzinfo is not None:
            return stamp.tz_convert(None)
        return stamp

    @staticmethod
    def _position_snapshot(position: Position) -> dict[str, Any]:
        last = position.last_price or position.entry_price
        market_value = last * position.quantity
        pnl = (last - position.entry_price) * position.quantity - position.entry_fee
        return {
            "symbol": position.symbol,
            "quantity": position.quantity,
            "entry_price": position.entry_price,
            "last_price": last,
            "market_value": market_value,
            "pnl": pnl,
            "pnl_pct": pnl / position.cost_basis if position.cost_basis else 0.0,
            "bars_held": position.bars_held,
            "stop_loss": position.stop_price,
            "take_profit": position.target_price,
        }

    def _prepare(self) -> dict[str, _SymbolData]:
        prepared: dict[str, _SymbolData] = {}
        for symbol, frame in self.prices.items():
            if not isinstance(frame.index, pd.DatetimeIndex):
                raise ValueError(f"{symbol}: index must be a DatetimeIndex")
            required = ("open", "high", "low", "close")
            missing = [column for column in required if column not in frame.columns]
            if missing:
                raise ValueError(f"{symbol}: missing columns {', '.join(missing)}")
            frame = frame[~frame.index.duplicated(keep="last")].sort_index()
            if len(frame) < 2:
                raise ValueError(f"{symbol}: need at least two bars")
            signals: Signals = self.strategy.signals(frame)
            if not signals.entry.index.equals(frame.index):
                raise ValueError(f"{symbol}: strategy returned a misaligned signal index")
            prepared[symbol] = _SymbolData(
                frame=frame,
                index=frame.index,
                positions={timestamp: row for row, timestamp in enumerate(frame.index)},
                open=frame["open"].to_numpy(dtype=float),
                high=frame["high"].to_numpy(dtype=float),
                low=frame["low"].to_numpy(dtype=float),
                close=frame["close"].to_numpy(dtype=float),
                entry=signals.entry.to_numpy(dtype=bool),
                exit=signals.exit.to_numpy(dtype=bool),
                score=signals.score.to_numpy(dtype=float),
            )
        return prepared

    def _execute_sells(
        self,
        date: pd.Timestamp,
        available: dict[str, _SymbolData],
        pending_sells: dict[str, str],
        positions: dict[str, Position],
        trades: list[Trade],
        cash: float,
    ) -> float:
        for symbol in sorted(list(pending_sells)):
            if symbol not in available:
                continue
            reason = pending_sells.pop(symbol)
            if symbol not in positions:
                continue
            bar = available[symbol]
            row = bar.positions[date]
            cash = self._close_position(
                positions, trades, symbol, date, float(bar.open[row]), reason, cash
            )
        return cash

    def _execute_buys(
        self,
        date: pd.Timestamp,
        available: dict[str, _SymbolData],
        pending_buys: dict[str, float],
        positions: dict[str, Position],
        last_close: dict[str, float],
        cash: float,
    ) -> float:
        for symbol in sorted(pending_buys, key=lambda name: pending_buys[name], reverse=True):
            if symbol not in available:
                continue
            score = pending_buys.pop(symbol)
            if symbol in positions:
                continue
            if len(positions) >= self.config.max_positions:
                continue
            bar = available[symbol]
            row = bar.positions[date]
            cash = self._open_position(
                positions, symbol, date, float(bar.open[row]), score, cash, last_close
            )
        return cash

    def _open_position(
        self,
        positions: dict[str, Position],
        symbol: str,
        date: pd.Timestamp,
        raw_price: float,
        score: float,
        cash: float,
        last_close: dict[str, float],
    ) -> float:
        cfg = self.config
        fill_price = raw_price * (1.0 + cfg.slippage_rate)
        if not math.isfinite(fill_price) or fill_price <= 0:
            return cash

        equity = cash + sum(
            position.quantity * last_close.get(name, position.entry_price)
            for name, position in positions.items()
        )
        allocation = equity * cfg.position_fraction
        affordable = int(cash // (fill_price * (1.0 + cfg.fee_rate)))
        quantity = min(int(allocation // fill_price), affordable)
        if quantity <= 0:
            return cash

        notional = quantity * fill_price
        fee = notional * cfg.fee_rate
        if notional + fee > cash + 1e-9:
            return cash
        cash -= notional + fee
        stop_price = fill_price * (1.0 - cfg.stop_loss_pct) if cfg.stop_loss_pct > 0 else None
        target_price = fill_price * (1.0 + cfg.take_profit_pct) if cfg.take_profit_pct > 0 else None
        positions[symbol] = Position(
            symbol=symbol,
            quantity=quantity,
            entry_price=fill_price,
            entry_date=date,
            entry_fee=fee,
            stop_price=stop_price,
            target_price=target_price,
            last_price=fill_price,
        )
        if self.record_replay:
            self._events.append(
                {
                    "type": "buy",
                    "at": date.isoformat(),
                    "symbol": symbol,
                    "quantity": quantity,
                    "price": fill_price,
                    "fee": fee,
                    "score": score,
                    "reason": "signal",
                }
            )
        return cash

    def _close_position(
        self,
        positions: dict[str, Position],
        trades: list[Trade],
        symbol: str,
        date: pd.Timestamp,
        raw_price: float,
        reason: str,
        cash: float,
    ) -> float:
        position = positions.pop(symbol)
        fill_price = raw_price * (1.0 - self.config.slippage_rate)
        if not math.isfinite(fill_price) or fill_price <= 0:
            fill_price = raw_price
        notional = position.quantity * fill_price
        fee = notional * self.config.fee_rate
        total_fees = position.entry_fee + fee
        pnl = (fill_price - position.entry_price) * position.quantity - total_fees
        return_pct = pnl / position.cost_basis if position.cost_basis else 0.0
        trades.append(
            Trade(
                symbol=symbol,
                entry_date=position.entry_date,
                exit_date=date,
                quantity=position.quantity,
                entry_price=position.entry_price,
                exit_price=fill_price,
                fees=total_fees,
                pnl=pnl,
                return_pct=return_pct,
                reason=reason,
                bars_held=position.bars_held,
            )
        )
        if self.record_replay:
            self._events.append(
                {
                    "type": "sell",
                    "at": date.isoformat(),
                    "symbol": symbol,
                    "quantity": position.quantity,
                    "price": fill_price,
                    "fee": fee,
                    "pnl": pnl,
                    "pnl_pct": return_pct,
                    "reason": reason,
                    "bars_held": position.bars_held,
                }
            )
        return cash + notional - fee
