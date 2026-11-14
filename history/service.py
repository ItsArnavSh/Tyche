"""History-mode orchestration: fetch, cache, window, and replay.

This module deliberately has no web-framework dependency.  :class:`HistoryApp`
contains the request-level behavior and is exercised by unit tests; the small
``server`` module only translates HTTP requests into these methods.
"""

from __future__ import annotations

import hashlib
import inspect
import math
import os
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import asdict, dataclass, field
from typing import Any, Callable, Sequence

import numpy as np
import pandas as pd

from backtest.data import DataError, fetch_yahoo_range, normalize_symbol
from backtest.engine import Backtest, BacktestConfig
from backtest.strategies import STRATEGIES, build_strategy

from .cache import FrameCache, build_cache

TZ = "Asia/Kolkata"
INTERVAL_ALIASES = {
    "1m": "1m",
    "2m": "2m",
    "5m": "5m",
    "15m": "15m",
    "30m": "30m",
    "60m": "60m",
    "1h": "60m",
    "1d": "1d",
}
BARS_PER_SESSION = {
    "1m": 375,
    "2m": 188,
    "5m": 75,
    "15m": 25,
    "30m": 13,
    "60m": 7,
    "1d": 1,
}
DEFAULT_SYMBOLS = (
    "RELIANCE.NS,TCS.NS,HDFCBANK.NS,INFY.NS,ICICIBANK.NS"
)


@dataclass
class CacheStats:
    backend: str
    ttl_seconds: int
    hits: int = 0
    misses: int = 0
    stored: int = 0
    errors: list[str] = field(default_factory=list)
    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False)

    def hit(self) -> None:
        with self._lock:
            self.hits += 1

    def miss(self) -> None:
        with self._lock:
            self.misses += 1

    def store(self) -> None:
        with self._lock:
            self.stored += 1

    def error(self, message: str) -> None:
        with self._lock:
            if len(self.errors) < 8:
                self.errors.append(message)

    def to_dict(self) -> dict[str, Any]:
        with self._lock:
            return {
                "backend": self.backend,
                "ttl_seconds": self.ttl_seconds,
                "hits": self.hits,
                "misses": self.misses,
                "stored": self.stored,
                "errors": list(self.errors),
            }


@dataclass
class DayDataset:
    date: str
    interval: str
    source: str
    frames: dict[str, pd.DataFrame]
    start: pd.Timestamp
    end: pd.Timestamp
    warmup_start: pd.Timestamp
    cache: CacheStats
    failures: dict[str, str] = field(default_factory=dict)

    @property
    def id(self) -> str:
        raw = "|".join(
            [
                self.date,
                self.interval,
                self.source,
                self.warmup_start.isoformat(),
                ",".join(sorted(self.frames)),
            ]
        )
        return hashlib.sha1(raw.encode("utf-8")).hexdigest()[:12]

    def summary(self) -> dict[str, Any]:
        target_counts: dict[str, int] = {}
        warmup_counts: dict[str, int] = {}
        for symbol, frame in self.frames.items():
            target = frame[(frame.index >= self.start) & (frame.index < self.end)]
            warmup = frame[frame.index < self.start]
            target_counts[symbol] = int(len(target))
            warmup_counts[symbol] = int(len(warmup))
        return {
            "id": self.id,
            "date": self.date,
            "interval": self.interval,
            "source": self.source,
            "start": self.start.isoformat(),
            "end": self.end.isoformat(),
            "warmup_start": self.warmup_start.isoformat(),
            "symbols": sorted(self.frames),
            "bars": {symbol: int(len(frame)) for symbol, frame in self.frames.items()},
            "target_bars": target_counts,
            "warmup_bars": warmup_counts,
            "failures": dict(self.failures),
            "cache": self.cache.to_dict(),
        }


def parse_date(value: Any) -> pd.Timestamp:
    stamp = pd.Timestamp(value)
    if stamp.tzinfo is None:
        stamp = stamp.tz_localize(TZ)
    else:
        stamp = stamp.tz_convert(TZ)
    return stamp.normalize()


