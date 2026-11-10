package org.Tyche.src.core.engine.Scheduler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.entity.TimedTask;
import org.Tyche.src.internal.cache.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides <em>when</em> a ticker is ready to be processed.  The worker pool
 * asks for functions; the load balancer only releases them once the ticker's
 * candle-size timer has fired, which prevents running 1-hour strategies on
 * every 5-second update.
 */
public class LoadBalancer {
    private static final Logger logger = LoggerFactory.getLogger(LoadBalancer.class);

    private final HashMap<String, Set<CandleSize>> activeTasks;
    private final FunctionAlloter functions;
    private final DelayQueue<TimedTask> timeQueue;
    private final ReentrantLock lock;
    private final HashSet<String> timerStarted;
    private final Repository repo;
    private final Thread background;
    private volatile boolean running = true;

    public LoadBalancer(Repository repo) {
        this.repo = repo;
        this.lock = new ReentrantLock();
        this.functions = new FunctionAlloter();
        this.timeQueue = new DelayQueue<>();
        this.activeTasks = new HashMap<>();
        this.timerStarted = new HashSet<>();
        this.background = new Thread(this::backgroundScheduler, "tyche-load-balancer");
        this.background.setDaemon(true);
        this.background.start();
    }

    public ArrayList<Consumer<StartParams>> give_funcs(PriorityBlock ticker, boolean boot) {
        if (ticker == null) {
            return new ArrayList<>();
        }
        this.lock.lock();
        try {
            if (is_active(ticker)) {
                remove_from_active(ticker.name, ticker.size);
                ArrayList<Consumer<StartParams>> funcs = this.functions.get_size_funcs(ticker.size, boot);
                return funcs == null ? new ArrayList<>() : new ArrayList<>(funcs);
            }
            return new ArrayList<>();
        } finally {
            this.lock.unlock();
        }
    }

    public void add_to_queue(PriorityBlock ticker) {
        if (ticker == null) {
            return;
        }
        add(new TimedTask(ticker));
    }

    private void add(TimedTask task) {
        this.lock.lock();
        try {
            this.timeQueue.add(task);
        } finally {
            this.lock.unlock();
        }
    }

    private void add_to_dict(String tickerName, CandleSize size) {
        this.activeTasks.computeIfAbsent(tickerName, k -> new HashSet<>()).add(size);
    }

    private void remove_from_active(String ticker, CandleSize size) {
        var tick = this.activeTasks.get(ticker);
        if (tick != null) {
            tick.remove(size);
            if (tick.isEmpty()) {
                this.activeTasks.remove(ticker);
            }
        }
    }

    private boolean is_active(PriorityBlock ticker) {
        var temp = this.activeTasks.get(ticker.name);
        return temp != null && temp.contains(ticker.size);
    }

    /** Starts the per-size timers for a ticker exactly once. */
    public void set_boot_ticker(String name) {
        if (name == null) {
            return;
        }
        this.lock.lock();
        try {
            if (!this.timerStarted.add(name)) {
                return;
            }
            List<CandleSize> sizes = Arrays.asList(CandleSize.sec5, CandleSize.sec30, CandleSize.min1,
                    CandleSize.min15, CandleSize.hour1);
            for (var size : sizes) {
                this.timeQueue.add(new TimedTask(new PriorityBlock(size, name)));
            }
        } finally {
            this.lock.unlock();
        }
    }

    /** Removes all timers and pending work for a ticker. */
    public void remove_ticker(String name) {
        if (name == null) {
            return;
        }
        this.lock.lock();
        try {
            this.timerStarted.remove(name);
            this.activeTasks.remove(name);
            this.timeQueue.removeIf(task -> task.ticker.name.equals(name));
        } finally {
            this.lock.unlock();
        }
    }

    private void backgroundScheduler() {
        while (running) {
            try {
                TimedTask task = timeQueue.take();
                this.lock.lock();
                try {
                    if (!running) {
                        return;
                    }
                    add_to_dict(task.ticker.name, task.ticker.size);
                } finally {
                    this.lock.unlock();

                }
                // Schedule the next run for this ticker/size.
                add_to_queue(task.ticker);
                repo.redis.set("monitor:pending_functions", Integer.toString(this.timeQueue.size()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                logger.error("Load balancer scheduler error", t);
            }
        }
    }

    public void shutdown() {
        running = false;
        this.lock.lock();
        try {
            this.timeQueue.clear();
            this.activeTasks.clear();
            this.timerStarted.clear();
        } finally {
            this.lock.unlock();
        }
        // Wake the blocking take().
        this.background.interrupt();
    }

    public int pending() {
        this.lock.lock();
        try {
            return this.timeQueue.size();
        } finally {
            this.lock.unlock();
        }
    }

    /** Exposed for tests. */
    int functionCount(CandleSize size, boolean boot) {
        var list = this.functions.get_size_funcs(size, boot);
        return list == null ? 0 : list.size();
    }
}
