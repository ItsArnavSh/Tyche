"""Cache backends for History mode.

The Docker Compose stack uses Redis (logical DB 1 by default).  Tests and
standalone development can use the in-process :class:`MemoryFrameCache`.
Frames are stored as compact JSON so a cache entry is portable between the
API process and any future fetcher workers.
"""

from __future__ import annotations

import json
import logging
import threading
import time
from typing import Any, Protocol

import pandas as pd

logger = logging.getLogger(__name__)


class FrameCache(Protocol):
    backend: str
    ttl_seconds: int

    def get_frame(self, key: str) -> pd.DataFrame | None: ...

    def put_frame(self, key: str, frame: pd.DataFrame) -> bool: ...

    def health(self) -> dict[str, Any]: ...

    def clear(self) -> int: ...


def encode_frame(frame: pd.DataFrame) -> str:
    if not isinstance(frame.index, pd.DatetimeIndex):
        raise ValueError("cached frames need a DatetimeIndex")
    payload = {
        "index": [stamp.isoformat() for stamp in frame.index],
        "index_name": frame.index.name,
        "tz": str(frame.index.tz) if frame.index.tz is not None else None,
        "columns": [str(column) for column in frame.columns],
        "data": frame.to_numpy(dtype=float).tolist(),
    }
    return json.dumps(payload, separators=(",", ":"))


def decode_frame(raw: str) -> pd.DataFrame:
    payload = json.loads(raw)
    index = pd.DatetimeIndex(pd.to_datetime(payload["index"]))
    timezone = payload.get("tz")
    if timezone:
        try:
            index = index.tz_convert(timezone)
        except (TypeError, ValueError):
            pass
    if index.tz is None:
        index = index.tz_localize("Asia/Kolkata")
    frame = pd.DataFrame(payload["data"], index=index, columns=payload["columns"])
    frame.index.name = payload.get("index_name", "timestamp")
    return frame.sort_index()


class MemoryFrameCache:
    """Small cache used by unit tests and ``HISTORY_CACHE_BACKEND=memory``."""

    backend = "memory"

    def __init__(self, ttl_seconds: int = 7 * 24 * 3600) -> None:
        self.ttl_seconds = ttl_seconds
        self._values: dict[str, tuple[float, str]] = {}
        self._lock = threading.Lock()

    def get_frame(self, key: str) -> pd.DataFrame | None:
        with self._lock:
            item = self._values.get(key)
            if item is None:
                return None
            expires, raw = item
            if expires and expires < time.time():
                self._values.pop(key, None)
                return None
        return decode_frame(raw)

    def put_frame(self, key: str, frame: pd.DataFrame) -> bool:
        expires = time.time() + self.ttl_seconds if self.ttl_seconds > 0 else 0.0
        encoded = encode_frame(frame)
        with self._lock:
            self._values[key] = (expires, encoded)
        return True

    def health(self) -> dict[str, Any]:
        with self._lock:
            size = len(self._values)
        return {"backend": self.backend, "healthy": True, "keys": size}

    def clear(self) -> int:
        with self._lock:
            count = len(self._values)
            self._values.clear()
        return count


class RedisFrameCache:
    """Redis-backed frame cache.

    ``redis`` is imported lazily so the rest of the history service (and its
    unit tests) work in environments where the optional client is absent.
    """

    backend = "redis"

    def __init__(
        self,
        url: str = "redis://localhost:6379/1",
        *,
        prefix: str = "tyche:history:v1",
        ttl_seconds: int = 7 * 24 * 3600,
        client: Any | None = None,
    ) -> None:
        self.url = url
        self.prefix = prefix.rstrip(":")
        self.ttl_seconds = ttl_seconds
        if client is None:
            try:
                import redis  # type: ignore[import-not-found]
            except ImportError as exc:  # pragma: no cover - Docker installs it
                raise RuntimeError(
                    "redis is not installed; install history/requirements.txt "
                    "or set HISTORY_CACHE_BACKEND=memory"
                ) from exc
            client = redis.Redis.from_url(
                url,
                decode_responses=True,
                socket_connect_timeout=2,
                socket_timeout=3,
            )
        self._client = client

    def _key(self, key: str) -> str:
        return f"{self.prefix}:{key}"

    def get_frame(self, key: str) -> pd.DataFrame | None:
        raw = self._client.get(self._key(key))
        if raw is None:
            return None
        return decode_frame(raw)

    def put_frame(self, key: str, frame: pd.DataFrame) -> bool:
        raw = encode_frame(frame)
        return bool(self._client.set(self._key(key), raw, ex=self.ttl_seconds or None))

    def health(self) -> dict[str, Any]:
        try:
            self._client.ping()
            keys = 0
            scan = getattr(self._client, "scan_iter", None)
            if scan is not None:
                keys = sum(1 for _ in scan(match=f"{self.prefix}:*"))
            return {"backend": self.backend, "healthy": True, "keys": keys}
        except Exception as exc:  # Redis is optional at runtime
            return {"backend": self.backend, "healthy": False, "error": str(exc)}

    def clear(self) -> int:
        scan = getattr(self._client, "scan_iter", None)
        if scan is None:
            return 0
        keys = list(scan(match=f"{self.prefix}:*"))
        if not keys:
            return 0
        return int(self._client.delete(*keys))


def build_cache(
    *,
    backend: str | None = None,
    redis_url: str | None = None,
    ttl_seconds: int = 7 * 24 * 3600,
) -> FrameCache:
    selected = (backend or "redis").strip().lower()
    if selected == "memory":
        return MemoryFrameCache(ttl_seconds=ttl_seconds)
    try:
        cache = RedisFrameCache(
            redis_url or "redis://localhost:6379/1",
            ttl_seconds=ttl_seconds,
        )
        if not cache.health().get("healthy"):
            raise RuntimeError("Redis ping failed")
        return cache
    except Exception as exc:
        # Local development may not have the redis client installed, and a
        # standalone run may not have Redis listening.  Docker Compose uses the
        # real Redis backend; degrade to an in-process cache rather than making
        # the History page unusable.
        logger.warning("Redis history cache unavailable (%s); using memory cache", exc)
        return MemoryFrameCache(ttl_seconds=ttl_seconds)
