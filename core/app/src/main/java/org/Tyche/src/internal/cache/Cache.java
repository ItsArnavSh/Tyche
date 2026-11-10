package org.Tyche.src.internal.cache;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;

/**
 * Thread-safe, bounded per-ticker candle store plus a per-ticker variable
 * space used by strategy functions.
 *
 * <p><b>Ordering contract:</b> every deque returned by this cache is
 * <em>newest first</em> ({@code peekFirst()} is the latest candle).  Incoming
 * historical data is supplied chronologically (oldest first) and normalised
 * here, so producers can never accidentally invert the series.</p>
 */
public class Cache {

    public static final int DEFAULT_MAX_CANDLES = 1500;

    private final Map<PriorityBlock, ArrayDeque<Candle>> candleCache;
    private final Map<String, Double> varCache;
    private final Map<String, Long> lastSignalAt;
    private final ReentrantLock candleLock;
    private final ReentrantLock varLock;
    private final int maxCandles;

    public Cache() {
        this(DEFAULT_MAX_CANDLES);
    }

    public Cache(int maxCandles) {
        if (maxCandles < 2) {
            throw new IllegalArgumentException("maxCandles must be >= 2");
        }
        this.maxCandles = maxCandles;
        this.candleCache = new HashMap<>();
        this.varCache = new HashMap<>();
        this.lastSignalAt = new HashMap<>();
        this.candleLock = new ReentrantLock();
        this.varLock = new ReentrantLock();
    }

    // ── Candles ─────────────────────────────────────────────────────────

    /** Appends the newest candle (O(1)). */
    public void push_candle(PriorityBlock ticker, Candle data) {
        if (ticker == null || data == null) {
            return;
        }
        this.candleLock.lock();
        try {
            ArrayDeque<Candle> list = candleCache.computeIfAbsent(ticker, k -> new ArrayDeque<>());
            list.addFirst(data);
            trim(list);
        } finally {
            this.candleLock.unlock();
        }
    }

    /**
     * Replaces the full history for a ticker.
     *
     * @param candles chronological series, oldest candle first (Yahoo/Kite
     *                returns data in this order).
     */
    public void new_candles(PriorityBlock ticker, Collection<Candle> candles) {
        if (ticker == null || candles == null || candles.isEmpty()) {
            return;
        }
        this.candleLock.lock();
        try {
            ArrayDeque<Candle> list = new ArrayDeque<>(Math.min(candles.size(), maxCandles));
            for (Candle candle : candles) {
                if (candle != null) {
                    list.addFirst(candle); // newest first
                }
            }
            trim(list);
            candleCache.put(ticker, list);
        } finally {
            this.candleLock.unlock();
        }
    }

    /** Backwards-compatible overload. */
    public void new_candles(PriorityBlock ticker, ArrayDeque<Candle> candles) {
        new_candles(ticker, (Collection<Candle>) candles);
    }

    /** Snapshot of the series in newest-first order. */
    public ArrayDeque<Candle> get_candles(PriorityBlock ticker) {
        this.candleLock.lock();
        try {
            ArrayDeque<Candle> existing = candleCache.get(ticker);
            return existing == null ? new ArrayDeque<>() : new ArrayDeque<>(existing);
        } finally {
            this.candleLock.unlock();
        }
    }

    /** Snapshot in chronological (oldest-first) order, convenient for indicators. */
    public List<Candle> get_candles_chronological(PriorityBlock ticker) {
        this.candleLock.lock();
        try {
            ArrayDeque<Candle> existing = candleCache.get(ticker);
            if (existing == null) {
                return new ArrayList<>();
            }
            ArrayList<Candle> out = new ArrayList<>(existing.size());
            // existing is newest-first; iterate from last to first.
            var it = existing.descendingIterator();
            while (it.hasNext()) {
                out.add(it.next());
            }
            return out;
        } finally {
            this.candleLock.unlock();
        }
    }

