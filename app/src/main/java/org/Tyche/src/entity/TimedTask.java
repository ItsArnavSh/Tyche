package org.Tyche.src.entity;

import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;

public class TimedTask implements Delayed {
    public final long run_at;
    public final PriorityBlock ticker;

    public TimedTask(PriorityBlock ticker) {
        this.run_at = System.currentTimeMillis() + ticker.size.get_duration_millis();
        this.ticker = ticker;
        System.out.println(System.currentTimeMillis() + " -> " + this.run_at);

    }

    @Override
    public long getDelay(TimeUnit unit) {
        var delay = unit.convert(
                run_at - System.currentTimeMillis(),
                TimeUnit.MILLISECONDS);
        System.out.println("Run at is at: " + this.run_at % 100000);
        return delay;
    }

    @Override
    public int compareTo(Delayed o) {
        return Long.compare(this.run_at, ((TimedTask) o).run_at);
    }
}
