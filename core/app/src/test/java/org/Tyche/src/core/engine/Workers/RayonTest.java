package org.Tyche.src.core.engine.Workers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;

import org.Tyche.src.core.engine.Scheduler.LoadBalancer;
import org.Tyche.src.core.engine.Scheduler.Scheduler;
import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.CoreAPI.BootRequest;
import org.Tyche.src.entity.CoreAPI.LatestStockVal;
import org.Tyche.src.entity.CoreAPI.RollRequest;
import org.Tyche.src.entity.CoreAPI.Series;
import org.Tyche.src.entity.CoreAPI.StockUpdate;
import org.Tyche.src.entity.CoreAPI.TickerHistory;
import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;
import org.Tyche.src.internal.cache.Repository;
import org.Tyche.src.util.BotClock;
import org.junit.jupiter.api.Test;

class RayonTest {

    @Test
    void bootLoaderMarksSeriesBootingSoRollsAreAcceptedBeforeWorkerRuns() {
        Repository repo = new Repository(false);
        StockHandler handler = new StockHandler(new BotClock());
        LoadBalancer loadBalancer = new LoadBalancer(repo);
        Scheduler scheduler = new Scheduler(4);
        Rayon rayon = new Rayon(loadBalancer, handler, scheduler, repo);
        PriorityBlock block = new PriorityBlock(CandleSize.min1, "TCS");

        try {
            BootRequest boot = new BootRequest();
            TickerHistory history = new TickerHistory("TCS");
            Series series = new Series(CandleSize.min1);
            series.candles = new ArrayDeque<>();
            series.candles.add(new Candle(100, 102, 99, 101, 1000));
            history.series.add(series);
            boot.history.add(history);

            rayon.boot_loader(boot);

            assertTrue(handler.boot_check(block), "boot data must stay in boot mode until the worker finishes");
            assertFalse(handler.stale_check(block), "a queued boot must not make roll data look missing");
            assertEquals(1, repo.cache.candle_count(block));

            RollRequest roll = new RollRequest();
            StockUpdate update = new StockUpdate("TCS");
            update.val.add(new LatestStockVal(new Candle(101, 103, 100, 102, 500), CandleSize.min1));
            roll.update.add(update);

            assertTrue(rayon.roll_loader(roll).isEmpty(),
                    "roll updates must be accepted while the boot job is still queued");
            assertEquals(2, repo.cache.candle_count(block));

            handler.mark_booted(block);
            assertFalse(handler.boot_check(block));
        } finally {
            loadBalancer.shutdown();
        }
    }
}