def normalize_interval(value: Any) -> str:
    key = str(value or "5m").strip().lower()
    if key not in INTERVAL_ALIASES:
        raise ValueError(
            f"unsupported interval {value!r}; choose one of "
            f"{', '.join(sorted(set(INTERVAL_ALIASES.values())))}"
        )
    return INTERVAL_ALIASES[key]


def periods_per_year(interval: str) -> int:
    return 252 * max(1, BARS_PER_SESSION.get(interval, 75))


def normalize_symbols(value: Any, *, max_symbols: int = 500) -> list[str]:
    if isinstance(value, str):
        raw = value.replace("\n", ",").split(",")
    elif isinstance(value, Sequence):
        raw = [str(item) for item in value]
    else:
        raw = []
    symbols: list[str] = []
    for item in raw:
        symbol = normalize_symbol(item)
        if symbol and symbol not in symbols:
            symbols.append(symbol)
    if not symbols:
        raise ValueError("provide at least one symbol")
    if len(symbols) > max_symbols:
        raise ValueError(f"too many symbols ({len(symbols)}); maximum is {max_symbols}")
    return symbols


def cache_key(
    *,
    source: str,
    interval: str,
    start: pd.Timestamp,
    end: pd.Timestamp,
    symbol: str,
) -> str:
    return (
        f"{source}:{interval}:{start.strftime('%Y%m%dT%H%M')}:"
        f"{end.strftime('%Y%m%dT%H%M')}:{symbol}"
    )


def _to_int(value: Any, default: int, *, low: int, high: int) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        parsed = default
    return max(low, min(high, parsed))


def _to_float(value: Any, default: float) -> float:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return default
    return parsed


def _to_bool(value: Any, default: bool) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        return value.strip().lower() in {"1", "true", "yes", "on"}
    return bool(value)


def _parse_params(value: Any) -> dict[str, Any]:
    if value is None:
        return {}
    if isinstance(value, dict):
        return dict(value)
    if isinstance(value, str):
        params: dict[str, Any] = {}
        for item in value.split(","):
            if not item.strip():
                continue
            if "=" not in item:
                raise ValueError(f"strategy params must be key=value, got {item!r}")
            key, raw = item.split("=", 1)
            key = key.strip()
            raw = raw.strip()
            if not key:
                raise ValueError("strategy parameter name must not be empty")
            if raw.lower() in {"true", "false"}:
                params[key] = raw.lower() == "true"
            else:
                try:
                    params[key] = int(raw)
                except ValueError:
                    try:
                        params[key] = float(raw)
                    except ValueError:
                        params[key] = raw
        return params
    raise ValueError("params must be an object or a comma-separated key=value string")


def _json_safe(value: Any) -> Any:
    if isinstance(value, dict):
        return {str(key): _json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_safe(item) for item in value]
    if isinstance(value, pd.Timestamp):
        return value.isoformat()
    if isinstance(value, np.generic):
        value = value.item()
    if isinstance(value, float):
        return value if math.isfinite(value) else None
    return value


def _cache_stats(cache: FrameCache) -> CacheStats:
    return CacheStats(
        backend=cache.backend,
        ttl_seconds=int(getattr(cache, "ttl_seconds", 0)),
    )


