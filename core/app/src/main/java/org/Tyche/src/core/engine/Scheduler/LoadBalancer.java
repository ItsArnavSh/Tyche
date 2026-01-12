package org.Tyche.src.core.engine.Scheduler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.TimedTask;
import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.util.Sleeper;

public class LoadBalancer {
    HashMap<String, Set<CandleSize>> active_tasks;
    FunctionAlloter odin;
    DelayQueue<TimedTask> time_queue;
    ReentrantLock lock;
    HashSet<String> TimerStarted;
    Sleeper sleeper;

    public LoadBalancer() {
        this.lock = new ReentrantLock();
        this.odin = new FunctionAlloter();
        this.sleeper = new Sleeper();
        this.time_queue = new DelayQueue<TimedTask>();
        this.active_tasks = new HashMap<String, Set<CandleSize>>();
        this.TimerStarted = new HashSet<>();
        Runnable bgs = () -> {
            this.background_scheduler();
        };
        Thread.ofVirtual().start(bgs);
    }

    public ArrayList<Consumer<StartParams>> give_funcs(PriorityBlock ticker, boolean boot) {
        // System.out.println("Here we gooo asking fot " + ticker.size);
        this.lock.lock();
        // System.out.println("Accuired Lock");
        try {
            if (this.is_active(ticker)) {
                // System.out.println("Not cooked");
                this.remove_from_active(ticker.name, ticker.size);
                // System.out.println("Tadaaa " + this.is_active(ticker.name, ticker.size));
                return this.odin.get_size_funcs(ticker.size, boot);
            } else {

                // System.out.println("No funcs yet");
                // return this.odin.get_size_funcs(ticker.size, false);
                return new ArrayList<>();
            }
        } finally {
            this.lock.unlock();
        }
    }

    public void add_to_queue(PriorityBlock ticker) {
        System.out.println("Aaaah");
        var task = new TimedTask(ticker);
        this.lock.lock();
        try {
            this.time_queue.add(task);
        } finally {
            this.lock.unlock();
        }

    }

    void add_to_dict(String ticker_name, CandleSize size) {
        this.active_tasks.computeIfAbsent(ticker_name, k -> new HashSet<>()).add(size);
    }

    void remove_from_active(String ticker, CandleSize size) {
        var tick = this.active_tasks.get(ticker);
        if (tick != null)
            tick.remove(size);
    }

    boolean is_active(PriorityBlock ticker) {
        var temp = this.active_tasks.get(ticker.name);
        if (temp == null)
            return false;
        return temp.contains(ticker.size);
    }

    public void set_boot_ticker(String name) {
        if (this.TimerStarted.contains(name))
            return;

        this.TimerStarted.add(name);
        var sizes = Arrays.asList(CandleSize.sec5);
        for (var size : sizes) {
            var task = new TimedTask(new PriorityBlock(size, name));
            this.time_queue.add(task);
        }

    }

    void background_scheduler() {

        for (;;) {
            try {
                TimedTask task = time_queue.take(); // blocks until delay expires

                lock.lock();
                try {
                    System.out.println("Making task available");
                    add_to_dict(task.ticker.name, task.ticker.size);
                } finally {
                    lock.unlock();
                }

                // schedule the NEXT run
                add_to_queue(task.ticker);

                System.out.println("Added to queue " + task.ticker.name + " " + task.ticker.size);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}