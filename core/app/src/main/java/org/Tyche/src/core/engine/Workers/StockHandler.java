package org.Tyche.src.core.engine.Workers;

import java.util.HashMap;

import org.Tyche.src.entity.StockVal;
import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;
import org.Tyche.src.util.BotClock;

public class StockHandler {
    HashMap<PriorityBlock, StockVal> active_stocks;
    BotClock timer;

    public StockHandler(BotClock timer) {
        this.timer = timer;
        this.active_stocks = new HashMap<>();
    }

    boolean boot_check(PriorityBlock ticker) {
        return this.active_stocks.containsKey(ticker);
    }

    void mark_booted(PriorityBlock ticker) {

        var status = this.active_stocks.get(ticker);
        if (status == null) {

            System.out.println("So it was not known");
            this.active_stocks.put(ticker, new StockVal(timer.time_elapsed(), false));

        } else {
            System.out.println("So it was known");
            status.bootmode = false;
        }
    }

    boolean stale_check(PriorityBlock ticker) {
        return false;
        /*
         * var now = this.timer.time_elapsed();
         * var entry = this.active_stocks.get(ticker);
         * if (entry == null)
         * return true;
         * var elapsed = now - entry.time;
         * var threshold = ticker.size.get_duration_millis() * 2;
         * if (elapsed > threshold) {
         * this.active_stocks.remove(ticker);
         * return true;
         * }
         * // entry.time = now;
         * return false;
         */
    }
}