def fetch_day(
    *,
    date: Any,
    symbols: Any,
    interval: Any = "5m",
    source: str = "yahoo",
    warmup_days: int = 10,
    cache: FrameCache,
    fetcher: Callable[..., pd.DataFrame] | None = None,
    max_workers: int = 8,
    timeout: float = 15.0,
    retries: int = 3,
) -> DayDataset:
    """Fetch (or load from cache) one session plus an indicator warmup window."""
    source = str(source or "yahoo").strip().lower()
    if source != "yahoo":
        raise ValueError("history mode currently supports source=yahoo only")
    interval = normalize_interval(interval)
    session = parse_date(date)
    target_start = session
    target_end = session + pd.Timedelta(days=1)
    warmup_days = _to_int(warmup_days, 10, low=0, high=365)
    warmup_start = target_start - pd.Timedelta(days=warmup_days)
    symbol_list = normalize_symbols(symbols)
    fetcher = fetcher or fetch_yahoo_range
    stats = _cache_stats(cache)
    frames: dict[str, pd.DataFrame] = {}
    failures: dict[str, str] = {}

    def trim(frame: pd.DataFrame) -> tuple[pd.DataFrame, bool]:
        frame = frame.sort_index()
        frame = frame[(frame.index >= warmup_start) & (frame.index < target_end)]
        target = frame[(frame.index >= target_start) & (frame.index < target_end)]
        return frame, not target.empty

    def load(symbol: str) -> tuple[str, pd.DataFrame | None, str | None]:
        key = cache_key(
            source=source,
            interval=interval,
            start=warmup_start,
            end=target_end,
            symbol=symbol,
        )
        try:
            cached = cache.get_frame(key)
        except Exception as exc:
            stats.error(f"{symbol}: cache read failed: {exc}")
            cached = None
        if cached is not None and isinstance(cached.index, pd.DatetimeIndex):
            frame, usable = trim(cached)
            if usable:
                stats.hit()
                return symbol, frame, None
        stats.miss()
        try:
            frame = fetcher(
                symbol,
                start=warmup_start,
                end=target_end,
                interval=interval,
                timeout=timeout,
                retries=retries,
            )
        except Exception as exc:
            return symbol, None, str(exc)
        if (
            not isinstance(frame, pd.DataFrame)
            or frame.empty
            or not isinstance(frame.index, pd.DatetimeIndex)
        ):
            return symbol, None, "no candles returned"
        frame, usable = trim(frame)
        if not usable:
            return symbol, None, "no candles for the requested session"
        try:
            if cache.put_frame(key, frame):
                stats.store()
        except Exception as exc:
            stats.error(f"{symbol}: cache write failed: {exc}")
        return symbol, frame, None

    workers = _to_int(max_workers, 8, low=1, high=16)
    with ThreadPoolExecutor(max_workers=min(workers, len(symbol_list))) as pool:
        futures = {pool.submit(load, symbol): symbol for symbol in symbol_list}
        for future in as_completed(futures):
            symbol, frame, error = future.result()
            if frame is not None:
                frames[symbol] = frame
            else:
                failures[symbol] = error or "unknown error"

    if not frames:
        detail = "; ".join(f"{symbol}: {reason}" for symbol, reason in failures.items())
        raise DataError(f"no history data available for {session.date()} ({detail})")

    ordered = {symbol: frames[symbol] for symbol in symbol_list if symbol in frames}
    target_total = sum(
        len(frame[(frame.index >= target_start) & (frame.index < target_end)])
        for frame in ordered.values()
    )
    if target_total < 2:
        raise DataError(
            f"only {target_total} session bar(s) available for {session.date()}; "
            "choose an intraday interval such as 5m"
        )
    return DayDataset(
        date=session.strftime("%Y-%m-%d"),
        interval=interval,
        source=source,
        frames=ordered,
        start=target_start,
        end=target_end,
        warmup_start=warmup_start,
        cache=stats,
        failures=failures,
    )


def build_config(payload: dict[str, Any], interval: str) -> BacktestConfig:
    periods = payload.get("periods_per_year")
    return BacktestConfig(
        initial_cash=_to_float(payload.get("cash"), 100_000.0),
        max_positions=_to_int(payload.get("max_positions"), 10, low=1, high=100),
        position_fraction=_to_float(payload.get("position_fraction"), 0.15),
        fee_bps=_to_float(payload.get("fee_bps"), 5.0),
        slippage_bps=_to_float(payload.get("slippage_bps"), 5.0),
        stop_loss_pct=_to_float(
            payload.get("stop_loss_pct", payload.get("stop_loss")), 0.05
        ),
        take_profit_pct=_to_float(
            payload.get("take_profit_pct", payload.get("take_profit")), 0.10
        ),
        max_hold_bars=_to_int(payload.get("max_hold_bars"), 0, low=0, high=100_000),
        close_at_end=_to_bool(payload.get("close_at_end"), True),
        periods_per_year=_to_int(periods, periods_per_year(interval), low=1, high=10_000_000),
    )


