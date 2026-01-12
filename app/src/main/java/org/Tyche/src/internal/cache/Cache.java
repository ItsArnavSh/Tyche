package org.Tyche.src.internal.cache;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;

//Thread safe cache
public class Cache {
    HashMap<String, Double> var_cache;
    HashMap<PriorityBlock, ArrayDeque<Candle>> candle_cache;
    ReentrantLock candle_lock, var_lock;

    public Cache() {
        var_cache = new HashMap<>();
        candle_cache = new HashMap<>();
        candle_lock = new ReentrantLock();
        var_lock = new ReentrantLock();
    }

    public void push_candle(PriorityBlock ticker, Candle data) {
        this.candle_lock.lock();
        try {
            var list = candle_cache.get(ticker);
            if (list == null) {
                candle_cache.put(ticker, new ArrayDeque<>());
                list = candle_cache.get(ticker);
            }
            list.push(data);
            if (list.size() > 100) {
                list.pollLast();
            }
        } finally {
            this.candle_lock.unlock();
        }
    }

    ;

    public void new_candles(PriorityBlock ticker, ArrayDeque<Candle> candles) {
        this.candle_lock.lock();
        try {
            this.candle_cache.put(ticker, candles);
        } finally {
            this.candle_lock.unlock();
        }
    }

    public void set_var(String key, double val) {
        this.var_lock.lock();
        try {
            this.var_cache.put(key, val);
        } finally {
            this.var_lock.unlock();
        }
    }

}
