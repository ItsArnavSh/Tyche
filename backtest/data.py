"""Market-data loaders for the backtester.

Yahoo Finance's public chart endpoint is the default source.  ``yfinance`` is
supported as an optional convenience, and plain CSV files are supported for
fully offline/reproducible research.
"""

from __future__ import annotations

import logging
import re
import time
from pathlib import Path
from typing import Any

import pandas as pd
import requests

logger = logging.getLogger(__name__)

YAHOO_CHART_URL = "https://query1.finance.yahoo.com/v8/finance/chart/{symbol}"
DEFAULT_SUFFIX = ".NS"
DEFAULT_HEADERS = {
    "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) Tyche-Backtest/1.0",
    "Accept": "application/json",
}
REQUIRED_COLUMNS = ("open", "high", "low", "close")


class DataError(RuntimeError):
    """Raised when market data cannot be downloaded or parsed."""


def normalize_symbol(symbol: str, suffix: str = DEFAULT_SUFFIX) -> str:
    """Turn ``RELIANCE`` into ``RELIANCE.NS`` while preserving explicit symbols."""
    value = (symbol or "").strip()
    if not value:
        return ""
    if ":" in value:
        value = value.split(":", 1)[1]
    if not suffix or value.startswith("^") or "." in value:
        return value
    return value + suffix


def _get_json(
    url: str,
    *,
    timeout: float = 15.0,
    retries: int = 3,
    session: requests.Session | None = None,
    sleep=None,
) -> dict[str, Any]:
    getter = session.get if session is not None else requests.get
    if sleep is None:
        sleep = time.sleep
    last_error: Exception | None = None
    for attempt in range(max(1, retries)):
        try:
            response = getter(url, headers=DEFAULT_HEADERS, timeout=timeout)
        except requests.RequestException as exc:
            last_error = exc
        else:
            status = response.status_code
            if 200 <= status < 300:
                try:
                    return response.json()
                except ValueError as exc:
                    raise DataError(f"invalid JSON from {url}: {exc}") from exc
            body = (getattr(response, "text", "") or "")[:200]
            if status == 429 or status >= 500:
                last_error = DataError(f"HTTP {status} from {url}: {body}")
            else:
                raise DataError(f"HTTP {status} from {url}: {body}")
        if attempt + 1 < max(1, retries):
            sleep(0.5 * (2**attempt))
    raise DataError(f"request failed after retries: {last_error}")


def parse_yahoo_chart(payload: dict[str, Any], symbol: str) -> pd.DataFrame:
    chart = (payload or {}).get("chart") or {}
    error = chart.get("error")
    if error:
        description = error.get("description") if isinstance(error, dict) else str(error)
        raise DataError(f"Yahoo error for {symbol}: {description}")
    results = chart.get("result") or []
    if not results:
        raise DataError(f"Yahoo returned no chart result for {symbol}")

    result = results[0]
    timestamps = result.get("timestamp") or []
    quotes = ((result.get("indicators") or {}).get("quote") or [])
    if not timestamps or not quotes:
        raise DataError(f"Yahoo returned no candles for {symbol}")
    quote = quotes[0]

    length = len(timestamps)
    def column(name: str) -> list[Any]:
        values = quote.get(name) or []
        return list(values[:length]) + [None] * max(0, length - len(values))

    frame = pd.DataFrame(
        {
            "open": column("open"),
            "high": column("high"),
            "low": column("low"),
            "close": column("close"),
            "volume": column("volume"),
        },
        index=pd.to_datetime(timestamps, unit="s", utc=True),
    )
    frame.index = frame.index.tz_convert("Asia/Kolkata")
    frame.index.name = "timestamp"

    for name in ("open", "high", "low", "close", "volume"):
        frame[name] = pd.to_numeric(frame[name], errors="coerce")
    frame = frame[~frame.index.duplicated(keep="last")].sort_index()
    frame = frame[frame["close"].notna() & (frame["close"] > 0)].copy()
    if frame.empty:
        raise DataError(f"Yahoo returned only invalid candles for {symbol}")

    frame["open"] = frame["open"].fillna(frame["close"])
    frame["high"] = frame["high"].fillna(frame[["open", "close"]].max(axis=1))
    frame["low"] = frame["low"].fillna(frame[["open", "close"]].min(axis=1))
    frame["volume"] = frame["volume"].fillna(0.0).clip(lower=0.0)
    # Keep OHLC internally consistent even if the upstream arrays disagree.
    frame["high"] = frame[["open", "close", "high"]].max(axis=1)
    frame["low"] = frame[["open", "close", "low"]].min(axis=1)
    return frame[["open", "high", "low", "close", "volume"]]


