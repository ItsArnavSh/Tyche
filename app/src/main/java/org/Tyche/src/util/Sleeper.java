package org.Tyche.src.util;

import java.util.concurrent.locks.*;

import java.util.concurrent.TimeUnit;

public class Sleeper {
    private final Lock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();
    private boolean awake = false;

    // sleep for up to timeoutMillis
    public boolean sleep(long timeoutMillis) throws InterruptedException {
        lock.lock();
        try {
            long remaining = timeoutMillis;

            while (!awake && remaining > 0) {
                long start = System.nanoTime();
                condition.await(remaining, TimeUnit.MILLISECONDS);
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                remaining -= elapsed;
            }

            boolean wasWoken = awake;
            awake = false;
            return wasWoken; // true = manual wake, false = timeout
        } finally {
            lock.unlock();
        }
    }

    public void wake() {
        lock.lock();
        try {
            awake = true;
            condition.signal();
        } finally {
            lock.unlock();
        }
    }
}
