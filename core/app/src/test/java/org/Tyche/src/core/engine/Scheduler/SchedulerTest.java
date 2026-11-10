package org.Tyche.src.core.engine.Scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;
import org.junit.jupiter.api.Test;

class SchedulerTest {

    private static PriorityBlock block(String name, CandleSize size) {
        return new PriorityBlock(size, name);
    }

    @Test
    void rejectsNonPositiveAllotment() {
        assertThrows(IllegalArgumentException.class, () -> new Scheduler(0));
        assertThrows(IllegalArgumentException.class, () -> new Scheduler(-5));
    }

    @Test
    void givesAtMostAllotmentAndRemovesJobs() {
        Scheduler scheduler = new Scheduler(2);
        PriorityBlock a = block("A", CandleSize.min1);
        PriorityBlock b = block("B", CandleSize.min1);
        PriorityBlock c = block("C", CandleSize.min1);

        scheduler.UpdateHeap(new ArrayList<>(List.of(a, b, c)), false);

        assertEquals(3, scheduler.size());
        assertEquals(2, scheduler.give_jobs().length);
        assertEquals(1, scheduler.size());
        assertEquals(1, scheduler.give_jobs().length);
        assertEquals(0, scheduler.give_jobs().length);
    }

    @Test
    void bootJobsJumpAheadOfRollingJobs() {
        Scheduler scheduler = new Scheduler(10);
        scheduler.UpdateHeap(new ArrayList<>(List.of(
                block("ROLL1", CandleSize.min1),
                block("ROLL2", CandleSize.min1))), false);
        scheduler.UpdateHeap(new ArrayList<>(List.of(
                block("BOOT", CandleSize.min15))), true);

        PriorityBlock[] jobs = scheduler.give_jobs();

        assertEquals("BOOT", jobs[0].name);
    }

    @Test
    void samePriorityIsFifo() {
        Scheduler scheduler = new Scheduler(10);
        scheduler.UpdateHeap(new ArrayList<>(List.of(block("FIRST", CandleSize.min1))), false);
        scheduler.UpdateHeap(new ArrayList<>(List.of(block("SECOND", CandleSize.min1))), false);

        PriorityBlock[] jobs = scheduler.give_jobs();

        assertEquals("FIRST", jobs[0].name);
        assertEquals("SECOND", jobs[1].name);
    }

    @Test
    void resubmissionDoesNotDuplicateAndCanEscalatePriority() {
        Scheduler scheduler = new Scheduler(10);
        PriorityBlock a = block("A", CandleSize.min1);
        PriorityBlock b = block("B", CandleSize.min1);

        scheduler.UpdateHeap(new ArrayList<>(List.of(a)), false);
        scheduler.UpdateHeap(new ArrayList<>(List.of(a)), false);
        scheduler.UpdateHeap(new ArrayList<>(List.of(b)), false);
        assertEquals(2, scheduler.size());

        scheduler.UpdateHeap(new ArrayList<>(List.of(a)), true);

        assertEquals(2, scheduler.size());
        assertEquals("A", scheduler.give_jobs()[0].name);
    }

    @Test
    void invalidEntriesAreIgnored() {
        Scheduler scheduler = new Scheduler(5);
        ArrayList<PriorityBlock> invalid = new ArrayList<>();
        invalid.add(null);
        invalid.add(new PriorityBlock(CandleSize.min1, null));
        invalid.add(new PriorityBlock(null, "TCS"));

        scheduler.UpdateHeap(invalid, false);
        scheduler.UpdateHeap(null, false);

        assertEquals(0, scheduler.size());
    }

    @Test
    void clearDropsEverything() {
        Scheduler scheduler = new Scheduler(5);
        scheduler.UpdateHeap(new ArrayList<>(List.of(block("A", CandleSize.min1))), false);

        scheduler.clear();

        assertEquals(0, scheduler.size());
        assertEquals(0, scheduler.give_jobs().length);
    }

    @Test
    void concurrentProducersAndConsumersSeeEachJobExactlyOnce() throws Exception {
        int producers = 4;
        int perProducer = 50;
        int total = producers * perProducer;
        Scheduler scheduler = new Scheduler(16);
        ExecutorService pool = Executors.newFixedThreadPool(producers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch produced = new CountDownLatch(producers);
        Set<PriorityBlock> seen = new HashSet<>();
        AtomicInteger duplicates = new AtomicInteger();

        try {
            for (int p = 0; p < producers; p++) {
                final int producer = p;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perProducer; i++) {
                            scheduler.UpdateHeap(new ArrayList<>(List.of(
                                    block("SYM" + producer + "_" + i, CandleSize.min1))), false);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        produced.countDown();
                    }
                });
            }
            start.countDown();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while ((seen.size() < total || produced.getCount() > 0)
                    && System.nanoTime() < deadline) {
                for (PriorityBlock job : scheduler.give_jobs()) {
                    if (!seen.add(job)) {
                        duplicates.incrementAndGet();
                    }
                }
                if (seen.size() == total && produced.getCount() == 0) {
                    break;
                }
                Thread.sleep(1);
            }

            assertTrue(produced.await(5, TimeUnit.SECONDS), "producers did not finish");
            assertTrue(seen.size() == total, "only saw " + seen.size() + "/" + total + " jobs");
            assertEquals(0, duplicates.get());
        } finally {
            pool.shutdownNow();
        }
    }
}
