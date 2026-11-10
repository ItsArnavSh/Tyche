package org.Tyche.src.internal.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;
import org.junit.jupiter.api.Test;

class CacheTest {

    private static final PriorityBlock TCS_MIN1 = new PriorityBlock(CandleSize.min1, "TCS");
    private static final PriorityBlock TCS_MIN15 = new PriorityBlock(CandleSize.min15, "TCS");
    private static final PriorityBlock INFY_MIN1 = new PriorityBlock(CandleSize.min1, "INFY");

    private static Candle candle(double close, long volume) {
        return new Candle(close, close + 1, close - 1, close, volume);
    }

    @Test
    void newCandlesNormalisesChronologicalInputToNewestFirst() {
        Cache cache = new Cache();
        Candle c1 = candle(100, 1);
        Candle c2 = candle(101, 2);
        Candle c3 = candle(102, 3);

        cache.new_candles(TCS_MIN1, List.of(c1, c2, c3));

        ArrayDeque<Candle> newestFirst = cache.get_candles(TCS_MIN1);
        assertEquals(3, newestFirst.size());
        assertEquals(c3, newestFirst.peekFirst());
        assertEquals(c1, newestFirst.peekLast());
        assertEquals(List.of(c1, c2, c3), cache.get_candles_chronological(TCS_MIN1));
    }

    @Test
    void pushCandlePrependsAndTrimsOldest() {
        Cache cache = new Cache(3);
        cache.new_candles(TCS_MIN1, List.of(candle(1, 1), candle(2, 2), candle(3, 3)));

        cache.push_candle(TCS_MIN1, candle(4, 4));
        cache.push_candle(TCS_MIN1, candle(5, 5));

        assertEquals(3, cache.candle_count(TCS_MIN1));
        ArrayDeque<Candle> candles = cache.get_candles(TCS_MIN1);
        assertEquals(5, candles.peekFirst().close, 1e-9);
        assertEquals(3, candles.peekLast().close, 1e-9);
    }

    @Test
    void snapshotsAreIndependentCopies() {
        Cache cache = new Cache();
        cache.push_candle(TCS_MIN1, candle(100, 1));

        ArrayDeque<Candle> snapshot = cache.get_candles(TCS_MIN1);
        snapshot.clear();

        assertEquals(1, cache.candle_count(TCS_MIN1));
        assertNotSame(snapshot, cache.get_candles(TCS_MIN1));
    }

    @Test
    void rejectsInvalidConfigurationAndIgnoresNullInputs() {
        assertThrows(IllegalArgumentException.class, () -> new Cache(1));

        Cache cache = new Cache();
        cache.push_candle(null, candle(1, 1));
        cache.push_candle(TCS_MIN1, null);
        cache.new_candles(TCS_MIN1, List.of());
        cache.new_candles(null, List.of(candle(1, 1)));

        assertEquals(0, cache.candle_count(TCS_MIN1));
        assertTrue(cache.get_candles(TCS_MIN1).isEmpty());
        assertEquals(0, cache.candle_count(INFY_MIN1));
    }

    @Test
    void variablesAreNamespacedByTickerAndSize() {
        Cache cache = new Cache();

        cache.set_var(TCS_MIN1, "rsi", 42);
        cache.set_var(TCS_MIN15, "rsi", 84);
        cache.set_var(INFY_MIN1, "rsi", 21);

        assertEquals(42, cache.get_var(TCS_MIN1, "rsi", -1), 1e-9);
        assertEquals(84, cache.get_var(TCS_MIN15, "rsi", -1), 1e-9);
        assertEquals(21, cache.get_var(INFY_MIN1, "rsi", -1), 1e-9);
        assertEquals(-1, cache.get_var(TCS_MIN1, "missing", -1), 1e-9);
        assertTrue(cache.has_var(TCS_MIN1, "rsi"));
        assertFalse(cache.has_var(TCS_MIN1, "missing"));

        cache.remove_var(TCS_MIN1, "rsi");
        assertFalse(cache.has_var(TCS_MIN1, "rsi"));
    }

