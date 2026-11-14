from __future__ import annotations

import unittest

import pandas as pd

from history.cache import MemoryFrameCache, RedisFrameCache, decode_frame, encode_frame


def frame() -> pd.DataFrame:
    index = pd.date_range("2024-01-02 09:15", periods=3, freq="5min", tz="Asia/Kolkata")
    index.name = "timestamp"
    return pd.DataFrame(
        {
            "open": [100.0, 101.0, 102.0],
            "high": [101.0, 102.0, 103.0],
            "low": [99.0, 100.0, 101.0],
            "close": [100.5, 101.5, 102.5],
            "volume": [1000, 1100, 1200],
        },
        index=index,
    )


class FakeRedis:
    def __init__(self) -> None:
        self.values: dict[str, str] = {}

    def get(self, key):
        return self.values.get(key)

    def set(self, key, value, ex=None):
        self.values[key] = value
        return True

    def ping(self):
        return True

    def scan_iter(self, match=None):
        prefix = (match or "").rstrip("*")
        return iter(sorted(key for key in self.values if key.startswith(prefix)))

    def delete(self, *keys):
        for key in keys:
            self.values.pop(key, None)
        return len(keys)


class CacheTests(unittest.TestCase):
    def test_frame_round_trip_preserves_timezone_and_values(self):
        original = frame()
        decoded = decode_frame(encode_frame(original))
        self.assertEqual(str(decoded.index.tz), "Asia/Kolkata")
        self.assertEqual(decoded.index.name, "timestamp")
        pd.testing.assert_frame_equal(decoded, original, check_freq=False, check_dtype=False)

    def test_memory_cache_hit_and_miss(self):
        cache = MemoryFrameCache()
        self.assertIsNone(cache.get_frame("missing"))
        cache.put_frame("day", frame())
        cached = cache.get_frame("day")
        self.assertIsNotNone(cached)
        pd.testing.assert_frame_equal(cached, frame(), check_freq=False, check_dtype=False)
        self.assertEqual(cache.health()["backend"], "memory")

    def test_redis_cache_uses_prefix_and_ttl(self):
        redis = FakeRedis()
        cache = RedisFrameCache(client=redis, prefix="tyche:history:v1", ttl_seconds=60)
        cache.put_frame("abc", frame())
        self.assertIn("tyche:history:v1:abc", redis.values)
        cached = cache.get_frame("abc")
        pd.testing.assert_frame_equal(cached, frame(), check_freq=False, check_dtype=False)
        self.assertTrue(cache.health()["healthy"])
        self.assertEqual(cache.health()["keys"], 1)
        self.assertEqual(cache.clear(), 1)
        self.assertIsNone(cache.get_frame("abc"))

    def test_redis_health_reports_failure(self):
        class BadRedis(FakeRedis):
            def ping(self):
                raise ConnectionError("offline")

        cache = RedisFrameCache(client=BadRedis())
        self.assertFalse(cache.health()["healthy"])
        self.assertIn("offline", cache.health()["error"])


if __name__ == "__main__":
    unittest.main()
