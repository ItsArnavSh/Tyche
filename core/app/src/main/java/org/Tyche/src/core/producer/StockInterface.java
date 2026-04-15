package org.Tyche.src.core.producer;

import org.Tyche.src.entity.CoreAPI.BootRequest;
import org.Tyche.src.entity.CoreAPI.RollRequest;
import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;

import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;

import org.Tyche.src.core.engine.Workers.Rayon;
import org.Tyche.src.core.producer.FakeVal.FakeVal;

public class StockInterface {
    Producer producer;
    Rayon rayon;
    ReentrantLock lock;
    ArrayList<PriorityBlock> req;

    public StockInterface(Rayon rayon) {
        this.rayon = rayon;
        this.producer = new FakeVal();
        this.lock = new ReentrantLock();
        this.req = new ArrayList<>();
    }

    public void PushHistoricalVals(BootRequest breq) {

        rayon.boot_loader(breq);
    }

    public ArrayList<PriorityBlock> PushLatestVals(RollRequest rreq) {
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
        return missing;

    }
}