    @Test
    void nonFiniteVariablesAreIgnored() {
        Cache cache = new Cache();
        cache.set_var(TCS_MIN1, "x", 7);

        cache.set_var(TCS_MIN1, "x", Double.NaN);
        cache.set_var(TCS_MIN1, "x", Double.POSITIVE_INFINITY);
        cache.set_var(TCS_MIN1, null, 1);

        assertEquals(7, cache.get_var(TCS_MIN1, "x", -1), 1e-9);
    }

    @Test
    void legacyGlobalVariablesStillWork() {
        Cache cache = new Cache();
        cache.set_var("market", 3);
        assertEquals(3, cache.get_var("market", -1), 1e-9);
        assertEquals(-1, cache.get_var("unknown", -1), 1e-9);
    }

    @Test
    void signalSlotsAreRateLimitedPerStrategyAndTicker() {
        Cache cache = new Cache();

        assertTrue(cache.tryAcquireSignalSlot(TCS_MIN1, "rsi", 60_000));
        assertFalse(cache.tryAcquireSignalSlot(TCS_MIN1, "rsi", 60_000));
        assertTrue(cache.tryAcquireSignalSlot(TCS_MIN1, "sma", 60_000));
        assertTrue(cache.tryAcquireSignalSlot(TCS_MIN15, "rsi", 60_000));
        assertTrue(cache.tryAcquireSignalSlot(TCS_MIN1, "rsi", 0));

        assertFalse(cache.tryAcquireSignalSlot(null, "rsi", 0));
        assertFalse(cache.tryAcquireSignalSlot(TCS_MIN1, null, 0));
    }

    @Test
    void resettingCooldownsAllowsImmediateSignals() {
        Cache cache = new Cache();
        assertTrue(cache.tryAcquireSignalSlot(TCS_MIN1, "rsi", Long.MAX_VALUE));
        assertFalse(cache.tryAcquireSignalSlot(TCS_MIN1, "rsi", Long.MAX_VALUE));

        cache.resetSignalCooldowns();

        assertTrue(cache.tryAcquireSignalSlot(TCS_MIN1, "rsi", Long.MAX_VALUE));
    }

    @Test
    void candleCountsReflectPerTickerState() {
        Cache cache = new Cache();
        cache.push_candle(TCS_MIN1, candle(1, 1));
        cache.push_candle(TCS_MIN1, candle(2, 2));
        cache.push_candle(TCS_MIN15, candle(3, 3));

        var counts = cache.candleCounts();
        assertEquals(2, counts.get(TCS_MIN1));
        assertEquals(1, counts.get(TCS_MIN15));
        assertThrows(UnsupportedOperationException.class, () -> counts.put(INFY_MIN1, 1));
    }

    @Test
    void clearRemovesCandlesVariablesAndCooldowns() {
        Cache cache = new Cache();
        cache.push_candle(TCS_MIN1, candle(1, 1));
        cache.set_var(TCS_MIN1, "x", 1);
        cache.tryAcquireSignalSlot(TCS_MIN1, "rsi", Long.MAX_VALUE);

        cache.clear();

        assertEquals(0, cache.candle_count(TCS_MIN1));
        assertFalse(cache.has_var(TCS_MIN1, "x"));
        assertTrue(cache.tryAcquireSignalSlot(TCS_MIN1, "rsi", Long.MAX_VALUE));
    }

    @Test
    void concurrentPushesRespectBoundsWithoutLosingTheLock() throws Exception {
        int threads = 8;
        int pushesPerThread = 250;
        Cache cache = new Cache(100);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        try {
            for (int t = 0; t < threads; t++) {
                final int thread = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < pushesPerThread; i++) {
                            cache.push_candle(TCS_MIN1, candle(thread * 1000 + i, i));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "worker threads did not finish");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(100, cache.candle_count(TCS_MIN1));
        assertEquals(100, cache.get_candles(TCS_MIN1).size());
    }
}