def fetch_yahoo(
    symbol: str,
    *,
    period: str = "2y",
    interval: str = "1d",
    suffix: str = DEFAULT_SUFFIX,
    timeout: float = 15.0,
    retries: int = 3,
    base_url: str = "https://query1.finance.yahoo.com",
    session: requests.Session | None = None,
) -> pd.DataFrame:
    """Download real daily/intraday candles from Yahoo Finance."""
    yahoo_symbol = normalize_symbol(symbol, suffix)
    if not yahoo_symbol:
        raise DataError("symbol must not be empty")
    url = (
        f"{base_url.rstrip('/')}/v8/finance/chart/{requests.utils.quote(yahoo_symbol, safe='')}"
        f"?interval={requests.utils.quote(interval)}&range={requests.utils.quote(period)}"
        "&includePrePost=false&events=div%2Csplits"
    )
    payload = _get_json(url, timeout=timeout, retries=retries, session=session)
    frame = parse_yahoo_chart(payload, yahoo_symbol)
    if frame.empty:
        raise DataError(f"empty price history for {yahoo_symbol}")
    return frame


def fetch_yfinance(
    symbol: str,
    *,
    period: str = "2y",
    interval: str = "1d",
    suffix: str = DEFAULT_SUFFIX,
) -> pd.DataFrame:
    """Optional yfinance loader (install ``yfinance`` to use it)."""
    try:
        import yfinance as yf  # type: ignore
    except ImportError as exc:  # pragma: no cover - depends on environment
        raise DataError("yfinance is not installed; use --source yahoo or install yfinance") from exc

    yahoo_symbol = normalize_symbol(symbol, suffix)
    frame = yf.download(
        yahoo_symbol,
        period=period,
        interval=interval,
        auto_adjust=False,
        progress=False,
        threads=False,
    )
    if frame is None or frame.empty:
        raise DataError(f"yfinance returned no data for {yahoo_symbol}")
    if isinstance(frame.columns, pd.MultiIndex):
        frame.columns = frame.columns.get_level_values(0)
    frame.columns = [str(column).strip().lower().replace(" ", "_") for column in frame.columns]
    frame = frame.rename(columns={"adj_close": "adj_close"})
    missing = [column for column in REQUIRED_COLUMNS if column not in frame.columns]
    if missing:
        raise DataError(f"yfinance data for {yahoo_symbol} is missing {', '.join(missing)}")
    if not isinstance(frame.index, pd.DatetimeIndex):
        frame.index = pd.to_datetime(frame.index, errors="coerce", utc=True)
    if frame.index.tz is None:
        frame.index = frame.index.tz_localize("Asia/Kolkata")
    else:
        frame.index = frame.index.tz_convert("Asia/Kolkata")
    frame.index.name = "timestamp"
    frame = frame[frame["close"].notna() & (frame["close"] > 0)].sort_index()
    frame["volume"] = frame.get("volume", 0).fillna(0.0)
    return frame[list(REQUIRED_COLUMNS) + ["volume"]]


def load_csv(path: str | Path) -> pd.DataFrame:
    """Load OHLCV candles from a CSV file with tolerant column names."""
    frame = pd.read_csv(path)
    if frame.empty:
        raise DataError(f"CSV file is empty: {path}")
    frame.columns = [str(column).strip().lower().replace(" ", "_") for column in frame.columns]
    date_column = next(
        (candidate for candidate in ("timestamp", "date", "datetime", "time") if candidate in frame.columns),
        None,
    )
    if date_column is None:
        raise DataError(f"CSV {path} has no timestamp/date column")
    frame[date_column] = pd.to_datetime(frame[date_column], errors="coerce", utc=True)
    frame = frame.dropna(subset=[date_column]).set_index(date_column)
    frame.index = frame.index.tz_convert("Asia/Kolkata")
    frame.index.name = "timestamp"

    missing = [column for column in REQUIRED_COLUMNS if column not in frame.columns]
    if missing:
        raise DataError(f"CSV {path} is missing columns: {', '.join(missing)}")
    for name in (*REQUIRED_COLUMNS, "volume"):
        if name in frame.columns:
            frame[name] = pd.to_numeric(frame[name], errors="coerce")
    frame = frame[frame["close"].notna() & (frame["close"] > 0)].sort_index()
    if "volume" not in frame.columns:
        frame["volume"] = 0.0
    frame["volume"] = frame["volume"].fillna(0.0)
    return frame[list(REQUIRED_COLUMNS) + ["volume"]]


