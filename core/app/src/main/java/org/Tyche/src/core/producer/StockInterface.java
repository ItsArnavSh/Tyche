package org.Tyche.src.core.producer;

import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;

import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;

import org.Tyche.src.core.engine.Workers.Rayon;
import org.Tyche.src.core.producer.FakeVal.FakeVal;

public class StockInterface {
    Producer producer;
    Rayon rayon;
    ReentrantLock lock;

    public StockInterface(Rayon rayon) {
        this.rayon = rayon;
        this.producer = new FakeVal();
        this.lock = new ReentrantLock();
    }

    public void StockProcess() {
        // Initial Boot
        var req = new ArrayList<PriorityBlock>();
        var stocks = TargetStocks.stocks;
        for (var stock : stocks) {
            String name = stock;
            for (var size : TargetStocks.sizes) {
                var block = new PriorityBlock(size, name);
                req.add(block);
            }
        }
        var breq = producer.GetHistoricalData(req);
        rayon.boot_loader(breq);

        // Now we will keep on sending the alternative boot data
        for (;;) {
            var rreq = producer.GetLatestVals(req);
            var missing = rayon.roll_loader(rreq);

            // Send missing data back
            if (!missing.isEmpty()) {
                this.lock.lock();
                try {
                    req.removeAll(missing);
                } finally {
                    this.lock.unlock();
                }
                Runnable fix_missing = () -> {
                    var missing_vals = producer.GetHistoricalData(missing);
                    System.out.println("Missing " + missing.get(0).name);
                    this.rayon.boot_loader(missing_vals);
                    this.lock.lock();
                    try {
                        req.addAll(missing);
                    } finally {
                        this.lock.unlock();
                    }
                };
                Thread.ofVirtual().start(fix_missing);
            }
            try {
                Thread.sleep(1000);// Only query once every 5 secs for fresh data
            } catch (Exception e) {
            }
        }
    }
}