    public int candle_count(PriorityBlock ticker) {
        this.candleLock.lock();
        try {
            ArrayDeque<Candle> existing = candleCache.get(ticker);
            return existing == null ? 0 : existing.size();
        } finally {
            this.candleLock.unlock();
        }
    }

    public void remove_candles(PriorityBlock ticker) {
        this.candleLock.lock();
        try {
            candleCache.remove(ticker);
        } finally {
            this.candleLock.unlock();
        }
    }

    private void trim(ArrayDeque<Candle> list) {
        while (list.size() > maxCandles) {
            list.pollLast(); // drop oldest
        }
    }

    // ── Variables ───────────────────────────────────────────────────────

    private static String varKey(PriorityBlock ticker, String key) {
        return ticker.name + "|" + ticker.size.name() + "|" + key;
    }

    /** Per-ticker/size variable.  Keys are automatically namespaced. */
    public void set_var(PriorityBlock ticker, String key, double val) {
        if (ticker == null || key == null || Double.isNaN(val) || Double.isInfinite(val)) {
            return;
        }
        this.varLock.lock();
        try {
            varCache.put(varKey(ticker, key), val);
        } finally {
            this.varLock.unlock();
        }
    }

    public double get_var(PriorityBlock ticker, String key, double fallback) {
        this.varLock.lock();
        try {
            return varCache.getOrDefault(varKey(ticker, key), fallback);
        } finally {
            this.varLock.unlock();
        }
    }

    public boolean has_var(PriorityBlock ticker, String key) {
        this.varLock.lock();
        try {
            return varCache.containsKey(varKey(ticker, key));
        } finally {
            this.varLock.unlock();
        }
    }

    public void remove_var(PriorityBlock ticker, String key) {
        this.varLock.lock();
        try {
            varCache.remove(varKey(ticker, key));
        } finally {
            this.varLock.unlock();
        }
    }

    /**
     * Returns true when a strategy is allowed to emit a signal for this ticker.
     * The first call always succeeds; subsequent calls are rate limited.
     */
    public boolean tryAcquireSignalSlot(PriorityBlock ticker, String strategy, long cooldownMillis) {
        if (ticker == null || strategy == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        String key = varKey(ticker, "signal@" + strategy);
        this.varLock.lock();
        try {
            Long last = lastSignalAt.get(key);
            if (last != null && now - last < Math.max(0, cooldownMillis)) {
                return false;
            }
            lastSignalAt.put(key, now);
            return true;
        } finally {
            this.varLock.unlock();
        }
    }

    public void resetSignalCooldowns() {
        this.varLock.lock();
        try {
            lastSignalAt.clear();
        } finally {
            this.varLock.unlock();
        }
    }

    // ── Legacy global variable helpers ──────────────────────────────────
    // Kept for compatibility; new code should use the per-ticker overloads.

    public void set_var(String key, double val) {
        this.varLock.lock();
        try {
            varCache.put("_global_|" + key, val);
        } finally {
            this.varLock.unlock();
        }
    }

    public double get_var(String key, double fallback) {
        this.varLock.lock();
        try {
            return varCache.getOrDefault("_global_|" + key, fallback);
        } finally {
            this.varLock.unlock();
        }
    }

    public void clear() {
        this.candleLock.lock();
        try {
            candleCache.clear();
        } finally {
            this.candleLock.unlock();
        }
        this.varLock.lock();
        try {
            varCache.clear();
            lastSignalAt.clear();
        } finally {
            this.varLock.unlock();
        }
    }

    /** Exposed for diagnostics / tests. Returned maps are immutable snapshots. */
    public Map<PriorityBlock, Integer> candleCounts() {
        this.candleLock.lock();
        try {
            Map<PriorityBlock, Integer> out = new HashMap<>();
            candleCache.forEach((k, v) -> out.put(k, v.size()));
            return Map.copyOf(out);
        } finally {
            this.candleLock.unlock();
        }
    }
}
