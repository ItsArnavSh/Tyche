package org.Tyche.src.core.engine.Workers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.Tyche.Config;
import org.Tyche.src.core.engine.Scheduler.LoadBalancer;
import org.Tyche.src.core.engine.Scheduler.Scheduler;
import org.Tyche.src.entity.CoreAPI.BootRequest;
import org.Tyche.src.entity.CoreAPI.RollRequest;
import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;
import org.Tyche.src.entity.StrategyEntity.Context;
import org.Tyche.src.entity.StrategyEntity.StartParams;
import org.Tyche.src.internal.cache.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Worker pool that executes strategy functions for scheduled tickers.
 */
public class Rayon {
   public int cores;
   private final LoadBalancer loadBalancer;
   private final StockHandler stockHandler;
   private final Scheduler scheduler;
   private final Repository repo;

   private final Logger logger = LoggerFactory.getLogger(Rayon.class);
   private volatile boolean running = false;
   private ExecutorService executor;

   public Rayon(LoadBalancer lb, StockHandler sh, Scheduler sch, Repository repo) {
      this.loadBalancer = lb;
      this.stockHandler = sh;
      this.scheduler = sch;
      this.repo = repo;
   }

   public void Monitor() {
      while (running) {
         try {
            Thread.sleep(1000);
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
         }
      }
   }

   public synchronized void BootThreads() {
      if (running) {
         return;
      }
      this.cores = Math.max(1, Config.WORKER_THREADS);
      this.executor = Executors.newFixedThreadPool(cores, runnable -> {
         Thread thread = new Thread(runnable, "tyche-worker");
         thread.setDaemon(true);
         return thread;
      });
      this.running = true;
      repo.redis.set("monitor:cores_no", String.valueOf(cores));
      logger.info("Starting {} worker threads", cores);

      for (int i = 0; i < cores; i++) {
         final int workerId = i;
         executor.submit(() -> {
            try {
               ThreadTask(workerId);
            } catch (Throwable t) {
               logger.error("Worker {} terminated unexpectedly", workerId, t);
            }
         });
      }
   }

   private void ThreadTask(int workerId) {
      logger.debug("Worker {} is ready", workerId);
      while (running) {
         try {
            PriorityBlock[] jobs = scheduler.give_jobs();
            if (jobs.length == 0) {
               Thread.sleep(50);
               continue;
            }
            for (PriorityBlock job : jobs) {
               if (job == null) {
                  continue;
               }
               executeJob(workerId, job);
            }
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
         } catch (Throwable t) {
            logger.error("Worker {} recovered from error", workerId, t);
         }
      }
   }

   private void executeJob(int workerId, PriorityBlock job) {
      boolean boot = stockHandler.boot_check(job);
      ArrayList<java.util.function.Consumer<StartParams>> funcs = loadBalancer.give_funcs(job, boot);
      if (funcs == null || funcs.isEmpty()) {
         // The load balancer has not opened the gate for this size yet. Put the
         // job back instead of silently dropping the data update.
         scheduler.UpdateHeap(new ArrayList<>(List.of(job)), boot);
         sleepQuietly(25);
         return;
      }

      StartParams params = new StartParams(repo, new Context(job));
      for (var func : funcs) {
         try {
            func.accept(params);
         } catch (Throwable t) {
            logger.error("Strategy {} failed on {} {}",
                  func, job.name, job.size, t);
         }
      }
      stockHandler.mark_booted(job);
      stockHandler.touch(job);
      logger.debug("Worker {} finished {} {} ({})", workerId, job.name, job.size,
            boot ? "boot" : "roll");
   }

   private void sleepQuietly(long millis) {
      try {
         Thread.sleep(millis);
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
      }
   }

   public void StopServer() {
      running = false;
      if (executor != null) {
         executor.shutdown();
         try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
               executor.shutdownNow();
            }
         } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
         }
      }
   }

   public void boot_loader(BootRequest breq) {
      if (breq == null) {
         return;
      }
      var rollThese = new ArrayList<PriorityBlock>();
      for (var stockHistory : breq.history) {
         if (stockHistory == null) {
            continue;
         }
         var name = stockHistory.name;
         for (var candleSeries : stockHistory.series) {
            var size = candleSeries.size;
            PriorityBlock block = new PriorityBlock(size, name);
            this.stockHandler.mark_booting(block);
            this.repo.cache.new_candles(block, candleSeries.candles);
            this.loadBalancer.activate_boot(block);
            rollThese.add(block);
         }
      }
      this.update_data(rollThese, true);
   }

   public ArrayList<PriorityBlock> roll_loader(RollRequest rreq) {
      var missing = new ArrayList<PriorityBlock>();
      var rollThese = new ArrayList<PriorityBlock>();
      if (rreq == null) {
         return missing;
      }
      for (var stockUpdate : rreq.update) {
         if (stockUpdate == null || stockUpdate.name == null) {
            continue;
         }
         var name = stockUpdate.name;
         for (var stock : stockUpdate.val) {
            if (stock == null || stock.size == null || stock.candle == null) {
               continue;
            }
            var block = new PriorityBlock(stock.size, name);
            var isStale = this.stockHandler.stale_check(block);
            if (isStale) {
               missing.add(block);
            } else {
               this.repo.cache.push_candle(block, stock.candle);
               rollThese.add(block);
            }
         }
      }
      update_data(rollThese, false);
      return missing;
   }

   public void update_data(ArrayList<PriorityBlock> stocks, boolean boot) {
      if (stocks == null || stocks.isEmpty()) {
         return;
      }
      if (boot) {
         for (var stock : stocks) {
            this.loadBalancer.set_boot_ticker(stock.name);
         }
      }
      this.scheduler.UpdateHeap(stocks, boot);
   }

   /** Stops all work for a ticker and drops its cached data. */
   public void remove_ticker(String name) {
      if (name == null) {
         return;
      }
      for (var size : org.Tyche.src.entity.CandleSize.values()) {
         PriorityBlock block = new PriorityBlock(size, name);
         this.repo.cache.remove_candles(block);
         this.stockHandler.remove(block);
      }
      this.loadBalancer.remove_ticker(name);
   }

   public boolean isRunning() {
      return running;
   }
}
