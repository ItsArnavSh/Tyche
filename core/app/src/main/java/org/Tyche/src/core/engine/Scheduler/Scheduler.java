package org.Tyche.src.core.engine.Scheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.locks.ReentrantLock;

import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;

/**
 * Fair, thread-safe work scheduler.
 *
 * <p>Each (ticker, candle-size) pair is a single job.  Boot jobs are
 * prioritised ahead of rolling jobs, and rolling jobs are served in FIFO
 * order so a busy ticker cannot starve the others.  Jobs are consumed by a
 * worker and are re-submitted when the next data update arrives.</p>
 */
public class Scheduler {
    private static final int BOOT_PRIORITY = -1000;
    private static final int ROLL_PRIORITY = 100;

    private final int allotNo;
    private final ReentrantLock lock;
    private final Map<PriorityBlock, Job> jobs;
    private final PriorityQueue<Job> queue;
    private long sequence;

    private static final class Job {
        final PriorityBlock block;
        int priority;
        final long sequence;

        Job(PriorityBlock block, int priority, long sequence) {
            this.block = block;
            this.priority = priority;
            this.sequence = sequence;
        }
    }

    private static final Comparator<Job> ORDER = Comparator
            .comparingInt((Job j) -> j.priority)
            .thenComparingLong(j -> j.sequence)
            .thenComparing(j -> j.block.name)
            .thenComparing(j -> j.block.size.name());

    public Scheduler(int allot_no) {
        if (allot_no <= 0) {
            throw new IllegalArgumentException("allot_no must be positive");
        }
        this.allotNo = allot_no;
        this.lock = new ReentrantLock();
        this.jobs = new HashMap<>();
        this.queue = new PriorityQueue<>(ORDER);
    }

    /**
     * Removes and returns up to {@code allotNo} jobs, highest priority first.
     */
    public PriorityBlock[] give_jobs() {
        this.lock.lock();
        try {
            ArrayList<PriorityBlock> result = new ArrayList<>(Math.min(allotNo, queue.size()));
            for (int i = 0; i < allotNo && !queue.isEmpty(); i++) {
                Job job = queue.poll();
                jobs.remove(job.block);
                result.add(job.block);
            }
            return result.toArray(PriorityBlock[]::new);
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Adds or refreshes jobs.  When {@code boot} is true the jobs jump the
     * queue; otherwise they are appended at normal priority.
     */
    public void UpdateHeap(ArrayList<PriorityBlock> tickers, boolean boot) {
        if (tickers == null || tickers.isEmpty()) {
            return;
        }
        this.lock.lock();
        try {
            for (PriorityBlock ticker : tickers) {
                if (ticker == null || ticker.name == null || ticker.size == null) {
                    continue;
                }
                Job existing = jobs.get(ticker);
                int priority = boot ? BOOT_PRIORITY : ROLL_PRIORITY;
                if (existing == null) {
                    jobs.put(ticker, new Job(ticker, priority, ++sequence));
                } else {
                    existing.priority = priority;
                }
            }
            rebuild();
        } finally {
            this.lock.unlock();
        }
    }

    private void rebuild() {
        queue.clear();
        queue.addAll(jobs.values());
    }

    /** Number of jobs waiting to be executed. */
    public int size() {
        this.lock.lock();
        try {
            return jobs.size();
        } finally {
            this.lock.unlock();
        }
    }

    /** Removes all jobs (used by tests and graceful shutdown). */
    public void clear() {
        this.lock.lock();
        try {
            jobs.clear();
            queue.clear();
        } finally {
            this.lock.unlock();
        }
    }
}
