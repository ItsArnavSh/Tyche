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

    /**
     * Marks a series as booting as soon as its history is accepted by the
     * data plane. Until the worker finishes the boot pipeline, roll updates
     * for that series must be accepted instead of being reported missing.
     */
    void mark_booting(PriorityBlock ticker) {
        if (ticker == null) {
            return;
        }
        activeStocks.compute(ticker, (key, existing) -> {
            if (existing == null) {
                return new StockVal(timer.time_elapsed(), true);
            }
            existing.bootmode = true;
            existing.time = timer.time_elapsed();
            return existing;
        });
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
     * A series that is still booting gets a grace window so queued boot work
     * cannot race the next roll and trigger an endless re-boot loop.
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
        if (existing.bootmode) {
            threshold = Math.max(threshold, Config.BOOT_GRACE_MILLIS);
        }
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
