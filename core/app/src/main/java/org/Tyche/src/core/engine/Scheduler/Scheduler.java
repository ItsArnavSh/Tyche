package org.Tyche.src.core.engine.Scheduler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.PriorityQueue;
import java.util.concurrent.locks.ReentrantLock;

import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;
import org.Tyche.src.entity.Scheduler_Entity.PriorityMapVal;

//Including no locks here, all that will be managed by the scheduler
//Its assumed heap will be accessed in a single threaded manner
//By a single access point aka the scheduler
public class Scheduler {
    int allot_no;
    PriorityQueue<PriorityBlock> pq;
    HashMap<PriorityMapVal, Integer> priority_map;
    private final ReentrantLock lock;

    public Scheduler(int allot_no) {
        this.allot_no = allot_no;
        this.pq = new PriorityQueue<PriorityBlock>();
        this.lock = new ReentrantLock();
        this.priority_map = new HashMap<>();
    }

    public PriorityBlock[] give_jobs() {
        this.lock.lock();
        try {
            PriorityBlock[] res = new PriorityBlock[this.allot_no];
            int count = 0;
            for (; count < allot_no; count++) {
                PriorityBlock t = pq.poll();
                if (t == null)
                    break;
                res[count] = t;
            }
            return Arrays.copyOf(res, count);
        } finally {
            this.lock.unlock();
        }
    }

    public void UpdateHeap(ArrayList<PriorityBlock> tickers, boolean boot) {

        this.lock.lock();
        try {
            if (!boot) {
                while (!this.pq.isEmpty()) {
                    var block = pq.poll();

                    System.out
                            .println(block.name + block.size + " Curr time: " + (System.currentTimeMillis() % 100000));
                    PriorityMapVal temp = new PriorityMapVal(block.name, block.size);
                    var priority = this.priority_map.get(temp);
                    if (priority == null) {
                        this.priority_map.put(temp, 100);
                        priority = 100;
                    }
                    this.priority_map.put(temp, priority - 1);
                }
            }

            // System.out.println("Size of heap: " + this.pq.size());
            for (var ticker : tickers) {
                var temp = new PriorityMapVal(ticker.name, ticker.size);
                var priority = 0;
                if (boot)
                    priority = -1000;// Very High priority on boots
                else
                    priority = 100;// Roll data gets standard prio
                this.priority_map.put(temp, priority);
                this.pq.add(ticker);

            }
        } finally {
            this.lock.unlock();
        }
    }
}