def _cache_path(cache_dir: Path, symbol: str, period: str, interval: str, source: str) -> Path:
    safe = re.sub(r"[^A-Za-z0-9_.-]+", "_", symbol)
    return cache_dir / f"{source}_{safe}_{period}_{interval}.csv"


def _load_cached(path: Path, ttl: float) -> pd.DataFrame | None:
    if not path.exists():
        return None
    if time.time() - path.stat().st_mtime > ttl:
        return None
    try:
        return load_csv(path)
    except (DataError, OSError, ValueError):
        return None


def load_prices(
    symbols: list[str],
    *,
    source: str = "yahoo",
    period: str = "2y",
    interval: str = "1d",
    csv_dir: str | Path | None = None,
    cache_dir: str | Path | None = None,
    cache_ttl: float = 12 * 3600,
    timeout: float = 15.0,
    retries: int = 3,
    suffix: str = DEFAULT_SUFFIX,
) -> dict[str, pd.DataFrame]:
    """Load one DataFrame per symbol.

    Partial failures are logged and skipped.  If no symbol loads successfully a
    :class:`DataError` is raised with the individual failures.
    """
    if not symbols:
        raise DataError("at least one symbol is required")
    source = (source or "yahoo").strip().lower()
    cache_root = Path(cache_dir).expanduser() if cache_dir else None
    if cache_root is not None:
        cache_root.mkdir(parents=True, exist_ok=True)

    frames: dict[str, pd.DataFrame] = {}
    failures: list[str] = []
    for raw_symbol in symbols:
        symbol = (raw_symbol or "").strip()
        if not symbol:
            continue
        cache_path = None
        if cache_root is not None:
            cache_path = _cache_path(cache_root, symbol, period, interval, source)
            cached = _load_cached(cache_path, cache_ttl)
            if cached is not None and not cached.empty:
                frames[symbol] = cached
                logger.info("loaded %s from cache", symbol)
                continue

        try:
            if source == "yahoo":
                frame = fetch_yahoo(
                    symbol,
                    period=period,
                    interval=interval,
                    suffix=suffix,
                    timeout=timeout,
                    retries=retries,
                )
            elif source == "yfinance":
                frame = fetch_yfinance(symbol, period=period, interval=interval, suffix=suffix)
            elif source == "csv":
                if csv_dir is None:
                    raise DataError("--csv-dir is required with --source csv")
                directory = Path(csv_dir).expanduser()
                candidates = [
                    directory / f"{symbol}.csv",
                    directory / f"{normalize_symbol(symbol, suffix)}.csv",
                    directory / f"{symbol.replace('.', '_')}.csv",
                ]
                path = next((candidate for candidate in candidates if candidate.exists()), None)
                if path is None:
                    raise DataError(f"no CSV found for {symbol} in {directory}")
                frame = load_csv(path)
            else:
                raise DataError(f"unknown data source {source!r} (expected yahoo, yfinance or csv)")
        except (DataError, OSError, ValueError) as exc:
            failures.append(f"{symbol}: {exc}")
            logger.warning("failed to load %s: %s", symbol, exc)
            continue

        if frame.empty:
            failures.append(f"{symbol}: empty frame")
            continue
        frames[symbol] = frame
        if cache_path is not None:
            try:
                frame.to_csv(cache_path, index_label="timestamp")
            except OSError as exc:
                logger.warning("could not write cache %s: %s", cache_path, exc)

    if not frames:
        raise DataError("could not load any symbols:\n  " + "\n  ".join(failures or ["no symbols supplied"]))
    for failure in failures:
        logger.warning("skipped symbol: %s", failure)
    return frames