def run_replay(dataset: DayDataset, payload: dict[str, Any]) -> dict[str, Any]:
    strategy_name = str(payload.get("strategy") or "sma").strip().lower()
    params = _parse_params(payload.get("params"))
    config = build_config(payload, dataset.interval)
    strategy = build_strategy(strategy_name, params)
    engine = Backtest(
        dataset.frames,
        strategy,
        config,
        start=dataset.start,
        end=dataset.end,
        record_replay=True,
    )
    result = engine.run()
    return {
        "dataset": dataset.summary(),
        "strategy": result.strategy,
        "config": _json_safe(asdict(result.config)),
        "metrics": _json_safe(result.metrics),
        "bars": int(len(result.equity)),
        "first_bar": result.equity.index[0].isoformat() if len(result.equity) else None,
        "last_bar": result.equity.index[-1].isoformat() if len(result.equity) else None,
        "trades": _json_safe([trade.to_dict() for trade in result.trades]),
        "replay": _json_safe(result.replay),
    }


def strategy_catalog() -> list[dict[str, Any]]:
    catalog: list[dict[str, Any]] = []
    seen: set[type] = set()
    for name in sorted(STRATEGIES):
        cls = STRATEGIES[name]
        if cls in seen:
            continue
        seen.add(cls)
        signature = inspect.signature(cls.__init__)
        params = {
            key: {
                "default": parameter.default
                if parameter.default is not inspect.Parameter.empty
                else None,
                "type": type(parameter.default).__name__
                if parameter.default is not inspect.Parameter.empty
                else "string",
            }
            for key, parameter in signature.parameters.items()
            if key != "self"
        }
        catalog.append(
            {
                "name": name,
                "aliases": sorted(
                    alias for alias, candidate in STRATEGIES.items() if candidate is cls
                ),
                "params": params,
            }
        )
    return catalog


class HistoryApp:
    """Request-level API used by both the HTTP server and unit tests."""

    def __init__(
        self,
        cache: FrameCache | None = None,
        *,
        fetcher: Callable[..., pd.DataFrame] | None = None,
        max_workers: int = 8,
        timeout: float = 15.0,
        retries: int = 3,
    ) -> None:
        self.cache = cache or build_cache(
            backend=os.getenv("HISTORY_CACHE_BACKEND", "redis"),
            redis_url=os.getenv("REDIS_URL", "redis://localhost:6379/1"),
            ttl_seconds=_to_int(
                os.getenv("HISTORY_CACHE_TTL"), 7 * 24 * 3600, low=0, high=10**9
            ),
        )
        self.fetcher = fetcher
        self.max_workers = max_workers
        self.timeout = timeout
        self.retries = retries

    def health(self) -> dict[str, Any]:
        return {
            "status": "ok",
            "service": "tyche-history",
            "cache": self.cache.health(),
        }

    def strategies(self) -> dict[str, Any]:
        return {"strategies": strategy_catalog()}

    def _common(self, payload: dict[str, Any]) -> tuple[DayDataset, dict[str, Any]]:
        date = payload.get("date")
        if date in (None, ""):
            raise ValueError("date is required (YYYY-MM-DD)")
        symbols = normalize_symbols(payload.get("symbols", DEFAULT_SYMBOLS))
        warmup_days = _to_int(payload.get("warmup_days"), 10, low=0, high=365)
        dataset = fetch_day(
            date=date,
            symbols=symbols,
            interval=payload.get("interval", "5m"),
            source=payload.get("source", "yahoo"),
            warmup_days=warmup_days,
            cache=self.cache,
            fetcher=self.fetcher,
            max_workers=_to_int(payload.get("max_workers"), self.max_workers, low=1, high=16),
            timeout=_to_float(payload.get("timeout"), self.timeout),
            retries=_to_int(payload.get("retries"), self.retries, low=1, high=10),
        )
        return dataset, payload

    def fetch(self, payload: dict[str, Any]) -> dict[str, Any]:
        dataset, _ = self._common(payload)
        return {"dataset": dataset.summary()}

    def replay(self, payload: dict[str, Any]) -> dict[str, Any]:
        dataset, body = self._common(payload)
        return run_replay(dataset, body)

    def clear_cache(self) -> dict[str, Any]:
        return {"cleared": self.cache.clear(), "cache": self.cache.health()}
