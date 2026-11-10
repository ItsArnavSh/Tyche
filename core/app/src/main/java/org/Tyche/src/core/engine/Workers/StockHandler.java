package org.Tyche.src.core.engine.Workers;

import java.util.concurrent.ConcurrentHashMap;

import org.Tyche.Config;
import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;
import org.Tyche.src.entity.StockVal;
import org.Tyche.src.util.BotClock;

/**
 * Tracks which (ticker, candle-size) jobs have been booted and whether their
 * data has gone stale.
 */
public class StockHandler {
    private final ConcurrentHashMap<PriorityBlock, StockVal> activeStocks;
    private final BotClock timer;

    public StockHandler(BotClock timer) {
        this.timer = timer;
        this.activeStocks = new ConcurrentHashMap<>();
    }

    /**
     * @return true when the worker must run the boot pipeline (first sight or
     *         still marked as booting).
     */
    boolean boot_check(PriorityBlock ticker) {
        if (ticker == null) {
            return true;
        }
        StockVal status = activeStocks.get(ticker);
        return status == null || status.bootmode;
    }

    void mark_booted(PriorityBlock ticker) {
        if (ticker == null) {
            return;
        }
        activeStocks.compute(ticker, (key, existing) -> {
            if (existing == null) {
                return new StockVal(timer.time_elapsed(), false);
            }
            existing.bootmode = false;
            existing.time = timer.time_elapsed();
            return existing;
        });
    }

    /**
     * Returns true when a roll update cannot be applied because the ticker is
     * unknown or its last update is older than {@code size * STALE_MULTIPLIER}.
     */
    boolean stale_check(PriorityBlock ticker) {
        if (ticker == null || ticker.size == null) {
            return true;
        }
        long now = timer.time_elapsed();
        StockVal existing = activeStocks.get(ticker);
        if (existing == null) {
            return true;
        }
        long threshold = (long) (ticker.size.get_duration_millis() * Config.STALE_MULTIPLIER);
        if (now - existing.time > threshold) {
            activeStocks.remove(ticker);
            return true;
        }
        return false;
    }

    void touch(PriorityBlock ticker) {
        if (ticker == null) {
            return;
        }
        activeStocks.computeIfPresent(ticker, (key, existing) -> {
            existing.time = timer.time_elapsed();
            return existing;
        });
    }

    int active_count() {
        return activeStocks.size();
    }

    void remove(PriorityBlock ticker) {
        if (ticker != null) {
            activeStocks.remove(ticker);
        }
    }
}
